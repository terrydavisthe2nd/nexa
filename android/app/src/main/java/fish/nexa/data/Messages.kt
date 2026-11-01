package fish.nexa.data

import fish.nexa.crypto.Bytes
import fish.nexa.crypto.DecryptError
import fish.nexa.crypto.Envelope
import fish.nexa.crypto.MessageCrypto
import fish.nexa.crypto.MessagePayload
import fish.nexa.net.Api
import fish.nexa.net.Message
import fish.nexa.net.PollTally
import fish.nexa.net.ReactionSummary
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentHashMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Why a message shows no words: its key hasn't come, it won't open, or it opened past what a message may hold (MessageCrypto.Limits). */
enum class DecryptFail { NO_KEY, BAD, INVALID }

/** A message as the app holds it: the row, and what it says once opened. */
data class ChatMessage(
    val m: Message,
    val payload: MessagePayload? = null,
    val error: DecryptFail? = null,
    /** On a call's line: how the call went (outcome, duration, joined, declined), sealed by its caller. */
    val call: JsonObject? = null,
    /** A webhook's message: its plain content, held to what can be drawn. */
    val hook: JsonObject? = null,
    /** A line only this device knows (an app's notice that something failed): gone when dismissed or the conversation loads again. */
    val local: Boolean = false,
) {
    val id get() = m.id
    val channelId get() = m.channelId
    val authorId get() = m.authorId
    val text get() = payload?.t
}

/** A message on its way: shown at once, settled when the server's copy (with its nonce) arrives. */
data class PendingMessage(
    val tmpId: String,
    val nonce: String,
    val channelId: String,
    val authorId: String,
    val payload: MessagePayload,
    val createdAt: Long,
    val status: String = "sending",
    val replyToId: String? = null,
    val ping: Boolean = true,
    val flags: Long = 0,
    val upload: PendingUpload? = null,
)

data class PendingUpload(val files: List<PendingFile>, val loaded: Long, val total: Long, val speed: Double = 0.0, val done: Boolean = false, val error: String? = null)
data class PendingFile(val id: String, val name: String, val size: Long, val type: String, val preview: String?)

data class ChannelMessages(
    /** Ascending ids. */
    val ids: PersistentList<String> = persistentListOf(),
    val byId: PersistentMap<String, ChatMessage> = persistentHashMapOf(),
    val hasOlder: Boolean = true,
    val hasNewer: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Int = 0,
    val pending: PersistentList<PendingMessage> = persistentListOf(),
)

/**
 * Messages per conversation (web/src/stores/messages.ts): pages of history
 * decrypted as they arrive, live events folded in, the sender's own copies
 * shown at once and settled by nonce.
 */
class MessagesStore(private val api: Api, private val keys: KeyManager) {
    private val _channels = MutableStateFlow(persistentHashMapOf<String, ChannelMessages>())
    val channels: StateFlow<PersistentMap<String, ChannelMessages>> = _channels
    private val generations = HashMap<String, Int>()

    /** Asks for the people a page names, so their names show. */
    var resolveAuthors: ((Collection<String>) -> Unit)? = null
    /** Every page of history seen (for the search index). */
    var onHistory: ((String, List<ChatMessage>) -> Unit)? = null

    fun get(channelId: String): ChannelMessages = _channels.value[channelId] ?: ChannelMessages()

    private fun gen(ch: String) = synchronized(generations) { generations[ch] ?: 0 }
    private fun bump(ch: String) = synchronized(generations) { generations[ch] = (generations[ch] ?: 0) + 1 }

    private fun with(ch: String, fn: (ChannelMessages) -> ChannelMessages) = _channels.update { s ->
        val cur = s[ch] ?: ChannelMessages()
        val next = fn(cur)
        if (next === cur) s else s.put(ch, next)
    }

