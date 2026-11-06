package fish.nexa.ui.shell

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.data.Perms
import fish.nexa.net.Channel
import fish.nexa.media.Media
import fish.nexa.media.TwemojiImage
import fish.nexa.ui.Icon
import fish.nexa.ui.Lucide
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.CountBadge
import fish.nexa.ui.components.NexaMark
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.mixSrgb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** A selected row's look: the sheen on --bg-selected, a lit top edge, a small shadow. */
@Composable
fun Modifier.selectedRow(selected: Boolean, pressed: Boolean): Modifier {
    val c = Nx.colors
    val shape = Radii.Sm
    return when {
        selected -> this
            .boxShadow(shape, 1f, 2f, c.shadeAt(0.35f))
            .background(c.bgSelected, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
        pressed -> this.background(c.bgHover, shape)
        else -> this
    }
}

/** The 4 x 8 light on the panel's edge beside an unread row. */
@Composable
fun UnreadEdge(modifier: Modifier = Modifier) {
    Box(modifier.offset(x = (-8).dp).size(4.dp, 8.dp).background(Nx.colors.textStrong, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
}

// ---------------------------------------------------------------------------
// Server channel list (ServerSidebar.tsx)

@Composable
fun ServerSidebar(serverId: String, channelId: String?) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val servers by Nexa.servers.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val reads by Nexa.readStates.byChannel.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val voices by Nexa.voiceStates.byUser.collectAsState()
    val joinedThreads by Nexa.threads.joined.collectAsState()
    // read so the list redraws when read marks, settings or voice states change
    @Suppress("UNUSED_VARIABLE") val touch = reads.size + doc.size + voices.size
    // The threads this account is in, still going, under the channel they
    // belong to: ones it started, wrote in or was brought into. Looking at a
    // thread or a forum post is not joining it, so it does not stand here
    // while it is open; its channel is shown as the place it is in instead.
    val threadsByParent = remember(channels, joinedThreads) {
        channels.values.filter { it.isThread && it.thread?.archived != true && it.id in joinedThreads }
            .sortedWith { a, b -> fish.nexa.data.Ids.compare(b.lastMessageId ?: b.id, a.lastMessageId ?: a.id) }
            .groupBy { it.parentId }
    }
    fun threadsUnder(ch: Channel): List<Channel> =
        if (!ch.holdsThreads) emptyList() else threadsByParent[ch.id].orEmpty()
    val open = channelId?.let { channels[it] }
    fun inside(ch: Channel) = open != null && open.parentId == ch.id && open.id !in joinedThreads && (open.type == "thread" || open.type == "private_thread")
    val st = servers[serverId] ?: return
    val viewAs by Nexa.servers.viewAs.collectAsState()
    val tree = remember(channels, serverId, viewAs, servers[serverId]) {
        val full = ChannelTree.of(serverId, channels.values)
        val st = servers[serverId]
        // View Server as Role: the server sends what this account sees, so the preview hides what the role would not
        if (st == null || viewAs?.serverId != serverId) full
        else {
            fun sees(id: String) = Nexa.servers.canI(st, fish.nexa.data.P.VIEW_CHANNEL, id)
            ChannelTree.Tree(
                full.loose.filter { sees(it.id) },
                full.categories.map { it.copy(children = it.children.filter { ch -> sees(ch.id) }) }.filter { it.children.isNotEmpty() || sees(it.channel.id) },
            )
        }
    }
    val collapsed = Nexa.settings.collapsedCategories
    // the category just opened here and the channels it brought back (web ServerSidebar's opened)
    var opened by remember(serverId) { mutableStateOf<Opened?>(null) }
    val density = LocalDensity.current.density
    val banner = st.server.bannerId
    val list = rememberLazyListState()
    val overBanner by remember { derivedStateOf { banner != null && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 89 * density } }

    Box(Modifier.fillMaxSize()) {
        val still = Nx.motion.reduced
        val ease = Nx.motion.easeOut
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            if (banner != null) item("banner") {
                AsyncImage(
                    model = Media.bannerUrl(banner, 480, density),
                    imageLoader = Media.loader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(138.dp).offset(x = 0.dp).clip(RoundedCornerShape(topStart = Radii.lg, topEnd = Radii.lg)),
                )
            } else item("top") { Spacer(Modifier.height(52.dp + 12.dp)) }
            items(tree.loose, key = { it.id }) { ch -> ChannelRow(st.server.id, ch, ch.id == channelId || inside(ch), threadsUnder(ch), channelId) }
            for (cat in tree.categories) {
                val isCollapsed = cat.channel.id in collapsed
                // a closed category still shows the open channel and the ones with something new
                fun keptClosed(ch: Channel) = ch.id == channelId || inside(ch) || (ch.hasChat && Unread.isUnread(Nexa.readStates.get(ch.id), ch.lastMessageId) && !Unread.isMuted(ch.id, serverId)) || (Nexa.readStates.get(ch.id)?.mentions ?: 0) > 0 ||
                    threadsUnder(ch).any { it.id == channelId }
                item(cat.channel.id) {
                    CategoryHeader(serverId, cat.channel, isCollapsed) {
                        // the channels this brings back, in order, fixed as it opens (web toggleCategory)
                        opened = Opened(cat.channel.id, cat.children.filter { !keptClosed(it) }.mapIndexed { i, ch -> ch.id to i }.toMap(), android.os.SystemClock.uptimeMillis())
                    }
                }
                val shown = if (!isCollapsed) cat.children else cat.children.filter { keptClosed(it) }
                // Opening a category slides the channels it brings back down into place, one just
                // after another (web catReveal); the ones showing all along stay where they are,
                // and closing is immediate, as there.
                val reveal = opened?.takeIf { it.category == cat.channel.id }
                items(shown, key = { it.id }) { ch ->
                    val at = reveal?.rows?.get(ch.id)
                    // only as it opens: not when a row scrolled out of sight comes back later
                    val rise = remember { androidx.compose.animation.core.Animatable(if (at != null && !still && android.os.SystemClock.uptimeMillis() - reveal.at < 400) 0f else 1f) }
                    LaunchedEffect(rise) { if (rise.value < 1f) rise.animateTo(1f, tween(220, delayMillis = minOf(at ?: 0, 8) * 18, easing = ease)) }
                    Box(Modifier.graphicsLayer { alpha = rise.value; translationY = (rise.value - 1f) * 6.dp.toPx() }) {
                        ChannelRow(st.server.id, ch, ch.id == channelId || inside(ch), threadsUnder(ch), channelId)
                    }
                }
            }
            if (tree.loose.isEmpty() && tree.categories.isEmpty()) item("empty") {
                Txt(t("sidebar.serverSidebar.noChannelsYouCanSeeYet"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp))
            }
            item("bottom") { Spacer(Modifier.height(8.dp)) }
        }
        // the header: on the banner while it shows, then a plain bar
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(
                    if (overBanner) Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (pressed) 0.7f else 0.55f), Color.Transparent)))
                    else Modifier.background(if (pressed) c.bgHover else c.bgBase).background(if (banner != null) Color.Transparent else Color.Transparent),
                )
                .pressable(interaction) { openMenu(serverMenu(serverId, fromRail = false)) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(st.server.name, style = Nx.type.name.copy(fontWeight = FontWeight(660)), color = if (overBanner) Color.White else c.textStrong, maxLines = 1, modifier = Modifier.weight(1f))
            Icon("ChevronDown", if (overBanner) Color.White else c.textStrong, size = 18.dp)
        }
        if (!overBanner) Box(Modifier.fillMaxWidth().padding(top = 52.dp).height(1.dp).background(c.borderSubtle))
    }
}

