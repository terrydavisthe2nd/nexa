package fish.nexa.ui.moderation

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ModEvent
import fish.nexa.data.ModPack
import fish.nexa.data.ModServerRef
import fish.nexa.data.ModUpload
import fish.nexa.data.Moderation
import fish.nexa.data.StaffCase
import fish.nexa.media.Media
import fish.nexa.net.Emoji
import fish.nexa.net.Sticker
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.chat.StickerMedia
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ToggleRow
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.dialogs.promptText
import fish.nexa.ui.nexa.packFieldLabel
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private const val PAGE = 48
private val PACK_FIELDS = listOf("name", "description", "public")

/** Why the viewer cannot act on a pack, in a sentence ("" when they can). */
fun packBlockedText(b: String): String = when (b) {
    "self" -> t("moderation.packs.blockedSelf")
    "owner" -> t("moderation.packs.blockedOwner")
    "staff" -> t("moderation.packs.blockedStaff")
    "" -> ""
    else -> t("moderation.packs.blockedOther")
}

/** One sticker or emoji as the panel draws it, fetched at [size] and drawn [size] square. */
@Composable
fun ExpressionPic(kind: String, blobId: String, format: String?, name: String, size: Dp) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    if (kind == "emoji") AsyncImage(Media.emojiUrl(blobId, size.value.toInt(), density, still = true), name, imageLoader = Media.loader, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    else StickerMedia(format, Media.stickerUrl(blobId, size.value.toInt(), density), name, size)
}

/** A picture in a cell: at most [share] of its width and [max] tall, as the web's `max-width` and `max-height` hold it. */
@Composable
private fun CellPic(kind: String, s: Sticker, share: Float, max: Dp) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ExpressionPic(kind, s.blobId, s.format, s.name, minOf(maxWidth * share, max, maxHeight))
    }
}

/** A pack's first stickers, in place of a banner: a card's, or the taller one on its page (PackMosaic). */
@Composable
fun PackMosaic(pack: ModPack, tall: Boolean = false) {
    val c = Nx.colors
    val first = pack.stickers.take(4)
    Row(
        Modifier.fillMaxWidth().height(if (tall) 132.dp else 96.dp).background(mixOklab(c.bgInput, c.bgBase, 0.3f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val cell = Modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(c.bgSurface.copy(alpha = 0.6f))
        if (first.isEmpty()) Box(cell.weight(1f), contentAlignment = Alignment.Center) { Icon("Sticker", c.textMuted, size = 28.dp) }
        for (s in first) Box(cell.weight(1f), contentAlignment = Alignment.Center) {
            CellPic(if (pack.emoji) "emoji" else "sticker", s, 0.82f, if (pack.emoji) 44.dp else if (tall) 112.dp else 82.dp)
        }
    }
}

/** The tags in a pack's corner: an emoji pack, public, or kept private by the staff. */
@Composable
fun PackFlags(pack: ModPack, modifier: Modifier) {
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pack.emoji) Flag(t("moderation.packs.emojiPack"))
        if (pack.discoverBlocked) Flag(t("moderation.packs.keptPrivate"), "warn") else if (pack.public) Flag(t("moderation.packs.public"))
    }
}