    /** Opens a message with the keys this device holds. Never throws. */
    fun decrypt(msg: Message): ChatMessage {
        // a webhook's or an app's message comes readable, with nothing to decrypt
        if (Webhooks.cameReadable(msg)) {
            val hook = Webhooks.sanitize(msg.webhook) ?: return ChatMessage(msg, error = DecryptFail.BAD)
            if (msg.type.startsWith("system_")) return ChatMessage(msg, hook = hook)
            return ChatMessage(msg, payload = Webhooks.payloadOf(hook), hook = hook)
        }
        // a published post's copy: sealed to the follow it came through, not with this channel's key
        if (msg.type == "crosspost") {
            if (msg.crosspost?.deleted == true) return ChatMessage(msg)
            val opened = Follows.openCopy(msg) ?: return ChatMessage(msg, error = DecryptFail.NO_KEY)
            return opened.fold({ ChatMessage(msg, payload = it) }, { ChatMessage(msg, error = DecryptFail.BAD) })
        }
        val ct = msg.ciphertext ?: return ChatMessage(msg)
        if (ct is kotlinx.serialization.json.JsonNull) return ChatMessage(msg)
        if (msg.type == "system_call") return decryptCallLine(msg)
        val env = Envelope.from(ct)
        val conv = msg.keyConvId
        val epoch = msg.keyEpoch
        if (env == null || conv == null || epoch == null) return ChatMessage(msg, error = DecryptFail.BAD)
        val key = keys.channelKey(conv, epoch, msg.channelId) ?: return waitFor(msg, conv)
        return try {
            ChatMessage(msg, payload = MessageCrypto.decrypt(key, env, fish.nexa.crypto.Aad(conv, epoch, msg.channelId, msg.authorId)))
        } catch (e: DecryptError) {
            ChatMessage(msg, error = if (e.message == "over_limits") DecryptFail.INVALID else DecryptFail.BAD)
        }
    }

    /** A message this device lacks the key for: it waits, and the holders are asked for it (lib/decrypt.ts). */
    private fun waitFor(msg: Message, conv: String): ChatMessage {
        keys.markWaiting(conv)
        return ChatMessage(msg, error = DecryptFail.NO_KEY)
    }

    /** A call's line: sealed with the channel key, bound to the line itself (voice/callSummary.ts). */
    private fun decryptCallLine(msg: Message): ChatMessage {
        val env = Envelope.from(msg.ciphertext) ?: return ChatMessage(msg)
        val conv = msg.keyConvId ?: return ChatMessage(msg)
        val epoch = msg.keyEpoch ?: return ChatMessage(msg)
        val key = keys.channelKey(conv, epoch, msg.channelId) ?: return waitFor(msg, conv)
        return try {
            val aad = Bytes.utf8("call|$conv|$epoch|${msg.channelId}|${msg.authorId}|${msg.id}")
            val pt = Bytes.gcmDecrypt(key, Bytes.unb64(env.iv), Bytes.unb64(env.ct), aad)
            ChatMessage(msg, call = MessageCrypto.json.parseToJsonElement(Bytes.str(pt)) as? JsonObject)
        } catch (_: Exception) {
            ChatMessage(msg)
        }
    }

    private fun put(c: ChannelMessages, msgs: List<ChatMessage>): ChannelMessages {
        if (msgs.isEmpty()) return c
        val byId = c.byId.builder()
        val fresh = ArrayList<String>()
        for (m in msgs) {
            if (m.id !in byId) fresh += m.id
            byId[m.id] = m
        }
        return c.copy(byId = byId.build(), ids = mergeIds(c.ids, fresh))
    }

    private fun mergeIds(existing: PersistentList<String>, added: List<String>): PersistentList<String> {
        if (added.isEmpty()) return existing
        val last = existing.lastOrNull()
        if (added.size == 1 && (last == null || Ids.compare(added[0], last) > 0)) return existing.add(added[0])
        val set = existing.toHashSet()
        val out = ArrayList(existing)
        for (id in added) if (set.add(id)) out += id
        out.sortWith { a, b -> Ids.compare(a, b) }
        return out.toPersistentList()
    }

    /** New messages for a window that has moved back in history are left for when it returns. */
    private fun arrived(c: ChannelMessages, msgs: List<ChatMessage>): ChannelMessages {
        val newest = c.ids.lastOrNull()
        if (!c.hasNewer || newest == null) return put(c, msgs)
        return put(c, msgs.filter { Ids.compare(it.id, newest) <= 0 })
    }

    private fun keepNewest(c: ChannelMessages, n: Int): ChannelMessages {
        if (c.ids.size <= n) return c
        val drop = c.ids.subList(0, c.ids.size - n)
        val byId = c.byId.builder()
        drop.forEach { byId.remove(it) }
        return c.copy(ids = c.ids.subList(c.ids.size - n, c.ids.size).toPersistentList(), byId = byId.build())
    }

    fun insert(ch: String, msgs: List<ChatMessage>) {
        if (msgs.isEmpty()) return
        resolveAuthors?.invoke(msgs.flatMap { listOf(it.authorId) + it.m.mentions })
        ReplyTargets.ingest(msgs.map { it.m })
        with(ch) { c -> if (c.loaded) arrived(c, msgs) else keepNewest(arrived(c, msgs), 50) }
    }

