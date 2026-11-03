package claudechat.ide.tools

import claudechat.cli.McpTool
import claudechat.cli.ToolOutput
import claudechat.cli.arr
import claudechat.cli.bool
import claudechat.cli.long
import claudechat.cli.str
import claudechat.follow.CheckoutMapper
import claudechat.follow.Placement
import claudechat.git.canonicalPath
import claudechat.ide.Mention
import claudechat.session.IdeHost
import claudechat.settings.ClaudeChatSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

private const val TOOL_TIMEOUT_MS = 30_000L
private const val AFTER_EDIT_TIMEOUT_MS = 20_000L
private const val MAX_FILES = 20

/** The IDE tools and edit checks one chat's Claude gets; [cwd] is the chat's current folder. */
class ChatIdeHost(private val project: Project, private val projectDir: String, private val cwd: () -> String) : IdeHost {
    private val settings get() = ClaudeChatSettings.getInstance().state

    override val tools: List<McpTool> get() = if (settings.ideTools) listOf(DiagnosticsTool(), FindUsagesTool()) else emptyList()

    override val checksEdits: Boolean get() = settings.checkEdits

    override fun afterEdit(filePath: String): String? {
        val file = resolve(filePath) ?: return null
        if (skipReason(file) != null) return null
        val checked = Diagnostics.collect(project, listOf(file), HighlightSeverity.ERROR, Deadline(AFTER_EDIT_TIMEOUT_MS))[file]
        return ProblemReport.afterEdit(display(file), checked?.problems.orEmpty())
    }

    /** [path] absolute or relative to the chat's folder, as the IDE knows it; null when there is no such file. */
    private fun resolve(path: String): VirtualFile? {
        val file = File(path).let { if (it.isAbsolute) it else File(cwd(), path) }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)?.takeIf { !it.isDirectory }
    }

    /** Why the IDE can't check [file] meaningfully: outside the project's indexed content, highlighting reports bogus errors. */
    private fun skipReason(file: VirtualFile): String? = when {
        file.fileType.isBinary -> "binary file, not checked"
        !ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(file) } ->
            "not checked: the file is outside the IDE project or excluded from indexing"
        else -> null
    }

    /** [file] relative to the chat's folder when inside it, else absolute. */
    private fun display(file: VirtualFile): String {
        val path = canonicalPath(file.path)
        return Mention.relativeTo(path, canonicalPath(cwd())) ?: path
    }

    /** The project's files in the chat's checkout: a main chat does not see worktree copies, nor a worktree chat main's. */
    private fun checkoutScope(): GlobalSearchScope {
        val inProject = GlobalSearchScope.projectScope(project)
        val placement = CheckoutMapper(projectDir).place(cwd())
        val dir = when (placement) {
            is Placement.Followable -> placement.checkout.dir
            is Placement.Outside -> placement.dir
        }
        val files = LocalFileSystem.getInstance()
        val root = files.findFileByPath(dir) ?: return inProject
        val scope = inProject.intersectWith(GlobalSearchScopesCore.directoryScope(project, root, true))
        if (placement !is Placement.Followable || !placement.checkout.isMain) return scope
        val worktrees = files.findFileByPath("$dir/${CheckoutMapper.WORKTREES}") ?: return scope
        return scope.intersectWith(GlobalSearchScope.notScope(GlobalSearchScopesCore.directoryScope(project, worktrees, true)))
    }

    private inner class DiagnosticsTool : McpTool {
        override val name = "diagnostics"
        override val description =
            "Errors and warnings the IDE reports for files: type errors, unresolved names and imports, syntax errors, " +
                "and inspection warnings, from the same analysis the editor shows. Use it to check code after editing " +
                "instead of running a type checker or build. Paths are absolute or relative to the working directory; " +
                "at most $MAX_FILES files per call."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_paths") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Files to check.")
                }
                putJsonObject("include_warnings") {
                    put("type", "boolean")
                    put("description", "Also list warnings and weak warnings (default true). False lists errors only.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("file_paths")) }
        }

        override fun call(args: JsonObject): ToolOutput {
            val paths = args.arr("file_paths").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (paths.isEmpty()) return ToolOutput("Give file_paths: one or more files to check.", isError = true)
            if (paths.size > MAX_FILES) return ToolOutput("At most $MAX_FILES files per call.", isError = true)
            val minSeverity = if (args.bool("include_warnings") == false) HighlightSeverity.ERROR else HighlightSeverity.WEAK_WARNING
            val entries = paths.map { path -> resolve(path).let { file -> Triple(path, file, file?.let(::skipReason)) } }
            val checkable = entries.mapNotNull { (_, file, skipped) -> file?.takeIf { skipped == null } }.distinct()
            val found = try {
                Diagnostics.collect(project, checkable, minSeverity, Deadline(TOOL_TIMEOUT_MS))
            } catch (e: ToolUnavailable) {
                return ToolOutput(e.message.orEmpty(), isError = true)
            }
            val report = entries.map { (path, file, skipped) ->
                when (file) {
                    null -> FileProblems(path, emptyList(), "no such file")
                    else -> FileProblems(display(file), found[file]?.problems.orEmpty(), skipped, found[file]?.note)
                }
            }
            return ToolOutput(ProblemReport.forTool(report))
        }
    }

    private inner class FindUsagesTool : McpTool {
        override val name = "find_usages"
        override val description =
            "Where a symbol is declared and every place in the project that references it, from the IDE's code index. " +
                "Unlike text search it follows imports, re-exports and aliases and skips unrelated symbols with the same " +
                "name. Give a file and line where the symbol appears (its declaration or any use) and its name as written there."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") { put("type", "string"); put("description", "File containing the symbol, absolute or relative to the working directory.") }
                putJsonObject("line") { put("type", "integer"); put("description", "1-based line where the symbol appears.") }
                putJsonObject("symbol") { put("type", "string"); put("description", "The symbol's name exactly as written on that line.") }
                putJsonObject("column") { put("type", "integer"); put("description", "1-based column, only needed when the name appears more than once on the line.") }
            }
            putJsonArray("required") { add(JsonPrimitive("file_path")); add(JsonPrimitive("line")); add(JsonPrimitive("symbol")) }
        }

        override fun call(args: JsonObject): ToolOutput {
            val path = args.str("file_path") ?: return ToolOutput("Give file_path.", isError = true)
            val line = args.long("line")?.toInt() ?: return ToolOutput("Give line (1-based).", isError = true)
            val symbol = args.str("symbol")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolOutput("Give symbol.", isError = true)
            val file = resolve(path) ?: return ToolOutput("No such file: $path", isError = true)
            skipReason(file)?.let { return ToolOutput("${display(file)}: $it", isError = true) }
            return try {
                val found = Usages.find(project, file, line, symbol, args.long("column")?.toInt(), checkoutScope(), Deadline(TOOL_TIMEOUT_MS))
                ToolOutput(UsageReport.format(found, ::display))
            } catch (e: ToolUnavailable) {
                ToolOutput(e.message.orEmpty(), isError = true)
            }
        }
    }
}
