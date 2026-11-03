package claudechat.ide.tools

import claudechat.cli.McpTool
import claudechat.cli.ToolOutput
import claudechat.cli.strings
import claudechat.cli.bool
import com.intellij.lang.annotation.HighlightSeverity
import kotlinx.serialization.json.JsonObject

private const val MAX_FILES = 20

/** The tools that only read: allowed without asking. */
internal fun readTools(host: ChatIdeHost): List<McpTool> = listOf(DiagnosticsTool(host), FindUsagesTool(host), FixesTool(host))

private class DiagnosticsTool(host: ChatIdeHost) : IdeTool(host, listOf(
    Arg("file_paths", ArgType.STRINGS, "Files to check."),
    Arg("include_warnings", ArgType.BOOLEAN, "Also list warnings and weak warnings (default true). False lists errors only.", required = false),
)) {
    override val name = "diagnostics"
    override val readOnly = true
    override val description =
        "Errors and warnings the IDE reports for files: type errors, unresolved names and imports, syntax errors, " +
            "and inspection warnings, from the same analysis the editor shows. Use it to check code after editing " +
            "instead of running a type checker or build. Paths are absolute or relative to the working directory; " +
            "at most $MAX_FILES files per call."

    override fun run(args: JsonObject): ToolOutput {
        val paths = args.strings("file_paths")
        if (paths.isEmpty()) throw ToolUnavailable("Give file_paths: one or more files to check.")
        if (paths.size > MAX_FILES) throw ToolUnavailable("At most $MAX_FILES files per call.")
        val minSeverity = if (args.bool("include_warnings") == false) HighlightSeverity.ERROR else HighlightSeverity.WEAK_WARNING
        val entries = paths.map { path -> host.resolve(path).let { file -> Triple(path, file, file?.let(host::skipReason)) } }
        val checkable = entries.mapNotNull { (_, file, skipped) -> file?.takeIf { skipped == null } }.distinct()
        val found = Diagnostics.collect(host.project, checkable, minSeverity, Deadline(TOOL_TIMEOUT_MS))
        val report = entries.map { (path, file, skipped) ->
            when (file) {
                null -> FileProblems(path, emptyList(), "no such file")
                else -> FileProblems(host.display(file), found[file]?.problems.orEmpty(), skipped, found[file]?.note)
            }
        }
        return ToolOutput(ProblemReport.forTool(report))
    }
}

private class FindUsagesTool(host: ChatIdeHost) : IdeTool(host, SYMBOL_ARGS) {
    override val name = "find_usages"
    override val readOnly = true
    override val description =
        "Where a symbol is declared and every place in the project that references it, from the IDE's code index. " +
            "Unlike text search it follows imports, re-exports and aliases and skips unrelated symbols with the same " +
            "name. Give a file and line where the symbol appears (its declaration or any use) and its name as written there."

    override fun run(args: JsonObject): ToolOutput {
        val found = Usages.find(host.project, args.symbol(), host.checkoutScope(), Deadline(TOOL_TIMEOUT_MS))
        return ToolOutput(UsageReport.format(found, host::display))
    }
}

private class FixesTool(host: ChatIdeHost) : IdeTool(host, listOf(
    Arg("file_path", ArgType.STRING, "The file, absolute or relative to the working directory."),
    Arg("line", ArgType.INTEGER, "1-based line of the problem."),
)) {
    override val name = "fixes"
    override val readOnly = true
    override val description =
        "The quick fixes the IDE offers (its Alt+Enter fixes) for the problems on a line: e.g. adding a missing import, " +
            "implementing members, or fixing a type. Apply one with apply_fix. Prefer these over hand edits for imports."

    override fun run(args: JsonObject): ToolOutput {
        val file = args.file()
        val line = args.int("line")
        return ToolOutput(FixReport.list(host.display(file), line, Fixes.list(host.project, file, line, Deadline(TOOL_TIMEOUT_MS))))
    }
}