/** Whose a pack is: the person, or the server and so its owner (PackWhose). */
@Composable
fun PackWhose(pack: ModPack, servers: Map<String, ModServerRef>) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    val sid = pack.serverId
    val srv = sid?.let { servers[it] }
    val owner = person(pack.ownerId)
    Row(
        Modifier.background(if (pressed) fish.nexa.ui.theme.mixSrgb(c.bgActive, c.text, 0.1f) else c.bgActive, shape).clip(shape)
            .pressable(interaction) { if (sid != null) goPanel("servers/$sid") else goPanel("users/${pack.ownerId}") }
            .padding(start = 4.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (sid != null) {
            ServerIcon(sid, srv?.iconId, srv?.name ?: "", 18.dp)
            Txt(t("moderation.packs.inServer", "name" to (srv?.name ?: t("moderation.packs.unknownServer"))), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1)
        } else {
            fish.nexa.ui.components.Avatar(pack.ownerId, nameOf(owner), owner?.avatarId, 18.dp)
            fish.nexa.ui.chat.PersonText(pack.ownerId, t("moderation.servers.ownedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), nameOf(owner), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1)
        }
    }
}

/** A small picture of a pack for a row, or a bigger one for its page's header: its cover, or its first sticker. */
@Composable
fun PackCover(pack: ModPack, big: Boolean) {
    val c = Nx.colors
    val cover = pack.stickers.firstOrNull { it.id == pack.coverStickerId } ?: pack.stickers.firstOrNull()
    val size = if (big) 48.dp else 36.dp
    Box(Modifier.size(size).clip(if (big) Radii.Sm else Radii.Xs).background(c.bgActive), contentAlignment = Alignment.Center) {
        if (cover != null) ExpressionPic(if (pack.emoji) "emoji" else "sticker", cover.blobId, cover.format, cover.name, size)
        else Icon("Sticker", c.textMuted, size = if (big) 22.dp else 18.dp)
    }
}

/**
 * Stickers and emoji, for the staff (ExpressionsTab): every pack on Nexa,
 * anyone's or any server's, public or not, and the newest uploads as a wall
 * of pictures. A server's emoji are taken down from its page in the Servers
 * tab, or from here when they turn up among the newest.
 */
@Composable
fun ExpressionsTab(uploads: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (uploads) UploadWall() else PackList()
        }
    }
}

@Composable
private fun ViewChips(uploads: Boolean) {
    ChipRow(t("moderation.tabs.expressions"), Modifier.padding(bottom = 14.dp)) {
        ModChip(t("moderation.packs.viewPacks"), !uploads, { if (uploads) goPanel("expressions", replace = true) }, icon = "Sticker")
        ModChip(t("moderation.packs.viewUploads"), uploads, { if (!uploads) goPanel("expressions/uploads", replace = true) }, icon = "Smile")
    }
}

