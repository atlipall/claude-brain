package claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCommandTest {
    private val base = listOf(
        "/bin/claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json",
        "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
    )

    @Test fun newSessionWithoutMode() =
        assertEquals(base, ClaudeCommand.build("/bin/claude", SessionLaunch(null, null), emptyList()))

    @Test fun resumeWithModeAndExtraArgs() = assertEquals(
        base + listOf("--permission-mode", "acceptEdits", "--resume", "abc", "--model", "sonnet"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", PermissionMode.ACCEPT_EDITS), listOf("--model", "sonnet")))

    @Test fun permissionModeFromCli() {
        assertEquals(PermissionMode.PLAN, PermissionMode.fromCli("plan"))
        assertNull(PermissionMode.fromCli("bypassPermissions"))
        assertNull(PermissionMode.fromCli(null))
    }

    @Test fun configuredPathWins() =
        assertEquals("/x/claude", ClaudeBinary.resolve("/x/claude", "/usr/bin", "/home/u") { true })

    @Test fun configuredPathThatIsNotExecutableGivesNull() =
        assertNull(ClaudeBinary.resolve("/x/claude", "/usr/bin", "/home/u") { it != "/x/claude" })

    @Test fun searchesPathThenFallbacks() {
        assertEquals("/b/claude", ClaudeBinary.resolve("", "/a:/b", "/home/u") { it == "/b/claude" })
        assertEquals("/home/u/.local/bin/claude", ClaudeBinary.resolve("", "/a", "/home/u") { it == "/home/u/.local/bin/claude" })
        assertEquals("/opt/homebrew/bin/claude", ClaudeBinary.resolve("  ", null, "/home/u") { it == "/opt/homebrew/bin/claude" })
        assertNull(ClaudeBinary.resolve("", "/a", "/home/u") { false })
    }
}
