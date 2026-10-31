package fish.nexa.ui.pickers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.media.TwemojiImage
import fish.nexa.net.Emoji
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.ui.Icon
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.ServerIcon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.markdown.EmojiData
import fish.nexa.ui.markdown.EmojiEntry
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ExpressionPicker.tsx on a phone: a sheet along the bottom with no scrim,
 * tabs for GIFs, stickers and emoji (as many as the opener takes). The
 * composer's can be pulled up to full height and swiped away; the ones for a
 * reaction or a poll answer sit still.
 */
object Pickers {
    enum class Tab { GIFS, STICKERS, EMOJI }

    /** A picked emoji: a standard one (in your skin tone) or a custom one as its token. */
    data class Picked(val text: String, val custom: Emoji? = null, val packEmoji: Sticker? = null)

    class Opener(
        val tabs: List<Tab>,
        val serverId: String?,
        /** Custom emoji of this server only, plus the standard ones (poll answers). */
        val thisServerOnly: Boolean = false,
        val draggable: Boolean = true,
        val closeOnEmoji: Boolean = false,
        val onEmoji: (Picked) -> Unit,
        val onSticker: ((Sticker) -> Unit)? = null,
        val onGif: ((Gif) -> Unit)? = null,
        /** What the picker is for, its title when it offers emoji alone ("Add Reaction"). */
        val title: String? = null,
    )

    data class Gif(val url: String, val w: Int?, val h: Int?)

    private const val KEY = "expression-picker"

    fun open(opener: Opener) {
        lateinit var layer: Layer
        layer = Layer(KEY) { close -> PickerSheet(layer, opener, close) }
        Layers.open(layer)
    }

    fun close() = Layers.close(KEY)

    val isOpen get() = Layers.stack.any { it.key == KEY }

    /** Moves a standard emoji to the front of Frequently Used (the last 40, synced). */
    fun noteRecent(unicode: String) {
        val next = (listOf(unicode) + Nexa.settings.recentEmoji.filter { it != unicode }).take(40)
        Nexa.settings.patch(buildJsonObject { put("recentEmoji", JsonArray(next.map { JsonPrimitive(it) })) })
    }
}

