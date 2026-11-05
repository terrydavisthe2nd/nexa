package fish.nexa.ui.shell

import fish.nexa.ui.collectEntryAsState
import fish.nexa.data.StaffRanks
import fish.nexa.ui.profile.copyBotIdItem
import fish.nexa.data.NexaInbox

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.DmLayout
import fish.nexa.data.Ids
import fish.nexa.net.Channel
import fish.nexa.net.PublicUser
import fish.nexa.ui.Icon
import fish.nexa.ui.components.CountBadge
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.Pick
import fish.nexa.ui.components.PickAction
import fish.nexa.ui.components.PickBar
import fish.nexa.ui.components.PickMark
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.dialogs.promptText
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.profile.AccountFlags
import fish.nexa.ui.profile.NexaOwner
import fish.nexa.ui.profile.ProfileLinks
import fish.nexa.ui.profile.StatusLine
import fish.nexa.ui.profile.confirmBlockPeople
import fish.nexa.ui.profile.confirmRemoveFriends
import fish.nexa.ui.profile.copyWithToast
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The official Nexa conversation's standing (MeUser.nexa): when it last said something, and what is unread. */
/** closeNexa (DMSidebar.tsx): read it, and take it off the list until it says something new. */
fun closeNexa() {
    if (Nav.route.let { it is Route.NexaConvo || it is Route.NexaCase || it is Route.NexaBug }) Nav.go(Route.Friends, replace = true)
    NexaInbox.markSeen()
    val l = DmArrange.current()
    val next = DmLayout.forget(l, DmLayout.NEXA_ID)
    Nexa.settings.patch(buildJsonObject {
        put("nexaClosed", NexaInbox.latest().takeIf { it > 0 } ?: System.currentTimeMillis())
        if (next !== l) put("dmList", DmLayout.toJson(next))
    })
}

/** components/sidebar/dmArrange.ts and dmMenu.ts: changing the arrangement, and the rows' menus. */
object DmArrange {
    fun current(): DmLayout.Layout = DmLayout.read(Nexa.settings.doc.value["dmList"])

    private fun known(id: String): Boolean {
        if (id == DmLayout.NEXA_ID) return true
        val ch = Nexa.channels.get(id)
        return ch?.type == "dm" || ch?.type == "group_dm"
    }

    /** Applies a change to the layout and saves it (pruned of what is gone). False when nothing changed. */
    fun change(fn: (DmLayout.Layout) -> DmLayout.Layout): Boolean {
        if (!Nexa.settings.loaded.value) return false
        val now = current()
        val next = fn(now)
        if (next === now) return false
        Nexa.settings.patch(buildJsonObject { put("dmList", DmLayout.toJson(DmLayout.prune(next, ::known))) })
        return true
    }

    fun forget(id: String) {
        if (!Nexa.settings.loaded.value) return
        val now = current()
        val next = DmLayout.forget(now, id)
        if (next !== now) Nexa.settings.patch(buildJsonObject { put("dmList", DmLayout.toJson(next)) })
    }

    fun groupName(g: DmLayout.Group) = g.name.trim().ifEmpty { t("organize.dm.unnamedGroup") }

    fun newGroup(ids: List<String> = emptyList()) {
        promptText(t("organize.dm.newGroupTitle"), t("organize.dm.groupName"), maxLength = DmLayout.GROUP_NAME_MAX, hint = t("organize.dm.groupHint"), confirmLabel = t("organize.dm.createGroup")) { name ->
            if (name.isNotBlank()) change { DmLayout.createGroup(it, name, ids).first }
        }
    }

    /** Pin, Add to Group ▸, Remove from…, then the moves; a separator after the last of them. */
    fun arrangeItems(id: String, moves: List<MenuItem.Action>): List<MenuItem> {
        val l = current()
        val pinned = DmLayout.isPinned(l, id)
        val group = DmLayout.groupOf(l, id)
        val items = ArrayList<MenuItem>()
        items += MenuItem.Action(if (pinned) t("organize.dm.unpinConversation") else t("organize.dm.pinConversation")) {
            change { if (pinned) DmLayout.unpin(it, id) else DmLayout.pin(it, id) }
        }
        items += MenuItem.Submenu(t("organize.dm.addToGroup"), buildList {
            l.groups.forEachIndexed { i, g ->
                add(MenuItem.Action(groupName(g), checked = if (g.id == group?.id) true else null, separatorAfter = i == l.groups.lastIndex) { change { DmLayout.addToGroup(it, id, g.id) } })
            }
            add(MenuItem.Action(t("organize.dm.newGroup")) { newGroup(listOf(id)) })
        })
        if (group != null) items += MenuItem.Action(t("organize.dm.removeFromNamed", "name" to groupName(group))) { change { DmLayout.removeFromGroup(it, id) } }
        items += moves
        return withSeparatorAfterLast(items)
    }

    fun withSeparatorAfterLast(items: List<MenuItem>): List<MenuItem> {
        if (items.isEmpty()) return items
        val last = items.last()
        return items.dropLast(1) + when (last) {
            is MenuItem.Action -> last.copy(separatorAfter = true)
            is MenuItem.Submenu -> last.copy(separatorAfter = true)
            else -> last
        }
    }

