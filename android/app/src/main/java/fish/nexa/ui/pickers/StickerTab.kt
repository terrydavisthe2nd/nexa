package fish.nexa.ui.pickers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ReportContext
import fish.nexa.media.Media
import fish.nexa.net.ApiError
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.ui.Icon
import fish.nexa.ui.chat.StickerMedia
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.reports.openReport
import fish.nexa.ui.stickers.CollapsedPacks
import fish.nexa.ui.stickers.FavoriteStickers
import fish.nexa.ui.stickers.StickerSettings
import fish.nexa.ui.stickers.StickerStill
import fish.nexa.ui.stickers.canRemovePack
import fish.nexa.ui.stickers.confirmRemovePack
import fish.nexa.ui.stickers.openPackSheet
import fish.nexa.ui.stickers.packCover
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch

private const val RECENT_ROWS = 2
private val TILE = 96.dp
private val GAP = 8.dp

/** One part of the list, and the item the grid can scroll to for it. */
private data class Part(val key: String, val at: Int)

/** Search results under the pack they came from (searchStickersByPack): the closest match first; a pack whose name matches brings all its stickers. */
private fun searchByPack(packs: List<StickerPack>, q: String): List<Pair<StickerPack, List<Sticker>>> {
    val out = mutableListOf<Triple<StickerPack, List<Sticker>, Int>>()
    for (pack in packs) {
        val packMatches = pack.name.lowercase().contains(q)
        var best = if (packMatches) 20 else -1
        val found = pack.stickers.filter { st ->
            val name = st.name.lowercase()
            val score = when {
                name == q -> 100
                name.startsWith(q) -> 80
                name.contains(q) -> 50
                st.tags.lowercase().split(Regex("\\s+")).any { it.startsWith(q) } -> 40
                else -> -1
            }
            if (score > best) best = score
            packMatches || score >= 0
        }
        if (found.isNotEmpty()) out += Triple(pack, found, best)
    }
    return out.sortedByDescending { it.third }.map { it.first to it.second }
}