@Composable
private fun PackList() {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var scopeOf by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("newest") }
    val packs = remember { mutableStateListOf<ModPack>() }
    val servers = remember { mutableStateMapOf<String, ModServerRef>() }
    var loaded by remember { mutableStateOf(false) }
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
                val r = Moderation.packs(q, scopeOf, sort, offset, PAGE)
                if (id != request) return@launch
                if (offset == 0) packs.clear()
                packs.addAll(r.packs.filter { x -> packs.none { it.id == x.id } })
                servers.putAll(r.servers)
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
                val r = Moderation.pack(id)
                val i = packs.indexOfFirst { it.id == id }
                if (i >= 0) packs[i] = r.pack
                servers.putAll(r.servers)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                packs.removeAll { it.id == id }
            }
        }
    }
    val list = rememberLazyListState()
    // A new search, filter or order starts the list again, from its top (typing
    // waits a moment). The old order's pages stop at once, so none lands in the new one.
    LaunchedEffect(q, scopeOf, sort) {
        request++
        more = false
        if (q.isNotEmpty()) delay(250)
        loaded = false
        packs.clear()
        list.scrollToItem(0)
        load(0)
    }
    LaunchedEffect(Unit) {
        Moderation.events.collect { e ->
            val pid = (e as? ModEvent.Case)?.case?.takeIf { it.action == "expression_takedown" }?.details?.let { (it["pack_id"] as? JsonPrimitive)?.content }
            if (pid != null && packs.any { it.id == pid }) refresh(pid)
        }
    }
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, more, loading, failed) { if (nearEnd && more && !loading && !failed && loaded) load(packs.size) }

    val scopes = listOf("all" to t("moderation.packs.scopeAll"), "discover" to t("moderation.packs.scopePublic"), "personal" to t("moderation.packs.scopePersonal"), "server" to t("moderation.packs.scopeServer"), "hidden" to t("moderation.packs.scopeKeptPrivate"))
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("tools") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ViewChips(uploads = false)
                SearchField(q, { q = it }, t("moderation.packs.search"), Modifier.fillMaxWidth())
                ChipRow(t("moderation.packs.scopeLabel")) { for ((id, label) in scopes) ModChip(label, scopeOf == id, { scopeOf = id }) }
                SortField(listOf("newest" to t("moderation.sort.newest"), "oldest" to t("moderation.sort.oldest"), "installs" to t("moderation.packs.sortInstalls"), "name" to t("moderation.packs.sortName")), sort) { sort = it }
            }
        }
        when {
            !loaded && !failed -> item("loading") { LoadingRow() }
            failed && packs.isEmpty() -> item("failed") { LoadFailed({ load(0) }, loading) }
            packs.isEmpty() -> item("empty") {
                EmptyState("Sticker", if (q.isNotEmpty() || scopeOf != "all") t("moderation.noMatches") else t("moderation.packs.none")) {
                    if (q.isNotEmpty() || scopeOf != "all") NxButton(t("moderation.clearFilters"), { q = ""; scopeOf = "all" }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                }
            }
            else -> items(packs, key = { it.id }) { pk ->
                PackCard(pk, servers) { openModeratePack(pk, pk.serverId?.let { servers[it] }, null) { _, _ -> refresh(pk.id) } }
            }
        }
        if (loading && loaded) item("more") { LoadingRow() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackCard(pack: ModPack, servers: Map<String, ModServerRef>, onModerate: () -> Unit) {
    val c = Nx.colors
    val blocked = packBlockedText(pack.blocked)
    Box(Modifier.fillMaxWidth().modCard(c).pressable(remember { MutableInteractionSource() }) { goPanel("expressions/packs/${pack.id}") }.semantics { contentDescription = pack.name }) {
        Column(Modifier.fillMaxWidth()) {
            PackMosaic(pack)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt(pack.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(17.5f), fontWeight = FontWeight(690)), color = c.textStrong, maxLines = 1)
                PackWhose(pack, servers)
                Txt(
                    if (pack.description.isNotEmpty()) previewText(pack.description) else t("moderation.servers.noDescription"),
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(18.9f), fontStyle = if (pack.description.isEmpty()) FontStyle.Italic else FontStyle.Normal),
                    color = if (pack.description.isEmpty()) c.textPlaceholder else c.textSubtle, maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp).heightIn(min = 37.8.dp),
                )
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetaItem(if (pack.emoji) "Smile" else "Sticker", if (pack.emoji) t("moderation.packs.emojiCount", "count" to pack.stickerCount) else t("moderation.packs.stickerCount", "count" to pack.stickerCount))
                    MetaItem("Download", t("moderation.packs.installs", "count" to pack.installs))
                    MetaItem("CalendarDays", Time.shortDate(pack.createdAt))
                }
                Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxButton(t("moderation.moderate"), onModerate, Modifier.weight(1f), icon = "Gavel", size = ButtonSize.TOUCH, enabled = blocked.isEmpty())
                    NxButton(t("moderation.servers.open"), { goPanel("expressions/packs/${pack.id}") }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = "Sticker", size = ButtonSize.TOUCH)
                }
                if (blocked.isNotEmpty()) Txt(blocked, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        PackFlags(pack, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The newest stickers and emoji on Nexa, newest first, as a wall to look
 * over (UploadWall). A tile opens where it lives (its pack, or its server);
 * its corner button takes it down there and then.
 */
@Composable
private fun UploadWall() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ModUpload>() }
    val servers = remember { mutableStateMapOf<String, ModServerRef>() }
    var loaded by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }

    fun load(after: ModUpload?) {
        loading = true
        failed = false
        scope.launch {
            try {
                val r = Moderation.uploads(after, 96)
                if (after == null) items.clear()
                items.addAll(r.uploads)
                servers.putAll(r.servers)
                more = r.more
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load(null) }
    fun gone(ids: List<String>) = items.removeAll { it.id in ids }

    /** Opens the takedown for one tile: its pack is read first, for the dialog's view of it. */
    fun takeDown(u: ModUpload) {
        if (u.kind == "emoji") {
            val sid = u.serverId ?: return
            openModerateEmoji(servers[sid] ?: ModServerRef(sid), listOf(Emoji(u.id, sid, u.name, u.blobId, u.animated, u.creatorId, u.createdAt))) { gone(listOf(u.id)) }
            return
        }
        val pid = u.packId ?: return
        opening = u.id
        scope.launch {
            try {
                val r = Moderation.pack(pid)
                servers.putAll(r.servers)
                openModeratePack(r.pack, r.pack.serverId?.let { servers[it] }, PackPreset(stickers = listOf(u.id))) { _, removed -> gone(removed) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // gone already: the wall shows what is left
                gone(listOf(u.id))
            } finally {
                opening = null
            }
        }
    }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd, more, loading, failed) { if (nearEnd && more && !loading && !failed && loaded) load(items.lastOrNull()) }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PanelPad) {
        item("tools") { ViewChips(uploads = true) }
        when {
            !loaded && !failed -> item("loading") { LoadingRow() }
            failed && items.isEmpty() -> item("failed") { LoadFailed({ load(null) }, loading) }
            items.isEmpty() -> item("empty") { EmptyState("Smile", t("moderation.packs.noUploads")) }
            else -> item("wall") {
                TileGrid(items.size, minWidth = 128.dp, gap = 10.dp) { i -> UploadTile(items[i], servers, opening == items[i].id) { takeDown(items[i]) } }
            }
        }
        if (loading && loaded) item("more") { LoadingRow() }
    }
}

