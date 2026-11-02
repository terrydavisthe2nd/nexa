package fish.nexa.data

import fish.nexa.net.Member
import fish.nexa.net.Overwrite
import fish.nexa.net.Role

/** Permission bits and their resolution, exactly as web/src/lib/perms.ts (and the server) work them out. */
object P {
    const val CREATE_INSTANT_INVITE = 1L shl 0
    const val KICK_MEMBERS = 1L shl 1
    const val BAN_MEMBERS = 1L shl 2
    const val ADMINISTRATOR = 1L shl 3
    const val MANAGE_CHANNELS = 1L shl 4
    const val MANAGE_SERVER = 1L shl 5
    const val ADD_REACTIONS = 1L shl 6
    const val VIEW_AUDIT_LOG = 1L shl 7
    const val PRIORITY_SPEAKER = 1L shl 8
    const val STREAM = 1L shl 9
    const val VIEW_CHANNEL = 1L shl 10
    const val SEND_MESSAGES = 1L shl 11
    const val MANAGE_MESSAGES = 1L shl 13
    const val EMBED_LINKS = 1L shl 14
    const val ATTACH_FILES = 1L shl 15
    const val READ_MESSAGE_HISTORY = 1L shl 16
    const val MENTION_EVERYONE = 1L shl 17
    const val CONNECT = 1L shl 20
    const val SPEAK = 1L shl 21
    const val MUTE_MEMBERS = 1L shl 22
    const val DEAFEN_MEMBERS = 1L shl 23
    const val MOVE_MEMBERS = 1L shl 24
    const val USE_VAD = 1L shl 25
    const val CHANGE_NICKNAME = 1L shl 26
    const val MANAGE_NICKNAMES = 1L shl 27
    const val MANAGE_ROLES = 1L shl 28
    const val MANAGE_WEBHOOKS = 1L shl 29
    const val MANAGE_EMOJIS = 1L shl 30
    const val USE_APPLICATION_COMMANDS = 1L shl 31
    const val USE_EXTERNAL_EMOJIS = 1L shl 18
    const val MANAGE_THREADS = 1L shl 34
    const val CREATE_PUBLIC_THREADS = 1L shl 35
    const val CREATE_PRIVATE_THREADS = 1L shl 36
    const val USE_EXTERNAL_STICKERS = 1L shl 37
    const val SEND_MESSAGES_IN_THREADS = 1L shl 38
    const val MODERATE_MEMBERS = 1L shl 40
    const val USE_SOUNDBOARD = 1L shl 42
    const val CREATE_EXPRESSIONS = 1L shl 43
    const val CREATE_POLLS = 1L shl 49
    const val USE_EXTERNAL_APPS = 1L shl 50
    const val PIN_MESSAGES = 1L shl 51
    const val BYPASS_SLOWMODE = 1L shl 52
    /** Nexa's own: someone's typing style is shown when they type here; without it, the plain dots. Readers' apps hold it. */
    const val USE_CUSTOM_TYPING = 1L shl 60

    val ALL = listOf(
        CREATE_INSTANT_INVITE, KICK_MEMBERS, BAN_MEMBERS, ADMINISTRATOR, MANAGE_CHANNELS, MANAGE_SERVER, ADD_REACTIONS,
        VIEW_AUDIT_LOG, PRIORITY_SPEAKER, STREAM, VIEW_CHANNEL, SEND_MESSAGES, MANAGE_MESSAGES, EMBED_LINKS, ATTACH_FILES,
        READ_MESSAGE_HISTORY, MENTION_EVERYONE, CONNECT, SPEAK, MUTE_MEMBERS, DEAFEN_MEMBERS, MOVE_MEMBERS, USE_VAD,
        CHANGE_NICKNAME, MANAGE_NICKNAMES, MANAGE_ROLES, MANAGE_WEBHOOKS, MANAGE_EMOJIS, MODERATE_MEMBERS, USE_SOUNDBOARD, CREATE_POLLS,
        USE_EXTERNAL_EMOJIS, MANAGE_THREADS, CREATE_PUBLIC_THREADS, CREATE_PRIVATE_THREADS, USE_EXTERNAL_STICKERS, SEND_MESSAGES_IN_THREADS,
        CREATE_EXPRESSIONS, PIN_MESSAGES, BYPASS_SLOWMODE, USE_APPLICATION_COMMANDS, USE_EXTERNAL_APPS, USE_CUSTOM_TYPING,
    ).fold(0L) { a, b -> a or b }

    val DEFAULT_EVERYONE = CREATE_INSTANT_INVITE or ADD_REACTIONS or STREAM or VIEW_CHANNEL or SEND_MESSAGES or EMBED_LINKS or
        ATTACH_FILES or READ_MESSAGE_HISTORY or USE_EXTERNAL_EMOJIS or CONNECT or SPEAK or USE_VAD or CHANGE_NICKNAME or USE_SOUNDBOARD or
        CREATE_POLLS or CREATE_PUBLIC_THREADS or CREATE_PRIVATE_THREADS or USE_EXTERNAL_STICKERS or SEND_MESSAGES_IN_THREADS or
        USE_APPLICATION_COMMANDS or USE_EXTERNAL_APPS or USE_CUSTOM_TYPING

    /** What a timed-out member keeps. */
    const val TIMED_OUT_KEEP = VIEW_CHANNEL or READ_MESSAGE_HISTORY

    fun parse(s: String?): Long = s?.toULongOrNull()?.toLong() ?: s?.toLongOrNull() ?: 0L

