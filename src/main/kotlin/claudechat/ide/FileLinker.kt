package claudechat.ide

import claudechat.git.canonicalPath
import claudechat.git.isUnder
import claudechat.image.Images
import claudechat.session.FileDiff
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.io.IOException

data class ResolvedPath(val target: String, val line: Int?)

@Service(Service.Level.PROJECT)
class FileLinker(private val project: Project) {
    private val pattern = Regex("""^(.+?)(?:(?::|#L)(\d+)(?:[:-]\d+)?)?$""")

    /**
     * Resolves a path Claude mentioned. Relative paths are tried against [cwd] (the chat's folder) first, then
     * the project content roots. A file counts if it is project content or lies under [cwd].
     */
    fun resolve(candidate: String, cwd: String? = null): ResolvedPath? {
        val match = pattern.matchEntire(candidate.trim()) ?: return null
        val raw = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val file = findFile(raw, cwd) ?: return null
        return ResolvedPath(file.url, line)
    }

    /**
     * A `data:` URL for a local image Claude's markdown shows (`![alt](path)`), or null. The file is found like [resolve]
     * finds one (relative to [cwd] first, then the content roots; it must be project content or lie under [cwd]); the
     * path may not contain a `..` segment (split on both slashes), a URL scheme or be over 1000 characters; the file, symbolic links resolved, must still lie under a content root or [cwd]; the file must be at most 5 MB and a PNG, JPEG, GIF or WebP by
     * its magic bytes. The page gets a display copy (at most 800 px and 512 KB). Reads the file: never call it on the EDT.
     */
    fun localImage(candidate: String, cwd: String?): String? {
        val raw = candidate.trim()
        if (raw.isEmpty() || raw.length > MAX_IMAGE_PATH || SCHEME.containsMatchIn(raw) || raw.split('/', '\\').any { it == ".." }) return null
        val file = findFile(raw, cwd) ?: return null
        if (!resolvesInside(file, cwd)) return null
        if (file.length > Images.MAX_BYTES) return null
        val bytes = try { VfsUtilCore.loadBytes(file) } catch (e: IOException) { return null }
        if (bytes.size > Images.MAX_BYTES) return null
        return Images.preview(bytes)?.dataUrl()
    }

    /** Whether the file, with symbolic links resolved, lies under a (canonical) content root or the chat folder. */
    private fun resolvesInside(file: VirtualFile, cwd: String?): Boolean {
        val roots = ReadAction.compute<List<String>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        }
        return (roots + listOfNotNull(cwd)).any { isUnder(file.path, it) }
    }

    private fun findFile(raw: String, cwd: String?): VirtualFile? {
        val cached = ReadAction.compute<VirtualFile?, RuntimeException> { lookup(raw, cwd) }
        if (cached != null) return cached
        // Claude may have just created the file; the VFS may not know yet. Refresh outside the read action.
        val relative = raw.removePrefix("./")
        val local = LocalFileSystem.getInstance()
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.filter { it.fileSystem === local }
        }
        val paths = buildList {
            if (raw.startsWith("/")) {
                add(raw)
            } else {
                if (cwd != null) add("${cwd.trimEnd('/')}/$relative")
                roots.forEach { add("${it.path}/$relative") }
            }
        }
        val refreshed = paths.mapNotNull { local.refreshAndFindFileByPath(it) }
        return ReadAction.compute<VirtualFile?, RuntimeException> { refreshed.firstOrNull { accepts(it, cwd) } }
    }

    private fun lookup(raw: String, cwd: String?): VirtualFile? {
        val relative = raw.removePrefix("./")
        val candidates = buildList {
            if (raw.startsWith("/")) {
                LocalFileSystem.getInstance().findFileByPath(raw)?.let(::add)
            } else {
                folder(cwd)?.findFileByRelativePath(relative)?.let(::add)
                ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                    root.findFileByRelativePath(relative)?.let(::add)
                }
            }
        }
        return candidates.firstOrNull { accepts(it, cwd) }
    }

    /** The chat folder: on disk, or inside a content root that lives on another file system (tests use an in-memory one). */
    private fun folder(cwd: String?): VirtualFile? {
        if (cwd == null) return null
        LocalFileSystem.getInstance().findFileByPath(cwd)?.let { return it }
        val dir = cwd.trimEnd('/')
        return ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { root ->
            when {
                dir == root.path -> root
                dir.startsWith(root.path + "/") -> root.findFileByRelativePath(dir.removePrefix(root.path + "/"))
                else -> null
            }
        }
    }

    private fun accepts(file: VirtualFile, cwd: String?): Boolean =
        !file.isDirectory && (ProjectFileIndex.getInstance(project).isInContent(file) || isUnder(file.path, cwd))

    /** [target] is a VFS URL or an absolute local path; [line] is 1-based. */
    fun open(target: String, line: Int?) {
        val file = findByTarget(target) ?: return
        OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    fun showDiff(diff: FileDiff) {
        DiffManager.getInstance().showDiff(project, request(diff, "Claude's edit", "Before Claude's edit"))
    }

    /** One diff window over a turn's files, opened at [index]; its arrows step through the others. */
    fun showTurnDiffs(diffs: List<FileDiff>, index: Int) {
        val chain = SimpleDiffRequestChain(diffs.map { request(it, "This turn", "Before this turn") })
        chain.index = index
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }

    /** [diff]'s original against the disk; a file deleted since shows as empty. */
    private fun request(diff: FileDiff, title: String, beforeLabel: String): SimpleDiffRequest {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(diff.filePath)
        val name = File(diff.filePath).name
        val factory = DiffContentFactory.getInstance()
        return SimpleDiffRequest(
            "$title: $name",
            factory.create(project, diff.originalContent, FileTypeManager.getInstance().getFileTypeByFileName(name)),
            if (file != null) factory.create(project, file) else factory.createEmpty(),
            beforeLabel,
            if (file != null) "Current" else "Deleted",
        )
    }

    private fun findByTarget(target: String): VirtualFile? =
        if (target.contains("://")) VirtualFileManager.getInstance().findFileByUrl(target)
        else LocalFileSystem.getInstance().refreshAndFindFileByPath(target)

    companion object {
        private const val MAX_IMAGE_PATH = 1000
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun getInstance(project: Project): FileLinker = project.service()
    }
}