@Composable
private fun PickerSheet(layer: Layer, opener: Pickers.Opener, close: () -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(200, easing = ease)) }
    layer.dismiss = {
        scope.launch {
            if (!reduced) shown.animateTo(0f, tween(140, easing = ease))
            Layers.remove(layer)
        }
    }
    var full by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }
    val recentEmoji = remember(opener) { Nexa.settings.recentEmoji.toList() }
    var tab by remember { mutableStateOf(if (Pickers.Tab.EMOJI in opener.tabs) Pickers.Tab.EMOJI else opener.tabs.first()) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = maxHeight
        val height = if (full) screenH else minOf(screenH * 0.6f, screenH - 56.dp)
        val shape = if (full) RectangleShape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        val density = LocalDensity.current.density
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    val p = shown.value
                    alpha = p
                    translationY = (1f - p) * 8f * density + drag.coerceAtLeast(0f)
                    val s = 0.97f + 0.03f * p
                    scaleX = s; scaleY = s
                }
                .shadowHigh(shape, c)
                .ring(shape, c.panelEdge)
                .background(c.bgFloating, shape)
                .topEdge(shape, c.edgeLight)
                .navigationBarsPadding()
                // taps inside stay inside
                .pressable(remember { MutableInteractionSource() }) {},
        ) {
            // the handle zone: pull up for full height, down to close
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .then(
                        if (opener.draggable) Modifier.pointerInput(full) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    when {
                                        drag > 120 * density -> close()
                                        drag < -60 * density -> full = true
                                        full && drag > 60 * density -> full = false
                                    }
                                    drag = 0f
                                },
                            ) { change, dy ->
                                change.consume()
                                drag += dy
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (opener.draggable) Box(Modifier.padding(top = 6.dp).size(36.dp, 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                // emoji alone: a title saying what the picker is for, not a bar with one tab in it
                if (opener.tabs.size == 1) Txt(opener.title ?: t("modals.customStatus.emoji"), style = Nx.type.headerTitle, color = c.textStrong, modifier = Modifier.weight(1f))
                else Tabs(opener.tabs, tab, Modifier.weight(1f)) { tab = it }
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .pressable(remember { MutableInteractionSource() }) { close() }
                        .semantics { contentDescription = t("pickers.expressionPicker.closePicker") },
                    contentAlignment = Alignment.Center,
                ) { Icon("X", c.icon, size = 18.dp) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Pickers.Tab.EMOJI -> EmojiTab(opener, recentEmoji) { picked ->
                        opener.onEmoji(picked)
                        if (opener.closeOnEmoji) close()
                    }
                    Pickers.Tab.STICKERS -> StickerTab(opener.serverId) { s ->
                        close()
                        opener.onSticker?.invoke(s)
                    }
                    Pickers.Tab.GIFS -> GifTab { g ->
                        close()
                        opener.onGif?.invoke(g)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(tabs: List<Pickers.Tab>, current: Pickers.Tab, modifier: Modifier, pick: (Pickers.Tab) -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .padding(top = 2.dp)
            .background(c.bgInput, shape)
            .ring(shape, c.borderSubtle)
            .padding(3.dp)
            .semantics { contentDescription = t("pickers.expressionPicker.expressionPicker") },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (tab in listOf(Pickers.Tab.GIFS, Pickers.Tab.STICKERS, Pickers.Tab.EMOJI).filter { it in tabs }) {
            val on = tab == current
            val tshape = RoundedCornerShape(9.dp)
            Box(
                Modifier
                    .then(if (on) Modifier.shadowLow(tshape, c).ring(tshape, c.borderSubtle).background(c.bgSurface, tshape).background(sheen(c), tshape).topEdge(tshape, c.edgeLight) else Modifier)
                    .clip(tshape)
                    .pressable(remember { MutableInteractionSource() }) { pick(tab) }
                    .semantics { selected = on }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label = when (tab) {
                    Pickers.Tab.GIFS -> t("pickers.expressionPicker.gifs")
                    Pickers.Tab.STICKERS -> t("pickers.expressionPicker.stickers")
                    Pickers.Tab.EMOJI -> t("modals.customStatus.emoji")
                }
                Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// emoji

private sealed interface Cell {
    data class Std(val e: EmojiEntry) : Cell
    data class Custom(val e: Emoji) : Cell
    data class Pack(val s: Sticker) : Cell
}

private data class Section(val key: String, val title: String, val icon: @Composable () -> Unit, val cells: List<Cell>)

@Composable
private fun EmojiTab(opener: Pickers.Opener, recent: List<String>, pick: (Pickers.Picked) -> Unit) {
    val c = Nx.colors
    var query by remember { mutableStateOf("") }
    val tone = Nexa.settings.doc.collectAsState().value.let { Nexa.settings.skinTone }
    val emojis by Nexa.expressions.emojis.collectAsState()
    val packs by Nexa.expressions.packs.collectAsState()
    var loaded by remember { mutableStateOf(EmojiData.loaded()) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EmojiData.all() }
            loaded = EmojiData.loaded()
        }
    }
    var last by remember { mutableStateOf<Cell?>(null) }

    val sections: List<Section> = remember(loaded, emojis, packs, recent, opener) {
        val out = ArrayList<Section>()
        if (!loaded) return@remember out
        val byUnicode = { u: String -> EmojiData.byUnicode(u) }
        val freq = recent.mapNotNull { byUnicode(it) }.map { Cell.Std(it) }
        if (freq.isNotEmpty()) out += Section("frequent", t("pickers.emojiPicker.frequentlyUsed"), { Icon("Clock", c.textMuted, size = 20.dp) }, freq)
        val servers = emojis.entries.filter { (sid, list) -> list.isNotEmpty() && (!opener.thisServerOnly || sid == opener.serverId) }
            .sortedWith(compareBy({ it.key != opener.serverId }, { Nexa.servers.get(it.key)?.server?.name?.lowercase() ?: "" }))
        for ((sid, list) in servers) {
            val s = Nexa.servers.get(sid)?.server ?: continue
            out += Section("server:$sid", s.name, { ServerIcon(s.id, s.name, s.iconId, 24.dp, radius = 12.dp) }, list.map { Cell.Custom(it) })
        }
        if (!opener.thisServerOnly) for (p in packs.values.filter { it.kind == "emoji" && it.stickers.isNotEmpty() }.sortedByDescending { it.updatedAt }) {
            out += Section("pack:${p.id}", p.name, { PackCover(p) }, p.stickers.map { Cell.Pack(it) })
        }
        val icons = mapOf(0 to "Smile", 1 to "Users", 3 to "Cat", 4 to "Pizza", 5 to "Plane", 6 to "Activity", 7 to "Lightbulb", 8 to "Leaf", 9 to "Flag")
        for (g in EmojiData.groups()) {
            val icon = icons[g.id] ?: "Smile"
            out += Section("group:${g.id}", t(g.nameKey), { Icon(icon, c.textMuted, size = 20.dp) }, g.items.filter { EmojiData.has(it.unicode) }.map { Cell.Std(it) })
        }
        out
    }
    val results: List<Cell>? = remember(query, loaded, emojis, packs) {
        val q = query.trim().lowercase().removePrefix(":").removeSuffix(":")
        if (q.isEmpty()) return@remember null
        val custom = emojis.entries.filter { !opener.thisServerOnly || it.key == opener.serverId }
            .flatMap { it.value }.filter { it.name.lowercase().contains(q) }.take(40).map { Cell.Custom(it) }
        val pack = if (opener.thisServerOnly) emptyList() else packs.values.filter { it.kind == "emoji" }.flatMap { it.stickers }.filter { it.name.lowercase().contains(q) }.take(40).map { Cell.Pack(it) }
        custom + pack + EmojiData.search(q, 100).filter { EmojiData.has(it.unicode) }.map { Cell.Std(it) }
    }

    fun picked(cell: Cell) {
        last = cell
        when (cell) {
            is Cell.Std -> {
                Pickers.noteRecent(cell.e.unicode)
                pick(Pickers.Picked(EmojiData.withTone(cell.e, tone)))
            }
            is Cell.Custom -> pick(Pickers.Picked(token(cell.e.name, cell.e.blobId, cell.e.animated), custom = cell.e))
            is Cell.Pack -> pick(Pickers.Picked(token(cell.s.name, cell.s.blobId, cell.s.animated), packEmoji = cell.s))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxTextField(query, { query = it }, placeholder = t("pickers.emojiPicker.findThePerfectEmoji"), modifier = Modifier.weight(1f).semantics { contentDescription = t("pickers.emojiPicker.searchEmoji") })
            ToneButton(tone)
        }
        if (!loaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Txt(t("pickers.emojiPicker.loadingEmoji"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted) }
            return@Column
        }
        val grid = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (results == null) {
                // the category column: a tap scrolls the grid to that section
                Column(
                    Modifier.width(48.dp).fillMaxHeight().background(Color.Transparent).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((i, s) in sections.withIndex()) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .pressable(remember { MutableInteractionSource() }) { scope.launch { grid.scrollToItem(headerIndex(sections, i)) } }
                                .semantics { contentDescription = s.title },
                            contentAlignment = Alignment.Center,
                        ) { s.icon() }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val cols = maxOf(1, ((maxWidth - 16.dp) / 40.dp).toInt())
                EmojiGrid(grid, cols, results, sections, tone) { picked(it) }
            }
        }
        // the inspector: what was picked last, else the prompt
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(mixOklab(c.bgFloating, c.bgBase, 0.5f))
                .topEdge(RectangleShape, c.borderSubtle)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val l = last) {
                null -> Txt(t("pickers.emojiPicker.pickAnEmoji"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                else -> {
                    CellGlyph(l, tone, 28.dp)
                    Txt(cellName(l), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
                }
            }
        }
    }
}

private fun headerIndex(sections: List<Section>, i: Int): Int {
    var idx = 0
    for (k in 0 until i) idx += 1 + sections[k].cells.size
    return idx
}

private fun token(name: String, blobId: String, animated: Boolean) = "<" + (if (animated) "a" else "") + ":" + name + ":" + blobId + ">"

private fun cellName(cell: Cell): String = when (cell) {
    is Cell.Std -> ":" + (cell.e.shortcodes.firstOrNull() ?: cell.e.label) + ":"
    is Cell.Custom -> ":${cell.e.name}:"
    is Cell.Pack -> ":${cell.s.name}:"
}

@Composable
private fun EmojiGrid(grid: LazyGridState, cols: Int, results: List<Cell>?, sections: List<Section>, tone: Int, pick: (Cell) -> Unit) {
    val c = Nx.colors
    LazyVerticalGrid(GridCells.Fixed(cols), state = grid, modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        if (results != null) {
            if (results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Txt(t("pickers.emojiPicker.noEmojiMatch"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.padding(24.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            }
            items(results.size) { i -> EmojiCell(results[i], tone) { pick(results[i]) } }
        } else for (s in sections) {
            item(key = "h:" + s.key, span = { GridItemSpan(maxLineSpan) }) {
                Txt(
                    s.title,
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700), letterSpacing = androidx.compose.ui.unit.TextUnit(0.01f, androidx.compose.ui.unit.TextUnitType.Em)),
                    color = c.textMuted, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().height(32.dp).background(c.bgFloating).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 5.dp),
                )
            }
            items(s.cells.size, key = { i -> s.key + ":" + i }) { i -> EmojiCell(s.cells[i], tone) { pick(s.cells[i]) } }
        }
    }
}

@Composable
private fun EmojiCell(cell: Cell, tone: Int, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(160, easing = Nx.motion.spring), label = "cell")
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) c.bgActive else Color.Transparent)
            .pressable(interaction, onClick = onClick)
            .semantics { contentDescription = cellName(cell) },
        contentAlignment = Alignment.Center,
    ) { CellGlyph(cell, tone, 32.dp) }
}

@Composable
private fun CellGlyph(cell: Cell, tone: Int, size: androidx.compose.ui.unit.Dp) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    when (cell) {
        is Cell.Std -> TwemojiImage(EmojiData.withTone(cell.e, tone), size)
        is Cell.Custom -> AsyncImage(Media.emojiUrl(cell.e.blobId, 32, density), cell.e.name, Modifier.size(size))
        is Cell.Pack -> AsyncImage(Media.emojiUrl(cell.s.blobId, 32, density), cell.s.name, Modifier.size(size))
    }
}

