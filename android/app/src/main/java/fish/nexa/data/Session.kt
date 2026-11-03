package fish.nexa.data

import android.util.Log
import fish.nexa.Nexa
import fish.nexa.crypto.Kdf
import fish.nexa.net.Channel
import fish.nexa.net.Emoji
import fish.nexa.net.Gateway
import fish.nexa.net.GroupAddNotice
import fish.nexa.net.KeyGrant
import fish.nexa.net.KeyNeed
import fish.nexa.net.Member
import fish.nexa.net.Message
import fish.nexa.net.Presence
import fish.nexa.net.PublicUser
import fish.nexa.net.ReadState
import fish.nexa.net.ReadyPayload
import fish.nexa.net.Relationship
import fish.nexa.net.Role
import fish.nexa.net.Server
import fish.nexa.net.ServerView
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.net.VoiceState
import fish.nexa.net.wireJson
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A gateway event after the stores have applied it, for the parts that react (voice, notes, notifications). */
data class GatewayEvent(val t: String, val d: JsonElement?)

/**
 * One signed-in session: the gateway socket, and every event it brings
 * applied to the stores strictly in arrival order (web/src/gateway/dispatch.ts).
 * Key upkeep runs beside the queue, so messages never wait behind hundreds of
 * grant uploads.
 */
