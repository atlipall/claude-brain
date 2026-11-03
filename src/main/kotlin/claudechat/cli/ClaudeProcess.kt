package claudechat.cli

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the claude CLI and exchanges NDJSON over its stdin/stdout. */
class ClaudeProcess(
    private val command: List<String>,
    private val workDir: File,
    private val env: Map<String, String>,
) : CliConnection {
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    override fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit) {
        val builder = ProcessBuilder(command).directory(workDir)
        builder.environment().putAll(env)
        val p = builder.start()
        process = p
        writer = p.outputStream.bufferedWriter(Charsets.UTF_8)

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
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
            } catch (_: IOException) {
                // stream closed by stop()
            }
            val code = p.waitFor()
            stderrThread.join(1000)
            onExit(code, synchronized(tail) { tail.joinToString("\n") })
        }
    }

    @Synchronized
    override fun send(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (_: IOException) {
            // process is gone; the exit callback reports it
        }
    }

    override fun stop() {
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        val p = process ?: return
        p.destroy()
        thread(isDaemon = true, name = "claude-reaper") {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }
}
