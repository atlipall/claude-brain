package tether.ui

import tether.cli.ContextUsage
import tether.cli.ModelChoice
import tether.cli.PermissionMode
import tether.cli.arr
import tether.cli.long
import tether.cli.parseJsonObject
import tether.cli.str
import tether.ide.CheckoutDiff
import tether.ide.FileLinker
import tether.ide.ResolvedPath
import tether.image.Attachment
import tether.image.Images
import tether.image.Prepared
import tether.image.Uploads
import tether.context.ContextNotes
import tether.session.Activity
import tether.session.Chat
import tether.session.ChatItem
import tether.session.FileDiff
import tether.session.PermissionDecision
import tether.session.QuestionAnswer
import tether.session.QueuedMessage
import tether.session.PromptHistory
import tether.session.SessionState
import tether.session.SessionView
import tether.session.toJson
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Connects one chat page to one [Chat]: web-view messages in, chat updates out. */
class ChatBridge(
    private val project: Project,
    private val chat: Chat,
    private val post: (String) -> Unit,
) : SessionView {
    private val linker get() = FileLinker.getInstance(project)
    private val session get() = chat.session
    private val history get() = PromptHistory.getInstance(project)
    @Volatile private var unsubscribe: (() -> Unit)? = null

    /** Runs image work (decoding, scaling, reading files) off the EDT. Tests make it synchronous. */
    internal var offEdt: (() -> Unit) -> Unit = { task -> IMAGE_EXECUTOR.execute { task() } }

    /** Resolves the paths a reply mentions off the EDT, apart from image work so neither waits behind the other. Tests make it synchronous. */
    internal var pathsOffEdt: (() -> Unit) -> Unit = { task -> PATH_EXECUTOR.execute { task() } }

    /** Validates and downscales image bytes. Tests replace it. */
    internal var preparer: (ByteArray) -> Prepared = { Images.prepare(it) }

    /** Display copy of a local image for the page (see [FileLinker.localImage]). Tests replace it. */
    internal var localImage: (String, String?) -> String? = { path, cwd -> linker.localImage(path, cwd) }

    /** Brings results back to the EDT. Tests make it synchronous. */
    internal var onEdt: (() -> Unit) -> Unit = { task -> ApplicationManager.getApplication().invokeLater({ task() }, project.disposed) }

    /** Opens the checkout's changes (see [CheckoutDiff]). Tests replace it. */
    internal var showChanges: (Chat) -> Unit = { CheckoutDiff.show(project, it) }

    /** Opens a turn's file diffs at the [Int]th (see [FileLinker.showTurnDiffs]). Tests replace it. */
    internal var showTurnDiffs: (List<FileDiff>, Int) -> Unit = { diffs, index -> linker.showTurnDiffs(diffs, index) }

    /** Opens all of a turn's files in the changes browser (see [FileLinker.showTurnChanges]). Tests replace it. */
    internal var showTurnChanges: (List<FileDiff>) -> Unit = { linker.showTurnChanges(it) }

    /** Shows the IDE file chooser for images and calls back with the chosen files (EDT). Tests replace it. */
    internal var chooseImages: ((List<VirtualFile>) -> Unit) -> Unit = { done ->
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp")
            .withTitle("Attach Images")
        FileChooser.chooseFiles(descriptor, project, null) { files -> done(files) }
    }

    private val uploads = Uploads()
    /** Ids whose bytes are being prepared off the EDT; a `detach` (or a page reload) removes one so its result is dropped. EDT only. */
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
                forgetAttachments()
                chat.attach(this)
            }
            "send" -> {
                val text = msg.str("text").orEmpty()
                val images = chat.attachments.take(strings(msg["attachments"]))
                if (text.isBlank() && images.isEmpty()) return
                remember(text)
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
            "setModel" -> msg.str("model")?.takeIf { it.isNotBlank() }?.let(session::setModel)
            "setEffort" -> msg.str("effort")?.takeIf { it.isNotBlank() }?.let(session::setEffort)
            "interrupt" -> chat.interrupt()
            "queueSendNow" -> msg.str("id")?.let(session::sendNow)
            "queueEdit" -> msg.str("id")?.let(chat::editQueued)
            "queueRemove" -> msg.str("id")?.let(session::unqueue)
            "restart" -> session.restart()
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "showChanges" -> showChanges(chat)
            "revertTo" -> msg.str("id")?.let(chat::revertTo)
            "openDiff" -> msg.str("toolUseId")?.let { id -> session.diffFor(id)?.let(linker::showDiff) }
            "openTurnDiff" -> turnDiffs(msg)?.let { diffs ->
                val index = msg.long("index")?.toInt() ?: 0
                if (index in diffs.indices) showTurnDiffs(diffs, index) else session.notice(TURN_TOO_OLD, isError = false)
            }
            "openTurnChanges" -> turnDiffs(msg)?.let(showTurnChanges)
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> resolvePaths(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.distinct().take(MAX_PATH_CANDIDATES))
            "resolveImages" -> resolveImages(msg.long("reqId") ?: 0, strings(msg["paths"]).distinct().take(MAX_IMAGE_PATHS))
        }
    }

    /** Forgets every attachment, started upload and preparation: a (re)loaded page shows none. */
    private fun forgetAttachments() {
        uploads.clear()
        preparing.clear()
        chat.attachments.clear()
    }

    /** Adds [text] to the input history, unless it is blank (history keeps text only) or the chat is dead ([Chat.send] ignores it). */
    private fun remember(text: String) {
        if (session.state != SessionState.DEAD && text.isNotBlank()) history.add(text)
    }

    /**
     * Answers request [reqId] with the local images at [paths], read off the EDT. A path that can't be read is left
     * out, so the page shows its alt text while the others are still answered; the answer goes out even when a
     * cancellation is being rethrown.
     */
    private fun resolveImages(reqId: Long, paths: List<String>) {
        val cwd = chat.cwd
        offEdt {
            val found = ArrayList<Pair<String, String>>()
            try {
                for (path in paths) {
                    try {
                        localImage(path, cwd)?.let { found += path to it }
                    } catch (e: Throwable) {
                        if (e is ControlFlowException) throw e
                    }
                }
            } finally {
                onEdt { post(imagesMessage(reqId, found)) }
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
                if (!preparing.remove(id)) return@onEdt
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

    /** Stops history updates and drops abandoned uploads and in-flight preparations, which go with the page. Called when the page goes away. */
    fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
        uploads.clear()
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

    override fun state(state: SessionState, mode: PermissionMode?) = post(buildJsonObject {
        put("type", "state")
        put("state", state.name)
        put("mode", mode?.cli)
    }.toString())

    override fun activity(activity: Activity?) = post(activityMessage(activity))

    override fun context(usage: ContextUsage) = post(usageMessage(usage))

    override fun model(choice: ModelChoice) = post(modelMessage(choice))

    override fun suggestion(text: String) = post(buildJsonObject {
        put("type", "suggestion")
        put("text", text)
    }.toString())

    override fun queue(messages: List<QueuedMessage>) = post(buildJsonObject {
        put("type", "queue")
        put("items", JsonArray(messages.map { ChatItem.UserText(it.id, it.text, it.images).toJson() }))
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

    /**
     * Answers the page with the [candidates] that are files, resolved [pathsOffEdt] because a lookup that misses refreshes
     * the file system. The page is always answered, with what was found, even when the lookup fails.
     */
    private fun resolvePaths(reqId: Long, candidates: List<String>) {
        val cwd = chat.cwd
        pathsOffEdt {
            val found = LinkedHashMap<String, ResolvedPath>()
            try {
                for (candidate in candidates) linker.resolve(candidate, cwd)?.let { found[candidate] = it }
            } finally {
                onEdt { post(resolvedMessage(reqId, found)) }
            }
        }
    }

    private fun resolvedMessage(reqId: Long, found: Map<String, ResolvedPath>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for ((candidate, resolved) in found) put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
        }
    }.toString()

    /** The diffs of the turn [msg] names, or null after saying the turn is too old to have them. */
    private fun turnDiffs(msg: JsonObject): List<FileDiff>? =
        msg.str("id")?.let(session::turnDiffs).orEmpty().ifEmpty { session.notice(TURN_TOO_OLD, isError = false); null }

    internal companion object {
        const val BATCH_CHARS = 2_000_000

        /** Longest tool name shown in the strip. */
        const val MAX_TOOL_NAME = 40

        const val TURN_TOO_OLD = "That turn is too old: its changes are no longer kept."

        /** `{type:"activity", phase, tool?, count?}`; a null phase hides the strip. The tool name is cleaned like the editor-context names. */
        fun activityMessage(activity: Activity?): String = buildJsonObject {
            put("type", "activity")
            put("phase", when (activity) {
                null -> null
                Activity.Starting -> "starting"
                Activity.Thinking -> "thinking"
                is Activity.Running -> "running"
                Activity.Writing -> "writing"
                Activity.Waiting -> "waiting"
                is Activity.Agents -> "agents"
            })
            if (activity is Activity.Agents) put("count", activity.count)
            if (activity is Activity.Running) put("tool", ContextNotes.clean(activity.tool, MAX_TOOL_NAME).ifEmpty { "tool" })
        }.toString()

        /** Longest category name shown in the context tooltip. */
        const val MAX_CATEGORY_NAME = 40
        /** Longest model name or description shown in the picker. */
        const val MAX_MODEL_TEXT = 60

        /** The header's model and effort pickers; option names and descriptions are cleaned like context category names. */
        fun modelMessage(choice: ModelChoice): String = buildJsonObject {
            put("type", "model")
            put("model", choice.model)
            choice.effort?.let { put("effort", it) }
            putJsonArray("efforts") { choice.efforts.forEach { add(it) } }
            putJsonArray("options") {
                for (o in choice.options) addJsonObject {
                    put("value", o.value)
                    put("name", ContextNotes.clean(o.name, MAX_MODEL_TEXT).ifEmpty { o.value })
                    o.description?.let { ContextNotes.clean(it, MAX_MODEL_TEXT) }?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                }
            }
        }.toString()

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
        private val IMAGE_EXECUTOR = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tether images", 1)
        /** Local images one reply may ask for at a time. */
        private val PATH_EXECUTOR = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tether paths", 1)
        const val MAX_IMAGE_PATHS = 20
        const val MAX_PATH_CANDIDATES = 200

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