@Composable
private fun CategoryHeader(serverId: String, cat: Channel, collapsed: Boolean, onOpen: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(40.dp)
            .pressable(interaction, onLongClick = { openMenu(categoryMenu(serverId, cat, collapsed, onOpen)) }) { if (collapsed) onOpen(); toggleCollapsed(cat.id, collapsed) }
            .padding(start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // the chevron turns as the category opens and closes (web .catChevron)
        val ease = Nx.motion.easeOut
        val turn by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, tween(Nx.motion.normal, easing = ease), label = "chevron")
        val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
        Box(Modifier.graphicsLayer { rotationZ = turn * if (rtl) 90f else -90f }) { Icon("ChevronDown", c.textMuted, size = 12.dp) }
        cat.glyph?.let { ChannelGlyph(cat, 14.dp, c.textMuted, Modifier.padding(start = 2.dp, end = 4.dp)) }
        Txt(cat.name, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), letterSpacing = androidx.compose.ui.unit.TextUnit(0.01f, androidx.compose.ui.unit.TextUnitType.Em)), color = c.textMuted, maxLines = 1)
    }
}

fun toggleCollapsed(id: String, collapsed: Boolean) {
    val now = Nexa.settings.collapsedCategories.toMutableList()
    if (collapsed) now.remove(id) else now += id
    Nexa.settings.set("collapsedCategories", JsonArray(now.map { JsonPrimitive(it) }))
}

