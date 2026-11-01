package fish.nexa.ui.media

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.crypto.AttachmentMeta
import fish.nexa.data.ChatMessage
import fish.nexa.media.Media
import fish.nexa.media.Priority
import fish.nexa.media.Saving
import fish.nexa.media.loadAttachment
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the viewer shows: an attachment to open whole, or a picture address (previews, posts). */
data class LightboxItem(
    val name: String,
    val type: String? = null,
    val att: AttachmentMeta? = null,
    val src: String? = null,
    val from: String? = null,
    val cm: ChatMessage? = null,
    val jump: (() -> Unit)? = null,
)

private const val MAX_ZOOM = 5f
private const val DOUBLE_ZOOM = 2.5f
private val SPRING_BACK = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Opens the viewer on [items] at [start] (Lightbox.tsx), over everything; Back closes it. */
fun openLightbox(items: List<LightboxItem>, start: Int) {
    if (items.isEmpty()) return
    lateinit var layer: Layer
    layer = Layer("lightbox") { close -> LightboxView(layer, items, start.coerceIn(0, items.size - 1), close) }
    Layers.open(layer)
}

@Composable
private fun LightboxView(layer: Layer, items: List<LightboxItem>, start: Int, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(180)) }
    layer.dismiss = {
        scope.launch {
            if (!reduced) enter.animateTo(0f, tween(140))
            Layers.remove(layer)
        }
    }
    val pager = rememberPagerState(initialPage = start) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    // how far a downward or upward swipe has pulled the picture, and how dim the room is
    val pull = remember { Animatable(0f) }
    val dim = max(0.2f, 1f - abs(pull.value) / 500f)
    val item = items[pager.currentPage]
    Saving.Track()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value }
            // the web dims to 86% and blurs what is behind; nothing here can blur the chat behind a
            // layer, so the room is darker instead, to leave the chat as unreadable as the blur does
            .background(Color(0xFF08050E).copy(alpha = 0.95f * dim)),
    ) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed && pull.value == 0f,
            modifier = Modifier.fillMaxSize(),
            key = { items[it].att?.blobId ?: items[it].src ?: it },
        ) { page ->
            val it = items[page]
            Box(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .graphicsLayer {
                        if (page == pager.currentPage) {
                            translationY = pull.value
                            val s = max(0.82f, 1f - abs(pull.value) / 2400f)
                            scaleX = s; scaleY = s
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (it.type?.startsWith("video/") == true) {
                    LightboxVideo(it)
                } else ZoomablePicture(
                    item = it,
                    active = page == pager.currentPage,
                    onZoomed = { z -> if (page == pager.currentPage) zoomed = z },
                    onClose = close,
                    onPull = { dy -> scope.launch { pull.snapTo(dy) } },
                    onRelease = { dy, speed ->
                        scope.launch {
                            if (abs(dy) > 110 * density || speed > 0.7f) {
                                pull.animateTo(dy + Math.signum(dy) * 240 * density, tween(160))
                                close()
                            } else pull.animateTo(0f, tween(if (reduced) 0 else 220, easing = SPRING_BACK))
                        }
                    },
                )
            }
        }
        TopBar(item, pager.currentPage, items.size, close)
        if (items.size > 1 && !zoomed) {
            val prev = pager.currentPage > 0
            val next = pager.currentPage < items.size - 1
            if (prev) SideButton(if (rtl) "ChevronRight" else "ChevronLeft", t("chat.lightbox.previous"), Modifier.align(Alignment.CenterStart)) {
                scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
            }
            if (next) SideButton(if (rtl) "ChevronLeft" else "ChevronRight", t("chat.lightbox.next"), Modifier.align(Alignment.CenterEnd)) {
                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}

/** The bar along the top: the name over who sent it and where in the set, then open, save and close. */
@Composable
private fun TopBar(item: LightboxItem, index: Int, count: Int, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x40000000))))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(item.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = Color.White, maxLines = 1)
            val sub = listOfNotNull(item.from?.takeIf { it.isNotEmpty() }, if (count > 1) t("chat.lightbox.position", "n" to index + 1, "count" to count) else null)
            if (sub.isNotEmpty()) Txt(sub.joinToString("   "), style = Nx.type.hint, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
        }
        if (item.jump != null) Tool("MessageSquare", t("chat.lightbox.goToMessage")) {
            close()
            item.jump.invoke()
        }
        // a media channel that hides downloads offers no save or open here either
        val noDownload = fish.nexa.data.MediaDownloads.hidden(item.cm?.m?.channelId)
        if (!noDownload && item.type?.contains("svg") != true) Tool("ExternalLink", t("chat.attachments.openOriginal")) { scope.launch { openOriginal(item) } }
        if (!noDownload) Tool("Download", t("chat.attachments.download")) { save(item) }
        Tool("X", t("chat.attachments.close"), strong = true, iconSize = 22) { close() }
    }
}