@Composable
private fun UploadTile(u: ModUpload, servers: Map<String, ModServerRef>, opening: Boolean, onTake: () -> Unit) {
    val c = Nx.colors
    val srv = u.serverId?.let { servers[it] }
    val owner = person(u.ownerId)
    val where = when {
        u.kind == "emoji" -> t("moderation.packs.inServer", "name" to (srv?.name ?: t("moderation.packs.unknownServer")))
        srv != null -> t("moderation.packs.inPackOfServer", "pack" to (u.packName ?: ""), "server" to srv.name)
        else -> t("moderation.packs.inPackOf", "pack" to (u.packName ?: ""), "name" to nameOf(owner))
    }
    val open = { if (u.kind == "emoji") goPanel("servers/${u.serverId}") else goPanel("expressions/packs/${u.packId}") }
    val shape = Radii.Md
    Box(Modifier.fillMaxWidth().shadowLow(shape, c).ring(shape, c.panelEdge).background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).clip(shape)) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(112.dp).background(mixOklab(c.bgInput, c.bgBase, 0.3f)).pressable(remember { MutableInteractionSource() }, onClick = open).semantics { contentDescription = where }
                    .padding(if (u.kind == "emoji") 40.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) { ExpressionPic(u.kind, u.blobId, u.format, u.name, if (u.kind == "emoji") 32.dp else 92.dp) }
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(if (u.kind == "emoji") ":${u.name}:" else u.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
                Row(Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }, onClick = open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(if (u.kind == "emoji" || srv != null) "Server" else "Sticker", c.textMuted, size = 12.dp)
                    Txt(where, style = Nx.type.hint, color = c.textMuted, maxLines = 1)
                }
                Txt(Time.relative(u.createdAt), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f)), color = c.textMuted, maxLines = 1)
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { Flag(if (u.kind == "emoji") t("moderation.packs.kindEmoji") else t("moderation.packs.kindSticker")) }
        // take it down from here: over the picture's corner, always shown on a touch screen
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .pressable(remember { MutableInteractionSource() }, enabled = !opening, onClick = onTake)
                .semantics { contentDescription = t("moderation.packs.takeDownNamed", "name" to u.name) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(30.dp).background(Color(0x990A0614), CircleShape).ring(CircleShape, Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon("Gavel", Color.White, size = 15.dp) }
        }
    }
}