private fun categoryMenu(serverId: String, cat: Channel, collapsed: Boolean, onOpen: () -> Unit): List<MenuItem> {
    val st = Nexa.servers.get(serverId)
    val items = ArrayList<MenuItem>()
    items += fish.nexa.ui.reports.reportItem(fish.nexa.data.ReportContext(kind = "channel", id = cat.id))
    items += MenuItem.Action(if (collapsed) t("sidebar.serverMenu.expandCategory") else t("sidebar.serverMenu.collapseCategory"), separatorAfter = true) { if (collapsed) onOpen(); toggleCollapsed(cat.id, collapsed) }
    if (Nexa.servers.canI(st, P.MANAGE_CHANNELS)) {
        items += MenuItem.Action(t("modals.createChannel.createChannel")) { openCreateChannel(serverId, cat.id) }
        items += MenuItem.Action(t("sidebar.serverMenu.editCategory")) { openChannelSettings(cat.id) }
        items += MenuItem.Action(t("sidebar.serverMenu.deleteCategory"), danger = true) { confirmDeleteChannel(cat) }
    }
    if (Nexa.settings.developerMode) items += MenuItem.Action(t("sidebar.serverMenu.copyCategoryId")) { copyText(cat.id) }
    return items
}

/** ChannelGlyph.tsx: the channel's own icon (lucide, emoji or custom emoji), else # or the speaker; a lock on private text channels. */
@Composable
fun ChannelGlyph(ch: Channel, size: androidx.compose.ui.unit.Dp, tint: Color, modifier: Modifier = Modifier, cut: Color = Nx.colors.bgBase) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val g = ch.glyph
    val icon = g?.removePrefix("icon:")?.takeIf { g.startsWith("icon:") }?.let { CHANNEL_ICONS[it] ?: it }?.takeIf { Lucide.exists(it) }
    val emoji = g?.removePrefix("emoji:")?.takeIf { g.startsWith("emoji:") }
    val custom = g?.removePrefix("custom:")?.takeIf { g.startsWith("custom:") }?.let { Nexa.expressions.emoji(it) }
    val st = ch.serverId?.let { Nexa.servers.get(it) }
    val private = st != null && ch.type != "voice" && Perms.isPrivateChannel(st.server.id, ch.overwrites)
    // a picture uploaded for it comes first: picking a glyph again puts the picture away
    val picture = ch.iconId?.takeIf { ch.serverId != null }
    val voiceOwn = ch.isVoice && (picture != null || icon != null || emoji != null || custom != null)
    val density = LocalDensity.current.density
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            picture != null -> AsyncImage(Media.iconUrl(picture, size.value.toInt(), density), null, Media.loader, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(size * 0.86f).clip(RoundedCornerShape(percent = 28)))
            icon != null -> Icon(icon, tint, size = size)
            emoji != null -> TwemojiImage(emoji, size * 0.86f)
            custom != null -> AsyncImage(Media.emojiUrl(custom.blobId, (size.value * 0.86f).toInt(), density), null, Media.loader, modifier = Modifier.size(size * 0.86f))
            ch.isVoice -> Icon("Volume2", tint, size = size)
            ch.isText -> Icon("Hash", tint, size = size)
            ch.isAnnouncement -> Icon("Megaphone", tint, size = size)
            ch.isMedia -> Icon("Images", tint, size = size)
            ch.isForum -> Icon("MessagesSquare", tint, size = size)
            ch.isLink -> Icon("Link2", tint, size = size)
            ch.isThread -> Icon("MessageSquareText", tint, size = size)
        }
        if (private || voiceOwn) {
            val badge = maxOf(9f, (size.value / 2)).dp
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp).background(cut, RoundedCornerShape(2.dp))) {
                Icon(if (private) "Lock" else "Volume2", tint, size = badge, stroke = 2.5f)
            }
        }
    }
}

