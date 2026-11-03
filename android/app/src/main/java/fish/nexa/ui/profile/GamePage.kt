package fish.nexa.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.data.Games
import fish.nexa.media.Media
import fish.nexa.media.mediaSrc
import fish.nexa.media.openExternal
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.media.LightboxItem
import fish.nexa.ui.media.openLightbox
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.topEdge
import java.util.Date
import java.util.TimeZone

/**
 * A game's page (web components/games/GamePage.tsx): the game's artwork
 * across the top with its box standing on the edge, then the stage (a
 * trailer or screenshot, with a strip of the rest), what it is about, where
 * to get it, the facts and games like it. A similar game opens in its place,
 * and the back arrow returns. Opening another game page replaces this one.
 */
fun openGamePage(id: String) {
    lateinit var layer: Layer
    layer = Layer("game", replaces = "game") { close -> GameSheet(layer, id, close) }
    Layers.open(layer)
}

@Composable
private fun GameSheet(layer: Layer, startId: String, close: () -> Unit) {
    val trail = remember { mutableStateListOf(startId) }
    val id = trail.last()
    var game by remember { mutableStateOf<Games.Details?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        game = null
        failed = false
        try {
            game = Games.load(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            failed = true
        }
    }
    Sheet(layer, maxWidth = 820.dp, closeButton = false, bare = true, padding = 0.dp) {
        Box(Modifier.fillMaxWidth()) {
            val g = game
            when {
                g != null -> Page(g) { next -> if (trail.last() != next) { if (trail.size >= 20) trail.removeAt(0); trail.add(next) } }
                failed -> Column(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(horizontal = 24.dp, vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Txt(t("games.page.failed"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = Nx.colors.textSubtle)
                    Row(
                        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(999.dp)).background(Nx.colors.bgActive).pressable(remember { MutableInteractionSource() }) { attempt++ }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon("RotateCw", Nx.colors.textStrong, size = 14.dp)
                        Txt(t("games.page.retry"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = Nx.colors.textStrong)
                    }
                }
                else -> Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) { Spinner(size = 32.dp) }
            }
            // round buttons over the artwork: Back on the left, Close on the right
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                if (trail.size > 1) BarButton("ArrowLeft", t("games.page.back")) { trail.removeAt(trail.lastIndex) }
                Box(Modifier.weight(1f))
                BarButton("X", t("chat.attachments.close"), close)
            }
        }
    }
}

@Composable
private fun BarButton(icon: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).topEdge(CircleShape, Color.White.copy(alpha = 0.14f))
            .pressable(remember { MutableInteractionSource() }, onClick = onClick).semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, Color.White, size = 18.dp) }
}

/** Draws [by] higher and gives that much room back, so the box stands on the artwork's edge (a negative margin). */
private fun Modifier.pullUp(by: androidx.compose.ui.unit.Dp) = layout { m, cs ->
    val shift = by.roundToPx()
    val p = m.measure(cs)
    layout(p.width, (p.height - shift).coerceAtLeast(0)) { p.place(0, -shift) }
}

@Composable
private fun SectionHeading(text: String) = Txt(
    text,
    style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(16f)),
    color = Nx.colors.textMuted,
    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
)

