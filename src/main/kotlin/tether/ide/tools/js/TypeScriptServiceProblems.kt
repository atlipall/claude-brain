package tether.ide.tools.js

import tether.ide.tools.Deadline
import tether.ide.tools.FixCandidate
import tether.ide.tools.Problem
import tether.ide.tools.ProblemSource
import tether.ide.tools.Severity
import com.intellij.lang.javascript.integration.JSAnnotationError
import com.intellij.lang.typescript.compiler.TypeScriptService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.util.concurrent.CompletableFuture

/** The TypeScript service's errors for a file (type errors and the like), which the editor shows from its own pass. */
class TypeScriptServiceProblems : ProblemSource {
    override val name = "the TypeScript service"

    override fun start(project: Project, file: VirtualFile): CompletableFuture<List<Problem>>? =
        highlight(project, file)?.thenApply { errors -> errors.mapNotNull { problemOf(it) } }

    override fun fixes(project: Project, file: VirtualFile, line: Int, deadline: Deadline): List<FixCandidate> {
        val pending = highlight(project, file) ?: return emptyList()
        val errors = deadline.await(pending, name).filter { it.line == line - 1 }
        if (errors.isEmpty()) return emptyList()
        // The service may be asked for its fixes here: a non-blocking read doesn't hold up the user's typing meanwhile.
        return deadline.read("Asking $name for fixes") {
            val service = TypeScriptService.getForFile(project, file) ?: return@read emptyList()
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@read emptyList()
            val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@read emptyList()
            errors.flatMap { error ->
                val problem = problemOf(error) ?: return@flatMap emptyList()
                val offset = (document.getLineStartOffset(error.line) + error.column).coerceIn(0, document.textLength)
                val element = psi.findElementAt(offset) ?: return@flatMap emptyList()
                service.getServiceFixes(psi, element, error).map { FixCandidate(problem, offset, it) }
            }
        }
    }

    /** The service's errors for [file] (this file's only), or null when the service doesn't handle it. */
    private fun highlight(project: Project, file: VirtualFile): CompletableFuture<List<JSAnnotationError>>? =
        ReadAction.compute<CompletableFuture<List<JSAnnotationError>>?, RuntimeException> {
            if (project.isDisposed) return@compute null
            val service = TypeScriptService.getForFile(project, file) ?: return@compute null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            if (service.canHighlight(psi)) service.highlight(psi) else null
        }?.thenApply { errors -> errors.orEmpty().filter { it.absoluteFilePath.let { p -> p == null || p == file.path } } }

    private fun problemOf(error: JSAnnotationError): Problem? {
        val message = error.description?.takeIf { it.isNotBlank() } ?: return null
        val severity = Severity.of(error.severity) ?: return null
        // The service counts lines and columns from 0.
        return Problem(error.line + 1, error.column + 1, severity, message)
    }
}
