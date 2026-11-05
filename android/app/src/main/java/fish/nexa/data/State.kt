package fish.nexa.data

import fish.nexa.net.Channel
import fish.nexa.net.Emoji
import fish.nexa.net.GroupAddNotice
import fish.nexa.net.Member
import fish.nexa.net.MeUser
import fish.nexa.net.Presence
import fish.nexa.net.PublicUser
import fish.nexa.net.ReadState
import fish.nexa.net.Relationship
import fish.nexa.net.Role
import fish.nexa.net.Server
import fish.nexa.net.ServerView
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.net.VoiceState
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * The app's state, store by store, as the web keeps it in its zustand stores
 * (web/src/stores). Each holds an immutable snapshot in a StateFlow, replaced
 * with `update`, so any thread may write and the UI recomposes from whatever
 * it reads. Gateway events are applied to these in arrival order by Session.
 */
class UsersStore {
    private val _me = MutableStateFlow<MeUser?>(null)
    val me: StateFlow<MeUser?> = _me
    private val _byId = MutableStateFlow(persistentHashMapOf<String, PublicUser>())
    val byId: StateFlow<PersistentMap<String, PublicUser>> = _byId

    val meId get() = _me.value?.id

    fun setMe(u: MeUser?) {
        _me.value = u
        if (u != null) upsert(listOf(u.asPublic()))
    }

    /** A profile change for the account itself: its own flags stay (the public copy masks them). */
    fun mergeMe(u: PublicUser) = _me.update { cur ->
        cur?.copy(
            username = u.username, displayName = u.displayName, avatarId = u.avatarId, bannerId = u.bannerId, aboutMe = u.aboutMe,
            pronouns = u.pronouns, accentColor = u.accentColor, accentColor2 = u.accentColor2, statusText = u.statusText,
            statusEmoji = u.statusEmoji, statusExpiresAt = u.statusExpiresAt, publicKey = u.publicKey.ifEmpty { cur.publicKey },
            groupLinkOnly = u.groupLinkOnly, typingStyle = u.typingStyle, serverTag = u.serverTag, plan = u.plan,
        )
    }

    fun upsert(users: Collection<PublicUser>) {
        if (users.isEmpty()) return
        _byId.update { m -> m.builder().apply { for (u in users) put(u.id, u) }.build() }
    }

    fun get(id: String?): PublicUser? = id?.let { _byId.value[it] }

    fun keep(ids: Set<String>) = _byId.update { m -> m.filterKeys { it in ids }.toPersistentHashMap() }

    fun clear() {
        _byId.value = persistentHashMapOf()
    }
}

class ChannelsStore {
    private val _byId = MutableStateFlow(persistentHashMapOf<String, Channel>())
    val byId: StateFlow<PersistentMap<String, Channel>> = _byId

    fun get(id: String?): Channel? = id?.let { _byId.value[it] }

    fun replaceAll(list: Collection<Channel>) {
        _byId.value = list.associateBy { it.id }.toPersistentHashMap()
    }

    fun upsert(list: Collection<Channel>) {
        if (list.isEmpty()) return
        _byId.update { m -> m.builder().apply { for (c in list) put(c.id, c) }.build() }
    }

    fun remove(id: String) = _byId.update { it.remove(id) }

    fun removeServer(serverId: String): List<String> {
        val gone = _byId.value.values.filter { it.serverId == serverId }.map { it.id }
        _byId.update { m -> m.builder().apply { gone.forEach { remove(it) } }.build() }
        return gone
    }

    fun setLastMessage(channelId: String, id: String) = _byId.update { m ->
        val c = m[channelId] ?: return@update m
        if (c.lastMessageId != null && Ids.compare(c.lastMessageId, id) >= 0) m else m.put(channelId, c.copy(lastMessageId = id))
    }

    fun resetLastMessage(channelId: String, id: String?) = _byId.update { m ->
        val c = m[channelId] ?: return@update m
        m.put(channelId, c.copy(lastMessageId = id))
    }

    fun positions(serverId: String, list: List<Triple<String, Int, String?>>) = _byId.update { m ->
        m.builder().apply {
            for ((id, pos, parent) in list) {
                val c = get(id) ?: continue
                if (c.serverId == serverId) put(id, c.copy(position = pos, parentId = parent))
            }
        }.build()
    }

