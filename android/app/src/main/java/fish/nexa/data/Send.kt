package fish.nexa.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import fish.nexa.Nexa
import fish.nexa.crypto.Aad
import fish.nexa.crypto.AttachmentMeta
import fish.nexa.crypto.AttachmentPreview
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.FileCrypto
import fish.nexa.crypto.MessageCrypto
import fish.nexa.crypto.MessagePayload
import fish.nexa.crypto.PollPayload
import fish.nexa.crypto.StickerRef
import fish.nexa.net.ApiError
import fish.nexa.net.Message
import fish.nexa.net.wireJson
import fish.nexa.ui.markdown.MessageLimit
import fish.nexa.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/** A file picked for a message, copied into the app's cache (and cleaned of its metadata) before it goes. */
data class Staged(
    val id: String,
    val file: File,
    val name: String,
    val type: String,
    val spoiler: Boolean = false,
    /** A local picture to show while it uploads (the file itself, for pictures and videos). */
    val preview: String? = null,
    /** A voice message recorded in the box: how long it runs (seconds) and the shape of its sound. */
    val voice: VoiceNote? = null,
) {
    val size: Long get() = file.length()
}

/** A voice message's length and shape, as lib/voiceMessage.ts sends them. */
data class VoiceNote(val duration: Double, val wave: String)

/** What the composer asks for besides the words (send.ts SendOptions). */
data class SendOptions(
    val replyTo: String? = null,
    val ping: Boolean? = null,
    val attachments: List<Uploaded>? = null,
    val embeds: List<JsonObject>? = null,
    /**
     * Link previews still on their way (components/composer/linkPreviews.ts):
     * made by the sender, so recipients never fetch the address, waited for
     * just before the message is sealed, and sealed inside it.
     */
    val embedsLater: (suspend () -> List<JsonObject>)? = null,
    val sticker: StickerRef? = null,
    val flags: Long = 0,
    val replaces: String? = null,
    val poll: PollPayload? = null,
    /** AutoMod has read it already (Send.screen): what to tell it once the message is sent. */
    val screened: ((String) -> Unit)? = null,
    /** Passes on another message (data/Forward.kt): its files go as they are, and nobody is pinged. */
    val forward: Forwarding? = null,
)

/** The message a forward passes on, and when that one was first sent. */
data class Forwarding(val channelId: String, val messageId: String, val at: Long, val comment: String? = null)

data class Uploaded(val meta: AttachmentMeta, val blobId: String, val size: Long)

/** Slow mode: when each channel lets you send again (stores/slowmode.ts). */
object Slowmode {
    private val _until = MutableStateFlow<Map<String, Long>>(emptyMap())
    val until: StateFlow<Map<String, Long>> = _until
    private val sent = HashMap<String, Long>()

    fun setUntil(channelId: String, at: Long) = _until.update { it + (channelId to at) }

    @Synchronized fun markSent(channelId: String) {
        sent[channelId] = System.currentTimeMillis()
        val ch = Nexa.channels.get(channelId) ?: return
        val secs = ch.slowmodeSeconds
        if (secs <= 0) return
        val sid = ch.serverId ?: return
        val st = Nexa.servers.get(sid) ?: return
        if (Nexa.servers.canI(st, P.MANAGE_MESSAGES, channelId) || Nexa.servers.canI(st, P.MANAGE_CHANNELS, channelId)) return
        setUntil(channelId, System.currentTimeMillis() + secs * 1000L)
    }
}

/**
 * Sending, editing and the rest of what a person does to messages
 * (components/composer/send.ts, lib/attachments.ts, messageMenu.ts). A send
 * shows at once as a pending row, then goes out in order with the others for
 * that channel; a failure leaves the row with Retry and Delete.
 */
object Send {
    private val rng = SecureRandom()
    private var tmpCounter = 0
    private val queues = HashMap<String, Mutex>()
    @Volatile private var account = 0

    private class UploadJob(val files: List<Staged>, var stop: Job? = null, val sent: HashMap<String, Uploaded> = HashMap())
    private val uploading = HashMap<String, UploadJob>()

    fun nonce(): String = Bytes.random(12).joinToString("") { "%02x".format(it) }

    @Synchronized fun tmpId(): String {
        tmpCounter += 1
        return "tmp-${System.currentTimeMillis()}-$tmpCounter"
    }

    private fun mutexOf(channelId: String): Mutex = synchronized(queues) { queues.getOrPut(channelId) { Mutex() } }

    /** Runs a send after the ones before it in this channel, so messages arrive in the order they were written. */
    private suspend fun <T> inTurn(channelId: String, job: suspend () -> T): T? {
        val mine = account
        return mutexOf(channelId).withLock { if (mine == account) job() else null }
    }

    data class Written(val payload: MessagePayload, val mentions: List<String>, val roles: List<String>, val everyone: Boolean)

    private fun written(channelId: String, rawText: String, opts: SendOptions): Written {
        val ex = Mentions.extract(rawText, channelId)
        val payload = MessagePayload(
            t = ex.text.ifEmpty { null },
            att = opts.attachments?.takeIf { it.isNotEmpty() }?.map { it.meta },
            sticker = opts.sticker,
            embeds = opts.embeds?.takeIf { it.isNotEmpty() },
            poll = opts.poll,
            fwdAt = opts.forward?.at,
            fwdComment = opts.forward?.comment,
        )
        // someone else's words, passed on: they ping nobody
        if (opts.forward != null) return Written(payload, emptyList(), emptyList(), false)
        return Written(payload, ex.mentions, ex.roles, ex.everyone)
    }

    private fun pendingOf(channelId: String, tmp: String): PendingMessage? = Nexa.messages.get(channelId).pending.firstOrNull { it.tmpId == tmp }

    /** What AutoMod makes of a message before it goes. */
    sealed interface Screened {
        /** Stopped: the box keeps the words and says why. */
        data object Stopped : Screened
        /** Let through: what to tell AutoMod once it is sent. */
        class Through(val afterSend: (String) -> Unit) : Screened
        /** The rules are still on their way: the send itself waits for them. */
        data object Later : Screened
    }