/** The Stickers tab (StickerPicker.tsx): favourites, recents, then every pack, with a rail that follows the part scrolled to. */
@Composable
internal fun StickerTab(serverId: String?, close: () -> Unit, pick: (Sticker) -> Unit) {
    val c = Nx.colors
    var query by remember { mutableStateOf("") }
    val packs by Nexa.expressions.packs.collectAsState()
    val recent by Nexa.expressions.recentStickers.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val me = Nexa.users.meId
    val q = query.trim().lowercase()
    val favoriteIds = FavoriteStickers.ids(doc)
    val favorites = remember(favoriteIds) { favoriteIds.toSet() }
    val collapsed = CollapsedPacks.ids(doc)
    val stickerPacks = remember(packs) { packs.values.filter { it.kind == "sticker" }.map { p -> p.copy(stickers = p.stickers.sortedBy { it.position }) } }
    // This Server, then yours, then installed, then other servers'; newest updated first in each
    val all = remember(stickerPacks, serverId, me) {
        stickerPacks.sortedWith(compareBy<StickerPack> {
            when {
                serverId != null && it.serverId == serverId -> 0
                it.serverId == null && it.ownerUserId == me -> 1
                it.serverId == null && it.ownerUserId != me && it.installed -> 2
                else -> 3
            }
        }.thenByDescending { it.updatedAt })
    }
    fun sourceOf(p: StickerPack): String = when {
        serverId != null && p.serverId == serverId -> t("expressions.thisServer")
        p.ownerUserId != null && p.ownerUserId == me -> t("pickers.stickerPicker.yourPack")
        p.installed -> t("expressions.installed")
        else -> p.serverId?.let { Nexa.servers.get(it)?.server?.name } ?: t("expressions.otherServers")
    }
    val byId = remember(all) { all.flatMap { p -> p.stickers.map { it.id to (it to p) } }.toMap() }
    val favoriteItems = remember(favoriteIds, byId) { favoriteIds.mapNotNull { byId[it] } }
    val recentItems = remember(recent, packs) { recent.mapNotNull { st -> packs[st.packId]?.takeIf { it.kind == "sticker" }?.let { st to it } }.take(24) }
    val results = remember(all, q) { if (q.isEmpty()) null else searchByPack(all, q) }
    var showAllRecent by remember { mutableStateOf(false) }
    val findMore = {
        close()
        Nav.go(Route.Discover("stickers"))
    }
    val grid = rememberLazyGridState()
    val rail = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(q) { grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            fish.nexa.ui.server.SearchBox(query, { query = it }, t("pickers.stickerPicker.searchStickers"))
        }
        if (stickerPacks.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(t("pickers.stickerPicker.noStickerPacksYet"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                NxButton(t("pickers.stickerPicker.findMorePacks"), findMore, kind = ButtonKind.LINK, inline = true)
                NxButton(t("settings.stickers.createStickerPack"), { fish.nexa.ui.settings.Settings.open("stickers") }, kind = ButtonKind.LINK, inline = true)
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val railWide = 48.dp
            val cols = maxOf(2, ((maxWidth - railWide - 16.dp - 16.dp + GAP) / (TILE + GAP)).toInt())
            // where each part starts in the grid, worked out as it is laid out below
            val parts = mutableListOf<Part>()
            var at = 0
            val shownRecent = recentItems.take(cols * RECENT_ROWS)
            val moreRecent = recentItems.drop(cols * RECENT_ROWS)
            if (results == null) {
                if (favoriteItems.isNotEmpty()) { parts += Part("favorites", at); at += 1 + favoriteItems.size }
                if (recentItems.isNotEmpty()) { parts += Part("recent", at); at += 1 + shownRecent.size + if (showAllRecent) moreRecent.size else 0 }
                for (p in all) {
                    parts += Part(p.id, at)
                    at += 1 + if (p.id in collapsed) 0 else maxOf(1, p.stickers.size)
                }
            } else for ((p, found) in results) {
                parts += Part(p.id, at)
                at += 1 + found.size
            }
            val activeKey by remember(parts.toList()) {
                derivedStateOf {
                    val first = grid.firstVisibleItemIndex
                    parts.lastOrNull { it.at <= first }?.key ?: parts.firstOrNull()?.key
                }
            }
            val railKeys = buildList {
                if (results == null) {
                    if (favoriteItems.isNotEmpty()) add("favorites")
                    if (recentItems.isNotEmpty()) add("recent")
                    addAll(all.map { it.id })
                } else addAll(results.map { it.first.id })
            }
            // the chosen icon stays in sight in a rail longer than the sheet
            LaunchedEffect(activeKey) {
                val i = railKeys.indexOf(activeKey)
                if (i < 0) return@LaunchedEffect
                val seen = rail.layoutInfo.visibleItemsInfo
                if (seen.none { it.index == i && it.offset >= 0 && it.offset + it.size <= rail.layoutInfo.viewportEndOffset }) rail.scrollToItem(i)
            }
            fun jump(key: String) {
                val to = parts.firstOrNull { it.key == key } ?: return
                scope.launch { grid.scrollToItem(to.at) }
            }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(railWide).fillMaxHeight().semantics { contentDescription = t("pickers.expressionPicker.stickers") },
                    state = rail,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(railKeys, key = { it }) { key ->
                        when (key) {
                            "favorites" -> RailButton(t("pickers.stickerPicker.favorites"), activeKey == key, { jump(key) }) { Icon("Star", if (activeKey == key) c.textBrand else c.textMuted, size = 18.dp) }
                            "recent" -> RailButton(t("pickers.stickerPicker.recent"), activeKey == key, { jump(key) }) { Icon("Clock", if (activeKey == key) c.textBrand else c.textMuted, size = 18.dp) }
                            else -> {
                                val p = all.firstOrNull { it.id == key }
                                if (p != null) RailButton(p.name, activeKey == key, { jump(key) }, onLong = { packMenu(p, me, close) }) {
                                    val cover = packCover(p)
                                    if (cover != null) StickerStill(cover, false, 28.dp, Modifier.size(26.dp))
                                    else Txt(p.name.take(1).uppercase(), style = Nx.type.hint.copy(fontWeight = FontWeight(700)), color = c.text)
                                }
                            }
                        }
                    }
                    if (results == null) item("more") { RailButton(t("pickers.stickerPicker.findMorePacks"), false, findMore) { Icon("Plus", c.textMuted, size = 18.dp) } }
                }
                LazyVerticalGrid(
                    GridCells.Fixed(cols),
                    state = grid,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    fun tiles(list: List<Pair<Sticker, StickerPack>>, key: String, animated: Boolean = false, star: Boolean = true) {
                        items(list.size, key = { "$key:" + list[it].first.id }) { i ->
                            val (s, p) = list[i]
                            Box(if (animated) Modifier.animateItem() else Modifier) {
                                StickerTile(s, star && s.id in favorites, { stickerMenu(s, p, s.id in favorites) }) { pick(s) }
                            }
                        }
                    }
                    if (results != null) {
                        if (results.isEmpty()) wide("none") {
                            Txt(t("pickers.stickerPicker.noStickersMatch"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.padding(24.dp).fillMaxWidth(), align = TextAlign.Center)
                        }
                        for ((p, found) in results) {
                            wide("s:${p.id}") {
                                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PartTitle(p.name, Modifier.weight(1f))
                                    Txt(t("server.packEditor.stickerCount", "count" to found.size), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, maxLines = 1)
                                }
                            }
                            tiles(found.map { it to p }, "s:${p.id}")
                        }
                    } else {
                        if (favoriteItems.isNotEmpty()) {
                            wide("favorites") { Box(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) { PartTitle(t("pickers.stickerPicker.favorites")) } }
                            tiles(favoriteItems, "f", star = false)
                        }
                        if (recentItems.isNotEmpty()) {
                            wide("recent") {
                                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PartTitle(t("pickers.stickerPicker.recent"), Modifier.weight(1f))
                                    if (moreRecent.isNotEmpty()) HeadLink(if (showAllRecent) t("pickers.stickerPicker.showLess") else t("pickers.stickerPicker.showAll", "count" to recentItems.size)) { showAllRecent = !showAllRecent }
                                    HeadLink(t("pickers.stickerPicker.clearRecent")) {
                                        Nexa.expressions.setRecent(emptyList())
                                        showAllRecent = false
                                        Nexa.scope.launch {
                                            try {
                                                Nexa.api.request("DELETE", "/stickers/recent")
                                            } catch (_: ApiError) {
                                                Nexa.ui.toast(t("pickers.stickerPicker.couldNotClearRecent"), "error")
                                            }
                                        }
                                    }
                                }
                            }
                            tiles(shownRecent, "r")
                            if (showAllRecent) tiles(moreRecent, "r", animated = true)
                        }
                        for (p in all) {
                            val open = p.id !in collapsed
                            wide("p:${p.id}") { PackHead(p, open, sourceOf(p), me, close) }
                            if (open) {
                                if (p.stickers.isEmpty()) wide("e:${p.id}") {
                                    Txt(t("pickers.stickerPicker.thisPackIsEmpty"), style = Nx.type.hint, color = c.textMuted, modifier = Modifier.padding(4.dp))
                                } else tiles(p.stickers.map { it to p }, "p:${p.id}", animated = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun PartTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = Nx.colors.textMuted, maxLines = 1, modifier = modifier)
}

@Composable
private fun HeadLink(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 36.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }, onClick = onClick).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Txt(text, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600)), color = Nx.colors.textBrand, maxLines = 1)
    }
}

