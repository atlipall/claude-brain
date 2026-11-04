package claudechat.git

import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Runs git with these arguments in a folder and returns its stdout, or null when git is missing or fails. */
typealias GitRunner = (dir: File, args: List<String>) -> String?

/** Runs git in [dir] and returns stdout; null if git is missing, fails, or takes longer than [timeoutSeconds]. */
fun runGit(dir: File, vararg args: String, timeoutSeconds: Long = 5): String? =
    runGitBytes(dir, *args, timeoutSeconds = timeoutSeconds)?.toString(Charsets.UTF_8)

/** The default [GitRunner]: [runGit] with a 30-second timeout (diffs of large checkouts take a while). */
fun gitRunner(dir: File, args: List<String>): String? = runGit(dir, *args.toTypedArray(), timeoutSeconds = 30)

/** Like [runGit], but returns stdout as bytes (file contents); [env] is added to git's environment, [input] is its stdin. */
fun runGitBytes(
    dir: File, vararg args: String, timeoutSeconds: Long = 5, env: Map<String, String> = emptyMap(), input: ByteArray? = null,
): ByteArray? = try {
    val builder = ProcessBuilder("git", *args)
        .directory(dir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
    builder.environment().putAll(env)
    val process = builder.start()
    // Read on another thread so a hung git (a lock, a prompt) still times out.
    val out = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
    // Written on another thread too: git may fill stdout before it has read all of stdin.
    if (input == null) process.outputStream.close()
    else CompletableFuture.runAsync { try { process.outputStream.use { it.write(input) } } catch (_: IOException) {} }
    when {
        !process.waitFor(timeoutSeconds, TimeUnit.SECONDS) -> { process.destroyForcibly(); null }
        process.exitValue() != 0 -> null
        else -> out.get(timeoutSeconds, TimeUnit.SECONDS)
    }
} catch (_: Exception) {
    null
}
