package claudechat.session

import claudechat.cli.McpTool

/**
 * What the IDE lends one chat's Claude: tools it can call (as `mcp__tether__<name>`) and a check after each of its edits.
 * Read when the claude process starts. [afterEdit] and the tools' calls run on a pooled thread, never under the session
 * lock, and may block.
 */
interface IdeHost {
    /** Empty lends none. Read-only ones ([McpTool.readOnly]) are allowed without asking; the rest ask as usual. */
    val tools: List<McpTool>

    /** False registers no after-edit hook. */
    val checksEdits: Boolean

    /** Text Claude reads after it edited [filePath] (absolute); null tells it nothing. */
    fun afterEdit(filePath: String): String?
}