@Composable
private fun Tool(icon: String, label: String, strong: Boolean = false, iconSize: Int = 20, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = if (strong) 4.dp else 0.dp)
            .size(44.dp)
            .clip(Radii.Sm)
            .background(Color.White.copy(alpha = if (strong) 0.14f else 0.08f))
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, Color.White, size = iconSize.dp) }
}

@Composable
private fun SideButton(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(horizontal = 12.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, Color.White, size = 28.dp) }
}

/** The whole picture as a file on this device (the attachment opened, or the address fetched). */
private suspend fun fileOf(item: LightboxItem): File? {
    item.att?.let { return loadAttachment(it, Priority.OPEN) }
    val src = item.src ?: return null
    return withContext(Dispatchers.IO) {
        val dir = File(Nexa.app.cacheDir, "linked").apply { mkdirs() }
        val out = File(dir, src.hashCode().toUInt().toString(16))
        if (!out.exists() || out.length() == 0L) {
            Nexa.api.http.newCall(okhttp3.Request.Builder().url(src).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("http ${r.code}")
                val tmp = File(dir, out.name + ".part")
                tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                tmp.renameTo(out)
            }
        }
        out
    }
}

/** Open Original: the picture in another app that shows pictures. */
private suspend fun openOriginal(item: LightboxItem) {
    try {
        val f = fileOf(item) ?: return
        val ctx = Nexa.app
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val ext = item.name.substringAfterLast('.', "").ifEmpty { "png" }
        val out = File(dir, "original.$ext")
        withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.type ?: "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(intent, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Nexa.ui.toast(t("chat.attachments.imageUnloadable"), "error")
    }
}

private fun save(item: LightboxItem) {
    Nexa.scope.launch {
        try {
            val f = fileOf(item) ?: return@launch
            withContext(Dispatchers.Main) {
                Saving.save(item.name, item.type ?: "image/png", write = { out -> withContext(Dispatchers.IO) { f.inputStream().use { it.copyTo(out) } } }) { err ->
                    if (err != null) Nexa.ui.toast(t("chat.messageMenu.couldNotSaveThePicture"), "error") else Nexa.ui.toast(t("settings.account.saved"), "success")
                }
            }
        } catch (e: Exception) {
            Nexa.ui.toast(t("chat.messageMenu.couldNotSaveThePicture"), "error")
        }
    }
}

/**
 * ZoomPan.tsx and the viewer's touch rules: pinch up to 5x and drag when
 * zoomed; a double tap zooms to 2.5x where it lands, or back; one tap closes
 * once the double-tap moment has passed; not zoomed, a vertical swipe pulls
 * the picture away and a sideways one goes to the next. A long press offers
 * Copy Image and Save Image.
 */
