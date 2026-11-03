package claudechat.session

import claudechat.cli.arr
import claudechat.cli.bool
import claudechat.cli.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The tool Claude uses to ask the user a question; its permission request is a question, not a permission. */
const val ASK_USER_QUESTION = "AskUserQuestion"

data class QuestionOption(val label: String, val description: String, val preview: String?)

data class Question(val question: String, val header: String, val multiSelect: Boolean, val options: List<QuestionOption>)

/** What the user chose for one question: option [labels] and/or a free-text [other] answer. */
data class QuestionAnswer(val labels: List<String>, val other: String?)

/** The questions of a pending (or answered) AskUserQuestion call; [answers] maps question text to the answer sent. */
data class QuestionCard(val questions: List<Question>, val answers: Map<String, String>? = null)

object Questions {
    const val MAX_QUESTIONS = 4
    const val MAX_OPTIONS = 10
    const val MAX_QUESTION = 2000
    const val MAX_HEADER = 200
    const val MAX_LABEL = 500
    const val MAX_DESCRIPTION = 2000
    const val MAX_PREVIEW = 20_000
    const val MAX_OTHER = 2000

    /** The questions of an AskUserQuestion input, or null when the input is malformed or over a length cap. */
    fun parse(input: JsonObject): List<Question>? {
        val raw = input.arr("questions") ?: return null
        if (raw.isEmpty() || raw.size > MAX_QUESTIONS) return null
        val questions = raw.map { parseQuestion(it as? JsonObject ?: return null) ?: return null }
        if (questions.map { it.question }.toSet().size != questions.size) return null
        return questions
    }

    private fun parseQuestion(q: JsonObject): Question? {
        val text = q.str("question")?.takeIf { it.isNotBlank() && it.length <= MAX_QUESTION } ?: return null
        val header = (q.str("header") ?: "").takeIf { it.length <= MAX_HEADER } ?: return null
        val rawOptions = q.arr("options") ?: return null
        if (rawOptions.isEmpty() || rawOptions.size > MAX_OPTIONS) return null
        val options = rawOptions.map { parseOption(it as? JsonObject ?: return null) ?: return null }
        return Question(text, header, q.bool("multiSelect") == true, options)
    }

    private fun parseOption(o: JsonObject): QuestionOption? {
        val label = o.str("label")?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL } ?: return null
        val description = (o.str("description") ?: "").takeIf { it.length <= MAX_DESCRIPTION } ?: return null
        val preview = o.str("preview")?.takeIf { it.isNotEmpty() }
        if (preview != null && preview.length > MAX_PREVIEW) return null
        return QuestionOption(label, description, preview)
    }

    /**
     * The answer text per question, or null when [answers] is not exactly one valid answer for each of [questions]:
     * every label must be one of that question's options (no repeats), the free text at most [MAX_OTHER] characters,
     * a single-select question takes one label or free text but not both, and a multi-select takes at least one.
     * Multiple answers are joined with ", ".
     */
    fun answerTexts(questions: List<Question>, answers: Map<String, QuestionAnswer>): Map<String, String>? {
        if (answers.keys != questions.map { it.question }.toSet()) return null
        val out = LinkedHashMap<String, String>()
        for (q in questions) {
            val a = answers.getValue(q.question)
            val other = a.other?.trim()?.takeIf { it.isNotEmpty() }
            if (other != null && other.length > MAX_OTHER) return null
            if (a.labels.toSet().size != a.labels.size || !q.options.map { it.label }.containsAll(a.labels)) return null
            val parts = a.labels + listOfNotNull(other)
            if (parts.isEmpty() || (!q.multiSelect && parts.size != 1)) return null
            out[q.question] = parts.joinToString(", ")
        }
        return out
    }

    /** [input] with `answers` added (the shape the CLI hands back to the tool, verified against claude 2.1.317). */
    fun withAnswers(input: JsonObject, answers: Map<String, String>): JsonObject = buildJsonObject {
        for ((k, v) in input) if (k != "answers") put(k, v)
        put("answers", buildJsonObject { for ((q, a) in answers) put(q, a) })
    }
}

fun QuestionCard.toJson(): JsonObject = buildJsonObject {
    put("questions", JsonArray(questions.map { q ->
        buildJsonObject {
            put("question", q.question)
            put("header", q.header)
            put("multiSelect", q.multiSelect)
            put("options", JsonArray(q.options.map { o ->
                buildJsonObject {
                    put("label", o.label)
                    put("description", o.description)
                    if (o.preview != null) put("preview", o.preview)
                }
            }))
        }
    }))
    if (answers != null) put("answers", buildJsonObject { for ((q, a) in answers) put(q, JsonPrimitive(a)) })
}