    /** Which conversation's keys encrypt a channel (web convForChannel). */
    fun convFor(channelId: String): String {
        val ch = get(channelId)
        if (ch != null && ch.keyConvId.isNotEmpty() && ch.keyConvId != "0") return ch.keyConvId
        if (ch?.serverId != null && ch.type != "dm" && ch.type != "group_dm") return ch.serverId
        return channelId
    }
}

/** One server as this device holds it. */
data class ServerState(
    val server: Server,
    val roles: PersistentMap<String, Role>,
    val members: PersistentMap<String, Member>,
    val memberCount: Int,
    val me: Member,
    val myPermissions: Long,
    val membersComplete: Boolean,
) {
    val permissionRoles: List<PermRole> by lazy { Perms.roles(roles.values) }
}

class ServersStore(private val users: UsersStore, private val channels: ChannelsStore) {
    private val _byId = MutableStateFlow(persistentHashMapOf<String, ServerState>())
    val byId: StateFlow<PersistentMap<String, ServerState>> = _byId
    private val _order = MutableStateFlow<List<String>>(emptyList())
    /** Join order, as ready lists them; the rail's own order comes from settings. */
    val order: StateFlow<List<String>> = _order

    fun get(id: String?): ServerState? = id?.let { _byId.value[it] }

    fun stateOf(view: ServerView): ServerState {
        val meId = users.meId ?: ""
        val me = view.me ?: view.members.firstOrNull { it.userId == meId } ?: Member(view.id, meId)
        return ServerState(
            view.server(), view.roles.associateBy { it.id }.toPersistentHashMap(),
            view.members.associateBy { it.userId }.toPersistentHashMap(), view.memberCount, me, P.parse(view.myPermissions),
            view.members.size >= view.memberCount,
        )
    }

    fun replaceAll(views: List<ServerView>) {
        _byId.value = views.associate { it.id to stateOf(it) }.toPersistentHashMap()
        _order.value = views.map { it.id }
    }

    fun put(view: ServerView) {
        _byId.update { it.put(view.id, stateOf(view)) }
        _order.update { if (view.id in it) it else it + view.id }
    }

    fun remove(id: String) {
        _byId.update { it.remove(id) }
        _order.update { it - id }
    }

    private fun change(id: String, fn: (ServerState) -> ServerState) = _byId.update { m ->
        val cur = m[id] ?: return@update m
        val next = fn(cur)
        m.put(id, next.copy(myPermissions = serverPerms(next, next.me.userId)))
    }

    fun setServer(s: Server) = change(s.id) { it.copy(server = s) }

    fun upsertMember(m: Member, user: PublicUser? = null) {
        user?.let { users.upsert(listOf(it)) }
        change(m.serverId) { st ->
            val isNew = m.userId !in st.members
            st.copy(
                members = st.members.put(m.userId, m),
                memberCount = if (isNew) st.memberCount + 1 else st.memberCount,
                me = if (m.userId == st.me.userId) m else st.me,
            )
        }
    }

    fun updateMember(m: Member) = change(m.serverId) { st ->
        st.copy(members = st.members.put(m.userId, m), me = if (m.userId == st.me.userId) m else st.me)
    }

    fun removeMember(serverId: String, userId: String) = change(serverId) { st ->
        if (userId !in st.members) st.copy(memberCount = maxOf(0, st.memberCount - 1))
        else st.copy(members = st.members.remove(userId), memberCount = maxOf(0, st.memberCount - 1))
    }

    fun addMembers(serverId: String, list: List<Member>, complete: Boolean? = null) = change(serverId) { st ->
        val members = st.members.builder().apply { list.forEach { put(it.userId, it) } }.build()
        st.copy(members = members, me = members[st.me.userId] ?: st.me, membersComplete = complete ?: st.membersComplete)
    }

    fun upsertRole(r: Role) = change(r.serverId) { it.copy(roles = it.roles.put(r.id, r)) }

    fun removeRole(serverId: String, roleId: String) = change(serverId) { st ->
        val members = st.members.mapValues { (_, m) -> if (roleId in m.roleIds) m.copy(roleIds = m.roleIds - roleId) else m }.toPersistentHashMap()
        st.copy(roles = st.roles.remove(roleId), members = members, me = members[st.me.userId] ?: st.me)
    }

    fun setMemberCount(serverId: String, count: Int) = change(serverId) { it.copy(memberCount = count) }

    fun clear() {
        _byId.value = persistentHashMapOf()
        _order.value = emptyList()
    }

    /**
     * View Server as Role: a preview, on this device only, of a server as one
     * role sees it (with @everyone). Only this account's own permissions are
     * worked out from it; the server still sends what the account may see.
     */
    data class ViewAs(val serverId: String, val roleIds: List<String>)

