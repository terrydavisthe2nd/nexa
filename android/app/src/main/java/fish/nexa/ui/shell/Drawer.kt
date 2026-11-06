package fish.nexa.ui.shell

import fish.nexa.ui.collectEntryAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Ids
import fish.nexa.data.NexaInbox
import fish.nexa.data.P
import fish.nexa.net.Channel
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.showsTyping
import fish.nexa.ui.components.CountBadge
import fish.nexa.ui.components.NexaMark
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sink
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.glowBrand
import fish.nexa.ui.theme.liftLow
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.panel
import fish.nexa.ui.theme.positiveGradient
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The navigation drawer (AppShell .drawer on a phone): the rail standing on
 * the canvas, the list on a floating panel beside it, and the dock (call
 * status and your user panel) under both. Its end corners are rounded; the
 * canvas carries a faint glow of the accent from the top corner.
 */
@Composable
fun NavDrawer(open: Boolean) {
    val c = Nx.colors
    val shape = RoundedCornerShape(topEnd = Radii.lg, bottomEnd = Radii.lg)
    Column(
        Modifier
            .fillMaxSize()
            .then(if (open) Modifier.shadowMedium(shape, c) else Modifier)
            .background(c.bgCanvas, shape)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(c.canvasGlow, Color.Transparent), center = Offset(-0.1f * size.width, -0.08f * size.height), radius = 520.dp.toPx() * 0.66f))
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Rail(Modifier.width(72.dp).fillMaxHeight().offset(y = (-8).dp))
            val panelShape = Radii.Lg
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .panel(panelShape, c, c.bgBase)
                    .clip(panelShape),
            ) {
                when (val r = Nav.route) {
                    is Route.Server -> ServerSidebar(r.serverId, r.channelId)
                    // the panel has its own sidebar in place of the conversations, as a server has its channels
                    is Route.Admin -> fish.nexa.ui.moderation.StaffSidebar(r)
                    else -> DmSidebar()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Dock(Modifier.padding(start = 8.dp))
    }
}

// ---------------------------------------------------------------------------
// Rail (Rail.module.css)

/** The light on the rail's edge: hidden, 8 (unread), 36 (selected, glowing). */
@Composable
private fun RailPill(selected: Boolean, unread: Boolean) {
    val c = Nx.colors
    val h by animateDpAsState(if (selected) 36.dp else 8.dp, tween(Nx.motion.normal, easing = Nx.motion.easeOut), label = "pill")
    val a by animateFloatAsState(if (selected || unread) 1f else 0f, tween(Nx.motion.normal), label = "pillA")
    val shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    Box(
        Modifier
            .width(4.dp)
            .height(h)
            .alpha(a)
            .then(if (selected) Modifier.glowBrand(shape, c) else Modifier)
            .background(c.textStrong, shape),
    )
}

/** One rail item: the pill, a 48 dp tile, an optional badge cut out of the canvas. */
@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    unread: Boolean,
    badge: Int,
    picture: Boolean,
    circle: Boolean = false,
    muted: Boolean = false,
    voice: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(if (circle) 24.dp else if (selected) 14.dp else 16.dp, tween(Nx.motion.normal, easing = Nx.motion.easeOut), label = "radius")
    val scale by animateFloatAsState(if (pressed && !Nx.motion.reduced) 0.96f else 1f, tween(80), label = "press")
    val shape = RoundedCornerShape(radius)
    Box(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = if (voice) t("rail.rail.serverInVoice", "server" to label) else label }, contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.CenterStart)) { RailPill(selected, unread && !selected) }
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .alpha(if (muted) 0.6f else 1f)
                .then(
                    when {
                        selected && picture -> Modifier
                            .ring(shape, c.brand, 4.dp)
                            .ring(shape, c.bgCanvas, 2.dp)
                            .background(c.bgSurface, shape)
                        selected -> Modifier
                            .glowBrand(shape, c)
                            .background(brandGradient(c), shape)
                            .topEdge(shape, Color.White.copy(alpha = 0.22f))
                        picture -> Modifier.ring(shape, c.panelEdge).liftLow(shape, c)
                        else -> Modifier
                            .liftLow(shape, c)
                            .ring(shape, c.panelEdge)
                            .background(c.bgSurface, shape)
                            .background(sheen(c), shape)
                            .topEdge(shape, c.edgeLight)
                    },
                )
                .clip(shape)
                .pressable(interaction, onLongClick = onLongClick, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
        // people are in a voice channel here this account could join: a speaker on a green disc, cut out of
        // the canvas like the count, sitting just above the count when there is one (Rail.module.css .voiceBadge)
        if (voice) Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp)
                .offset(y = if (badge > 0) (-19).dp else 3.dp)
                .size(26.dp)
                .background(c.bgCanvas, CircleShape)
                .padding(3.dp)
                .background(positiveGradient(c), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon("Volume2", c.onPositive, size = 12.dp, stroke = 2.6f) }
        if (badge > 0) Box(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp).offset(y = 3.dp)) { CountBadge(badge, cut = c.bgCanvas) }
    }
}

