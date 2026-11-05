package fish.nexa.data

import fish.nexa.net.*
import java.util.Locale
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberRosterTest {
    // Nexa's Developers (flag 1) are ordinary members: a private channel hides them like anyone
    @Test fun filtersPrivateChannelsToTheOwnerAndAllowedRoles() {
        val members = listOf("owner", "admin", "allowed", "hidden", "me").associateWith { Member("s", it, roleIds = if (it == "allowed") listOf("r") else emptyList()) }
        val st = ServerState(Server("s", ownerId = "owner"), persistentHashMapOf("s" to Role("s", permissions = P.DEFAULT_EVERYONE.toString()), "r" to Role("r")), members.toPersistentHashMap(), 5, members["me"]!!, 0, true)
        val users = mapOf("admin" to PublicUser("admin", flags = 1))
        val ow = listOf(Overwrite(targetId = "s", targetType = "role", deny = P.VIEW_CHANNEL.toString()), Overwrite(targetId = "r", targetType = "role", allow = P.VIEW_CHANNEL.toString()))
        val shown = MemberRoster.sorted(st, users, ow, true, "me", 1, "You", "Unknown", Locale.ENGLISH)
        assertEquals(setOf("owner", "allowed"), shown.map { it.first.userId }.toSet())
        assertEquals(5, MemberRoster.sorted(st, users, ow, false, "me", 0, "You", "Unknown", Locale.ENGLISH).size)
    }

    @Test fun sortsNicknamesDisplayNamesAndFallbacksInTheChosenLocale() {
        val a = Member("s", "a", nickname = "Zed")
        val b = Member("s", "b")
        val me = Member("s", "me")
        val st = ServerState(Server("s"), persistentHashMapOf(), persistentHashMapOf("a" to a, "b" to b, "me" to me), 3, me, 0, true)
        val names = MemberRoster.sorted(st, mapOf("a" to PublicUser("a", displayName = "Ignored"), "b" to PublicUser("b", username = "Alice")), null, false, "me", 0, "You", "Unknown", Locale.ENGLISH)
        assertEquals(listOf("Alice", "You", "Zed"), names.map { it.second })
    }
}