    private val _viewAs = MutableStateFlow<ViewAs?>(null)
    val viewAs: StateFlow<ViewAs?> = _viewAs

    private fun viewingAs(st: ServerState, userId: String): List<String>? =
        _viewAs.value?.takeIf { it.serverId == st.server.id && userId == st.me.userId }?.roleIds

    fun startViewAs(serverId: String, roleId: String) {
        val prev = _viewAs.value
        _viewAs.value = ViewAs(serverId, if (roleId == serverId) emptyList() else listOf(roleId))
        if (prev != null && prev.serverId != serverId) change(prev.serverId) { it }
        change(serverId) { it }
    }

    fun stopViewAs() {
        val prev = _viewAs.value ?: return
        _viewAs.value = null
        change(prev.serverId) { it }
    }

    fun serverPerms(st: ServerState, userId: String): Long {
        viewingAs(st, userId)?.let { return Perms.base(st.server.id, st.permissionRoles, PermMember(userId, it, false), false) }
        // only the server's owner holds everything: Nexa's staff are ordinary members here
        if (st.server.ownerId == userId) return P.ALL
        return Perms.base(st.server.id, st.permissionRoles, Perms.member(st.members[userId], userId), false)
    }

    /** Threads this account is in, kept by the threads store, for private threads' permissions. */
    var joinedThread: (String) -> Boolean = { false }

    fun channelPerms(st: ServerState, channelId: String, userId: String): Long {
        val ch = channels.get(channelId)
        val preview = viewingAs(st, userId)
        val isOwner = preview == null && st.server.ownerId == userId
        val member = if (preview != null) PermMember(userId, preview, false) else Perms.member(st.members[userId], userId)
        // a thread answers to its channel
        if (ch != null && ch.isThread && ch.parentId != null) {
            val parent = Perms.compute(st.server.id, st.permissionRoles, member, isOwner, channels.get(ch.parentId)?.overwrites)
            // only this account's own membership is known here; others' private threads read as closed
            val inIt = userId == st.me.userId && joinedThread(channelId)
            return Perms.thread(parent, ch.type == "private_thread", inIt)
        }
        return Perms.compute(st.server.id, st.permissionRoles, member, isOwner, ch?.overwrites)
    }

    fun canI(st: ServerState?, need: Long, channelId: String? = null): Boolean {
        if (st == null) return false
        if (channelId != null) return P.has(channelPerms(st, channelId, st.me.userId), need)
        if (viewingAs(st, st.me.userId) != null) return P.has(serverPerms(st, st.me.userId), need)
        val until = st.me.timeoutUntil
        val lapsed = until != null && until <= System.currentTimeMillis()
        return P.has(if (lapsed) serverPerms(st, st.me.userId) else st.myPermissions, need)
    }

    fun memberHighest(st: ServerState, userId: String): Pair<Int, Boolean> {
        viewingAs(st, userId)?.let { return Perms.highestPosition(st.permissionRoles, PermMember(userId, it, false)) to false }
        val isOwner = st.server.ownerId == userId
        return Perms.highestPosition(st.permissionRoles, Perms.member(st.members[userId], userId)) to isOwner
    }

    /** Roles top first. */
    fun rolesByPosition(st: ServerState?): List<Role> =
        st?.roles?.values?.sortedWith(compareByDescending<Role> { it.position }.thenBy { it.id.length }.thenBy { it.id }) ?: emptyList()

    /** The colour of a member's highest coloured role (0xRRGGBB), or null. */
    fun memberColor(st: ServerState?, userId: String): Long? {
        val m = st?.members?.get(userId) ?: return null
        var best: Role? = null
        for (id in m.roleIds) {
            val r = st.roles[id] ?: continue
            if (r.color != 0L && (best == null || r.position > best.position)) best = r
        }
        return best?.color
    }
}

class RelationshipsStore(private val users: UsersStore) {
    private val _byId = MutableStateFlow(persistentHashMapOf<String, Relationship>())
    val byId: StateFlow<PersistentMap<String, Relationship>> = _byId

    fun replaceAll(list: List<Relationship>) {
        users.upsert(list.map { it.user })
        _byId.value = list.associateBy { it.user.id }.toPersistentHashMap()
    }

    fun set(r: Relationship) {
        users.upsert(listOf(r.user))
        _byId.update { it.put(r.user.id, r) }
    }

