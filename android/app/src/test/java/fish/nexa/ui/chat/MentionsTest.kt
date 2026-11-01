package fish.nexa.ui.chat

import fish.nexa.net.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    private fun m(mentions: List<String>, roles: List<String>, everyone: Boolean = false) =
        Message(id = "1", channelId = "2", mentions = mentions, mentionRoles = roles, mentionEveryone = everyone)

    @Test fun prefersYouThenYourRoleThenEveryone() {
        assertEquals(Mentions.Why.Me, Mentions.why(m(listOf("1"), listOf("r"), true), "1", listOf("r")))
        assertEquals(Mentions.Why.OfRole("r"), Mentions.why(m(emptyList(), listOf("x", "r"), true), "1", listOf("r")))
        assertEquals(Mentions.Why.Everyone, Mentions.why(m(emptyList(), listOf("x"), true), "1", listOf("r")))
        assertEquals(Mentions.Why.OfRole("x"), Mentions.why(m(emptyList(), listOf("x")), "1", emptyList()))
    }

    @Test fun asksForWhatWasChosen() {
        assertEquals("/users/@me/mentions?limit=25", Mentions.path(Mentions.View(), null))
        assertEquals("/users/@me/mentions?before=9&limit=25", Mentions.path(Mentions.View(), "9"))
        assertEquals("/users/@me/mentions?kinds=everyone&server_id=%40me&sort=oldest&after=9&limit=25", Mentions.path(Mentions.View("everyone", "@me", "oldest"), "9"))
    }

    @Test fun ordersSnowflakes() {
        assertTrue(Mentions.newer("100", "99"))
        assertTrue(Mentions.newer("120", "110"))
        assertTrue(!Mentions.newer("110", "110"))
    }
}