    fun managesGroup(ch: Channel?): Boolean {
        val me = Nexa.users.me.value ?: return false
        if (ch == null || ch.type != "group_dm") return false
        return ch.ownerId == me.id
    }

    fun markRead(id: String) {
        if (id == DmLayout.NEXA_ID) return NexaInbox.markSeen()
        val ch = Nexa.channels.get(id) ?: return
        val last = ch.lastMessageId ?: return
        if (Unread.isUnread(Nexa.readStates.get(id), last)) Nexa.scope.launch { Nexa.session.ack(id, last) }
    }

    fun isUnread(id: String): Boolean {
        if (id == DmLayout.NEXA_ID) return NexaInbox.unread() > 0
        val ch = Nexa.channels.get(id) ?: return false
        return ch.lastMessageId != null && Unread.isUnread(Nexa.readStates.get(id), ch.lastMessageId)
    }

    fun setMuted(ids: List<String>, on: Boolean) {
        val now = Unread.mutedIds()
        val next = if (on) (now + ids.filter { it != DmLayout.NEXA_ID }).toSet() else now - ids.toSet()
        val n = Nexa.settings.notifications
        Nexa.settings.patch(buildJsonObject {
            put("notifications", JsonObject(n + ("muted" to kotlinx.serialization.json.JsonArray(next.map { JsonPrimitive(it) }))))
        })
    }
}

/** Closes a DM (hidden until a new message) or leaves a group; goes to Friends when it was open. */
fun closeDm(channelId: String, silent: Boolean = false) {
    Nexa.scope.launch {
        try {
            Nexa.api.request("DELETE", "/channels/$channelId" + if (silent) "?silent=true" else "")
            leaveIfViewing(channelId)
            Nexa.channels.remove(channelId)
            Nexa.messages.clearChannel(channelId)
            DmArrange.forget(channelId)
        } catch (e: Exception) {
            Nexa.ui.toast(fish.nexa.ui.describeError(e, t("sidebar.dmMenu.couldNotCloseThatConversation")), "error")
        }
    }
}

/** Deletes a DM for both people, or a group for everyone. */
suspend fun purgeDm(channelId: String) {
    try {
        Nexa.api.request("DELETE", "/channels/$channelId?purge=true")
        leaveIfViewing(channelId)
        Nexa.channels.remove(channelId)
        Nexa.messages.clearChannel(channelId)
        DmArrange.forget(channelId)
    } catch (e: Exception) {
        Nexa.ui.toast(fish.nexa.ui.describeError(e, t("sidebar.dmMenu.couldNotDeleteThatConversation")), "error")
    }
}

private fun leaveIfViewing(channelId: String) {
    val open = (Nav.route as? Route.Dm)?.channelId ?: return
    if (open == channelId || Nexa.channels.get(open)?.parentId == channelId) Nav.go(Route.Friends, replace = true)
}

/** dmMenuItems: a conversation row's menu, with Select after Mark as Read and its moves in the middle. */
fun dmMenu(ch: Channel, moves: List<MenuItem.Action> = emptyList(), select: Boolean = false): List<MenuItem> {
    val me = Nexa.users.me.value
    val group = ch.type == "group_dm"
    val owner = DmArrange.managesGroup(ch)
    val name = Names.channel(ch)
    val muted = ch.id in Unread.mutedIds()
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(t("sidebar.dmMenu.markAsRead"), disabled = !DmArrange.isUnread(ch.id), separatorAfter = !select) { DmArrange.markRead(ch.id) }
    if (select) items += MenuItem.Action(t("pick.select"), separatorAfter = true) { Pick.start("dms", ch.id) }
    items += DmArrange.arrangeItems(ch.id, moves)
    if (group) {
        items += MenuItem.Action(t("modals.editGroup.editGroup")) { openEditGroup(ch.id) }
        items += MenuItem.Action(t("chat.chat.invitePeople")) { openGroupInvite(ch.id) }
        items += MenuItem.Action(t("sidebar.dmMenu.addFriends")) { fish.nexa.ui.dialogs.Dialogs.createDm(groupId = ch.id) }
    } else {
        val other = ch.recipientIds.firstOrNull { it != me?.id }
        items += MenuItem.Action(t("profile.profile.profile")) { other?.let { openProfile(it) } }
        other?.let { ProfileLinks.item(it) }?.let { items += it }
        items += MenuItem.Action(t("sidebar.dmMenu.startCall")) {
            Nav.go(Route.Dm(ch.id))
            fish.nexa.voice.Calls.join(ch.id)
        }
    }
    items += MenuItem.Action(if (muted) t("sidebar.dmMenu.unmuteConversation") else t("sidebar.dmMenu.muteConversation"), separatorAfter = true) { DmArrange.setMuted(listOf(ch.id), !muted) }
    items += MenuItem.Action(if (group) t("chat.groupMemberMenu.leaveGroup") else t("sidebar.dMSidebar.closeDm"), danger = group) {
        if (group) confirmLeaveGroup(ch.id)
        else closeDm(ch.id)
    }
    if (!group || owner) {
        items += MenuItem.Action(if (group) t("sidebar.dmMenu.deleteGroup") else t("sidebar.dmMenu.deleteConversation"), danger = true) {
            confirm(
                if (group) t("sidebar.dmMenu.deleteNamed", "name" to name) else t("sidebar.dmMenu.deleteYourConversationWith", "name" to name),
                if (group) t("sidebar.dmMenu.theGroupAndEveryMessageIn") else t("sidebar.dmMenu.everyMessageIsRemovedForBoth"),
                if (group) t("sidebar.dmMenu.deleteGroup") else t("sidebar.dmMenu.deleteForEveryone"),
            ) { purgeDm(ch.id) }
        }
    }
    if (Nexa.settings.developerMode) {
        val last = items.removeAt(items.lastIndex) as MenuItem.Action
        items += last.copy(separatorAfter = true)
        val other = if (group) null else ch.recipientIds.firstOrNull { it != me?.id }
        if (other != null) {
            items += MenuItem.Action(t("chat.groupMemberMenu.copyUserId")) { copyWithToast(other, t("chat.groupMemberMenu.copied")) }
            items += copyBotIdItem(other)
        }
        items += MenuItem.Action(t("sidebar.dmMenu.copyChannelId")) { copyWithToast(ch.id, t("chat.groupMemberMenu.copied")) }
    }
    return items
}