    fun settle(ch: String, msgs: List<ChatMessage>, tmpId: String? = null, nonce: String? = null) {
        resolveAuthors?.invoke(msgs.map { it.authorId })
        ReplyTargets.ingest(msgs.map { it.m })
        with(ch) { c ->
            val kept = c.pending.filter { (tmpId == null || it.tmpId != tmpId) && (nonce == null || it.nonce != nonce) }
            val next = arrived(c, msgs)
            if (kept.size == c.pending.size) next else next.copy(pending = kept.toPersistentList())
        }
    }

    /** An edit or a change from the server: keep this device's reactions and votes, take the rest. */
    fun update(ch: String, msg: ChatMessage) {
        // a quoted original follows its edits, loaded here or not
        ReplyTargets.refresh(msg)
        ReplyTargets.ingest(listOf(msg.m))
        updateLoaded(ch, msg)
    }

    private fun updateLoaded(ch: String, msg: ChatMessage) = with(ch) { c ->
        val prev = c.byId[msg.id] ?: return@with c
        // the reactions already here stay (their own events keep them current), and so does the thread it
        // started: an edited copy (a person's, an app's, an app's answer) comes without it
        var m = msg.m.copy(reactions = prev.m.reactions, thread = msg.m.thread ?: prev.m.thread)
        val pr = prev.m.pollResults
        val nr = msg.m.pollResults
        if (nr != null && pr != null) {
            val mine = pr.answerCounts.filter { it.meVoted }.map { it.id }.toSet()
            m = m.copy(pollResults = nr.copy(answerCounts = nr.answerCounts.map { it.copy(meVoted = it.id in mine) }))
        }
        c.copy(byId = c.byId.put(msg.id, msg.copy(m = m)))
    }

    /** A thread started from a message: the message says so under it at once. */
    fun setThread(ch: String, id: String, thread: fish.nexa.net.ThreadSummary) = with(ch) { c ->
        val prev = c.byId[id] ?: return@with c
        if (prev.m.thread?.id == thread.id) return@with c
        c.copy(byId = c.byId.put(id, prev.copy(m = prev.m.copy(thread = thread))))
    }

    fun removeMany(ch: String, ids: Collection<String>) {
        // replies to what went say it was deleted, wherever they are
        ReplyTargets.markDeleted(ids)
        removeLoaded(ch, ids)
    }

    private fun removeLoaded(ch: String, ids: Collection<String>) = with(ch) { c ->
        val drop = ids.toHashSet()
        if (c.ids.none { it in drop }) return@with c
        val byId = c.byId.builder()
        ids.forEach { byId.remove(it) }
        c.copy(byId = byId.build(), ids = c.ids.filter { it !in drop }.toPersistentList())
    }

    fun setReaction(ch: String, messageId: String, emoji: String, add: Boolean, isMe: Boolean) = with(ch) { c ->
        val cm = c.byId[messageId] ?: return@with c
        val reactions = cm.m.reactions.toMutableList()
        val idx = reactions.indexOfFirst { it.emoji == emoji }
        if (add) {
            if (idx >= 0) {
                val r = reactions[idx]
                if (isMe && r.me) return@with c
                reactions[idx] = r.copy(count = r.count + 1, me = r.me || isMe)
            } else reactions += ReactionSummary(emoji, 1, isMe)
        } else if (idx >= 0) {
            val r = reactions[idx]
            if (isMe && !r.me) return@with c
            val n = r.count - 1
            if (n <= 0) reactions.removeAt(idx) else reactions[idx] = r.copy(count = n, me = if (isMe) false else r.me)
        } else return@with c
        c.copy(byId = c.byId.put(messageId, cm.copy(m = cm.m.copy(reactions = reactions))))
    }

    /** Takes a message's reactions away: every one, or every one of one emoji. */
    fun clearReactions(ch: String, messageId: String, emoji: String?) = with(ch) { c ->
        val cm = c.byId[messageId] ?: return@with c
        val reactions = if (emoji == null) emptyList() else cm.m.reactions.filter { it.emoji != emoji }
        c.copy(byId = c.byId.put(messageId, cm.copy(m = cm.m.copy(reactions = reactions))))
    }

