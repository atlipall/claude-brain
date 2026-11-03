package claudechat.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class FileLinkerTest : BasePlatformTestCase() {
    private val linker get() = FileLinker.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    fun testResolvesRelativePathWithLineForms() {
        val file = myFixture.addFileToProject("src/foo.ts", "a\nb\nc\n").virtualFile
        assertEquals(ResolvedPath(file.url, null), linker.resolve("src/foo.ts"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts:2"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts:2:5"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts#L2-3"))
    }

    fun testUnknownOrDirectoryDoesNotResolve() {
        myFixture.addFileToProject("src/bar.ts", "x")
        assertNull(linker.resolve("src/missing.ts"))
        assertNull(linker.resolve("src"))
        assertNull(linker.resolve(""))
    }

    fun testOpenNavigatesToFile() {
        val file = myFixture.addFileToProject("src/open.ts", "a\nb\n").virtualFile
        linker.open(file.url, 2)
        val editors = FileEditorManager.getInstance(project)
        assertEquals(file, editors.selectedFiles.first())
        assertEquals(1, editors.selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun testRelativePathResolvesAgainstChatFolderFirst() {
        myFixture.addFileToProject("lib/x.txt", "project copy")
        val inWorktree = myFixture.addFileToProject(".claude/worktrees/wt1/lib/x.txt", "worktree copy").virtualFile
        assertEquals(ResolvedPath(inWorktree.url, 3), linker.resolve("lib/x.txt:3", "$root/.claude/worktrees/wt1"))
    }

    fun testFallsBackToContentRootsWhenNotInChatFolder() {
        val inProject = myFixture.addFileToProject("lib/y.txt", "x").virtualFile
        assertEquals(ResolvedPath(inProject.url, null), linker.resolve("lib/y.txt", "$root/.claude/worktrees/wt1"))
    }

    fun testFileOutsideProjectResolvesOnlyUnderChatFolder() {
        val dir = Files.createTempDirectory("wt-outside").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        try {
            val notes = File(dir, "notes.txt").apply { writeText("x") }
            val url = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(notes)!!.url
            assertEquals(ResolvedPath(url, null), linker.resolve("notes.txt", dir.path))
            assertEquals(ResolvedPath(url, 2), linker.resolve("${notes.path}:2", dir.path))
            assertNull(linker.resolve("notes.txt", null))
            assertNull(linker.resolve(notes.path, null))
            assertNull(linker.resolve(notes.path, "/elsewhere"))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testSiblingFolderWithSamePrefixIsNotUnderChatFolder() {
        val parent = Files.createTempDirectory("wt-sib").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, parent.path)
        try {
            val proj = File(parent, "proj").apply { mkdirs() }
            val proj2 = File(parent, "proj2").apply { mkdirs() }
            val other = File(proj2, "a.txt").apply { writeText("x") }
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(other)!!
            assertNull(linker.resolve(other.path, proj.path))
            assertNull(linker.resolve("../proj2/a.txt", proj.path))
        } finally {
            parent.deleteRecursively()
        }
    }

    fun testSymlinkedChatFolderCountsAsUnder() {
        val parent = Files.createTempDirectory("wt-link").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, parent.path)
        try {
            val real = File(parent, "real").apply { mkdirs() }
            val f = File(real, "n.txt").apply { writeText("x") }
            val link = File(parent, "link")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            val url = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!.url
            assertEquals(ResolvedPath(url, null), linker.resolve(f.path, link.path))
        } finally {
            parent.deleteRecursively()
        }
    }
}
