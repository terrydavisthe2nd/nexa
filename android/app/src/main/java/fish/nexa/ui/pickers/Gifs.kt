package fish.nexa.ui.pickers

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.GifFavorites
import fish.nexa.media.Media
import fish.nexa.ui.Icon
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.ring
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private data class GifResult(val url: String, val preview: String, val w: Int?, val h: Int?)

@Composable
fun GifPicker(pick: (Pickers.Gif) -> Unit) {
    val c = Nx.colors
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var query by remember { mutableStateOf("") }
    val doc by Nexa.settings.doc.collectAsState()
    val favs = remember(doc) { GifFavorites.all() }
    var view by remember { mutableStateOf(if (favs.isNotEmpty()) "favorites" else "trending") }
    val results = remember { mutableStateListOf<GifResult>() }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf(0) }
    var dead by remember { mutableStateOf(setOf<String>()) }

    // without search on this server, the favourites are all there is
    LaunchedEffect(enabled) { if (enabled == false) view = "favorites" }
    LaunchedEffect(Unit) {
        enabled = runCatching { (Nexa.api.get("/gif/status") as? JsonObject)?.get("enabled")?.let { (it as JsonPrimitive).content == "true" } ?: false }.getOrDefault(false)
    }

    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true
        failed = false
        val mine = gen
        try {
            val q = query.trim()
            val pos = if (more) next?.let { "&pos=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "" else ""
            val path = if (q.isNotEmpty()) "/gif/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + pos else "/gif/trending?" + pos.removePrefix("&")
            val page = Nexa.api.get(path).jsonObject
            if (mine != gen) return
            val list = (page["results"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                GifResult(url, (o["preview"] as? JsonPrimitive)?.contentOrNull ?: url, (o["w"] as? JsonPrimitive)?.intOrNull, (o["h"] as? JsonPrimitive)?.intOrNull)
            } ?: emptyList()
            if (!more) results.clear()
            results += list
            next = (page["next"] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
        } catch (_: Exception) {
            if (mine == gen) failed = true
        } finally {
            loading = false
        }
    }

    // typing searches after a 300 ms pause
    LaunchedEffect(query, view, enabled) {
        if (enabled != true || view == "favorites") return@LaunchedEffect
        gen += 1
        delay(if (query.isBlank()) 0 else 300)
        load(more = false)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (enabled == true) NxTextField(query, {
                query = it
                if (it.isNotBlank()) view = "search"
            }, placeholder = t("pickers.gifPicker.searchKlipy"), modifier = Modifier.weight(1f).semantics { contentDescription = t("pickers.gifPicker.searchGifs") })
            else Box(Modifier.weight(1f))
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(t("pickers.gifPicker.favorites"), view == "favorites") { view = "favorites" }
            if (enabled == true) Pill(if (query.isNotBlank()) t("pickers.gifPicker.results") else t("pickers.gifPicker.trending"), view != "favorites") { view = if (query.isNotBlank()) "search" else "trending" }
        }
        val grid = rememberLazyStaggeredGridState()
        val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
        LaunchedEffect(nearEnd, view) { if (nearEnd && view != "favorites" && next != null && !loading) load(more = true) }
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Fixed(2),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            if (view == "favorites") {
                if (favs.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                    Note(t("pickers.gifPicker.noFavoritesYet") + " " + if (enabled == true) t("pickers.gifPicker.starInSearch") else t("pickers.gifPicker.starInChat"))
                }
                items(favs.size, key = { "fav:" + favs[it].url }) { i ->
                    val f = favs[i]
                    FavoriteTile(f, gone = f.url in dead, onGone = { dead = dead + f.url }, pick = pick)
                }
                if (dead.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Note(t("pickers.gifPicker.favouritesNoLongerLoad", "count" to dead.size))
                        Pill(t("pickers.gifPicker.removeThem"), false) { GifFavorites.remove(dead); dead = emptySet() }
                    }
                }
            } else {
                items(results.size, key = { "r:" + results[it].url + it }) { i ->
                    val g = results[i]
                    val fav = GifFavorites.isFavorite(g.url)
                    GifTile(Media.absolute(g.preview), g.w, g.h, favorite = fav, gone = false, onGone = {}, onLongPress = {
                        openMenu(listOf(
                            MenuItem.Action(t("pickers.gifPicker.sendGif")) { pick(Pickers.Gif(g.url, g.w, g.h)) },
                            MenuItem.Action(if (fav) t("pickers.gifPicker.removeFromFavorites") else t("pickers.gifPicker.addToFavorites")) { GifFavorites.toggle(g.url, g.w, g.h) },
                        ), haptic = true)
                    }, onStar = { GifFavorites.toggle(g.url, g.w, g.h) }) { pick(Pickers.Gif(g.url, g.w, g.h)) }
                }
                item(span = StaggeredGridItemSpan.FullLine) {
                    when {
                        enabled == false -> Note(t("pickers.gifPicker.gifSearchUnavailable"))
                        loading -> Note(t("chat.pins.loading"))
                        failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Note(if (results.isEmpty()) t("pickers.gifPicker.gifsCouldNotBeLoaded") else t("pickers.gifPicker.moreGifsCouldNotBeLoaded"))
                            Pill(t("chat.messageList.tryAgain"), false) { gen += 1 }
                        }
                        results.isEmpty() -> Note(if (query.isNotBlank()) t("pickers.gifPicker.noGifsMatch") else t("pickers.gifPicker.nothingTrendingRightNow"))
                        else -> Note(t("pickers.gifPicker.searchPoweredByKlipyThisServer"))
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Txt(
    text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = Nx.colors.textMuted, align = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
)

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .then(if (on) Modifier.background(brandGradient(c), shape) else Modifier.ring(shape, c.borderSubtle).background(c.bgSurface, shape))
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) { Txt(label, style = Nx.type.label.copy(fontWeight = FontWeight(650)), color = if (on) c.onBrand else c.textMuted) }
}

/**
 * A favourite as GifPicker.tsx shows it. A link is shown, and sent, as that
 * link signed again (signatures run out after a day, and loading the saved
 * one failed and called the favourite gone); an uploaded GIF is opened from
 * its attachment and sent again as a file. Only a link the server will not
 * sign again, or a file that cannot be opened, is gone.
 */
@Composable
private fun FavoriteTile(f: GifFavorites.Fav, gone: Boolean, onGone: () -> Unit, pick: (Pickers.Gif) -> Unit) {
    val att = remember(f.att) { f.att?.let { fish.nexa.crypto.MessageCrypto.sanitizeAttachment(it) } }
    val w = f.w ?: att?.w?.toInt()
    val h = f.h ?: att?.h?.toInt()
    var src: Any? = null
    var lost = gone
    var send: Pickers.Gif? = null
    if (att != null) {
        val load = fish.nexa.media.rememberAttachmentLoad(att, auto = true)
        src = load.file
        if (load.error != null) lost = true
        send = Pickers.Gif(f.url, w, h, att)
    } else {
        // read so a link signed again draws again
        fish.nexa.media.MediaLinks.version
        when (val fresh = fish.nexa.media.MediaLinks.fresh(f.url)) {
            null -> lost = true
            "" -> {}
            else -> {
                src = Media.absolute(fresh)
                send = Pickers.Gif(fresh, w, h)
            }
        }
    }
    GifTile(src, w, h, favorite = true, gone = lost, onGone = onGone, onLongPress = {
        openMenu(buildList {
            send?.let { g -> if (!lost) add(MenuItem.Action(t("pickers.gifPicker.sendGif")) { pick(g) }) }
            add(MenuItem.Action(t("pickers.gifPicker.removeFromFavorites"), danger = true) { GifFavorites.remove(setOf(f.url)) })
        }, haptic = true)
    }, onStar = { GifFavorites.remove(setOf(f.url)) }) { send?.let(pick) }
}

/** A tile as tall as the GIF's shape allows (0.4 to 2 times its width), a star for favourites. [src] null is still on its way. */
@Composable
private fun GifTile(src: Any?, w: Int?, h: Int?, favorite: Boolean, gone: Boolean, onGone: () -> Unit, onLongPress: () -> Unit, onStar: () -> Unit, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Sm
    val ratio = if (w != null && h != null && w > 0 && h > 0) (h.toFloat() / w).coerceIn(0.4f, 2f) else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (gone) Modifier.padding(0.dp) else Modifier.aspectRatio(1f / ratio))
            .ring(shape, c.borderSubtle)
            .background(c.bgInput, shape)
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, onLongClick = onLongPress) { if (!gone) onClick() },
    ) {
        if (gone) Txt(t("pickers.gifGrid.thisOneIsGone"), style = Nx.type.hint, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp))
        else if (src != null) AsyncImage(src, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { onGone() })
        // on a phone only favourites show their star
        if (favorite) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(26.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .pressable(remember { MutableInteractionSource() }, onClick = onStar)
                .semantics { contentDescription = t("pickers.gifPicker.removeFavorite") },
            contentAlignment = Alignment.Center,
        ) { Icon("Star", c.warning, size = 14.dp) }
    }
}
