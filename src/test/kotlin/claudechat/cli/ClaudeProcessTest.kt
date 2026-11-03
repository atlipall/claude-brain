package claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClaudeProcessTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"))

    @Test fun echoesStdinLinesBackOnStdout() {
        val lines = CopyOnWriteArrayList<String>()
        val got = CountDownLatch(2)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "while IFS= read -r l; do echo \"echo:\$l\"; done"), tmp, emptyMap())
        p.start(onLine = { lines += it; got.countDown() }, onExit = { _, _ -> })
        p.send("""{"a":1}""")
        p.send("two")
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("""echo:{"a":1}""", "echo:two"), lines.toList())
        p.stop()
    }

    @Test fun reportsExitCodeAndStderrTail() {
        val lines = CopyOnWriteArrayList<String>()
        val exited = CountDownLatch(1)
        var code = -1
        var tail = ""
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "echo out; echo err1 >&2; echo err2 >&2; exit 3"), tmp, mapOf("X" to "1"))
        p.start(onLine = { lines += it }, onExit = { c, t -> code = c; tail = t; exited.countDown() })
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("out"), lines.toList())
        assertEquals(3, code)
        assertEquals("err1\nerr2", tail)
    }

    @Test fun sendAfterExitDoesNotThrow() {
        val exited = CountDownLatch(1)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "exit 0"), tmp, emptyMap())
        p.start(onLine = {}, onExit = { _, _ -> exited.countDown() })
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        p.send("x")
    }

    @Test fun sendPreservesOrderAndNeverBlocks() {
        val lines = CopyOnWriteArrayList<String>()
        val all = CountDownLatch(1000)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "while IFS= read -r l; do echo \"\$l\"; done"), tmp, emptyMap())
        try {
            p.start(onLine = { lines += it; all.countDown() }, onExit = { _, _ -> })
            for (i in 0 until 1000) p.send("line $i")
            assertTrue(all.await(10, TimeUnit.SECONDS))
            assertEquals((0 until 1000).map { "line $it" }, lines.toList())
        } finally {
            p.stop()
        }
    }

    @Test fun sendDoesNotBlockWhenChildNeverReadsStdin() {
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "sleep 5"), tmp, emptyMap())
        try {
            p.start(onLine = {}, onExit = { _, _ -> })
            val payload = "x".repeat(5_000)
            val started = System.nanoTime()
            repeat(200) { p.send(payload) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("200 sends took ${elapsedMs}ms", elapsedMs < 1000)
        } finally {
            p.stop()
        }
    }

    @Test fun writerThreadEndsWhenProcessExitsOnItsOwn() {
        val exited = CountDownLatch(1)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "exit 0"), tmp, emptyMap())
        p.start(onLine = {}, onExit = { _, _ -> exited.countDown() })
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val writer = checkNotNull(p.stdinThread)
        writer.join(2000)
        assertFalse("stdin writer still alive after process exit", writer.isAlive)
        p.send("after exit")
        p.stop()
    }

    @Test fun listenerExceptionDoesNotStopLaterLines() {
        val lines = CopyOnWriteArrayList<String>()
        val exited = CountDownLatch(1)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "echo a; echo b; echo c"), tmp, emptyMap())
        p.start(
            onLine = { if (it == "a") throw IllegalStateException("boom") else lines += it },
            onExit = { _, _ -> exited.countDown() },
        )
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("b", "c"), lines.toList())
    }
}