    /**
     * Reads a message with the server's AutoMod rules before it goes, when the
     * rules are here to say. The names of files about to go up count as they
     * will once they are attached.
     */
    fun screen(channelId: String, rawText: String, files: List<String>, opts: SendOptions = SendOptions()): Screened {
        val ch = Nexa.channels.get(channelId) ?: return Screened.Through {}
        val me = Nexa.users.meId ?: return Screened.Through {}
        if (ch.serverId == null) return Screened.Through {}
        val w = written(channelId, rawText, opts)
        val payload = if (files.isEmpty()) w.payload else w.payload.copy(att = files.map { fish.nexa.crypto.AttachmentMeta("", it, "", 0L, "") })
        val verdict = Automod.screenOutgoing(ch, me, payload, onlyIfLoaded = true) ?: return Screened.Later
        verdict.stopped?.let {
            AutomodNotices.stopped(channelId, it, null)
            return Screened.Stopped
        }
        AutomodNotices.dismiss(channelId)
        return Screened.Through(verdict.afterSend)
    }

    /** Stopped while it was on its way: nothing waits in the list to be sent again, and the words go back in the box. */
    private class AutomodStop(val c: Automod.Catch) : Exception("automod_blocked")

    fun sendMessage(channelId: String, rawText: String, opts: SendOptions = SendOptions()) {
        val me = Nexa.users.me.value ?: return
        val w = written(channelId, rawText, opts)
        val p = w.payload
        if (p.t == null && p.att == null && p.sticker == null && p.poll == null) return
        // nothing goes out that the apps would refuse to show (MessageCrypto.Limits), whoever asks to send it
        if (refused(p)) return
        val nonce = nonce()
        val tmp = tmpId()
        Nexa.messages.addPending(PendingMessage(tmp, nonce, channelId, me.id, p, System.currentTimeMillis(), replyToId = opts.replyTo, ping = opts.ping ?: true, flags = opts.flags))
        if (opts.replaces != null) Nexa.messages.removePending(channelId, opts.replaces)
        // pressing send until the server has it, earlier messages queued ahead included
        val began = android.os.SystemClock.elapsedRealtime()
        Nexa.scope.launch { runCatching { inTurn(channelId) { deliver(channelId, me.id, w, tmp, nonce, opts, began) } } }
    }

    /** Whether a message breaks what a message may hold; the box says so, and it stays unsent. */
    private fun refused(p: MessagePayload): Boolean {
        if (MessageCrypto.brokenLimit(p.toJson()) == null) return false
        Nexa.ui.toast(t(MessageLimit.TOO_LONG_KEY, "count" to MessageLimit.MAX_MESSAGE_LENGTH), "error")
        return true
    }

    fun sendPoll(channelId: String, poll: PollPayload) = sendMessage(channelId, "", SendOptions(poll = poll))

    fun sendWithFiles(channelId: String, rawText: String, files: List<Staged>, opts: SendOptions = SendOptions()) {
        val me = Nexa.users.me.value ?: return
        val w = written(channelId, rawText, opts)
        if (refused(w.payload)) return
        val nonce = nonce()
        val tmp = tmpId()
        val upload = PendingUpload(files.map { PendingFile(it.id, it.name, it.size, it.type, it.preview) }, 0, files.sumOf { it.size })
        Nexa.messages.addPending(
            PendingMessage(tmp, nonce, channelId, me.id, w.payload, System.currentTimeMillis(), replyToId = opts.replyTo, ping = opts.ping ?: true, flags = opts.flags, upload = upload),
        )
        synchronized(uploading) { uploading[tmp] = UploadJob(files) }
        Nexa.scope.launch { uploadThenSend(channelId, me.id, tmp, nonce, w, opts) }
    }

    private fun forget(tmp: String) {
        val job = synchronized(uploading) { uploading.remove(tmp) } ?: return
        for (f in job.files) runCatching { f.file.delete() }
    }

    private class Speedometer {
        private var at = 0L
        private var last = 0L
        private var speed = 0.0
        fun feed(bytes: Long, now: Long): Double {
            if (at == 0L) {
                at = now; last = bytes; return speed
            }
            val seconds = (now - at) / 1000.0
            if (seconds < 0.4) return speed
            val instant = maxOf(0.0, (bytes - last) / seconds)
            speed = if (speed > 0) speed * 0.6 + instant * 0.4 else instant
            at = now
            last = bytes
            return speed
        }
    }

    private const val FILES_AT_ONCE = 3
    private const val TOGETHER_MAX = 16L * 1024 * 1024

    private suspend fun uploadThenSend(channelId: String, meId: String, tmp: String, nonce: String, w: Written, opts: SendOptions) {
        val job = synchronized(uploading) { uploading[tmp] } ?: return
        val total = job.files.sumOf { it.size }
        val speed = Speedometer()
        var shownAt = 0L
        val loaded = HashMap<String, Long>()
        fun progress() {
            var n = 0L
            synchronized(loaded) { for (f in job.files) n += if (job.sent.containsKey(f.id)) f.size else (loaded[f.id] ?: 0) }
            val now = System.currentTimeMillis()
            val bps = speed.feed(n, now)
            if (now - shownAt < 200) return
            shownAt = now
            val cur = pendingOf(channelId, tmp)?.upload ?: return
            Nexa.messages.updatePending(channelId, tmp) { it.copy(upload = cur.copy(loaded = minOf(n, total), speed = bps)) }
        }
        val ups: List<Uploaded>
        try {
            coroutineScope {
                val worker = launch {
                    val todo = job.files.filter { !job.sent.containsKey(it.id) }
                    val single = Nexa.auth.instance.value?.uploadSingleBytes ?: (32L shl 20)
                    val together = todo.filter { it.size <= TOGETHER_MAX && FileCrypto.encryptedSize(it.size) <= single }
                    val alone = todo.filter { it !in together }
                    suspend fun lanes(list: List<Staged>, width: Int) {
                        var next = 0
                        val lock = Mutex()
                        coroutineScope {
                            repeat(minOf(width, list.size)) {
                                launch {
                                    while (true) {
                                        val f = lock.withLock { if (next < list.size) list[next++] else null } ?: break
                                        val up = Uploads.uploadStaged("/channels/$channelId/attachments", f, { p ->
                                            synchronized(loaded) { loaded[f.id] = (p * f.size).toLong() }
                                            progress()
                                        })
                                        synchronized(job.sent) { job.sent[f.id] = up }
                                    }
                                }
                            }
                        }
                    }
                    lanes(together, FILES_AT_ONCE)
                    lanes(alone, 1)
                }
                job.stop = worker
                worker.join()
                if (worker.isCancelled) throw CancellationException()
            }
            ups = job.files.map { job.sent[it.id]!! }
        } catch (e: CancellationException) {
            return
        } catch (e: Exception) {
            val msg = (e as? ApiError)?.message ?: e.message ?: t("composer.composer.uploadFailed")
            val cur = pendingOf(channelId, tmp)
            if (cur?.upload != null) Nexa.messages.updatePending(channelId, tmp) { it.copy(status = "failed", upload = cur.upload.copy(speed = 0.0, error = msg)) }
            Nexa.ui.toast(msg, "error")
            return
        } finally {
            job.stop = null
        }
        val cur = pendingOf(channelId, tmp)
        if (cur?.upload == null) return forget(tmp)
        val payload = w.payload.copy(att = ups.map { it.meta })
        Nexa.messages.updatePending(channelId, tmp) { it.copy(payload = payload, upload = cur.upload.copy(loaded = total, speed = 0.0, done = true)) }
        runCatching { inTurn(channelId) { deliver(channelId, meId, w.copy(payload = payload), tmp, nonce, opts.copy(attachments = ups)) } }
        forget(tmp)
    }