/** What the pack dialog opens with already chosen: stickers ticked on the pack's page, a part of the pack, or all of it. */
data class PackPreset(val stickers: List<String> = emptyList(), val fields: List<String> = emptyList(), val delete: Boolean = false)

/**
 * One pack, for the staff (PackPage.tsx): the pack as everyone sees it, every
 * sticker in it to tick and take down, its name, description and public
 * listing, who answers for it, and the cases about it.
 */
@Composable
fun PackPage(packId: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var data by remember(packId) { mutableStateOf<Moderation.OnePack?>(null) }
    var failed by remember(packId) { mutableStateOf(false) }
    val picked = remember(packId) { mutableStateListOf<String>() }

    fun load() {
        scope.launch {
            try {
                val r = Moderation.pack(packId)
                data = r
                // what was ticked and has gone since is no longer there to take
                picked.retainAll { id -> r.pack.stickers.any { it.id == id } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }
    LaunchedEffect(packId) { load() }
    LaunchedEffect(packId) {
        Moderation.events.collect { e ->
            val cs = (e as? ModEvent.Case)?.case ?: return@collect
            if (cs.action == "expression_takedown" && (cs.details["pack_id"] as? JsonPrimitive)?.content == packId) load()
        }
    }
    val d = data
    if (failed) {
        EmptyState(null, null, t("moderation.packs.missing")) { NxButton(t("moderation.packs.back"), { goPanel("expressions", replace = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM) }
        return
    }
    if (d == null) {
        LoadingRow()
        return
    }
    val pack = d.pack
    val server = pack.serverId?.let { d.servers[it] }
    val owner = person(pack.ownerId)
    val blocked = packBlockedText(pack.blocked)
    fun moderate(preset: PackPreset?) = openModeratePack(pack, server, preset) { cs, removed ->
        picked.removeAll(removed)
        // a pack taken down whole has no page any more
        if ((cs.details["deleted"] as? JsonPrimitive)?.content == "true") goPanel("expressions", replace = true) else load()
    }
    fun relist() {
        promptText(t("moderation.packs.allowPublicTitle"), t("moderation.form.reason"), placeholder = t("moderation.packs.allowPublicPlaceholder"), maxLength = 1000, confirmLabel = t("moderation.packs.allowPublic")) { reason ->
            if (reason.isBlank()) return@promptText
            Nexa.scope.launch {
                try {
                    Moderation.relistPack(pack.id, reason.trim())
                    Nexa.ui.toast(t("moderation.packs.allowedPublic", "name" to pack.name), "success")
                    load()
                } catch (e: Exception) {
                    Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHead(
            back = t(if(ReportNavigation.origin != null) "reports.returnToReport" else "moderation.packs.back"),
            onBack = { panelBack("expressions") },
            leading = { PackCover(pack, big = true) },
            title = pack.name,
            sub = listOf(
                if (pack.emoji) t("moderation.packs.emojiCount", "count" to pack.stickerCount) else t("moderation.packs.stickerCount", "count" to pack.stickerCount),
                t("moderation.packs.installs", "count" to pack.installs),
                t("moderation.casesCount", "count" to d.cases.size),
            ).joinToString(" · "),
            actions = { NxButton(t("moderation.moderate"), { moderate(null) }, size = ButtonSize.TOUCH, icon = "Gavel", enabled = blocked.isEmpty()) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("face") {
                Box(Modifier.fillMaxWidth().modCard(c)) {
                    Column(Modifier.fillMaxWidth()) {
                        PackMosaic(pack, tall = true)
                        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Txt(pack.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(21f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 2)
                                fish.nexa.ui.chat.PersonText(pack.ownerId, if (server != null) t("moderation.packs.inServer", "name" to server.name) else t("moderation.servers.ownedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), nameOf(owner), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                            }
                            FaceSection(t("moderation.servers.description")) {
                                if (pack.description.isNotEmpty()) Txt(pack.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = c.text)
                                else Txt(t("moderation.servers.noDescription"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontStyle = FontStyle.Italic), color = c.textMuted)
                            }
                            FaceSection(if (pack.emoji) t("moderation.packs.emojiCount", "count" to pack.stickers.size) else t("moderation.packs.stickerCount", "count" to pack.stickers.size)) {
                                if (pack.stickers.isEmpty()) Txt(t("moderation.packs.emptyPack"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontStyle = FontStyle.Italic), color = c.textMuted)
                                else Box(Modifier.padding(top = 4.dp)) {
                                    TileGrid(pack.stickers.size, minWidth = if (pack.emoji) 76.dp else 96.dp, gap = if (pack.emoji) 8.dp else 10.dp) { i ->
                                        val s = pack.stickers[i]
                                        PickTile(if (pack.emoji) ":${s.name}:" else s.name, s.id in picked, blocked.isEmpty(), emoji = pack.emoji, onToggle = { if (s.id in picked) picked.remove(s.id) else picked.add(s.id) }) {
                                            ExpressionPic(if (pack.emoji) "emoji" else "sticker", s.blobId, s.format, s.name, if (pack.emoji) 40.dp else 72.dp)
                                        }
                                    }
                                }
                                if (picked.isNotEmpty()) PickBar(picked.size, { picked.clear() }) { moderate(PackPreset(stickers = picked.toList())) }
                            }
                        }
                    }
                    FaceCaption(Modifier.align(Alignment.TopStart))
                }
            }
            item("public") {
                SheetPanel(t("moderation.sheet.publicTitle"), blocked.ifEmpty { t("moderation.packs.publicHint") }) {
                    FieldRowView(packFieldLabel("name"), false, { NxButton(t("moderation.sheet.reset"), { moderate(PackPreset(fields = listOf("name"))) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "Eraser", enabled = blocked.isEmpty()) }) { FieldValue(pack.name) }
                    FieldRowView(packFieldLabel("description"), pack.description.isEmpty(), { NxButton(t("moderation.sheet.clear"), { moderate(PackPreset(fields = listOf("description"))) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "Eraser", enabled = blocked.isEmpty() && pack.description.isNotEmpty()) }) {
                        FieldValue(pack.description.ifEmpty { t("moderation.sheet.notSet") }, pack.description.isEmpty())
                    }
                    FieldRowView(packFieldLabel("public"), !pack.public && !pack.discoverBlocked, {
                        // letting a pack the staff kept private be public again is an admin's
                        if (pack.discoverBlocked) NxButton(t("moderation.packs.allowPublic"), ::relist, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "LockOpen", enabled = fish.nexa.data.StaffRanks.can(fish.nexa.data.StaffCan.TAKE_DOWN_SERVERS))
                        else NxButton(t("moderation.packs.makePrivateShort"), { moderate(PackPreset(fields = listOf("public"))) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "EyeOff", enabled = blocked.isEmpty() && pack.public)
                    }) {
                        FieldValue(if (pack.discoverBlocked) t("moderation.packs.listingKeptPrivate") else if (pack.public) t("moderation.packs.listingPublic") else t("moderation.packs.listingPrivate"), !pack.public && !pack.discoverBlocked)
                    }
                }
            }
            item("owner") {
                SheetPanel(if (server != null) t("moderation.packs.serverTitle") else t("moderation.servers.ownerTitle")) {
                    if (server != null) OpenRow(server.name, t("moderation.servers.ownedBy", "name" to nameOf(owner)), { goPanel("servers/${server.id}") }) { ServerIcon(server.id, server.iconId, server.name, 36.dp) }
                    OpenRow(nameOf(owner), owner?.let { "@" + it.username }, { goPanel("users/${pack.ownerId}") }, personId = pack.ownerId) { fish.nexa.ui.components.Avatar(pack.ownerId, nameOf(owner), owner?.avatarId, 36.dp) }
                }
            }
            item("cases") {
                SheetPanel(t("moderation.packs.casesTitle")) {
                    if (d.cases.isEmpty()) PanelEmpty("FolderOpen", t("moderation.packs.noCases"))
                    else for (cs in d.cases) CaseOpenRow(cs, expressionSummary(cs.details))
                }
            }
            item("facts") {
                SheetPanel(t("moderation.packs.factsTitle")) {
                    FactsList(listOf(
                        Triple("CalendarDays", t("moderation.servers.created")) { FactText(Time.shortDate(pack.createdAt)) },
                        Triple(if (pack.emoji) "Smile" else "Sticker", t("moderation.packs.kindLabel")) { FactText(if (pack.emoji) t("moderation.packs.emojiPack") else t("moderation.packs.stickerPack")) },
                        Triple("Download", t("moderation.packs.installsLabel")) { FactText(pack.installs.toString()) },
                        Triple(null, t("moderation.packs.packId")) { CopyId(pack.id, t("moderation.packs.copyId")) },
                    ))
                }
            }
        }
    }
}

private class PackState(preset: PackPreset?, stickers: List<Sticker>, empty: Map<String, Boolean>) {
    var withStickers by mutableStateOf(stickers.isNotEmpty())
    var fields by mutableStateOf((preset?.fields ?: emptyList()).filter { empty[it] != true })
    var whole by mutableStateOf(preset?.delete == true)
    var block by mutableStateOf(true)
    var reason by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Taking down stickers in a pack, parts of what the pack shows everyone, or
 * the whole pack (ModeratePackModal). The reason goes to whoever answers for
 * it, word for word with the moderator's name, and opens a case they can
 * answer in. Pictures taken down can be blocked everywhere.
 */
fun openModeratePack(pack: ModPack, server: ModServerRef?, preset: PackPreset?, onDone: (StaffCase, List<String>) -> Unit) {
    val empty = mapOf("name" to false, "description" to pack.description.isEmpty(), "public" to !pack.public)
    val chosen = (preset?.stickers ?: emptyList()).toSet()
    val stickers = pack.stickers.filter { it.id in chosen }
    val s = PackState(preset, stickers, empty)
    fun takesPictures() = if (s.whole) pack.stickers.isNotEmpty() else s.withStickers && stickers.isNotEmpty()
    fun ready() = (s.whole || (s.withStickers && stickers.isNotEmpty()) || s.fields.isNotEmpty()) && reasonOk(s.reason)
    fun submit(close: () -> Unit, ownerName: String) {
        if (!ready() || s.busy) return
        s.busy = true
        s.error = null
        Nexa.scope.launch {
            try {
                val cs = Moderation.actOnPack(
                    pack.id,
                    stickers = if (s.withStickers) stickers.map { it.id } else emptyList(),
                    fields = PACK_FIELDS.filter { it in s.fields },
                    delete = s.whole,
                    block = s.block && takesPictures(),
                    reason = s.reason.trim(),
                )
                Nexa.ui.toast(t("moderation.packs.done", "number" to cs.number, "name" to ownerName), "success")
                onDone(cs, if (s.whole) pack.stickers.map { it.id } else if (s.withStickers) stickers.map { it.id } else emptyList())
                close()
            } catch (e: Exception) {
                s.error = describeError(e, t("moderation.failed"))
            } finally {
                s.busy = false
            }
        }
    }
    openModSheet(
        "moderate-pack",
        top = { close ->
            ModSheetTop(if (pack.emoji) t("moderation.packs.moderateEmojiPack") else t("moderation.packs.moderateTitle"), close, leading = { PackCover(pack, big = false) }) {
                fish.nexa.ui.chat.PersonText(pack.ownerId, pack.name + " · " + if (server != null) t("moderation.packs.inServer", "name" to server.name) else t("moderation.servers.ownedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), ownerName(pack.ownerId), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = Nx.colors.textMuted, maxLines = 1)
            }
        },
        foot = { close ->
            val owner = ownerName(pack.ownerId)
            ModSheetFoot(if (server != null) t("moderation.packs.notifiesServer", "name" to fish.nexa.ui.chat.PERSON_SLOT, "server" to server.name) else t("moderation.packs.notifies", "name" to fish.nexa.ui.chat.PERSON_SLOT), close, personId = pack.ownerId, personName = owner) {
                NxButton(
                    if (s.whole) (if (pack.emoji) t("moderation.packs.removeEmojiPack") else t("moderation.packs.removePack")) else t("moderation.packs.submit"),
                    { submit(close, owner) }, kind = if (s.whole) ButtonKind.DANGER else ButtonKind.PRIMARY, enabled = ready(), loading = s.busy,
                )
            }
        },
    ) {
        val c = Nx.colors
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(t("moderation.servers.whatToTake"))
            if (stickers.isNotEmpty()) PickBox(s.withStickers && !s.whole, !s.whole, { s.withStickers = !s.withStickers }, label = if (pack.emoji) t("moderation.packs.chosenEmoji", "count" to stickers.size) else t("moderation.packs.chosenStickers", "count" to stickers.size)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(if (pack.emoji) t("moderation.packs.chosenEmoji", "count" to stickers.size) else t("moderation.packs.chosenStickers", "count" to stickers.size), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.textStrong)
                    Thumbs(stickers.map { st -> st.name to @Composable { ExpressionPic(if (pack.emoji) "emoji" else "sticker", st.blobId, st.format, st.name, 40.dp) } })
                }
            }
            for (f in PACK_FIELDS) {
                val none = empty[f] == true
                PickBox(f in s.fields && !s.whole, !none && !s.whole, { s.fields = if (f in s.fields) s.fields - f else s.fields + f }, label = if (f == "public") t("moderation.packs.makePrivate") else packFieldLabel(f)) {
                    PickText(
                        if (f == "public") t("moderation.packs.makePrivate") else packFieldLabel(f),
                        when {
                            f == "public" -> if (pack.public) t("moderation.packs.listingPublic") else t("moderation.packs.listingPrivate")
                            none -> t("moderation.form.none")
                            f == "name" -> pack.name
                            else -> previewText(pack.description)
                        },
                    )
                }
            }
            PickBox(s.whole, true, { s.whole = !s.whole }, danger = true, label = if (pack.emoji) t("moderation.packs.wholeEmojiPack") else t("moderation.packs.wholePack")) {
                PickText(if (pack.emoji) t("moderation.packs.wholeEmojiPack") else t("moderation.packs.wholePack"), t("moderation.packs.wholePackHint", "count" to pack.installs))
            }
            NoteBox(t("moderation.packs.takeNote"))
        }
        if (takesPictures()) ToggleRow(s.block, { s.block = it }, t("moderation.packs.blockPictures"), t("moderation.packs.blockPicturesHint"), rule = false)
        ReasonSection(s.reason, { s.reason = it }, t("moderation.packs.reasonPlaceholder"), t("moderation.servers.reasonVisible", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(t("moderation.quick.hateful"), t("moderation.quick.explicit"), t("moderation.quick.harassment"), t("moderation.quick.impersonation"), t("moderation.quick.spam"), t("moderation.packs.quickCopyright")), personId = pack.ownerId, personName = ownerName(pack.ownerId))
        s.error?.let { e -> DangerBox { Txt(e, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) } }
    }
}
