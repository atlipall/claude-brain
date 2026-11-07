package tether.ui

import com.intellij.ide.dnd.FileCopyPasteUtil
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import javax.swing.JComponent

/**
 * Takes files dropped on a Swing component (Finder, the Project view) and hands them to [onFiles].
 *
 * JCEF's off-screen browser component installs no drop target, so a native file drop never reaches the page's own
 * `drop` listener; this is how dropped files get in there. Drags carrying anything but files are rejected, and so
 * are file drags while [accepting] is false. [onHover] is told when files start and stop being dragged over the
 * component. All callbacks run on the EDT.
 */
class FileDrop(
    private val accepting: () -> Boolean,
    private val onHover: (Boolean) -> Unit,
    private val onFiles: (List<File>) -> Unit,
) : DropTargetAdapter() {
    private var hovering = false

    override fun dragEnter(e: DropTargetDragEvent) = judge(e)

    override fun dragOver(e: DropTargetDragEvent) = judge(e)

    override fun dropActionChanged(e: DropTargetDragEvent) = judge(e)

    override fun dragExit(e: DropTargetEvent) = hover(false)

    override fun drop(e: DropTargetDropEvent) {
        hover(false)
        if (!accepting() || !FileCopyPasteUtil.isFileListFlavorAvailable(e.currentDataFlavors)) return e.rejectDrop()
        e.acceptDrop(DnDConstants.ACTION_COPY)
        val dropped = files(e.transferable)
        e.dropComplete(dropped != null)
        if (!dropped.isNullOrEmpty()) onFiles(dropped)
    }

    private fun judge(e: DropTargetDragEvent) {
        val takes = accepting() && FileCopyPasteUtil.isFileListFlavorAvailable(e.currentDataFlavors)
        hover(takes)
        if (takes) e.acceptDrag(DnDConstants.ACTION_COPY) else e.rejectDrag()
    }

    private fun hover(active: Boolean) {
        if (active == hovering) return
        hovering = active
        onHover(active)
    }

    companion object {
        /** Makes [component] a drop target for files (see [FileDrop]). */
        fun install(component: JComponent, drop: FileDrop) {
            component.dropTarget = DropTarget(component, DnDConstants.ACTION_COPY, drop, true)
        }

        /**
         * The files a transfer carries (Java file lists, and the URI lists Linux file managers send), or null when it
         * carries none or they can't be read.
         */
        internal fun files(transferable: Transferable): List<File>? = FileCopyPasteUtil.getFileList(transferable)
    }
}
