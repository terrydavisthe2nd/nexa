package fish.nexa.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.crypto.AttachmentMeta
import fish.nexa.media.AUTO_LOAD_MAX
import fish.nexa.media.Downloads
import fish.nexa.media.Fit
import fish.nexa.media.MediaPrefs
import fish.nexa.media.Saving
import fish.nexa.media.Streams
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import kotlinx.coroutines.delay

/** Where a player's video comes from: an attachment (streamed as it decrypts) or a link's file (with its own sound, for Reddit). */
sealed interface VideoSource {
    data class Attachment(val att: AttachmentMeta) : VideoSource
    data class Url(val url: String, val audio: String? = null) : VideoSource
}

/**
 * One player's engine and what the controls read from it. Made when the
 * video is first wanted, released when its row goes, and handed to the full
 * screen view and back without stopping.
 */
@OptIn(UnstableApi::class)
@Stable
class VideoHandle(private val source: VideoSource) {
    var player: ExoPlayer? by mutableStateOf(null)
        private set
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var natural by mutableStateOf<Pair<Int, Int>?>(null)
    var failed by mutableStateOf(false)
    var muted by mutableStateOf(MediaPrefs.get().muted)
    var speed by mutableFloatStateOf(MediaPrefs.get().speed)
    var full by mutableStateOf(false)

    fun ensure(context: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(Streams.factory())).build()
        val factory = ProgressiveMediaSource.Factory(Streams.factory())
        when (source) {
            is VideoSource.Attachment -> p.setMediaItem(MediaItem.fromUri(Streams.uriOf(source.att)))
            is VideoSource.Url -> {
                val video = factory.createMediaSource(MediaItem.fromUri(source.url))
                if (source.audio != null) p.setMediaSource(MergingMediaSource(video, factory.createMediaSource(MediaItem.fromUri(source.audio))))
                else p.setMediaSource(video)
            }
        }
        p.volume = if (muted) 0f else MediaPrefs.get().volume
        p.setPlaybackSpeed(speed)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) duration = maxOf(0L, p.duration)
                if (state == Player.STATE_ENDED) {
                    p.pause()
                    p.seekTo(0)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) natural = videoSize.width to videoSize.height
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        })
        p.prepare()
        player = p
        return p
    }

    fun toggle(context: Context) {
        val p = ensure(context)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seek(ms: Long) {
        val p = player ?: return
        p.seekTo(ms.coerceIn(0L, maxOf(0L, duration)))
        position = p.currentPosition
    }

    fun setMute(on: Boolean) {
        muted = on
        MediaPrefs.save(muted = on)
        player?.volume = if (on) 0f else MediaPrefs.get().volume
    }

    fun setSpeedTo(v: Float) {
        speed = v
        MediaPrefs.save(speed = v)
        player?.setPlaybackSpeed(v)
    }

    fun release() {
        player?.release()
        player = null
        playing = false
    }
}

private val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)
private const val CONTROLS_LINGER = 2800L

/** A clock as the web writes it: m:ss, or h:mm:ss past an hour. */
private fun clock(ms: Long): String = Time.duration(maxOf(0L, ms) / 1000.0)

/**
 * VideoPlayer.tsx: a black frame in the video's shape (fitVideo), a big play
 * button while paused, and a bar along the bottom that a tap brings back and
 * that fades while playing: the seek track, play, the time, mute, then speed
 * and download in a menu on a phone, and full screen.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    source: VideoSource,
    name: String,
    width: Double?,
    height: Double?,
    poster: String? = null,
    spoiler: Boolean = false,
    sizeHint: String? = null,
    eager: Boolean = true,
    onDownload: (() -> Unit)? = null,
    /** As wide as its line, in its own shape (a note's video). */
    fill: Boolean = false,
) {
    val context = LocalContext.current
    val handle = remember(source) { VideoHandle(source) }
    DisposableEffect(handle) { onDispose { if (!handle.full) handle.release() } }
    LaunchedEffect(handle, eager) { if (eager) handle.ensure(context) }
    val shape = handle.natural?.let { it.first.toDouble() to it.second.toDouble() }
        ?: if (width != null && height != null && width > 0 && height > 0) width to height else null
    val box = Fit.video(shape?.first ?: 640.0, shape?.second ?: 360.0)
    Box(
        (if (fill) Modifier.fillMaxWidth().aspectRatio(ratioOf(shape?.first, shape?.second))
        else Modifier.padding(top = 4.dp).width(box.width.dp).aspectRatio(box.width.toFloat() / box.height))
            .clip(Radii.Sm)
            .background(Color.Black),
    ) {
        if (handle.full) return@Box
        PlayerBody(handle, name, poster, spoiler, sizeHint, onDownload, fullScreen = false) { openFull(handle, name, poster, onDownload, context) }
    }
}