/** Nexa's menu; without `moves` (the rail) it has no arranging. */
internal fun nexaMenu(moves: List<MenuItem.Action>?, select: Boolean): List<MenuItem> = buildList {
    add(MenuItem.Action(t("sidebar.dmMenu.markAsRead"), disabled = NexaInbox.unread() == 0, separatorAfter = !select) { NexaInbox.markSeen() })
    if (select) add(MenuItem.Action(t("pick.select"), separatorAfter = true) { Pick.start("dms", DmLayout.NEXA_ID) })
    if (moves != null) addAll(DmArrange.arrangeItems(DmLayout.NEXA_ID, moves))
    add(MenuItem.Action(t("sidebar.dMSidebar.closeDm")) { closeNexa() })
}

private fun groupMenu(g: DmLayout.Group, unread: Boolean, pinned: Boolean, moves: List<MenuItem.Action>): List<MenuItem> {
    val name = DmArrange.groupName(g)
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(t("sidebar.dmMenu.markAsRead"), disabled = !unread, separatorAfter = true) { g.ids.forEach { DmArrange.markRead(it) } }
    items += MenuItem.Action(if (g.collapsed) t("organize.dm.expandGroup") else t("organize.dm.collapseGroup")) { DmArrange.change { DmLayout.setCollapsed(it, g.id, !g.collapsed) } }
    items += MenuItem.Action(t("organize.dm.renameGroup")) {
        promptText(t("organize.dm.renameGroup"), t("organize.dm.groupName"), initial = g.name, maxLength = DmLayout.GROUP_NAME_MAX) { next ->
            if (next.isNotBlank()) DmArrange.change { DmLayout.rename(it, g.id, next) }
        }
    }
    items += MenuItem.Action(if (pinned) t("organize.dm.unpinGroup") else t("organize.dm.pinGroup")) { DmArrange.change { if (pinned) DmLayout.unpin(it, g.id) else DmLayout.pin(it, g.id) } }
    if (pinned) items += moves
    val out = DmArrange.withSeparatorAfterLast(items).toMutableList()
    out += MenuItem.Action(t("organize.dm.deleteGroup")) {
        confirm(t("organize.dm.deleteNamed", "name" to name), t("organize.dm.deleteBody"), t("organize.dm.deleteGroup"), danger = false) {
            DmArrange.change { DmLayout.deleteGroup(it, g.id) }
        }
    }
    return out
}

/** dmBulk: what the pick bar and the bulk menu do to every picked conversation. */
private object DmBulk {
    fun peopleOf(ids: List<String>): List<PublicUser> {
        val me = Nexa.users.meId
        return ids.mapNotNull { id ->
            val ch = Nexa.channels.get(id)?.takeIf { it.type == "dm" } ?: return@mapNotNull null
            ch.recipientIds.firstOrNull { it != me }?.let { Nexa.users.get(it) }
        }.distinctBy { it.id }
    }

    private fun allPinned(ids: List<String>) = DmArrange.current().let { l -> ids.all { DmLayout.isPinned(l, it) } }
    private fun allMuted(ids: List<String>) = Unread.mutedIds().let { m -> ids.all { it == DmLayout.NEXA_ID || it in m } }

    private fun pin(ids: List<String>) {
        val all = allPinned(ids)
        DmArrange.change { l -> ids.fold(l) { acc, id -> if (all) DmLayout.unpin(acc, id) else if (DmLayout.isPinned(acc, id)) acc else DmLayout.pin(acc, id) } }
    }