/**
 * The servers where people are in a voice channel this account could join
 * too (Rail.tsx voiceLive): there is a call going, and the speaker on the
 * icon says so.
 */
@Composable
private fun voiceLiveServers(): Set<String> {
    val voice by Nexa.voiceStates.byUser.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    return remember(voice, channels, servers) {
        val live = HashSet<String>()
        for (v in voice.values) {
            val chId = v.channelId ?: continue
            val ch = channels[chId] ?: continue
            val sid = ch.serverId ?: continue
            if (ch.type != "voice" || sid in live) continue
            if (Nexa.servers.canI(servers[sid], fish.nexa.data.P.VIEW_CHANNEL or fish.nexa.data.P.CONNECT, chId)) live += sid
        }
        live
    }
}

/** One conversation waiting under home: a DM or group, or Nexa's own (no channel). */
private class Waiting(val channel: fish.nexa.net.Channel?, val count: Int, val at: Long)

@Composable
fun Rail(modifier: Modifier = Modifier) {
    val c = Nx.colors
    val servers by Nexa.servers.byId.collectAsState()
    val order by Nexa.servers.order.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val reads by Nexa.readStates.byChannel.collectAsState()
    val rels by Nexa.relationships.byId.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val me by Nexa.users.me.collectAsState()
    val nexaStanding by NexaInbox.standing.collectAsState()
    val route = Nav.route
    val requests = rels.values.count { it.type == "incoming" }
    val home = route.isHome
    val liveVoice = voiceLiveServers()
    val layout = remember(doc, order) {
        RailLayout.resolve(doc["rail"], order, (doc["serverPositions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList())
    }
    val openDm = (route as? Route.Dm)?.channelId
    val nexaOpen = route is Route.NexaConvo
    // conversations with messages waiting stand under home, newest first; what Nexa sent waits with them,
    // and once its conversation is closed only something new counts (keyed on the read marks, so the rail redraws with them)
    val waiting = remember(channels, reads, doc, openDm, nexaOpen, nexaStanding) {
        buildList {
            for (ch in channels.values) {
                if ((ch.type != "dm" && ch.type != "group_dm") || ch.id == openDm) continue
                val n = Unread.conversationCount(ch)
                if (n > 0) add(Waiting(ch, n, Ids.createdAt(ch.lastMessageId ?: ch.id)))
            }
            if (nexaStanding != null && !nexaOpen && NexaInbox.listed() && NexaInbox.unread() > 0) add(Waiting(null, NexaInbox.unread(), NexaInbox.latest()))
        }.sortedByDescending { it.at }.take(10)
    }
    val muted = remember(doc) { Unread.mutedIds() }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RailItem(
            label = if (requests > 0) t("rail.rail.directMessagesWith", "what" to t("rail.rail.friendRequestsCount", "count" to requests)) else t("sidebar.dMSidebar.directMessages"),
            selected = home, unread = requests > 0 && !home, badge = requests, picture = false,
            onClick = { Nav.go(Route.Friends, keepDrawer = true) },
        ) { NexaMark(if (home) c.onBrand else c.textSubtle, 30.dp) }
        // every rank of staff opens the panel
        if (fish.nexa.data.StaffRanks.of(me?.id, me?.flags ?: 0L) != null) {
            // threads waiting on the staff this person has not read, and reports nobody has picked up
            val waitingOnStaff = fish.nexa.ui.moderation.attentionCount()
            val inPanel = route is Route.Admin
            RailItem(
                if (waitingOnStaff > 0) t("moderation.railWithCount", "count" to waitingOnStaff) else t("moderation.title"),
                inPanel, waitingOnStaff > 0 && !inPanel, waitingOnStaff, false,
                onLongClick = { fish.nexa.ui.moderation.openModerationMenu() },
                onClick = { Nav.go(Route.Admin()) },
            ) {
                Icon("Shield", if (inPanel) c.onBrand else c.textSubtle, size = 24.dp)
            }
        }
        for (w in waiting) {
            val ch = w.channel
            val what = if (w.count > 99) t("rail.rail.moreThan99Unread") else t("rail.rail.unreadMessagesCount", "count" to w.count)
            if (ch == null) {
                RailItem(
                    label = t("rail.rail.channelUnread", "name" to "Nexa", "what" to what),
                    selected = false, unread = true, badge = w.count, picture = true, circle = true,
                    onLongClick = { openMenu(nexaMenu(null, select = false), "Nexa") },
                    onClick = { Nav.go(Route.NexaConvo) },
                ) { fish.nexa.ui.profile.NexaAvatar(48.dp) }
                continue
            }
            RailItem(
                label = t("rail.rail.channelUnread", "name" to Names.channel(ch), "what" to what),
                selected = false, unread = true, badge = w.count, picture = true, circle = true,
                onLongClick = { openMenu(dmMenu(ch)) },
                onClick = { Nav.go(Route.Dm(ch.id)) },
            ) { ConversationAvatar(ch, 48.dp) }
        }
        Box(Modifier.size(28.dp, 2.dp).background(c.border, RoundedCornerShape(1.dp)))
        for ((i, list) in listOf(layout.pinned, layout.rest).withIndex()) {
            if (i == 1 && layout.pinned.isNotEmpty() && layout.rest.isNotEmpty()) {
                Row(Modifier.size(40.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f).height(2.dp).background(c.border, RoundedCornerShape(1.dp)))
                    Icon("Pin", c.textMuted, size = 12.dp)
                    Box(Modifier.weight(1f).height(2.dp).background(c.border, RoundedCornerShape(1.dp)))
                }
            }
            for (e in list) {
                when (e) {
                    is RailLayout.Entry.ServerEntry -> servers[e.id]?.let { st ->
                        val selected = (route as? Route.Server)?.serverId == e.id
                        val u = Unread.server(e.id)
                        val mutedServer = e.id in muted
                        RailItem(
                            label = if (u.mentions > 0) t("rail.rail.serverWithMentions", "name" to st.server.name, "count" to u.mentions) else st.server.name,
                            selected = selected, unread = u.unread && !mutedServer, badge = u.mentions, picture = st.server.iconId != null,
                            muted = mutedServer, voice = e.id in liveVoice,
                            onLongClick = { openMenu(serverMenu(e.id, fromRail = true), st.server.name) },
                            onClick = { Nav.go(Route.Server(e.id), keepDrawer = true) },
                        ) { ServerTileFace(e.id, st.server.name, st.server.iconId, selected) }
                    }
                    is RailLayout.Entry.FolderEntry -> FolderTile(e.folder, route, liveVoice)
                }
            }
        }
        RailItem(t("rail.rail.addAServer"), false, false, 0, picture = true, onClick = { openCreateServer() }) {
            Box(Modifier.fillMaxSize().ring(RoundedCornerShape(16.dp), c.positive.copy(alpha = 0.5f), 1.5.dp), contentAlignment = Alignment.Center) {
                Icon("Plus", c.positive, size = 24.dp)
            }
        }
        // servers anyone can join, as the web's rail has them under Add a Server
        val discovering = route is Route.Discover
        RailItem(t("discovery.title"), discovering, false, 0, picture = discovering, onClick = { Nav.go(Route.Discover()) }) {
            Box(
                Modifier.fillMaxSize().then(if (discovering) Modifier.background(c.positive) else Modifier.ring(RoundedCornerShape(16.dp), c.positive.copy(alpha = 0.5f), 1.5.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon("Compass", if (discovering) Color.White else c.positive, size = 24.dp) }
        }
    }
}

@Composable
private fun ServerTileFace(id: String, name: String, iconId: String?, selected: Boolean) {
    val c = Nx.colors
    if (iconId != null) {
        fish.nexa.ui.components.ServerIcon(id, name, iconId, 48.dp, radius = if (selected) 14.dp else 16.dp)
    } else {
        Txt(
            fish.nexa.ui.components.AvatarColor.initials(name),
            style = Nx.type.name.copy(fontSize = Nx.type.sp(16f), fontWeight = FontWeight(700)),
            color = if (selected) c.onBrand else c.textStrong,
            maxLines = 1,
        )
    }
}

@Composable
private fun FolderTile(f: RailLayout.Folder, route: Route, liveVoice: Set<String>) {
    val c = Nx.colors
    val folder = fish.nexa.ui.theme.parseHex(f.color) ?: c.brand
    val servers = Nexa.servers.byId.value
    val activeHere = (route as? Route.Server)?.serverId in f.servers
    val unread = f.servers.any { Unread.server(it).unread }
    val mentions = f.servers.sumOf { Unread.server(it).mentions }
    val name = f.name.ifBlank { Names.list(f.servers.mapNotNull { servers[it]?.server?.name }) }.ifBlank { t("organize.rail.folder") }
    fun toggle() = Rail.setFolderOpen(f.id, !f.open)
    if (!f.open) {
        RailItem(
            label = t("organize.rail.folderLabel", "name" to name, "count" to f.servers.size),
            selected = activeHere, unread = unread && !activeHere, badge = mentions, picture = true,
            // a closed folder stands in for a call going on in any of its servers
            voice = f.servers.any { it in liveVoice },
            onLongClick = { openMenu(listOf(MenuItem.Action(t("organize.rail.deleteFolder")) { Rail.deleteFolder(f.id) }), name) },
            onClick = { toggle() },
        ) {
            Box(Modifier.fillMaxSize().background(mixOklab(c.bgSurface, folder, 0.34f)).background(sheen(c)), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in f.servers.take(4).chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (sid in row) {
                            val st = servers[sid]
                            Box(Modifier.size(16.dp).clip(CircleShape).background(c.bgRaised), contentAlignment = Alignment.Center) {
                                if (st?.server?.iconId != null) fish.nexa.ui.components.ServerIcon(sid, st.server.name, st.server.iconId, 16.dp, radius = 8.dp)
                                else Txt(fish.nexa.ui.components.AvatarColor.initials(st?.server?.name ?: "?"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(9f), fontWeight = FontWeight(700)), color = c.textStrong)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(
            Modifier
                .width(58.dp)
                .background(mixOklab(c.bgCanvas, folder, 0.16f), RoundedCornerShape(18.dp))
                .ring(RoundedCornerShape(18.dp), folder.copy(alpha = 0.24f))
                .topEdge(RoundedCornerShape(18.dp), c.edgeLight)
                .padding(bottom = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RailItem(name, false, false, 0, picture = true, onClick = { toggle() }) {
                Icon("Folder", folder, size = 24.dp)
            }
            for (sid in f.servers) {
                val st = servers[sid] ?: continue
                val selected = (route as? Route.Server)?.serverId == sid
                val u = Unread.server(sid)
                RailItem(
                    st.server.name, selected, u.unread, u.mentions, st.server.iconId != null,
                    voice = sid in liveVoice,
                    onLongClick = { openMenu(serverMenu(sid, fromRail = true), st.server.name) },
                    onClick = { Nav.go(Route.Server(sid), keepDrawer = true) },
                ) { ServerTileFace(sid, st.server.name, st.server.iconId, selected) }
            }
        }
    }
}

/** Rail layout edits, saved to the account's settings (railActions.ts). */
object Rail {
    private fun current(): RailLayout.Layout {
        val doc = Nexa.settings.doc.value
        return RailLayout.resolve(doc["rail"], Nexa.servers.order.value, emptyList())
    }

    private fun save(l: RailLayout.Layout) = Nexa.settings.set("rail", RailLayout.toJson(l))

    private fun mapFolders(l: RailLayout.Layout, fn: (RailLayout.Folder) -> RailLayout.Folder?): RailLayout.Layout {
        fun m(list: List<RailLayout.Entry>) = list.mapNotNull { e -> if (e is RailLayout.Entry.FolderEntry) fn(e.folder)?.let { RailLayout.Entry.FolderEntry(it) } else e }
        return RailLayout.Layout(m(l.pinned), m(l.rest))
    }

    fun setFolderOpen(id: String, open: Boolean) = save(mapFolders(current()) { if (it.id == id) it.copy(open = open) else it })

    fun deleteFolder(id: String) {
        val l = current()
        fun m(list: List<RailLayout.Entry>) = list.flatMap { e ->
            if (e is RailLayout.Entry.FolderEntry && e.folder.id == id) e.folder.servers.map { RailLayout.Entry.ServerEntry(it) } else listOf(e)
        }
        save(RailLayout.Layout(m(l.pinned), m(l.rest)))
    }

    fun pin(serverId: String, pin: Boolean) {
        val l = current()
        fun strip(list: List<RailLayout.Entry>) = list.mapNotNull { e ->
            when (e) {
                is RailLayout.Entry.ServerEntry -> e.takeIf { it.id != serverId }
                is RailLayout.Entry.FolderEntry -> e.folder.servers.filter { it != serverId }.takeIf { it.isNotEmpty() }?.let { RailLayout.Entry.FolderEntry(e.folder.copy(servers = it)) }
            }
        }
        val entry = RailLayout.Entry.ServerEntry(serverId)
        save(if (pin) RailLayout.Layout(strip(l.pinned) + entry, strip(l.rest)) else RailLayout.Layout(strip(l.pinned), listOf(entry) + strip(l.rest)))
    }

    fun isPinned(serverId: String) = current().pinned.any { it is RailLayout.Entry.ServerEntry && it.id == serverId }

    fun move(serverId: String, by: Int) {
        val l = current()
        fun mv(list: List<RailLayout.Entry>): List<RailLayout.Entry> {
            val i = list.indexOfFirst { it is RailLayout.Entry.ServerEntry && it.id == serverId }
            if (i < 0) return list
            val j = (i + by).coerceIn(0, list.size - 1)
            val m = list.toMutableList()
            val x = m.removeAt(i)
            m.add(j, x)
            return m
        }
        save(RailLayout.Layout(mv(l.pinned), mv(l.rest)))
    }
}

// ---------------------------------------------------------------------------

/** A conversation's picture: the other person, the group's icon, or a group glyph on the accent. */
@Composable
fun ConversationAvatar(ch: Channel, size: Dp, status: String? = null, showTyping: Boolean = false) {
    val c = Nx.colors
    // DMSidebar.tsx TypingAvatar: while someone in it types, the presence dot becomes a pill of dots
    val typers = if (showTyping) typersIn(ch.id) else emptyList()
    val on = showTyping && settingOn("typingAvatars")
    when {
        ch.type == "dm" -> {
            val other = Names.otherOf(ch) ?: ""
            val u = Nexa.users.get(other)
            Avatar(other, Names.shown(other), u?.avatarId, size, status = status, typing = if (on) other in typers else null)
        }
        else -> {
            // a group: a dot that only shows while someone types, in the colour of the first of them who shows as present
            val typer = typers.map { Nexa.presence.statusOf(it) }.firstOrNull { showsTyping(it) }
            val face: (@Composable () -> Unit)? = if (ch.iconId != null) null else {
                { Box(Modifier.fillMaxSize().background(brandGradient(c)).topEdge(CircleShape, Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Icon("Users", c.onBrand, size = size * 0.5f) } }
            }
            Avatar(ch.id, Names.channel(ch), ch.iconId, size, icon = true, status = typer, typing = if (on) typer != null else null, face = face)
        }
    }
}

/**
 * A yes or no setting, read so the composable redraws when it changes:
 * Nexa.settings' getters are plain reads, and a row that read one keeps the
 * old answer until something else redraws it.
 */
@Composable
fun settingOn(key: String, default: Boolean = true): Boolean {
    val value by Nexa.settings.doc.collectEntryAsState(key)
    return (value as? JsonPrimitive)?.booleanOrNull ?: default
}

/** Who is typing in one channel, apart from this account (stores/typing.ts useTypingIn). */
@Composable
fun typersIn(channelId: String): List<String> {
    val typers by Nexa.typing.byChannel.collectEntryAsState(channelId)
    val me = Nexa.users.meId
    val now = System.currentTimeMillis()
    return typers?.filter { (u, exp) -> exp > now && u != me }?.keys?.toList() ?: emptyList()
}

/** The server menu (serverMenu.ts serverMenuItems), from the rail or the sidebar header. */
fun serverMenu(serverId: String, fromRail: Boolean): List<MenuItem> {
    val st = Nexa.servers.get(serverId) ?: return emptyList()
    val items = ArrayList<MenuItem>()
    items += fish.nexa.ui.reports.reportItem(fish.nexa.data.ReportContext(kind = "server", id = serverId))
    items += MenuItem.Action(t("sidebar.dmMenu.markAsRead"), separatorAfter = true) { Unread.markServerRead(serverId) }
    if (fromRail) {
        val pinned = Rail.isPinned(serverId)
        items += MenuItem.Action(if (pinned) t("organize.rail.unpinServer") else t("organize.rail.pinServer")) { Rail.pin(serverId, !pinned) }
        items += MenuItem.Action(t("reorder.moveUp")) { Rail.move(serverId, -1) }
        items += MenuItem.Action(t("reorder.moveDown"), separatorAfter = true) { Rail.move(serverId, 1) }
    }
    val canInvite = Nexa.channels.byId.value.values.any { it.serverId == serverId && it.isText && Nexa.servers.canI(st, P.CREATE_INSTANT_INVITE, it.id) }
    val manage = listOf(P.MANAGE_SERVER, P.MANAGE_ROLES, P.MANAGE_CHANNELS, P.VIEW_AUDIT_LOG, P.BAN_MEMBERS, P.KICK_MEMBERS).any { Nexa.servers.canI(st, it) }
    val channels = Nexa.servers.canI(st, P.MANAGE_CHANNELS)
    val group = ArrayList<MenuItem.Action>()
    if (canInvite) group += MenuItem.Action(t("chat.chat.invitePeople")) { openInvite(serverId, null) }
    if (manage) group += MenuItem.Action(t("server.serverSettings.serverSettings")) { openServerSettings(serverId) }
    if (channels) {
        group += MenuItem.Action(t("modals.createChannel.createChannel")) { openCreateChannel(serverId, null) }
        group += MenuItem.Action(t("modals.createCategory.createCategory")) { openCreateCategory(serverId) }
    }
    // a Pro account puts one of its boosts here: bigger files for everyone in it
    if (fish.nexa.data.Plans.mine() == "pro") group += MenuItem.Action(t("support.boosts.boostServer")) { boostServer(serverId, st.server.name) }
    if (group.isNotEmpty()) items += group.dropLast(1) + group.last().copy(separatorAfter = true)
    val muted = serverId in Unread.mutedIds()
    items += MenuItem.Action(if (muted) t("sidebar.serverMenu.unmuteServer") else t("sidebar.serverMenu.muteServer")) { Unread.setMuted(serverId, !muted) }
    items += MenuItem.Action(t("modals.serverNotifications.notificationSettings"), separatorAfter = true) { openServerNotifications(serverId) }
    items += MenuItem.Action(t("sidebar.serverMenu.editServerProfile")) { openServerProfile(serverId) }
    if (st.server.ownerId != Nexa.users.meId) items += MenuItem.Action(t("sidebar.serverMenu.leaveServer"), danger = true) { confirmLeaveServer(serverId) }
    if (Nexa.settings.developerMode) items += MenuItem.Action(t("sidebar.serverMenu.copyServerId")) { copyText(serverId) }
    return items
}

private fun boostServer(serverId: String, name: String) {
    Nexa.scope.launch {
        try {
            Nexa.api.request("POST", "/billing/boosts", kotlinx.serialization.json.buildJsonObject { put("server_id", kotlinx.serialization.json.JsonPrimitive(serverId)) })
            Nexa.ui.toast(t("support.boosts.boosted", "server" to name, "size" to fish.nexa.ui.Time.bytes(fish.nexa.data.Plans.info().boostFileMax)), "success")
            fish.nexa.data.Plans.load()
        } catch (e: fish.nexa.net.ApiError) {
            Nexa.ui.toast(e.message ?: t("support.error"), "error")
        } catch (e: Exception) {
            Nexa.ui.toast(t("support.error"), "error")
        }
    }
}

fun copyText(text: String) {
    val cm = Nexa.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Nexa", text))
    Nexa.ui.toast(t("chat.groupMemberMenu.copied"))
}
