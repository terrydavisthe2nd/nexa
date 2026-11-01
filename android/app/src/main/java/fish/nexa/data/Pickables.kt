package fish.nexa.data

import fish.nexa.Nexa
import fish.nexa.net.Channel
import fish.nexa.net.Member
import fish.nexa.net.PublicUser
import fish.nexa.net.Role
import fish.nexa.net.wireJson
import fish.nexa.ui.I18n
import fish.nexa.ui.markdown.People
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * What someone can pick when an app asks for a person, a role or a channel
 * (web lib/pickables.ts): in a select menu on its message, an option of its
 * command, or a field of its form. The people, roles and channels of where it
 * was used, as the message box's @ and # suggestions find them.
 */
object Pickables {
    /** A channel's kind, as bot libraries number them. */
    fun protocolChannelType(ch: Channel): Int = when (ch.type) {
        "dm" -> 1
        "voice" -> 2
        "group_dm" -> 3
        "category" -> 4
        "announcement" -> 5
        "thread" -> 11
        "private_thread" -> 12
        "forum" -> 15
        "media" -> 16
        else -> 0
    }

    /** The people of where something was used that match what was typed, those who spoke last first. */
    fun people(channelId: String, query: String, limit: Int = 25): List<PublicUser> {
        val ch = Nexa.channels.get(channelId)
        val st = Nexa.servers.get(ch?.serverId)
        val pool = LinkedHashMap<String, PublicUser>()
        if (st != null) {
            for (id in st.members.keys) Nexa.users.get(id)?.let { pool[id] = it }
        } else if (ch != null) {
            for (id in ch.recipientIds) Nexa.users.get(id)?.let { pool[id] = it }
            Nexa.users.me.value?.let { me -> pool[me.id] = Nexa.users.get(me.id) ?: me.asPublic() }
        }
        val here = Nexa.messages.get(channelId)
        val authors = here.ids.takeLast(100).asReversed().mapNotNull { here.byId[it]?.authorId }
        return People.rank(
            query.trim(), pool.values.toList(), { People.Person(it.id, it.username, it.displayName) },
            { id -> st?.members?.get(id)?.nickname ?: "" }, People.recentSpeakers(authors), I18n.locale,
        ).take(limit)
    }

    private val asked = HashSet<String>()

    /**
     * Asks the server for a big server's members by name, once per name: its
     * members come a page at a time, and whoever is not loaded yet would
     * otherwise never be offered.
     */
    suspend fun findMembers(channelId: String, query: String) {
        val st = Nexa.servers.get(Nexa.channels.get(channelId)?.serverId) ?: return
        val q = query.trim()
        if (st.membersComplete || q.length < 2) return
        val key = "${st.server.id}:${q.lowercase()}"
        if (!synchronized(asked) { asked.add(key) }) return
        try {
            val res = Nexa.api.get("/servers/${st.server.id}/members?q=${URLEncoder.encode(q, "UTF-8")}&limit=25") as JsonObject
            Nexa.users.upsert(wireJson.decodeFromJsonElement(ListSerializer(PublicUser.serializer()), res["users"] ?: JsonArray(emptyList())))
            Nexa.servers.addMembers(st.server.id, wireJson.decodeFromJsonElement(ListSerializer(Member.serializer()), res["members"] ?: JsonArray(emptyList())))
        } catch (_: Exception) {
            synchronized(asked) { asked.remove(key) }
        }
    }

    /** The roles of where something was used that match what was typed, highest first; @everyone last. */
    fun roles(channelId: String, query: String, limit: Int = 25): List<Role> {
        val st = Nexa.servers.get(Nexa.channels.get(channelId)?.serverId) ?: return emptyList()
        val q = query.trim().lowercase()
        return Nexa.servers.rolesByPosition(st).filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(limit)
    }

    /** The channels of where something was used that this account can see, of the kinds asked for. */
    fun channels(channelId: String, query: String, types: List<Int>? = null, limit: Int = 25): List<Channel> {
        val ch = Nexa.channels.get(channelId) ?: return emptyList()
        // outside a server the only channel there is, is this one
        if (ch.serverId == null) return if (!types.isNullOrEmpty() && protocolChannelType(ch) !in types) emptyList() else listOf(ch)
        val st = Nexa.servers.get(ch.serverId)
        val q = query.trim().lowercase()
        val collator = java.text.Collator.getInstance(I18n.locale)
        return Nexa.channels.byId.value.values
            .filter { c -> c.serverId == ch.serverId && (types.isNullOrEmpty() || protocolChannelType(c) in types) && (q.isEmpty() || c.name.lowercase().contains(q)) && Nexa.servers.canI(st, P.VIEW_CHANNEL, c.id) }
            .sortedWith(compareBy<Channel> { it.position }.thenBy(collator) { it.name })
            .take(limit)
    }

    /** The words a picked person, role or channel shows with. */
    fun label(channelId: String, value: String): String {
        Nexa.users.get(value)?.let { return it.name }
        Nexa.channels.get(value)?.let { return "#${it.name}" }
        return roles(channelId, "", 1000).firstOrNull { it.id == value }?.name ?: value
    }
}