    fun closeAll(ids: List<String>) {
        val groups = ids.count { Nexa.channels.get(it)?.type == "group_dm" }
        val run: suspend (Boolean) -> Unit = { silent ->
            Pick.clear()
            for (id in ids) if (id == DmLayout.NEXA_ID) closeNexa() else closeDm(id, silent && Nexa.channels.get(id)?.type == "group_dm")
        }
        if (groups == 0) Nexa.scope.launch { run(false) }
        else confirmGroupLeave(t("pick.closeTitle", "count" to ids.size), t("pick.closeBody", "count" to groups), t("pick.closeConfirm")) { run(it) }
    }

    fun bar(ids: List<String>): List<PickAction> {
        val pinned = allPinned(ids)
        val muted = allMuted(ids)
        return listOf(
            PickAction("CheckCheck", t("sidebar.dmMenu.markAsRead"), enabled = ids.any { DmArrange.isUnread(it) }) { ids.forEach { DmArrange.markRead(it) } },
            PickAction(if (pinned) "PinOff" else "Pin", if (pinned) t("pick.unpin") else t("pick.pin")) { pin(ids) },
            PickAction(if (muted) "Bell" else "BellOff", if (muted) t("pick.unmute") else t("pick.mute")) { DmArrange.setMuted(ids, !muted) },
            PickAction("LogOut", t("pick.close"), danger = true) { closeAll(ids) },
            PickAction("MoreHorizontal", t("pick.more")) { openMenu(menu(ids), t("pick.count", "count" to ids.size)) },
        )
    }

    fun menu(ids: List<String>): List<MenuItem> {
        val l = DmArrange.current()
        val people = peopleOf(ids)
        val friends = people.filter { Nexa.relationships.isFriend(it.id) }
        val blockable = people.filter { !Nexa.relationships.isBlocked(it.id) }
        val muted = allMuted(ids)
        val items = ArrayList<MenuItem>()
        items += MenuItem.Action(t("sidebar.dmMenu.markAsRead"), disabled = ids.none { DmArrange.isUnread(it) }, separatorAfter = true) { ids.forEach { DmArrange.markRead(it) } }
        items += MenuItem.Action(if (allPinned(ids)) t("pick.unpin") else t("pick.pin")) { pin(ids) }
        items += MenuItem.Submenu(t("organize.dm.addToGroup"), buildList {
            l.groups.forEachIndexed { i, g ->
                add(MenuItem.Action(DmArrange.groupName(g), separatorAfter = i == l.groups.lastIndex) { DmArrange.change { lay -> ids.fold(lay) { acc, id -> DmLayout.addToGroup(acc, id, g.id) } } })
            }
            add(MenuItem.Action(t("organize.dm.newGroup")) { DmArrange.newGroup(ids) })
        })
        items += MenuItem.Action(if (muted) t("pick.unmute") else t("pick.mute"), separatorAfter = true) { DmArrange.setMuted(ids, !muted) }
        if (people.isNotEmpty()) {
            val block = ArrayList<MenuItem.Action>()
            block += MenuItem.Action(t("pick.groupChatWith", "count" to people.size)) { fish.nexa.ui.dialogs.Dialogs.createDm(initial = people.map { it.id }) }
            if (friends.isNotEmpty()) block += MenuItem.Action(t("pick.removeFriends", "count" to friends.size), danger = true) { confirmRemoveFriends(friends) }
            if (blockable.isNotEmpty()) block += MenuItem.Action(t("pick.block", "count" to blockable.size), danger = true) { confirmBlockPeople(blockable) }
            items += block.dropLast(1) + block.last().copy(separatorAfter = true)
        }
        items += MenuItem.Action(t("pick.close"), danger = true) { closeAll(ids) }
        return items
    }
}

/** A Snowflake for a moment (lib/ids.ts idAt), to sort Nexa's row among conversations by its latest notice. */
private fun idAt(ms: Long): String = if (ms <= Ids.EPOCH_MS) "0" else ((ms - Ids.EPOCH_MS) shl 22).toString()

private enum class Place { PINNED, MAIN, GROUP }

/**
 * DMSidebar.tsx on a phone: the search button, Friends, Notes (and
 * Moderation for staff), your pinned conversations and groups, then the rest
 * by their latest activity, each with its menu. "Select" picks several, and a
 * bar at the foot acts on them all.
 */
