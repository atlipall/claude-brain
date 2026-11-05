package tether.ide.tools

import tether.ide.EditTracker
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.codeInsight.quickfix.LazyQuickFixUpdater
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModShowConflicts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** A fix the IDE offers for [problem]: its Alt+Enter [action], to be invoked with the caret at [offset]. */
class Fix(val text: String, val problem: Problem, internal val action: IntentionAction, internal val offset: Int)

/** What [Fixes.apply] did: the files it changed. */
data class Applied(val fix: String, val changed: List<VirtualFile>)

/** The IDE's quick fixes (what Alt+Enter offers on a highlighted problem), listed and applied without an open editor. */
object Fixes {
    /** The latest [list]: apply_fix nearly always follows the fixes tool on the same, unchanged line. */
    private class Listed(val file: VirtualFile, val line: Int, val stamp: Long, val fixes: List<Fix>)
    @Volatile private var last: Listed? = null

    /** The fixes the IDE offers for the problems on 1-based [line] of [file]. Pooled thread. */
    fun list(project: Project, file: VirtualFile, line: Int, deadline: Deadline): List<Fix> {
        val document = documentOf(file)
        val stamp = document.modificationStamp
        val range = ReadAction.compute<IntRange, RuntimeException> {
            document.requireLine(file, line)
            document.getLineStartOffset(line - 1)..document.getLineEndOffset(line - 1)
        }
        val infos = Diagnostics.highlights(project, listOf(file), HighlightSeverity.WEAK_WARNING, deadline)[document].orEmpty()
            .filter { it.startOffset in range }
        computeLazyFixes(project, document, infos, deadline)
        val candidates = (ReadAction.compute<List<FixCandidate>, RuntimeException> { infos.flatMap { registeredFixes(document, it) } } +
            ProblemSource.EP.extensionList.flatMap { source ->
                try {
                    source.fixes(project, file, line, deadline)
                } catch (e: ToolUnavailable) {
                    LOG.info("No fixes from ${source.name}: ${e.message}")
                    emptyList()
                }
            }).filter { runsWithoutDialogs(it.action) }
        val fixes = availableOf(project, document, candidates, deadline)
            .map { Fix(it.action.text, it.problem, it.action, it.offset) }
            .distinctBy { it.problem to it.text }
        last = Listed(file, line, stamp, fixes)
        return fixes
    }

