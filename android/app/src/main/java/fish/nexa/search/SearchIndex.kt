package fish.nexa.search

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import fish.nexa.Nexa
import fish.nexa.data.AuthState
import fish.nexa.data.ChatMessage
import fish.nexa.data.GatewayEvent
import fish.nexa.data.Ids
import fish.nexa.net.Channel
import fish.nexa.net.Gateway
import fish.nexa.net.Message
import fish.nexa.net.wireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The search index, as the app runs it (web/src/search/indexer.ts).
 *
 * It opens once the account's session is ready (its keys, its settings, its
 * conversations) and closes when the keys go: a sign-out, or the identity
 * key missing. A sign-out, or turning the setting off, also wipes it from
 * this device: the key that sealed it first, then the files.
 *
 * While open it is fed from three places: the walk through history
 * (Crawler), the messages that arrive, change and go live, and the pages the
 * chat loads anyway, which also show what was deleted or edited while this
 * device was not listening.
 *
 * Everything that touches the index runs on one thread at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object SearchIndex {
    /** The most messages kept on disk, read into memory whole when the index opens. */
    const val MAX_DISK_DOCS = 150_000
    /** In memory only, for one session. */
    const val MAX_MEMORY_DOCS = 50_000

    enum class Mode { CLOSED, OPENING, MEMORY, DISK }

    data class Status(
        /** Closed (signed out, locked), opening, or open: on disk, or for this session only. */
        val mode: Mode = Mode.CLOSED,
        /** Messages it holds. */
        val docs: Int = 0,
        /** Conversations there are to search, how many are walked to their start, and how many this device cannot read. */
        val total: Int = 0,
        val done: Int = 0,
        val unreadable: Int = 0,
        /** The walk still has something it could fetch. */
        val walking: Boolean = false,
        /** The server asked for a pause and it is being waited out. */
        val throttled: Boolean = false,
        /** Full: older history is left unread. */
        val capped: Boolean = false,
        /** Moves on whenever what it holds changes, so an open search can ask again. */
        val version: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** The one thread the index is touched from. */
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private val core = SearchCore()
    @Volatile private var crawler: Crawler? = null
    @Volatile private var openFor: String? = null
    /** Moves on with every open and close, so work begun for one never lands in the next. */
    @Volatile private var gen = 0
    private var flushJob: Job? = null
    private var saveJob: Job? = null
    @Volatile private var pendingCrawl: CrawlState? = null
    private var refreshJob: Job? = null
    @Volatile private var visible = true
    private var installed = false

    /** Conversations that hold messages: categories and voice rooms hold none. */
    private val SEARCHABLE = setOf("text", "dm", "group_dm", "subchat")

    fun searchableChannels(byId: Map<String, Channel> = Nexa.channels.byId.value): List<Channel> = byId.values.filter { it.type in SEARCHABLE }

    private val slot = object : IndexKeys.Slot {
        override fun get(name: String) = Nexa.secure.getString(name)
        override fun put(name: String, value: String) = Nexa.secure.putString(name, value)
        override fun delete(name: String) = Nexa.secure.delete(name)
    }

    private fun root() = File(Nexa.app.noBackupFilesDir, "search")
    private fun dirFor(userId: String) = File(root(), userId)

    /** Phones have less room than a browser tab: the disk index holds less where the app's memory is small. */
    private fun maxDiskDocs(): Int {
        val am = Nexa.app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = am?.memoryClass ?: 256
        return when {
            mb >= 384 -> MAX_DISK_DOCS
            mb >= 256 -> 100_000
            else -> MAX_MEMORY_DOCS
        }
    }

    // ---- what the index holds changes ----

    private fun bump(m: SearchCore.Mutation) {
        _status.update { it.copy(docs = m.docs, version = if (m.changed) it.version + 1 else it.version) }
        crawler?.setDocs(m.docs)
        if (m.changed) scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = Nexa.scope.launch(thread) {
            // each write seals a whole block again, so out of sight they gather up (going out of sight writes what waited)
            delay(if (visible || fish.nexa.data.Power.plentiful()) FLUSH_MS else HIDDEN_FLUSH_MS)
            flushNow()
        }
    }

    private fun flushNow() {
        try {
            core.flush()
        } catch (e: Exception) {
            Log.w(TAG, "could not save the search index", e)
        }
    }

    /** Applies a change to the index if it is open; failures are logged, not thrown. */
    private fun mutate(op: (SearchCore) -> SearchCore.Mutation) {
        if (openFor == null) return
        val g = gen
        Nexa.scope.launch(thread) {
            if (g != gen || openFor == null) return@launch
            try {
                bump(op(core))
            } catch (e: Exception) {
                Log.w(TAG, "a search index update did not go through", e)
            }
        }
    }

    /** Works progress out again, at most a few times a second. */
    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = Nexa.scope.launch {
            delay(200)
            val c = crawler ?: return@launch
            val p = c.progress(searchableChannels())
            _status.update { it.copy(total = p.total, done = p.done, unreadable = p.unreadable, walking = c.busy, throttled = c.throttled, capped = c.capped) }
        }
    }

    private fun saveCrawl(state: CrawlState) {
        pendingCrawl = state
        if (saveJob?.isActive == true) return
        saveJob = Nexa.scope.launch(thread) {
            delay(1_500)
            val s = pendingCrawl ?: return@launch
            pendingCrawl = null
            if (openFor != null) {
                core.setCrawl(s.toJson())
                scheduleFlush()
            }
        }
    }

    // ---- what searches are waiting on ----

    private val demands = LinkedHashMap<Int, List<String>?>()
    private var demandSeq = 0

    private fun applyDemand() {
        val c = crawler ?: return
        val (set, all) = synchronized(demands) {
            if (demands.isEmpty()) null to false
            else if (demands.values.any { it == null }) null to true
            else demands.values.flatMap { it!! }.toSet() to false
        }
        c.setDemand(set, all)
    }

    /**
     * A search that is waiting on these conversations (null: on everything).
     * The walk goes to them first, at the faster pace, and with the index kept
     * in memory only they are the only ones it walks at all. Call what comes
     * back once the search is closed.
     */
    fun demand(channels: List<String>?): () -> Unit {
        val id = synchronized(demands) {
            val id = ++demandSeq
            demands[id] = channels
            id
        }
        applyDemand()
        return {
            synchronized(demands) { demands.remove(id) }
            applyDemand()
        }
    }

    /** How far the walk has got through some conversations (null: all of them). */
    fun scopeProgress(channelIds: List<String>?): Crawler.Progress {
        val all = searchableChannels()
        val list = if (channelIds == null) all else channelIds.toHashSet().let { ids -> all.filter { it.id in ids } }
        return crawler?.progress(list) ?: Crawler.Progress(list.size, 0, 0)
    }

    /** Asks the index. Answers nothing while it is closed; waits while it opens. */
    suspend fun query(req: EngineRequest): EngineResult = withContext(thread) {
        if (openFor == null) EngineResult.EMPTY
        else try {
            core.query(req)
        } catch (e: Exception) {
            Log.w(TAG, "a search failed", e)
            EngineResult.EMPTY
        }
    }

    // ---- opening and closing ----

    /** Stops the walk and lets the index go. With `flush`, what was changed is written first. */
    private fun shutdown(flush: Boolean) {
        gen++
        crawler?.stop()
        crawler = null
        val last = pendingCrawl
        pendingCrawl = null
        saveJob?.cancel()
        flushJob?.cancel()
        val wasOpen = openFor != null
        openFor = null
        _status.value = Status()
        if (!wasOpen) return
        try {
            if (flush) {
                last?.let { core.setCrawl(it.toJson()) }
                core.close()
            } else core.release()
        } catch (e: Exception) {
            Log.w(TAG, "the search index did not close cleanly", e)
        }
    }

    private fun wipe(userId: String) {
        IndexKeys.forget(slot, userId)
        BlockStore.delete(dirFor(userId))
    }

    private fun keepsIndex(doc: JsonObject = Nexa.settings.doc.value): Boolean = (doc["searchIndex"] as? JsonPrimitive)?.booleanOrNull != false

    /** Opens the index for the signed-in account, as its settings say to keep it. */
    private suspend fun open() = withContext(thread) {
        val me = (Nexa.auth.state.value as? AuthState.Ready)?.me ?: return@withContext
        val identity = Nexa.keys.identity ?: return@withContext
        if (!Nexa.settings.loaded.value) return@withContext
        shutdown(true)
        val g = gen
        val userId = me.id
        openFor = userId
        _status.value = Status(mode = Mode.OPENING)
        val keep = keepsIndex()
        var key: IndexKeys.IndexKey? = null
        if (keep) {
            key = try {
                IndexKeys.keyFor(slot, userId, identity.privateKey)
            } catch (e: Exception) {
                Log.w(TAG, "no key for the search index on this device", e)
                null
            }
        } else {
            // turned off, perhaps on another device: nothing is kept here either
            wipe(userId)
        }
        val res = core.open(userId, if (key != null) dirFor(userId) else null, key?.key, key?.fresh ?: false)
        if (g != gen) return@withContext
        val mode = if (res.persisted) Mode.DISK else Mode.MEMORY
        val c = Crawler(Deps(g), background = res.persisted, maxDocs = if (mode == Mode.DISK) maxDiskDocs() else MAX_MEMORY_DOCS, CrawlState.parse(res.crawl), res.docs)
        for (ch in res.lost) c.restart(ch)
        // Conversations this account can no longer see (a server left, a group
        // left) go from the index; only against a list that has arrived.
        val known = searchableChannels().map { it.id }.toHashSet()
        if (known.isNotEmpty()) {
            for (ch in res.counts.keys) if (ch !in known) bump(core.removeChannel(ch))
            for (ch in c.snapshot().channels.keys) if (ch !in known) c.forget(ch)
        }
        crawler = c
        _status.update { it.copy(mode = mode, docs = core.size, version = it.version + 1) }
        applyDemand()
        c.start(Nexa.scope)
        refresh()
    }

    /** Takes the index off this device and starts it again, walking everything afresh. */
    suspend fun rebuild() {
        withContext(thread) {
            val id = openFor ?: Nexa.users.meId
            shutdown(false)
            if (id != null) wipe(id)
        }
        open()
    }

    private class Deps(private val g: Int) : CrawlerDeps {
        override suspend fun list(channelId: String, before: String?, after: String?, limit: Int): List<Message> {
            val q = StringBuilder("/channels/$channelId/messages?limit=$limit")
            before?.let { q.append("&before=").append(it) }
            after?.let { q.append("&after=").append(it) }
            return Nexa.api.get(q.toString(), ListSerializer(Message.serializer()))
        }

        override fun decrypt(m: Message): ChatMessage = Nexa.messages.decrypt(m)

        override fun channels(): List<Channel> = searchableChannels()

        override suspend fun index(docs: List<SearchDoc>): Int = withContext(thread) {
            if (g != gen) return@withContext core.size
            val m = core.upsert(docs)
            bump(m)
            m.docs
        }

        override fun now() = System.currentTimeMillis()

        override suspend fun sleep(ms: Long) = delay(ms)

        override fun online() = Nexa.session.gatewayState?.value?.status == Gateway.Status.OPEN

        override fun mayWalk() = fish.nexa.data.Power.plentiful()

        override fun hidden() = !visible

        override fun changed() = refresh()

        override fun save(state: CrawlState) = saveCrawl(state)
    }

    // ---- fed from the live connection and the chat's own pages ----

    private fun decode(d: JsonElement?): Message? = try {
        d?.let { wireJson.decodeFromJsonElement(Message.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    private fun str(d: JsonElement?, k: String) = ((d as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull

    /** Conversations the index holds that the account can no longer see go from it. */
    private fun prune() {
        val known = searchableChannels().map { it.id }.toHashSet()
        if (known.isEmpty()) return
        Nexa.scope.launch(thread) {
            if (openFor == null) return@launch
            for (ch in core.channelCounts().keys) if (ch !in known) {
                bump(core.removeChannel(ch))
                crawler?.forget(ch)
            }
        }
    }

    private fun onEvent(e: GatewayEvent) {
        when (e.t) {
            "ready" -> {
                val me = Nexa.users.meId
                val c = crawler
                if (c != null && me != null && openFor == me) c.resync() else Nexa.scope.launch { open() }
            }
            "message_create" -> {
                if (openFor == null) return
                val m = decode(e.d) ?: return
                val doc = SearchDocs.toDoc(Nexa.messages.decrypt(m)) ?: return
                mutate { it.upsert(listOf(doc)) }
                crawler?.noteLive(doc.c, doc.id)
            }
            "message_update" -> {
                if (openFor == null) return
                val m = decode(e.d) ?: return
                val doc = SearchDocs.toDoc(Nexa.messages.decrypt(m))
                mutate { if (doc != null) it.upsert(listOf(doc)) else it.remove(listOf(m.id)) }
            }
            "message_delete" -> str(e.d, "id")?.let { id -> mutate { it.remove(listOf(id)) } }
            "message_delete_bulk" -> {
                val ids = ((e.d as? JsonObject)?.get("ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return
                mutate { it.remove(ids) }
            }
            "pin_update" -> {
                val id = str(e.d, "message_id") ?: return
                val pinned = ((e.d as? JsonObject)?.get("pinned") as? JsonPrimitive)?.booleanOrNull ?: return
                mutate { it.pin(id, pinned) }
            }
            "channel_delete" -> str(e.d, "id")?.let { id ->
                mutate { it.removeChannel(id) }
                crawler?.forget(id)
            }
            "server_delete" -> prune()
            "channel_create", "server_create" -> crawler?.kick()
        }
    }

    /**
     * A page of history the chat fetched. Its messages go in as they are, and
     * anything held between its first and last message that the page does not
     * have was deleted while nobody here was listening.
     */
    private fun onHistory(channelId: String, page: List<ChatMessage>) {
        if (openFor == null || Nexa.channels.get(channelId)?.type !in SEARCHABLE) return
        val docs = page.mapNotNull { SearchDocs.toDoc(it) }
        if (docs.isNotEmpty()) mutate { it.upsert(docs) }
        if (page.size < 2) return
        val ids = page.map { it.id }.sortedWith { a, b -> Ids.compare(a, b) }
        mutate { it.reconcile(channelId, ids.first(), ids.last(), ids) }
    }

    /** Wires the index into the app; called once at start. */
    fun install() {
        if (installed) return
        installed = true
        Nexa.messages.onHistory = { ch, page -> onHistory(ch, page) }
        // events go through a queue of their own, so the session never waits on the index
        val queue = KChannel<GatewayEvent>(KChannel.UNLIMITED)
        Nexa.scope.launch { Nexa.session.events.collect { e -> queue.trySend(e) } }
        Nexa.scope.launch {
            for (e in queue) {
                try {
                    onEvent(e)
                } catch (ex: Exception) {
                    Log.w(TAG, "search could not take in ${e.t}", ex)
                }
            }
        }
        // signing out wipes it; the keys going (no identity here) closes it
        Nexa.scope.launch {
            var lastUser: String? = null
            var seenReady = false
            Nexa.auth.state.collect { s ->
                when (s) {
                    is AuthState.Ready -> {
                        lastUser = s.me.id
                        seenReady = true
                    }
                    AuthState.Anonymous -> withContext(thread) {
                        shutdown(false)
                        lastUser?.let { wipe(it) }
                        // nothing signed in: nothing of anyone's index stays on this phone
                        if (!seenReady) {
                            Nexa.secure.deletePrefix(IndexKeys.slotName(""))
                            root().listFiles()?.forEach { BlockStore.delete(it) }
                        }
                        lastUser = null
                    }
                    is AuthState.Locked -> withContext(thread) { shutdown(true) }
                    else -> Unit
                }
            }
        }
        // the setting, changed here or on another device
        Nexa.scope.launch {
            var prev: Boolean? = null
            combine(Nexa.settings.doc, Nexa.settings.loaded) { d, loaded -> if (loaded) keepsIndex(d) else null }.distinctUntilChanged().collect { on ->
                val was = prev
                prev = on
                if (on == null || was == null || was == on) return@collect
                if (on) open() else rebuild()
            }
        }
        // plugged in: the walk may go on
        Nexa.scope.launch { fish.nexa.data.Power.charging.collect { if (it) crawler?.kick() } }
        // a new conversation, or a message that moves one along, may be work
        Nexa.scope.launch { Nexa.channels.byId.collect { crawler?.kick() } }
        // what changed in the last second is written before the app may be stopped
        Nexa.scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    visible = true
                    crawler?.kick()
                }

                override fun onStop(owner: LifecycleOwner) {
                    visible = false
                    Nexa.scope.launch(thread) { if (openFor != null) flushNow() }
                    crawler?.kick()
                }
            })
        }
        // a session already under way when this was installed
        if (Nexa.session.ready.value) Nexa.scope.launch { open() }
    }

    private const val TAG = "NexaSearch"
    private const val FLUSH_MS = 1_000L
    private const val HIDDEN_FLUSH_MS = 60_000L
}

/** The searches the search box offers again (web/src/search/recent.ts), kept in the account's sealed settings. */
object SearchRecent {
    const val MAX_RECENT = 8
    private const val MAX_LENGTH = 200

    fun list(doc: JsonObject = Nexa.settings.doc.value): List<String> =
        (doc["searchRecent"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.length <= MAX_LENGTH } }?.take(MAX_RECENT) ?: emptyList()

    private fun save(next: List<String>) = Nexa.settings.set("searchRecent", JsonArray(next.map(::JsonPrimitive)))

    fun remember(raw: String) {
        val q = Js.trim(raw).replace(Regex("\\s+"), " ")
        if (q.isEmpty() || q.length > MAX_LENGTH) return
        // before the account's settings arrive there is nothing to add to
        if (!Nexa.settings.loaded.value) return
        val cur = list()
        val next = (listOf(q) + cur.filter { it.lowercase() != q.lowercase() }).take(MAX_RECENT)
        if (next == cur) return
        save(next)
    }

    fun forget(raw: String) {
        val cur = list()
        val next = cur.filter { it != raw }
        if (next.size != cur.size) save(next)
    }

    fun clear() {
        if (list().isNotEmpty()) save(emptyList())
    }
}