@Composable
fun DmSidebar() {
    val c = Nx.colors
    val channels by Nexa.channels.byId.collectAsState()
    val reads by Nexa.readStates.byChannel.collectAsState()
    val rels by Nexa.relationships.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val presences by Nexa.presence.byId.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val me by Nexa.users.me.collectAsState()
    // read so rows redraw when read marks, people or presence change
    @Suppress("UNUSED_VARIABLE") val touch = reads.size + users.size + presences.size
    val route = Nav.route
    val requests = rels.values.count { it.type == "incoming" }
    val layout = remember(doc) { DmLayout.read(doc["dmList"]) }
    val nexaOpen = route is Route.NexaConvo
    val nexaShown = me != null && NexaInbox.latest() > 0 && (NexaInbox.listed() || nexaOpen)
    val activeId = (route as? Route.Dm)?.channelId
    val activeConvo = activeId?.let { channels[it] }?.let { if (it.type == "subchat") it.parentId else it.id }
    val dms = channels.values.filter { it.type == "dm" || it.type == "group_dm" }
    val view = remember(dms, layout, nexaShown, me) {
        val ids = dms.map { it.id } + if (nexaShown) listOf(DmLayout.NEXA_ID) else emptyList()
        val byId = dms.associateBy { it.id }
        DmLayout.view(
            layout, ids,
            known = { id -> if (id == DmLayout.NEXA_ID) nexaShown else byId.containsKey(id) },
            activity = { id -> if (id == DmLayout.NEXA_ID) idAt(NexaInbox.latest()) else byId[id]?.lastMessageId ?: id },
            compare = Ids::compare,
        )
    }
    val restIds = view.rest.map { DmLayout.rowId(it) }
    val pinnedIds = view.pinned.map { DmLayout.rowId(it) }
    val picking = Pick.picking("dms")

    // what was picked and has since gone is let go of
    val shownOrder = buildList {
        for (r in view.pinned + view.rest) when (r) {
            is DmLayout.Row.GroupRow -> addAll(r.ids)
            is DmLayout.Row.Conversation -> add(r.id)
        }
    }
    LaunchedEffect(shownOrder, picking) { if (picking) Pick.drop(Pick.ids.filter { it !in shownOrder }) }

    fun moves(id: String, place: Place): List<MenuItem.Action> {
        val list = when (place) {
            Place.PINNED -> pinnedIds
            Place.MAIN -> restIds
            Place.GROUP -> return emptyList()
        }
        val at = list.indexOf(id)
        if (at < 0) return emptyList()
        val out = ArrayList<MenuItem.Action>()
        fun step(delta: Int) {
            if (place == Place.PINNED) DmArrange.change { DmLayout.movePinned(DmLayout.prune(it) { x -> x == DmLayout.NEXA_ID || channels.containsKey(x) }, id, delta) }
            else {
                val to = restIds.getOrNull(at + delta) ?: return
                DmArrange.change { DmLayout.moveInMain(it, id, to, if (delta > 0) "after" else "before", restIds, view.keys, view.now) }
            }
        }
        if (at > 0) out += MenuItem.Action(t("reorder.moveUp")) { step(-1) }
        if (at < list.size - 1) out += MenuItem.Action(t("reorder.moveDown")) { step(1) }
        return out
    }

    fun rowName(id: String): String = when {
        id == DmLayout.NEXA_ID -> "Nexa"
        DmLayout.isGroupId(id) -> layout.groups.firstOrNull { it.id == id }?.let { DmArrange.groupName(it) } ?: ""
        else -> channels[id]?.let { Names.channel(it) } ?: ""
    }

    fun openRowMenu(id: String, place: Place) {
        if (Pick.list == "dms" && id in Pick.ids && Pick.ids.size > 1) {
            openMenu(DmBulk.menu(Pick.ids.toList()), t("pick.count", "count" to Pick.ids.size))
            return
        }
        if (Pick.list == "dms" && !Pick.mode && Pick.ids.isNotEmpty()) Pick.clear()
        val items = if (id == DmLayout.NEXA_ID) nexaMenu(moves(id, place), select = true)
        else channels[id]?.let { dmMenu(it, moves(id, place), select = true) } ?: return
        openMenu(items, rowName(id))
    }

    fun tapRow(id: String, open: () -> Unit) {
        if (picking) Pick.toggle("dms", id)
        else {
            open()
            Nav.leftOpen = false
        }
    }

    val noteRequests = fish.nexa.notes.Notes.invites.collectAsState().value.size
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SearchButton()
            LazyColumn(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
                item("friends") { NavRow("Users", t("sidebar.dMSidebar.friends"), route is Route.Friends, requests, t("rail.rail.friendRequestsCount", "count" to requests)) { Nav.go(Route.Friends); Nav.leftOpen = false } }
                // note requests waiting for an answer, counted where the answering is done
                item("notes") { NavRow("NotebookPen", t("notes.title"), route is Route.Notes, noteRequests, t("notes.requests.count", "count" to noteRequests)) { Nav.go(Route.Notes()); Nav.leftOpen = false } }
                item("support") { SupportRow(route is Route.Support) { Nav.go(Route.Support); Nav.leftOpen = false } }
                // case threads and bug reports waiting on the staff that hold something this person has not read
                // every rank of staff opens the panel
                if (StaffRanks.of(me?.id, me?.flags ?: 0L) != null) item("mod") {
                    val waitingOnStaff = fish.nexa.ui.moderation.attentionCount()
                    NavRow("Shield", t("moderation.navLabel"), route is Route.Admin, waitingOnStaff, t("moderation.railWithCount", "count" to waitingOnStaff), onLongClick = { fish.nexa.ui.moderation.openModerationMenu() }) {
                        Nav.go(Route.Admin())
                        Nav.leftOpen = false
                    }
                }
                if (view.pinned.isNotEmpty()) item("pinned-head") { SectionHeader(pinned = true) }
                rows(view.pinned, Place.PINNED, activeId, activeConvo, nexaOpen, picking, ::openRowMenu, ::tapRow, ::moves)
                item("dm-head") { SectionHeader(pinned = false) }
                if (view.pinned.isEmpty() && view.rest.isEmpty()) item("empty") {
                    Txt(
                        t("sidebar.dMSidebar.noConversationsYetAddAFriend"),
                        style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted,
                        align = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
                rows(view.rest, Place.MAIN, activeId, activeConvo, nexaOpen, picking, ::openRowMenu, ::tapRow, ::moves)
                item("end") { Spacer(Modifier.height(if (picking) 140.dp else 8.dp)) }
            }
        }
        if (picking) {
            PickBar(t("pick.dmBar"), DmBulk.bar(Pick.ids.toList()), Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun LazyListScope.rows(
    rows: List<DmLayout.Row>,
    place: Place,
    activeId: String?,
    activeConvo: String?,
    nexaOpen: Boolean,
    picking: Boolean,
    openRowMenu: (String, Place) -> Unit,
    tapRow: (String, () -> Unit) -> Unit,
    moves: (String, Place) -> List<MenuItem.Action>,
) {
    for (r in rows) when (r) {
        is DmLayout.Row.Conversation -> item("row-${r.id}") {
            ConversationEntry(r.id, place, activeId, activeConvo, nexaOpen, picking, openRowMenu, tapRow)
        }
        is DmLayout.Row.GroupRow -> item("group-${r.group.id}") {
            GroupSection(r, place == Place.PINNED, activeId, activeConvo, nexaOpen, picking, openRowMenu, tapRow, moves)
        }
    }
}

@Composable
private fun ConversationEntry(
    id: String,
    place: Place,
    activeId: String?,
    activeConvo: String?,
    nexaOpen: Boolean,
    picking: Boolean,
    openRowMenu: (String, Place) -> Unit,
    tapRow: (String, () -> Unit) -> Unit,
) {
    if (id == DmLayout.NEXA_ID) {
        NexaRow(nexaOpen, picking, { openRowMenu(id, place) }) { tapRow(id) { Nav.go(Route.NexaConvo) } }
        return
    }
    val channels by Nexa.channels.byId.collectAsState()
    val reads by Nexa.readStates.byChannel.collectAsState()
    val ch = channels[id] ?: return
    Column {
        ConversationRow(ch, selected = activeId == ch.id, parentSelected = activeConvo == ch.id && activeId != ch.id, picking = picking, onLongPress = { openRowMenu(ch.id, place) }) {
            tapRow(ch.id) { Nav.go(Route.Dm(ch.id)) }
        }
        if (!picking) {
            val subs = channels.values.filter { it.type == "subchat" && it.parentId == ch.id && (activeConvo == ch.id || Unread.isUnread(reads[it.id], it.lastMessageId)) }.sortedBy { it.position }
            for (sc in subs) SubchatRow(sc, activeId == sc.id)
        }
    }
}

@Composable
private fun SearchButton() {
    val c = Nx.colors
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .ring(Radii.Sm, c.borderSubtle)
                .then(Modifier.background(c.bgInput, Radii.Sm))
                .pressable(interaction) { openQuickSwitcher() }
                .semantics { contentDescription = t("sidebar.dMSidebar.findOrStartAConversation") }
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon("Search", c.textMuted.copy(alpha = 0.8f), size = 15.dp)
            Txt(t("sidebar.dMSidebar.findOrStartAConversation"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun NavRow(icon: String, label: String, selected: Boolean, badge: Int, badgeLabel: String?, onLongClick: (() -> Unit)? = null, iconTint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .height(42.dp)
            .selectedRow(selected, pressed)
            .pressable(interaction, onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = if (badge > 0 && badgeLabel != null) "$label, $badgeLabel" else label }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Icon(icon, if (selected) c.textBrand else iconTint ?: c.textChannel, size = 22.dp) }
        Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(550)), color = if (selected) c.textStrong else c.textChannel, maxLines = 1, modifier = Modifier.weight(1f))
        CountBadge(badge)
    }
}

/** Support Nexa, its heart in Supporter's rose (Sidebar.module.css `.navIcon[data-support]`). */
@Composable
private fun SupportRow(selected: Boolean, onClick: () -> Unit) =
    NavRow("Heart", t("support.nav"), selected, 0, null, iconTint = Nx.colors.tierSupporter, onClick = onClick)

/** The Pinned heading, or Direct Messages with List Options and Create DM. */
@Composable
private fun SectionHeader(pinned: Boolean) {
    val c = Nx.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp, end = 0.dp, top = 18.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pinned) {
            Icon("Pin", c.textMuted, size = 12.dp)
            Txt(t("organize.pinned"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), letterSpacing = androidx.compose.ui.unit.TextUnit(0.01f, androidx.compose.ui.unit.TextUnitType.Em)), color = c.textMuted, modifier = Modifier.weight(1f))
        } else {
            Txt(t("sidebar.dMSidebar.directMessages"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, modifier = Modifier.weight(1f))
            IconButton("MoreHorizontal", t("pick.listOptions"), {
                openMenu(listOf(MenuItem.Action(t("pick.selectConversations")) { Pick.start("dms") }), t("sidebar.dMSidebar.directMessages"))
            }, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
            IconButton("Plus", t("sidebar.dMSidebar.createDm"), { openCreateDm() }, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
        }
    }
}

/** A conversation in the list: who, how they are, what is waiting; the pick circle while picking. */
@Composable
private fun ConversationRow(ch: Channel, selected: Boolean, parentSelected: Boolean, picking: Boolean, onLongPress: () -> Unit, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val picked = Pick.isPicked("dms", ch.id)
    val count = if (selected) 0 else Unread.conversationCount(ch)
    val unread = !selected && Unread.isUnread(Nexa.readStates.get(ch.id), ch.lastMessageId)
    val dm = ch.type == "dm"
    val other = if (dm) Names.otherOf(ch) else null
    val name = Names.channel(ch)
    val countLabel = if (count > 99) t("rail.rail.moreThan99Unread") else if (count > 0) t("rail.rail.unreadMessagesCount", "count" to count) else null
    Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        if (unread && !picked) UnreadEdge(Modifier.align(Alignment.CenterStart))
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .then(
                    when {
                        picked -> Modifier.ring(Radii.Sm, c.brand.copy(alpha = 0.3f)).background(c.brand.copy(alpha = 0.14f), Radii.Sm)
                        parentSelected -> Modifier.background(c.bgHover, Radii.Sm)
                        else -> Modifier.selectedRow(selected, pressed)
                    },
                )
                .pressable(interaction, onLongClick = onLongPress, onClick = onClick)
                .semantics { contentDescription = listOfNotNull(name, countLabel).joinToString(", ") }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (picking) PickMark(picked, t("pick.pickNamed", "name" to name))
            ConversationAvatar(ch, 32.dp, status = other?.let { Nexa.presence.statusOf(it) }, showTyping = true)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fish.nexa.ui.chat.RoleName(
                        name, fish.nexa.ui.chat.RoleLooks.withOwn(null, other?.let { Nexa.users.byId.collectAsState().value[it]?.nameStyle }),
                        style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(550), lineHeight = Nx.type.sp(19.4f)),
                        fallback = if (selected || unread || picked) c.textStrong else c.textChannel,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (other != null) {
                        OwnerMarks(other)
                        // a conversation with an app's bot is marked as one
                        if (Nexa.users.get(other)?.isBot == true) fish.nexa.ui.media.AppTag()
                        Nexa.users.get(other)?.serverTag?.takeIf { it.text.isNotEmpty() }?.let { fish.nexa.ui.chat.ServerTagChip(it, small = true) }
                    }
                }
                if (dm && other != null) StatusLine(other, Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), c.textMuted)
                else if (!dm) Txt(t("chat.groupMemberList.membersOnly", "count" to ch.recipientIds.size), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
            }
            if (count > 0) CountBadge(count)
        }
    }
}