/** What the inline and the full screen player share: the picture, the spoiler cover, the controls. */
@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.PlayerBody(
    handle: VideoHandle,
    name: String,
    poster: String?,
    spoiler: Boolean,
    sizeHint: String?,
    onDownload: (() -> Unit)?,
    fullScreen: Boolean,
    onFull: () -> Unit,
) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(!spoiler || fullScreen) }
    var ui by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    val player = handle.player
    // the bar fades 2.8 s after the last touch while playing
    LaunchedEffect(handle.playing, touched) {
        if (!handle.playing) {
            ui = true
            return@LaunchedEffect
        }
        delay(CONTROLS_LINGER)
        ui = false
    }
    // the time and the buffered part, read four times a second while there is a player
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            handle.position = p.currentPosition
            handle.buffered = p.bufferedPosition
            if (p.duration > 0) handle.duration = p.duration
            delay(250)
        }
    }
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(revealed, ui) {
                detectTapGestures(
                    onTap = {
                        when {
                            !revealed -> revealed = true
                            !ui -> {
                                ui = true; touched++
                            }
                            else -> {
                                handle.toggle(context); touched++
                            }
                        }
                    },
                    onDoubleTap = { if (revealed) onFull() },
                )
            },
    ) {
        if (player != null) ContentFrame(
            player,
            Modifier.fillMaxSize().then(if (!revealed && canBlur) Modifier.spoilerBlur() else Modifier),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
        )
        else if (poster != null && (revealed || canBlur)) AsyncImage(poster, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().then(if (!revealed) Modifier.spoilerBlur() else Modifier))
    }
    if (!revealed) {
        SpoilerCover(13.5f) { revealed = true }
        return
    }
    if (handle.failed) {
        Column(
            Modifier.matchParentSize().background(Nx.colors.bgSurface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(t("android.media.cannotPlayVideo"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = Nx.colors.textMuted, align = TextAlign.Center)
            if (onDownload != null) Box(
                Modifier.clip(Radii.Xs).background(Nx.colors.brand).pressable(remember { MutableInteractionSource() }, onClick = onDownload).padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Txt(t("chat.videoPlayer.downloadIt"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = Nx.colors.onBrand) }
        }
        return
    }
    if (!handle.playing) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(64.dp)
                .background(Color(0x99000000), CircleShape)
                .pressable(remember { MutableInteractionSource() }) { handle.toggle(context); touched++ }
                .semantics { contentDescription = t("chat.videoPlayer.play") },
            contentAlignment = Alignment.Center,
        ) { Icon("Play", Color.White, size = 28.dp, filled = true) }
    }
    val bar by animateFloatAsState(if (ui) 1f else 0f, tween(if (Nx.motion.reduced) 0 else 160), label = "bar")
    if (bar > 0f) ControlBar(handle, sizeHint, onDownload, fullScreen, onFull, Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = bar }) { touched++ }
}

/** The bar: seek track over the buttons, always laid out left to right as the web's is. */
@Composable
private fun ControlBar(
    handle: VideoHandle,
    sizeHint: String?,
    onDownload: (() -> Unit)?,
    fullScreen: Boolean,
    onFull: () -> Unit,
    modifier: Modifier,
    onTouch: () -> Unit,
) {
    val context = LocalContext.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val narrow = maxWidth < 420.dp
            val tiny = maxWidth < 260.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xBF000000))))
                    .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 4.dp),
            ) {
                SeekTrack(handle, onTouch)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    BarButton(if (handle.playing) "Pause" else "Play", if (handle.playing) t("chat.media.pause") else t("chat.videoPlayer.play")) { handle.toggle(context); onTouch() }
                    val time = if (sizeHint != null && handle.duration <= 0) sizeHint
                    else if (tiny) clock(handle.position) else "${clock(handle.position)} / ${clock(handle.duration)}"
                    Txt(time, style = Nx.type.hint.copy(fontFeatureSettings = "tnum"), color = Color.White.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                    Spacer(Modifier.weight(1f))
                    BarButton(if (handle.muted) "VolumeX" else "Volume2", if (handle.muted) t("chat.media.unmute") else t("chat.media.mute")) { handle.setMute(!handle.muted); onTouch() }
                    if (narrow) {
                        BarButton("MoreVertical", t("chat.chat.more")) {
                            onTouch()
                            openMenu(
                                buildList {
                                    add(MenuItem.Slider(t("chat.audioPlayer.playbackSpeed"), handle.speed, 0.5f..2f, "×") { v -> handle.setSpeedTo((Math.round(v * 2) / 2f).coerceIn(0.5f, 2f)) })
                                    if (onDownload != null) add(MenuItem.Action(t("chat.attachments.download"), onClick = onDownload))
                                },
                                haptic = false,
                            )
                        }
                    } else {
                        Box(
                            Modifier.height(40.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) {
                                handle.setSpeedTo(SPEEDS[(SPEEDS.indexOf(handle.speed).coerceAtLeast(0) + 1) % SPEEDS.size]); onTouch()
                            }.padding(horizontal = 8.dp).semantics { contentDescription = t("chat.audioPlayer.playbackSpeed") },
                            contentAlignment = Alignment.Center,
                        ) { Txt("${trim(handle.speed)}×", style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = Color.White) }
                        if (onDownload != null) BarButton("Download", t("chat.videoPlayer.downloadVideo")) { onDownload(); onTouch() }
                    }
                    BarButton(if (fullScreen) "Minimize2" else "Maximize2", if (fullScreen) t("chat.videoPlayer.exitFullScreen") else t("chat.videoPlayer.fullScreen")) { onFull() }
                }
            }
        }
    }
}

