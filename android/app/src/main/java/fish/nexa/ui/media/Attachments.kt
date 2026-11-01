package fish.nexa.ui.media

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.crypto.AttachmentMeta
import fish.nexa.data.ChatMessage
import fish.nexa.media.AUTO_LOAD_MAX
import fish.nexa.media.AttachmentLoad
import fish.nexa.media.Downloads
import fish.nexa.media.Fit
import fish.nexa.media.GifFavorites
import fish.nexa.media.INLINE_MAX
import fish.nexa.media.Media
import fish.nexa.media.MediaLinks
import fish.nexa.media.Saving
import fish.nexa.media.loadPriority
import fish.nexa.media.rememberAttachmentLoad
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.chat.ListCtx
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.topEdge

/** Who sent a message, as the viewer names it under a picture. */
fun senderName(cm: ChatMessage?, ctx: ListCtx?): String {
    if (cm == null) return ""
    fish.nexa.ui.markdown.Webhook.of(cm.hook)?.let { return it.name }
    return Names.member(ctx?.server?.server?.id, cm.authorId)
}

/** The small copy of a picture, as an attachment of its own (lib/pictureCopy.ts copyOf). */
fun copyOf(att: AttachmentMeta): AttachmentMeta? {
    val p = att.preview ?: return null
    return AttachmentMeta(blobId = p.blobId, name = att.name, type = p.type, size = p.size, key = p.key, w = p.w, h = p.h)
}

/**
 * Attachments.tsx: a message's files. Two or more pictures come first as one
 * grid; then every other file in the order it was sent: pictures, videos
 * (streamed as they decrypt), sounds, and a card for everything else.
 */
@Composable
fun Attachments(list: List<AttachmentMeta>, cm: ChatMessage?, ctx: ListCtx?, fill: Boolean = false) {
    Saving.Track()
    val from = senderName(cm, ctx)
    val pictures = remember(list) { list.filter { it.size <= INLINE_MAX && it.type.startsWith("image/") } }
    val tiles = if (pictures.size > 1) Fit.mosaic(pictures.size) else null
    fun gallery(openBlob: String): Pair<List<LightboxItem>, Int> {
        val shown = pictures.filter { it.spoiler != true || it.blobId == openBlob }
        val items = shown.map { LightboxItem(it.name, it.type, att = it, from = from, cm = cm) }
        return items to shown.indexOfFirst { it.blobId == openBlob }.coerceAtLeast(0)
    }
    val open = { att: AttachmentMeta -> gallery(att.blobId).let { (items, start) -> openLightbox(items, start) } }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
        if (tiles != null) Mosaic(pictures, tiles, cm, open)
        for (att in list) {
            val type = att.type
            val inline = att.size <= INLINE_MAX
            when {
                inline && type.startsWith("image/") -> if (tiles == null) ImageAttachment(att, cm, tile = null, onOpen = { open(att) }, fill = fill)
                type.startsWith("video/") -> VideoAttachment(att, fill = fill, noDownload = fish.nexa.data.MediaDownloads.hidden(cm?.m?.channelId))
                inline && type.startsWith("audio/") -> AudioAttachment(att)
                else -> FileCard(att)
            }
        }
    }
}

/** The six-column grid of pictures, its shapes fixed before anything loads (mosaicLayout). */
@Composable
private fun Mosaic(pictures: List<AttachmentMeta>, tiles: List<Fit.Tile>, cm: ChatMessage?, open: (AttachmentMeta) -> Unit) {
    MosaicGrid(tiles) {
        pictures.forEachIndexed { i, att ->
            ImageAttachment(att, cm, tile = tiles[i], onOpen = { open(att) })
        }
    }
}

/**
 * Lays one tile per child into the six-column grid Fit.mosaic describes, as
 * CSS grid places them: each at the first free place, row by row, the rows
 * as tall as their tiles' shapes say. A message's pictures, and a case
 * thread's.
 */
