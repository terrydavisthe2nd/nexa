package fish.nexa.ui.chat

import fish.nexa.ui.collectEntryAsState
import fish.nexa.ui.profile.copyBotIdItem
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.data.People
import fish.nexa.data.Perms
import fish.nexa.net.Channel
import fish.nexa.net.Member
import fish.nexa.net.PublicUser
import fish.nexa.net.wireJson
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.Pick
import fish.nexa.ui.components.PickAction
import fish.nexa.ui.components.PickBar
import fish.nexa.ui.components.PickMark
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.dialogs.Dialogs
import fish.nexa.ui.dialogs.PromptField
import fish.nexa.ui.dialogs.promptFields
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.people.MemberActions
import fish.nexa.ui.profile.NexaOwner
import fish.nexa.ui.profile.ProfileLinks
import fish.nexa.ui.profile.Profiles
import fish.nexa.ui.profile.StatusLine
import fish.nexa.ui.profile.confirmBlockPeople
import fish.nexa.ui.profile.copyWithToast
import fish.nexa.ui.profile.dmOpenError
import fish.nexa.ui.profile.relationshipMenuItems
import fish.nexa.ui.shell.DmArrange
import fish.nexa.ui.shell.Names
import fish.nexa.ui.shell.closeDm
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.rgbColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** nameOrder: names sorted as people read them in the app's language. */
private fun nameOrder(): Comparator<String> {
    val collator = java.text.Collator.getInstance(I18n.locale).apply { strength = java.text.Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

private fun offline(status: String?) = status == null || status == "offline" || status == "invisible"

/** Puts "@username " into the open conversation's composer, and makes way for it. */
private fun mention(username: String) {
    val channelId = when (val r = Nav.route) {
        is Route.Server -> r.channelId
        is Route.Dm -> r.channelId
        else -> null
    } ?: return
    ChatUi.inserts[channelId] = "@$username "
    Nav.rightOpen = false
}

// ---------------------------------------------------------------------------
// Rows

private sealed interface MemberRow {
    val key: String

    data class Head(override val key: String, val label: String, val count: Int) : MemberRow
    data class Person(override val key: String, val userId: String, val name: String, val user: PublicUser?, val avatarId: String?, val look: RoleLook?, val offline: Boolean, val crown: Crown) : MemberRow
}

/** A server's or group's owner's gold crown after a name; Nexa's staff carry their rank's mark (OwnerMarks) instead. */
private enum class Crown { NONE, OWNER }

private fun crownOf(userId: String, ownerId: String?): Crown = if (ownerId == userId && !NexaOwner.isOwner(userId)) Crown.OWNER else Crown.NONE

@Composable
private fun GroupHead(label: String, count: Int) {
    Txt(
        t("chat.memberList.roleGroupCount", "label" to label, "count" to count),
        style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650), letterSpacing = 0.01.em),
        color = Nx.colors.textMuted,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp, end = 8.dp, top = 18.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** MemberList.module.css .row: 46 high, the picture (dimmed while offline), the name in its role's colour, the status under it. */
@Composable
private fun PersonRow(row: MemberRow.Person, list: String, ownerLabel: String, onTap: () -> Unit, onLongPress: () -> Unit, typing: Boolean? = null) {
    val c = Nx.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val picking = Pick.picking(list)
    val picked = Pick.isPicked(list, row.userId)
    val presence by Nexa.presence.byId.collectEntryAsState(row.userId)
    val status = presence?.status ?: "offline"
    Row(
        Modifier
            .padding(vertical = 1.dp)
            .fillMaxWidth()
            .height(46.dp)
            .clip(Radii.Sm)
            .background(
                when {
                    picked -> c.brand.copy(alpha = 0.14f)
                    pressed -> c.bgSelected
                    else -> Color.Transparent
                },
            )
            .pressable(interaction, onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            }) {
                if (picking) Pick.toggle(list, row.userId) else onTap()
            }
            .semantics { if (picking) selected = picked }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (picking) PickMark(picked, t("pick.pickNamed", "name" to row.name))
        Avatar(row.userId, row.name, row.avatarId, 32.dp, Modifier.alpha(if (row.offline && !pressed) 0.5f else 1f), status = status, typing = typing)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // in their roles' colours: one, a gradient or a shimmer, and their highest role's icon
                RoleName(
                    row.name,
                    row.look,
                    Nx.type.name.copy(fontFamily = Nx.type.text, fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(18.6f), fontWeight = FontWeight(550)),
                    if (pressed) c.text else c.textChannel,
                    Modifier.weight(1f, fill = false),
                )
                row.look?.icon?.let { RoleIcon(it, 16.dp) }
                if (row.crown == Crown.OWNER) Icon("Crown", c.warning, size = 14.dp, label = ownerLabel)
                fish.nexa.ui.shell.OwnerMarks(row.userId)
                // an app's bot is marked as one wherever it is listed
                if (row.user?.isBot == true) fish.nexa.ui.media.AppTag()
                row.user?.serverTag?.let { ServerTagChip(it, small = true) }
            }
            StatusLine(
                row.userId,
                Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), lineHeight = Nx.type.sp(15f)),
                if (row.offline) c.textChannel else c.textMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// A server's members

/**
 * MemberList: the members who can see the channel, under their highest
 * hoisted role, then Online, then Offline, each list in name order. A big
 * server's members arrive a page at a time.
 */
@Composable
fun MemberList(serverId: String, channelId: String?) {
    val servers by Nexa.servers.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val presence by Nexa.presence.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val st = servers[serverId] ?: return
    val meId = Nexa.users.meId
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    // a large server comes without its members: they are paged in once
    LaunchedEffect(serverId, st.membersComplete, attempt) {
        if (st.membersComplete) return@LaunchedEffect
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                var after: String? = null
                for (i in 0 until 20) {
                    val q = "limit=1000" + (after?.let { "&after=$it" } ?: "")
                    val res = Nexa.api.get("/servers/$serverId/members?$q") as JsonObject
                    val members = wireJson.decodeFromJsonElement(ListSerializer(Member.serializer()), res["members"] ?: JsonArray(emptyList()))
                    val people = wireJson.decodeFromJsonElement(ListSerializer(PublicUser.serializer()), res["users"] ?: JsonArray(emptyList()))
                    Nexa.users.upsert(people)
                    Nexa.servers.addMembers(serverId, members, members.size < 1000)
                    if (members.size < 1000) break
                    after = members.last().userId
                }
            }
            failed = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // tried again a little later, rather than left short for good
            failed = true
        }
    }
    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        delay(15_000)
        attempt++
    }

    val overwrites = channelId?.let { channels[it]?.overwrites }
    val you = t("callScreen.you")
    val unknown = t("names.unknownUser")
    val locale = I18n.locale
    val meFlags = Nexa.users.me.collectAsState().value?.flags ?: 0L
    val sorted by androidx.compose.runtime.key(serverId, channelId) {
        androidx.compose.runtime.produceState<List<Pair<Member, String>>>(
            emptyList(), st, users, overwrites, meId, meFlags, you, unknown, locale,
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                fish.nexa.data.MemberRoster.sorted(st, users, overwrites, channelId != null, meId, meFlags, you, unknown, locale)
            }
        }
    }
    val rows = remember(st, sorted, presence) {
        val hoisted = Nexa.servers.rolesByPosition(st).filter { it.hoist && it.id != serverId }
        val groups = LinkedHashMap<String, Pair<String, MutableList<Pair<Member, String>>>>()
        for (r in hoisted) groups[r.id] = r.name to ArrayList()
        val online = ArrayList<Pair<Member, String>>()
        val off = ArrayList<Pair<Member, String>>()
        for (pair in sorted) {
            val m = pair.first
            if (offline(presence[m.userId]?.status)) {
                off += pair
                continue
            }
            var top: fish.nexa.net.Role? = null
            for (id in m.roleIds) {
                val r = st.roles[id] ?: continue
                if (r.hoist && (top == null || r.position > top.position)) top = r
            }
            val g = top?.let { groups[it.id] }
            if (g != null) g.second += pair else online += pair
        }
        val out = ArrayList<MemberRow>()
        fun push(key: String, label: String, list: List<Pair<Member, String>>, isOff: Boolean) {
            if (list.isEmpty()) return
            out += MemberRow.Head("h:$key", label, list.size)
            for ((m, name) in list) out += MemberRow.Person(
                m.userId, m.userId, name, users[m.userId], m.avatarId ?: users[m.userId]?.avatarId,
                RoleLooks.withOwn(RoleLooks.of(st, m.userId), users[m.userId]?.nameStyle), isOff, crownOf(m.userId, st.server.ownerId),
            )
        }
        for ((id, g) in groups) push(id, g.first, g.second, false)
        push("online", t("chat.memberList.online"), online, false)
        push("offline", t("chat.memberList.offline"), off, true)
        out
    }

    val list = "members:$serverId"
    val ownerLabel = t("chat.memberList.serverOwner")
    // whoever types in the channel open beside the list shows it on their dot
    val typers = if (channelId != null) fish.nexa.ui.shell.typersIn(channelId) else emptyList()
    val typingOn = channelId != null && fish.nexa.ui.shell.settingOn("typingAvatars")
    Box(Modifier.fillMaxSize().semantics { contentDescription = t("chat.chat.memberList") }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = if (Pick.picking(list)) 120.dp else 16.dp)) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is MemberRow.Head -> GroupHead(row.label, row.count)
                    is MemberRow.Person -> PersonRow(
                        row, list, ownerLabel,
                        onTap = { Profiles.open(row.userId, serverId) },
                        onLongPress = { openMemberMenu(serverId, row.userId, row.name, list) },
                        typing = if (typingOn) row.userId in typers else null,
                    )
                }
            }
        }
        if (Pick.picking(list)) {
            val ids = Pick.ids.toList()
            PickBar(t("pick.membersBar"), memberBulk(serverId, ids), Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Whether you outrank a member (or are that member): who you may moderate. */
fun outranks(serverId: String, userId: String): Boolean {
    val st = Nexa.servers.get(serverId) ?: return false
    val me = Nexa.users.meId ?: return false
    if (userId == me) return true
    val actor = Nexa.servers.memberHighest(st, me)
    val target = Nexa.servers.memberHighest(st, userId)
    return Perms.canActOn(actor.first, target.first, actor.second, target.second)
}

/** Time Out, Kick and Ban, each where you may and outrank the member; nothing for yourself (memberModerationItems). */
fun memberModerationItems(serverId: String, userId: String): List<MenuItem.Action> {
    val st = Nexa.servers.get(serverId) ?: return emptyList()
    if (userId == Nexa.users.meId || !outranks(serverId, userId)) return emptyList()
    val until = st.members[userId]?.timeoutUntil
    val timedOut = until != null && until > System.currentTimeMillis()
    val mod = ArrayList<MenuItem.Action>()
    if (Nexa.servers.canI(st, P.MODERATE_MEMBERS)) mod += MenuItem.Action(if (timedOut) t("server.memberAction.removeTimeout") else t("server.members.timeout"), danger = true) { MemberActions.open(serverId, userId, MemberActions.Kind.TIMEOUT) }
    if (Nexa.servers.canI(st, P.KICK_MEMBERS)) mod += MenuItem.Action(t("server.members.kick"), danger = true) { MemberActions.open(serverId, userId, MemberActions.Kind.KICK) }
    if (Nexa.servers.canI(st, P.BAN_MEMBERS)) mod += MenuItem.Action(t("server.members.ban"), danger = true) { MemberActions.open(serverId, userId, MemberActions.Kind.BAN) }
    return mod
}

/** A menu item with the line under it that ends its group. */
private fun MenuItem.withSeparator(): MenuItem = when (this) {
    is MenuItem.Action -> copy(separatorAfter = true)
    is MenuItem.Submenu -> copy(separatorAfter = true)
    else -> this
}

/** memberMenuItems: profile, mention; friends and blocking; nickname, roles; timeout, kick, ban; the ID. */
fun memberMenuItems(serverId: String, userId: String): List<MenuItem.Action> {
    val st = Nexa.servers.get(serverId) ?: return emptyList()
    val me = Nexa.users.me.value ?: return emptyList()
    val self = userId == me.id
    val above = outranks(serverId, userId)
    val username = Nexa.users.get(userId)?.username ?: if (self) me.username else null
    val items = ArrayList<MenuItem.Action>()
    // the order people know from other chat apps: who they are, friendship and blocking,
    // moderation, roles, then the copy items (Apps goes in after the first group)
    items += MenuItem.Action(t("profile.profile.profile")) { Profiles.openFull(userId) }
    items += MenuItem.Action(t("settings.notifications.mention"), disabled = username.isNullOrEmpty(), separatorAfter = true) { username?.let(::mention) }
    // being friends, and blocking: the same choices a profile offers
    val people = relationshipMenuItems(userId)
    if (people.isNotEmpty()) {
        items += people.dropLast(1)
        items += people.last().copy(separatorAfter = true)
    }
    // one's own server profile is designed in Settings, Profiles; someone else's nickname here
    val canNick = self || Nexa.servers.canI(st, P.MANAGE_NICKNAMES) && above
    if (canNick) items += MenuItem.Action(if (self) t("sidebar.serverMenu.editServerProfile") else t("server.memberMenu.changeTheirNickname")) {
        if (self) fish.nexa.ui.shell.openServerProfile(serverId) else Dialogs.serverProfile(serverId, userId)
    }
    items += memberModerationItems(serverId, userId)
    items[items.lastIndex] = items.last().copy(separatorAfter = true)
    if (Nexa.servers.canI(st, P.MANAGE_ROLES) && above) items += MenuItem.Action(t("server.members.roles"), separatorAfter = true) { MemberActions.open(serverId, userId, MemberActions.Kind.ROLES) }
    copyItems(items, userId)
    return items
}

private fun openMemberMenu(serverId: String, userId: String, name: String, list: String) {
    // a long press on one of several picked acts on all of them
    if (Pick.isPicked(list, userId) && Pick.ids.size > 1) {
        openMenu(memberBulkMenu(serverId, Pick.ids.toList()), t("pick.count", "count" to Pick.ids.size))
        return
    }
    if (Pick.list == list && !Pick.mode && Pick.ids.isNotEmpty()) Pick.clear()
    val items = ArrayList<MenuItem>(memberMenuItems(serverId, userId))
    if (items.isEmpty()) return
    // what apps can do with someone, used in the conversation open in this server
    val here = Nexa.ui.activeChannel.value?.takeIf { Nexa.channels.get(it)?.serverId == serverId }
    withAppsUpFront(items, fish.nexa.ui.apps.userAppsItem(here, userId))
    items[items.lastIndex] = items.last().withSeparator()
    items += MenuItem.Action(t("pick.select")) { Pick.start(list, userId) }
    openMenu(items, name, titleWords = fish.nexa.ui.chat.PersonWords(fish.nexa.ui.chat.PERSON_SLOT, listOf(userId to name)))
}

// ---------------------------------------------------------------------------
// Several members at once (server/memberBulk.ts)

private fun usersOf(ids: List<String>): List<PublicUser> = ids.mapNotNull { Nexa.users.get(it) }

/** Runs one change for each person in turn, then says once how it went. */
private suspend fun each(ids: List<String>, job: suspend (String) -> Unit, done: (Int) -> String) {
    var ok = 0
    var first: Exception? = null
    for (id in ids) {
        try {
            job(id)
            ok++
        } catch (e: Exception) {
            if (first == null) first = e
        }
    }
    if (ok > 0) Nexa.ui.toast(done(ok), "success")
    first?.let { Nexa.ui.toast(describeError(it, t("friends.couldNotUpdate")), "error") }
}

/** Kicks or bans several members after one question, with one reason for them all. */
private fun removeAll(serverId: String, ids: List<String>, ban: Boolean) {
    val names = Names.list(usersOf(ids).map { Names.display(it) })
    promptFields(
        title = if (ban) t("pick.banTitle", "count" to ids.size) else t("pick.kickTitle", "count" to ids.size),
        fields = listOf(PromptField(key = "reason", label = t("server.memberAction.reasonOptional"), maxLength = 512, hint = names, hintWords = fish.nexa.ui.chat.PersonWords(Names.list(usersOf(ids).indices.map { fish.nexa.ui.chat.personSlot(it) }), usersOf(ids).map { it.id to Names.display(it) }, serverId))),
        confirmLabel = if (ban) t("pick.ban", "count" to ids.size) else t("pick.kick", "count" to ids.size),
        allowEmpty = true,
    ) { values ->
        val reason = values["reason"]?.trim()?.ifEmpty { null }
        Pick.clear()
        Nexa.scope.launch {
            each(ids, { id ->
                if (ban) MemberActions.ban(serverId, id, reason, 0) else MemberActions.kick(serverId, id, reason)
            }) { count -> if (ban) t("pick.banned", "count" to count) else t("pick.kicked", "count" to count) }
        }
    }
}

private class Bulk(val bar: List<PickAction>, val menu: List<MenuItem>)

private fun memberBulkOf(serverId: String, ids: List<String>): Bulk {
    val st = Nexa.servers.get(serverId)
    val me = Nexa.users.meId
    val others = ids.filter { it != me }
    // the ones you may act on: below you, and never the owner
    val below = others.filter { outranks(serverId, it) }
    val blockable = usersOf(others).filter { Nexa.relationships.typeOf(it.id) != "blocked" }
    val bar = ArrayList<PickAction>()
    val menu = ArrayList<MenuItem>()
    if (st != null && Nexa.servers.canI(st, P.MANAGE_ROLES) && below.isNotEmpty()) {
        val mine = if (me != null) Nexa.servers.memberHighest(st, me) else 0 to false
        val roles = Nexa.servers.rolesByPosition(st).filter { it.id != serverId && Perms.canManageRole(mine.first, mine.second, it.position) }
        fun give(roleId: String, on: Boolean) = Nexa.scope.launch {
            each(below.filter { (Nexa.servers.get(serverId)?.members?.get(it)?.roleIds?.contains(roleId) == true) != on }, { id ->
                MemberActions.setRole(serverId, id, roleId, on)
            }) { count -> if (on) t("pick.roleGiven", "count" to count) else t("pick.roleTaken", "count" to count) }
        }
        if (roles.isNotEmpty()) {
            menu += MenuItem.Submenu(t("pick.addRole"), roles.map { r -> MenuItem.Action(r.name) { give(r.id, true) } })
            menu += MenuItem.Submenu(t("pick.removeRole"), roles.map { r -> MenuItem.Action(r.name) { give(r.id, false) } }, separatorAfter = true)
        }
    }
    if (st != null && below.isNotEmpty() && Nexa.servers.canI(st, P.KICK_MEMBERS)) {
        val label = t("pick.kick", "count" to below.size)
        bar += PickAction("DoorOpen", label, danger = true) { removeAll(serverId, below, false) }
        menu += MenuItem.Action(label, danger = true) { removeAll(serverId, below, false) }
    }
    if (st != null && below.isNotEmpty() && Nexa.servers.canI(st, P.BAN_MEMBERS)) {
        val label = t("pick.ban", "count" to below.size)
        bar += PickAction("Ban", label, danger = true) { removeAll(serverId, below, true) }
        menu += MenuItem.Action(label, danger = true) { removeAll(serverId, below, true) }
    }
    if (blockable.isNotEmpty()) {
        val label = t("pick.block", "count" to blockable.size)
        if (menu.isNotEmpty()) (menu.last() as? MenuItem.Action)?.let { menu[menu.lastIndex] = it.copy(separatorAfter = true) }
        menu += MenuItem.Action(label, danger = true) { confirmBlockPeople(blockable) }
        if (bar.size < 3) bar += PickAction("ShieldOff", label, danger = true) { confirmBlockPeople(blockable) }
    }
    return Bulk(bar.take(3), menu)
}

private fun memberBulkMenu(serverId: String, ids: List<String>): List<MenuItem> = memberBulkOf(serverId, ids).menu

/** The pick bar's actions, with the rest behind More. */
private fun memberBulk(serverId: String, ids: List<String>): List<PickAction> {
    val b = memberBulkOf(serverId, ids)
    val bar = ArrayList(b.bar.map { if (ids.isEmpty()) it.copy(enabled = false) else it })
    if (b.menu.isNotEmpty()) bar += PickAction("MoreHorizontal", t("pick.more"), enabled = ids.isNotEmpty()) { openMenu(b.menu, t("pick.count", "count" to ids.size)) }
    return bar
}

// ---------------------------------------------------------------------------
// A group's members

/**
 * GroupMemberList: drawn as a server's member list, online first, the owner
 * crowned; a long press for what you may do to each, which for the owner
 * includes handing the group on and removing people.
 */
@Composable
fun GroupMemberList(channel: Channel) {
    val users by Nexa.users.byId.collectAsState()
    val presence by Nexa.presence.byId.collectAsState()
    val rels by Nexa.relationships.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val ch = channels[channel.id] ?: channel
    val me by Nexa.users.me.collectAsState()
    val unknown = t("names.unknownUser")
    val rows = remember(ch.recipientIds, presence, users, rels, me) {
        val order = nameOrder()
        ch.recipientIds.map { id ->
            val nick = rels[id]?.takeIf { it.type == "friend" }?.nickname?.ifBlank { null }
            val name = nick ?: users[id]?.let { Names.display(it) } ?: if (id == me?.id) me?.displayName?.ifBlank { me?.username } ?: unknown else unknown
            MemberRow.Person(id, id, name, users[id], users[id]?.avatarId ?: if (id == me?.id) me?.avatarId else null, RoleLooks.withOwn(null, users[id]?.nameStyle ?: if (id == me?.id) me?.nameStyle else null), offline(presence[id]?.status), crownOf(id, ch.ownerId))
        }.sortedWith { a, b -> if (a.offline != b.offline) (if (a.offline) 1 else -1) else order.compare(a.name, b.name) }
    }
    val list = "members:${ch.id}"
    val ownerLabel = t("chat.groupMemberList.groupOwner")
    // whoever types in the group shows it on their dot
    val typers = fish.nexa.ui.shell.typersIn(ch.id)
    val typingOn = fish.nexa.ui.shell.settingOn("typingAvatars")
    Box(Modifier.fillMaxSize().semantics { contentDescription = t("chat.chat.memberList") }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = if (Pick.picking(list)) 120.dp else 16.dp)) {
            item(key = "head") {
                Txt(
                    t("chat.groupMemberList.membersCount", "count" to rows.size),
                    style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650), letterSpacing = 0.01.em),
                    color = Nx.colors.textMuted,
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp, end = 8.dp, top = 18.dp, bottom = 4.dp).semantics { heading() },
                )
            }
            items(rows, key = { it.key }) { row ->
                PersonRow(
                    row, list, ownerLabel,
                    onTap = { Profiles.open(row.userId) },
                    onLongPress = { openGroupMemberMenu(ch.id, row.userId, row.name, list) },
                    typing = if (typingOn) row.userId in typers else null,
                )
            }
        }
        if (Pick.picking(list)) {
            val ids = Pick.ids.toList()
            val bulk = groupMemberBulk(ch.id, ids)
            if (bulk.bar.isNotEmpty()) PickBar(t("pick.membersBar"), bulk.bar.map { if (ids.isEmpty()) it.copy(enabled = false) else it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Opens (or starts) the one-to-one conversation with someone. */
private fun messageUser(userId: String) {
    Nexa.scope.launch {
        try {
            val dm = People.openDm(userId)
            Nav.go(Route.Dm(dm.id))
        } catch (e: Exception) {
            Nexa.ui.toast(dmOpenError(e, t("chat.groupMemberMenu.couldNotOpenTheConversation")), "error")
        }
    }
}

/** groupMemberMenuItems: profile, mention, message; friends and blocking; ownership and removal for whoever manages the group; leaving. */
fun groupMemberMenuItems(channelId: String, userId: String): List<MenuItem.Action> {
    val ch = Nexa.channels.get(channelId) ?: return emptyList()
    val me = Nexa.users.me.value ?: return emptyList()
    // a one-to-one conversation has the same person menu, without the group's choices
    if (ch.type != "group_dm" && ch.type != "dm") return emptyList()
    fun nameOf(id: String) = Nexa.relationships.nickname(id) ?: Nexa.users.get(id)?.let { Names.display(it) }
    val self = userId == me.id
    val user = Nexa.users.get(userId)
    val name = nameOf(userId) ?: if (self) me.displayName.ifBlank { me.username } else t("chat.groupMemberMenu.unknownUser")
    val group = Names.channel(ch)
    val manage = DmArrange.managesGroup(ch)
    val isOwner = ch.ownerId == userId
    val owner = ch.ownerId?.let { nameOf(it) } ?: t("chat.groupMemberMenu.itsCurrentOwner")
    val items = ArrayList<MenuItem.Action>()
    items += MenuItem.Action(t("profile.profile.profile")) { Profiles.openFull(userId) }
    items += MenuItem.Action(t("settings.notifications.mention"), disabled = user?.username.isNullOrEmpty()) { user?.username?.let(::mention) }
    if (!self) items += MenuItem.Action(t("profile.profileCard.message")) { messageUser(userId) }
    items[items.lastIndex] = items.last().copy(separatorAfter = true)
    val people = relationshipMenuItems(userId)
    if (people.isNotEmpty()) {
        items += people.dropLast(1)
        items += people.last().copy(separatorAfter = true)
    }
    if (manage && !isOwner) {
        val label = if (self) t("chat.groupMemberMenu.takeOwnership") else t("chat.groupMemberMenu.transferOwnership")
        items += MenuItem.Action(label) {
            confirm(
                if (self) t("chat.groupMemberMenu.takeOwnershipOf", "group" to group) else t("chat.groupMemberMenu.transferOwnershipOf", "group" to group),
                when {
                    self -> t("chat.groupMemberMenu.youBecomeTheOwnerInPlaceOf", "owner" to owner)
                    ch.ownerId == me.id -> t("chat.groupMemberMenu.theyBecomeTheOwnerAndYouAMember", "name" to name)
                    else -> t("chat.groupMemberMenu.theyBecomeTheOwnerInPlaceOf", "name" to name, "owner" to owner)
                },
                label,
                danger = false,
                bodyWords = fish.nexa.ui.chat.PersonWords(when {
                    self -> t("chat.groupMemberMenu.youBecomeTheOwnerInPlaceOf", "owner" to "\u0002")
                    ch.ownerId == me.id -> t("chat.groupMemberMenu.theyBecomeTheOwnerAndYouAMember", "name" to fish.nexa.ui.chat.PERSON_SLOT)
                    else -> t("chat.groupMemberMenu.theyBecomeTheOwnerInPlaceOf", "name" to fish.nexa.ui.chat.PERSON_SLOT, "owner" to "\u0002")
                }, listOf(userId to name, ch.ownerId to owner)),
            ) {
                val res = Nexa.api.request("PATCH", "/channels/$channelId", buildJsonObject { put("owner_id", userId) })
                runCatching { wireJson.decodeFromJsonElement(Channel.serializer(), res) }.getOrNull()?.let { Nexa.channels.upsert(listOf(it)) }
            }
        }
    }
    if (manage && !self && !isOwner) {
        items += MenuItem.Action(t("chat.groupMemberMenu.removeFromGroup"), danger = true) {
            confirm(t("chat.groupMemberMenu.removeNamed", "name" to name), t("chat.groupMemberMenu.theyAreRemovedFromTheGroup", "name" to name, "group" to group), t("composer.composer.remove"), titleWords = fish.nexa.ui.chat.PersonWords(t("chat.groupMemberMenu.removeNamed", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(userId to name)), bodyWords = fish.nexa.ui.chat.PersonWords(t("chat.groupMemberMenu.theyAreRemovedFromTheGroup", "name" to fish.nexa.ui.chat.PERSON_SLOT, "group" to group), listOf(userId to name))) {
                Nexa.api.request("DELETE", "/channels/$channelId/recipients/$userId")
            }
        }
    }
    if (self && ch.type == "group_dm") {
        items += MenuItem.Action(t("chat.groupMemberMenu.leaveGroup"), danger = true) {
            fish.nexa.ui.shell.confirmGroupLeave(
                t("sidebar.dmMenu.leaveNamed", "name" to group),
                if (isOwner) t("chat.groupMemberMenu.youStopReceivingMessagesFromThis") else t("sidebar.dmMenu.youStopReceivingMessagesFromThis"),
                t("chat.groupMemberMenu.leaveGroup"),
            ) { silent -> closeDm(channelId, silent) }
        }
    }
    if (items.isNotEmpty()) items[items.lastIndex] = items.last().copy(separatorAfter = true)
    copyItems(items, userId)
    return items
}

/** Copy Profile Link, then (with Developer Mode) the user and bot ids: the last group of a person's menu. */
private fun copyItems(items: ArrayList<MenuItem.Action>, userId: String) {
    ProfileLinks.item(userId)?.let { items += it }
    if (Nexa.settings.developerMode) {
        items += MenuItem.Action(t("chat.groupMemberMenu.copyUserId")) { copyWithToast(userId, t("clipboard.idCopied")) }
        items += copyBotIdItem(userId)
    }
    // no line hanging under the menu's last item
    if (items.isNotEmpty()) items[items.lastIndex] = items.last().copy(separatorAfter = false)
}

/** Puts Apps after the first group of a person's menu (who they are), as the web's menus do. */
private fun withAppsUpFront(items: ArrayList<MenuItem>, apps: MenuItem.Submenu?) {
    if (apps == null || items.isEmpty()) return
    val first = items.indexOfFirst { it is MenuItem.Action && it.separatorAfter }
    if (first < 0) return fish.nexa.ui.apps.withApps(items, apps)
    items.add(first + 1, apps.copy(separatorAfter = true))
}

/**
 * components/chat/personMenu.ts: the menu for someone shown in a conversation
 * (a long press on a message's picture or name, or the name a reply quotes),
 * the one the member list there opens. False, and nothing opened, where there
 * is none or it has nothing to offer.
 */
fun openPersonMenu(channelId: String, userId: String): Boolean {
    val ch = Nexa.channels.get(channelId) ?: return false
    val sid = ch.serverId
    val items = ArrayList<MenuItem>(
        when {
            sid != null -> memberMenuItems(sid, userId)
            ch.type == "group_dm" || ch.type == "dm" -> groupMemberMenuItems(channelId, userId)
            else -> emptyList()
        },
    )
    if (items.isEmpty()) return false
    // what apps can do with someone, used in this conversation
    withAppsUpFront(items, fish.nexa.ui.apps.userAppsItem(channelId, userId))
    openMenu(items, Names.member(sid, userId), titleWords = fish.nexa.ui.chat.PersonWords(fish.nexa.ui.chat.PERSON_SLOT, listOf(userId to Names.member(sid, userId)), sid))
    return true
}

private fun openGroupMemberMenu(channelId: String, userId: String, name: String, list: String) {
    if (Pick.isPicked(list, userId) && Pick.ids.size > 1) {
        openMenu(groupMemberBulk(channelId, Pick.ids.toList()).menu, t("pick.count", "count" to Pick.ids.size))
        return
    }
    if (Pick.list == list && !Pick.mode && Pick.ids.isNotEmpty()) Pick.clear()
    val items = ArrayList<MenuItem>(groupMemberMenuItems(channelId, userId))
    if (items.isEmpty()) return
    // what apps can do with someone, used in this group
    withAppsUpFront(items, fish.nexa.ui.apps.userAppsItem(channelId, userId))
    items[items.lastIndex] = items.last().withSeparator()
    items += MenuItem.Action(t("pick.select")) { Pick.start(list, userId) }
    openMenu(items, name, titleWords = fish.nexa.ui.chat.PersonWords(fish.nexa.ui.chat.PERSON_SLOT, listOf(userId to name)))
}

/** What can be done to several people in a group at once: remove them (for whoever manages it), block them. */
private fun groupMemberBulk(channelId: String, ids: List<String>): Bulk {
    val ch = Nexa.channels.get(channelId)
    val me = Nexa.users.meId
    val others = ids.filter { it != me && it != ch?.ownerId }
    val blockable = usersOf(ids.filter { it != me }).filter { Nexa.relationships.typeOf(it.id) != "blocked" }
    val bar = ArrayList<PickAction>()
    val menu = ArrayList<MenuItem>()
    if (ch != null && DmArrange.managesGroup(ch) && others.isNotEmpty()) {
        val label = t("pick.removeFromGroup", "count" to others.size)
        val remove = {
            confirm(t("pick.removeFromGroupTitle", "count" to others.size), t("pick.removeFromGroupBody", "names" to Names.list(usersOf(others).map { Names.display(it) })), label, bodyWords = fish.nexa.ui.chat.PersonWords(t("pick.removeFromGroupBody", "names" to Names.list(usersOf(others).indices.map { fish.nexa.ui.chat.personSlot(it) })), usersOf(others).map { it.id to Names.display(it) })) {
                Pick.clear()
                each(others, { id -> Nexa.api.request("DELETE", "/channels/$channelId/recipients/$id") }) { count -> t("pick.removedFromGroup", "count" to count) }
            }
        }
        bar += PickAction("DoorOpen", label, danger = true) { remove() }
        menu += MenuItem.Action(label, danger = true) { remove() }
    }
    if (blockable.isNotEmpty()) {
        val label = t("pick.block", "count" to blockable.size)
        bar += PickAction("ShieldOff", label, danger = true) { confirmBlockPeople(blockable) }
        menu += MenuItem.Action(label, danger = true) { confirmBlockPeople(blockable) }
    }
    return Bulk(bar, menu)
}