    private fun replyAuthor(channelId: String, messageId: String): String? {
        val m = Nexa.messages.get(channelId).byId[messageId]?.m ?: return null
        return if (m.webhookId != null) null else m.authorId
    }

    private suspend fun deliver(channelId: String, meId: String, w: Written, tmp: String, nonce: String, opts: SendOptions, began: Long = 0L) {
        var payload = w.payload
        opts.embedsLater?.let { later ->
            val found = runCatching { later() }.getOrDefault(emptyList())
            if (found.isNotEmpty()) {
                payload = payload.copy(embeds = payload.embeds.orEmpty() + found)
                // kept on the pending row too, so a retry still carries them
                Nexa.messages.updatePending(channelId, tmp) { it.copy(payload = payload) }
            }
        }
        try {
            // AutoMod reads it here when its rules were not in yet as it was sent
            var afterSend = opts.screened
            val ch = Nexa.channels.get(channelId)
            if (afterSend == null && ch?.serverId != null) {
                Automod.ready(ch.serverId)
                val verdict = Automod.screenOutgoing(ch, meId, payload)
                verdict?.stopped?.let { throw AutomodStop(it) }
                afterSend = verdict?.afterSend
            }
            val conv = Nexa.channels.convFor(channelId)
            val (epoch, _) = Nexa.keys.ensureConvKey(conv, current = true) ?: throw IllegalStateException("waiting_for_key")
            val chanKey = Nexa.keys.channelKey(conv, epoch, channelId) ?: throw IllegalStateException("waiting_for_key")
            val env = withContext(Dispatchers.Default) { MessageCrypto.encrypt(chanKey, Aad(conv, epoch, channelId, meId), payload) }
            val replyTo = opts.replyTo
            val author = replyTo?.let { replyAuthor(channelId, it) }
            var mentions = if (opts.ping == false && author != null) w.mentions.filter { it != author } else w.mentions
            if (replyTo != null && opts.ping != false && author != null && author != meId && author !in mentions) mentions = mentions + author
            val body = buildJsonObject {
                put("ciphertext", env.toJson())
                put("key_conv_id", conv)
                put("key_epoch", epoch)
                putJsonArray("mentions") { mentions.forEach { add(JsonPrimitive(it)) } }
                put("nonce", nonce)
                if (replyTo != null) put("reply_to_id", replyTo)
                if (w.everyone) put("mention_everyone", true)
                if (w.roles.isNotEmpty()) putJsonArray("mention_roles") { w.roles.forEach { add(JsonPrimitive(it)) } }
                val att = opts.attachments
                if (!att.isNullOrEmpty()) putJsonArray("attachments") {
                    for (a in att) {
                        add(buildJsonObject { put("blob_id", a.blobId); put("size", a.size) })
                        a.meta.preview?.let { p ->
                            add(buildJsonObject { put("blob_id", p.blobId); put("size", FileCrypto.encryptedSize(p.size)); put("preview", true) })
                        }
                    }
                }
                if (opts.flags != 0L) put("flags", opts.flags)
                opts.forward?.let { f -> put("forward", buildJsonObject { put("channel_id", f.channelId); put("message_id", f.messageId) }) }
                payload.poll?.let { p ->
                    put("poll", buildJsonObject {
                        put("answer_count", p.answers.size)
                        put("duration", p.hours)
                        put("allow_multiselect", p.multiselect)
                    })
                }
                // the apps it is addressed to read their own copy, sealed to their bots
                val copies = BotCopies.seal(BotCopies.addressed(channelId, mentions, author, meId), payload)
                if (copies.isNotEmpty()) put("bot_copies", buildJsonObject { for ((k, v) in copies) put(k, v) })
            }
            val sent = wireJson.decodeFromJsonElement(Message.serializer(), Nexa.api.request("POST", "/channels/$channelId/messages", body))
            afterSend?.invoke(sent.id)
            var dec = withContext(Dispatchers.Default) { Nexa.messages.decrypt(sent) }
            if (dec.payload == null) dec = dec.copy(payload = payload, error = null)
            Nexa.messages.settle(channelId, listOf(dec), tmpId = tmp, nonce = nonce)
            if (began != 0L) PerfReports.stage("send", (android.os.SystemClock.elapsedRealtime() - began).toDouble())
            Nexa.channels.setLastMessage(channelId, sent.id)
            Slowmode.markSent(channelId)
        } catch (e: Exception) {
            // stopped by AutoMod: it never went, so nothing waits in the list to be sent again
            if (e is AutomodStop || (e is ApiError && e.code == "automod_blocked")) {
                val words = pendingOf(channelId, tmp)?.payload?.t
                Nexa.messages.removePending(channelId, tmp)
                if (e is AutomodStop) AutomodNotices.stopped(channelId, e.c, words)
                else {
                    val d = (e as ApiError).details
                    fun field(k: String) = (d?.get(k) as? JsonPrimitive)?.contentOrNull
                    val custom = (d?.get("custom") as? JsonPrimitive)?.booleanOrNull == true
                    AutomodNotices.show(channelId, AutomodNotices.Notice(if (custom) e.message ?: "" else "", field("rule") ?: "", field("kind") ?: "", text = words))
                }
                throw e
            }
            Nexa.messages.failPending(channelId, tmp)
            if (e is ApiError && e.code == "slowmode") {
                val secs = e.retryAfter?.takeIf { it > 0 } ?: 5
                Slowmode.setUntil(channelId, System.currentTimeMillis() + secs * 1000L)
                Nexa.ui.toast(t("composer.send.slowModeTryAgain", "seconds" to secs), "error")
            } else {
                Nexa.ui.toast(errorText(e) ?: (e as? ApiError)?.message ?: t("composer.send.yourMessageCouldNotBeSent"), "error")
            }
            throw e
        }
    }

