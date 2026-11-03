package claudechat.ide.tools

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import com.intellij.openapi.application.ReadAction

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

    /**
     * Runs [block] on the EDT, outside any modal dialog (write-safe), and waits for it. If it has not started by the
     * deadline it never will (a dialog was open); once started it is waited for to the end, however long it takes, so
     * a change is never made behind the caller's back.
     *
     * @throws ToolUnavailable when it could not start by the deadline.
     */
    fun <T> onEdt(project: Project, block: () -> T): T {
        val result = CompletableFuture<T>()
        val state = AtomicInteger(QUEUED)
        ApplicationManager.getApplication().invokeLater({
            if (!state.compareAndSet(QUEUED, RUNNING)) return@invokeLater
            try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) }
        }, ModalityState.nonModal(), project.disposed)
        try {
            return try {
                result.get(remainingMs(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (state.compareAndSet(QUEUED, ABANDONED)) throw ToolUnavailable("The IDE is busy (is a dialog open?); try again in a moment.")
                result.get()
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Runs [block] in a non-blocking read action (restarted after the user's edits rather than holding them up) until
     * the deadline. A [ToolUnavailable] it throws comes out as is (the platform would log it as an error otherwise).
     */
    fun <T> read(what: String, block: () -> T): T = run(what) {
        ReadAction.nonBlocking(Callable {
            try { Result.success(block()) } catch (e: ToolUnavailable) { Result.failure(e) }
        }).executeSynchronously()
    }.getOrThrow()

    /** [future]'s value. @throws ToolUnavailable naming [what] when it failed or did not finish by the deadline (it is then cancelled). */
    fun <T> await(future: CompletableFuture<T>, what: String): T =
        try {
            future.get(remainingMs(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw ToolUnavailable("$what did not answer within $seconds seconds")
        } catch (e: ExecutionException) {
            throw ToolUnavailable("$what failed (${e.cause?.message ?: e.message})")
        }

    private companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val ABANDONED = 2
    }
}

/** @throws ToolUnavailable while the IDE is indexing: its analysis and searches need the indexes. */
fun requireIndexes(project: Project) {
    if (DumbService.isDumb(project)) throw ToolUnavailable("The IDE is indexing; try again in a moment.")
}
