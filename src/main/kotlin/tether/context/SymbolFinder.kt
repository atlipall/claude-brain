package tether.context

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageViewTypeLocation

/**
 * Finds the structural elements around an offset through the file's Structure View model, which every language with a
 * Structure View provides (JavaScript and TypeScript in WebStorm). Call in a read action off the EDT (the JS plugin reads indexes), with the document committed.
 */
object SymbolFinder {
    private const val MAX_DEPTH = 32

    /**
     * The innermost element at [offset] and its function-like container (see [Symbols.pick]); both null without a
     * structure model, and in dumb mode, since structure models may need indexes.
     */
    fun find(file: PsiFile, document: Document, offset: Int): Pair<Symbol?, Symbol?> {
        if (DumbService.isDumb(file.project)) return null to null
        return safely {
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
                ?: return@safely null to null
            var model: StructureViewModel? = null
            try {
                model = builder.createStructureViewModel(null)
                Symbols.pick(path(model.root, file, document, offset))
            } finally {
                model?.let(Disposer::dispose)
            }
        }
    }

    /** Runs [block]; a failing structure model (third-party language plugins can throw anything) means no symbol. */
    internal fun safely(block: () -> Pair<Symbol?, Symbol?>): Pair<Symbol?, Symbol?> =
        try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            logger<SymbolFinder>().debug("No symbol: the structure model failed", e)
            null to null
        }

    /** From the root's children down: at each level the smallest child whose element contains [offset]. */
    private fun path(root: TreeElement, file: PsiFile, document: Document, offset: Int): List<StructureHit> {
        val hits = mutableListOf<StructureHit>()
        var node = root
        repeat(MAX_DEPTH) {
            val (child, psi) = node.children
                .mapNotNull { element -> psiOf(element, file)?.takeIf { it.textRange?.containsOffset(offset) == true }?.let { element to it } }
                .minByOrNull { candidate -> candidate.second.textRange.length }
                ?: return hits
            hit(child, psi, document)?.let(hits::add)
            node = child
        }
        return hits
    }

    /** The element's PSI, only when it lies in [file]: its text range means nothing against another file's offsets. */
    private fun psiOf(element: TreeElement, file: PsiFile): PsiElement? =
        ((element as? StructureViewTreeElement)?.value as? PsiElement)?.takeIf { it.isValid && it.containingFile == file }

    /** The hit for [psi]; its lines span the first and last offset of the element's text range (end offsets are exclusive). */
    private fun hit(element: TreeElement, psi: PsiElement, document: Document): StructureHit? {
        val name = (psi as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() }
            ?: Symbols.nameFromLabel(element.presentation.presentableText)
            ?: return null
        val kind = Symbols.kindWord(ElementDescriptionUtil.getElementDescription(psi, UsageViewTypeLocation.INSTANCE))
        val range = psi.textRange
        val start = range.startOffset.coerceIn(0, document.textLength)
        val lastOffset = (range.endOffset - 1).coerceIn(start, document.textLength)
        val lines = Lines(document.getLineNumber(start) + 1, document.getLineNumber(lastOffset) + 1)
        return StructureHit(Symbol(name, kind, lines), Symbols.isFunctionLike(kind, psi.javaClass.simpleName))
    }
}
