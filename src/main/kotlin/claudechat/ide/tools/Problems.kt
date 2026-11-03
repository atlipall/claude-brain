package claudechat.ide.tools

import com.intellij.lang.annotation.HighlightSeverity

/** How bad a [Problem] is, worst first. */
enum class Severity(val label: String) {
    ERROR("error"), WARNING("warning"), WEAK_WARNING("weak warning");

    companion object {
        /** Null for severities below weak warnings (information, symbol highlighting). */
        fun of(severity: HighlightSeverity): Severity? = when {
            severity >= HighlightSeverity.ERROR -> ERROR
            severity >= HighlightSeverity.WARNING -> WARNING
            severity >= HighlightSeverity.WEAK_WARNING -> WEAK_WARNING
            else -> null
        }
    }
}

/** One highlight the IDE reports; [line] and [column] are 1-based. */
data class Problem(val line: Int, val column: Int, val severity: Severity, val message: String)

/** What a check of one file found; [note] says what part of the check failed, if any. */
data class Checked(val problems: List<Problem>, val note: String? = null)

/**
 * The problems of one file, or why it could not be checked ([skipped]); [note] as in [Checked]. [path] is as the chat
 * names the file.
 */
data class FileProblems(val path: String, val problems: List<Problem>, val skipped: String? = null, val note: String? = null)

/** Text Claude reads about IDE problems. */
object ProblemReport {
    /** Most problems listed per file; the rest are counted. */
    const val MAX_PER_FILE = 50

    /** The diagnostics tool's answer: per file, a count line then one line per problem, in position order. */
    fun forTool(files: List<FileProblems>): String = files.joinToString("\n\n") { file ->
        val report = when {
            file.skipped != null -> "${file.path}: ${file.skipped}"
            file.problems.isEmpty() -> "${file.path}: no problems"
            else -> "${file.path}: ${counts(file.problems)}\n${lines(file.problems)}"
        }
        if (file.note == null) report else "$report\n  (${file.note})"
    }

    /** What Claude is told after editing a file: its errors, or null when it has none (warnings are not worth a turn). */
    fun afterEdit(path: String, problems: List<Problem>): String? {
        val errors = problems.filter { it.severity == Severity.ERROR }
        if (errors.isEmpty()) return null
        val noun = if (errors.size == 1) "error" else "errors"
        return "The IDE reports ${errors.size} $noun in $path after this edit:\n${lines(errors)}"
    }

    private fun counts(problems: List<Problem>): String =
        Severity.entries.mapNotNull { s ->
            val n = problems.count { it.severity == s }
            if (n == 0) null else "$n ${s.label}${if (n == 1) "" else "s"}"
        }.joinToString(", ")

    private fun lines(problems: List<Problem>): String {
        val sorted = problems.sortedWith(compareBy({ it.line }, { it.column }))
        val shown = sorted.take(MAX_PER_FILE).joinToString("\n") { "  ${it.line}:${it.column} ${it.severity.label}: ${oneLine(it.message)}" }
        val more = sorted.size - MAX_PER_FILE
        return if (more > 0) "$shown\n  … and $more more" else shown
    }

    private fun oneLine(message: String) = message.replace(Regex("\\s*\\n\\s*"), " ").trim()
}
