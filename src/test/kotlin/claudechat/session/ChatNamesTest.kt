package claudechat.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNamesTest {
    @Test fun titleIsLabelUntilAMessageIsSent() = assertEquals("main", ChatNames.tabTitle("main", null))

    @Test fun titleAddsShortFirstMessageWithWhitespaceCollapsed() =
        assertEquals("wt-1 · fix the build", ChatNames.tabTitle("wt-1", "fix the\n  build"))

    @Test fun longFirstMessageIsCutAtThirtyCharacters() = assertEquals(
        "main · Fix the flaky login test in th…",
        ChatNames.tabTitle("main", "Fix the flaky login test in the auth module"))

    @Test fun blankMessageKeepsLabel() = assertEquals("main", ChatNames.tabTitle("main", "   "))

    @Test fun folderLabel() {
        assertEquals("main", ChatNames.folderLabel("/p/", "/p"))
        assertEquals("wt-1", ChatNames.folderLabel("/p/.claude/worktrees/wt-1", "/p"))
    }

    @Test fun folderLabelComparesCanonicalPaths() {
        val dir = java.nio.file.Files.createTempDirectory("proj").toFile()
        val link = java.nio.file.Files.createSymbolicLink(
            java.io.File(dir.parentFile, dir.name + "-link").toPath(), dir.toPath()).toFile()
        try {
            assertEquals("main", ChatNames.folderLabel(link.path, dir.path))
            assertEquals("main", ChatNames.folderLabel(dir.path + "/", dir.path))
            assertEquals("main", ChatNames.folderLabel(dir.path + "/./", link.path + "/"))
        } finally { link.delete(); dir.deleteRecursively() }
    }

    @Test fun specs() {
        assertEquals(ChatSpec("main", "/p"), ChatSpec.inProject("/p"))
        assertEquals(ChatSpec("wt-2", "/p", worktree = "wt-2"), ChatSpec.newWorktree("/p", "wt-2"))
        assertEquals(ChatSpec("co", "/x/co"), ChatSpec.existingWorktree("/x/co"))
        assertEquals(
            ChatSpec("wt-1", "/p/.claude/worktrees/wt-1", resumeId = "s1"),
            ChatSpec.resume("/p", SessionSummary("s1", "t", 0, "/p/.claude/worktrees/wt-1")))
        assertEquals(ChatSpec("main", "/p", resumeId = "s2"), ChatSpec.resume("/p", SessionSummary("s2", "t", 0, "/p")))
    }
}
