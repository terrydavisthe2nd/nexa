package fish.nexa.ui.media

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.media.MusicCard
import fish.nexa.media.MusicTrack
import fish.nexa.media.Unfurl
import fish.nexa.media.mediaSrc
import fish.nexa.media.openExternal
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.profile.Equalizer
import fish.nexa.ui.profile.SongPreviews
import fish.nexa.ui.profile.SongVolume
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.topEdge
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MusicEmbed.tsx: a link to music drawn as music. Deezer's and Apple Music's
 * thirty-second previews play in the app's own player (SongPreviews, one at a
 * time with the songs on profiles); Spotify's and SoundCloud's own players
 * open in place, served through Nexa (internal/api/music_players.go) so
 * neither learns who is listening.
 */
private data class MusicSite(val name: String, val color: Color)

private val SITES = mapOf(
    "spotify" to MusicSite("Spotify", Color(0xFF1ED760)),
    "apple" to MusicSite("Apple Music", Color(0xFFFA2D48)),
    "deezer" to MusicSite("Deezer", Color(0xFFA238FF)),
    "soundcloud" to MusicSite("SoundCloud", Color(0xFFFF5500)),
)

private val DEEZER_PREVIEW = Regex("^/media/music/preview/\\d{1,15}$")

/** Where a preview's sound comes from: Deezer's through this server, anything else through the proxy. */
@Composable
private fun previewUrl(src: String?): String? {
    if (src == null) return null
    if (DEEZER_PREVIEW.matches(src)) return Nexa.api.baseUrl + src
    val signed = mediaSrc(src)?.takeIf { it.isNotEmpty() } ?: return null
    return if (signed.startsWith("/")) Nexa.api.baseUrl + signed else signed
}

@Composable
private fun Explicit() {
    val c = Nx.colors
    Box(
        Modifier.size(15.dp).background(c.textMuted, RoundedCornerShape(3.dp)).semantics { contentDescription = t("chat.embeds.music.explicit") },
        contentAlignment = Alignment.Center,
    ) { Txt("E", style = Nx.type.hint.copy(fontSize = Nx.type.sp(10f), fontWeight = FontWeight(800), lineHeight = Nx.type.sp(10f)), color = c.embedBg) }
}

@Composable
private fun Pill(text: String, site: Color) {
    val c = Nx.colors
    Box(Modifier.background(site.copy(alpha = 0.14f), CircleShape).padding(horizontal = 6.dp)) {
        Txt(text, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(16f)), color = lerp(c.text, site, 0.7f), maxLines = 1)
    }
}

@Composable
fun MusicEmbed(e: Unfurl, m: MusicCard) {
    val c = Nx.colors
    val site = SITES[m.platform] ?: MusicSite(m.platform, c.brand)
    val owner = remember { "music-" + System.nanoTime() }
    DisposableEffect(owner) { onDispose { SongPreviews.stop(owner) } }
    LaunchedEffect(SongPreviews.playingId) { if (SongPreviews.playingId?.startsWith(owner) == true) SongPreviews.track() }
    val art = mediaSrc(m.art)
    val icon = mediaSrc(e.icon)
    val preview = previewUrl(m.preview)
    val player = m.player == "spotify" || m.player == "soundcloud"
    var open by remember(e.url) { mutableStateOf(false) }
    val key = "$owner:main"
    val on = SongPreviews.playingId == key
    val state = if (on) SongPreviews.state else null
    val tracks = m.tracks.take(5)
    val by = tracks.any { it.artist != null && it.artist != m.artist && it.artist != m.title }
    val press: (() -> Unit)? = when {
        player -> ({ SongPreviews.stopAny(); open = true })
        preview != null -> ({ SongPreviews.play(key, preview, owner) })
        else -> null
    }
    val shape = Radii.Md
    Box(
        Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .shadowLow(shape, c)
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .background(c.embedBg)
            .topEdge(shape, c.edgeLight),
    ) {
        // the record's own colours, blurred into the card behind everything (blur needs Android 12)
        if (!art.isNullOrEmpty() && !(open && player) && android.os.Build.VERSION.SDK_INT >= 31) {
            AsyncImage(art, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, alpha = 0.9f, modifier = Modifier.matchParentSize().blur(36.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.embedBg.copy(alpha = 0.58f), c.embedBg.copy(alpha = 0.82f)))))
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var broken by remember(icon) { mutableStateOf(false) }
                if (!icon.isNullOrEmpty() && !broken) AsyncImage(icon, null, imageLoader = Media.loader, modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)), onError = { broken = true })
                Txt(site.name, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
                Pill(t("chat.embeds.music.kind.${m.kind}"), site.color)
            }
            if (open && player) {
                PlayerView(m)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val round = m.kind == "artist"
                    val coverShape = if (round) CircleShape else Radii.Sm
                    Box(Modifier.size(84.dp).ring(coverShape, c.borderSubtle).clip(coverShape).background(lerp(c.bgInput, site.color, 0.18f))) {
                        if (!art.isNullOrEmpty()) AsyncImage(art, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (press != null) Disc(
                            on, state, site.color, m.title, press,
                            if (round) Modifier.align(Alignment.Center) else Modifier.align(Alignment.BottomEnd).offset((-5).dp, (-5).dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt(
                            m.title, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(19f)), color = c.textStrong, maxLines = 2,
                            modifier = Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(e.url) },
                        )
                        if (m.artist != null && m.kind != "artist") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt(m.artist, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (m.explicit && m.kind == "track") Explicit()
                        }
                        val album = m.album?.takeIf { (m.kind == "track" || m.kind == "episode") && it != m.title }
                        if (album != null) Txt(album, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                        val meta = listOfNotNull(
                            m.year?.toString(),
                            m.count?.takeIf { it > 0 }?.let { t("chat.embeds.music.songs", "count" to it) },
                            m.duration?.takeIf { m.kind == "track" || m.kind == "episode" }?.let { Time.duration(it) },
                        )
                        if (meta.isNotEmpty()) Txt(meta.joinToString(" · "), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontFeatureSettings = "tnum"), color = c.textMuted, maxLines = 1)
                    }
                }
                if (preview != null && !player) PreviewStrip(key, site.color)
                if (tracks.isNotEmpty()) Column(Modifier.offset(x = (-6).dp)) {
                    tracks.forEachIndexed { i, tr -> TrackRow(tr, i, "$owner:$i", owner, site.color, by, if (player) press else null) }
                }
            }
        }
    }
}

