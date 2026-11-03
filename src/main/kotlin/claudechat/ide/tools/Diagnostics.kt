package claudechat.ide.tools

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.MainPassesRunner
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs the IDE's own highlighting (the passes the editor runs, inspections included) on files, open or not. */
object Diagnostics {
    /**
     * What a check of [files] finds at [minSeverity] or worse, by file: the main passes plus every [ProblemSource].
     * Picks up changes made on disk first. Call on a pooled thread, never the EDT or inside a read action.
     *
     * @throws ToolUnavailable while the IDE is indexing, or when the main passes did not finish by [deadline].
     */
    fun collect(project: Project, files: List<VirtualFile>, minSeverity: HighlightSeverity, deadline: Deadline): Map<VirtualFile, Checked> {
        if (files.isEmpty()) return emptyMap()
        requireIndexes(project)
        // Claude writes files behind the IDE's back: load the new text, then let PSI see it.
        VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
        // Sources such as the TypeScript service work in their own process: start them first, collect them last.
        val pending = files.associateWith { file ->
            ProblemSource.EP.extensionList.mapNotNull { source -> source.start(project, file)?.let { source.name to it } }
        }
        try {
            commit(project, files, deadline)
            val byDocument = deadline.run("The IDE's check") {
                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
                MainPassesRunner(project, "Tether: checking files", profile).runMainPasses(files, minSeverity)
            }
            val main = ReadAction.compute<Map<VirtualFile, List<Problem>>, RuntimeException> {
                byDocument.entries.mapNotNull { (document, infos) ->
                    FileDocumentManager.getInstance().getFile(document)?.let { it to problems(document, infos, minSeverity) }
                }.toMap()
            }
            val worst = Severity.of(minSeverity) ?: Severity.WEAK_WARNING
            return files.associateWith { file ->
                val (extra, note) = await(pending[file].orEmpty(), deadline)
                // A source may repeat what a pass found (WebStorm checks some TypeScript itself).
                val all = (main[file].orEmpty() + extra.filter { it.severity <= worst }).distinctBy { Triple(it.line, it.severity, it.message) }
                Checked(all, note)
            }
        } finally {
            pending.values.flatten().forEach { it.second.cancel(true) } // no-op for the finished ones
        }
    }

    /** The sources' problems, and a note when some of them failed or ran out of time. */
    private fun await(futures: List<Pair<String, CompletableFuture<List<Problem>>>>, deadline: Deadline): Pair<List<Problem>, String?> {
        val notes = mutableListOf<String>()
        val problems = futures.flatMap { (name, future) ->
            try {
                future.get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                notes += "$name did not answer within ${deadline.seconds} seconds; its problems are missing"
                emptyList()
            } catch (e: ExecutionException) {
                LOG.info("Problem source failed: $name", e.cause)
                notes += "$name failed (${e.cause?.message ?: e.message}); its problems are missing"
                emptyList()
            }
        }
        return problems to notes.joinToString("; ").ifEmpty { null }
    }

    /** Commits the files' uncommitted documents on the EDT and waits, until [deadline]: a modal dialog holds it back. */
    private fun commit(project: Project, files: List<VirtualFile>, deadline: Deadline) {
        val psiDocuments = PsiDocumentManager.getInstance(project)
        val documents = files.mapNotNull { FileDocumentManager.getInstance().getCachedDocument(it) }.filterNot(psiDocuments::isCommitted)
        if (documents.isEmpty()) return
        val done = CompletableFuture<Unit>()
        ApplicationManager.getApplication().invokeLater({
            documents.forEach(psiDocuments::commitDocument)
            done.complete(Unit)
        }, ModalityState.nonModal(), project.disposed)
        try {
            done.get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw ToolUnavailable("The IDE is busy (is a dialog open?); try again in a moment.")
        }
    }

    private fun problems(document: Document, infos: List<HighlightInfo>, minSeverity: HighlightSeverity): List<Problem> =
        infos.mapNotNull { info ->
            val message = info.description?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (info.severity < minSeverity) return@mapNotNull null
            val severity = Severity.of(info.severity) ?: return@mapNotNull null
            val offset = info.startOffset.coerceIn(0, document.textLength)
            val line = document.getLineNumber(offset)
            Problem(line + 1, offset - document.getLineStartOffset(line) + 1, severity, message)
        }.distinct()

    private val LOG = logger<Diagnostics>()
}