/** CHANNEL_ICONS: the stored name to its lucide icon. */
val CHANNEL_ICONS = mapOf(
    "chat" to "MessageSquare", "discussion" to "MessagesSquare", "megaphone" to "Megaphone", "newspaper" to "Newspaper",
    "welcome" to "Hand", "rules" to "ScrollText", "help" to "CircleHelp", "ideas" to "Lightbulb", "events" to "CalendarDays",
    "gift" to "Gift", "party" to "PartyPopper", "trophy" to "Trophy", "people" to "Users", "crown" to "Crown", "shield" to "Shield",
    "lock" to "Lock", "code" to "Code", "terminal" to "Terminal", "bug" to "Bug", "tools" to "Wrench", "rocket" to "Rocket",
    "bot" to "Bot", "link" to "Link", "image" to "Image", "camera" to "Camera", "art" to "Palette", "video" to "Video", "film" to "Film",
    "tv" to "Tv", "music" to "Music", "headphones" to "Headphones", "mic" to "Mic", "gamepad" to "Gamepad2", "laugh" to "Laugh",
    "book" to "BookOpen", "study" to "GraduationCap", "coffee" to "Coffee", "food" to "Pizza", "globe" to "Globe", "travel" to "Plane",
    "nature" to "Leaf", "pets" to "PawPrint", "fitness" to "Dumbbell", "heart" to "Heart", "star" to "Star", "sparkles" to "Sparkles",
    "flame" to "Flame",
)

