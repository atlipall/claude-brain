package tether.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File

class FileDropTest {
    private fun files(vararg files: File) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any = files.toList()
    }

    @Test fun aFileListYieldsItsFiles() {
        assertEquals(listOf(File("/a.png"), File("/b.jpg")), FileDrop.files(files(File("/a.png"), File("/b.jpg"))))
    }

    @Test fun textIsNotAFileDrop() {
        assertNull(FileDrop.files(StringSelection("hello")))
    }

    @Test fun aFileListThatFailsToArriveIsNotAFileDrop() {
        val broken = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = true
            override fun getTransferData(flavor: DataFlavor): Any = throw java.io.IOException("gone")
        }
        assertNull(FileDrop.files(broken))
    }
}