class Session(private val nx: Nexa) {
    private var gateway: Gateway? = null
    private var consumer: Job? = null
    private var typingSweeper: Job? = null
    private var queue = KChannel<GatewayEvent>(KChannel.UNLIMITED)
    private var generation = 0

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 256)
    /** Every event, after it was applied. */
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _ready = MutableStateFlow(false)
    /** A ready has been applied since the session started. */
    val ready: StateFlow<Boolean> = _ready

    val gatewayState: StateFlow<Gateway.State>? get() = gateway?.state

    /** Acks that failed for a network reason, sent again after the next ready. */
    private val failedAcks = HashMap<String, String>()
    private val fetchingUsers = HashSet<String>()
    private val fetchingChannels = HashSet<String>()

    var onSignedOut: ((reason: String) -> Unit)? = null

    /** Locked during a call: the connection stays for the call, and only its events are taken (applyLockedEvent). */
    @Volatile var lockedInCall = false

    /** Alerts (notify.ts dispatchHooks): a message that arrived, a friend request that came in. */
    var onMessage: ((Message, Boolean, ChatMessage) -> Unit)? = null
    var onFriendRequest: ((Relationship) -> Unit)? = null

    fun start() {
        if (gateway != null) return
        val gen = ++generation
        queue = KChannel(KChannel.UNLIMITED)
        val q = queue
        consumer = nx.scope.launch {
            for (e in q) {
                if (gen != generation) break
                if (lockedInCall && e.t !in LOCKED_EVENTS) continue
                try {
                    if (lockedInCall && e.t == "ready") decode(e.d, ReadyPayload.serializer())?.let { ServerClock.set(it.now); nx.voiceStates.replaceAll(it.voiceStates) }
                    else apply(e)
                } catch (ex: Exception) {
                    Log.w(TAG, "event ${e.t} failed", ex)
                }
                _events.tryEmit(e)
            }
        }
        // sweeps expired typing only while someone is typing, so an idle connection sleeps
        typingSweeper = nx.scope.launch {
            nx.typing.byChannel.collectLatest { m ->
                if (m.isNotEmpty()) while (true) {
                    delay(1000)
                    nx.typing.sweep()
                }
            }
        }
        nx.messages.resolveAuthors = { ids -> ensureUsers(ids) }
        nx.keys.onKeysArrived = { convs -> nx.messages.redecrypt(convs) }
        val g = Gateway(nx.api, nx.scope) { t, d -> q.trySend(GatewayEvent(t, d)) }
        gateway = g
        g.connect()
    }

    fun stop() {
        generation++
        gateway?.close()
        gateway = null
        consumer?.cancel()
        typingSweeper?.cancel()
        queue.close()
        _ready.value = false
        lockedInCall = false
    }

    fun wake() = gateway?.wake()
    fun restart() = gateway?.restart()
    fun send(op: String, d: JsonElement? = null) = gateway?.send(op, d)

    private inline fun <reified T> decode(d: JsonElement?, s: KSerializer<T>): T? = try {
        d?.let { wireJson.decodeFromJsonElement(s, it) }
    } catch (e: Exception) {
        Log.w(TAG, "could not read ${s.descriptor.serialName}", e)
        null
    }

    private fun JsonElement?.str(k: String): String? = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.bool(k: String): Boolean = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.booleanOrNull ?: false

    private suspend fun apply(e: GatewayEvent) {
        val d = e.d
        val meId = nx.users.meId
        when (e.t) {
            "ready" -> decode(d, ReadyPayload.serializer())?.let { onReady(it) }
            "message_create" -> decode(d, Message.serializer())?.let { onMessageCreate(it, meId) }
            "message_update" -> decode(d, Message.serializer())?.let { m ->
                val dec = nx.messages.decrypt(m)
                nx.messages.update(m.channelId, dec)
                adjustEditMentions(d, m, meId)
            }
            "message_delete" -> {
                val ch = d.str("channel_id") ?: return
                val id = d.str("id") ?: return
                // an app's answer only this person saw: it goes, and was never part of the conversation
                if (d.bool("ephemeral")) nx.messages.removeMany(ch, listOf(id))
                else onDeleted(ch, listOf(id), d, single = true)
            }
            "message_delete_bulk" -> {
                val ch = d.str("channel_id") ?: return
                val ids = ((d as? JsonObject)?.get("ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return
                onDeleted(ch, ids, d, single = false)
            }
            "reaction_remove_all", "reaction_remove_emoji" -> {
                val ch = d.str("channel_id") ?: return
                val mid = d.str("message_id") ?: return
                nx.messages.clearReactions(ch, mid, if (e.t == "reaction_remove_emoji") d.str("emoji") ?: return else null)
            }
            // the commands kept for each channel are read again when next needed (a page listing a server's apps follows the event itself)
            "application_commands_update", "integrations_update" -> AppCommands.forget()
            "reaction_add", "reaction_remove" -> {
                val ch = d.str("channel_id") ?: return
                val mid = d.str("message_id") ?: return
                val emoji = d.str("emoji") ?: return
                val uid = d.str("user_id")
                nx.messages.setReaction(ch, mid, emoji, e.t == "reaction_add", uid == meId)
            }
            "pin_update" -> {
                val ch = d.str("channel_id") ?: return
                val mid = d.str("message_id") ?: return
                val cm = nx.messages.get(ch).byId[mid] ?: return
                val f = if (d.bool("pinned")) cm.m.flags or Message.MSG_FLAG_PINNED else cm.m.flags and Message.MSG_FLAG_PINNED.inv()
                nx.messages.setFlags(ch, mid, f)
            }
            "poll_vote_add", "poll_vote_remove" -> {
                val o = d as? JsonObject ?: return
                val ch = d.str("channel_id") ?: return
                val mid = d.str("message_id") ?: return
                val answer = (o["answer_id"] as? JsonPrimitive)?.intOrNull ?: return
                val voters = (o["total_voters"] as? JsonPrimitive)?.intOrNull
                nx.messages.setPollVote(ch, mid, answer, e.t == "poll_vote_add", d.str("user_id") == meId, voters)
            }
            "typing" -> {
                val ch = d.str("channel_id") ?: return
                val uid = d.str("user_id") ?: return
                if (uid == meId) return
                if (d.bool("stop")) nx.typing.remove(ch, uid)
                else if (!nx.relationships.isBlocked(uid)) nx.typing.add(ch, uid)
            }
            "channel_create", "channel_update" -> decode(d, Channel.serializer())?.let { c ->
                nx.channels.upsert(listOf(c))
                ensureUsers(c.recipientIds)
            }
            "channel_delete" -> {
                val id = d.str("id") ?: return
                nx.channels.remove(id)
                nx.messages.clearChannel(id)
                nx.misc.groupAddNotices.update { it.remove(id) }
            }
            "channel_positions" -> {
                val sid = d.str("server_id") ?: return
                val list = ((d as? JsonObject)?.get("channels") as? JsonArray)?.mapNotNull {
                    val o = it as? JsonObject ?: return@mapNotNull null
                    Triple(o.str("id") ?: return@mapNotNull null, (o["position"] as? JsonPrimitive)?.intOrNull ?: 0, o.str("parent_id"))
                } ?: return
                nx.channels.positions(sid, list)
            }
            "thread_create", "thread_update" -> decode(d, Channel.serializer())?.let { th ->
                nx.channels.upsert(listOf(th))
                // started from a message: the message says so under it at once
                val starter = th.thread?.starterId
                if (e.t == "thread_create" && starter != null && th.parentId != null) {
                    nx.messages.setThread(th.parentId, starter, fish.nexa.net.ThreadSummary(th.id, th.name, th.thread.messageCount, th.lastMessageId, th.thread.archived, th.thread.locked))
                }
                th.memberCount?.let { nx.threads.membersChanged(th.id, emptyList(), emptyList(), it, meId) }
            }
            "thread_delete" -> {
                val id = d.str("id") ?: return
                nx.channels.remove(id)
                nx.messages.clearChannel(id)
                nx.threads.forget(id)
            }
            "thread_members_update" -> {
                val o = d as? JsonObject ?: return
                val id = d.str("thread_id") ?: return
                fun ids(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                val count = (o["member_count"] as? JsonPrimitive)?.intOrNull ?: 0
                nx.threads.membersChanged(id, ids("added"), ids("removed"), count, meId)
            }
            "automod_rules_update" -> {
                val sid = d.str("server_id") ?: return
                val at = ((d as? JsonObject)?.get("automod_at") as? JsonPrimitive)?.longOrNull
                nx.servers.get(sid)?.let { st -> if (at != null) nx.servers.setServer(st.server.copy(automodAt = at)) }
                Automod.forget(sid)
            }
            "follows_update" -> d.str("channel_id")?.let { Follows.forget(it) }
            "group_add_notice" -> decode(d, GroupAddNotice.serializer())?.let { n -> nx.misc.groupAddNotices.update { it.put(n.channelId, n) } }
            "group_add_notice_delete" -> d.str("channel_id")?.let { id -> nx.misc.groupAddNotices.update { it.remove(id) } }
            "server_create" -> decode(d, ServerView.serializer())?.let { v ->
                putServer(v, ((d as? JsonObject)?.get("users") as? JsonArray)?.let { decode(it, ListSerializer(PublicUser.serializer())) } ?: emptyList())
            }
            "server_update" -> decode(d, Server.serializer())?.let { nx.servers.setServer(it) }
            "server_delete" -> {
                val id = d.str("id") ?: return
                for (ch in nx.channels.removeServer(id)) nx.messages.clearChannel(ch)
                nx.servers.remove(id)
                nx.expressions.removeServer(id)
            }
            "member_add" -> {
                val o = d as? JsonObject ?: return
                val m = decode(o["member"], Member.serializer()) ?: return
                val u = decode(o["user"], PublicUser.serializer())
                u?.let { judgeAll(listOf(it)) }
                nx.servers.upsertMember(m, u)
            }
            "member_update" -> decode(d, Member.serializer())?.let { nx.servers.updateMember(it) }
            "member_remove" -> {
                val sid = d.str("server_id") ?: return
                val uid = d.str("user_id") ?: return
                nx.servers.removeMember(sid, uid)
            }
            "role_create", "role_update" -> decode(d, Role.serializer())?.let { nx.servers.upsertRole(it) }
            "role_delete" -> {
                val sid = d.str("server_id") ?: return
                val id = d.str("id") ?: return
                nx.servers.removeRole(sid, id)
            }
            "emoji_update" -> {
                val sid = d.str("server_id") ?: return
                val list = decode((d as? JsonObject)?.get("emojis"), ListSerializer(Emoji.serializer())) ?: emptyList()
                nx.expressions.setServerEmojis(sid, list)
            }
            "sticker_update" -> {
                val id = d.str("pack_id") ?: return
                val pack = (d as? JsonObject)?.get("pack")?.let { decode(it, StickerPack.serializer()) }
                if (d.bool("deleted") || pack == null) nx.expressions.removePack(id) else nx.expressions.upsertPack(pack)
            }
            "sticker_recent" -> decode((d as? JsonObject)?.get("sticker"), Sticker.serializer())?.let { nx.expressions.useRecent(it) }
            "presence" -> decode(d, Presence.serializer())?.let { nx.presence.set(it) }
            // this account's subscription or boosts changed; only a page that has read them reads them again
            "billing_update" -> if (Plans.billing.value != null) nx.scope.launch { Plans.load() }
            "user_update" -> decode(d, PublicUser.serializer())?.let { u ->
                judgeAll(listOf(u))
                if (u.id == meId) nx.users.mergeMe(u)
                nx.users.upsert(listOf(if (u.id == meId) u.copy(flags = nx.users.me.value?.flags ?: u.flags) else u))
            }
            "relationship_add" -> decode(d, Relationship.serializer())?.let { r ->
                judgeAll(listOf(r.user))
                val was = nx.relationships.typeOf(r.user.id)
                nx.relationships.set(r)
                // a stranger's request is the only place their name comes from, and Block needs it
                nx.users.upsert(listOf(r.user))
                if (r.type == "incoming" && was != "incoming") onFriendRequest?.invoke(r)
            }
            "relationship_remove" -> d.str("user_id")?.let { nx.relationships.remove(it) }
            "read_state" -> decode(d, ReadState.serializer())?.let { nx.readStates.apply(it) }
            "settings_update" -> {
                val v = ((d as? JsonObject)?.get("version") as? JsonPrimitive)?.longOrNull ?: return
                if (v > nx.settings.version) nx.scope.launch { nx.settings.reload() }
            }
            "key_grant" -> decode(d, KeyGrant.serializer())?.let { g -> nx.scope.launch { nx.keys.ingest(listOf(g)) } }
            "keys_needed" -> decode(d, KeyNeed.serializer())?.let { n ->
                if (nx.keys.ring.get(n.convId, n.epoch) != null) nx.scope.launch {
                    try {
                        nx.keys.grantTo(n.convId, n.epoch, n.userIds)
                    } catch (_: Exception) {
                    }
                }
            }
            "key_rotation_needed" -> d.str("conv_id")?.let { nx.keys.rotateSerially(it) }
            "voice_state" -> decode(d, VoiceState.serializer())?.let { v ->
                v.user?.let { cu -> if (nx.users.get(cu.id) == null && cu.flags and 8L == 0L) ensureUsers(listOf(cu.id)) }
                nx.voiceStates.apply(v)
            }
            "nexa_state" -> {
                nx.misc.nexa.value = d
                NexaInbox.onEvent(e.t, d)
            }
            "nexa_notice", "nexa_case", "nexa_bug" -> {
                (d as? JsonObject)?.get("state")?.let { nx.misc.nexa.value = it }
                NexaInbox.onEvent(e.t, d)
            }
            "nexa_announcement", "nexa_announcement_delete" -> {
                NexaInbox.onEvent(e.t, d)
                Moderation.onEvent(e.t, d)
            }
            "mod_case", "mod_log", "mod_bug", "mod_unread" -> Moderation.onEvent(e.t, d)
            "mod_report" -> Reports.onStaffEvent(d)
            "report_update" -> runCatching { wireJson.decodeFromJsonElement(ReportReceipt.serializer(), d!!) }.getOrNull()?.let { r ->
                val before = Reports.mine.value?.firstOrNull { it.id == r.id }
                Reports.onReceipt(r)
                NexaInbox.onReport(r, before)
            }
            "note_update", "note_delete", "note_invite", "note_invite_delete", "note_presence", "note_cursor", "note_crdt" -> fish.nexa.notes.Notes.onEvent(e.t, d)
            "session_expired" -> onSignedOut?.invoke(d.str("reason") ?: "expired")
            // the socket was shut on a ban; what the ban screen needs comes over REST
            "account_banned" -> nx.scope.launch {
                runCatching { (nx.api.get("/nexa") as? JsonObject)?.get("state")?.let { NexaInbox.setState(it) } }
            }
            else -> Unit // voice signalling, calls, notes and staff events are handled by the modules that listen to `events`
        }
    }

    /** A whole server and the people in it, as server_create brings one and GET /servers/{id} answers. */
    fun putServer(v: ServerView, users: List<PublicUser>) {
        judgeAll(users)
        nx.users.upsert(users)
        nx.channels.upsert(v.channels)
        nx.threads.setServer(v.id, v.threads, v.joinedThreads)
        nx.servers.put(v)
    }

    private fun judgeAll(users: Collection<PublicUser>) {
        for (u in users) if (u.publicKey.isNotEmpty() && u.id != nx.users.meId) nx.alerts.judge(u.id, u.publicKey)
    }

    private suspend fun onReady(d: ReadyPayload) {
        ServerClock.set(d.now)
        val keep = setOfNotNull(nx.ui.activeChannel.value)
        nx.messages.clearAll(keep)
        val keepUsers = keep.flatMap { ch -> nx.messages.get(ch).byId.values.map { it.authorId } }.toSet()
        nx.users.keep(keepUsers)
        nx.presence.clear()
        nx.typing.clear()
        nx.misc.sessionId.value = d.sessionId
        val prevMe = nx.users.me.value
        nx.users.setMe(if (prevMe != null && prevMe.id == d.user.id) d.user.copy(nexa = d.user.nexa ?: prevMe.nexa) else d.user)
        judgeAll(d.users)
        nx.users.upsert(d.users + d.user.asPublic())
        nx.misc.nexa.value = d.nexa ?: d.user.nexa
        NexaInbox.setState(d.nexa ?: d.user.nexa)
        nx.channels.replaceAll(d.channels + d.servers.flatMap { it.channels + it.threads })
        nx.threads.clear()
        for (v in d.servers) nx.threads.setServer(v.id, v.threads, v.joinedThreads)
        nx.relationships.replaceAll(d.relationships)
        nx.readStates.replaceAll(d.readStates)
        nx.misc.groupAddNotices.value = persistentHashMapOf<String, GroupAddNotice>().builder().apply { d.groupAddNotices.forEach { put(it.channelId, it) } }.build()
        nx.presence.replaceAll(d.presences)
        nx.servers.replaceAll(d.servers)
        // the rules members' apps apply, fetched for each server that has any
        Automod.warm(d.servers.filter { it.automodAt > 0 }.map { it.id })
        nx.expressions.setAll(d.emojis, d.stickerPacks)
        nx.voiceStates.replaceAll(d.voiceStates)
        nx.settings.loadFromReady(d.settings)
        _ready.value = true
        // acks that failed while offline, or ready's older state would bring the badge back
        val retry = synchronized(failedAcks) { failedAcks.toMap().also { failedAcks.clear() } }
        for ((ch, mid) in retry) nx.scope.launch { ack(ch, mid) }
        nx.scope.launch {
            try {
                val recent = nx.api.get("/stickers/recent", ListSerializer(Sticker.serializer()))
                nx.expressions.setRecent(recent)
            } catch (_: Exception) {
            }
        }
        nx.scope.launch {
            nx.keys.ingest(d.keyGrants)
            nx.keys.reconcile()
        }
        for (ch in keep) nx.scope.launch { nx.messages.syncGap(ch) }
        // The notes ride REST. Asked for again on every ready rather than
        // cleared first: a note open keeps what it has while the list catches up.
        fish.nexa.notes.Notes.onReady()
        nx.auth.applyLockPolicy()
    }

    fun mentionsMe(m: Message, meId: String): Boolean {
        if (m.mentionEveryone || meId in m.mentions) return true
        if (m.mentionRoles.isEmpty()) return false
        val sid = nx.channels.get(m.channelId)?.serverId ?: return false
        val mine = nx.servers.get(sid)?.me?.roleIds ?: return false
        return m.mentionRoles.any { it in mine }
    }

    fun isQuiet(cm: ChatMessage): Boolean {
        if (nx.relationships.isBlocked(cm.authorId)) return true
        if (nx.settings.hideBlockedReplies) {
            val reply = cm.m.replyToId ?: return false
            val target = nx.messages.get(cm.channelId).byId[reply] ?: (ReplyTargets.byId.value[reply] as? ReplyTarget.Found)?.cm ?: return false
            return nx.relationships.isBlocked(target.authorId)
        }
        return false
    }

    private suspend fun onMessageCreate(m: Message, meId: String?) {
        val dec = nx.messages.decrypt(m)
        // An app's answer only this person sees: it shows where it was asked
        // for, and is no part of the conversation (nothing unread, nothing newest).
        if (m.ephemeral) {
            nx.messages.insert(m.channelId, listOf(dec))
            ensureUsers(listOf(m.authorId))
            return
        }
        if (m.nonce != null && meId == m.authorId) nx.messages.settle(m.channelId, listOf(dec), nonce = m.nonce)
        else nx.messages.insert(m.channelId, listOf(dec))
        val lastBefore = nx.channels.get(m.channelId)?.lastMessageId
        nx.channels.setLastMessage(m.channelId, m.id)
        // a thread counts what is said in it, for the message it hangs from, and shows the newest under it
        val inThread = nx.channels.get(m.channelId)
        val meta = inThread?.thread
        if (inThread != null && meta != null && !m.type.startsWith("system_") && lastBefore != m.id) {
            nx.channels.upsert(listOf(inThread.copy(thread = meta.copy(messageCount = meta.messageCount + 1, archived = false))))
            if (inThread.type == "thread") ThreadLast.noteMessage(m.channelId, dec)
        }
        nx.typing.remove(m.channelId, m.authorId)
        if (dec.error == DecryptFail.NO_KEY && m.keyConvId != null) nx.keys.markWaiting(m.keyConvId)
        ensureUsers(listOf(m.authorId))
        ensureChannel(m.channelId)
        // AutoMod: repeats are counted, and a moderator's app reports what got past
        Automod.onArrive(dec, meId)
        val mine = meId == m.authorId
        val acted = mine && m.type != "system_poll_result"
        if (acted || nx.ui.readingPresent(m.channelId)) {
            nx.scope.launch { ack(m.channelId, m.id) }
        } else if (isQuiet(dec)) {
            if (!nx.readStates.isUnreadAfter(m.channelId, lastBefore)) nx.scope.launch { ack(m.channelId, m.id) }
        } else {
            if (meId != null && mentionsMe(m, meId)) nx.readStates.bumpMentions(m.channelId)
            val type = nx.channels.get(m.channelId)?.type
            if (!mine && (type == null || type == "dm" || type == "group_dm" || type == "subchat")) nx.readStates.bumpUnread(m.channelId)
        }
        // what AutoMod keeps hidden makes no sound
        if (Automod.heldBy(dec, meId) == null) onMessage?.invoke(m, mine, dec)
    }

    private fun adjustEditMentions(d: JsonElement?, m: Message, meId: String?) {
        if (meId == null || m.authorId == meId || nx.relationships.isBlocked(m.authorId)) return
        val was = (d as? JsonObject)?.get("was") as? JsonObject ?: return
        val before = m.copy(
            mentions = (was["mentions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            mentionRoles = (was["mention_roles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            mentionEveryone = (was["mention_everyone"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        val wasMe = mentionsMe(before, meId)
        val isMe = mentionsMe(m, meId)
        if (wasMe == isMe) return
        val mark = nx.readStates.get(m.channelId)?.lastReadId
        if (mark != null && Ids.compare(m.id, mark) <= 0) return
        if (isMe) nx.readStates.bumpMentions(m.channelId) else nx.readStates.takeBack(m.channelId, 0, 1)
    }

    private fun onDeleted(ch: String, ids: List<String>, d: JsonElement?, single: Boolean) {
        // a thread's newest message gone: its card stops quoting it
        for (id in ids) ThreadLast.forget(ch, id)
        val o = d as? JsonObject
        if (o != null && o.containsKey("last_message_id")) nx.channels.resetLastMessage(ch, o.str("last_message_id"))
        val meId = nx.users.meId
        val mark = nx.readStates.get(ch)?.lastReadId
        val held = nx.messages.get(ch).byId
        val metas: Map<String, JsonObject> = if (single) mapOf(ids[0] to (o ?: JsonObject(emptyMap())))
        else ((o?.get("mentions") as? JsonArray)?.mapNotNull { it as? JsonObject }?.associateBy { it.str("id") ?: "" } ?: emptyMap())
        var unread = 0
        var mentions = 0
        val type = nx.channels.get(ch)?.type
        for (id in ids) {
            if (mark != null && Ids.compare(id, mark) <= 0) continue
            val cm = held[id]
            val author = cm?.authorId ?: metas[id].str("author_id")
            if (author == meId || nx.relationships.isBlocked(author)) continue
            if (type == "dm" || type == "group_dm" || type == "subchat") unread++
            val mentioned = if (cm != null) meId != null && mentionsMe(cm.m, meId) else {
                val meta = metas[id]
                meta != null && meId != null && (meta.bool("mention_everyone") ||
                    (meta["mentions"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == meId } == true)
            }
            if (mentioned) mentions++
        }
        if (unread > 0 || mentions > 0) nx.readStates.takeBack(ch, unread, mentions)
        nx.messages.removeMany(ch, ids)
    }

    /** Moves the read marker: here at once, then on the server (and every other device). */
    suspend fun ack(channelId: String, messageId: String, manual: Boolean = false) {
        if (!manual) nx.readStates.markRead(channelId, messageId)
        try {
            val res = nx.api.request("POST", "/channels/$channelId/messages/$messageId/ack", if (manual) buildJsonObject { put("manual", true) } else null)
            decode(res, ReadState.serializer())?.let { nx.readStates.apply(it) }
        } catch (e: fish.nexa.net.ApiError) {
            Log.w(TAG, "ack $channelId/$messageId failed: ${e.status} ${e.code}")
            if (e.isTransient) synchronized(failedAcks) { failedAcks[channelId] = messageId }
        }
    }

    /** Fetches people this device has not seen yet, once each. */
    fun ensureUsers(ids: Collection<String>) {
        val missing = synchronized(fetchingUsers) {
            ids.filter { it.isNotEmpty() && it != "0" && nx.users.get(it) == null && fetchingUsers.add(it) }
        }
        if (missing.isEmpty()) return
        for (id in missing) nx.scope.launch {
            try {
                val u = nx.api.get("/users/$id", PublicUser.serializer())
                judgeAll(listOf(u))
                nx.users.upsert(listOf(u))
            } catch (_: Exception) {
            } finally {
                synchronized(fetchingUsers) { fetchingUsers.remove(id) }
            }
        }
    }

    private fun ensureChannel(id: String) {
        if (nx.channels.get(id) != null) return
        if (!synchronized(fetchingChannels) { fetchingChannels.add(id) }) return
        nx.scope.launch {
            try {
                val c = nx.api.get("/channels/$id", Channel.serializer())
                nx.channels.upsert(listOf(c))
                ensureUsers(c.recipientIds)
            } catch (e: fish.nexa.net.ApiError) {
                if (e.status == 403 || e.status == 404) nx.messages.clearChannel(id)
            } catch (_: Exception) {
            } finally {
                synchronized(fetchingChannels) { fetchingChannels.remove(id) }
            }
        }
    }

    fun reset() {
        stop()
        ThreadLast.clear()
        fish.nexa.ui.markdown.LinkedChannels.clear()
        Games.reset()
        Invites.clear()
        nx.messages.clear()
        nx.users.clear()
        nx.channels.replaceAll(emptyList())
        nx.servers.clear()
        nx.relationships.replaceAll(emptyList())
        nx.readStates.clear()
        nx.presence.clear()
        nx.typing.clear()
        nx.voiceStates.clear()
        nx.expressions.clear()
        nx.misc.clear()
        synchronized(failedAcks) { failedAcks.clear() }
        fish.nexa.notify.Notifier.clearAccount()
        fish.nexa.ui.layers.Layers.clear()
        fish.nexa.ui.nav.Nav.reset()
        fish.nexa.media.Media.clear()
    }

    companion object {
        private const val TAG = "NexaSession"

        /** What a locked app still takes from its connection: the call, and being signed out or banned. */
        private val LOCKED_EVENTS = setOf("ready", "voice_state", "voice_signal", "voice_error", "voice_resumed", "call_end", "session_expired", "account_banned")
    }
}
