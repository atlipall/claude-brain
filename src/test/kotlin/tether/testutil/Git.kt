package tether.testutil

import java.io.File

/** Runs real git in [dir] with a throwaway identity; fails the test with git's output when git fails. */
fun git(dir: File, vararg args: String) {
    val p = ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", *args)
        .directory(dir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
}

/** True when a git binary can be run (tests using real git skip otherwise). */
fun gitAvailable(): Boolean = try { ProcessBuilder("git", "--version").start().waitFor() == 0 } catch (_: Exception) { false }
