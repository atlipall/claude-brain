package claudechat.ide.tools

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/** Why an IDE tool has no answer; the message is for Claude. */
class ToolUnavailable(message: String) : Exception(message)

/** One time limit shared by every step of a tool call, so the steps' waits don't add up. */
class Deadline(timeoutMs: Long) {
    private val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val seconds = timeoutMs / 1000

    fun remainingMs(): Long = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()).coerceAtLeast(0)

    /**
     * Runs [block] under a progress indicator that is cancelled when the deadline passes.
     *
     * @throws ToolUnavailable "[what] did not finish …" when it was.
     */
    fun <T> run(what: String, block: () -> T): T {
        val indicator = ProgressIndicatorBase()
        val timer = AppExecutorUtil.getAppScheduledExecutorService().schedule({ indicator.cancel() }, remainingMs(), TimeUnit.MILLISECONDS)
        try {
            return ProgressManager.getInstance().runProcess(Computable(block), indicator)
        } catch (e: ProcessCanceledException) {
            if (!indicator.isCanceled) throw e
            throw ToolUnavailable("$what did not finish within $seconds seconds.")
        } finally {
            timer.cancel(false)
        }
    }
}

/** @throws ToolUnavailable while the IDE is indexing: its analysis and searches need the indexes. */
fun requireIndexes(project: Project) {
    if (DumbService.isDumb(project)) throw ToolUnavailable("The IDE is indexing; try again in a moment.")
}
