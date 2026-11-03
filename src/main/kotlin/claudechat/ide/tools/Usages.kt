package claudechat.ide.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.Processor
import java.util.concurrent.Callable

/** A place in a file: [line] is 1-based, [text] is that line, trimmed. */
data class Spot(val file: VirtualFile, val line: Int, val text: String)

/** What [Usages.find] found: the symbol's declarations and the references to it. */
data class FoundUsages(val name: String, val kind: String?, val definitions: List<Spot>, val usages: List<Spot>, val total: Int = usages.size)

/** Finds a symbol's references through the IDE's index (what Find Usages uses), not by text. */
object Usages {
    /** Longest line text shown for a usage. */
    private const val MAX_LINE = 200

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * The 0-based index of [symbol] as a whole word in [lineText]; the occurrence nearest 1-based [column] when given,
     * else the first. Null when it is not on the line.
     */
    fun locate(lineText: String, symbol: String, column: Int?): Int? {
        if (symbol.isEmpty()) return null
        val hits = generateSequence(lineText.indexOf(symbol)) { i -> lineText.indexOf(symbol, i + 1).takeIf { it >= 0 } }
            .takeWhile { it >= 0 }
            .filter { i ->
                (i == 0 || !isWordChar(lineText[i - 1])) &&
                    (i + symbol.length >= lineText.length || !isWordChar(lineText[i + symbol.length]))
            }
            .toList()
        if (hits.isEmpty()) return null
        return if (column == null) hits.first() else hits.minBy { kotlin.math.abs(it - (column - 1)) }
    }

    /**
     * The declarations of and references to [symbol] on 1-based [line] of [file], within [scope]. [FoundUsages.usages]
     * holds at most [UsageReport.MAX_USAGES]; [FoundUsages.total] counts them all. Call on a pooled thread.
     *
     * @throws ToolUnavailable (with a message for Claude) when the symbol can't be found or resolved, while
     * indexing, or when the search did not finish by [deadline].
     */
    fun find(project: Project, file: VirtualFile, line: Int, symbol: String, column: Int?, scope: GlobalSearchScope, deadline: Deadline): FoundUsages {
        requireIndexes(project)
        VfsUtil.markDirtyAndRefresh(false, false, false, file)
        return deadline.run("The search") {
            // Non-blocking: a long search must not hold up the user's typing; it restarts after their edits.
            // The platform logs exceptions thrown inside as errors, so "not found" comes out as a Result.
            ReadAction.nonBlocking(Callable {
                try { Result.success(search(project, file, line, symbol, column, scope)) } catch (e: ToolUnavailable) { Result.failure(e) }
            }).executeSynchronously()
        }.getOrThrow()
    }

    private fun search(project: Project, file: VirtualFile, line: Int, symbol: String, column: Int?, scope: GlobalSearchScope): FoundUsages {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: throw ToolUnavailable("The IDE can't read ${file.name}.")
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: throw ToolUnavailable("The IDE can't read ${file.name}.")
        if (line < 1 || line > document.lineCount) throw ToolUnavailable("${file.name} has ${document.lineCount} lines; there is no line $line.")
        val index = locate(document.lineText(line - 1), symbol, column)
            ?: throw ToolUnavailable("`$symbol` is not on line $line of ${file.name}.")
        val target = targetAt(psiFile, document.getLineStartOffset(line - 1) + index)
            ?: throw ToolUnavailable("The IDE does not resolve `$symbol` on line $line to a declaration.")
        val declaration = target.navigationElement.takeIf { it.isValid } ?: target
        val definitions = listOfNotNull(spotOf(declaration, null)).filter { scope.contains(it.file) }
        var total = 0
        val usages = LinkedHashSet<Spot>()
        ReferencesSearch.search(target, scope).forEach(Processor { ref ->
            total++
            // Only the shown ones are worth a document and a line of text.
            if (usages.size < UsageReport.MAX_USAGES) {
                spotOf(ref.element, ref.element.textRange.startOffset + ref.rangeInElement.startOffset)?.let(usages::add)
            }
            true
        })
        val name = (target as? PsiNamedElement)?.name ?: symbol
        val kind = ElementDescriptionUtil.getElementDescription(target, UsageViewTypeLocation.INSTANCE).takeIf { it.isNotBlank() }
        return FoundUsages(name, kind, definitions, usages.sortedWith(compareBy({ it.file.path }, { it.line })), total)
    }

    /** The reference at [offset], resolved; else the declaration whose name is at [offset]. */
    private fun targetAt(file: PsiFile, offset: Int): PsiElement? {
        val reference = file.findReferenceAt(offset)
        val resolved = when (reference) {
            null -> null
            is PsiPolyVariantReference -> reference.multiResolve(false).firstNotNullOfOrNull { it.element?.takeIf(PsiElement::isValid) }
            else -> reference.resolve()
        }
        if (resolved != null) return resolved
        var element: PsiElement? = file.findElementAt(offset)
        repeat(4) {
            val e = element ?: return null
            if (e is PsiNameIdentifierOwner && e.nameIdentifier?.textRange?.containsOffset(offset) == true) return e
            element = e.parent
        }
        return null
    }

    /** Where [element] (or [offset] in its file) is; null for elements outside real files (e.g. built-in declarations). */
    private fun spotOf(element: PsiElement, offset: Int?): Spot? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val at = (offset ?: ((element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element).textRange.startOffset)
            .coerceIn(0, document.textLength)
        val line = document.getLineNumber(at)
        val text = document.lineText(line).trim()
        return Spot(file, line + 1, if (text.length > MAX_LINE) text.take(MAX_LINE) + "\u2026" else text)
    }

    private fun Document.lineText(line: Int) = getText(TextRange(getLineStartOffset(line), getLineEndOffset(line)))
}

/** Text Claude reads about found usages. */
object UsageReport {
    const val MAX_USAGES = 200

    /** [path] names a file as the chat does. */
    fun format(found: FoundUsages, path: (VirtualFile) -> String): String = buildString {
        val what = listOfNotNull(found.kind, "`${found.name}`").joinToString(" ")
        when (found.definitions.size) {
            0 -> append("$what is declared outside the project.")
            else -> append("$what is declared at ").append(found.definitions.joinToString(", ") { "${path(it.file)}:${it.line}" }).append('.')
        }
        append('\n')
        val usages = found.usages
        if (usages.isEmpty()) { append("No usages found in this checkout."); return@buildString }
        val total = found.total
        append("$total usage${if (total == 1) "" else "s"}")
        append(if (total > usages.size) " (the first ${usages.size}):" else ":")
        for ((file, spots) in usages.groupBy { it.file }) {
            append("\n").append(path(file))
            for (s in spots) append("\n  ${s.line}: ${s.text}")
        }
    }
}
