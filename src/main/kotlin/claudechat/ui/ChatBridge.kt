package claudechat.ui

import claudechat.cli.ContextUsage
import claudechat.cli.PermissionMode
import claudechat.cli.arr
import claudechat.cli.bool
import claudechat.cli.long
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.ide.CheckoutDiff
import claudechat.ide.FileLinker
import claudechat.image.Attachment
import claudechat.image.Images
import claudechat.image.Prepared
import claudechat.image.Uploads
import claudechat.context.ContextNotes
import claudechat.session.Activity
import claudechat.session.Chat
import claudechat.session.ChatItem
import claudechat.session.PermissionDecision
import claudechat.session.QuestionAnswer
import claudechat.session.PromptHistory
import claudechat.session.SessionState
import claudechat.session.SessionView
import claudechat.session.toJson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Connects one chat page to one [Chat]: web-view messages in, chat updates out. */
class ChatBridge(
    private val project: Project,
    private val chat: Chat,
    private val onNewChat: () -> Unit,
    private val post: (String) -> Unit,
) : SessionView {
    private val linker get() = FileLinker.getInstance(project)
    private val session get() = chat.session
    private val history get() = PromptHistory.getInstance(project)
    @Volatile private var unsubscribe: (() -> Unit)? = null

    /** Runs image work (decoding, scaling, reading files) off the EDT. Tests make it synchronous. */
    internal var offEdt: (() -> Unit) -> Unit = { task -> IMAGE_EXECUTOR.execute { task() } }

    /** Validates and downscales image bytes. Tests replace it. */
    internal var preparer: (ByteArray) -> Prepared = { Images.prepare(it) }

    /** Display copy of a local image for the page (see [FileLinker.localImage]). Tests replace it. */
    internal var localImage: (String, String?) -> String? = { path, cwd -> linker.localImage(path, cwd) }

    /** Brings results back to the EDT. Tests make it synchronous. */
    internal var onEdt: (() -> Unit) -> Unit = { task -> ApplicationManager.getApplication().invokeLater({ task() }, project.disposed) }

    /** Opens the checkout's changes (see [CheckoutDiff]). Tests replace it. */
    internal var showChanges: (Chat) -> Unit = { CheckoutDiff.show(project, it) }

    /** Shows the IDE file chooser for images and calls back with the chosen files (EDT). Tests replace it. */
    internal var chooseImages: ((List<VirtualFile>) -> Unit) -> Unit = { done ->
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp")
            .withTitle("Attach Images")
        FileChooser.chooseFiles(descriptor, project, null) { files -> done(files) }
    }

    private val uploads = Uploads()
    /** Ids whose bytes are being prepared off the EDT; a `detach` removes one so its result is dropped. EDT only. */
    private val preparing = HashSet<String>()
    private var pickSeq = 0

    /** EDT only. */
    fun handle(json: String) {
        val msg = parseJsonObject(json) ?: return
        when (msg.str("type")) {
            "ready" -> {
                post(Theme.message())
                post(historyMessage(history.entries()))
                if (unsubscribe == null) unsubscribe = history.subscribe { post(historyMessage(it)) }
                // A (re)loaded page shows no attachments: forget the ones the old page had.
                uploads.clear()
                preparing.clear()
                chat.attachments.clear()
                chat.attach(this)
            }
            "send" -> {
                val text = msg.str("text").orEmpty()
                val images = chat.attachments.take(strings(msg["attachments"]))
                if (text.isBlank() && images.isEmpty()) return
                // Chat.send ignores a dead chat; history keeps text only
                if (session.state != SessionState.DEAD && text.isNotBlank()) history.add(text)
                chat.send(text, images)
            }
            "attachStart" -> {
                val id = msg.str("id")?.takeIf(ID::matches) ?: return
                val name = displayName(msg.str("name"))
                val size = msg.long("size") ?: 0
                when {
                    slotsUsed() >= Images.MAX_PER_MESSAGE -> refuse(id, "Couldn't attach $name: at most ${Images.MAX_PER_MESSAGE} images per message.")
                    size > Images.MAX_INPUT_BYTES -> refuse(id, "Couldn't attach $name: it is larger than 20 MB.")
                    else -> uploads.start(id, name)
                }
            }
            "attachChunk" -> {
                val id = msg.str("id") ?: return
                if (uploads.has(id) && !uploads.chunk(id, msg.str("data").orEmpty())) {
                    refuse(id, "Couldn't attach the image: it is larger than 20 MB or could not be read.")
                }
            }
            "attachEnd" -> {
                val id = msg.str("id") ?: return
                val (name, bytes) = uploads.finish(id) ?: return
                prepare(id, name) { bytes }
            }
            "detach" -> msg.str("id")?.let { id ->
                uploads.cancel(id)
                preparing.remove(id)
                chat.attachments.remove(id)
            }
            "pickImages" -> chooseImages { files -> files.forEach(::attachFile) }
            "permission" -> {
                val requestId = msg.str("requestId") ?: return
                val decision = when (msg.str("decision")) {
                    "allow" -> PermissionDecision.Allow
                    "allowSession" -> PermissionDecision.AllowForSession
                    else -> PermissionDecision.Deny(msg.str("message") ?: "The user denied this action.")
                }
                session.respond(requestId, decision)
            }
            "answer" -> {
                val requestId = msg.str("requestId") ?: return
                val answers = questionAnswers(msg["answers"]) ?: return session.answerFailed(requestId)
                session.answer(requestId, answers)
            }
            "skipQuestion" -> {
                val requestId = msg.str("requestId") ?: return
                if (session.pendingQuestions(requestId) != null) session.respond(requestId, PermissionDecision.Deny("The user skipped the question."))
            }
            "setMode" -> PermissionMode.fromCli(msg.str("mode"))?.let(session::setPermissionMode)
            "interrupt" -> chat.interrupt()
            "newChat" -> onNewChat()
            "restart" -> session.restart()
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "showChanges" -> showChanges(chat)
            "openDiff" -> msg.str("toolUseId")?.let { id -> session.diffFor(id)?.let(linker::showDiff) }
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
            "resolveImages" -> {
                val reqId = msg.long("reqId") ?: 0
                val paths = strings(msg["paths"]).distinct().take(MAX_IMAGE_PATHS)
                val cwd = chat.cwd
                offEdt {
                    val found = ArrayList<Pair<String, String>>()
                    try {
                        for (path in paths) {
                            try {
                                localImage(path, cwd)?.let { found += path to it }
                            } catch (e: Throwable) {
                                if (e is ControlFlowException) throw e
                                // this path shows its alt text; the others still get answered
                            }
                        }
                    } finally {
                        onEdt { post(imagesMessage(reqId, found)) } // also when a cancellation is being rethrown
                    }
                }
            }
        }
    }

    private fun slotsUsed() = chat.attachments.size + uploads.count + preparing.size

    private fun attachFile(file: VirtualFile) {
        val id = "k${++pickSeq}"
        val name = displayName(file.name)
        if (slotsUsed() >= Images.MAX_PER_MESSAGE) {
            session.notice("Couldn't attach $name: at most ${Images.MAX_PER_MESSAGE} images per message.")
            return
        }
        post(buildJsonObject {
            put("type", "attachment")
            put("id", id)
            put("name", name)
            put("loading", true)
        }.toString())
        prepare(id, name) { if (file.length > Images.MAX_INPUT_BYTES) null else VfsUtilCore.loadBytes(file) }
    }

    /**
     * Validates and (if needed) downscales the bytes off the EDT, then adds the attachment and shows its thumbnail,
     * or refuses it with a notice. [read] runs off the EDT; null means the file is larger than 20 MB.
     */
    private fun prepare(id: String, name: String, read: () -> ByteArray?) {
        preparing += id
        offEdt {
            var thumb = ""
            val outcome = try {
                val bytes = read()
                if (bytes == null) {
                    Prepared.Rejected("it is larger than 20 MB")
                } else {
                    val prepared = preparer(bytes)
                    if (prepared is Prepared.Ok) {
                        thumb = Images.preview(prepared.image.bytes, Images.THUMB_EDGE, Images.THUMB_BYTES)?.dataUrl().orEmpty()
                    }
                    prepared
                }
            } catch (e: Throwable) {
                if (e is ControlFlowException) {
                    onEdt { if (preparing.remove(id)) refuse(id, "Couldn't attach $name: cancelled.") }
                    throw e
                }
                Prepared.Rejected(if (e is OutOfMemoryError) "the image is too large to process" else Images.UNREADABLE)
            }
            onEdt {
                if (!preparing.remove(id)) return@onEdt // removed (×) or the page reloaded meanwhile
                when (outcome) {
                    is Prepared.Rejected -> refuse(id, "Couldn't attach $name: ${outcome.reason}.")
                    is Prepared.Ok ->
                        if (chat.attachments.add(Attachment(id, name, outcome.image, thumb))) post(attachmentMessage(id, name, thumb))
                        else refuse(id, "Couldn't attach $name: at most ${Images.MAX_PER_MESSAGE} images per message.")
                }
            }
        }
    }

    /** Tells the page the attachment is gone and the chat why. */
    private fun refuse(id: String, notice: String) {
        uploads.cancel(id)
        session.notice(notice)
        post(buildJsonObject {
            put("type", "attachment")
            put("id", id)
            put("failed", true)
        }.toString())
    }

    private fun attachmentMessage(id: String, name: String, thumb: String) = buildJsonObject {
        put("type", "attachment")
        put("id", id)
        put("name", name)
        put("thumb", thumb)
    }.toString()

    /** Stops history updates. Called when the page goes away. */
    fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
        uploads.clear() // abandoned uploads and in-flight preparations go with the page
        preparing.clear()
    }

    /** One `reset` message, then `items` messages, each at most [BATCH_CHARS] long (images make items large). */
    override fun reset(items: List<ChatItem>) {
        val batches = batches(items.map { it.toJson() }, BATCH_CHARS)
        post(buildJsonObject {
            put("type", "reset")
            put("items", JsonArray(batches.firstOrNull().orEmpty()))
        }.toString())
        for (batch in batches.drop(1)) post(buildJsonObject {
            put("type", "items")
            put("items", JsonArray(batch))
        }.toString())
    }

    override fun item(item: ChatItem) = post(buildJsonObject {
        put("type", "item")
        put("item", item.toJson())
    }.toString())

    override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) = post(buildJsonObject {
        put("type", "state")
        put("state", state.name)
        put("sessionId", sessionId)
        put("mode", mode?.cli)
    }.toString())

    override fun activity(activity: Activity?) = post(activityMessage(activity))

    override fun context(usage: ContextUsage) = post(usageMessage(usage))

    override fun suggestion(text: String) = post(buildJsonObject {
        put("type", "suggestion")
        put("text", text)
    }.toString())

    override fun insert(text: String) = post(buildJsonObject {
        put("type", "insert")
        put("text", text)
    }.toString())

    override fun checkout(text: String) = post(buildJsonObject {
        put("type", "checkout")
        put("text", text)
    }.toString())

    /** `{ "<question>": { "labels": [..], "other": ".." } }`, or null if the shape is wrong anywhere (the session checks the content). */
    private fun questionAnswers(element: JsonElement?): Map<String, QuestionAnswer>? {
        val map = element as? JsonObject ?: return null
        val out = LinkedHashMap<String, QuestionAnswer>()
        for ((question, value) in map) {
            val a = value as? JsonObject ?: return null
            val labelsRaw = a["labels"] as? JsonArray ?: return null
            val labels = strings(labelsRaw)
            if (labels.size != labelsRaw.size) return null
            val otherRaw = a["other"]
            if (otherRaw != null && otherRaw !is JsonNull && a.str("other") == null) return null
            out[question] = QuestionAnswer(labels, a.str("other"))
        }
        return out
    }

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun imagesMessage(reqId: Long, found: List<Pair<String, String>>) = buildJsonObject {
        put("type", "images")
        put("reqId", reqId)
        putJsonObject("images") { for ((path, url) in found) put(path, url) }
    }.toString()

    private fun historyMessage(entries: List<String>) = buildJsonObject {
        put("type", "history")
        put("entries", JsonArray(entries.map(::JsonPrimitive)))
    }.toString()

    private fun resolvedMessage(reqId: Long, candidates: List<String>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for (candidate in candidates) {
                val resolved = linker.resolve(candidate, chat.cwd) ?: continue
                put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
            }
        }
    }.toString()

    internal companion object {
        const val BATCH_CHARS = 2_000_000

        /** Longest tool name shown in the strip. */
        const val MAX_TOOL_NAME = 40

        /** `{type:"activity", phase, tool?}`; a null phase hides the strip. The tool name is cleaned like the editor-context names. */
        fun activityMessage(activity: Activity?): String = buildJsonObject {
            put("type", "activity")
            put("phase", when (activity) {
                null -> null
                Activity.Starting -> "starting"
                Activity.Thinking -> "thinking"
                is Activity.Running -> "running"
                Activity.Writing -> "writing"
                Activity.Waiting -> "waiting"
            })
            if (activity is Activity.Running) put("tool", ContextNotes.clean(activity.tool, MAX_TOOL_NAME).ifEmpty { "tool" })
        }.toString()

        /** Longest category name shown in the context tooltip. */
        const val MAX_CATEGORY_NAME = 40

        /** `{type:"usage", used, max, percent, autocompactAt?, categories:[{name, tokens}]}`; names are cleaned like tool names. */
        fun usageMessage(usage: ContextUsage): String = buildJsonObject {
            put("type", "usage")
            put("used", usage.used)
            put("max", usage.max)
            put("percent", usage.percent)
            usage.autocompactAt?.let { put("autocompactAt", it) }
            putJsonArray("categories") {
                for (c in usage.categories) {
                    val name = ContextNotes.clean(c.name, MAX_CATEGORY_NAME)
                    if (name.isNotEmpty()) addJsonObject {
                        put("name", name)
                        put("tokens", c.tokens)
                    }
                }
            }
        }.toString()

        /** All image work (decoding, scaling, reading files) runs here, one task at a time. */
        private val IMAGE_EXECUTOR = AppExecutorUtil.createBoundedApplicationPoolExecutor("ClaudeChat images", 1)
        /** Local images one reply may ask for at a time. */
        const val MAX_IMAGE_PATHS = 20

        /** Consecutive groups of [items] whose JSON adds up to at most [limit] characters; a larger item goes alone. */
        fun batches(items: List<JsonObject>, limit: Int): List<List<JsonObject>> {
            val out = mutableListOf<MutableList<JsonObject>>()
            var size = 0
            for (item in items) {
                val length = item.toString().length
                if (out.isEmpty() || size + length > limit) {
                    out += mutableListOf<JsonObject>()
                    size = 0
                }
                out.last() += item
                size += length
            }
            return out
        }

        private val ID = Regex("[A-Za-z0-9_-]{1,32}")

        /** A file name for notices: plain text, one line, at most 80 characters. */
        private fun displayName(raw: String?): String =
            raw?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "image"
    }
}
