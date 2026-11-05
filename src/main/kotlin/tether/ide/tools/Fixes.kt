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
        // Some fixes (often imports) are computed only when asked for.
        deadline.run("Computing the fixes") {
            infos.filter(HighlightInfo::hasLazyQuickFixes).forEach { LazyQuickFixUpdater.getInstance(project).waitQuickFixesSynchronously(it, project, document) }
        }
        val candidates = (ReadAction.compute<List<FixCandidate>, RuntimeException> { infos.flatMap { registeredFixes(document, it) } } +
            ProblemSource.EP.extensionList.flatMap { source ->
                try {
                    source.fixes(project, file, line, deadline)
                } catch (e: ToolUnavailable) {
                    LOG.info("No fixes from ${source.name}: ${e.message}")
                    emptyList()
                }
            }).filter { runsWithoutDialogs(it.action) }
        val fixes = if (candidates.isEmpty()) emptyList() else deadline.onEdt(project) {
            withEditor(project, document) { psiFile, editor ->
                candidates.filter { available(psiFile, editor, it.offset, it.action) }
                    .map { Fix(it.action.text, it.problem, it.action, it.offset) }
                    .distinctBy { it.problem to it.text }
            }
        }
        last = Listed(file, line, stamp, fixes)
        return fixes
    }

    /**
     * Applies the fix on [line] whose text is [text] (or, failing that, the only one containing it), as Alt+Enter
     * would, and saves the files it changed.
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
        editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
        return ShowIntentionActionsHandler.availableFor(psiFile, editor, offset, action)
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
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: throw ToolUnavailable("The IDE can't read this file.")
        val editor = EditorFactory.getInstance().createEditor(document, project)
        try {
            return block(psiFile, editor)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    private val LOG = logger<Fixes>()
}

/** @throws ToolUnavailable when [line] (1-based) is not a line of this document of [file]. */
internal fun Document.requireLine(file: VirtualFile, line: Int) {
    if (line < 1 || line > lineCount) throw ToolUnavailable("${file.name} has $lineCount lines; there is no line $line.")
}