@Composable
private fun ChannelRow(serverId: String, ch: Channel, selected: Boolean, threads: List<Channel> = emptyList(), activeId: String? = null) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // collected by the row itself: a row whose own arguments did not change is
    // skipped when the list redraws, and would keep an old read mark or call
    val reads by Nexa.readStates.byChannel.collectAsState()
    val voices by Nexa.voiceStates.byUser.collectAsState()
    val read = reads[ch.id]
    val muted = Unread.isMuted(ch.id, serverId)
    val unread = ch.hasChat && !selected && Unread.isUnread(read, ch.lastMessageId) && !muted
    val mentions = read?.mentions ?: 0
    val people = if (ch.isVoice) voices.values.filter { it.channelId == ch.id }.sortedBy { it.joinedAt } else emptyList()
    Column {
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(if (ch.id in Unread.mutedIds()) 0.6f else 1f)) {
            if (unread) UnreadEdge(Modifier.align(Alignment.CenterStart))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .selectedRow(selected, pressed)
                    .pressable(interaction, onLongClick = { openMenu(channelMenu(serverId, ch)) }) {
                        when {
                            // a link channel opens its address and leaves the open channel where it is
                            ch.isLink -> LinkChannels.open(ch)
                            ch.isVoice -> {
                                fish.nexa.ui.voice.VoiceChatAsked.channelId = null
                                Nav.go(Route.Server(serverId, ch.id))
                                joinVoice(ch.id)
                            }
                            else -> Nav.go(Route.Server(serverId, ch.id))
                        }
                    }
                    .padding(start = 8.dp, end = 4.dp)
                    .semantics {
                        contentDescription = ch.name + when {
                            mentions > 0 -> ", " + t("sidebar.serverSidebar.mentionsCount", "count" to mentions)
                            unread -> ", " + t("sidebar.serverSidebar.unread")
                            else -> ""
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ChannelGlyph(ch, 20.dp, if (selected) c.textBrand else c.textMuted, cut = if (selected) c.bgBase else c.bgBase)
                // the name and, just after it, whoever is typing there; counts stay at the end of the row
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(
                        ch.name,
                        style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(550)),
                        color = when {
                            selected || unread -> c.textStrong
                            pressed -> c.text
                            else -> c.textChannel
                        },
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ChannelTyping(serverId, ch.id, lit = selected || unread)
                }
                if (ch.isVoice && people.isNotEmpty()) fish.nexa.ui.voice.CallTimer(ch.id, pill = true)
                if (ch.isVoice && ch.userLimit > 0) Txt("${people.size}/${ch.userLimit}", style = Nx.type.hint, color = c.textMuted, maxLines = 1)
                // a link channel leads out of the app, which the arrow says before it is pressed
                if (ch.isLink) Icon("ArrowUpRight", if (pressed) c.text else c.textMuted, size = 14.dp, modifier = Modifier.padding(end = 4.dp))
                // a voice channel's chat, without joining its call; reading it needs Connect too
                if (ch.isVoice && Nexa.servers.canI(Nexa.servers.get(serverId), P.VIEW_CHANNEL or P.CONNECT, ch.id)) VoiceChatButton(serverId, ch.id)
                if (mentions > 0) CountBadge(mentions, Modifier.padding(start = 4.dp))
            }
        }
        if (people.isNotEmpty()) {
            Column(Modifier.padding(start = 22.dp, bottom = 4.dp)) {
                for (v in people) VoiceParticipantRow(serverId, v)
            }
        }
        for ((i, th) in threads.withIndex()) ThreadRow(serverId, th, th.id == activeId, first = i == 0, last = i == threads.lastIndex)
    }
}

