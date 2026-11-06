package fish.nexa.ui.chat

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import fish.nexa.Nexa
import fish.nexa.crypto.StickerRef
import fish.nexa.media.Media
import fish.nexa.media.Streams
import fish.nexa.media.Webm
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.AnchorRef
import fish.nexa.ui.layers.anchor
import fish.nexa.ui.layers.openPopout
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fish.nexa.net.StickerInfo
import okhttp3.Request
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/** A sticker's drawing: a picture (animated or not), a Lottie animation (.tgs), or a looping silent video (.webm). */
@Composable
fun StickerMedia(format: String?, url: String, alt: String, size: Dp, modifier: Modifier = Modifier) {
    when (format) {
        "tgs" -> TgsView(url, alt, size, modifier)
        "webm" -> WebmView(url, alt, size, modifier)
        else -> AsyncImage(url, alt, imageLoader = Media.loader, contentScale = ContentScale.Fit, modifier = modifier.size(size))
    }
}

/** .tgs files are gzipped Lottie JSON; a few are kept read (StickerView.tsx TGS_KEEP). */
private object Tgs {
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 96
    }

    suspend fun json(url: String): String? {
        synchronized(cache) { cache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith("file:")) {
                    GZIPInputStream(java.io.File(java.net.URI(url)).inputStream()).bufferedReader().use { it.readText() }
                } else {
                    Nexa.api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        GZIPInputStream(r.body.byteStream()).bufferedReader().use { it.readText() }
                    }
                }
            }.getOrNull()?.also { synchronized(cache) { cache[url] = it } }
        }
    }
}