@Composable
fun MosaicGrid(tiles: List<Fit.Tile>, content: @Composable () -> Unit) {
    val gap = 4.dp
    Box(Modifier.widthIn(max = 550.dp).fillMaxWidth().clip(Radii.Md)) {
        Layout(content = content) { measurables, constraints ->
            val w = constraints.maxWidth
            val g = gap.roundToPx()
            val cw = (w - 5 * g) / 6f
            fun span(cols: Int) = (cols * cw + (cols - 1) * g).toInt()
            // CSS grid auto-placement: each tile at the first free place, row by row
            val rows = ArrayList<BooleanArray>()
            fun free(r: Int, c: Int, cols: Int, rs: Int): Boolean {
                if (c + cols > 6) return false
                for (rr in r until r + rs) {
                    while (rows.size <= rr) rows += BooleanArray(6)
                    for (cc in c until c + cols) if (rows[rr][cc]) return false
                }
                return true
            }
            val places = tiles.map { t ->
                var r = 0
                var found: Pair<Int, Int>? = null
                while (found == null) {
                    for (c in 0..(6 - t.col)) if (free(r, c, t.col, t.row)) { found = r to c; break }
                    if (found == null) r++
                }
                val (pr, pc) = found
                for (rr in pr until pr + t.row) for (cc in pc until pc + t.col) rows[rr][cc] = true
                found
            }
            // row heights from the tiles of one row that have a fixed shape
            val heights = IntArray(rows.size)
            tiles.forEachIndexed { i, t ->
                if (t.row == 1 && t.ratio != null) {
                    val r = places[i].first
                    heights[r] = maxOf(heights[r], (span(t.col) / t.ratio).toInt())
                }
            }
            val tops = IntArray(rows.size)
            for (r in 1 until rows.size) tops[r] = tops[r - 1] + heights[r - 1] + g
            val placeables = measurables.mapIndexed { i, m ->
                val t = tiles[i]
                val (r, _) = places[i]
                val h = (r until r + t.row).sumOf { heights[it] } + (t.row - 1) * g
                m.measure(Constraints.fixed(span(t.col), maxOf(1, h)))
            }
            val total = if (rows.isEmpty()) 0 else tops.last() + heights.last()
            layout(w, total) {
                placeables.forEachIndexed { i, p ->
                    val (r, c) = places[i]
                    p.placeRelative((c * (cw + g)).toInt(), tops[r])
                }
            }
        }
    }
}

/** The picture pressed last, so a long press on it puts Copy Image and Save Image first in the menu. */
object PressedImage {
    var target: ImageTarget? = null

    fun take(): ImageTarget? = target.also { target = null }
}

/**
 * ImageAttachment: a picture framed as the web frames it, the small copy in
 * the chat (the viewer, Copy and Save use the whole picture), a cover over a
 * spoiler until the first tap, the star on GIFs.
 */
@Composable
fun ImageAttachment(att: AttachmentMeta, cm: ChatMessage?, tile: Fit.Tile?, onOpen: () -> Unit, fill: Boolean = false) {
    val c = Nx.colors
    var copyFailed by remember(att.blobId) { mutableStateOf(false) }
    val copy = remember(att.blobId, copyFailed) { if (copyFailed) null else copyOf(att) }
    val shown = copy ?: att
    val auto = copy != null || att.size <= AUTO_LOAD_MAX
    val load = rememberAttachmentLoad(shown, auto)
    LaunchedEffect(load.error) { if (copy != null && load.error == "decrypt") copyFailed = true }
    var revealed by remember(att.blobId) { mutableStateOf(att.spoiler != true) }
    val fit = Fit.media(att.w, att.h)
    val frame = when {
        tile != null -> Modifier.fillMaxSize()
        // a note's picture takes the whole width its line gives it, in its own shape
        fill -> Modifier.fillMaxWidth().aspectRatio(ratioOf(att.w, att.h))
        else -> Modifier.width(fit.width.dp).aspectRatio(fit.width.toFloat() / fit.height)
    }
    val file = load.file
    Box(
        frame
            .then(if (tile == null) Modifier.ring(Radii.Md, c.borderSubtle).clip(Radii.Md) else Modifier.clipToBounds())
            .background(c.bgInput)
            .loadPriority(load, shown.blobId)
            .pointerInput(att.blobId, revealed, file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (revealed && file != null) PressedImage.target = ImageTarget(att, null, att.name, att.type, cm)
                }
            }
            .pressable(remember { MutableInteractionSource() }) { if (revealed) { if (file != null) onOpen() } else revealed = true }
            .semantics { if (revealed) role = Role.Image },
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            // under a cover the picture is blurred; a phone that cannot blur shows only the cover
            if (revealed || canBlur) AsyncImage(
                file, att.alt ?: att.name,
                imageLoader = Media.loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (!revealed) Modifier.spoilerBlur() else Modifier),
            )
        } else {
            Placeholder(load, att, auto, revealed, what = "image")
        }
        if (att.type == "image/gif" && revealed) GifStar("attach:${att.blobId}", att.w, att.h, att, Modifier.align(Alignment.TopEnd))
        if (!revealed) SpoilerCover { revealed = true }
    }
}

