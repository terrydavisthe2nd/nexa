package fish.nexa.data

import android.util.Log
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.Conv
import fish.nexa.crypto.DecryptError
import fish.nexa.crypto.Identity
import fish.nexa.crypto.KeyRing
import fish.nexa.crypto.Keys
import fish.nexa.net.Api
import fish.nexa.net.ApiError
import fish.nexa.net.KeyGrant
import fish.nexa.net.KeyNeed
import fish.nexa.net.wireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The identity keys this device has seen for people, pinned the first time,
 * so a key that changes under an unchanged name is noticed rather than
 * trusted (web/src/lib/keyPins.ts). Kept per account, sealed at rest.
 */
class KeyPins(private val store: SecureStore) {
    enum class Verdict { UNKNOWN, FIRST, SAME, CHANGED }

    private var account: String? = null
    private val keys = LinkedHashMap<String, String>()
    private var since = 0L
    private val fresh = HashSet<String>()
    var startedBlank = true
        private set
    @Volatile private var dirty = false

    @Synchronized fun open(userId: String) {
        if (account == userId) return
        flush()
        account = userId
        keys.clear()
        fresh.clear()
        since = System.currentTimeMillis()
        store.getString("keypins-$userId")?.let { raw ->
            try {
                val o = wireJson.parseToJsonElement(raw).jsonObject
                (o["keys"] as? JsonObject)?.forEach { (id, k) -> (k as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { keys[id] = it } }
                since = (o["since"] as? JsonPrimitive)?.long ?: since
            } catch (_: Exception) {
            }
        }
        startedBlank = keys.isEmpty()
    }

    @Synchronized fun close() {
        flush()
        account = null
        keys.clear()
        fresh.clear()
        startedBlank = true
    }

    @Synchronized fun flush() {
        val a = account ?: return
        if (!dirty) return
        dirty = false
        store.putString("keypins-$a", buildJsonObject {
            put("v", 1)
            put("keys", JsonObject(keys.mapValues { JsonPrimitive(it.value) }))
            put("since", since)
        }.toString())
    }

    @Synchronized fun pinned(userId: String): String? = keys[userId]
    @Synchronized fun learnedThisSession(userId: String) = userId in fresh
    @Synchronized fun since(): Long = since
    @Synchronized fun count(): Int = keys.size

    @Synchronized fun check(userId: String, pub: String?): Verdict {
        if (pub.isNullOrEmpty() || account == null) return Verdict.UNKNOWN
        val have = keys[userId] ?: return Verdict.FIRST
        return if (have == pub) Verdict.SAME else Verdict.CHANGED
    }

    @Synchronized fun pin(userId: String, pub: String) {
        if (account == null || pub.isEmpty()) return
        if (userId in keys) fresh.remove(userId) else {
            fresh.add(userId)
            while (keys.size >= MAX_PINS) {
                val drop = keys.keys.first()
                keys.remove(drop)
                fresh.remove(drop)
            }
        }
        keys[userId] = pub
        dirty = true
    }

    /** Pins a key seen for the first time; says what it was. */
    fun note(userId: String, pub: String?): Verdict {
        val v = check(userId, pub)
        if (v == Verdict.FIRST && pub != null) pin(userId, pub)
        return v
    }

    @Synchronized fun forget(userId: String) {
        if (keys.remove(userId) != null) {
            fresh.remove(userId)
            dirty = true
            flush()
        }
    }

    companion object {
        const val MAX_PINS = 4000
    }
}

/** What this device noticed about identity keys and refused to do (web/src/stores/keyAlerts.ts). */
data class SecurityEvent(val id: Int, val at: Long, val kind: String, val userId: String? = null, val conv: String? = null, val epoch: Int? = null, val count: Int? = null)
data class PendingKeyChange(val userId: String, val pinned: String, val offered: String, val firstSeen: Long)

class KeyAlerts(private val pins: KeyPins) {
    private val _events = MutableStateFlow<List<SecurityEvent>>(emptyList())
    val events: StateFlow<List<SecurityEvent>> = _events
    private val _pending = MutableStateFlow<Map<String, PendingKeyChange>>(emptyMap())
    val pending: StateFlow<Map<String, PendingKeyChange>> = _pending
    private var nextId = 1

    /** Set by the UI: a refusal worth a toast, a key change worth a dialog. */
    var onOutsiderRefused: ((count: Int) -> Unit)? = null
    var onKeyChange: ((userId: String) -> Unit)? = null