/** A pack's heading: fold it, what it is, where it came from, and everything else behind one menu. */
@Composable
private fun PackHead(p: StickerPack, open: Boolean, source: String, me: String?, close: () -> Unit) {
    val c = Nx.colors
    val motion = Nx.motion
    val turn by animateFloatAsState(if (open) 0f else -90f, if (motion.reduced) tween(0) else tween(180, easing = motion.easeOut), label = "chevron")
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 36.dp)
                .clip(Radii.Xs)
                .pressable(remember { MutableInteractionSource() }, onLongClick = { packMenu(p, me, close) }) { CollapsedPacks.toggle(p.id) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon("ChevronDown", c.textMuted, size = 14.dp, modifier = Modifier.rotate(turn))
            Txt(p.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(700)), color = c.text, maxLines = 1)
        }
        Txt(source, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(500)), color = c.textMuted, maxLines = 1)
        Box(
            Modifier
                .size(width = 36.dp, height = 36.dp)
                .clip(Radii.Xs)
                .pressable(remember { MutableInteractionSource() }) { packMenu(p, me, close) }
                .semantics { contentDescription = t("pickers.stickerPicker.packOptions", "name" to p.name) },
            contentAlignment = Alignment.Center,
        ) { Icon("Ellipsis", c.icon, size = 16.dp) }
    }
}

