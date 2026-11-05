package fish.nexa.data

import fish.nexa.net.Member
import fish.nexa.net.Overwrite
import fish.nexa.net.PublicUser
import java.text.Collator
import java.util.Locale

/** Permission filtering and locale-aware sorting for the member list, run away from the UI thread. */
object MemberRoster {
    fun sorted(
        st: ServerState,
        users: Map<String, PublicUser>,
        overwrites: List<Overwrite>?,
        filterChannel: Boolean,
        meId: String?,
        meFlags: Long,
        you: String,
        unknown: String,
        locale: Locale,
        now: Long = System.currentTimeMillis(),
    ): List<Pair<Member, String>> {
        val order = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return st.members.values.asSequence().filter { m ->
            val flags = if (m.userId == meId) meFlags else users[m.userId]?.flags ?: 0L
            val owner = st.server.ownerId == m.userId
            !filterChannel || P.has(Perms.compute(st.server.id, st.permissionRoles, Perms.member(m, m.userId, now), owner, overwrites), P.VIEW_CHANNEL)
        }.map { m ->
            val user = users[m.userId]
            val name = m.nickname.takeIf { it.isNotBlank() }
                ?: user?.displayName?.takeIf { it.isNotBlank() }
                ?: user?.username?.takeIf { it.isNotBlank() }
                ?: if (user == null && m.userId == meId) you else unknown
            m to name
        }.toList().sortedWith { a, b -> order.compare(a.second, b.second) }
    }
}
