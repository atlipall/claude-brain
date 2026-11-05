package tether.git

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BranchTest {
    private fun tempDir() = Files.createTempDirectory("branch").toFile()

    @Test fun readsTheBranchFromHeadInAParentFolder() {
        val root = tempDir()
        try {
            File(root, ".git").mkdirs()
            File(root, ".git/HEAD").writeText("ref: refs/heads/feature/login\n")
            val sub = File(root, "src/main").apply { mkdirs() }
            assertEquals("feature/login", currentBranch(root.path))
            assertEquals("feature/login", currentBranch(sub.path))
        } finally { root.deleteRecursively() }
    }

    @Test fun detachedOrNoRepositoryIsNull() {
        val root = tempDir()
        try {
            assertEquals(null, currentBranch(root.path))
            File(root, ".git").mkdirs()
            File(root, ".git/HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
            assertEquals(null, currentBranch(root.path))
        } finally { root.deleteRecursively() }
    }

    @Test fun followsALinkedWorktreesGitFile() {
        val root = tempDir()
        try {
            val gitDir = File(root, "repo/.git/worktrees/wt").apply { mkdirs() }
            File(gitDir, "HEAD").writeText("ref: refs/heads/worktree-wt\n")
            val wt = File(root, "wt").apply { mkdirs() }
            File(wt, ".git").writeText("gitdir: ${gitDir.path}\n")
            assertEquals("worktree-wt", currentBranch(wt.path))
            File(wt, ".git").writeText("gitdir: ../repo/.git/worktrees/wt\n")
            assertEquals("worktree-wt", currentBranch(wt.path))
        } finally { root.deleteRecursively() }
    }
}
