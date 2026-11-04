package tether.ide.tools

import tether.git.canonicalPath
import tether.ide.EditTracker
import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.containers.MultiMap
import java.io.File

/** A renamed symbol ([what] names it as it was) and the files whose text changed. */
data class Renamed(val what: String, val changed: List<VirtualFile>)

/** A moved file at its new place ([now]) and the other files whose text changed (their references to it). */
data class Moved(val now: VirtualFile, val updated: List<VirtualFile>)

/**
 * The IDE's rename and move refactorings, run without dialogs: no preview, and conflicts are reported instead of shown.
 * Only references inside the given scope are updated.
 */
object Refactors {
    /** Renames the symbol [ref] names to [newName], updating its references in [scope]. Pooled thread. */
    fun rename(project: Project, ref: SymbolRef, newName: String, scope: SearchScope, deadline: Deadline): Renamed {
        requireIndexes(project)
        VfsUtil.markDirtyAndRefresh(false, false, false, ref.file)
        val (pointer, what) = deadline.read("Finding the symbol") { renameTarget(project, ref, newName, scope) }
        val changed = deadline.onEdt(project) {
            val element = pointer.element ?: throw ToolUnavailable("`${ref.symbol}` changed before it could be renamed; try again.")
            perform(project) { HeadlessRename(project, element, newName, scope) }
        }
        return Renamed(what, changed)
    }

    /**
     * Moves and/or renames [file] to [destination] (an absolute path the caller has checked), updating references in
     * [scope] (imports and the like). Pooled thread.
     */
    fun move(project: Project, file: VirtualFile, destination: String, scope: SearchScope, deadline: Deadline): Moved {
        requireIndexes(project)
        val target = File(canonicalPath(destination))
        if (target.exists()) throw ToolUnavailable("${target.path} already exists.")
        val changed = deadline.onEdt(project) {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: throw ToolUnavailable("The IDE can't read ${file.name}.")
            val moved = if (target.parent != canonicalPath(file.parent.path)) perform(project) { HeadlessMove(project, psiFile, directory(project, target.parent), scope) } else emptyList()
            if (target.name == file.name) return@onEdt moved
            try {
                moved + perform(project) { HeadlessRename(project, psiFile, target.name, scope) }
            } catch (e: ToolUnavailable) {
                if (moved.isEmpty()) throw e
                throw ToolUnavailable("Moved ${file.name} to ${target.parent}, but renaming it failed. ${e.message}")
            }
        }
        val now = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.path)
            ?: throw ToolUnavailable("The IDE did not move the file (it may have refused); nothing was changed.")
        return Moved(now, changed.distinct().filter { it != now })
    }

    /** The folder [path] as PSI, created if missing. EDT. */
    private fun directory(project: Project, path: String): PsiDirectory {
        val dir = WriteAction.compute<VirtualFile?, Exception> { VfsUtil.createDirectoryIfMissing(path) } ?: throw ToolUnavailable("Could not create $path.")
        return PsiManager.getInstance(project).findDirectory(dir) ?: throw ToolUnavailable("The IDE can't see $path.")
    }

    /** The element to rename (and how to name it), checked: inside [scope], and [newName] a valid name in its language. Read action. */
    private fun renameTarget(project: Project, ref: SymbolRef, newName: String, scope: SearchScope): Pair<SmartPsiElementPointer<PsiElement>, String> {
        val element = Usages.resolve(project, ref)
        val named = element as? PsiNamedElement ?: throw ToolUnavailable("`${ref.symbol}` is not something the IDE can rename.")
        val home = element.containingFile?.virtualFile
        if (home == null || !scope.contains(home)) {
            throw ToolUnavailable("`${ref.symbol}` is declared outside this checkout's project files (a library or built-in?); it can't be renamed.")
        }
        if (named.name == newName) throw ToolUnavailable("`${ref.symbol}` is already called $newName.")
        val validator = LanguageNamesValidation.INSTANCE.forLanguage(element.language)
        if (!validator.isIdentifier(newName, project)) throw ToolUnavailable("`$newName` is not a valid name here.")
        if (validator.isKeyword(newName, project)) throw ToolUnavailable("`$newName` is a keyword.")
        val kind = ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE).takeIf { it.isNotBlank() }
        return SmartPointerManager.createPointer<PsiElement>(element) to listOfNotNull(kind, "`${named.name}`").joinToString(" ")
    }

    /** What a processor run here found in its way; non-empty [texts] means it refused. */
    private class Conflicts {
        var texts: List<String> = emptyList()
            private set

        /** For `showConflicts`: true (go ahead) when there are none. */
        fun accept(conflicts: MultiMap<PsiElement, String>): Boolean {
            texts = conflicts.values().map { StringUtil.removeHtmlTags(it).trim() }.distinct()
            return texts.isEmpty()
        }
    }

    private interface Headless {
        val conflicts: Conflicts
    }

    /**
     * Runs [create]'s refactoring and saves what it changed. EDT, outside modal dialogs.
     *
     * @throws ToolUnavailable with the IDE's conflicts when it refused.
     */
    private fun <P> perform(project: Project, create: () -> P): List<VirtualFile> where P : BaseRefactoringProcessor, P : Headless {
        val processor = create()
        processor.setPreviewUsages(false)
        val changed = EditTracker.getInstance(project).saveChangedBy(processor::run)
        val conflicts = processor.conflicts.texts
        if (conflicts.isNotEmpty()) throw ToolUnavailable("The IDE refused, nothing was changed:\n" + conflicts.joinToString("\n") { "- $it" })
        return changed
    }

    private class HeadlessRename(project: Project, element: PsiElement, newName: String, scope: SearchScope) :
        RenameProcessor(project, element, newName, scope, false, false), Headless {
        override val conflicts = Conflicts()
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?) = this.conflicts.accept(conflicts)
        override fun isPreviewUsages(usages: Array<out UsageInfo>) = false
    }

    private class HeadlessMove(project: Project, file: PsiElement, parent: PsiDirectory, private val scope: SearchScope) :
        MoveFilesOrDirectoriesProcessor(project, arrayOf(file), parent, true, false, false, null, null), Headless {
        override val conflicts = Conflicts()
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?) = this.conflicts.accept(conflicts)
        override fun isPreviewUsages(usages: Array<out UsageInfo>) = false

        /** References outside the chat's checkout (another worktree's copy) are not this move's to update. */
        override fun findUsages(): Array<UsageInfo> =
            super.findUsages().filter { usage -> usage.virtualFile.let { it == null || scope.contains(it) } }.toTypedArray()
    }
}
