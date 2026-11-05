package tether.cli

import com.intellij.openapi.diagnostic.logger
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The command line and the extra environment to run claude with. */
class Launch(val command: List<String>, val env: Map<String, String>)

/**
 * Runs the claude CLI and exchanges NDJSON over its stdin/stdout. [start] returns at once: [prepare] (which may block,
 * e.g. on the IDE's shell environment) and the spawn run on a thread of their own, and lines sent meanwhile are queued
 * and written once the process is up. When [prepare] or the spawn fails, `onExit` gets [CliConnection.LAUNCH_FAILED]
 * and the error message as the tail.
 */
class ClaudeProcess(private val workDir: File, private val prepare: () -> Launch) : CliConnection {
    constructor(command: List<String>, workDir: File, env: Map<String, String>) : this(workDir, { Launch(command, env) })

    @Volatile private var process: Process? = null

    /** Lines waiting for the stdin writer thread; null until start(), after the writer exits and after a failed launch. */
    @Volatile private var outbox: LinkedBlockingQueue<Any>? = null
    private val stopped = AtomicBoolean(false)

    /** The stdin writer thread; set on the launch thread once the process is up. Exposed internally so tests can confirm it ends. */
    @Volatile internal var stdinThread: Thread? = null

    override fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit) {
        val queue = LinkedBlockingQueue<Any>()
        outbox = queue
        thread(isDaemon = true, name = "claude-launch") {
            val p = try {
                spawn(prepare())
            } catch (e: Throwable) {
                stopped.set(true)
                outbox = null
                onExit(CliConnection.LAUNCH_FAILED, e.message ?: e.toString())
                return@thread
            }
            process = p
            if (stopped.get()) terminate(p)
            run(p, queue, onLine, onExit)
        }
    }

    private fun spawn(launch: Launch): Process {
        val builder = ProcessBuilder(launch.command).directory(workDir)
        val childEnv = childEnvironment(builder.environment(), launch.env)
        builder.environment().apply { clear(); putAll(childEnv) }
        return builder.start()
    }

    /** Pumps [p]'s pipes until it exits; the stdin writer drains [queue], lines sent before the spawn included. */
    private fun run(p: Process, queue: LinkedBlockingQueue<Any>, onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit) {
        val stdin = p.outputStream.bufferedWriter(Charsets.UTF_8)
        stdinThread = thread(isDaemon = true, name = "claude-stdin") { writeLoop(stdin, queue) }

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

    /** Never blocks the caller: the line is queued for the stdin writer thread, also before the process is up. */
    override fun send(line: String) {
        if (stopped.get()) return
        outbox?.offer(line)
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        outbox?.offer(CLOSE)
        process?.let(::terminate)
    }

    /** Asks [p] to end, and kills it if it hasn't within 3 seconds. */
    private fun terminate(p: Process) {
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