/** The mark of a member of Nexa's staff beside their name in a list: the owner's crown, a developer's brackets, an admin's hammer, a mod's shield. */
@Composable
fun OwnerMarks(userId: String) {
    val c = Nx.colors
    val user by Nexa.users.byId.collectEntryAsState(userId)
    val me by Nexa.users.me.collectAsState()
    val flags = if (me?.id == userId) me?.flags else user?.flags
    val rank = StaffRanks.of(userId, flags ?: 0L) ?: return
    Icon(rank.icon, c.textBrand, size = 14.dp, modifier = Modifier.semantics { contentDescription = rank.label })
}

@Composable
private fun NexaRow(open: Boolean, picking: Boolean, onLongPress: () -> Unit, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val picked = Pick.isPicked("dms", DmLayout.NEXA_ID)
    val count = if (open) 0 else NexaInbox.unread()
    Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        if (count > 0 && !picked) UnreadEdge(Modifier.align(Alignment.CenterStart))
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .then(if (picked) Modifier.ring(Radii.Sm, c.brand.copy(alpha = 0.3f)).background(c.brand.copy(alpha = 0.14f), Radii.Sm) else Modifier.selectedRow(open, pressed))
                .pressable(interaction, onLongClick = onLongPress, onClick = onClick)
                .semantics { contentDescription = if (count > 0) "Nexa, " + t("nexa.unreadCount", "count" to count) else "Nexa" }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (picking) PickMark(picked, t("pick.pickNamed", "name" to "Nexa"))
            fish.nexa.ui.profile.NexaAvatar(32.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Nexa", style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(550)), color = if (open || count > 0) c.textStrong else c.textChannel, maxLines = 1)
                    fish.nexa.ui.profile.OfficialBadge(compact = true)
                }
                Txt(t("nexa.sidebarSub"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
            }
            if (count > 0) CountBadge(count)
        }
    }
}

