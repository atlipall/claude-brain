package claudechat.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileLinkerTest : BasePlatformTestCase() {
    private val linker get() = FileLinker.getInstance(project)

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
}