/**
 * Under the cover while a preview can play (the web's PreviewStrip): how far
 * through it is (a bar to tap or drag once it plays), its times, and how loud
 * previews play.
 */
@Composable
private fun PreviewStrip(key: String, site: Color) {
    val c = Nx.colors
    val on = SongPreviews.playingId == key
    val progress = if (on) SongPreviews.progress else 0f
    val total = if (on && SongPreviews.duration > 0f) SongPreviews.duration else 30f
    val time = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(Time.duration((progress * total).toDouble()), style = time, color = if (on) c.textStrong else c.textMuted, maxLines = 1)
        var width by remember { mutableStateOf(1f) }
        fun at(x: Float) = (x / width).coerceIn(0f, 1f)
        Box(
            Modifier
                .weight(1f)
                .height(32.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(on) { if (on) detectTapGestures { pos -> SongPreviews.seek(key, at(pos.x)) } }
                .pointerInput(on) {
                    if (on) detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        SongPreviews.seek(key, at(change.position.x))
                    }
                }
                .semantics {
                    contentDescription = t("chat.embeds.music.seek")
                    stateDescription = "${Time.duration((progress * total).toDouble())} / ${Time.duration(total.toDouble())}"
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.text.copy(alpha = 0.16f), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(progress).height(4.dp).background(site, RoundedCornerShape(2.dp)))
            if (on) Box(Modifier.graphicsLayer { translationX = progress * (width - 12.dp.toPx()) }.size(12.dp).background(c.textStrong, CircleShape))
        }
        Txt(Time.duration(total.toDouble()), style = time, color = c.textMuted, maxLines = 1)
        SongVolume(fill = site, slider = LocalConfiguration.current.screenWidthDp > 520)
    }
}