@Composable
private fun PackCover(p: StickerPack) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val first = p.stickers.firstOrNull()
    val density = LocalDensity.current.density
    if (first != null) AsyncImage(Media.emojiUrl(first.blobId, 24, density) + "&still=1", p.name, Modifier.size(24.dp).clip(CircleShape))
    else Box(Modifier.size(24.dp).background(c.bgSurface, CircleShape), contentAlignment = Alignment.Center) {
        Txt(p.name.take(1).uppercase(), style = Nx.type.hint.copy(fontWeight = FontWeight(700)), color = c.text)
    }
}

/** The skin-tone button: the raised hand in your tone; a tap offers the six. */
@Composable
private fun ToneButton(tone: Int) {
    val c = Nx.colors
    var open by remember { mutableStateOf(false) }
    val hand = "✋"
    val handEntry = EmojiData.byUnicode(hand)
    Box {
        Box(
            Modifier
                .size(32.dp)
                .clip(Radii.Sm)
                .background(if (open) c.bgHover else Color.Transparent)
                .pressable(remember { MutableInteractionSource() }) { open = !open }
                .semantics { contentDescription = t("pickers.emojiPicker.skinTone") },
            contentAlignment = Alignment.Center,
        ) { TwemojiImage(handEntry?.let { EmojiData.withTone(it, tone) } ?: hand, 22.dp) }
        if (open) {
            androidx.compose.ui.window.Popup(alignment = Alignment.TopEnd, onDismissRequest = { open = false }) {
                val shape = Radii.Md
                Column(
                    Modifier.shadowHigh(shape, c).ring(shape, c.panelEdge).background(c.bgFloating, shape).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (i in 0..5) {
                        val on = i == tone
                        Box(
                            Modifier
                                .size(32.dp)
                                .then(if (on) Modifier.ring(Radii.Sm, c.brand, 2.dp).background(c.bgSelected, Radii.Sm) else Modifier)
                                .clip(Radii.Sm)
                                .pressable(remember { MutableInteractionSource() }) {
                                    Nexa.settings.patch(buildJsonObject { put("skinTone", i) })
                                    open = false
                                }
                                .semantics { contentDescription = t("pickers.emojiPicker.skinToneNamed", "tone" to t(EmojiData.TONE_KEYS[i])) },
                            contentAlignment = Alignment.Center,
                        ) { TwemojiImage(handEntry?.let { EmojiData.withTone(it, i) } ?: hand, 22.dp) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// stickers

@Composable
private fun StickerTab(serverId: String?, pick: (Sticker) -> Unit) {
    val c = Nx.colors
    var query by remember { mutableStateOf("") }
    val packs by Nexa.expressions.packs.collectAsState()
    val recent by Nexa.expressions.recentStickers.collectAsState()
    val me = Nexa.users.meId
    val stickerPacks = packs.values.filter { it.kind == "sticker" }
    // This Server, My Packs, Installed, Other Servers; newest updated first in each
    val groups = listOf(
        t("expressions.thisServer") to stickerPacks.filter { serverId != null && it.serverId == serverId },
        t("expressions.myPacks") to stickerPacks.filter { it.serverId == null && it.ownerUserId == me },
        t("expressions.installed") to stickerPacks.filter { it.serverId == null && it.ownerUserId != me && it.installed },
        t("expressions.otherServers") to stickerPacks.filter { it.serverId != null && it.serverId != serverId },
    ).map { (title, list) -> title to list.sortedByDescending { it.updatedAt } }.filter { it.second.isNotEmpty() }
    val q = query.trim().lowercase()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            NxTextField(query, { query = it }, placeholder = t("pickers.stickerPicker.searchStickers"))
        }
        if (stickerPacks.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(t("pickers.stickerPicker.noStickerPacksYet"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                fish.nexa.ui.components.NxButton(t("pickers.stickerPicker.createOrInstallAPack"), { fish.nexa.ui.settings.Settings.open("stickers") }, kind = fish.nexa.ui.components.ButtonKind.LINK, inline = true)
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cols = maxOf(2, ((maxWidth - 16.dp) / 104.dp).toInt())
            LazyVerticalGrid(GridCells.Fixed(cols), modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fun heading(title: String, key: String, small: Boolean) {
                    item(key = "h:$key", span = { GridItemSpan(maxLineSpan) }) {
                        Txt(
                            title,
                            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(if (small) 13f else 12.5f), fontWeight = FontWeight(if (small) 600 else 700)),
                            color = if (small) c.text else c.textMuted, maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp, top = if (small) 4.dp else 12.dp, bottom = 4.dp),
                        )
                    }
                }
                fun tiles(list: List<Sticker>, key: String) {
                    items(list.size, key = { "$key:" + list[it].id }) { i -> StickerTile(list[i]) { pick(list[i]) } }
                }
                fun matches(s: Sticker) = q.isEmpty() || s.name.lowercase().contains(q) || s.tags.lowercase().contains(q)
                if (q.isEmpty() && recent.isNotEmpty()) {
                    heading(t("pickers.stickerPicker.recent"), "recent", small = false)
                    tiles(recent, "recent")
                }
                var any = false
                // the groups (This Server, My Packs, Installed, Other Servers), each pack under its own name
                for ((title, list) in groups) {
                    val shown = list.map { it to it.stickers.filter(::matches) }.filter { it.second.isNotEmpty() || q.isEmpty() }
                    if (shown.isEmpty()) continue
                    heading(title, "g:$title", small = false)
                    for ((p, stickers) in shown) {
                        heading(p.name, "p:${p.id}", small = true)
                        if (stickers.isEmpty()) item(key = "e:${p.id}", span = { GridItemSpan(maxLineSpan) }) {
                            Txt(t("pickers.stickerPicker.thisPackIsEmpty"), style = Nx.type.hint, color = c.textMuted, modifier = Modifier.padding(4.dp))
                        } else {
                            any = true
                            tiles(stickers, "p:${p.id}")
                        }
                    }
                }
                if (q.isNotEmpty() && !any) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    Txt(t("pickers.stickerPicker.noStickersMatch"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StickerTile(s: Sticker, onClick: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val shape = Radii.Md
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .graphicsLayer { val k = if (pressed) 0.96f else 1f; scaleX = k; scaleY = k }
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(interaction, onClick = onClick)
            .semantics { contentDescription = s.name },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(Media.stickerUrl(s.blobId, 96, density) + if (s.animated || s.format == "tgs" || s.format == "webm") "&still=1" else "", s.name, Modifier.fillMaxSize().padding(8.dp))
    }
}

@Composable
private fun GifTab(pick: (Pickers.Gif) -> Unit) = GifPicker(pick)