/** A picture's or video's own shape, width over height, or a usual one when it does not say. */
fun ratioOf(w: Double?, h: Double?): Float = if (w != null && h != null && w > 0 && h > 0) (w / h).toFloat().coerceIn(0.2f, 5f) else 16f / 9f

/** Whether this phone can blur what is drawn (Android 12 and later); a cover hides the picture entirely where it cannot. */
val canBlur: Boolean get() = Build.VERSION.SDK_INT >= 31

/**
 * A spoiler's blur, fading at the edges into the frame behind it as the web's
 * `filter: blur()` does; the frame clips it to its own bounds.
 */
fun Modifier.spoilerBlur(radius: Dp = 44.dp): Modifier = blur(radius, BlurredEdgeTreatment.Unbounded)

/** What an empty frame says while its picture comes: decrypting, a button for a big one, or why it failed. */
@Composable
private fun Placeholder(load: AttachmentLoad, att: AttachmentMeta, auto: Boolean, revealed: Boolean, what: String) {
    val c = Nx.colors
    val err = load.error
    when {
        err != null -> LoadFailed(err, what) { load.retry() }
        load.wanted || auto -> Txt(
            if (att.plain) t("chat.pins.loading") else t("chat.attachments.decryptingShort"),
            style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted,
        )
        else -> LoadButton(t("chat.attachments.loadImage", "size" to Time.bytes(att.size)), icon = null) { if (revealed) load.load() }
    }
}

/** The "Load …" button for a file too big to fetch by itself. */
@Composable
fun LoadButton(label: String, icon: String?, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .clip(Radii.Xs)
            .background(if (pressed) c.brand else c.bgActive)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, if (pressed) c.onBrand else c.text, size = 20.dp)
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = if (pressed) c.onBrand else c.text)
    }
}

private val FAILED = mapOf(
    "image" to ("chat.attachments.imageUndecryptable" to "chat.attachments.imageUnloadable"),
    "video" to ("chat.attachments.videoUndecryptable" to "chat.attachments.videoUnloadable"),
    "audio" to ("chat.attachments.audioUndecryptable" to "chat.attachments.audioUnloadable"),
)

/** Why a file did not show, and Retry when it was the connection rather than the file. */
@Composable
fun LoadFailed(error: String, what: String, onRetry: () -> Unit) {
    val c = Nx.colors
    val keys = FAILED[what] ?: FAILED.getValue("image")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(8.dp),
    ) {
        Txt(t(if (error == "decrypt") keys.first else keys.second), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, align = TextAlign.Center)
        if (error == "load") {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .height(32.dp)
                    .clip(Radii.Xs)
                    .background(if (pressed) c.brand else c.bgActive)
                    .pressable(interaction, onClick = onRetry)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(t("chat.messageItem.retry"), style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = if (pressed) c.onBrand else c.text)
            }
        }
    }
}

