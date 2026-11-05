package fish.nexa.ui.moderation

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ModEvent
import fish.nexa.data.ModServer
import fish.nexa.data.ModServerRef
import fish.nexa.data.Moderation
import fish.nexa.data.StaffCase
import fish.nexa.media.Media
import fish.nexa.net.Emoji
import fish.nexa.net.WornTag
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ToggleRow
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sink
import fish.nexa.ui.describeError
import fish.nexa.ui.nexa.fieldLabel
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE = 48
private val SERVER_FIELDS = listOf("icon", "banner", "name", "description", "tag", "discovery")

/** Where a server stands on Discover, in the words of its row and its dialog. */
private fun discoverState(server: ModServer): String = when {
    server.discoveryHidden -> t("moderation.servers.discoveryHeld")
    server.discoverable -> t("moderation.servers.discoveryListed")
    else -> t("moderation.servers.discoveryNotListed")
}

/** A server the staff hid from Discover is shown again, after asking; it goes in the log, with no case. */
private fun showOnDiscover(server: ModServer, done: () -> Unit) {
    fish.nexa.ui.shell.confirm(t("moderation.servers.discoveryShowTitle", "name" to server.name), t("moderation.servers.discoveryShowBody"), t("moderation.servers.discoveryShow"), danger = false) {
        try {
            Moderation.showOnDiscover(server.id)
            Nexa.ui.toast(t("moderation.servers.discoveryShown", "name" to server.name), "success")
            done()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
        }
    }
}

/** Why the viewer cannot act on a server: its owner is out of their reach ("" when they can). */
fun serverBlockedText(b: String): String = when (b) {
    "self" -> t("moderation.servers.blockedSelf")
    "owner" -> t("moderation.servers.blockedOwner")
    "staff" -> t("moderation.servers.blockedStaff")
    else -> ""
}

/** A server's tag as its chip draws it. */
private fun tagOf(s: ModServer): WornTag? = s.tag?.let { WornTag(s.id, it.text, it.badge, it.color) }

/**
 * Every server on Nexa, as everyone sees it from outside (ServersTab): its
 * banner, icon, name and description, who owns it and how big it is. Nothing
 * said inside a server is here, or anywhere in the panel.
 */
@Composable
fun ServersTab(startListing: String = "") {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("newest") }
    // Discovery is a view of the servers: those on it, listed or held ("" is every server)
    var listing by remember(startListing) { mutableStateOf(startListing) }
    val discovery = listing.isNotEmpty()
    val servers = remember { mutableStateListOf<ModServer>() }
    var loaded by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf<Int?>(null) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf(0) }

    fun load(offset: Int) {
        val id = ++request
        loading = true
        failed = false
        scope.launch {
            try {
                val r = Moderation.servers(q, sort, offset, PAGE, if (discovery) listing else null)
                if (id != request) return@launch
                if (offset == 0) servers.clear()
                servers.addAll(r.servers.filter { x -> servers.none { it.id == x.id } })
                total = r.total
                more = r.more
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (id == request) failed = true
            } finally {
                if (id == request) loading = false
            }
        }
    }
    fun refresh(id: String) {
        scope.launch {
            try {
                val r = Moderation.server(id)
                val i = servers.indexOfFirst { it.id == id }
                if (i >= 0) servers[i] = r.server
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                servers.removeAll { it.id == id }
            }
        }
    }
    val list = rememberLazyListState()
    // A new search, filter or order starts the list again, from its top (typing
    // waits a moment). The old order's pages stop at once, so none lands in the new one.
    LaunchedEffect(q, sort, discovery, listing) {
        request++
        more = false
        if (q.isNotEmpty()) delay(250)
        loaded = false
        servers.clear()
        list.scrollToItem(0)
        load(0)
    }
    LaunchedEffect(Unit) {
        Moderation.events.collect { e ->
            val sid = (e as? ModEvent.Case)?.case?.takeIf { it.action == "server_reset" }?.details?.let { (it["server_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
            if (sid != null && servers.any { it.id == sid }) refresh(sid)
        }
    }
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, more, loading, failed) { if (nearEnd && more && !loading && !failed && loaded) load(servers.size) }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("tools") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SearchField(q, { q = it }, t("moderation.servers.search"), Modifier.weight(1f))
                    total?.let { Txt(t("moderation.servers.total", "count" to it), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = Nx.colors.textMuted, maxLines = 1) }
                }
                SortField(listOf("" to t("moderation.servers.everyServer"), "all" to t("moderation.servers.onDiscover"), "listed" to t("moderation.servers.discoveryListed"), "held" to t("moderation.servers.discoveryHeld")), listing) { listing = it }
                SortField(listOf("newest" to t("moderation.sort.newest"), "oldest" to t("moderation.sort.oldest"), "members" to t("moderation.servers.sortMembers"), "name" to t("moderation.sort.name")), sort) { sort = it }
            }
        }
        when {
            !loaded && !failed -> item("loading") { LoadingRow() }
            failed && servers.isEmpty() -> item("failed") { LoadFailed({ load(0) }, loading) }
            servers.isEmpty() -> item("empty") {
                EmptyState("Server", if (q.isNotEmpty()) t("moderation.noMatches") else t("moderation.servers.none")) {
                    if (q.isNotEmpty()) NxButton(t("moderation.clearFilters"), { q = "" }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                }
            }
            else -> items(servers, key = { it.id }) { s -> ServerCard(s, discovery) { if (discovery && s.discoveryHidden) showOnDiscover(s) { load(0) } else openModerateServer(s, if (discovery) listOf("discovery") else null) { if (discovery) load(0) else refresh(s.id) } } }
        }
        if (loading && loaded) item("more") { LoadingRow() }
    }
}

