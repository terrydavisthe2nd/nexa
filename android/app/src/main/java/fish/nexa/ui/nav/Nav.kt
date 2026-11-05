package fish.nexa.ui.nav

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fish.nexa.Nexa

/** Where the person is, as the web's addresses name it. */
sealed interface Route {
    /** `/channels/@me`: Friends. */
    data object Friends : Route
    /** `/channels/@me/<id>`: a DM, group or subchat. */
    data class Dm(val channelId: String, val jumpTo: String? = null) : Route
    /** `/channels/@me/nexa`: the official Nexa conversation. */
    data object NexaConvo : Route
    /** `/channels/@me/nexa/cases/<id>`: a moderation case's thread. */
    data class NexaCase(val caseId: String) : Route
    /** `/channels/@me/nexa/bugs/<id>`: a bug report's thread. */
    data class NexaBug(val reportId: String) : Route
    /** `/channels/@me/notes[/<id>]`. */
    data class Notes(val noteId: String? = null) : Route
    /** `/channels/<server>/<channel>`. */
    data class Server(val serverId: String, val channelId: String? = null, val jumpTo: String? = null) : Route
    /**
     * `/channels/@admin/<path>`: the staff's panel, staff only. The path is the
     * web's own below @admin: "users", "users/<id>[/cases[/<case>]]",
     * "servers[/<id>]", "expressions[/uploads|/packs/<id>]", "cases[/<id>]",
     * "bugs[/<id>]", "reports[/<id>]", "calls", "logs", "announcements", "badges".
     */
    data class Admin(val path: String = "reports") : Route {
        val parts: List<String> get() = path.split('/').filter { it.isNotEmpty() }
        val tab: String get() = parts.firstOrNull()?.takeIf { it in TABS } ?: "reports"

        companion object {
            val TABS = listOf("reports", "discovery", "users", "bots", "servers", "expressions", "calls", "cases", "bugs", "logs", "announcements", "badges")
        }
    }
    /** `/channels/@me/support`: Nexa's plans, and gifts. */
    data object Support : Route
    /** `/channels/@discover`: servers anyone can join. */
    data object Discover : Route

    val isHome get() = this is Friends || this is Dm || this is NexaConvo || this is NexaCase || this is NexaBug || this is Notes || this is Support

    /** The web address for this place (without a message to jump to). */
    fun toPath(): String = when (this) {
        Friends -> "/channels/@me"
        is Dm -> "/channels/@me/$channelId"
        NexaConvo -> "/channels/@me/nexa"
        is NexaCase -> "/channels/@me/nexa/cases/$caseId"
        is NexaBug -> "/channels/@me/nexa/bugs/$reportId"
        is Notes -> if (noteId != null) "/channels/@me/notes/$noteId" else "/channels/@me/notes"
        is Server -> if (channelId != null) "/channels/$serverId/$channelId" else "/channels/$serverId"
        is Admin -> "/channels/@admin/$path"
        Support -> "/channels/@me/support"
        Discover -> "/channels/@discover"
    }
}

/**
 * Navigation on a phone (web AppShell + backLayers): one page at a time, a
 * history to go back through, the navigation drawer on the start side and the
 * member drawer on the end side, and the rules for which taps keep the drawer
 * open (picking a server) and which close it (picking a channel).
 */
object Nav {
    var route by mutableStateOf<Route>(Route.Friends)
        private set
    var leftOpen by mutableStateOf(false)
    var rightOpen by mutableStateOf(false)

    /** A tablet or wide window (768 dp, the web's phone breakpoint): the rail and sidebar stay beside the page. */
    var wide by mutableStateOf(false)

    /** Room for the member list beside the chat too (1100 dp, the web's useIsNarrow). */
    var roomy by mutableStateOf(false)

    /** Whether the member list beside the chat is shown, when there is room for it (the web's own toggle). */
    var membersPinned by mutableStateOf(true)

    /** The member list's toggle: beside the chat when there is room, otherwise the drawer. */
    val membersShown: Boolean get() = if (roomy) membersPinned else rightOpen

