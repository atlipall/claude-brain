package claudechat.context

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Disposer
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageViewTypeLocation

/**
 * Finds the structural elements around an offset through the file's Structure View model, which every language with a
 * Structure View provides (JavaScript and TypeScript in WebStorm). Call in a read action, with the document committed.
 */
object SymbolFinder {
    private const val MAX_DEPTH = 32

    /** The innermost element at [offset] and its function-like container (see [Symbols.pick]); both null without a structure model. */
    fun find(file: PsiFile, document: Document, offset: Int): Pair<Symbol?, Symbol?> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
            ?: return null to null
        val model = builder.createStructureViewModel(null)
        return try {
            Symbols.pick(path(model.root, document, offset))
        } catch (_: IndexNotReadyException) {
            null to null // some structure models need indexes; while indexing there is no symbol
        } finally {
            Disposer.dispose(model)
        }
    }

    /** From the root's children down: at each level the smallest child whose element contains [offset]. */
    private fun path(root: TreeElement, document: Document, offset: Int): List<StructureHit> {
        val hits = mutableListOf<StructureHit>()
        var node = root
        repeat(MAX_DEPTH) {
            val (child, psi) = node.children
                .mapNotNull { element -> psiOf(element)?.takeIf { it.textRange?.containsOffset(offset) == true }?.let { element to it } }
                .minByOrNull { candidate -> candidate.second.textRange.length }
                ?: return hits
            hit(child, psi, document)?.let(hits::add)
            node = child
        }
        return hits
    }

    private fun psiOf(element: TreeElement): PsiElement? =
        ((element as? StructureViewTreeElement)?.value as? PsiElement)?.takeIf { it.isValid }

    private fun hit(element: TreeElement, psi: PsiElement, document: Document): StructureHit? {
        val name = (psi as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() }
            ?: Symbols.nameFromLabel(element.presentation.presentableText)
            ?: return null
        val kind = Symbols.kindWord(ElementDescriptionUtil.getElementDescription(psi, UsageViewTypeLocation.INSTANCE))
        val range = psi.textRange
        val start = range.startOffset.coerceIn(0, document.textLength)
        val last = (range.endOffset - 1).coerceIn(start, document.textLength) // end offsets are exclusive
        val lines = Lines(document.getLineNumber(start) + 1, document.getLineNumber(last) + 1)
        return StructureHit(Symbol(name, kind, lines), Symbols.isFunctionLike(kind, psi.javaClass.simpleName))
    }
}
