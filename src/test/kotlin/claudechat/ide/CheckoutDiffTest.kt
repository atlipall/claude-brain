package claudechat.ide

import claudechat.git.CheckoutChanges
import claudechat.git.DiffBases
import claudechat.testutil.git
import claudechat.testutil.gitAvailable
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class CheckoutDiffTest : BasePlatformTestCase() {
    fun testChangesReadTheBaseFromGitAndTheRestFromDisk() {
        if (!gitAvailable()) return
        val repo = Files.createTempDirectory("checkout-diff").toFile()
        VfsRootAccess.allowRootAccess(testRootDisposable, repo.canonicalPath)
        try {
            git(repo, "init", "-q", "-b", "main")
            File(repo, "edit.txt").writeText("before\n")
            File(repo, "drop.txt").writeText("bye\n")
            File(repo, "old.txt").writeText("same\n")
            git(repo, "add", ".")
            git(repo, "commit", "-q", "-m", "base")
            git(repo, "checkout", "-q", "-b", "feature")
            File(repo, "edit.txt").writeText("after\n")
            File(repo, "drop.txt").delete()
            git(repo, "mv", "old.txt", "new.txt")
            File(repo, "added.txt").writeText("fresh\n")

            val base = DiffBases().resolve(repo.path, null, null)!!
            val checkout = CheckoutChanges()
            val changes = CheckoutDiff.toChanges(base, checkout.list(base)!!, checkout).associateBy { (it.afterRevision ?: it.beforeRevision)!!.file.name }

            assertEquals(setOf("edit.txt", "drop.txt", "new.txt", "added.txt"), changes.keys)
            assertEquals("before\n", changes.getValue("edit.txt").beforeRevision!!.content)
            assertEquals("after\n", changes.getValue("edit.txt").afterRevision!!.content)
            assertEquals(Change.Type.DELETED, changes.getValue("drop.txt").type)
            assertEquals("bye\n", changes.getValue("drop.txt").beforeRevision!!.content)
            assertEquals(Change.Type.NEW, changes.getValue("added.txt").type)
            assertEquals("fresh\n", changes.getValue("added.txt").afterRevision!!.content)
            val moved = changes.getValue("new.txt")
            assertTrue(moved.isMoved || moved.isRenamed)
            assertEquals("old.txt", moved.beforeRevision!!.file.name)
            assertEquals("same\n", moved.beforeRevision!!.content)
            assertEquals(base.commit.take(7), moved.beforeRevision!!.revisionNumber.asString())
        } finally {
            repo.deleteRecursively()
        }
    }
}