private fun trim(v: Float): String = if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()

@Composable
private fun BarButton(icon: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(Radii.Xs)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, Color.White, size = 18.dp) }
}

/** The seek track: 28 dp to touch, a 4 dp line, buffered and played parts, and the knob; a drag here never opens a drawer. */
@Composable
private fun SeekTrack(handle: VideoHandle, onTouch: () -> Unit) {
    val c = Nx.colors
    val dur = handle.duration
    val played = if (dur > 0) (handle.position.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val buffered = if (dur > 0) (handle.buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(dur) {
                detectTapGestures { o -> handle.seek((o.x / size.width * dur).toLong()); onTouch() }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    handle.seek((change.position.x / size.width * dur).toLong())
                    onTouch()
                }
            }
            .semantics { contentDescription = t("chat.audioPlayer.seek") },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)))
        Box(Modifier.width(w * buffered).height(4.dp).background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
        Box(Modifier.width(w * played).height(4.dp).background(c.brand, RoundedCornerShape(2.dp)))
        Box(Modifier.offset(x = w * played - 7.dp).size(14.dp).background(Color.White, CircleShape))
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Full screen: the same player filling the screen, the system bars out of the way, turned sideways for a wide video. */
@OptIn(UnstableApi::class)
private fun openFull(handle: VideoHandle, name: String, poster: String?, onDownload: (() -> Unit)?, context: Context) {
    handle.ensure(context)
    handle.full = true
    lateinit var layer: Layer
    layer = Layer("video-full") { close ->
        val view = LocalView.current
        DisposableEffect(Unit) {
            val activity = context.findActivity()
            val before = activity?.requestedOrientation
            val wide = handle.natural?.let { it.first > it.second } ?: true
            if (wide) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            layer.dismiss = {
                handle.full = false
                Layers.remove(layer)
            }
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                if (activity != null && before != null) activity.requestedOrientation = before
                handle.full = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                PlayerBody(handle, name, poster, spoiler = false, sizeHint = null, onDownload = onDownload, fullScreen = true) { close() }
            }
        }
    }
    Layers.open(layer)
}

/** A post's GIF: a silent video that loops by itself; a tap pauses it or plays it again (Loop in PostEmbed.tsx). */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(url: String, poster: String?) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(Streams.factory())).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pressable(remember { MutableInteractionSource() }) { if (player.isPlaying) player.pause() else player.play() },
    ) {
        if (!poster.isNullOrEmpty()) AsyncImage(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        ContentFrame(player, Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Crop)
    }
}

/** An uploaded video: streamed as it decrypts, its first frame and length fetched by themselves when it is small. */
@OptIn(UnstableApi::class)
@Composable
fun VideoAttachment(att: AttachmentMeta, fill: Boolean = false, noDownload: Boolean = false) {
    Saving.Track()
    val download = {
        Saving.save(att.name, att.type, write = { out -> Downloads.writeAttachment(att, out) {} }) { err ->
            if (err != null) Nexa.ui.toast(t("chat.attachments.couldNotDownloadThatFile"), "error") else Nexa.ui.toast(t("settings.account.saved"), "success")
        }
    }
    VideoPlayer(
        VideoSource.Attachment(att), att.name, att.w, att.h,
        spoiler = att.spoiler == true,
        sizeHint = if (att.size > AUTO_LOAD_MAX) Time.bytes(att.size) else null,
        eager = att.size <= AUTO_LOAD_MAX,
        // a media channel that hides downloads offers none for its videos
        onDownload = if (noDownload) null else download,
        fill = fill,
    )
}
