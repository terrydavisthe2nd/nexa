package fish.nexa.search

import fish.nexa.data.ChatMessage
import fish.nexa.data.DecryptFail
import fish.nexa.data.Ids
import fish.nexa.net.ApiError
import fish.nexa.net.Channel
import fish.nexa.net.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * How history is walked without being a nuisance to the server or the phone
 * (web/src/search/pacing.ts): the search index's walk and the data export
 * page through whole conversations at this pace.
 */
object Pacing {
    /** The most messages one request may ask for; the server refuses more than 100. */
    const val PAGE = 100

    /** The gap between two history requests while someone waits on them (half the rate budget). */
    const val REQUEST_GAP_MS = 200L

    /** The gap while nobody is waiting: the index filling itself in the background. */
    const val BACKGROUND_GAP_MS = 500L

    /** And slower again while the app is out of sight. */
    const val HIDDEN_GAP_MS = 1_500L

    /** Tries a page gets before its conversation is given up on for now. */
    const val MAX_RETRIES = 4

    private const val BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L

    /** A refusal worth waiting out, as opposed to one that will never succeed (0 is a timeout or a dead network). */
    fun retryable(e: Throwable): Boolean = e is ApiError && (e.status == 429 || e.status == 0 || e.status >= 500)

    /** How long to wait before asking for a page again; the server's own answer wins. */
    fun backoffFor(e: Throwable, attempt: Int): Long {
        val asked = (e as? ApiError)?.retryAfter?.let { it * 1000L } ?: 0L
        val grown = BACKOFF_MS shl attempt.coerceAtMost(20)
        return minOf(maxOf(asked, grown), MAX_BACKOFF_MS)
    }

    /** Whether a page says the walk has reached the edge of what its reader may read: no key for any of it. */
    fun pageIsAllLocked(page: List<ChatMessage>): Boolean = page.isNotEmpty() && page.all { it.error == DecryptFail.NO_KEY }

    /** One past an id, so a page asked for `before` it still includes it. */
    fun justAfter(id: String): String = id.toLongOrNull()?.let { (it + 1).toString() } ?: id
}

/** How far one conversation has been walked. */
class ChannelCrawl(
    /** The newest message held with nothing missing between it and [oldest]. */
    var newest: String? = null,
    /** How far back the walk has reached. */
    var oldest: String? = null,
    /** Walked back to its first message, or to where this reader's keys begin. */
    var done: Boolean = false,
    /** Stopped at messages this device holds no key for; worth one more look next session. */
    var locked: Boolean = false,
    /** Nothing in it can be read here: no key at all, or the server refused its history. */
    var unreadable: Boolean = false,
    /** Something in it was readable, so a locked page further down is where access begins. */
    var readable: Boolean = false,
) {
    fun copy() = ChannelCrawl(newest, oldest, done, locked, unreadable, readable)

    fun toJson(): JsonObject = buildJsonObject {
        newest?.let { put("newest", it) }
        oldest?.let { put("oldest", it) }
        if (done) put("done", true)
        if (locked) put("locked", true)
        if (unreadable) put("unreadable", true)
        if (readable) put("readable", true)
    }
}