/** Opens a voice channel's chat with no call started; a press on the row itself joins. */
@Composable
private fun VoiceChatButton(serverId: String, channelId: String) {
    val label = t("voiceChat.open")
    Box(
        Modifier
            .size(40.dp)
            .pressable(remember { MutableInteractionSource() }) {
                fish.nexa.ui.voice.VoiceChatAsked.channelId = channelId
                Nav.go(Route.Server(serverId, channelId))
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon("MessageSquareText", Nx.colors.textMuted, size = 18.dp) }
}

/**
 * A thread under its channel (ServerSidebar.module.css .threadRow): a line
 * drops from the bottom edge of the channel's row, straight down from the
 * middle of its icon, and curves into each thread, whose name lines up with
 * the channel's; the thread's own tile starts after the curve, so the line
 * never runs through a highlight or the icon. Along the row, the icon's
 * middle is 18 dp in and the channel's name 35.
 */
@Composable
private fun ThreadRow(serverId: String, th: Channel, selected: Boolean, first: Boolean, last: Boolean) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val read = Nexa.readStates.get(th.id)
    val muted = Unread.isMuted(th.id, serverId) || (th.parentId != null && Unread.isMuted(th.parentId, serverId))
    val unread = !selected && Unread.isUnread(read, th.lastMessageId) && !muted
    val mentions = read?.mentions ?: 0
    // opaque, mixed with the panel, so where two pieces meet the line does not darken
    val spine = mixSrgb(c.textMuted, c.bgBase, 0.54f)
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 2.dp.toPx()
                val mid = size.height / 2f
                val r = 9.dp.toPx()
                fun x(v: Float) = if (rtl) size.width - v else v
                val at = 18.dp.toPx()
                val end = 27.dp.toPx()
                // the first leaves from the channel row's own bottom edge, outside its tile
                val top = if (first) -2.dp.toPx() else 0f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x(at), top)
                    lineTo(x(at), mid - r)
                    quadraticTo(x(at), mid, x(at + r), mid)
                    lineTo(x(end), mid)
                    // a thread with more under it carries the line on down to the next
                    if (!last) {
                        moveTo(x(at), mid - r)
                        lineTo(x(at), size.height)
                    }
                }
                drawPath(path, spine, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
            }
            .padding(vertical = 2.dp),
    ) {
        if (unread) UnreadEdge(Modifier.align(Alignment.CenterStart))
        Row(
            Modifier
                .padding(start = 28.dp)
                .fillMaxWidth()
                .height(40.dp)
                .selectedRow(selected, pressed)
                .pressable(interaction, onLongClick = { openMenu(channelMenu(serverId, th)) }) { Nav.go(Route.Server(serverId, th.id)) }
                .padding(start = 7.dp, end = 4.dp)
                .semantics {
                    contentDescription = th.name + when {
                        mentions > 0 -> ", " + t("sidebar.serverSidebar.mentionsCount", "count" to mentions)
                        unread -> ", " + t("sidebar.serverSidebar.unread")
                        else -> ""
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(
                    th.name,
                    style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(500)),
                    color = when {
                        selected || unread -> c.textStrong
                        pressed -> c.text
                        else -> c.textMuted
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ChannelTyping(serverId, th.id, lit = selected || unread)
            }
            if (mentions > 0) CountBadge(mentions, Modifier.padding(start = 4.dp))
        }
    }
}

/** "Alice is typing…", as the typing line under a conversation says it (TypingBar.kt). */
private fun typingSentence(names: List<String>): String = when (names.size) {
    1 -> t("chat.typingBar.oneTyping", "a" to names[0])
    2 -> t("chat.typingBar.twoTyping", "a" to names[0], "b" to names[1])
    3 -> t("chat.typingBar.threeTyping", "a" to names[0], "b" to names[1], "c" to names[2])
    else -> t("chat.typingBar.severalPeopleAreTyping")
}

/**
 * Dots just after a channel's name while someone types in it
 * (ServerSidebar.tsx ChannelTyping), so a server shows where people are
 * talking before anything is sent; brighter on a row that is lit. Settings,
 * Chat turns them off.
 */
@Composable
private fun ChannelTyping(serverId: String, channelId: String, lit: Boolean) {
    val on = settingOn("typingChannels")
    val typers = typersIn(channelId)
    if (!on || typers.isEmpty()) return
    val c = Nx.colors
    val label = typingSentence(typers.map { Names.member(serverId, it) })
    val reduced = Nx.motion.reduced
    val turns = androidx.compose.animation.core.rememberInfiniteTransition(label = "channelTyping")
    val phase by turns.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    val color = if (lit) c.text else c.textMuted
    // exactly the dots' width, so the gap after the name is the row's own (ServerSidebar.module.css .typing)
    androidx.compose.foundation.Canvas(Modifier.size(17.dp, 20.dp).semantics { contentDescription = label }) {
        val r = 2.dp.toPx()
        val gap = 2.5.dp.toPx()
        val x0 = (size.width - (6 * r + 2 * gap)) / 2 + r
        for (i in 0..2) {
            // each dot brightest a little after the one before it, rising a touch unless motion is reduced
            val local = ((phase - i * 0.133f) % 1f + 1f) % 1f
            val beat = if (local < 0.3f) local / 0.3f else if (local < 0.6f) 1f - (local - 0.3f) / 0.3f else 0f
            drawCircle(color.copy(alpha = 0.35f + 0.65f * beat), r, androidx.compose.ui.geometry.Offset(x0 + i * (2 * r + gap), size.height / 2 - if (reduced) 0f else 1.5.dp.toPx() * beat))
        }
    }
}

@Composable
private fun VoiceParticipantRow(serverId: String, v: fish.nexa.net.VoiceState) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val guest = v.guest != null || ((v.user?.flags ?: 0L) and 8L) != 0L
    val name = if (guest) v.user?.displayName ?: "" else Names.member(serverId, v.userId)
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(if (pressed) c.bgHover else Color.Transparent, Radii.Sm)
            .pressable(interaction, onLongClick = { v.channelId?.let { ch -> openMenu(fish.nexa.ui.voice.voicePersonMenu(v.userId, ch), title = name) } }) { if (!guest) openProfile(v.userId) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val speaking = fish.nexa.voice.Speaking.isSpeaking(v.userId)
        Avatar(v.userId, name, v.user?.avatarId ?: Nexa.users.get(v.userId)?.avatarId, 24.dp, speaking = speaking)
        // the name, then their server tag right after it, then the state icons at the end
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fish.nexa.ui.chat.PersonName(if (guest) null else v.userId, name, serverId = serverId, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textChannel, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (guest) Box(Modifier.background(c.bgActive, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                Txt(t("voice.voiceParticipants.guest").uppercase(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(10f), fontWeight = FontWeight(700)), color = c.textMuted)
            }
            else (if (v.userId == Nexa.users.meId) Nexa.users.me.value?.serverTag else Nexa.users.get(v.userId)?.serverTag)?.takeIf { it.text.isNotEmpty() }?.let { fish.nexa.ui.chat.ServerTagChip(it, small = true) }
        }
        val modMute = fish.nexa.ui.voice.VoiceModeration.modMuted(v)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (v.prioritySpeaker) Icon("Megaphone", c.textMuted, size = 14.dp)
            if (v.video) Icon("Video", c.textMuted, size = 14.dp)
            if (v.streaming) Icon("MonitorUp", c.textMuted, size = 14.dp)
            // a moderator's mute or deafen in red, wherever the person is shown
            if (v.selfMute || v.serverMute) Icon("MicOff", if (modMute) c.textDanger else c.textMuted, size = 14.dp)
            if (v.selfDeaf || v.serverDeaf) Icon("HeadphoneOff", if (v.serverDeaf) c.textDanger else c.textMuted, size = 14.dp)
        }
    }
}