    fun report(kind: String, userId: String? = null, conv: String? = null, epoch: Int? = null, count: Int? = null) {
        _events.update { (listOf(SecurityEvent(nextId++, System.currentTimeMillis(), kind, userId, conv, epoch, count)) + it).take(100) }
        if (kind == "outsider_refused") onOutsiderRefused?.invoke(count ?: 1)
    }

    fun flagKeyChange(userId: String, offered: String) {
        val pinned = pins.pinned(userId) ?: return
        if (pinned == offered) return
        if (_pending.value[userId]?.offered == offered) return
        _pending.update { it + (userId to PendingKeyChange(userId, pinned, offered, System.currentTimeMillis())) }
        report("key_changed", userId = userId)
        onKeyChange?.invoke(userId)
    }

    fun accept(userId: String) {
        val p = _pending.value[userId] ?: return
        pins.pin(userId, p.offered)
        pins.flush()
        _pending.update { it - userId }
    }

    fun reject(userId: String) = _pending.update { it - userId }

    fun clearEvents() {
        _events.value = emptyList()
    }

    fun reset() {
        _events.value = emptyList()
        _pending.value = emptyMap()
    }

    /** Every user object the server sends passes through here (judgeKey). */
    fun judge(userId: String, pub: String?): KeyPins.Verdict {
        val v = pins.note(userId, pub)
        if (v == KeyPins.Verdict.CHANGED && pub != null) flagKeyChange(userId, pub)
        return v
    }
}

/**
 * Conversation keys: the ring of roots this device holds, grants opened and
 * handed out, epochs created and rotated (web/src/stores/keys.ts). Online
 * holders give keys to members who lack them and replace them when someone
 * loses access; without that, newcomers read nothing and leavers keep reading.
 */
class KeyManager(
    private val api: Api,
    private val store: SecureStore,
    private val users: UsersStore,
    private val channels: ChannelsStore,
    private val servers: ServersStore,
    val pins: KeyPins,
    val alerts: KeyAlerts,
    private val scope: CoroutineScope,
) {
    val ring = KeyRing()
    @Volatile var userId: String? = null
        private set
    @Volatile var identity: Identity? = null
        private set
    /** Whether roots may be cached at rest (not behind a passcode). */
    @Volatile var persistRoots = true

    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /** Conversations with messages waiting for a key. */
    val waiting: StateFlow<Set<String>> = _waiting

    /** Called with the conversations whose keys just arrived, to decrypt again what waited. */
    var onKeysArrived: (suspend (Set<String>) -> Unit)? = null

    private val pendingEnsure = HashMap<String, Deferred<Pair<Int, ByteArray>?>>()
    private val rotations = HashMap<String, Job>()
    private val saveMutex = Mutex()

    fun init(userId: String, identity: Identity, persist: Boolean) {
        this.userId = userId
        this.identity = identity
        this.persistRoots = persist
        _waiting.value = emptySet()
        pins.open(userId)
        if (!persist) {
            store.delete("roots-$userId")
            return
        }
        store.getString("roots-$userId")?.let { raw ->
            try {
                for (el in wireJson.parseToJsonElement(raw).jsonArray) {
                    val o = el.jsonObject
                    val root = Bytes.unb64OrNull(o["root"]?.jsonPrimitive?.content) ?: continue
                    ring.set(o["conv"]!!.jsonPrimitive.content, o["epoch"]!!.jsonPrimitive.int, root)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cached roots could not be read", e)
            }
        }
    }

    private suspend fun saveRoots() {
        val uid = userId ?: return
        if (!persistRoots) return
        saveMutex.withLock {
            val all = ring.all()
            val json = buildJsonArray {
                for ((conv, epochs) in all) for ((epoch, root) in epochs) add(buildJsonObject {
                    put("conv", conv)
                    put("epoch", epoch)
                    put("root", Bytes.b64(root))
                })
            }
            withContext(Dispatchers.IO) { store.putString("roots-$uid", json.toString()) }
        }
    }

    /**
     * A message here could not be read for want of its conversation's key:
     * wait for it, and ask the holders for it again now and then.
     */
    fun markWaiting(conv: String) {
        if (userId == null) return
        _waiting.update { if (conv in it) it else it + conv }
        askAgain(conv)
    }

    /**
     * Asking the holders of a conversation's keys again while this device
     * waits for them: soon after it finds it lacks them, then now and then, a
     * few times (stores/keys.ts askAgain). The ask made as it came in can be
     * missed (nobody holding them was online, or their app had not heard of
     * the newcomer yet), and nothing else asked again until a holder next
     * connected. A server without the route only says no.
     */
    private val asking = HashMap<String, Job>()

    private fun askAgain(conv: String) {
        synchronized(asking) {
            if (asking[conv]?.isActive == true) return
            asking[conv] = scope.launch {
                for (wait in Timings.askAgainMs) {
                    delay(wait)
                    // got them meanwhile, or signed out: nothing to ask for
                    if (conv !in _waiting.value || userId == null) break
                    try {
                        api.request("POST", "/keys/request", buildJsonObject { put("conv_id", conv) })
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
                val self = coroutineContext[Job]
                synchronized(asking) { if (asking[conv] === self) asking.remove(conv) }
            }
        }
    }

    private fun stopAsking(convs: Collection<String>? = null) {
        synchronized(asking) {
            val it = asking.entries.iterator()
            while (it.hasNext()) {
                val (c, job) = it.next()
                if (convs != null && c !in convs) continue
                job.cancel()
                it.remove()
            }
        }
    }

    /** The channel key for a message: HKDF(root of conv/epoch, "chan:" + channel). */
    fun channelKey(conv: String, epoch: Int, channelId: String): ByteArray? = ring.get(conv, epoch)?.let { Conv.channelKey(it, channelId) }

    /** Opens grants this device does not hold yet; returns the conversations that gained a key. */
    suspend fun ingest(grants: List<KeyGrant>): Set<String> {
        val id = identity ?: return emptySet()
        val fresh = grants.filter { ring.get(it.convId, it.epoch) == null }
        if (fresh.isEmpty()) return emptySet()
        val changed = HashSet<String>()
        withContext(Dispatchers.Default) {
            for (batch in fresh.chunked(64)) {
                val roots = batch.map { g ->
                    async {
                        try {
                            Keys.open(id.privateKey, id.publicKey, Bytes.unb64(g.wrappedKey))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                if (identity !== id) return@withContext
                for ((j, root) in roots.withIndex()) {
                    val g = batch[j]
                    // the server counts these: a key several people cannot use gets replaced
                    if (root == null || root.size != 32 || !matchesEpoch(g, root)) {
                        runCatching {
                            api.request("POST", "/keys/request", buildJsonObject {
                                put("conv_id", g.convId)
                                put("bad_epoch", g.epoch)
                                put("bad_wrapped_key", g.wrappedKey)
                            })
                        }
                        continue
                    }
                    if (ring.get(g.convId, g.epoch) != null) continue
                    ring.set(g.convId, g.epoch, root)
                    changed += g.convId
                }
            }
        }
        if (identity !== id) return emptySet()
        if (changed.isNotEmpty()) {
            saveRoots()
            _waiting.update { it - changed }
            stopAsking(changed)
            onKeysArrived?.invoke(changed)
        }
        return changed
    }

    /** Whether an opened key is the one its epoch was made with. Epochs from before fingerprints have none to check. */
    private fun matchesEpoch(g: KeyGrant, root: ByteArray): Boolean {
        val commitment = g.commitment ?: return true
        return runCatching { Conv.keyCommitment(root, g.convId).contentEquals(Bytes.unb64(commitment)) }.getOrDefault(false)
    }

    /** Who this device believes is in a conversation (web lib/convMembers.ts). */
    private fun membershipOf(conv: String): Triple<Boolean, Boolean, Set<String>> {
        val ch = channels.get(conv)
        if (ch != null) {
            if (ch.isDmLike) return Triple(true, true, ch.recipientIds.toSet())
            if (ch.serverId != null) return serverMembership(ch.serverId)
            return Triple(false, false, emptySet())
        }
        return serverMembership(conv)
    }

    private fun serverMembership(serverId: String): Triple<Boolean, Boolean, Set<String>> {
        val st = servers.get(serverId) ?: return Triple(false, false, emptySet())
        return Triple(true, st.membersComplete, st.members.keys)
    }

    /**
     * Narrows a server-supplied recipient list to accounts this device knows
     * are in the conversation. Anyone outside it is set aside and looked at
     * again a few times (`later`), in case this device has not heard of them
     * joining yet, and only then refused and written down: the ask can outrun
     * the event that says someone joined, and refusing them at once left a
     * newcomer without keys until a holder next connected.
     */
    private fun recipientsFor(conv: String, epoch: Int, userIds: List<String>, attempt: Int): Pair<List<String>, List<String>> {
        val (known, complete, members) = membershipOf(conv)
        val me = userId
        if (!known || !complete) {
            if (userIds.isNotEmpty()) alerts.report("unchecked", conv = conv, epoch = epoch, count = userIds.size)
            return userIds to emptyList()
        }
        val allowed = ArrayList<String>()
        val outsiders = ArrayList<String>()
        for (id in userIds) if (id in members || id == me) allowed += id else outsiders += id
        val retrying = attempt < Timings.outsiderRetryMs.size
        if (outsiders.isNotEmpty() && !retrying) {
            Log.w(TAG, "refusing to wrap $conv epoch $epoch for ${outsiders.joinToString()}: not in this conversation")
            alerts.report("outsider_refused", conv = conv, epoch = epoch, count = outsiders.size, userId = outsiders.singleOrNull())
        }
        return allowed to if (retrying) outsiders else emptyList()
    }

    private suspend fun publicKeyOf(uid: String): String {
        users.get(uid)?.publicKey?.takeIf { it.isNotEmpty() }?.let { return it }
        if (uid == userId) identity?.let { return Bytes.b64(it.publicKey) }
        val res = api.get("/users/$uid/public-key")
        return Api.str(res, "public_key") ?: throw ApiError(404, "not_found", "No key")
    }

    /** The key to seal to for a person, or null when it is not the one pinned for them. */
    suspend fun sealingKeyOf(uid: String): ByteArray? {
        val pub = publicKeyOf(uid)
        if (alerts.judge(uid, pub) == KeyPins.Verdict.CHANGED) return null
        return Bytes.unb64(pub)
    }

    /** The one path every grant takes: recipients checked, keys judged against pins, then uploaded. */
    private suspend fun putGrantsFor(conv: String, epoch: Int, root: ByteArray, wanted: List<String>, attempt: Int = 0) {
        val (ids, later) = recipientsFor(conv, epoch, wanted, attempt)
        if (later.isNotEmpty()) scope.launch {
            delay(Timings.outsiderRetryMs[attempt])
            // the same root, if this session still holds it
            val still = ring.get(conv, epoch) ?: return@launch
            try {
                putGrantsFor(conv, epoch, still, later, attempt + 1)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val uploads = java.util.Collections.synchronizedList(ArrayList<JsonObject>())
        suspend fun wrap(uid: String): Boolean = try {
            val pub = sealingKeyOf(uid)
            if (pub == null) {
                alerts.report("grant_withheld", conv = conv, epoch = epoch, userId = uid)
            } else {
                val wrapped = withContext(Dispatchers.Default) { Keys.seal(pub, root) }
                uploads += buildJsonObject {
                    put("conv_id", conv)
                    put("epoch", epoch)
                    put("user_id", uid)
                    put("wrapped_key", Bytes.b64(wrapped))
                }
            }
            true
        } catch (e: ApiError) {
            e.status == 404
        } catch (_: Exception) {
            false
        }
        val again = ArrayList<String>()
        for (batch in ids.chunked(16)) {
            val ok = coroutineScope { batch.map { async { wrap(it) } }.awaitAll() }
            for ((j, fine) in ok.withIndex()) if (!fine) again += batch[j]
        }
        if (again.isNotEmpty()) {
            delay(500)
            val healed = coroutineScope { again.map { async { wrap(it) } }.awaitAll() }
            val missed = again.filterIndexed { j, _ -> !healed[j] }
            if (missed.isNotEmpty()) Log.w(TAG, "${missed.size} member(s) could not be granted $conv epoch $epoch; the server will ask again")
        }
        for (chunk in uploads.toList().chunked(200)) {
            api.request("POST", "/keys/grants", JsonArray(chunk))
        }
    }

    private suspend fun createAndGrant(conv: String, uid: String, reason: String?): Pair<Int, ByteArray> {
        val root = Conv.newRootKey()
        val res = api.request("POST", "/keys/epochs", buildJsonObject {
            put("conv_id", conv)
            if (reason != null) put("reason", reason)
            put("commitment", Bytes.b64(Conv.keyCommitment(root, conv)))
        }).jsonObject
        val epoch = res["epoch"]!!.jsonPrimitive.int
        val userIds = (res["user_ids"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        val targets = if (uid in userIds) userIds else userIds + uid
        putGrantsFor(conv, epoch, root, targets)
        ring.set(conv, epoch, root)
        saveRoots()
        return epoch to root
    }

    /** The latest key for a conversation, creating epoch 0 when nobody has made one. */
    suspend fun ensureConvKey(conv: String, current: Boolean = false): Pair<Int, ByteArray>? {
        if (current) {
            suspend fun info() = api.request("GET", "/keys/epochs?conv_id=$conv").jsonObject
            fun needsRotation(value: JsonObject) = (value["flags"] as? JsonObject)?.get("needs_rotation")?.jsonPrimitive?.content == "true"
            var state = info()
            if (needsRotation(state)) {
                rotate(conv, "membership change")
                state = info()
                if (needsRotation(state)) { markWaiting(conv); return null }
            }
            val epoch = (state["epochs"] as? JsonArray)?.lastOrNull()?.jsonObject?.get("epoch")?.jsonPrimitive?.int
            if (epoch != null) {
                if (ring.get(conv, epoch) == null) {
                    val mine = api.get("/keys/grants", ListSerializer(KeyGrant.serializer()))
                    ingest(mine.filter { it.convId == conv })
                }
                ring.get(conv, epoch)?.let { return epoch to it }
                markWaiting(conv)
                return null
            }
        }
        ring.latest(conv)?.let { return it }
        val uid = userId ?: return null
        val job = synchronized(pendingEnsure) {
            pendingEnsure[conv] ?: scope.async {
                try {
                    createAndGrant(conv, uid, null)
                } catch (e: ApiError) {
                    if (e.status == 409 || e.code == "epoch_race") {
                        val mine = api.get("/keys/grants", ListSerializer(KeyGrant.serializer()))
                        ingest(mine.filter { it.convId == conv })
                        ring.latest(conv) ?: run { markWaiting(conv); null }
                    } else throw e
                } finally {
                    synchronized(pendingEnsure) { pendingEnsure.remove(conv) }
                }
            }.also { pendingEnsure[conv] = it }
        }
        return job.await()
    }

    /**
     * Takes a root this device was handed outside the grant system (an invite
     * link's key): keeps it, saves it, and grants it to this account so its
     * other devices get it too (lib/joinInvite.ts).
     */
    suspend fun adopt(conv: String, epoch: Int, root: ByteArray) {
        if (ring.get(conv, epoch) != null) return
        ring.set(conv, epoch, root)
        saveRoots()
        userId?.let { grantTo(conv, epoch, listOf(it)) }
    }

    suspend fun grantTo(conv: String, epoch: Int, userIds: List<String>) {
        val root = ring.get(conv, epoch) ?: return
        if (userIds.isEmpty()) return
        putGrantsFor(conv, epoch, root, userIds)
    }

    /** On every ready: hand out what members lack, rotate what waited for a holder. False when the server could not be asked. */
    suspend fun reconcile(): Boolean {
        val needs = try {
            api.get("/keys/needs", ListSerializer(KeyNeed.serializer()))
        } catch (e: Exception) {
            Log.w(TAG, "could not ask what needs granting", e)
            return false
        }
        for (n in needs) {
            if (ring.get(n.convId, n.epoch) == null) continue
            try {
                grantTo(n.convId, n.epoch, n.userIds)
                if (n.rotate) rotate(n.convId, "membership change")
            } catch (e: Exception) {
                Log.w(TAG, "could not reconcile ${n.convId}", e)
            }
        }
        return true
    }

    suspend fun rotate(conv: String, reason: String?) {
        val uid = userId ?: return
        try {
            createAndGrant(conv, uid, reason)
        } catch (e: ApiError) {
            if (e.status == 409 || e.code == "epoch_race") return
            throw e
        }
    }

    /** key_rotation_needed: one rotation at a time per conversation. */
    fun rotateSerially(conv: String) {
        if (ring.latest(conv) == null) return
        synchronized(rotations) {
            val prev = rotations[conv]
            rotations[conv] = scope.launch {
                prev?.join()
                try {
                    rotate(conv, "membership change")
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Forget every key (sign out, lock); pins stay on the device, as they should. */
    fun reset() {
        ring.clear()
        pendingEnsure.clear()
        stopAsking()
        pins.close()
        alerts.reset()
        userId = null
        identity = null
        _waiting.value = emptySet()
    }

    /**
     * When to look again at someone this device did not know as a member, and
     * when to ask the holders again while waiting (stores/keys.ts keyTimings).
     */
    object Timings {
        var outsiderRetryMs = longArrayOf(1500, 5000, 15000)
        var askAgainMs = longArrayOf(2000, 20000, 60000, 180000, 600000)
    }

    companion object {
        private const val TAG = "NexaKeys"
    }
}