    fun toggleMembers() {
        if (roomy) membersPinned = !membersPinned else rightOpen = !rightOpen
    }
    private val history = ArrayDeque<Route>()

    val canGoBack get() = history.isNotEmpty()

    /** Whether the page has a member list to open from the end side: a server's channel, or a group. */
    val hasMembers: Boolean
        get() = when (val r = route) {
            is Route.Server -> r.channelId != null
            is Route.Dm -> Nexa.channels.get(r.channelId)?.type == "group_dm"
            else -> false
        }

    /** The page going back leads to, if there is one. */
    val previous: Route? get() = history.lastOrNull()

    fun go(next: Route, keepDrawer: Boolean = false, replace: Boolean = false) {
        val target = resolve(next)
        if (target != route) {
            if (!replace) {
                history.addLast(route)
                while (history.size > 50) history.removeFirst()
            }
            route = target
        }
        rightOpen = false
        if (!keepDrawer) leftOpen = false
        publish()
    }

    /**
     * A web address inside Nexa (a notification's path, a shared link):
     * /channels/@me[/<id>[/<message>]], /channels/@me/notes[/<id>],
     * /channels/@me/nexa, /channels/<server>[/<channel>[/<message>]], /channels/@admin.
     * False when it names nothing the app opens.
     */
    fun openPath(path: String): Boolean {
        val parts = path.substringBefore('?').substringBefore('#').trim('/').split('/').filter { it.isNotEmpty() }
        // a shared note's link: what it leads to, and joining it, with the key from after its #
        if (parts.size == 2 && parts[0] == "note") {
            fish.nexa.ui.notes.openNoteLink(parts[1], path.substringAfter('#', ""))
            return true
        }
        // a login page's QR code, scanned with the phone's own camera: let that device in
        if (parts == listOf("login", "qr")) {
            fish.nexa.ui.auth.openQrApprove(path.substringAfter('#', ""))
            return true
        }
        // a plan bought as a link: what it holds, and claiming it
        if (parts.size == 2 && parts[0] == "gift") {
            fish.nexa.ui.support.openGiftLink(parts[1])
            return true
        }
        // an invite link, a profile link
        if (parts.size == 2 && parts[0] == "invite") {
            fish.nexa.ui.dialogs.Dialogs.openInvite(parts[1], path.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" })
            return true
        }
        if (parts.size == 1 && parts[0].startsWith("@") && parts[0].length > 1) {
            fish.nexa.ui.profile.Profiles.openByUsername(parts[0].substring(1))
            return true
        }
        // a link that adds an app: what it is, where it goes and what it may do, before saying yes
        if (parts == listOf("oauth2", "authorize")) {
            // over whatever is open, which it leaves the person on once they are done
            fish.nexa.ui.apps.openAuthorize(path.substringAfter('?', "").substringBefore('#'), fromApp = true)
            return true
        }
        if (parts.firstOrNull() != "channels" || parts.size < 2) return false
        val id = Regex("^\\d{1,20}$")
        val next: Route = when (parts[1]) {
            "@me" -> when {
                parts.size == 2 -> Route.Friends
                parts[2] == "support" -> Route.Support
                parts[2] == "notes" -> Route.Notes(parts.getOrNull(3)?.takeIf { id.matches(it) })
                parts[2] == "nexa" -> when {
                    parts.getOrNull(3) == "cases" && parts.getOrNull(4)?.let { id.matches(it) } == true -> Route.NexaCase(parts[4])
                    parts.getOrNull(3) == "bugs" && parts.getOrNull(4)?.let { id.matches(it) } == true -> Route.NexaBug(parts[4])
                    else -> Route.NexaConvo
                }
                id.matches(parts[2]) -> Route.Dm(parts[2], parts.getOrNull(3)?.takeIf { id.matches(it) })
                else -> return false
            }
            "@admin" -> Route.Admin(parts.drop(2).filter { Regex("^[a-z]+$|^\\d{1,20}$").matches(it) }.joinToString("/").ifEmpty { "reports" })
            "@discover" -> Route.Discover
            else -> if (id.matches(parts[1])) Route.Server(parts[1], parts.getOrNull(2)?.takeIf { id.matches(it) }, parts.getOrNull(3)?.takeIf { id.matches(it) }) else return false
        }
        go(next)
        return true
    }

    /** History back; false when there is nothing to go back to. */
    fun back(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        route = prev
        rightOpen = false
        publish()
        return true
    }

    /** Return to an existing workspace without leaving inspected pages in Back history. */
    fun returnTo(next:Route) {
        val target=resolve(next)
        if(history.contains(target)) {
            while(history.lastOrNull()!=target) history.removeLast()
            back()
        } else go(target,replace=true)
    }

    /** A server opens on its remembered text channel, else its first one (serverNav.ts). */
    private fun resolve(r: Route): Route {
        if (r is Route.Server && r.channelId == null) {
            val remembered = lastChannel(r.serverId)?.takeIf { id -> Nexa.channels.get(id)?.let { it.serverId == r.serverId && (it.isTextLike || it.isForum) } == true }
            return r.copy(channelId = remembered ?: firstTextChannel(r.serverId))
        }
        return r
    }

    fun firstTextChannel(serverId: String): String? {
        val all = Nexa.channels.byId.value.values.filter { it.serverId == serverId }
        val cats = all.filter { it.isCategory }.sortedWith(compareBy({ it.position }, { it.id.length }, { it.id })).map { it.id }.toSet()
        val loose = all.filter { (it.isText || it.isAnnouncement) && (it.parentId == null || it.parentId !in cats) }.sortedWith(compareBy({ it.position }, { it.id.length }, { it.id }))
        loose.firstOrNull()?.let { return it.id }
        for (cat in all.filter { it.isCategory }.sortedWith(compareBy({ it.position }, { it.id.length }, { it.id }))) {
            all.filter { (it.isText || it.isAnnouncement) && it.parentId == cat.id }.minWithOrNull(compareBy({ it.position }, { it.id.length }, { it.id }))?.let { return it.id }
        }
        return null
    }

    private fun prefs() = Nexa.app.getSharedPreferences("nav", Context.MODE_PRIVATE)

    fun lastChannel(serverId: String): String? = Nexa.users.meId?.let { prefs().getString("lastChannel:$it:$serverId", null) }

    fun rememberChannel(serverId: String, channelId: String) {
        val me = Nexa.users.meId ?: return
        prefs().edit().putString("lastChannel:$me:$serverId", channelId).apply()
    }

    /**
     * Opening the app after it was closed: back where you were, as a phone
     * app is expected to be, unless a notification or link said where to go.
     */
    fun restore(switched: Boolean = false) {
        // after switching accounts: what the last one had open goes, without being saved as this one's place
        if (switched) {
            history.clear()
            route = Route.Friends
            leftOpen = false
            rightOpen = false
        }
        if (route != Route.Friends || history.isNotEmpty()) return
        val me = Nexa.users.meId ?: return
        val path = prefs().getString("last:$me", null) ?: return
        val parts = path.trim('/').split('/')
        // only places that still exist
        val ok = when {
            parts.size >= 3 && parts[1] == "@me" && parts[2].all { it.isDigit() } -> Nexa.channels.get(parts[2]) != null
            parts.size >= 2 && parts[1].all { it.isDigit() } -> Nexa.servers.get(parts[1]) != null
            else -> true
        }
        if (ok) openPath(path)
        history.clear()
    }

    private fun publish() {
        Nexa.users.meId?.let { prefs().edit().putString("last:$it", route.toPath()).apply() }
        when (val r = route) {
            is Route.Server -> {
                Nexa.ui.open(r.serverId, r.channelId)
                if (r.channelId != null && Nexa.channels.get(r.channelId)?.let { it.isTextLike || it.isForum } == true) rememberChannel(r.serverId, r.channelId)
            }
            is Route.Dm -> Nexa.ui.open(null, r.channelId)
            else -> Nexa.ui.open(null, null)
        }
    }

    fun reset() {
        history.clear()
        route = Route.Friends
        leftOpen = false
        rightOpen = false
        publish()
    }
}
