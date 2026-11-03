package claudechat.cli

import com.intellij.openapi.diagnostic.logger
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Runs the claude CLI and exchanges NDJSON over its stdin/stdout. */
class ClaudeProcess(
    private val command: List<String>,
    private val workDir: File,
    private val env: Map<String, String>,
) : CliConnection {
    private var process: Process? = null

    /** Lines waiting for the stdin writer thread; null until start() and after the writer exits. */
    @Volatile private var outbox: LinkedBlockingQueue<Any>? = null
    private val stopped = AtomicBoolean(false)

    /** The stdin writer thread; set by start(). Exposed internally so tests can confirm it ends. */
    @Volatile internal var stdinThread: Thread? = null

    override fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit) {
        val builder = ProcessBuilder(command).directory(workDir)
        val childEnv = childEnvironment(builder.environment(), env)
        builder.environment().apply { clear(); putAll(childEnv) }
        val p = builder.start()
        process = p

        val queue = LinkedBlockingQueue<Any>()
        val stdin = p.outputStream.bufferedWriter(Charsets.UTF_8)
        stdinThread = thread(isDaemon = true, name = "claude-stdin") { writeLoop(stdin, queue) }
        outbox = queue

        val tail = ArrayDeque<String>()
        val stderrThread = thread(isDaemon = true, name = "claude-stderr") {
            p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                synchronized(tail) {
                    tail.addLast(line)
                    if (tail.size > 40) tail.removeFirst()
                }
            }
        }
        thread(isDaemon = true, name = "claude-stdout") {
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    try {
                        onLine(line)
                    } catch (e: Exception) {
                        logger<ClaudeProcess>().warn("Listener failed on a claude output line", e)
                    }
                }
            } catch (_: IOException) {
                // stream closed by stop()
            }
            val code = p.waitFor()
            stderrThread.join(1000)
            // The process is gone: refuse further sends and wake the writer so it ends.
            stopped.set(true)
            outbox?.offer(CLOSE)
            onExit(code, synchronized(tail) { tail.joinToString("\n") })
        }
    }

    /** Sole writer to stdin. Lines are written in queue order; CLOSE ends the loop. */
    private fun writeLoop(w: BufferedWriter, queue: LinkedBlockingQueue<Any>) {
        try {
            while (true) {
                val item = queue.take()
                if (item === CLOSE) break
                w.write(item as String)
                w.write("\n")
                w.flush()
            }
        } catch (_: IOException) {
            // process is gone; the exit callback reports it
        } finally {
            outbox = null
            runCatching { w.close() }
        }
    }

    /** Never blocks the caller: the line is queued for the stdin writer thread. */
    override fun send(line: String) {
        if (stopped.get()) return
        outbox?.offer(line)
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        outbox?.offer(CLOSE)
        val p = process ?: return
        p.destroy()
        thread(isDaemon = true, name = "claude-reaper") {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    companion object {
        private val CLOSE = Any()

        /** Variables that would make claude bill an API account instead of the user's logged-in subscription. */
        private val STRIPPED = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

        internal fun childEnvironment(base: Map<String, String>, extra: Map<String, String>): Map<String, String> =
            (base + extra) - STRIPPED.toSet()
    }
}