class CrawlState(val channels: Map<String, ChannelCrawl>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("channels", JsonObject(channels.mapValues { it.value.toJson() }))
    }

    companion object {
        private fun isId(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

        /** A saved walk as the crawler takes it, with anything that does not look right left out (indexer.ts parseCrawl). */
        fun parse(v: JsonElement?): CrawlState? {
            val o = v as? JsonObject ?: return null
            if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) return null
            val chans = o["channels"] as? JsonObject ?: return null
            val out = LinkedHashMap<String, ChannelCrawl>()
            for ((id, el) in chans) {
                if (!isId(id)) continue
                val c = el as? JsonObject ?: continue
                fun optId(k: String): Pair<Boolean, String?> {
                    val p = c[k] ?: return true to null
                    val s = (p as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false to null
                    return isId(s) to s
                }
                fun optBool(k: String): Pair<Boolean, Boolean> {
                    val p = c[k] ?: return true to false
                    val b = (p as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return false to false
                    return true to b
                }
                val (okN, newest) = optId("newest")
                val (okO, oldest) = optId("oldest")
                val (okD, done) = optBool("done")
                val (okL, locked) = optBool("locked")
                val (okU, unreadable) = optBool("unreadable")
                val (okR, readable) = optBool("readable")
                if (!(okN && okO && okD && okL && okU && okR)) continue
                out[id] = ChannelCrawl(newest, oldest, done, locked, unreadable, readable)
            }
            return CrawlState(out)
        }
    }
}

interface CrawlerDeps {
    /** A page of history, oldest first: before an id, or after one. */
    suspend fun list(channelId: String, before: String?, after: String?, limit: Int): List<Message>
    fun decrypt(m: Message): ChatMessage
    /** The conversations there are to walk. */
    fun channels(): List<Channel>
    /** Hands decrypted messages to the index; answers how many it now holds. */
    suspend fun index(docs: List<SearchDoc>): Int
    fun now(): Long
    suspend fun sleep(ms: Long)
    fun online(): Boolean
    fun hidden(): Boolean
    /** The walk may go on with nobody waiting for it. */
    fun mayWalk(): Boolean = true
    /** There is something new to say about progress. */
    fun changed()
    /** The walk moved on; worth saving. */
    fun save(state: CrawlState)
}

/**
 * The walk that fills the search index (web/src/search/crawler.ts).
 *
 * Messages are end-to-end encrypted, so the only way to search a
 * conversation's history is to fetch its ciphertext and decrypt it here. This
 * walks every conversation the account can read, newest page first, and hands
 * what it decrypts to the index; it remembers how far each conversation has
 * been walked, so a restart picks up where it stopped and later visits only
 * fetch what arrived since.
 *
 * Requests are paced (a fifth of the rate limit in the background, half
 * while a search waits on it, slower while the app is out of sight) and
 * refusals waited out; with nobody waiting it walks only when [CrawlerDeps.mayWalk]
 * says so, since a request every moment keeps a phone's radio at full power; a very long conversation gives way after
 * [SLICE_PAGES] pages so everything else gets its turn; and it stops the
 * moment it is told to. With the index kept in memory only, nothing is walked
 * until a search needs it, and then only the conversations it looks in.
 */
class Crawler(
    private val deps: CrawlerDeps,
    /** Walk everything (the index is kept), or only what a search asks for. */
    private val background: Boolean,
    /** No further back once the index holds this many messages. */
    private val maxDocs: Int,
    initial: CrawlState?,
    docs: Int,
) {
    private val state = LinkedHashMap<String, ChannelCrawl>()
    /** Conversations whose newest message is known to be held, kept so by live messages. */
    private val live = HashSet<String>()
    /** The newest message that arrived live in each conversation this session. */
    private val liveMax = HashMap<String, String>()
    private val slices = HashMap<String, Int>()
    private val retryAt = HashMap<String, Long>()
    private var demand: Set<String>? = null
    private var demandAll = false
    private var job: Job? = null
    private val waker = KChannel<Unit>(KChannel.CONFLATED)
    private var last = 0L
    @Volatile private var docs = docs

    /** Refused and waiting it out right now. */
    @Volatile var throttled = false
        private set

    init {
        initial?.channels?.forEach { (id, c) -> state[id] = c.copy() }
        // A conversation that stopped at messages it had no key for, or whose
        // history the server refused, gets one more look each session: a key
        // may have arrived since, or a role that lets its history be read.
        for (c in state.values) {
            if (!c.locked && !c.unreadable) continue
            c.done = false
            c.unreadable = false
        }
    }

    private val lock = Any()

    /** Reached the most the index may hold; older history is left unread. */
    val capped get() = docs >= maxDocs

    fun snapshot(): CrawlState = synchronized(lock) { CrawlState(LinkedHashMap(state.mapValues { it.value.copy() })) }

    fun setDocs(n: Int) {
        docs = n
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Something changed that may mean there is work: wake the walk if it is resting. */
    fun kick() {
        waker.trySend(Unit)
    }

    /** What a search is waiting on: some conversations, everything (null with `all`), or nothing. */
    fun setDemand(channels: Set<String>?, all: Boolean) {
        synchronized(lock) {
            demand = channels
            demandAll = all
        }
        kick()
    }

    /** A reconnect: live messages may have been missed, so every conversation is caught up again. */
    fun resync() {
        synchronized(lock) {
            live.clear()
            liveMax.clear()
        }
        kick()
    }

    /** A message arrived live and was indexed. */
    fun noteLive(channelId: String, id: String) {
        val save = synchronized(lock) {
            liveMax[channelId] = Ids.max(liveMax[channelId], id)!!
            val c = state[channelId]
            if (c != null && channelId in live) {
                c.newest = Ids.max(c.newest, id)
                true
            } else false
        }
        if (save) deps.save(snapshot())
    }

    fun forget(channelId: String) {
        synchronized(lock) {
            state.remove(channelId)
            live.remove(channelId)
            liveMax.remove(channelId)
            slices.remove(channelId)
        }
        deps.save(snapshot())
    }

    /** Starts a conversation's walk over, for history on disk that could not be read. */
    fun restart(channelId: String) {
        synchronized(lock) {
            state[channelId] = ChannelCrawl()
            live.remove(channelId)
        }
    }

    /** Whether a conversation has been walked all the way (or cannot be). */
    fun isDone(ch: Channel): Boolean = synchronized(lock) {
        val c = state[ch.id]
        if (c?.unreadable == true) return@synchronized true
        // never a message in it: nothing to walk
        if (ch.lastMessageId == null && c?.oldest == null) return@synchronized true
        c?.done == true && !needsCatchup(ch, c)
    }

    fun isUnreadable(channelId: String): Boolean = synchronized(lock) { state[channelId]?.unreadable == true }

    data class Progress(val total: Int, val done: Int, val unreadable: Int)

    /** How many of these conversations are walked. */
    fun progress(channels: List<Channel>): Progress {
        var done = 0
        var unreadable = 0
        for (ch in channels) {
            if (isDone(ch)) done++
            if (isUnreadable(ch.id)) unreadable++
        }
        return Progress(channels.size, done, unreadable)
    }

    /** Whether the walk has anything it could do right now (for "still indexing"). */
    val busy get() = next() != null

    private fun needsCatchup(ch: Channel, c: ChannelCrawl): Boolean =
        !c.unreadable && c.newest != null && ch.lastMessageId != null && Ids.compare(ch.lastMessageId, c.newest!!) > 0

    private fun wanted(ch: Channel): Boolean = demandAll || demand?.contains(ch.id) == true

    private class Step(val ch: Channel, val catchup: Boolean, val urgent: Boolean)

    /** What to fetch next, if anything. */
    private fun next(): Step? = synchronized(lock) {
        val now = deps.now()
        val all = deps.channels().sortedWith { a, b -> Ids.compare(b.lastMessageId ?: b.id, a.lastMessageId ?: a.id) }
        val eligible = all.filter { ch -> val at = retryAt[ch.id]; !(at != null && at > now) && state[ch.id]?.unreadable != true }
        val wantedList = eligible.filter { wanted(it) }
        fun pick(list: List<Channel>, urgent: Boolean, useSlices: Boolean): Step? {
            for (ch in list) {
                val c = state[ch.id] ?: ChannelCrawl()
                if (needsCatchup(ch, c)) return Step(ch, true, urgent)
            }
            // full: what arrives is still taken in, but no further back
            if (capped) return null
            var starved = false
            for (ch in list) {
                val c = state[ch.id] ?: ChannelCrawl()
                if (c.done) continue
                if (ch.lastMessageId == null && c.oldest == null) continue
                if (useSlices && (slices[ch.id] ?: 0) >= SLICE_PAGES) {
                    starved = true
                    continue
                }
                return Step(ch, false, urgent)
            }
            // everyone has had their turn: the next round starts
            if (starved) {
                slices.clear()
                return pick(list, urgent, useSlices)
            }
            return null
        }
        val first = pick(wantedList, true, false)
        // unasked, the walk waits for a time when it costs next to nothing
        if (first != null || !background || !deps.mayWalk()) return@synchronized first
        pick(eligible, false, true)
    }

    /** Waits, or stops waiting when something wakes the walk. */
    private suspend fun rest(ms: Long) = coroutineScope {
        val sleeper = async { deps.sleep(ms) }
        select {
            sleeper.onAwait {}
            waker.onReceive {}
        }
        sleeper.cancel()
    }

    private fun gap(urgent: Boolean): Long = when {
        urgent -> Pacing.REQUEST_GAP_MS
        deps.hidden() -> Pacing.HIDDEN_GAP_MS
        else -> Pacing.BACKGROUND_GAP_MS
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val job = next()
            deps.changed()
            if (job == null) {
                // nothing to do; a message, a new conversation or a search wakes it,
                // and it looks again now and then for what failed a while ago
                rest(RETRY_AFTER_MS)
                continue
            }
            if (!deps.online()) {
                rest(5_000)
                continue
            }
            val wait = last + gap(job.urgent) - deps.now()
            if (wait > 0) {
                // a search starting while this waits shortens the wait
                rest(wait)
                if (!currentCoroutineContext().isActive) return
                if (last + gap(next()?.urgent ?: job.urgent) > deps.now()) continue
            }
            try {
                run(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("NexaSearch", "could not walk a conversation ${job.ch.id}: ${e.message}")
                synchronized(lock) { retryAt[job.ch.id] = deps.now() + RETRY_AFTER_MS }
            }
        }
    }

    /** One page, paced and retried; null when the conversation's history is refused for good. */
    private suspend fun fetch(ch: Channel, before: String?, after: String?): List<Message>? {
        var attempt = 0
        while (true) {
            last = deps.now()
            try {
                val page = deps.list(ch.id, before, after, Pacing.PAGE)
                if (throttled) {
                    throttled = false
                    deps.changed()
                }
                return page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiError && (e.status == 403 || e.status == 404)) return null
                if (!Pacing.retryable(e) || attempt >= Pacing.MAX_RETRIES) throw e
                // the server asking to slow down: waiting it out is the point of pacing,
                // and the search says it is waiting so the pause is not read as a hang
                throttled = true
                deps.changed()
                deps.sleep(Pacing.backoffFor(e, attempt))
                attempt++
            }
        }
    }

    private suspend fun decrypt(page: List<Message>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(page.size)
        var i = 0
        while (i < page.size && currentCoroutineContext().isActive) {
            for (m in page.subList(i, minOf(page.size, i + DECRYPT_BATCH))) out += deps.decrypt(m)
            i += DECRYPT_BATCH
            yield()
        }
        return out
    }

    private suspend fun take(msgs: List<ChatMessage>): Boolean {
        val docs = msgs.mapNotNull { SearchDocs.toDoc(it) }
        if (docs.isNotEmpty()) this.docs = deps.index(docs)
        return docs.isNotEmpty()
    }

    private suspend fun run(job: Step) {
        val ch = job.ch
        val c = synchronized(lock) { state.getOrPut(ch.id) { ChannelCrawl() } }
        if (job.catchup) {
            val page = fetch(ch, null, c.newest)
            if (!currentCoroutineContext().isActive) return
            if (page == null) {
                synchronized(lock) {
                    c.unreadable = true
                    c.done = true
                }
            } else {
                val msgs = decrypt(page)
                if (!currentCoroutineContext().isActive) return
                if (take(msgs)) c.readable = true
                synchronized(lock) {
                    c.newest = Ids.max(c.newest, page.lastOrNull()?.id)
                    if (page.size < Pacing.PAGE) {
                        // caught up with the present: what arrived live meanwhile is held too
                        c.newest = Ids.max(c.newest, liveMax[ch.id])
                        live += ch.id
                    }
                }
            }
            deps.save(snapshot())
            return
        }
        // back through history from wherever the walk had got to
        val cursor = c.oldest ?: Pacing.justAfter(ch.lastMessageId ?: "0")
        val page = fetch(ch, cursor, null)
        if (!currentCoroutineContext().isActive) return
        if (page == null) {
            synchronized(lock) {
                c.unreadable = true
                c.done = true
            }
            deps.save(snapshot())
            return
        }
        val msgs = decrypt(page)
        if (!currentCoroutineContext().isActive) return
        if (take(msgs)) c.readable = true
        synchronized(lock) {
            if (c.newest == null && page.isNotEmpty()) {
                // the first page reaches the present, and live messages carry on from it
                c.newest = Ids.max(page.last().id, liveMax[ch.id])
                live += ch.id
            }
            val locked = Pacing.pageIsAllLocked(msgs)
            val nextId = page.firstOrNull()?.id
            slices[ch.id] = (slices[ch.id] ?: 0) + 1
            // `before` is exclusive, so a page always starts below the cursor it was asked for
            val moved = nextId != null && Ids.compare(nextId, cursor) < 0
            // a page with no key for any of it keeps the cursor above it, so next session looks at it again
            if (moved && !locked) c.oldest = nextId
            if (page.size < Pacing.PAGE || locked || !moved) {
                c.done = true
                c.locked = locked
                // no key for the newest page and none before: this device cannot read it
                if (locked && !c.readable) c.unreadable = true
            }
        }
        deps.save(snapshot())
    }

    companion object {
        /** Pages a conversation gets before the others have their turn. */
        const val SLICE_PAGES = 20

        /** After a failure that was not a refusal, how long before that conversation is tried again. */
        const val RETRY_AFTER_MS = 60_000L

        /** Messages decrypted between two chances for everything else to run. */
        private const val DECRYPT_BATCH = 25
    }
}