/** The play disc on the cover; while a preview plays, a ring in the service's colour runs round it. */
@Composable
private fun Disc(on: Boolean, state: String?, site: Color, title: String, press: () -> Unit, modifier: Modifier) {
    val playing = on && state == "playing"
    val progress by animateFloatAsState(if (on) SongPreviews.progress else 0f, tween(if (Nx.motion.reduced) 0 else 250), label = "ring")
    Box(
        modifier
            .size(40.dp)
            .background(if (on) site else Color.Black.copy(alpha = 0.62f), CircleShape)
            .clip(CircleShape)
            .pressable(remember { MutableInteractionSource() }) { press() }
            .semantics { contentDescription = t(if (playing) "widgets.music.pause" else "widgets.music.play", "name" to title) },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Canvas(Modifier.fillMaxSize().padding(1.dp)) {
            val w = 2.5.dp.toPx()
            drawArc(Color.White, -90f, 360f * progress, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
        }
        val tint = if (on) Color.Black else Color.White
        when {
            on && state == "loading" -> Spinner(color = tint, size = 20.dp)
            playing -> Icon("Pause", tint, size = 18.dp, filled = true)
            else -> Icon("Play", tint, size = 18.dp, filled = true, modifier = Modifier.offset(x = 1.dp))
        }
    }
}

@Composable
private fun TrackRow(tr: MusicTrack, index: Int, key: String, owner: String, site: Color, by: Boolean, open: (() -> Unit)?) {
    val c = Nx.colors
    val src = previewUrl(tr.preview)
    val on = SongPreviews.playingId == key
    val state = if (on) SongPreviews.state else null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press: (() -> Unit)? = if (src != null) ({ SongPreviews.play(key, src, owner) }) else open
    val lit = lerp(c.textStrong, site, 0.75f)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (pressed) c.text.copy(alpha = 0.06f) else Color.Transparent)
                .then(
                    if (press != null) Modifier.pressable(interaction) { press() }
                        .semantics { contentDescription = t(if (on && state != "paused") "widgets.music.pause" else "widgets.music.play", "name" to tr.title) }
                    else Modifier,
                )
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.widthIn(min = 18.dp).height(16.dp), contentAlignment = Alignment.CenterEnd) {
                when {
                    state == "loading" -> Spinner(color = lit, size = 14.dp)
                    state == "playing" -> Equalizer(site)
                    state == "paused" -> Icon("Play", lit, size = 13.dp, filled = true)
                    else -> Txt((index + 1).toString(), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650), fontFeatureSettings = "tnum"), color = c.textMuted)
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(tr.title, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(550), lineHeight = Nx.type.sp(18f)), color = if (on) lit else c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (tr.explicit) Explicit()
            }
            // who made each track only beside a wide card, as on the web, where a phone leaves it out
            if (by && LocalConfiguration.current.screenWidthDp >= 520) Txt(tr.artist.orEmpty(), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.textMuted, maxLines = 1, modifier = Modifier.weight(0.5f))
            tr.duration?.let { Txt(Time.duration(it), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontFeatureSettings = "tnum"), color = c.textMuted) }
        }
        if (on) {
            val progress by animateFloatAsState(SongPreviews.progress, tween(250), label = "played")
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 1.dp).fillMaxWidth().height(2.dp)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(site, RoundedCornerShape(1.dp)))
            }
        }
    }
}

/** The page around the player's frame; the frame is sandboxed as the web's is. */
private fun framePage(src: String, spotify: Boolean): String {
    val quoted = src.replace("&", "&amp;").replace("\"", "&quot;")
    val play = if (spotify) "<script>addEventListener('message',function(e){var f=document.getElementById('p');" +
        "if(f&&e.source===f.contentWindow&&e.data&&e.data.type==='ready')f.contentWindow.postMessage({command:'play'},'*')})</script>" else ""
    return "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;height:100%;background:transparent}" +
        "iframe{display:block;border:0;width:100%;height:100%}</style>$play" +
        "<iframe id=p src=\"$quoted\" sandbox=\"allow-scripts allow-presentation\" allow=\"autoplay; encrypted-media\" referrerpolicy=no-referrer></iframe>"
}

/**
 * The service's own player, opened through Nexa in a web view that can reach
 * nothing but Nexa (the page's policy says so) and opens every link it is
 * asked to follow in the browser instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlayerView(m: MusicCard) {
    val c = Nx.colors
    var url by remember(m.id) { mutableStateOf<String?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.player, m.kind, m.id) {
        runCatching { Nexa.api.get("/music/player/${m.player}/${m.kind}/${m.id}") }
            .onSuccess { r ->
                val u = runCatching { r.jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()
                if (u != null && (u.startsWith("/sp/") || u.startsWith("/sc/"))) url = Nexa.api.baseUrl + u else failed = true
            }
            .onFailure { failed = true }
    }
    val tall = m.kind != "track" && m.kind != "episode"
    val height = when {
        m.player == "soundcloud" && !tall -> 166.dp
        tall -> 352.dp
        else -> 152.dp
    }
    val bg = if (m.player == "soundcloud") Color.White else Color(0xFF121212)
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        val u = url
        when {
            failed -> Txt(t("chat.embeds.music.playerFailed"), style = Nx.type.bodySmall, color = Color(0xFFDDDDDD), modifier = Modifier.padding(12.dp))
            u == null -> Spinner(color = Color(0xFF999999))
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(bg.toArgb())
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                // the player's own links ("Open in Spotify") leave for the browser or the service's app
                                val to = request.url.toString()
                                if (to.startsWith("http://") || to.startsWith("https://")) openExternal(to)
                                return true
                            }
                        }
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        // the player sits in a frame, as on the web: it picks its layout from the
                        // frame's size, and Spotify's takes the play command only from a page around it
                        post { loadDataWithBaseURL(Nexa.api.baseUrl + "/", framePage(u, m.player == "spotify"), "text/html", "utf-8", null) }
                    }
                },
                onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
            )
        }
    }
}
