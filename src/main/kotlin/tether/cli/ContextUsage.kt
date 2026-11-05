package tether.cli

import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/** One part of what fills the context window, e.g. "Messages". */
data class ContextCategory(val name: String, val tokens: Long)

/**
 * How full the context window is, from the CLI's `get_context_usage` reply (what `/context` shows).
 * [autocompactAt] is the percentage at which the CLI compacts the conversation, null when it reported no buffer.
 */
data class ContextUsage(
    val used: Long,
    val max: Long,
    val percent: Int,
    val autocompactAt: Int?,
    val categories: List<ContextCategory>,
) {
    companion object {
        /** Null when [response] lacks the token counts (an older or changed CLI). */
        fun parse(response: JsonObject): ContextUsage? {
            val used = response.long("totalTokens")?.takeIf { it >= 0 } ?: return null
            val max = response.long("maxTokens")?.takeIf { it > 0 } ?: return null
            val cats = response.arr("categories").orEmpty().filterIsInstance<JsonObject>()
            val buffer = cats.firstOrNull { it.str("kind") == "buffer" }?.long("tokens")?.takeIf { it in 1 until max }
            val percent = response.double("percentage")?.roundToInt() ?: (used * 100 / max).toInt()
            return ContextUsage(
                used = used,
                max = max,
                percent = percent.coerceIn(0, 100),
                autocompactAt = buffer?.let { ((max - it) * 100 / max).toInt() }?.takeIf { it > 0 },
                categories = cats.filter { it.str("kind") == "used" }.mapNotNull { c ->
                    val name = c.str("name") ?: return@mapNotNull null
                    val tokens = c.long("tokens")?.takeIf { it > 0 } ?: return@mapNotNull null
                    ContextCategory(name, tokens)
                },
            )
        }
    }
}