@Composable
private fun TgsView(url: String, alt: String, size: Dp, modifier: Modifier) {
    val json by produceState<String?>(null, url) { value = Tgs.json(url) }
    val text = json
    Box(modifier.size(size).semantics { contentDescription = alt }) {
        if (text != null) {
            val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(text))
            LottieAnimation(composition, isPlaying = fish.nexa.ui.shell.settingOn("animateStickers"), iterations = LottieConstants.IterateForever, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A video sticker, its see-through parts kept (media/WebmAlpha.kt); one this
 * cannot read plays in the ordinary player instead.
 */
@Composable
private fun WebmView(url: String, alt: String, size: Dp, modifier: Modifier) {
    val box = with(LocalDensity.current) { size.roundToPx() }
    var frame by remember(url) { mutableStateOf<Bitmap?>(null) }
    var plain by remember(url) { mutableStateOf(false) }
    val animate = fish.nexa.ui.shell.settingOn("animateStickers")
    LaunchedEffect(url, box, animate) {
        val clip = Webm.load(url)
        if (clip == null) {
            plain = true
            return@LaunchedEffect
        }
        val (w, h) = Webm.fit(clip, box)
        if (!Webm.play(clip, w, h, loop = animate) { frame = it }) plain = true
    }
    if (plain) {
        PlainWebm(url, alt, size, modifier)
        return
    }
    Canvas(modifier.size(size).semantics { contentDescription = alt }) {
        val bmp = frame ?: return@Canvas
        // contained and centred, as object-fit: contain draws it
        val scale = minOf(this.size.width / bmp.width, this.size.height / bmp.height)
        val w = (bmp.width * scale).roundToInt()
        val h = (bmp.height * scale).roundToInt()
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset(((this.size.width - w) / 2).roundToInt(), ((this.size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Medium,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlainWebm(url: String, alt: String, size: Dp, modifier: Modifier) {
    val context = LocalContext.current
    val animate = fish.nexa.ui.shell.settingOn("animateStickers")
    val player = remember(url) {
        ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(Streams.factory())).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    LaunchedEffect(player, animate) { player.playWhenReady = animate; if (!animate) player.seekTo(0) }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier.size(size).semantics { contentDescription = alt }) {
        ContentFrame(player, Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit)
    }
}

/**
 * A sticker in a message: 160 dp tall at its own shape; a tap shows its name
 * and, for a pack anyone can see, the pack. With no picture it reads
 * "[sticker]" in the small grey of "(edited)".
 */
@Composable
fun StickerView(sticker: StickerRef) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val density = LocalDensity.current.density
    val packs by Nexa.expressions.packs.collectAsState()
    val blob = sticker.blob
    if (blob == null) {
        Txt(t("chat.messageItem.sticker"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(10f)), color = c.textMuted)
        return
    }
    // new messages carry the format; older ones are looked up in the packs this device knows
    val format = sticker.format ?: remember(packs, sticker.id) { packs.values.firstNotNullOfOrNull { p -> p.stickers.firstOrNull { it.id == sticker.id }?.format } }
    val w = if (sticker.w != null && sticker.h != null && sticker.h > 0) minOf(160.0, 160 * sticker.w / sticker.h) else 160.0
    val ref = remember { AnchorRef() }
    val label = sticker.name?.let { t("chat.messageItem.stickerNamed", "name" to it) } ?: t("attachmentViews.sticker")
    Box(
        Modifier
            .padding(top = 4.dp)
            .anchor(ref)
            .clip(Radii.Xs)
            .pressable(remember { MutableInteractionSource() }) { openStickerPopout(sticker, format, ref) }
            .semantics { contentDescription = label },
    ) {
        StickerMedia(format, Media.stickerUrl(blob, 160, density), label, 160.dp, Modifier.width(w.dp))
    }
}

/** GET /stickers/{id}: the sticker and its pack, kept a minute (StickerPopout.tsx stickerInfo). */
private val infoCache = HashMap<String, Pair<Long, StickerInfo?>>()

private suspend fun stickerInfo(id: String): StickerInfo? {
    synchronized(infoCache) { infoCache[id]?.let { (at, v) -> if (System.currentTimeMillis() - at < 60_000) return v } }
    val v = runCatching { Nexa.api.get("/stickers/$id", StickerInfo.serializer()) }.getOrNull()
    synchronized(infoCache) { if (v != null) infoCache[id] = System.currentTimeMillis() to v else infoCache.remove(id) }
    return v
}

/** StickerPopout: the sticker small, its name and pack, and the way to the pack. */
private fun openStickerPopout(sticker: StickerRef, format: String?, ref: AnchorRef) {
    openPopout("sticker", ref.rect, 240.dp) { close ->
        val c = Nx.colors
        val density = LocalDensity.current.density
        var info by remember { mutableStateOf<StickerInfo?>(null) }
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            info = stickerInfo(sticker.id)
            info?.users?.let { Nexa.users.upsert(it) }
            loaded = true
        }
        val name = sticker.name ?: t("attachmentViews.sticker")
        val packName = info?.pack?.name
        val packId = info?.pack?.id
        val infoFormat = info?.sticker?.format
        Column(Modifier.width(240.dp).padding(12.dp).semantics { contentDescription = name }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                sticker.blob?.let { StickerMedia(format ?: infoFormat, Media.stickerUrl(it, 64, density), "", 48.dp) }
                Column(Modifier.weight(1f)) {
                    Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                    if (packName != null) Txt(t("chat.stickerPopout.fromPack", "name" to packName), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                }
            }
            NxButton(t("reports.report"), { close(); fish.nexa.ui.reports.openReport(fish.nexa.data.ReportContext(kind = "sticker", id = sticker.id)) }, kind = fish.nexa.ui.components.ButtonKind.GHOST, size = ButtonSize.SM, block = true)
            when {
                !loaded -> Box(Modifier.fillMaxWidth().background(c.bgFloating), contentAlignment = Alignment.Center) { Spinner(size = 16.dp) }
                packId != null -> NxButton(t("chat.stickerPopout.viewPack"), { close(); fish.nexa.ui.stickers.openPackSheet(packId, info?.pack, sticker.id) }, size = ButtonSize.SM, block = true)
            }
        }
    }
}
