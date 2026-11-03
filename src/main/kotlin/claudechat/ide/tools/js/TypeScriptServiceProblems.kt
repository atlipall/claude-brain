package claudechat.ide.tools.js

import claudechat.ide.tools.Problem
import claudechat.ide.tools.ProblemSource
import claudechat.ide.tools.Severity
import com.intellij.lang.javascript.integration.JSAnnotationError
import com.intellij.lang.typescript.compiler.TypeScriptService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.concurrent.CompletableFuture

/** The TypeScript service's errors for a file (type errors and the like), which the editor shows from its own pass. */
class TypeScriptServiceProblems : ProblemSource {
    override val name = "the TypeScript service"

    override fun start(project: Project, file: VirtualFile): CompletableFuture<List<Problem>>? {
        val pending = ReadAction.compute<CompletableFuture<List<JSAnnotationError>>?, RuntimeException> {
            if (project.isDisposed) return@compute null
            val service = TypeScriptService.getForFile(project, file) ?: return@compute null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            if (service.canHighlight(psi)) service.highlight(psi) else null
        } ?: return null
        return pending.thenApply { errors -> errors.orEmpty().mapNotNull { problemOf(it, file) } }
    }

    private fun problemOf(error: JSAnnotationError, file: VirtualFile): Problem? {
        // The service may report problems in other files (e.g. a broken tsconfig); keep this file's only.
        error.absoluteFilePath?.let { if (it != file.path) return null }
        val message = error.description?.takeIf { it.isNotBlank() } ?: return null
        val severity = Severity.of(error.severity) ?: return null
        // The service counts lines and columns from 0.
        return Problem(error.line + 1, error.column + 1, severity, message)
    }
}