    fun setPollVote(ch: String, messageId: String, answerId: Int, add: Boolean, isMe: Boolean, voters: Int?) = with(ch) { c ->
        val cm = c.byId[messageId] ?: return@with c
        val tally = cm.m.pollResults ?: return@with c
        val a = tally.answerCounts.firstOrNull { it.id == answerId } ?: return@with c
        val total = voters ?: tally.totalVoters
        if (isMe && a.meVoted == add) {
            return@with if (total == tally.totalVoters) c else c.copy(byId = c.byId.put(messageId, cm.copy(m = cm.m.copy(pollResults = tally.copy(totalVoters = total)))))
        }
        val counts = tally.answerCounts.map {
            if (it.id != answerId) it else it.copy(count = maxOf(0, it.count + if (add) 1 else -1), meVoted = if (isMe) add else it.meVoted)
        }
        c.copy(byId = c.byId.put(messageId, cm.copy(m = cm.m.copy(pollResults = tally.copy(answerCounts = counts, totalVoters = total)))))
    }

    fun setPollResults(ch: String, messageId: String, results: PollTally) = with(ch) { c ->
        val cm = c.byId[messageId] ?: return@with c
        c.copy(byId = c.byId.put(messageId, cm.copy(m = cm.m.copy(pollResults = results))))
    }

    fun setFlags(ch: String, id: String, flags: Long) = with(ch) { c ->
        val cm = c.byId[id] ?: return@with c
        c.copy(byId = c.byId.put(id, cm.copy(m = cm.m.copy(flags = flags))))
    }

    fun addPending(p: PendingMessage) = with(p.channelId) { it.copy(pending = it.pending.add(p)) }
    fun removePending(ch: String, tmpId: String) = with(ch) { c -> c.copy(pending = c.pending.filter { it.tmpId != tmpId }.toPersistentList()) }
    fun failPending(ch: String, tmpId: String) = updatePending(ch, tmpId) { it.copy(status = "failed") }
    fun updatePending(ch: String, tmpId: String, fn: (PendingMessage) -> PendingMessage) = with(ch) { c ->
        if (c.pending.none { it.tmpId == tmpId }) c else c.copy(pending = c.pending.map { if (it.tmpId == tmpId) fn(it) else it }.toPersistentList())
    }
    fun resolvePendingByNonce(ch: String, nonce: String) = with(ch) { c ->
        if (c.pending.none { it.nonce == nonce }) c else c.copy(pending = c.pending.filter { it.nonce != nonce }.toPersistentList())
    }

    fun clearChannel(ch: String) {
        bump(ch)
        _channels.update { it.remove(ch) }
    }

    /** On a new ready: everything goes but the conversations named, and sends still on their way. */
    fun clearAll(keep: Set<String>) = _channels.update { s ->
        for (id in s.keys) if (id !in keep) bump(id)
        val out = persistentHashMapOf<String, ChannelMessages>().builder()
        for ((id, c) in s) {
            if (id in keep) out[id] = c else if (c.pending.isNotEmpty()) out[id] = ChannelMessages(pending = c.pending)
        }
        out.build()
    }

    fun returnToPresent(ch: String) {
        bump(ch)
        with(ch) { ChannelMessages(pending = it.pending) }
    }

    private suspend fun list(ch: String, before: String? = null, after: String? = null, around: String? = null, limit: Int = PAGE): List<Message> {
        val q = StringBuilder("/channels/$ch/messages?limit=$limit")
        before?.let { q.append("&before=$it") }
        after?.let { q.append("&after=$it") }
        around?.let { q.append("&around=$it") }
        return api.get(q.toString(), ListSerializer(Message.serializer()))
    }

    private suspend fun decryptAll(page: List<Message>): List<ChatMessage> {
        // the originals the page's replies point at came along with it
        ReplyTargets.ingest(page)
        return withContext(Dispatchers.Default) { page.map { decrypt(it) } }
    }

    suspend fun loadInitial(ch: String) {
        val cur = _channels.value[ch]
        if (cur?.loaded == true || cur?.loading == true) return
        val g = gen(ch)
        with(ch) { it.copy(loading = true) }
        try {
            val page = list(ch)
            val dec = decryptAll(page)
            if (gen(ch) != g) return
            resolveAuthors?.invoke(dec.flatMap { listOf(it.authorId) + it.m.mentions })
            with(ch) { c -> put(c, dec).copy(loaded = true, loading = false, failed = 0, hasOlder = page.size >= PAGE, hasNewer = false) }
            if (dec.isNotEmpty()) onHistory?.invoke(ch, dec)
        } catch (e: Exception) {
            android.util.Log.w("NexaMessages", "first page of $ch failed", e)
            if (gen(ch) == g) with(ch) { it.copy(loading = false, failed = it.failed + 1) }
        }
    }

