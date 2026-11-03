package claudechat.ide

import claudechat.session.FileDiff
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

data class ResolvedPath(val target: String, val line: Int?)

@Service(Service.Level.PROJECT)
class FileLinker(private val project: Project) {
    private val pattern = Regex("""^(.+?)(?:(?::|#L)(\d+)(?:[:-]\d+)?)?$""")

    fun resolve(candidate: String): ResolvedPath? {
        val match = pattern.matchEntire(candidate.trim()) ?: return null
        val raw = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val file = findFile(raw) ?: return null
        return ResolvedPath(file.url, line)
    }

    private fun findFile(raw: String): VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
        val index = ProjectFileIndex.getInstance(project)
        val candidates = buildList {
            if (raw.startsWith("/")) LocalFileSystem.getInstance().findFileByPath(raw)?.let(::add)
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                root.findFileByRelativePath(raw.removePrefix("./"))?.let(::add)
            }
        }
        candidates.firstOrNull { !it.isDirectory && index.isInContent(it) }
    }

    /** [target] is a VFS URL or an absolute local path; [line] is 1-based. */
    fun open(target: String, line: Int?) {
        val file = findByTarget(target) ?: return
        OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    fun showDiff(diff: FileDiff) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(diff.filePath) ?: return
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Claude's edit: ${file.name}",
            factory.create(project, diff.originalContent, file.fileType),
            factory.create(project, file),
            "Before Claude's edit",
            "Current",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun findByTarget(target: String): VirtualFile? =
        if (target.contains("://")) VirtualFileManager.getInstance().findFileByUrl(target)
        else LocalFileSystem.getInstance().refreshAndFindFileByPath(target)

    companion object {
        fun getInstance(project: Project): FileLinker = project.service()
    }
}