/** `.card` for a server: its banner, its icon on it, its name, owner, description, size, and what can be done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(s: ModServer, discovery: Boolean = false, onModerate: () -> Unit) {
    val c = Nx.colors
    val owner = person(s.ownerId)
    // taking a server down, or holding it from Discover, is an admin's
    val blocked = serverBlockedText(s.blocked).ifEmpty { if (fish.nexa.data.StaffRanks.can(fish.nexa.data.StaffCan.TAKE_DOWN_SERVERS)) "" else t("staff.adminOnlyServers") }
    Box(Modifier.fillMaxWidth().modCard(c).pressable(remember { MutableInteractionSource() }) { goPanel("servers/${s.id}") }.semantics { contentDescription = s.name }) {
        Column(Modifier.fillMaxWidth()) {
            CardBanner(s.bannerId, profileBrush(null, null, s.id), 88.dp)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 44.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt(s.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(17.5f), fontWeight = FontWeight(690)), color = c.textStrong, maxLines = 1)
                if (s.nsfw) Txt(t("nsfw.label"), style = Nx.type.hint, color = c.warning)
                if (discovery) Txt(discoverState(s), style = Nx.type.hint, color = c.textMuted)
                OwnerChip(s.ownerId, t("moderation.servers.ownedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), ownerName = nameOf(owner)) { goPanel("users/${s.ownerId}") }
                Txt(
                    if (s.description.isNotEmpty()) previewText(s.description) else t("moderation.servers.noDescription"),
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(18.9f), fontStyle = if (s.description.isEmpty()) FontStyle.Italic else FontStyle.Normal),
                    color = if (s.description.isEmpty()) c.textPlaceholder else c.textSubtle, maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp).heightIn(min = 37.8.dp),
                )
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetaItem("Users", t("moderation.servers.members", "count" to s.memberCount))
                    MetaItem("Hash", t("moderation.servers.channels", "count" to s.channelCount))
                    MetaItem("CalendarDays", Time.shortDate(s.createdAt))
                }
                Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxButton(if (discovery) t(if (s.discoveryHidden) "moderation.servers.discoveryShow" else "moderation.servers.discoveryHide") else t("moderation.moderate"), onModerate, Modifier.weight(1f), icon = "Gavel", size = ButtonSize.TOUCH, enabled = blocked.isEmpty())
                    NxButton(t("moderation.servers.open"), { goPanel("servers/${s.id}") }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = "Server", size = ButtonSize.TOUCH)
                }
                if (blocked.isNotEmpty()) Txt(blocked, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Box(Modifier.padding(start = 14.dp, top = 44.dp).background(cardFill(c), RoundedCornerShape(30)).padding(5.dp)) { ServerIcon(s.id, s.iconId, s.name, 64.dp) }
    }
}

/** `.ownerChip` and `.whose`: whose something is, as a chip that opens them. */
@Composable
fun OwnerChip(userId: String, text: String, ownerName: String? = null, onClick: () -> Unit) {
    val c = Nx.colors
    val p = person(userId)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .padding(top = 2.dp)
            .background(c.textStrong.copy(alpha = if (pressed) 0.10f else 0.05f), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(interaction, onClick = onClick)
            .padding(start = 4.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(userId, nameOf(p), p?.avatarId, 18.dp)
        fish.nexa.ui.chat.PersonText(userId, text, ownerName ?: nameOf(p), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = if (pressed) c.textStrong else c.textSubtle, maxLines = 1)
    }
}

/**
 * One server, for the staff (ServerPage.tsx): what it shows everyone from
 * outside, with a way to take down any part of it, who owns it, its emoji
 * and packs, and the cases about it. What is said inside it is never shown.
 */
@Composable
fun StaffServerPage(serverId: String) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var data by remember(serverId) { mutableStateOf<Moderation.OneServer?>(null) }
    var failed by remember(serverId) { mutableStateOf(false) }
    val picked = remember(serverId) { mutableStateListOf<String>() }

    fun load() {
        scope.launch {
            try {
                val r = Moderation.server(serverId)
                data = r
                picked.retainAll { id -> r.emojis.any { it.id == id } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }
    LaunchedEffect(serverId) { load() }
    LaunchedEffect(serverId) {
        Moderation.events.collect { e ->
            val cs = (e as? ModEvent.Case)?.case ?: return@collect
            val sid = (cs.details["server_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if ((cs.action == "server_reset" || cs.action == "expression_takedown") && sid == serverId) load()
        }
    }
    val d = data
    if (failed) {
        EmptyState(null, null, t("moderation.servers.missing")) { NxButton(t("moderation.servers.back"), { goPanel("servers", replace = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM) }
        return
    }
    if (d == null) {
        LoadingRow()
        return
    }
    val server = d.server
    val owner = person(server.ownerId)
    val blocked = serverBlockedText(server.blocked)
    // a server's own parts are an admin's to take down; its emoji a mod's
    val serverBlocked = blocked.ifEmpty { if (fish.nexa.data.StaffRanks.can(fish.nexa.data.StaffCan.TAKE_DOWN_SERVERS)) "" else t("staff.adminOnlyServers") }
    val density = LocalDensity.current.density
    Column(Modifier.fillMaxSize()) {
        PageHead(
            back = t(if(ReportNavigation.origin != null) "reports.returnToReport" else "moderation.servers.back"),
            onBack = { panelBack("servers") },
            leading = { ServerIcon(server.id, server.iconId, server.name, 48.dp) },
            title = server.name,
            sub = listOf(t("moderation.servers.members", "count" to server.memberCount), t("moderation.servers.channels", "count" to server.channelCount), t("moderation.casesCount", "count" to d.cases.size)).joinToString(" · "),
            actions = { NxButton(t("moderation.moderate"), { openModerateServer(server, null) { load() } }, size = ButtonSize.TOUCH, icon = "Gavel", enabled = serverBlocked.isEmpty()) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("face") {
                    Box(Modifier.fillMaxWidth().modCard(c)) {
                        Column(Modifier.fillMaxWidth()) {
                            CardBanner(server.bannerId, profileBrush(null, null, server.id), 120.dp)
                            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.pullUp(48.dp).background(cardFill(c), RoundedCornerShape(30)).padding(6.dp)) { ServerIcon(server.id, server.iconId, server.name, 84.dp) }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Txt(server.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(21f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 2)
                                    Txt(t("moderation.servers.members", "count" to server.memberCount), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                                }
                                FaceSection(t("moderation.servers.description")) {
                                    if (server.description.isNotEmpty()) Txt(server.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = c.text)
                                    else Txt(t("moderation.servers.noDescription"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontStyle = FontStyle.Italic), color = c.textMuted)
                                }
                                FaceSection(t("moderation.servers.created")) { Txt(Time.shortDate(server.createdAt), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) }
                            }
                        }
                        FaceCaption(Modifier.align(Alignment.TopStart))
                    }
                }
                item("public") {
                    SheetPanel(t("moderation.sheet.publicTitle"), serverBlocked.ifEmpty { t("moderation.servers.publicHint") }) {
                        for (f in SERVER_FIELDS) {
                            val empty = when (f) {
                                "icon" -> server.iconId == null
                                "banner" -> server.bannerId == null
                                "description" -> server.description.isEmpty()
                                "tag" -> server.tag == null
                                // one hidden by staff is shown again from here; one its owner lists is hidden through the dialog
                                "discovery" -> !server.discoverable && !server.discoveryHidden
                                else -> false
                            }
                            val (action, icon) = when (f) {
                                "icon", "banner", "tag" -> t("moderation.sheet.remove") to (if (f == "tag") "Eraser" else "ImageOff")
                                "description" -> t("moderation.sheet.clear") to "Eraser"
                                "discovery" -> if (server.discoveryHidden) t("moderation.servers.discoveryShow") to "Eye" else t("moderation.servers.discoveryHide") to "Compass"
                                else -> t("moderation.sheet.reset") to "Eraser"
                            }
                            val act = if (f == "discovery" && server.discoveryHidden) ({ showOnDiscover(server) { load() } }) else ({ openModerateServer(server, listOf(f)) { load() } })
                            FieldRowView(fieldLabel(f), empty, { NxButton(action, act, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = icon, enabled = serverBlocked.isEmpty() && !empty) }) {
                                when {
                                    f == "icon" && server.iconId != null -> ServerIcon(server.id, server.iconId, server.name, 36.dp)
                                    f == "banner" && server.bannerId != null -> Box(Modifier.width(120.dp).aspectRatio(3f).clip(Radii.Xs).ring(Radii.Xs, c.borderSubtle)) {
                                        AsyncImage(Media.bannerUrl(server.bannerId, 240, density), fieldLabel("banner"), imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    }
                                    f == "tag" && server.tag != null -> fish.nexa.ui.chat.ServerTagChip(tagOf(server)!!, small = false, open = false)
                                    f == "name" -> FieldValue(server.name)
                                    f == "description" && server.description.isNotEmpty() -> FieldValue(previewText(server.description))
                                    f == "discovery" -> FieldValue(discoverState(server), empty)
                                    else -> FieldValue(t("moderation.sheet.notSet"), true)
                                }
                            }
                        }
                    }
                }
                item("owner") {
                    SheetPanel(t("moderation.servers.ownerTitle")) {
                        OpenRow(nameOf(owner), owner?.let { "@" + it.username }, { goPanel("users/${server.ownerId}") }, personId = server.ownerId) { Avatar(server.ownerId, nameOf(owner), owner?.avatarId, 36.dp) }
                    }
                }
                item("emoji") {
                    SheetPanel(t("moderation.packs.serverEmojiTitle", "count" to d.emojis.size), if (d.emojis.isNotEmpty()) blocked.ifEmpty { t("moderation.packs.serverEmojiHint") } else null) {
                        if (d.emojis.isEmpty()) PanelEmpty("Smile", t("moderation.packs.noServerEmoji"))
                        else Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                            TileGrid(d.emojis.size, minWidth = 76.dp, gap = 8.dp) { i ->
                                val e = d.emojis[i]
                                PickTile(":${e.name}:", e.id in picked, blocked.isEmpty(), emoji = true, onToggle = { if (e.id in picked) picked.remove(e.id) else picked.add(e.id) }) {
                                    AsyncImage(Media.emojiUrl(e.blobId, 48, density, still = true), ":${e.name}:", imageLoader = Media.loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                            }
                            if (picked.isNotEmpty()) PickBar(picked.size, { picked.clear() }) {
                                val chosen = d.emojis.filter { it.id in picked }
                                openModerateEmoji(ModServerRef(server.id, server.name, server.iconId, server.ownerId), chosen) {
                                    picked.clear()
                                    load()
                                }
                            }
                        }
                    }
                }
                if (d.packs.isNotEmpty()) item("packs") { SheetPanel(t("moderation.packs.serverPacksTitle", "count" to d.packs.size)) { PackRows(d.packs) } }
                item("cases") {
                    SheetPanel(t("moderation.servers.casesTitle")) {
                        if (d.cases.isEmpty()) PanelEmpty("FolderOpen", t("moderation.servers.noCases"))
                        else for (cs in d.cases) CaseOpenRow(cs, if (cs.action == "expression_takedown") expressionSummary(cs.details) else previewText(cs.reason))
                    }
                }
                item("facts") {
                    SheetPanel(t("moderation.servers.factsTitle")) {
                        FactsList(listOf(
                            Triple("CalendarDays", t("moderation.servers.created")) { FactText(Time.shortDate(server.createdAt)) },
                            Triple("Users", t("moderation.servers.membersLabel")) { FactText(server.memberCount.toString()) },
                            Triple("Hash", t("moderation.servers.channelsLabel")) { FactText(server.channelCount.toString()) },
                            Triple(null, t("moderation.servers.serverId")) { CopyId(server.id, t("moderation.servers.copyId")) },
                        ))
                    }
                }
            }
        }
    }
}

/** `.panelEmpty`: a panel with nothing in it. */
@Composable
fun PanelEmpty(icon: String, text: String) {
    val c = Nx.colors
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, c.textMuted, size = 16.dp)
        Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
    }
}

/** A case as a row that opens it, in a server's or a pack's page. */
@Composable
fun CaseOpenRow(cs: StaffCase, meta: String) {
    OpenRow(caseTitle(cs), meta, { goPanel("cases/${cs.id}") }, trailing = Time.relative(cs.createdAt)) { CaseIcon(cs.action) }
}

/** Tiles in as many columns of at least [minWidth] as fit, sharing the width (`repeat(auto-fill, minmax(...))`). */
@Composable
fun TileGrid(count: Int, minWidth: Dp, gap: Dp, tile: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = maxOf(1, ((maxWidth + gap) / (minWidth + gap)).toInt())
        val w = (maxWidth - gap * (cols - 1)) / cols
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until (count + cols - 1) / cols) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (col in 0 until cols) {
                    val i = row * cols + col
                    Box(Modifier.width(w)) { if (i < count) tile(i) }
                }
            }
        }
    }
}

/** `.tile`: a sticker or an emoji to tick, its picture and name; ticked, it is lit in the colour of danger. */
@Composable
fun PickTile(name: String, picked: Boolean, enabled: Boolean, emoji: Boolean, onToggle: () -> Unit, picture: @Composable () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Md
    Box(Modifier.fillMaxWidth()) {
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .sink(pressed && enabled)
            .then(if (picked) Modifier.ring(shape, c.danger.copy(alpha = 0.8f), 2.dp).background(mixSrgb(c.bgSurface, c.danger, 0.12f), shape) else Modifier.ring(shape, if (pressed) c.borderStrong else c.borderSubtle).background(mixOklab(c.bgSurface, c.bgRaised, 0.4f), shape))
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(interaction, enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .semantics {
                contentDescription = name
                toggleableState = ToggleableState(picked)
            }
            .padding(start = 8.dp, end = 8.dp, top = if (emoji) 26.dp else 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(if (emoji) 40.dp else 72.dp), contentAlignment = Alignment.Center) { picture() }
        Txt(name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(if (emoji) 11.5f else 12.5f), fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1)
    }
    // the tick, in the corner
    Box(Modifier.padding(7.dp).size(17.dp).background(if (picked) c.danger else Color.Transparent, RoundedCornerShape(4.dp)).ring(RoundedCornerShape(4.dp), if (picked) c.danger else c.borderStrong, 1.5.dp), contentAlignment = Alignment.Center) {
        if (picked) Icon("Check", Color.White, size = 12.dp, stroke = 3f)
    }
    }
}

/** `.pickBar`: what is ticked, and the one thing to do with it. */
@Composable
fun PickBar(count: Int, onClear: () -> Unit, onTake: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.panelEdge).background(c.bgFloating, shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(t("moderation.packs.pickedCount", "count" to count), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong)
        NxButton(t("moderation.packs.clearPicked"), onClear, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH)
        Spacer(Modifier.weight(1f))
        NxButton(t("moderation.packs.takeDownPicked"), onTake, kind = ButtonKind.DANGER, size = ButtonSize.TOUCH, icon = "Gavel")
    }
}

/** What the server dialog holds while it is open. */
private class ServerState(preset: List<String>?) {
    var fields by mutableStateOf(preset ?: emptyList())
    var reason by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Taking down parts of what a server shows everyone (ModerateServerModal):
 * its icon, banner, name, description or tag. The reason goes to the
 * server's owner, word for word, with the moderator's name, and opens a case
 * they can answer in.
 */
fun openModerateServer(server: ModServer, preset: List<String>?, onDone: (StaffCase) -> Unit) {
    // Discover can only be taken from a server listed there and not hidden already
    val empty = mapOf("icon" to (server.iconId == null), "banner" to (server.bannerId == null), "name" to false, "description" to server.description.isEmpty(), "tag" to (server.tag == null), "discovery" to (!server.discoverable || server.discoveryHidden))
    val s = ServerState(preset?.filter { empty[it] != true })
    fun ready() = s.fields.isNotEmpty() && reasonOk(s.reason)
    fun submit(close: () -> Unit) {
        if (!ready() || s.busy) return
        s.busy = true
        s.error = null
        Nexa.scope.launch {
            try {
                val cs = Moderation.actOnServer(server.id, SERVER_FIELDS.filter { it in s.fields }, s.reason.trim())
                Nexa.ui.toast(t("moderation.servers.done", "number" to cs.number, "name" to server.name), "success")
                onDone(cs)
                close()
            } catch (e: Exception) {
                s.error = describeError(e, t("moderation.failed"))
            } finally {
                s.busy = false
            }
        }
    }
    openModSheet(
        "moderate-server",
        top = { close ->
            val owner = ownerName(server.ownerId)
            ModSheetTop(t("moderation.servers.moderateTitle"), close, leading = { ServerIcon(server.id, server.iconId, server.name, 40.dp) }) {
                fish.nexa.ui.chat.PersonText(server.ownerId, server.name + " · " + t("moderation.servers.ownedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), owner, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = Nx.colors.textMuted, maxLines = 1)
            }
        },
        foot = { close ->
            ModSheetFoot(t("moderation.servers.notifies", "name" to fish.nexa.ui.chat.PERSON_SLOT), close, personId = server.ownerId, personName = ownerName(server.ownerId)) {
                NxButton(t("moderation.servers.submit", "count" to maxOf(1, s.fields.size)), { submit(close) }, enabled = ready(), loading = s.busy)
            }
        },
    ) {
        val c = Nx.colors
        val density = LocalDensity.current.density
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(t("moderation.servers.whatToTake"))
            for (f in SERVER_FIELDS) {
                val none = empty[f] == true
                PickBox(f in s.fields, !none, { s.fields = if (f in s.fields) s.fields - f else s.fields + f }, label = fieldLabel(f)) {
                    when (f) {
                        "icon" -> if (server.iconId != null) ServerIcon(server.id, server.iconId, server.name, 32.dp)
                        "banner" -> BannerChip(server.bannerId, profileBrush(null, null, server.id))
                        "tag" -> tagOf(server)?.let { fish.nexa.ui.chat.ServerTagChip(it, small = true, open = false) }
                        "discovery" -> Icon("Compass", c.textSubtle, size = 22.dp)
                    }
                    PickText(
                        fieldLabel(f),
                        when {
                            f == "discovery" -> discoverState(server)
                            none -> t("moderation.form.none")
                            f == "name" -> server.name
                            f == "description" -> previewText(server.description)
                            f == "tag" -> server.tag?.text ?: ""
                            f == "icon" -> t("moderation.servers.iconSet")
                            else -> t("moderation.form.bannerSet")
                        },
                    )
                }
            }
            NoteBox(t("moderation.servers.takeNote"))
            if ("discovery" in s.fields) NoteBox(t("moderation.servers.discoveryNote"))
        }
        ReasonSection(s.reason, { s.reason = it }, t("moderation.servers.reasonPlaceholder"), t("moderation.servers.reasonVisible", "name" to fish.nexa.ui.chat.PERSON_SLOT), quickReasons(t("moderation.servers.quickScam")), personId = server.ownerId, personName = ownerName(server.ownerId))
        s.error?.let { e -> DangerBox { Txt(e, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) } }
    }
}

/** The pictures a takedown names, small, in its dialog (StickerThumbs). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Thumbs(items: List<Pair<String, @Composable () -> Unit>>, max: Int = 12) {
    val c = Nx.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, pic) in items.take(max)) Box(Modifier.size(44.dp).clip(Radii.Xs).background(c.bgInput).semantics { contentDescription = name }, contentAlignment = Alignment.Center) { pic() }
        if (items.size > max) Box(Modifier.size(44.dp).clip(Radii.Xs).background(c.bgActive), contentAlignment = Alignment.Center) {
            Txt("+${items.size - max}", style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textSubtle)
        }
    }
}

private class EmojiState {
    var block by mutableStateOf(true)
    var reason by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Taking down some of a server's emoji (ModerateEmojiModal). The server's
 * owner answers for them: the reason goes to them, word for word with the
 * moderator's name, and opens a case they can answer in.
 */
fun openModerateEmoji(server: ModServerRef, emojis: List<Emoji>, onDone: (StaffCase) -> Unit) {
    val s = EmojiState()
    fun ready() = emojis.isNotEmpty() && reasonOk(s.reason)
    fun submit(close: () -> Unit) {
        if (!ready() || s.busy) return
        s.busy = true
        s.error = null
        Nexa.scope.launch {
            try {
                val cs = Moderation.actOnEmojis(server.id, emojis.map { it.id }, s.block, s.reason.trim())
                Nexa.ui.toast(t("moderation.servers.done", "number" to cs.number, "name" to server.name), "success")
                onDone(cs)
                close()
            } catch (e: Exception) {
                s.error = describeError(e, t("moderation.failed"))
            } finally {
                s.busy = false
            }
        }
    }
    openModSheet(
        "moderate-emoji",
        top = { close ->
            ModSheetTop(t("moderation.packs.moderateEmoji"), close, leading = { ServerIcon(server.id, server.iconId, server.name, 40.dp) }) {
                fish.nexa.ui.chat.PersonText(server.ownerId, server.name + " · " + t("moderation.servers.ownedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), ownerName(server.ownerId), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = Nx.colors.textMuted, maxLines = 1)
            }
        },
        foot = { close ->
            ModSheetFoot(t("moderation.servers.notifies", "name" to fish.nexa.ui.chat.PERSON_SLOT), close, personId = server.ownerId, personName = ownerName(server.ownerId)) {
                NxButton(t("moderation.packs.submitEmoji", "count" to emojis.size), { submit(close) }, enabled = ready(), loading = s.busy)
            }
        },
    ) {
        val c = Nx.colors
        val density = LocalDensity.current.density
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(t("moderation.servers.whatToTake"))
            Thumbs(emojis.map { e -> ":${e.name}:" to @Composable { AsyncImage(Media.emojiUrl(e.blobId, 48, density, still = true), ":${e.name}:", imageLoader = Media.loader, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp)) } }, max = 24)
            NoteBox(t("moderation.packs.emojiNote"))
        }
        ToggleRow(s.block, { s.block = it }, t("moderation.packs.blockPictures"), t("moderation.packs.blockPicturesHint"), rule = false)
        ReasonSection(s.reason, { s.reason = it }, t("moderation.packs.reasonPlaceholder"), t("moderation.servers.reasonVisible", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(t("moderation.quick.hateful"), t("moderation.quick.explicit"), t("moderation.quick.harassment"), t("moderation.quick.impersonation"), t("moderation.packs.quickCopyright")), personId = server.ownerId, personName = ownerName(server.ownerId))
        s.error?.let { e -> DangerBox { Txt(e, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) } }
    }
}