    suspend fun loadOlder(ch: String) {
        val cur = _channels.value[ch] ?: return
        if (cur.loading || !cur.hasOlder) return
        val oldest = cur.ids.firstOrNull() ?: return loadInitial(ch)
        val g = gen(ch)
        with(ch) { it.copy(loading = true) }
        try {
            val page = list(ch, before = oldest)
            val dec = decryptAll(page)
            if (gen(ch) != g) return
            with(ch) { it.copy(hasOlder = page.size >= PAGE) }
            insert(ch, dec)
            if (dec.isNotEmpty()) onHistory?.invoke(ch, dec)
        } catch (_: Exception) {
        } finally {
            if (gen(ch) == g) with(ch) { it.copy(loading = false) }
        }
    }

    suspend fun loadNewer(ch: String) {
        val cur = _channels.value[ch] ?: return
        val newest = cur.ids.lastOrNull() ?: return
        if (cur.loading || !cur.hasNewer) return
        val g = gen(ch)
        with(ch) { it.copy(loading = true) }
        try {
            val page = list(ch, after = newest)
            val dec = decryptAll(page)
            if (gen(ch) != g) return
            resolveAuthors?.invoke(dec.map { it.authorId })
            with(ch) { c -> put(c, dec).copy(hasNewer = page.size >= PAGE) }
            if (dec.isNotEmpty()) onHistory?.invoke(ch, dec)
        } catch (_: Exception) {
        } finally {
            if (gen(ch) == g) with(ch) { it.copy(loading = false) }
        }
    }