@Composable
private fun Page(g: Games.Details, onOpen: (String) -> Unit) {
    val c = Nx.colors
    val backdrop = Games.backdrop(g)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 620.dp
        val roomy = maxWidth >= 480.dp
        val heroH = (maxWidth.value * 0.34f).coerceIn(150f, 290f).dp
        Column(Modifier.fillMaxWidth()) {
            // the artwork fades into the sheet, so the box can stand on its edge
            Box(Modifier.fillMaxWidth().height(heroH).clip(RoundedCornerShape(0.dp)).background(Brush.linearGradient(listOf(mixOklab(fish.nexa.ui.components.AvatarColor.of(g.ref.name), c.bgFloating, 0.45f), c.bgFloating)))) {
                if (backdrop != null) AsyncImage(Games.pictureUrl(backdrop, "b"), null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else g.ref.cover?.let { AsyncImage(Games.coverUrl(it, "s"), null, Media.loader, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f; scaleX = 1.6f; scaleY = 1.6f }.blur(40.dp), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.3f to Color.Transparent, 0.45f to Color.Transparent, 1f to c.bgFloating)))
            }
            val boxW = if (wide) 120.dp else 92.dp
            Row(
                Modifier.fillMaxWidth().pullUp(if (wide) 84.dp else 64.dp).padding(horizontal = if (wide) 24.dp else 16.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (wide) 18.dp else 14.dp),
            ) {
                GameCover(Widgets.Game(g.ref.id, g.ref.name, g.ref.cover, null, emptyList()), 120, boxW, RoundedCornerShape(9.dp))
                Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(g.ref.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(if (wide) 28f else 22f), fontWeight = FontWeight(750), lineHeight = Nx.type.sp(if (wide) 31f else 25f)), color = c.textStrong, maxLines = 3, modifier = Modifier.semantics { heading() })
                    val byline = listOfNotNull(g.ref.year?.toString(), g.developers.firstOrNull())
                    if (byline.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (b in byline) Txt(b, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.textSubtle)
                    }
                    g.rating?.let { score ->
                        Row(
                            Modifier.padding(top = 4.dp).height(26.dp).clip(RoundedCornerShape(999.dp)).background(c.bgActive).topEdge(RoundedCornerShape(999.dp), c.edgeLight).padding(horizontal = 10.dp)
                                .semantics { contentDescription = t("games.page.ratingFrom", "count" to (g.ratingCount ?: 0)) },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon("Star", c.warning, size = 14.dp, filled = true)
                            Txt(t("games.page.ratingOutOf", "score" to score), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(700), fontFeatureSettings = "tnum"), color = c.textStrong)
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = if (wide) 24.dp else 16.dp, end = if (wide) 24.dp else 16.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Media(g)
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) { MainText(g) }
                    Column(Modifier.width(240.dp)) { Facts(g) }
                } else {
                    MainText(g)
                    Column { Facts(g) }
                }
                if (g.similar.isNotEmpty()) Column {
                    SectionHeading(t("games.page.similar"))
                    val cols = if (roomy) 6 else 3
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (row in g.similar.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (s in row) Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).pressable(remember { MutableInteractionSource() }) { onOpen(s.id) }
                                    .semantics { contentDescription = t("games.page.openNamed", "name" to s.name); role = Role.Button },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                GameCover(Widgets.Game(s.id, s.name, s.cover, null, emptyList()), 96, null)
                                Txt(s.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(15f)), color = c.textSubtle, maxLines = 2)
                            }
                            repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
                Txt(t("widgets.game.credit", "site" to "IGDB.com"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun MainText(g: Games.Details) {
    g.summary?.let { Column { SectionHeading(t("games.page.about")); Folded(it) } }
    g.storyline?.let { Column { SectionHeading(t("games.page.storyline")); Folded(it) } }
    if (g.links.isNotEmpty()) Column {
        SectionHeading(t("games.page.whereToGet"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in g.links) StoreLink(l)
        }
    }
}

/** A paragraph of IGDB's that folds to six lines until opened. */
@Composable
private fun Folded(text: String) {
    val c = Nx.colors
    var open by remember(text) { mutableStateOf(false) }
    val long = text.length > 360
    Column {
        Txt(
            text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(22.5f)), color = c.text,
            maxLines = if (open || !long) Int.MAX_VALUE else 6,
        )
        if (long) Txt(
            if (open) t("games.page.showLess") else t("games.page.readMore"),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textBrand,
            modifier = Modifier.padding(top = 2.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { open = !open }.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun StoreLink(l: Games.Link) {
    val c = Nx.colors
    val host = runCatching { java.net.URI(l.url).host?.removePrefix("www.") }.getOrNull() ?: l.url
    val name = if (l.kind == "official") t("games.page.officialSite") else Games.STORE_NAMES[l.kind] ?: l.kind
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(999.dp)).background(c.bgActive).topEdge(RoundedCornerShape(999.dp), c.edgeLight)
            .pressable(remember { MutableInteractionSource() }) {
                fish.nexa.ui.shell.confirm(t("textMenu.openLink"), l.url, t("textMenu.openLink"), danger = false) { openLink(l.url) }
            }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon("ExternalLink", c.textMuted, size = 14.dp)
        Txt(name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
        Txt(host, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1, modifier = Modifier.widthIn(max = 200.dp))
    }
}

@Composable
private fun Facts(g: Games.Details) {
    SectionHeading(t("games.page.details"))
    val released = g.released?.let {
        android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.LONG, I18n.locale).apply { timeZone = android.icu.util.TimeZone.getTimeZone("UTC") }.format(Date(it))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Fact(t("games.page.released"), listOfNotNull(released))
        Fact(t("games.page.genres"), g.genres)
        Fact(t("games.page.themes"), g.themes)
        Fact(t("games.page.modes"), g.modes)
        Fact(t("games.page.platforms"), g.platforms)
        Fact(t("games.page.developers"), g.developers)
        Fact(t("games.page.publishers"), g.publishers)
        Fact(t("games.page.ageRatings"), g.ageRatings.map { "${it.board} ${it.rating}" })
    }
}

@Composable
private fun Fact(label: String, values: List<String>) {
    if (values.isEmpty()) return
    val c = Nx.colors
    Column {
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(15f)), color = c.textMuted)
        Txt(values.joinToString(", "), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(19.6f)), color = c.textStrong, modifier = Modifier.padding(top = 2.dp))
    }
}