@Composable
private fun ZoomablePicture(
    item: LightboxItem,
    active: Boolean,
    onZoomed: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPull: (Float) -> Unit,
    onRelease: (Float, Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var file by remember(item) { mutableStateOf(item.att?.let { Media.cached(it.blobId, it.plain) }) }
    val copy = remember(item) { item.att?.preview?.let { Media.cached(it.blobId) } }
    var failed by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) {
        if (item.att != null && file == null) {
            try {
                file = loadAttachment(item.att, Priority.OPEN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
    }
    var scale by remember(item) { mutableFloatStateOf(1f) }
    var offset by remember(item) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f; offset = Offset.Zero
        }
    }
    LaunchedEffect(scale) { onZoomed(scale > 1.01f) }
    val reduced = Nx.motion.reduced
    val zoomAnim = remember { Animatable(1f) }
    // the picture's own size, for how far it may move once zoomed (lib/zoomPan.ts clampView)
    var natural by remember(item) { mutableStateOf(androidx.compose.ui.geometry.Size.Unspecified) }
    /**
     * Holds an offset to where the picture's edges stay at or beyond the
     * screen's: it moves only as far as it is larger than the screen, and while
     * narrower (or shorter) it stays in the middle that way. Measuring the
     * screen alone let a wide picture on a tall phone be dragged out of sight.
     */
    fun clamp(o: Offset, s: Float, w: Float, h: Float): Offset {
        val n = natural
        val known = n != androidx.compose.ui.geometry.Size.Unspecified && n.width > 0 && n.height > 0 && n.width.isFinite() && n.height.isFinite()
        val fit = if (known) minOf(w / n.width, h / n.height) else 1f
        val cw = if (known) n.width * fit else w
        val ch = if (known) n.height * fit else h
        val mx = max(0f, (cw * s - w) / 2)
        val my = max(0f, (ch * s - h) / 2)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    val model: Any? = file ?: copy ?: item.src
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t0 = System.currentTimeMillis()
                    var total = Offset.Zero
                    // 0 undecided, 1 zoom or pan, 2 pulling away, 3 left to the pager
                    var mode = 0
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) mode = 1
                        val one = ev.changes.firstOrNull { it.id == down.id }
                        if (one != null) total += one.positionChange()
                        if (mode == 0 && (abs(total.x) > slop || abs(total.y) > slop)) {
                            mode = when {
                                scale > 1.01f -> 1
                                abs(total.y) > 12 * density && abs(total.y) > 1.3f * abs(total.x) -> 2
                                else -> 3
                            }
                        }
                        if (mode == 3) return@awaitEachGesture
                        if (mode == 1) {
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val centroid = ev.calculateCentroid(useCurrent = true)
                            val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val c = Offset(size.width / 2f, size.height / 2f)
                            // the point between the fingers stays where it is while the scale changes
                            val p = centroid - c
                            val moved = (offset - p) * (next / scale) + p + pan
                            scale = next
                            offset = if (next <= 1.001f) Offset.Zero else clamp(moved, next, size.width.toFloat(), size.height.toFloat())
                            ev.changes.forEach { it.consume() }
                        } else if (mode == 2 && one != null) {
                            onPull(total.y)
                            one.consume()
                        }
                    }
                    if (mode == 2) {
                        val ms = max(1L, System.currentTimeMillis() - t0)
                        onRelease(total.y, abs(total.y) / density / ms)
                    }
                }
            }
            .pointerInput(item) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        scope.launch {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            if (scale > 1.01f) {
                                zoomAnim.snapTo(scale)
                                val from = offset
                                val start = scale
                                zoomAnim.animateTo(1f, tween(if (reduced) 0 else 200)) {
                                    val f = if (start == 1f) 1f else (value - 1f) / (start - 1f)
                                    scale = value
                                    offset = from * f
                                }
                                scale = 1f; offset = Offset.Zero
                            } else {
                                val p = at - c
                                val target = clamp(p * (1 - DOUBLE_ZOOM), DOUBLE_ZOOM, size.width.toFloat(), size.height.toFloat())
                                zoomAnim.snapTo(1f)
                                zoomAnim.animateTo(DOUBLE_ZOOM, tween(if (reduced) 0 else 200)) {
                                    val f = (value - 1f) / (DOUBLE_ZOOM - 1f)
                                    scale = value
                                    offset = target * f
                                }
                            }
                        }
                    },
                    onTap = { if (scale <= 1.01f) onClose() },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!fish.nexa.data.MediaDownloads.hidden(item.cm?.m?.channelId)) openMenu(imageMenuItems(ImageTarget(item.att, item.src, item.name, item.type, item.cm), separatorAfter = false), haptic = false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model, item.name,
                imageLoader = Media.loader,
                contentScale = ContentScale.Fit,
                onSuccess = { st -> natural = st.painter.intrinsicSize },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            )
        }
        if (failed && file == null) Txt(t("chat.attachments.imageUnloadable"), style = Nx.type.body, color = Color.White)
        else if (file == null && item.att != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(40.dp).background(Color(0x8C000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Spinner(Color.White, 18.dp) }
        }
    }
}

/** A video opened in the viewer (from search's media): the player, starting by itself. */
@Composable
private fun LightboxVideo(item: LightboxItem) {
    val att = item.att
    if (att != null) VideoPlayer(VideoSource.Attachment(att), item.name, att.w, att.h)
    else if (item.src != null) VideoPlayer(VideoSource.Url(item.src), item.name, null, null)
}