    /** Jumps to a message far back: the window becomes the page around it. */
    suspend fun loadAround(ch: String, messageId: String) {
        bump(ch)
        val g = gen(ch)
        with(ch) { ChannelMessages(pending = it.pending, loading = true) }
        try {
            val page = list(ch, around = messageId)
            val dec = decryptAll(page)
            if (gen(ch) != g) return
            resolveAuthors?.invoke(dec.map { it.authorId })
            val half = PAGE / 2
            val idx = page.indexOfFirst { it.id == messageId }
            with(ch) { c ->
                put(c, dec).copy(loaded = true, loading = false, hasOlder = idx < 0 || idx >= half, hasNewer = page.size - (if (idx < 0) 0 else idx) >= PAGE - half)
            }
            if (dec.isNotEmpty()) onHistory?.invoke(ch, dec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the list that asked went away (a cold open recomposes it): the jump is still waiting for the next one
            if (gen(ch) == g) with(ch) { it.copy(loading = false) }
            throw e
        } catch (_: Exception) {
            if (gen(ch) == g) with(ch) { it.copy(loading = false, failed = it.failed + 1) }
        }
    }

    /**
     * After a reconnect: fetch what was missed, then re-read what is held so
     * edits, deletions and reactions made meanwhile show (web syncGap).
     */
    suspend fun syncGap(ch: String) {
        val cur = _channels.value[ch]
        val newest = cur?.ids?.lastOrNull()
        val oldest = cur?.ids?.firstOrNull()
        if (cur?.loaded != true || newest == null || oldest == null) {
            loadInitial(ch)
            return
        }
        val g = gen(ch)
        try {
            if (!cur.hasNewer) {
                var after = newest
                var filled = false
                var i = 0
                while (i < 5 && !filled) {
                    val page = list(ch, after = after)
                    val dec = decryptAll(page)
                    if (gen(ch) != g) return
                    insert(ch, dec)
                    filled = page.size < PAGE
                    if (!filled) after = page.last().id
                    i++
                }
                if (!filled) {
                    returnToPresent(ch)
                    loadInitial(ch)
                    return
                }
            }
            var before = Ids.next(newest)
            repeat(REFRESH_PAGES) {
                val page = list(ch, before = before)
                if (gen(ch) != g) return
                val known = _channels.value[ch]?.byId ?: return
                val fresh = page.filter { it.id in known }.map { refreshed(known[it.id]!!, it) }
                val upto = before
                val floor = if (page.size >= PAGE) page.first().id else null
                val returned = page.map { it.id }.toHashSet()
                with(ch) { c ->
                    val gone = c.ids.filter { Ids.compare(it, upto) < 0 && (floor == null || Ids.compare(it, floor) >= 0) && it !in returned }
                    val changed = fresh.filter { it !== known[it.id] && c.byId[it.id] === known[it.id] }
                    if (gone.isEmpty() && changed.isEmpty()) return@with c
                    val byId = c.byId.builder()
                    changed.forEach { byId[it.id] = it }
                    gone.forEach { byId.remove(it) }
                    val drop = gone.toHashSet()
                    c.copy(byId = byId.build(), ids = if (gone.isNotEmpty()) c.ids.filter { it !in drop }.toPersistentList() else c.ids)
                }
                if (floor == null || Ids.compare(floor, oldest) <= 0) return
                before = floor
            }
        } catch (_: Exception) {
            if (gen(ch) != g) return
            returnToPresent(ch)
            loadInitial(ch)
        }
    }

    private fun refreshed(prev: ChatMessage, m: Message): ChatMessage {
        if (prev.m.editedAt != m.editedAt || prev.m.ciphertext != m.ciphertext || prev.m.webhook != m.webhook) return decrypt(m)
        if (prev.m.flags == m.flags && prev.m.reactions == m.reactions && prev.m.attachments == m.attachments && prev.m.pollResults == m.pollResults) return prev
        return prev.copy(m = m)
    }

    /** Opens again what waited for keys that have now arrived. */
    suspend fun redecrypt(convs: Set<String>) {
        ReplyTargets.redecrypt(convs)
        val snapshot = _channels.value
        for ((ch, c) in snapshot) {
            val waiting = c.byId.values.filter { it.error == DecryptFail.NO_KEY && it.m.keyConvId in convs || it.m.type == "system_call" && it.call == null && it.m.keyConvId in convs }
            if (waiting.isEmpty()) continue
            val opened = withContext(Dispatchers.Default) { waiting.map { decrypt(it.m) } }
            with(ch) { cur ->
                val b = cur.byId.builder()
                for (o in opened) if (cur.byId[o.id] != null) b[o.id] = o
                cur.copy(byId = b.build())
            }
        }
    }

    /** Opens again a channel's published copies that could not be read, once its follows' keys are here. */
    suspend fun reopenCopies(channelId: String) {
        val c = _channels.value[channelId] ?: return
        val waiting = c.byId.values.filter { it.m.type == "crosspost" && it.error != null }
        if (waiting.isEmpty()) return
        val opened = withContext(Dispatchers.Default) { waiting.map { decrypt(it.m) } }
        with(channelId) { cur ->
            val b = cur.byId.builder()
            for (o in opened) if (cur.byId[o.id] != null) b[o.id] = o
            cur.copy(byId = b.build())
        }
    }

    fun clear() {
        ReplyTargets.clear()
        synchronized(generations) { for (k in generations.keys) generations[k] = generations[k]!! + 1 }
        _channels.value = persistentHashMapOf()
    }

    companion object {
        const val PAGE = 50
        const val REFRESH_PAGES = 10
    }
}

/**
 * Webhook messages: posted by programs, not encrypted, read as the web reads
 * them (lib/webhookMessage.ts, ported in ui/markdown/Webhook.kt). The raw
 * object is kept on the message; [content] is its checked form.
 */
object Webhooks {
    /** Whether a message's content came readable: a webhook's, or an app's (`application_id` in it). */
    fun cameReadable(msg: Message): Boolean {
        val w = msg.webhook ?: return false
        if (w is kotlinx.serialization.json.JsonNull) return false
        if (msg.webhookId != null) return true
        return ((w as? JsonObject)?.get("application_id") as? kotlinx.serialization.json.JsonPrimitive)?.isString == true
    }

    /** The message's webhook object when it is a well-formed one. */
    fun sanitize(el: JsonElement?): JsonObject? = if (fish.nexa.ui.markdown.Webhook.sanitize(el) != null) el as? JsonObject else null

    fun content(hook: JsonObject?): fish.nexa.ui.markdown.Webhook.Content? = fish.nexa.ui.markdown.Webhook.of(hook)

    /** What the row draws as the message itself: the webhook's words and the files it lists. */
    fun payloadOf(hook: JsonObject): MessagePayload {
        val h = fish.nexa.ui.markdown.Webhook.of(hook) ?: return MessagePayload()
        // an app's sticker stands where a person's would
        val sticker = h.stickers.firstOrNull()?.let { fish.nexa.crypto.StickerRef(it.id, blob = it.blobId, name = it.name, format = it.format) }
        return MessagePayload(t = h.content.takeIf { it.isNotEmpty() }, att = fish.nexa.ui.markdown.Webhook.listedAttachments(h).takeIf { it.isNotEmpty() }, sticker = sticker)
    }
}