    /**
     * The [candidates] that apply as Alt+Enter would judge them, in their order. Like the IDE's own intentions pass,
     * the checks run in a background read action, so a slow one (TypeScript's imports) doesn't freeze the IDE; only
     * moving the hidden editor's caret to each problem, which the checks read, happens on the EDT. When time runs out
     * (or a dialog takes the EDT) part way, the ones found to apply so far are returned. An edit made while it runs
     * can leave the caret off the problem it was placed for; the list is then judged as of before the edit. Pooled
     * thread.
     *
     * @throws ToolUnavailable when the file can't be read, the EDT can't be had to make the editor, or time ran out
     *   before any was found to apply.
     */
    private fun availableOf(project: Project, document: Document, candidates: List<FixCandidate>, deadline: Deadline): List<FixCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return withEditorOffEdt(project, document, deadline) { psiFile, editor ->
            val applicable = HashSet<FixCandidate>()
            try {
                for ((offset, atOffset) in candidates.groupBy { it.offset }) {
                    deadline.onEdt(project) { placeCaret(editor, offset) }
                    applicable += deadline.read("Checking the fixes") {
                        atOffset.filter { ShowIntentionActionsHandler.availableFor(psiFile, editor, offset, it.action) }
                    }
                }
            } catch (e: ToolUnavailable) {
                if (applicable.isEmpty()) throw e
                LOG.info("Listing only the fixes checked in time: ${e.message}")
            }
            candidates.filter { it in applicable }
        }
    }

    /** Waits for the fixes of [infos] that are computed only when asked for (often imports). */
    private fun computeLazyFixes(project: Project, document: Document, infos: List<HighlightInfo>, deadline: Deadline) {
        deadline.run("Computing the fixes") {
            infos.filter(HighlightInfo::hasLazyQuickFixes).forEach { LazyQuickFixUpdater.getInstance(project).waitQuickFixesSynchronously(it, project, document) }
        }
    }

    /**
     * Applies the fix on [line] whose text is [text] (or, failing that, the only one containing it), as Alt+Enter
     * would, and saves the files it changed. Unlike [list], this checks on the EDT whether the fix still applies:
     * `ShowIntentionActionsHandler.chooseActionAndInvoke` judges that there again anyway, so a check in the background
     * first would only add to it.
     *
     * @throws ToolUnavailable when there is no such fix, it no longer applies, or it changed nothing.
     */
    fun apply(project: Project, file: VirtualFile, line: Int, text: String, deadline: Deadline): Applied {
        val document = documentOf(file)
        val fixes = last?.takeIf { it.file == file && it.line == line && it.stamp == document.modificationStamp }?.fixes
            ?: list(project, file, line, deadline)
        val fix = fixes.firstOrNull { it.text == text }
            ?: fixes.singleOrNull { it.text.contains(text, ignoreCase = true) }
            ?: throw ToolUnavailable(
                "No fix “$text” on line $line of ${file.name}. " +
                    if (fixes.isEmpty()) "The IDE offers none there." else "It offers: " + fixes.joinToString(", ") { "“${it.text}”" }
            )
        val changed = deadline.onEdt(project) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
            withEditor(project, document) { psiFile, editor ->
                if (!available(psiFile, editor, fix.offset, fix.action)) throw ToolUnavailable("“${fix.text}” no longer applies; list the fixes again.")
                if (asksToChoose(psiFile, editor, fix.action)) {
                    throw ToolUnavailable("“${fix.text}” asks a question (pick an option, or confirm conflicts), which this tool can't answer. Make this edit by hand.")
                }
                EditTracker.getInstance(project).saveChangedBy {
                    ShowIntentionActionsHandler.chooseActionAndInvoke(psiFile, editor, fix.action, fix.text)
                }
            }
        }
        last = null
        if (changed.isEmpty()) throw ToolUnavailable("“${fix.text}” made no change. Make this edit by hand.")
        return Applied(fix.text, changed)
    }

    /**
     * Fixes that run in a write action can't open a dialog, and inspections' quick fixes are made to run in batch
     * without one. A ModCommand only asks something through its command, which [apply] checks first (see
     * [asksToChoose]). Other intentions (create a class, change a signature) may open a dialog, which would block the
     * IDE until someone answers it.
     */
    private fun runsWithoutDialogs(action: IntentionAction) =
        action.startInWriteAction() || action.asModCommandAction() != null || action is LocalQuickFix || QuickFixWrapper.unwrap(action) != null

    /**
     * Whether [action] is a ModCommand that would ask something (a chooser, or a conflicts dialog): on the hidden editor
     * nobody could answer a chooser, and the dialog would block the IDE until someone did. Computes the command, which is how the IDE finds out too. EDT, caret placed.
     */
    private fun asksToChoose(psiFile: PsiFile, editor: Editor, action: IntentionAction): Boolean {
        val modCommand = action.asModCommandAction() ?: return false
        val command = ReadAction.compute<ModCommand, RuntimeException> { modCommand.perform(ActionContext.from(editor, psiFile)) }
        return command.unpack().any { it is ModChooseAction || it is ModShowConflicts }
    }

    /** Whether [action] applies with the caret at [offset], as Alt+Enter would judge it. EDT. */
    private fun available(psiFile: PsiFile, editor: Editor, offset: Int, action: IntentionAction): Boolean {
        placeCaret(editor, offset)
        return ShowIntentionActionsHandler.availableFor(psiFile, editor, offset, action)
    }

    /** Puts [editor]'s caret at [offset], where Alt+Enter would be pressed. EDT. */
    private fun placeCaret(editor: Editor, offset: Int) {
        editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
    }

    private fun registeredFixes(document: Document, info: HighlightInfo): List<FixCandidate> {
        val problem = Diagnostics.problemOf(document, info) ?: return emptyList()
        val actions = mutableListOf<IntentionAction>()
        info.findRegisteredQuickFix<Unit> { descriptor, _ -> actions += descriptor.action; null }
        return actions.map { FixCandidate(problem, info.startOffset, it) }
    }

    private fun documentOf(file: VirtualFile): Document =
        ReadAction.compute<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file) }
            ?: throw ToolUnavailable("The IDE can't read ${file.name}.")

    /** Runs [block] with an editor (never shown) on [document]: fixes decide whether they apply from one. EDT. */
    private fun <T> withEditor(project: Project, document: Document, block: (PsiFile, Editor) -> T): T {
        val psiFile = psiFileOf(project, document)
        val editor = EditorFactory.getInstance().createEditor(document, project)
        try {
            return block(psiFile, editor)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    /**
     * Runs [block] on this pooled thread with an editor (never shown) on [document], created and released on the EDT.
     * When the EDT can't be had in time to release it, it is released as soon as it can be.
     */
    private fun <T> withEditorOffEdt(project: Project, document: Document, deadline: Deadline, block: (PsiFile, Editor) -> T): T {
        val (psiFile, editor) = deadline.onEdt(project) { psiFileOf(project, document) to EditorFactory.getInstance().createEditor(document, project) }
        try {
            return block(psiFile, editor)
        } finally {
            val release = { EditorFactory.getInstance().releaseEditor(editor) }
            try {
                deadline.onEdt(project, release)
            } catch (e: ToolUnavailable) {
                ApplicationManager.getApplication().invokeLater(release, ModalityState.any())
            }
        }
    }

    private fun psiFileOf(project: Project, document: Document): PsiFile =
        PsiDocumentManager.getInstance(project).getPsiFile(document) ?: throw ToolUnavailable("The IDE can't read this file.")

    private val LOG = logger<Fixes>()
}

/** @throws ToolUnavailable when [line] (1-based) is not a line of this document of [file]. */
internal fun Document.requireLine(file: VirtualFile, line: Int) {
    if (line < 1 || line > lineCount) throw ToolUnavailable("${file.name} has $lineCount lines; there is no line $line.")
}