private fun channelMenu(serverId: String, ch: Channel): List<MenuItem> {
    val st = Nexa.servers.get(serverId)
    val items = ArrayList<MenuItem>()
    items += fish.nexa.ui.reports.reportItem(fish.nexa.data.ReportContext(kind = "channel", id = ch.id))
    if (ch.isTextLike) items += MenuItem.Action(t("sidebar.dmMenu.markAsRead"), disabled = ch.lastMessageId == null, separatorAfter = true) { Unread.markRead(ch.id) }
    if (!ch.isLink && Nexa.servers.canI(st, P.CREATE_INSTANT_INVITE, ch.id)) items += MenuItem.Action(t("chat.chat.invitePeople")) { openInvite(serverId, ch.id) }
    // a link channel's own link is where it leads
    if (ch.isLink) ch.url?.let { url -> items += MenuItem.Action(t("channelLink.copyAddress"), separatorAfter = true) { copyText(url) } }
    else items += MenuItem.Action(t("sidebar.serverMenu.copyLink"), separatorAfter = true) { copyText("${Nexa.api.baseUrl}/channels/$serverId/${ch.id}") }
    if (ch.isTextLike || ch.isForum) {
        val muted = ch.id in Unread.mutedIds()
        items += MenuItem.Action(if (muted) t("sidebar.serverMenu.unmuteChannel") else t("sidebar.serverMenu.muteChannel"), separatorAfter = true) { Unread.setMuted(ch.id, !muted) }
    }
    if (Nexa.servers.canI(st, P.MANAGE_CHANNELS, ch.id)) {
        items += MenuItem.Action(t("sidebar.serverSidebar.editChannel")) { openChannelSettings(ch.id) }
        items += MenuItem.Action(t("sidebar.serverMenu.deleteChannel"), danger = true) { confirmDeleteChannel(ch) }
    }
    if (Nexa.settings.developerMode) items += MenuItem.Action(t("sidebar.dmMenu.copyChannelId")) { copyText(ch.id) }
    return items
}


/** A category just opened in the sidebar: the channels it brought back, in order, and when. */
private data class Opened(val category: String, val rows: Map<String, Int>, val at: Long)