private sealed interface Shown {
    data class Trailer(val v: Games.Video) : Shown
    data class Shot(val p: Games.Picture, val index: Int) : Shown
}

/**
 * The stage and its strip. A trailer opens in the YouTube app or the browser
 * when pressed, as a YouTube link in a chat does on Android; a screenshot
 * opens the picture viewer with the rest beside it.
 */
@Composable
private fun Media(g: Games.Details) {
    val c = Nx.colors
    val items = remember(g) { Games.trailersFirst(g.videos).map { Shown.Trailer(it) } + g.screenshots.mapIndexed { i, p -> Shown.Shot(p, i) } }
    if (items.isEmpty()) return
    var at by remember(g.ref.id) { mutableIntStateOf(0) }
    val shown = items.getOrElse(at) { items[0] }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).ring(Radii.Sm, c.borderSubtle).clip(Radii.Sm).background(Color.Black)
                .pressable(remember { MutableInteractionSource() }) {
                    when (shown) {
                        is Shown.Trailer -> openExternal("https://www.youtube.com/watch?v=${shown.v.id}")
                        is Shown.Shot -> openLightbox(g.screenshots.mapIndexed { i, p -> LightboxItem(t("games.page.screenshotOf", "name" to g.ref.name, "n" to i + 1), src = Games.pictureUrl(p.id, "b")) }, shown.index)
                    }
                }
                .semantics {
                    contentDescription = when (shown) {
                        is Shown.Trailer -> t("chat.embeds.playVideoNamed", "name" to (shown.v.name ?: g.ref.name))
                        is Shown.Shot -> t("games.page.viewScreenshot")
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (shown) {
                is Shown.Trailer -> {
                    val poster = mediaSrc(shown.v.poster ?: shown.v.thumb)
                    // YouTube's larger picture is 4:3 with black bands round a 16:9 frame: scaled by 4/3 the bands fall outside
                    if (!poster.isNullOrEmpty()) AsyncImage(poster, null, Media.loader, Modifier.fillMaxSize().graphicsLayer { if (shown.v.poster != null) { scaleX = 1.334f; scaleY = 1.334f } }, contentScale = ContentScale.Crop)
                    Box(Modifier.size(60.dp).background(Color(0xB8000000), CircleShape), contentAlignment = Alignment.Center) { Icon("Play", Color.White, size = 26.dp, filled = true) }
                }
                is Shown.Shot -> AsyncImage(Games.pictureUrl(shown.p.id, "b"), null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        if (items.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { i, it ->
                val on = i == at
                val shape = RoundedCornerShape(7.dp)
                Box(
                    Modifier.width(96.dp).aspectRatio(16f / 9f)
                        .then(if (on) Modifier.ring(shape, c.brand, 2.dp) else Modifier.ring(shape, c.borderSubtle))
                        .clip(shape).background(c.bgActive)
                        .graphicsLayer { alpha = if (on) 1f else 0.62f }
                        .pressable(remember { MutableInteractionSource() }) { at = i }
                        .semantics {
                            selected = on
                            role = Role.Tab
                            contentDescription = when (it) {
                                is Shown.Trailer -> t("games.page.trailerNamed", "name" to (it.v.name ?: g.ref.name))
                                is Shown.Shot -> t("games.page.screenshotOf", "name" to g.ref.name, "n" to it.index + 1)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when (it) {
                        is Shown.Trailer -> {
                            val thumb = mediaSrc(it.v.thumb)
                            if (!thumb.isNullOrEmpty()) AsyncImage(thumb, null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) { Icon("Play", Color.White, size = 14.dp, filled = true) }
                        }
                        is Shown.Shot -> AsyncImage(Games.pictureUrl(it.p.id, "t"), null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}