    fun remove(userId: String) = _byId.update { it.remove(userId) }

    fun typeOf(userId: String?): String? = userId?.let { _byId.value[it]?.type }
    fun isBlocked(userId: String?) = typeOf(userId) == "blocked"
    fun isFriend(userId: String?) = typeOf(userId) == "friend"
    fun nickname(userId: String?): String? = userId?.let { _byId.value[it]?.nickname?.takeIf { n -> n.isNotBlank() } }
    fun blocked(): Set<String> = _byId.value.values.filter { it.type == "blocked" }.map { it.user.id }.toSet()
}

/** A channel's read marker and counts, as this device keeps them between readies. */
data class ReadMark(val lastReadId: String?, val mentions: Int, val unread: Int, val manual: Boolean = false)

class ReadStatesStore(private val channels: ChannelsStore) {
    private val _byChannel = MutableStateFlow(persistentHashMapOf<String, ReadMark>())
    val byChannel: StateFlow<PersistentMap<String, ReadMark>> = _byChannel

    fun replaceAll(list: List<ReadState>) {
        _byChannel.value = list.associate { it.channelId to ReadMark(it.lastReadMessageId, it.mentionCount, it.unreadCount, it.manual) }.toPersistentHashMap()
    }

    /** A read_state from the server: never moves the marker back unless it was a Mark Unread. */
    fun apply(rs: ReadState) = _byChannel.update { m ->
        val cur = m[rs.channelId]
        if (!rs.manual && cur?.lastReadId != null && rs.lastReadMessageId != null && Ids.compare(rs.lastReadMessageId, cur.lastReadId) < 0) return@update m
        m.put(rs.channelId, ReadMark(rs.lastReadMessageId, rs.mentionCount, rs.unreadCount, rs.manual))
    }

    /** Marks a channel read up to `id` here at once; the REST ack follows. */
    fun markRead(channelId: String, id: String) = _byChannel.update { m ->
        val cur = m[channelId]
        val last = Ids.max(cur?.lastReadId, id)
        m.put(channelId, ReadMark(last, 0, 0))
    }

    /** Sets the marker here as given (Mark Unread does this before telling the server). */
    fun setLocal(channelId: String, mark: ReadMark) = _byChannel.update { it.put(channelId, mark) }

    fun bumpMentions(channelId: String) = _byChannel.update { m ->
        val cur = m[channelId] ?: ReadMark(null, 0, 0)
        m.put(channelId, cur.copy(mentions = minOf(100, cur.mentions + 1)))
    }

    fun bumpUnread(channelId: String) = _byChannel.update { m ->
        val cur = m[channelId] ?: ReadMark(null, 0, 0)
        m.put(channelId, cur.copy(unread = minOf(100, cur.unread + 1)))
    }

    fun takeBack(channelId: String, unread: Int, mentions: Int) = _byChannel.update { m ->
        val cur = m[channelId] ?: return@update m
        m.put(channelId, cur.copy(unread = maxOf(0, cur.unread - unread), mentions = maxOf(0, cur.mentions - mentions)))
    }

    fun get(channelId: String) = _byChannel.value[channelId]

    /** Unread: the channel's newest message is past the read marker. */
    fun isUnread(channelId: String): Boolean {
        val ch = channels.get(channelId) ?: return false
        val last = ch.lastMessageId ?: return false
        val mark = _byChannel.value[channelId]?.lastReadId ?: return true
        return Ids.compare(last, mark) > 0
    }

    fun isUnreadAfter(channelId: String, lastBefore: String?): Boolean {
        val last = lastBefore ?: return false
        val mark = _byChannel.value[channelId]?.lastReadId ?: return true
        return Ids.compare(last, mark) > 0
    }

    fun mentions(channelId: String) = _byChannel.value[channelId]?.mentions ?: 0
    fun unreadCount(channelId: String) = _byChannel.value[channelId]?.unread ?: 0

    fun clear() {
        _byChannel.value = persistentHashMapOf()
    }
}

class PresenceStore {
    private val _byId = MutableStateFlow(persistentHashMapOf<String, Presence>())
    val byId: StateFlow<PersistentMap<String, Presence>> = _byId

    fun replaceAll(list: List<Presence>) {
        _byId.value = list.associateBy { it.userId }.toPersistentHashMap()
    }

    fun set(p: Presence) = _byId.update { it.put(p.userId, p) }

    fun statusOf(userId: String?): String = userId?.let { _byId.value[it]?.status } ?: "offline"