    fun has(p: Long, need: Long) = p and need == need
}

data class PermRole(val id: String, val position: Int, val permissions: Long)
data class PermMember(val userId: String, val roleIds: List<String>, val timedOut: Boolean)

object Perms {
    fun roles(roles: Collection<Role>) = roles.map { PermRole(it.id, it.position, P.parse(it.permissions)) }

    /** A member as permissions see them: one AutoMod holds counts as timed out, as the server has it. */
    fun member(m: Member?, userId: String, now: Long = System.currentTimeMillis()) =
        PermMember(userId, m?.roleIds ?: emptyList(), (m?.quarantined ?: false) || (m?.timeoutUntil ?: 0L) > now)

    /**
     * What someone may do in a thread, from what they may do in its channel,
     * as the server's perms.Thread works it out: talking is Send Messages in
     * Threads, and a private thread is only for the people in it and those
     * who manage threads.
     */
    fun thread(parent: Long, isPrivate: Boolean, member: Boolean): Long {
        if (!P.has(parent, P.VIEW_CHANNEL)) return 0L
        if (P.has(parent, P.ADMINISTRATOR)) return P.ALL
        if (isPrivate && !member && !P.has(parent, P.MANAGE_THREADS)) return 0L
        var p = parent and P.SEND_MESSAGES.inv()
        if (P.has(parent, P.SEND_MESSAGES_IN_THREADS)) p = p or P.SEND_MESSAGES
        return p
    }

    fun base(everyoneId: String, roles: List<PermRole>, m: PermMember, isOwner: Boolean): Long {
        if (isOwner) return P.ALL
        val mine = HashSet<String>(m.roleIds.size + 1).apply { add(everyoneId); addAll(m.roleIds) }
        var p = 0L
        for (r in roles) if (r.id in mine) p = p or r.permissions
        if (P.has(p, P.ADMINISTRATOR)) return P.ALL
        if (m.timedOut) p = p and P.TIMED_OUT_KEEP
        return p
    }

    fun compute(everyoneId: String, roles: List<PermRole>, m: PermMember, isOwner: Boolean, overwrites: List<Overwrite>?): Long {
        val b = base(everyoneId, roles, m, isOwner)
        if (isOwner || P.has(b, P.ADMINISTRATOR)) return P.ALL
        var p = b
        val mine = m.roleIds.toHashSet()
        var everyoneOw: Overwrite? = null
        var roleAllow = 0L
        var roleDeny = 0L
        var memberOw: Overwrite? = null
        for (ow in overwrites ?: emptyList()) {
            if (ow.targetType == "role" && ow.targetId == everyoneId) everyoneOw = ow
            else if (ow.targetType == "role" && ow.targetId in mine) {
                roleAllow = roleAllow or P.parse(ow.allow)
                roleDeny = roleDeny or P.parse(ow.deny)
            } else if (ow.targetType == "member" && ow.targetId == m.userId) memberOw = ow
        }
        everyoneOw?.let { p = (p and P.parse(it.deny).inv()) or P.parse(it.allow) }
        p = (p and roleDeny.inv()) or roleAllow
        memberOw?.let { p = (p and P.parse(it.deny).inv()) or P.parse(it.allow) }
        if (m.timedOut) p = p and P.TIMED_OUT_KEEP
        if (!P.has(p, P.VIEW_CHANNEL)) return 0L
        return p
    }

    fun highestPosition(roles: List<PermRole>, m: PermMember): Int {
        var best = 0
        for (id in m.roleIds) for (r in roles) if (r.id == id && r.position > best) best = r.position
        return best
    }

    fun canManageRole(actorHighest: Int, actorIsOwner: Boolean, rolePosition: Int) = actorIsOwner || actorHighest > rolePosition

    fun canActOn(actorHighest: Int, targetHighest: Int, actorIsOwner: Boolean, targetIsOwner: Boolean): Boolean {
        if (targetIsOwner) return false
        return actorIsOwner || actorHighest > targetHighest
    }

    /** Whether two channels' overwrites say the same: a channel is synced with its category when they do (web sameOverwrites). */
    fun sameOverwrites(a: List<Overwrite>?, b: List<Overwrite>?): Boolean {
        val x = a.orEmpty()
        val y = b.orEmpty()
        if (x.size != y.size) return false
        val byTarget = x.associateBy { it.targetId }
        return y.all { o -> byTarget[o.targetId]?.let { it.targetType == o.targetType && P.parse(it.allow) == P.parse(o.allow) && P.parse(it.deny) == P.parse(o.deny) } == true }
    }

    fun isPrivateChannel(everyoneId: String, overwrites: List<Overwrite>?): Boolean {
        val ow = overwrites?.firstOrNull { it.targetType == "role" && it.targetId == everyoneId } ?: return false
        return P.has(P.parse(ow.deny), P.VIEW_CHANNEL)
    }
}

/** Snowflake order: numeric, never lexicographic. */
object Ids {
    const val EPOCH_MS = 1767225600000L

    fun compare(a: String, b: String): Int {
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.compareTo(b)
    }

    fun createdAt(id: String): Long = (id.toLongOrNull() ?: 0L).let { (it shr 22) + EPOCH_MS }

    fun max(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        compare(a, b) >= 0 -> a
        else -> b
    }

    /** An id just after this one, for "before" queries that should include it. */
    fun next(id: String): String = ((id.toLongOrNull() ?: 0L) + 1).toString()
}