/** The cover on a spoiler: a dark pill reading "Spoiler"; the first tap uncovers it. */
@Composable
fun BoxScope.SpoilerCover(fontSize: Float = 14f, onReveal: () -> Unit) {
    Box(
        Modifier
            .matchParentSize()
            .pressable(remember { MutableInteractionSource() }, onClick = onReveal)
            .semantics { contentDescription = t("render.spoilerHidden") },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(999.dp)
        Box(
            Modifier
                .ring(shape, Color.White.copy(alpha = 0.14f))
                .background(Color(0x9E0A0614), shape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Txt(t("composer.composer.spoiler"), style = Nx.type.body.copy(fontSize = Nx.type.sp(fontSize), fontWeight = FontWeight(700), letterSpacing = 0.01.em), color = Color.White)
        }
    }
}

/** The star on a GIF: adds it to the GIF picker's favourites, or takes it out. Always shown on a phone. */
@Composable
fun GifStar(url: String, w: Double?, h: Double?, att: AttachmentMeta?, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val doc by Nexa.settings.doc.collectAsState()
    val on = remember(doc, url) { GifFavorites.isFavorite(url) }
    Box(
        modifier
            .size(40.dp)
            .pressable(remember { MutableInteractionSource() }) {
                val now = GifFavorites.toggle(url, w, h, att)
                Nexa.ui.toast(if (now) t("chat.embeds.addedToYourGifFavorites") else t("chat.embeds.removedFromFavorites"), "success")
            }
            .semantics { contentDescription = if (on) t("chat.embeds.removeFromFavorites") else t("chat.embeds.addToFavorites") },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 6.dp)
                .size(28.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon("Star", if (on) c.warning else Color.White, size = 16.dp, filled = on)
        }
    }
}

/**
 * The file card: an icon, the name (a tap saves it too) over its size, and a
 * download button that turns into a spinner while it saves; the size line
 * counts up as it goes. Big files are written as they decrypt.
 */
@Composable
fun FileCard(att: AttachmentMeta) {
    val c = Nx.colors
    Saving.Track()
    var busy by remember(att.blobId) { mutableStateOf(false) }
    var done by remember(att.blobId) { mutableLongStateOf(0L) }
    val download = {
        if (!busy) {
            busy = true
            done = 0L
            Saving.save(att.name, att.type, write = { out -> Downloads.writeAttachment(att, out) { done = it } }, onCancel = { busy = false }) { err ->
                busy = false
                if (err != null) Nexa.ui.toast(err.message?.takeIf { it.isNotBlank() && err is fish.nexa.net.ApiError } ?: t("chat.attachments.couldNotDownloadThatFile"), "error")
                else Nexa.ui.toast(t("settings.account.saved"), "success")
            }
        }
    }
    val shape = Radii.Md
    Row(
        Modifier
            .widthIn(max = 432.dp)
            .fillMaxWidth()
            .shadowLow(shape, c)
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon("File", c.icon, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Txt(
                att.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(16f)), color = c.link, maxLines = 1,
                modifier = Modifier.pressable(remember { MutableInteractionSource() }) { download() },
            )
            val pct = if (att.size > 0) minOf(100, (done * 100 / att.size).toInt()) else 0
            Txt(
                if (busy) t("chat.attachments.downloadingPercent", "percent" to pct, "size" to Time.bytes(att.size)) else Time.bytes(att.size),
                style = Nx.type.hint, color = c.textMuted, maxLines = 1,
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .pressable(remember { MutableInteractionSource() }, enabled = !busy) { download() }
                .semantics { contentDescription = t("chat.attachments.downloadNamed", "name" to att.name) },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) Spinner(c.icon, 20.dp) else Icon("Download", c.icon, size = 20.dp)
        }
    }
}

/** The waiting box a video or a card keeps its place with. */
@Composable
fun PendingBox(width: Dp?, ratio: Float, content: @Composable BoxScope.() -> Unit = {}) {
    val c = Nx.colors
    Box(
        Modifier
            .padding(top = 4.dp)
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(ratio)
            .clip(Radii.Sm)
            .background(c.bgSurface)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