    fun clear() {
        _byId.value = persistentHashMapOf()
    }
}

/** Who is typing where, each until a moment 10 s after their last start arrived. */
class TypingStore {
    private val _byChannel = MutableStateFlow(persistentHashMapOf<String, PersistentMap<String, Long>>())
    val byChannel: StateFlow<PersistentMap<String, PersistentMap<String, Long>>> = _byChannel

    fun add(channelId: String, userId: String, now: Long = System.currentTimeMillis()) = _byChannel.update { m ->
        val cur = m[channelId] ?: persistentHashMapOf()
        m.put(channelId, cur.put(userId, now + 10_000))
    }

    fun remove(channelId: String, userId: String) = _byChannel.update { m ->
        val cur = m[channelId] ?: return@update m
        if (userId !in cur) m else m.put(channelId, cur.remove(userId))
    }

    /** Drops what has expired; called on a timer while anyone is typing. */
    fun sweep(now: Long = System.currentTimeMillis()) = _byChannel.update { m ->
        var changed = false
        val b = m.builder()
        for ((ch, users) in m) {
            val kept = users.filterValues { it > now }
            if (kept.size != users.size) {
                changed = true
                if (kept.isEmpty()) b.remove(ch) else b[ch] = kept.toPersistentHashMap()
            }
        }
        if (changed) b.build() else m
    }

    fun clear() {
        _byChannel.value = persistentHashMapOf()
    }
}

class VoiceStatesStore {
    private val _byUser = MutableStateFlow(persistentHashMapOf<String, VoiceState>())
    /** Every call participant this device can see, by user. */
    val byUser: StateFlow<PersistentMap<String, VoiceState>> = _byUser

    fun replaceAll(list: List<VoiceState>) {
        _byUser.value = list.filter { it.channelId != null }.associateBy { it.userId }.toPersistentHashMap()
    }

    fun apply(v: VoiceState) = _byUser.update { m -> if (v.channelId == null) m.remove(v.userId) else m.put(v.userId, v) }

    fun inChannel(channelId: String): List<VoiceState> = _byUser.value.values.filter { it.channelId == channelId }.sortedBy { it.joinedAt }

    fun clear() {
        _byUser.value = persistentHashMapOf()
    }
}

class ExpressionsStore {
    private val _emojis = MutableStateFlow(persistentHashMapOf<String, List<Emoji>>())
    /** Custom emoji by server. */
    val emojis: StateFlow<PersistentMap<String, List<Emoji>>> = _emojis
    private val _packs = MutableStateFlow(persistentHashMapOf<String, StickerPack>())
    val packs: StateFlow<PersistentMap<String, StickerPack>> = _packs
    private val _recent = MutableStateFlow<List<Sticker>>(emptyList())
    val recentStickers: StateFlow<List<Sticker>> = _recent

    fun setAll(emojis: List<Emoji>, packs: List<StickerPack>) {
        _emojis.value = emojis.groupBy { it.serverId }.toPersistentHashMap()
        _packs.value = packs.associateBy { it.id }.toPersistentHashMap()
    }

    fun setServerEmojis(serverId: String, list: List<Emoji>) = _emojis.update { it.put(serverId, list) }
    fun removeServer(serverId: String) {
        _emojis.update { it.remove(serverId) }
        _packs.update { m -> m.filterValues { it.serverId != serverId }.toPersistentHashMap() }
    }

    fun upsertPack(p: StickerPack) = _packs.update { it.put(p.id, p) }
    fun removePack(id: String) = _packs.update { it.remove(id) }

    fun setRecent(list: List<Sticker>) {
        _recent.value = list.take(24)
    }

    fun useRecent(s: Sticker) = _recent.update { (listOf(s) + it.filter { x -> x.id != s.id }).take(24) }

    /** Any custom emoji by id, wherever it is from. */
    fun emoji(id: String): Emoji? {
        for (list in _emojis.value.values) list.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    fun clear() {
        _emojis.value = persistentHashMapOf()
        _packs.value = persistentHashMapOf()
        _recent.value = emptyList()
    }
}

class MiscStore {
    val groupAddNotices = MutableStateFlow(persistentHashMapOf<String, GroupAddNotice>())
    /** The account's moderation standing (NexaState), kept as JSON. */
    val nexa = MutableStateFlow<JsonElement?>(null)
    val sessionId = MutableStateFlow<String?>(null)

    fun clear() {
        groupAddNotices.value = persistentHashMapOf()
    }
}