/** A subchat of the open conversation, listed under its row. */
@Composable
private fun SubchatRow(sc: Channel, selected: Boolean) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reads by Nexa.readStates.byChannel.collectAsState()
    val read = reads[sc.id]
    val count = if (Unread.isUnread(read, sc.lastMessageId)) maxOf(read?.unread ?: 0, read?.mentions ?: 0) else 0
    val unread = !selected && Unread.isUnread(read, sc.lastMessageId)
    Row(
        Modifier
            .padding(start = 16.dp, bottom = 2.dp)
            .fillMaxWidth()
            .height(32.dp)
            .clip(Radii.Sm)
            .background(if (selected) c.bgActive else if (pressed) c.bgHover else Color.Transparent)
            .pressable(interaction, onLongClick = { openMenu(subchatMenu(sc), sc.name) }) {
                Nav.go(Route.Dm(sc.id))
                Nav.leftOpen = false
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (sc.locked) "Lock" else "MessageCircle", if (selected || unread) c.textStrong else c.textMuted, size = 15.dp)
        Txt(
            sc.name,
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = if (unread) FontWeight(600) else FontWeight(400)),
            color = if (selected || unread) c.textStrong else c.textMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) CountBadge(count)
    }
}

/** subchatMenuItems: edit it, delete it for everyone, and its id for developers. */
private fun subchatMenu(sc: Channel): List<MenuItem> {
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(t("chat.subchat.editSubchat")) { fish.nexa.ui.dialogs.Dialogs.subchat(sc.parentId ?: sc.id, sc.id) }
    items += MenuItem.Action(t("chat.subchat.deleteSubchat"), danger = true) {
        confirm(t("chat.subchat.deleteNamed", "name" to sc.name), t("chat.subchat.theSubchatAndEveryMessageIn"), t("chat.subchat.deleteSubchat")) {
            try {
                Nexa.api.request("DELETE", "/channels/${sc.id}")
                Nexa.channels.remove(sc.id)
                sc.parentId?.let { Nav.go(Route.Dm(it), replace = true) }
            } catch (e: Exception) {
                Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("chat.subchat.couldNotDeleteThatSubchat"), "error")
            }
        }
    }
    if (Nexa.settings.developerMode) {
        val last = items.removeAt(items.lastIndex) as MenuItem.Action
        items += last.copy(separatorAfter = true)
        items += MenuItem.Action(t("sidebar.dmMenu.copyChannelId")) { copyWithToast(sc.id, t("chat.groupMemberMenu.copied")) }
    }
    return items
}