    fun errorText(e: Throwable): String? {
        if (e is ApiError) return when (e.code) {
            "rate_limited" -> t("composer.send.youAreSendingMessagesTooQuickly")
            "message_too_long", "too_large" -> tooLongMessage()
            "cannot_create_polls" -> t("poll.create.notAllowed")
            "automod_blocked" -> t("automod.notice.stoppedEdit")
            else -> null
        }
        return when (e.message) {
            "automod_blocked" -> t("automod.notice.stoppedEdit")
            "waiting_for_key" -> t("composer.send.waitingForTheEncryptionKeyFor")
            "too_long" -> tooLongMessage()
            else -> null
        }
    }

    const val MAX_MESSAGE_LENGTH = 4000
    fun tooLong(text: String) = text.trim().length > MAX_MESSAGE_LENGTH
    fun tooLongMessage() = t("composer.composer.messagesAreLimitedTo", "count" to MAX_MESSAGE_LENGTH)

    /** Saves an edit (send.ts editMessage); throws for the caller to report. */
    suspend fun editMessage(channelId: String, messageId: String, rawText: String) {
        val me = Nexa.users.me.value ?: return
        val existing = Nexa.messages.get(channelId).byId[messageId] ?: return
        val old = existing.payload ?: return
        if (tooLong(rawText)) throw IllegalStateException("too_long")
        val ex = Mentions.extract(rawText, channelId)
        val mentions = ex.mentions.toMutableList()
        val replied = existing.m.replyToId?.let { replyAuthor(channelId, it) }
        if (replied != null && replied in existing.m.mentions && replied !in mentions) mentions += replied
        var payload = old.copy(t = ex.text.ifEmpty { null })
        val before = Mentions.urls(old.t ?: "")
        val after = Mentions.urls(rawText)
        if (!payload.embeds.isNullOrEmpty() && before != after) {
            val keep = after.toSet()
            val kept = payload.embeds!!.filter { e -> (e["url"] as? JsonPrimitive)?.contentOrNull in keep }
            payload = payload.copy(embeds = kept.ifEmpty { null })
        }
        if (payload.t == null && payload.att.isNullOrEmpty() && payload.sticker == null) return
        // an edit is read by AutoMod as a new message is
        var afterEdit: ((String) -> Unit)? = null
        Nexa.channels.get(channelId)?.takeIf { it.serverId != null }?.let { ch ->
            Automod.ready(ch.serverId!!)
            val verdict = Automod.screenOutgoing(ch, me.id, payload)
            verdict?.stopped?.let {
                AutomodNotices.stopped(channelId, it, null)
                throw IllegalStateException("automod_blocked")
            }
            afterEdit = verdict?.afterSend
        }
        val conv = Nexa.channels.convFor(channelId)
        val (epoch, _) = Nexa.keys.ensureConvKey(conv, current = true) ?: throw IllegalStateException("waiting_for_key")
        val chanKey = Nexa.keys.channelKey(conv, epoch, channelId)
        if (chanKey == null) throw IllegalStateException("waiting_for_key")
        val env = withContext(Dispatchers.Default) { MessageCrypto.encrypt(chanKey, Aad(conv, epoch, channelId, me.id), payload) }
        val body = buildJsonObject {
            put("ciphertext", env.toJson())
            put("key_conv_id", conv)
            put("key_epoch", epoch)
            putJsonArray("mentions") { mentions.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("mention_roles") { ex.roles.forEach { add(JsonPrimitive(it)) } }
            put("mention_everyone", ex.everyone)
            // the apps it is addressed to get the edit too; an app no longer addressed loses its copy
            val copies = BotCopies.seal(BotCopies.addressed(channelId, mentions, replied, me.id), payload)
            put("bot_copies", buildJsonObject { for ((k, v) in copies) put(k, v) })
        }
        val updated = wireJson.decodeFromJsonElement(Message.serializer(), Nexa.api.request("PATCH", "/channels/$channelId/messages/$messageId", body))
        afterEdit?.invoke(messageId)
        Nexa.messages.update(channelId, withContext(Dispatchers.Default) { Nexa.messages.decrypt(updated) })
        // a published post's copies follow its edits
        if (fish.nexa.ui.chat.isPublished(updated)) fish.nexa.ui.chat.republish(channelId, messageId, payload)
    }

    fun retryPending(channelId: String, tmp: String) {
        val p = pendingOf(channelId, tmp) ?: return
        val me = Nexa.users.me.value ?: return
        var opts = SendOptions(replyTo = p.replyToId, ping = p.ping, flags = p.flags, embeds = p.payload.embeds, sticker = p.payload.sticker, poll = p.payload.poll)
        val up = p.upload
        if (up != null && !up.done && synchronized(uploading) { uploading.containsKey(tmp) }) {
            Nexa.messages.updatePending(channelId, tmp) { it.copy(status = "sending", upload = up.copy(loaded = 0, speed = 0.0, error = null)) }
            Nexa.scope.launch { uploadThenSend(channelId, me.id, tmp, p.nonce, written(channelId, p.payload.t ?: "", opts), opts) }
            return
        }
        opts = opts.copy(replaces = tmp)
        p.payload.att?.takeIf { it.isNotEmpty() }?.let { list -> opts = opts.copy(attachments = list.map { Uploaded(it, it.blobId, it.size) }) }
        sendMessage(channelId, p.payload.t ?: "", opts)
        forget(tmp)
    }

    fun discardPending(channelId: String, tmp: String) {
        synchronized(uploading) { uploading[tmp] }?.stop?.cancel()
        forget(tmp)
        Nexa.messages.removePending(channelId, tmp)
    }

    /** Signing out: nothing queued for the old account goes anywhere. */
    fun forgetSends() {
        account++
        synchronized(queues) { queues.clear() }
        val jobs = synchronized(uploading) { uploading.keys.toList() }
        for (tmp in jobs) {
            synchronized(uploading) { uploading[tmp] }?.stop?.cancel()
            forget(tmp)
        }
    }

    // ---- the message menu's actions ----

    private fun enc(emoji: String) = java.net.URLEncoder.encode(emoji, "UTF-8").replace("+", "%20")

    /** Toggles your reaction, at once on screen and back again if the server refuses. */
    fun react(channelId: String, messageId: String, emoji: String, add: Boolean) {
        if (Nexa.users.me.value == null) return
        Nexa.messages.setReaction(channelId, messageId, emoji, add, isMe = true)
        if (add) EmojiUse.note(listOf(emoji))
        Nexa.scope.launch {
            try {
                Nexa.api.request(if (add) "PUT" else "DELETE", "/channels/$channelId/messages/$messageId/reactions/${enc(emoji)}/@me")
            } catch (e: Exception) {
                Nexa.messages.setReaction(channelId, messageId, emoji, !add, isMe = true)
                Nexa.ui.toast((e as? ApiError)?.message ?: t("chat.reactions.couldNotReact"), "error")
            }
        }
    }

    suspend fun reactors(channelId: String, messageId: String, emoji: String, limit: Int = 100): List<String> =
        (Nexa.api.get("/channels/$channelId/messages/$messageId/reactions/${enc(emoji)}?limit=$limit") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    suspend fun setPinned(channelId: String, messageId: String, pinned: Boolean) {
        Nexa.api.request(if (pinned) "PUT" else "DELETE", "/channels/$channelId/pins/$messageId")
    }

    suspend fun delete(channelId: String, messageId: String) {
        Nexa.api.request("DELETE", "/channels/$channelId/messages/$messageId")
        Nexa.messages.removeMany(channelId, listOf(messageId))
    }

    suspend fun deleteMany(channelId: String, ids: List<String>) {
        for (batch in ids.chunked(100)) {
            Nexa.api.request("POST", "/channels/$channelId/messages/bulk-delete", buildJsonObject { putJsonArray("ids") { batch.forEach { add(JsonPrimitive(it)) } } })
            Nexa.messages.removeMany(channelId, batch)
        }
    }

    suspend fun setEmbedsHidden(channelId: String, messageId: String, hidden: Boolean) {
        val cm = Nexa.messages.get(channelId).byId[messageId] ?: return
        val flags = if (hidden) cm.m.flags or Message.MSG_FLAG_SUPPRESS_EMBEDS else cm.m.flags and Message.MSG_FLAG_SUPPRESS_EMBEDS.inv()
        Nexa.api.request("PATCH", "/channels/$channelId/messages/$messageId", buildJsonObject { put("flags", flags and Message.MSG_FLAG_SUPPRESS_EMBEDS) })
        Nexa.messages.setFlags(channelId, messageId, flags)
    }

    suspend fun vote(channelId: String, messageId: String, answerIds: List<Int>) {
        val res = Nexa.api.request("PUT", "/channels/$channelId/polls/$messageId/answers/@me", buildJsonObject { putJsonArray("answer_ids") { answerIds.forEach { add(JsonPrimitive(it)) } } })
        val tally = runCatching { wireJson.decodeFromJsonElement(fish.nexa.net.PollTally.serializer(), res) }.getOrNull() ?: return
        Nexa.messages.setPollResults(channelId, messageId, tally)
    }

    suspend fun voters(channelId: String, messageId: String, answerId: Int): List<String> =
        ((Nexa.api.get("/channels/$channelId/polls/$messageId/answers/$answerId?limit=100") as? JsonObject)?.get("users") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

/**
 * Encrypted uploads (lib/attachments.ts): one request up to the server's
 * single-request size, resumable pieces above it, and for big still pictures a
 * small copy under its own key that lists and previews load instead.
 */
object Uploads {
    /** A file's name held to what a message may hold (MessageCrypto.Limits.FILE_NAME code points), its extension kept (the web's fitName). */
    fun fitName(name: String): String {
        val max = MessageCrypto.Limits.FILE_NAME
        if (name.codePointCount(0, name.length) <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val keep = max - ext.codePointCount(0, ext.length)
        return name.substring(0, name.offsetByCodePoints(0, keep)) + ext
    }

    private const val COPY_SIDE = 1280
    private const val COPY_FROM = 256L * 1024
    private const val COPY_SHARE = 0.6
    private const val COPY_MAX = 1024L * 1024
    private val COPYABLE = setOf("image/jpeg", "image/png", "image/webp", "image/avif", "image/bmp")
    private const val CHUNK_TRIES = 4
    private const val STALL_MS = 5 * 60_000L
    private val OCTET = "application/octet-stream".toMediaType()

    data class Look(val w: Int? = null, val h: Int? = null, val copy: Copy? = null)
    class Copy(val bytes: ByteArray, val type: String, val w: Int, val h: Int)

    /** The largest file (the plan's, or a boosted server's for its channel), the most in one request, and a piece's size. */
    private fun limits(channelId: String? = null): Triple<Long, Long, Long> {
        val i = Nexa.auth.instance.value
        return Triple(Plans.fileMaxIn(channelId), i?.uploadSingleBytes ?: (32L shl 20), i?.uploadChunkBytes ?: (32L shl 20))
    }

    fun fits(size: Long, channelId: String? = null) = FileCrypto.encryptedSize(size) <= limits(channelId).first

    /** The conversation a place to upload is, when it is one: a boosted server takes bigger files. */
    private fun channelOf(path: String): String? = Regex("^/channels/(\\d+)/attachments$").find(path)?.groupValues?.get(1)

    /** A body that counts what has gone, for the progress bar. */
    private class Counted(private val data: ByteArray, private val start: Int, private val onProgress: (Long) -> Unit) : RequestBody() {
        override fun contentType() = OCTET
        override fun contentLength() = (data.size - start).toLong()
        override fun writeTo(sink: BufferedSink) {
            var off = start
            while (off < data.size) {
                val n = minOf(64 * 1024, data.size - off)
                sink.write(data, off, n)
                off += n
                onProgress((off - start).toLong())
            }
        }
    }

    private suspend fun post(path: String, method: String, body: ByteArray, headers: Map<String, String>, offset: Int = 0, onProgress: (Long) -> Unit): JsonObject =
        post(path, method, Counted(body, offset, onProgress), headers)

    private suspend fun post(path: String, method: String, body: RequestBody, headers: Map<String, String>): JsonObject {
        val api = Nexa.api
        val b = api.requestBuilder(path).method(method, body)
        for ((k, v) in headers) b.header(k, v)
        val res = withContext(Dispatchers.IO) { api.execute(api.transfer, b.build(), true) }
        return res as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun animated(head: ByteArray, type: String): Boolean {
        fun ascii(at: Int, n: Int) = String(head, at, minOf(n, maxOf(0, head.size - at)), Charsets.ISO_8859_1)
        fun u32(at: Int) = ((head[at].toLong() and 0xff) shl 24) or ((head[at + 1].toLong() and 0xff) shl 16) or ((head[at + 2].toLong() and 0xff) shl 8) or (head[at + 3].toLong() and 0xff)
        when (type) {
            "image/png" -> {
                var p = 8
                while (p + 8 <= head.size) {
                    val kind = ascii(p + 4, 4)
                    if (kind == "acTL") return true
                    if (kind == "IDAT") return false
                    p += 12 + u32(p).toInt()
                    if (p < 0) return true
                }
                return true
            }
            "image/webp" -> return head.size > 20 && ascii(12, 4) == "VP8X" && (head[20].toInt() and 0x02) != 0
            "image/avif" -> {
                val end = minOf(head.size.toLong(), u32(0)).toInt()
                var p = 8
                while (p + 4 <= end) {
                    if (ascii(p, 4) == "avis") return true
                    p += 4
                }
            }
        }
        return false
    }

    /** Measures a picture and, when it is big and still, makes its small copy (pictureCopy.ts). */
    fun look(file: File, type: String): Look {
        if (!type.startsWith("image/")) return Look()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = fish.nexa.media.PhotoOrientation.of(file)
        val swapped = orientation in 5..8
        val w = (if (swapped) bounds.outHeight else bounds.outWidth).takeIf { it > 0 }
        val h = (if (swapped) bounds.outWidth else bounds.outHeight).takeIf { it > 0 }
        if (w == null || h == null) return Look()
        if (type !in COPYABLE || file.length() < COPY_FROM) return Look(w, h)
        val head = ByteArray(minOf(64 * 1024, file.length().toInt()))
        RandomAccessFile(file, "r").use { it.readFully(head) }
        if (animated(head, type)) return Look(w, h)
        val copy = runCatching { smallCopy(file, w, h, type) }.getOrNull()
        val kept = copy?.takeIf { it.bytes.size <= COPY_MAX && it.bytes.size <= file.length() * COPY_SHARE }
        return Look(w, h, kept)
    }

    private fun smallCopy(file: File, w: Int, h: Int, type: String): Copy? {
        val scale = minOf(1.0, COPY_SIDE.toDouble() / maxOf(w, h))
        val tw = maxOf(1, Math.round(w * scale).toInt())
        val th = maxOf(1, Math.round(h * scale).toInt())
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val src = fish.nexa.media.PhotoOrientation.apply(raw, fish.nexa.media.PhotoOrientation.of(file))
        val scaled = if (src.width != tw || src.height != th) Bitmap.createScaledBitmap(src, tw, th, true).also { if (it !== src) src.recycle() } else src
        val out = ByteArrayOutputStream()
        val webp = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        val ok = scaled.compress(webp, 80, out)
        val result = if (ok) Copy(out.toByteArray(), "image/webp", tw, th) else {
            if (type != "image/jpeg" && scaled.hasAlpha()) null
            else ByteArrayOutputStream().let { o -> if (scaled.compress(Bitmap.CompressFormat.JPEG, 82, o)) Copy(o.toByteArray(), "image/jpeg", tw, th) else null }
        }
        scaled.recycle()
        return result
    }

    private suspend fun sendCopy(path: String, copy: Copy): AttachmentPreview {
        val key = FileCrypto.newFileKey()
        val baseIv = FileCrypto.newBaseIv()
        val body = withContext(Dispatchers.Default) { FileCrypto.encryptBytes(copy.bytes, key, baseIv) }
        val res = post(path, "POST", body, emptyMap()) {}
        val blob = (res["blob_id"] as? JsonPrimitive)?.contentOrNull ?: throw ApiError(200, "bad_response", t("attachments.theServerSAnswerCouldNot"))
        fish.nexa.media.Media.seedBytes(blob, copy.bytes)
        return AttachmentPreview(blob, Bytes.b64(key), copy.bytes.size.toLong(), copy.type, copy.w.toDouble(), copy.h.toDouble())
    }

    private fun worthAnotherTry(e: Throwable) = e is ApiError && e.code != "aborted" && (e.status == 0 || e.status >= 500)

    /** uploadStaged: encrypts and sends one file, returning what the message lists for it. */
    suspend fun uploadStaged(path: String, st: Staged, onProgress: (Double) -> Unit): Uploaded {
        val channelId = channelOf(path)
        val (max, single, _) = limits(channelId)
        if (FileCrypto.encryptedSize(st.size) > max) throw ApiError(0, "too_large", t("attachments.overServerLimit", "limit" to formatSize(max)))
        val total = FileCrypto.encryptedSize(st.size)
        val key = FileCrypto.newFileKey()
        val baseIv = FileCrypto.newBaseIv()
        return try {
            sendStaged(path, st, channelId, total, single, key, baseIv, onProgress)
        } catch (e: ApiError) {
            // this month's uploads used up: a plan with more is offered beside the failed upload
            if (e.code == "upload_month_full") Plans.offer(Plans.Offer.Uploads)
            throw e
        }
    }

    private suspend fun sendStaged(path: String, st: Staged, channelId: String?, total: Long, single: Long, key: ByteArray, baseIv: ByteArray, onProgress: (Double) -> Unit): Uploaded {
        return coroutineScope {
            val looked = async(Dispatchers.Default) { runCatching { look(st.file, st.type) }.getOrDefault(Look()) }
            val copied = async { looked.await().copy?.let { runCatching { sendCopy(path, it) }.getOrNull() } }
            // A file big enough goes in parts the server streams on to
            // storage when it offers that (relayed); otherwise one request up
            // to the single-request limit, pieces through the server above it.
            var opened: JsonObject? = null
            var relayed: JsonObject? = null
            if (total >= RELAY_MIN) {
                val session = Nexa.api.request("POST", "/uploads", buildJsonObject {
                    put("size", total)
                    if (channelId != null) put("channel_id", channelId)
                    put("relay", true)
                }).jsonObject
                when {
                    session["relay"] is JsonObject -> relayed = relayed(session, st, key, baseIv) { onProgress(it.toDouble() / total) }
                    total <= single -> runCatching { Nexa.api.request("DELETE", "/uploads/${(session["id"] as JsonPrimitive).content}") }
                    else -> opened = session
                }
            }
            val res: JsonObject = if (relayed != null) relayed else if (total <= single) {
                val body = withContext(Dispatchers.Default) {
                    RandomAccessFile(st.file, "r").use { FileCrypto.encryptChunks(it, st.size, key, baseIv, 0, FileCrypto.chunkCount(st.size)) }
                }
                try {
                    post(path, "POST", body, emptyMap()) { onProgress(it.toDouble() / total) }
                } catch (e: Exception) {
                    if (!worthAnotherTry(e)) throw e
                    delay(1500)
                    onProgress(0.0)
                    post(path, "POST", body, emptyMap()) { onProgress(it.toDouble() / total) }
                }
            } else inPieces(st, key, baseIv, channelId, opened) { onProgress(it.toDouble() / total) }
            val blob = (res["blob_id"] as? JsonPrimitive)?.contentOrNull ?: throw ApiError(200, "bad_response", t("attachments.theServerSAnswerCouldNot"))
            val size = (res["size"] as? JsonPrimitive)?.longOrNull ?: total
            val seen = looked.await()
            val copy = copied.await()
            val meta = AttachmentMeta(
                blobId = blob, name = st.name, type = st.type.ifEmpty { "application/octet-stream" }, size = st.size, key = Bytes.b64(key),
                w = seen.w?.toDouble(), h = seen.h?.toDouble(), spoiler = if (st.spoiler) true else null, preview = copy,
                voice = st.voice != null, duration = st.voice?.duration, wave = st.voice?.wave,
            )
            if (Regex("^(image|video|audio)/").containsMatchIn(meta.type)) fish.nexa.media.Media.seed(blob, st.file)
            Uploaded(meta, blob, size)
        }
    }

    private suspend fun inPieces(st: Staged, key: ByteArray, baseIv: ByteArray, channelId: String?, opened: JsonObject?, onProgress: (Long) -> Unit): JsonObject = coroutineScope {
        val total = FileCrypto.encryptedSize(st.size)
        val chunks = FileCrypto.chunkCount(st.size)
        val session = opened ?: Nexa.api.request("POST", "/uploads", buildJsonObject { put("size", total); if (channelId != null) put("channel_id", channelId) }).jsonObject
        val id = (session["id"] as JsonPrimitive).content
        val limit = minOf((session["chunk_size"] as? JsonPrimitive)?.longOrNull ?: limits().third, limits().third)
        val perPiece = maxOf(1, ((limit - FileCrypto.HEADER_LEN) / FileCrypto.FRAME_LEN).toInt())
        var sent = 0L
        var movedAt = System.currentTimeMillis()
        try {
            // One prepared piece beside the active request, independent of file size.
            // Each task owns its file handle, so a retry cannot race its seek position.
            fun prepare(first: Int) = async(Dispatchers.Default) {
                RandomAccessFile(st.file, "r").use { raf ->
                    FileCrypto.encryptChunks(raf, st.size, key, baseIv, first, minOf(perPiece, chunks - first))
                }
            }
            // The next piece goes out as soon as the current one's bytes have
            // left, while its answer is still on the way (lib/attachments.ts):
            // through the proxy and a home connection that round trip left the
            // line idle at every piece boundary. The server holds a piece that
            // comes early until the one before it has landed. Its failure is
            // kept as a result, so it cannot cancel the upload before its turn.
            class Ahead(val first: Int, val body: Deferred<ByteArray>) {
                lateinit var reply: Deferred<Result<JsonObject>>
                @Volatile var left = false
                @Volatile var onLeft: (() -> Unit)? = null
            }
            val lock = Any()
            var prepared: Deferred<ByteArray>? = prepare(0)
            var ahead: Ahead? = null
            val shown = java.util.concurrent.atomic.AtomicLong(0)
            fun show(n: Long) {
                if (shown.getAndAccumulate(n) { a, b -> maxOf(a, b) } < n) onProgress(n)
            }
            fun launchNext(next: Int) {
                synchronized(lock) {
                    val body = prepared
                    if (ahead != null || next >= chunks || body == null) return
                    val count = minOf(perPiece, chunks - next)
                    val start = FileCrypto.frameOffset(next)
                    val end = minOf(total, FileCrypto.frameOffset(next + count))
                    prepared = if (next + count < chunks) prepare(next + count) else null
                    val entry = Ahead(next, body)
                    entry.reply = async(start = CoroutineStart.LAZY) {
                        runCatching {
                            post("/uploads/$id", "PATCH", body.await(), mapOf("Upload-Offset" to start.toString()), offset = 0) {
                                show(start + it)
                                if (start + it >= end && !entry.left) {
                                    entry.left = true
                                    entry.onLeft?.invoke()
                                }
                            }
                        }
                    }
                    ahead = entry
                    entry.reply.start()
                }
            }
            try {
                var first = 0
                while (first < chunks) {
                    val count = minOf(perPiece, chunks - first)
                    val start = if (first == 0) 0L else FileCrypto.frameOffset(first)
                    val end = minOf(total, FileCrypto.frameOffset(first + count))
                    if (sent != start) throw ApiError(0, "lost", t("attachments.theUploadLostItsPlace"))
                    val early = synchronized(lock) { ahead.also { ahead = null } }?.takeIf { it.first == first }
                    var pending = early?.reply
                    val whole: ByteArray
                    if (early != null) {
                        // now the current piece: once its bytes have left, the next one goes
                        early.onLeft = { launchNext(first + count) }
                        if (early.left) launchNext(first + count)
                        whole = early.body.await()
                    } else {
                        whole = prepared!!.await()
                        synchronized(lock) { prepared = if (first + count < chunks) prepare(first + count) else null }
                    }
                    var tries = 0
                    while (sent < end) {
                        try {
                            val reply = pending?.let {
                                pending = null
                                it.await().getOrThrow()
                            } ?: run {
                                val skip = (sent - start).toInt()
                                val from = sent
                                post("/uploads/$id", "PATCH", whole, mapOf("Upload-Offset" to sent.toString()), offset = skip) {
                                    show(from + it)
                                    if (from + it >= end) launchNext(first + count)
                                }
                            }
                            val received = (reply["received"] as? JsonPrimitive)?.longOrNull ?: -1
                            if (received <= sent || received > end) throw ApiError(0, "lost", t("attachments.theServerLostTrackOfThis"))
                            sent = received
                            movedAt = System.currentTimeMillis()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val landed = landedSoFar(id, movedAt + STALL_MS) ?: throw e
                            if (landed.first < start || landed.first > end) throw e
                            if (landed.first > sent) {
                                tries = 0
                                movedAt = System.currentTimeMillis()
                            } else if (!landed.second) tries += 1
                            if (tries >= CHUNK_TRIES || System.currentTimeMillis() - movedAt > STALL_MS) throw e
                            sent = landed.first
                            if (sent < end) delay(400L * tries)
                        }
                        show(sent)
                    }
                    first += perPiece
                }
            } finally {
                synchronized(lock) {
                    prepared?.cancel()
                    ahead?.reply?.cancel()
                }
            }
            if (sent != total) throw ApiError(0, "size", t("attachments.theUploadCameOutADifferentSize"))
            finishUpload(id)
        } catch (e: Throwable) {
            runCatching { withContext(kotlinx.coroutines.NonCancellable) { Nexa.api.request("DELETE", "/uploads/$id") } }
            throw e
        }
    }

    /** The smallest file sent in parts the server streams on to storage (api/upload_relay.go): R2's smallest part. */
    private const val RELAY_MIN = 5L shl 20

    /** How many parts of one file are on their way at once. */
    private const val RELAY_LANES = 3

    /**
     * The ciphertext bytes [from, to) of the whole encrypted file, as the
     * web's encryptBytes: storage wants every part but the last the same
     * size, which whole chunks are not (the first carries the header).
     * Returns the encrypted chunks and where the stretch starts in them.
     */
    internal fun encryptBytes(raf: RandomAccessFile, plainSize: Long, key: ByteArray, baseIv: ByteArray, from: Long, to: Long): Pair<ByteArray, Int> {
        val chunks = FileCrypto.chunkCount(plainSize)
        fun chunkAt(byte: Long): Int = if (byte < FileCrypto.HEADER_LEN) 0 else minOf(chunks - 1L, (byte - FileCrypto.HEADER_LEN) / FileCrypto.FRAME_LEN).toInt()
        val first = chunkAt(from)
        val last = chunkAt(to - 1)
        val whole = FileCrypto.encryptChunks(raf, plainSize, key, baseIv, first, last - first + 1)
        val base = if (first == 0) 0L else FileCrypto.frameOffset(first)
        return whole to (from - base).toInt()
    }

    /** A part's body: the stretch [start, start + length) of data, counting what has gone. */
    private class PartBody(private val data: ByteArray, private val start: Int, private val length: Int, private val onProgress: (Long) -> Unit) : RequestBody() {
        override fun contentType() = OCTET
        override fun contentLength() = length.toLong()
        override fun writeTo(sink: BufferedSink) {
            var off = 0
            while (off < length) {
                val n = minOf(64 * 1024, length - off)
                sink.write(data, start + off, n)
                off += n
                onProgress(off.toLong())
            }
        }
    }

    /**
     * Sends a file in equal parts that the server streams on to storage as
     * they arrive, as the web's sendRelayed: a few parts at once, each an
     * ordinary signed-in request to the server, then has the server check and
     * join them. A part that fails goes again; an upload that cannot finish
     * is thrown away, parts and all.
     */
    private suspend fun relayed(session: JsonObject, st: Staged, key: ByteArray, baseIv: ByteArray, onProgress: (Long) -> Unit): JsonObject {
        val id = (session["id"] as JsonPrimitive).content
        val total = (session["size"] as? JsonPrimitive)?.longOrNull ?: FileCrypto.encryptedSize(st.size)
        val r = session["relay"] as JsonObject
        val part = (r["part_size"] as? JsonPrimitive)?.longOrNull ?: 0L
        val parts = (r["parts"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        try {
            if (part <= 0 || parts.toLong() != (total + part - 1) / part) throw ApiError(0, "bad_response", t("attachments.theServerSAnswerCouldNot"))
            val next = java.util.concurrent.atomic.AtomicInteger(1)
            val landed = java.util.concurrent.atomic.AtomicLong(0)
            val inFlight = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            val movedAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            fun report() = onProgress(landed.get() + inFlight.values.sum())
            coroutineScope {
                repeat(minOf(RELAY_LANES, parts)) {
                    launch {
                        while (true) {
                            val n = next.getAndIncrement()
                            if (n > parts) break
                            val from = (n - 1) * part
                            val to = minOf(total, n * part)
                            val (data, start) = withContext(Dispatchers.Default) {
                                RandomAccessFile(st.file, "r").use { encryptBytes(it, st.size, key, baseIv, from, to) }
                            }
                            var tries = 0
                            while (true) {
                                try {
                                    post("/uploads/$id/parts/$n", "PUT", PartBody(data, start, (to - from).toInt()) { inFlight[n] = it; report() }, emptyMap())
                                    inFlight.remove(n)
                                    landed.addAndGet(to - from)
                                    movedAt.set(System.currentTimeMillis())
                                    report()
                                    break
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: ApiError) {
                                    inFlight.remove(n)
                                    report()
                                    val again = e.status == 0 || e.status >= 500 || e.status == 408 || e.code == "rate_limited"
                                    if (!again) throw e
                                    tries++
                                    if (tries >= CHUNK_TRIES || System.currentTimeMillis() - movedAt.get() > STALL_MS) throw e
                                    delay(e.retryAfter?.let { it * 1000L } ?: minOf(30_000L, 1000L shl tries))
                                }
                            }
                        }
                    }
                }
            }
            if (landed.get() != total) throw ApiError(0, "size", t("attachments.theUploadCameOutADifferentSize"))
            return finishUpload(id)
        } catch (e: Throwable) {
            runCatching { withContext(kotlinx.coroutines.NonCancellable) { Nexa.api.request("DELETE", "/uploads/$id") } }
            throw e
        }
    }

    private suspend fun finishUpload(id: String): JsonObject {
        var tries = 1
        while (true) {
            try {
                return Nexa.api.request("POST", "/uploads/$id/finish").jsonObject
            } catch (e: ApiError) {
                if ((e.status in 1..499) || tries >= CHUNK_TRIES) throw e
                delay(400L * tries)
                tries++
            }
        }
    }

    /** How much of an upload has landed, asking again with backoff until the deadline. */
    private suspend fun landedSoFar(id: String, deadline: Long): Pair<Long, Boolean>? {
        var attempt = 0
        while (true) {
            try {
                val st = Nexa.api.get("/uploads/$id").jsonObject
                return ((st["received"] as? JsonPrimitive)?.longOrNull ?: 0L) to (attempt > 0)
            } catch (e: ApiError) {
                if (e.status in 400..499 && e.status != 408 && e.status != 429) return null
                if (System.currentTimeMillis() >= deadline) return null
                delay(minOf(30_000L, 1000L shl minOf(attempt, 5), maxOf(0L, deadline - System.currentTimeMillis())))
                attempt++
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 1) return "${Math.round(gb * 10) / 10.0} GB"
        return "${Math.round(bytes / (1024.0 * 1024))} MB"
    }
}