@Composable
private fun RailButton(label: String, on: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(if (on) mixOklab(c.bgFloating, c.brand, 0.18f) else androidx.compose.ui.graphics.Color.Transparent, shape)
            .pressable(remember { MutableInteractionSource() }, onLongClick = onLong, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StickerTile(s: Sticker, starred: Boolean, onLong: () -> Unit, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer { val k = if (pressed) 0.96f else 1f; scaleX = k; scaleY = k }
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(interaction, onLongClick = onLong, onClick = onClick)
            .semantics { contentDescription = s.name },
        contentAlignment = Alignment.Center,
    ) {
        StickerStill(s, false, TILE, Modifier.fillMaxSize().padding(8.dp))
        if (starred) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(c.bgBase.copy(alpha = 0.72f), CircleShape), contentAlignment = Alignment.Center) {
            Icon("Star", c.warning, size = 13.dp, filled = true)
        }
    }
}

/** A held sticker: drawn big and moving with its name and pack, over what can be done with it. */
private fun stickerMenu(s: Sticker, p: StickerPack, favorite: Boolean) {
    openMenu(
        listOf(
            MenuItem.Action(t(if (favorite) "pickers.gifPicker.removeFromFavorites" else "pickers.gifPicker.addToFavorites")) { FavoriteStickers.toggle(s.id) },
            MenuItem.Action(t("pickers.stickerPicker.viewPack"), separatorAfter = true) { openPackSheet(p.id, p, s.id) },
            MenuItem.Action(t("reports.report")) { openReport(ReportContext(kind = "sticker", id = s.id)) },
        ),
        header = {
            val c = Nx.colors
            val density = LocalDensity.current.density
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StickerMedia(s.format, Media.stickerUrl(s.blobId, 160, density), s.name, 160.dp, Modifier.padding(bottom = 6.dp))
                Txt(s.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(690)), color = c.textStrong, align = TextAlign.Center)
                Txt(p.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(500)), color = c.textMuted, align = TextAlign.Center)
            }
        },
    )
}

/** A pack in the picker: see it all, change your own, take away one you installed, or report it. */
private fun packMenu(p: StickerPack, me: String?, close: () -> Unit) {
    openMenu(
        listOfNotNull(
            MenuItem.Action(t("pickers.stickerPicker.viewPack")) { openPackSheet(p.id, p) },
            if (p.ownerUserId == me) MenuItem.Action(t("pickers.stickerPicker.editPack")) { close(); StickerSettings.openPack(p.id) } else null,
            if (canRemovePack(p, me)) MenuItem.Action(t("pickers.stickerPicker.removePack"), danger = true) { confirmRemovePack(p) } else null,
            MenuItem.Action(t("reports.report")) { openReport(ReportContext(kind = "pack", id = p.id)) },
        ),
        title = p.name,
    )
}