/** One of your own groups: a sleeve round its name and what is in it, folding away with its chevron. */
@Composable
private fun GroupSection(
    row: DmLayout.Row.GroupRow,
    pinned: Boolean,
    activeId: String?,
    activeConvo: String?,
    nexaOpen: Boolean,
    picking: Boolean,
    openRowMenu: (String, Place) -> Unit,
    tapRow: (String, () -> Unit) -> Unit,
    moves: (String, Place) -> List<MenuItem.Action>,
) {
    val c = Nx.colors
    val g = row.group
    val name = DmArrange.groupName(g)
    val collapsed = g.collapsed
    var count = 0
    var anyUnread = false
    for (id in row.ids) {
        if (id == DmLayout.NEXA_ID) {
            if (!nexaOpen) {
                count += NexaInbox.unread(); if (NexaInbox.unread() > 0) anyUnread = true
            }
            continue
        }
        val ch = Nexa.channels.get(id) ?: continue
        if (activeId == id) continue
        count += Unread.conversationCount(ch)
        if (Unread.isUnread(Nexa.readStates.get(id), ch.lastMessageId)) anyUnread = true
    }
    val shown = if (collapsed) row.ids.filter { it == activeConvo || (it == DmLayout.NEXA_ID && nexaOpen) || Pick.isPicked("dms", it) } else row.ids
    val menu = { openMenu(groupMenu(g, anyUnread, pinned, moves(g.id, if (pinned) Place.PINNED else Place.MAIN)), name) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val chevron by animateFloatAsState(if (collapsed) (if (rtl) 90f else -90f) else 0f, tween(Nx.motion.fast), label = "chev")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp)
            .ring(Radii.Md, c.borderSubtle)
            .background(c.bgHover, Radii.Md)
            .padding(bottom = 2.dp)
            .semantics { contentDescription = name },
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (collapsed && anyUnread) UnreadEdge(Modifier.align(Alignment.CenterStart))
            Row(Modifier.fillMaxWidth().height(44.dp).padding(end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(Radii.Sm)
                        .pressable(remember { MutableInteractionSource() }, onLongClick = menu) { DmArrange.change { DmLayout.setCollapsed(it, g.id, !collapsed) } }
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon("ChevronDown", if (collapsed && anyUnread) c.textStrong else c.textChannel, size = 14.dp, modifier = Modifier.graphicsLayer { rotationZ = chevron })
                    Txt(name, style = Nx.type.label.copy(fontSize = Nx.type.sp(13f)), color = if (collapsed && anyUnread) c.textStrong else c.textChannel, maxLines = 1)
                }
                if (collapsed && count > 0) CountBadge(count)
                IconButton("MoreHorizontal", t("organize.dm.groupOptions", "name" to name), menu, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
            }
        }
        for (id in shown) ConversationEntry(id, Place.GROUP, activeId, activeConvo, nexaOpen, picking, openRowMenu, tapRow)
        if (!collapsed && row.ids.isEmpty()) Txt(
            t("organize.dm.emptyGroup"),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)),
            color = c.textMuted,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
    }
}
