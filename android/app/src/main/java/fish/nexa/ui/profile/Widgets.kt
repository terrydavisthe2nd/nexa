package fish.nexa.ui.profile

import androidx.annotation.OptIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.ui.Icon
import fish.nexa.ui.components.AvatarColor
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** lib/widgets.ts: what people put on their profiles, read as safely as the web reads it. */
object Widgets {
    /** The most widgets a profile holds (lib/widgets.ts MAX_WIDGETS). */
    const val MAX = 8
    data class Game(val id: String?, val name: String, val cover: String?, val image: String?, val tags: List<String>)
    data class Music(val id: String?, val name: String, val by: String?, val art: String?, val image: String?, val duration: Int?)
    data class Stat(val label: String, val value: String)

    sealed interface Widget {
        val id: String
        val type: String
    }

    data class GameWidget(override val id: String, override val type: String, val games: List<Game>, val note: String?) : Widget
    data class MusicWidget(override val id: String, override val type: String, val music: List<Music>) : Widget
    data class CardWidget(override val id: String, val title: String, val image: String?, val shape: String?, val text: String?, val stats: List<Stat>, val link: String?, val linkLabel: String?) : Widget {
        override val type get() = "custom"
    }

    private val MOST_GAMES = mapOf("favorite" to 1, "playing" to 5, "wishlist" to 20, "played" to 20)
    private val MOST_MUSIC = mapOf("artists" to 12, "albums" to 12, "songs" to 10)

    private fun JsonObject.s(k: String, max: Int): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(max) ?: ""
    private fun JsonObject.match(k: String, rx: Regex): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { rx.matches(it) }

    private fun readGame(x: JsonObject): Game? {
        val name = x.s("name", 100).trim()
        if (name.isEmpty()) return null
        val tags = (x["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() }?.take(20) }?.take(3) ?: emptyList()
        return Game(x.match("id", Regex("^\\d{1,10}$")), name, x.match("cover", Regex("^[a-z0-9]{6,32}$")), x.match("image", Regex("^\\d{1,20}$")), tags)
    }

    private fun readMusic(x: JsonObject, type: String): Music? {
        val name = x.s("name", 100).trim()
        if (name.isEmpty()) return null
        val by = if (type == "artists") "" else x.s("by", 100).trim()
        val secs = (x["duration"] as? JsonPrimitive)?.doubleOrNull
        val duration = if (type == "songs" && secs != null && secs > 0 && secs <= 86400) Math.round(secs).toInt() else null
        return Music(x.match("id", Regex("^\\d{1,20}$")), name, by.ifEmpty { null }, x.match("art", Regex("^[0-9a-f]{32}$")), x.match("image", Regex("^\\d{1,20}$")), duration)
    }

    fun read(list: List<JsonObject>): List<Widget> {
        val out = ArrayList<Widget>()
        for (w in list) {
            val id = (w["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "w${out.size}"
            when (val type = (w["type"] as? JsonPrimitive)?.contentOrNull) {
                "custom" -> {
                    val title = w.s("title", 40).trim()
                    if (title.isEmpty()) continue
                    val image = w.match("image", Regex("^\\d{1,20}$"))
                    val shape = if (image != null) (if ((w["shape"] as? JsonPrimitive)?.contentOrNull == "square") "square" else "wide") else null
                    val stats = (w["stats"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { Stat(it.s("label", 24), it.s("value", 40)) }?.filter { it.label.isNotEmpty() && it.value.isNotEmpty() }?.take(6) ?: emptyList()
                    val link = w["link"] as? JsonObject
                    val url = link?.let { (it["url"] as? JsonPrimitive)?.contentOrNull }?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    out += CardWidget(id, title, image, shape, w.s("text", 300).ifEmpty { null }, stats, url, if (url != null) link.s("label", 32).ifEmpty { null } else null)
                }
                "artists", "albums", "songs" -> {
                    val music = (w["music"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { m -> readMusic(m, type) } }?.take(MOST_MUSIC[type]!!) ?: emptyList()
                    if (music.isNotEmpty()) out += MusicWidget(id, type, music)
                }
                "favorite", "playing", "wishlist", "played" -> {
                    val games = (w["games"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::readGame) }?.take(MOST_GAMES[type]!!) ?: emptyList()
                    if (games.isEmpty()) continue
                    out += GameWidget(id, type, games, if (type == "favorite") w.s("note", 200).ifEmpty { null } else null)
                }
            }
        }
        return out.take(MAX)
    }

    /** A widget as PUT /users/@me/widgets takes it (lib/widgets.ts); the server cleans what it keeps. */
    fun toJson(w: Widget): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(w.id))
        put("type", JsonPrimitive(w.type))
        when (w) {
            is GameWidget -> {
                put("games", JsonArray(w.games.map { g ->
                    buildJsonObject {
                        g.id?.let { put("id", JsonPrimitive(it)) }
                        put("name", JsonPrimitive(g.name))
                        g.cover?.let { put("cover", JsonPrimitive(it)) }
                        g.image?.let { put("image", JsonPrimitive(it)) }
                        if (g.tags.isNotEmpty()) put("tags", JsonArray(g.tags.map { JsonPrimitive(it) }))
                    }
                }))
                w.note?.takeIf { it.isNotBlank() }?.let { put("note", JsonPrimitive(it)) }
            }
            is MusicWidget -> put("music", JsonArray(w.music.map { m ->
                buildJsonObject {
                    m.id?.let { put("id", JsonPrimitive(it)) }
                    put("name", JsonPrimitive(m.name))
                    m.by?.let { put("by", JsonPrimitive(it)) }
                    m.art?.let { put("art", JsonPrimitive(it)) }
                    m.image?.let { put("image", JsonPrimitive(it)) }
                    m.duration?.let { put("duration", JsonPrimitive(it)) }
                }
            }))
            is CardWidget -> {
                put("title", JsonPrimitive(w.title))
                w.image?.let { put("image", JsonPrimitive(it)); put("shape", JsonPrimitive(w.shape ?: "wide")) }
                w.text?.takeIf { it.isNotBlank() }?.let { put("text", JsonPrimitive(it)) }
                if (w.stats.isNotEmpty()) put("stats", JsonArray(w.stats.map { st ->
                    buildJsonObject { put("label", JsonPrimitive(st.label)); put("value", JsonPrimitive(st.value)) }
                }))
                w.link?.let { url ->
                    put("link", buildJsonObject {
                        put("url", JsonPrimitive(url))
                        w.linkLabel?.let { put("label", JsonPrimitive(it)) }
                    })
                }
            }
        }
    }

    fun title(type: String) = when (type) {
        "favorite" -> t("widgets.type.favorite")
        "playing" -> t("widgets.type.playing")
        "wishlist" -> t("widgets.type.wishlist")
        "played" -> t("widgets.type.played")
        "artists" -> t("widgets.type.artists")
        "albums" -> t("widgets.type.albums")
        "songs" -> t("widgets.type.songs")
        else -> t("widgets.type.custom")
    }

    fun name(w: Widget) = if (w is CardWidget) w.title else title(w.type)

    fun musicCount(type: String, count: Int) = when (type) {
        "artists" -> t("widgets.artistCount", "count" to count)
        "albums" -> t("widgets.albumCount", "count" to count)
        else -> t("widgets.songCount", "count" to count)
    }

    private fun base() = Nexa.api.baseUrl
    fun gamePicture(g: Game, css: Int, density: Float): String? = when {
        g.cover != null -> "${base()}/media/game/${g.cover}?size=${if (css > 80) "l" else "s"}"
        g.image != null -> Media.widgetUrl(g.image, css, density)
        else -> null
    }

    fun musicPicture(type: String, m: Music, css: Int, density: Float): String? = when {
        m.art != null -> "${base()}/media/music/${if (type == "artists") "artist" else "cover"}/${m.art}?size=${if (Media.sized(css, density) > 128) "l" else "s"}"
        m.image != null -> Media.widgetUrl(m.image, css, density)
        else -> null
    }

    fun musicLink(type: String, m: Music): String? = m.id?.let { "https://www.deezer.com/${if (type == "artists") "artist" else if (type == "albums") "album" else "track"}/$it" }

    fun songLength(secs: Int): String {
        val s = maxOf(0, secs)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    fun linkHost(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}

/**
 * previews.ts: one 30-second song preview at a time, played through Nexa's
 * server (it fetches from Deezer, so nobody's address reaches them), at a
 * volume of its own that every list's slider sets (starting from the one
 * voice notes and videos play at), kept on this device.
 */
@OptIn(UnstableApi::class)
object SongPreviews {
    private fun prefs() = Nexa.app.getSharedPreferences("media", android.content.Context.MODE_PRIVATE)

    /** How loud previews play, 0 to 1, and whether they are muted: this device's, kept. */
    var volume by mutableFloatStateOf(prefs().takeIf { it.contains("songVolume") }?.getFloat("songVolume", 1f)?.coerceIn(0f, 1f) ?: fish.nexa.media.MediaPrefs.get().volume.takeIf { it > 0f } ?: 1f)
        private set
    // a muted video says nothing about songs: pressing play on one means wanting to hear it
    var muted by mutableStateOf(if (prefs().contains("songVolume")) prefs().getBoolean("songMuted", false) else false)
        private set

    /** Sets how loud previews play, on the one playing now too, and keeps it on this device. */
    fun setVolume(v: Float, mute: Boolean) {
        volume = v.coerceIn(0f, 1f)
        muted = mute
        player?.volume = if (mute) 0f else volume
        if (SoundCloudWidget.active) SoundCloudWidget.volume(if (mute) 0f else volume)
        prefs().edit().putFloat("songVolume", volume).putBoolean("songMuted", mute).apply()
    }

    var playingId by mutableStateOf<String?>(null)
        private set
    var owner by mutableStateOf<String?>(null)
        private set
    /** "loading", "playing" or "paused". */
    var state by mutableStateOf("paused")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    /** How long the one playing is, in seconds, once known (0 before). */
    var duration by mutableFloatStateOf(0f)
        private set
    private var player: ExoPlayer? = null

    private fun idle() {
        playingId = null
        owner = null
        state = "paused"
        progress = 0f
        duration = 0f
    }

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(Nexa.app).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (playingId == null) return
                when (s) {
                    Player.STATE_BUFFERING -> state = "loading"
                    Player.STATE_ENDED -> idle()
                    Player.STATE_READY -> state = if (p.isPlaying) "playing" else "paused"
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (playingId == null) return
                if (isPlaying) state = "playing" else if (p.playbackState == Player.STATE_READY) state = "paused"
            }

            override fun onPlayerError(error: PlaybackException) {
                if (playingId == null) return
                val missing = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                Nexa.ui.toast(if (missing) t("widgets.music.noPreview") else t("widgets.music.previewFailed"), "error")
                idle()
            }
        })
        player = p
    }

    fun toggle(song: Widgets.Music, by: String) {
        val id = song.id ?: return
        play(id, "${Nexa.api.baseUrl}/media/music/preview/$id", by)
    }

    /** Plays any preview, or pauses it (and plays it on) when it is the one playing: a widget's song or a music card's. */
    fun play(key: String, url: String, by: String) {
        if (playingId == key && SoundCloudWidget.active) {
            if (state == "playing") SoundCloudWidget.pause() else SoundCloudWidget.play()
            return
        }
        SoundCloudWidget.stop()
        val p = player()
        if (playingId == key) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        playingId = key
        owner = by
        state = "loading"
        progress = 0f
        duration = 0f
        p.volume = if (muted) 0f else volume
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
    }

    /** The progress line under the row, read a few times a second while it plays. */
    suspend fun track() {
        while (true) {
            val p = player
            if (p != null && playingId != null && !SoundCloudWidget.active && p.duration > 0) {
                progress = (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f)
                duration = p.duration / 1000f
            }
            delay(250)
        }
    }

    /** Plays a SoundCloud track through its widget, out of sight (SoundCloudWidget), or pauses and plays it on. */
    fun playWidget(key: String, url: String, by: String) {
        if (playingId == key) {
            if (SoundCloudWidget.active) {
                if (state == "playing") SoundCloudWidget.pause() else SoundCloudWidget.play()
            }
            return
        }
        player?.stop()
        player?.clearMediaItems()
        playingId = key
        owner = by
        state = "loading"
        progress = 0f
        duration = 0f
        fun ours() = playingId == key
        SoundCloudWidget.start(url, if (muted) 0f else volume, object : SoundCloudWidget.Events {
            override fun loading() { if (ours()) state = "loading" }
            override fun playing() { if (ours()) state = "playing" }
            override fun paused() { if (ours()) state = "paused" }
            override fun progress(at: Float, seconds: Float) {
                if (!ours()) return
                progress = at
                if (seconds > 0f) duration = seconds
            }
            override fun ended() {
                if (!ours()) return
                SoundCloudWidget.stop()
                idle()
            }
            override fun failed() {
                if (!ours()) return
                SoundCloudWidget.stop()
                Nexa.ui.toast(t("chat.embeds.music.playerFailed"), "error")
                idle()
            }
        })
    }

    /** Moves the playing preview to a point from 0 to 1, when it is the one with this key. */
    fun seek(key: String, at: Float) {
        if (playingId == key && SoundCloudWidget.active) {
            val f = at.coerceIn(0f, 1f)
            SoundCloudWidget.seek(f)
            progress = f
            return
        }
        val p = player ?: return
        if (playingId != key || p.duration <= 0) return
        val f = at.coerceIn(0f, 1f)
        p.seekTo((f * p.duration).toLong())
        progress = f
    }

    /** Stops whatever preview is playing: a service's own player opening takes over. */
    fun stopAny() {
        owner?.let { stop(it) }
    }

    fun stop(by: String) {
        if (owner != by) return
        idle()
        SoundCloudWidget.stop()
        player?.stop()
        player?.clearMediaItems()
    }
}

/** The board: every widget in order, as the full profile shows them. */
@Composable
fun ProfileWidgets(widgets: List<Widgets.Widget>, onAdd: (() -> Unit)? = null, onShowAll: (() -> Unit)? = null, design: WidgetDesign? = null) {
    val c = Nx.colors
    if (widgets.isEmpty()) {
        if (onAdd == null) return
        FlowRow(
            Modifier.fillMaxWidth().border(1.5.dp, c.border, Radii.Sm).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(t("widgets.emptyOwn"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.textSubtle, modifier = Modifier.widthIn(min = 200.dp))
            ChipButton("LayoutGrid", t("widgets.add"), onAdd)
        }
        return
    }
    @Composable
    fun draw(w: Widgets.Widget) = when (w) {
        is Widgets.MusicWidget -> when (w.type) {
            "artists" -> Artists(w, onShowAll)
            "albums" -> Albums(w, onShowAll)
            else -> Songs(w, onShowAll)
        }
        is Widgets.CardWidget -> CardWidget(w)
        is Widgets.GameWidget -> when (w.type) {
            "favorite" -> Favorite(w)
            "playing" -> Playing(w)
            else -> Shelf(w, onShowAll)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (design == null) for (w in widgets) draw(w)
        else {
            widgets.forEachIndexed { i, w -> key(w.id) { Designed(w, i, widgets.size, design) { draw(w) } } }
            if (widgets.size < Widgets.MAX) AddWidgetTile(design.add)
        }
    }
}

/** One widget in the designer: pressing it changes it, and its tools sit along its top. */
@Composable
private fun Designed(w: Widgets.Widget, index: Int, count: Int, design: WidgetDesign, content: @Composable () -> Unit) {
    val c = Nx.colors
    val p = LocalProfile.current
    val name = Widgets.name(w)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().semantics { contentDescription = t("designer.widget.tools", "name" to name) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val pill = RoundedCornerShape(999.dp)
            val small = Nx.type.label.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(650))
            if (index == 0) Row(
                Modifier.height(24.dp).background(p.accent.copy(alpha = 0.26f), pill).padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon("Pin", c.textStrong, size = 12.dp)
                Txt(t("designer.widget.onCard"), style = small, color = c.textStrong, maxLines = 1)
            } else {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Row(
                    Modifier.height(24.dp).border(1.dp, if (pressed) c.border else c.borderSubtle, pill).clip(pill).pressable(interaction) { design.feature(w.id) }.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon("Pin", if (pressed) c.textStrong else c.textMuted, size = 12.dp)
                    Txt(t("designer.widget.showOnCard"), style = small, color = if (pressed) c.textStrong else c.textMuted, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f))
            WidgetTool("Pencil", t("designer.widget.editShort")) { design.edit(w.id) }
            WidgetTool("ArrowUp", t("reorder.moveUp"), enabled = index > 0) { design.move(w.id, -1) }
            WidgetTool("ArrowDown", t("reorder.moveDown"), enabled = index < count - 1) { design.move(w.id, 1) }
            WidgetTool("Trash2", t("widgets.remove"), danger = true) { design.remove(w.id) }
        }
        // the widget itself changes it when pressed, in the designer
        Box {
            content()
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .matchParentSize()
                    .background(if (pressed) p.accent.copy(alpha = 0.07f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .pressable(interaction) { design.edit(w.id) }
                    .semantics { contentDescription = t("designer.widget.edit", "name" to name) },
            )
        }
    }
}

/** A tool beside a widget in the designer: its chip colour, 32 dp to touch. */
@Composable
private fun WidgetTool(icon: String, label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .background(if (pressed) p.chipHover else p.chip, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (pressed && danger) c.textDanger else if (pressed) c.textStrong else c.textMuted, size = 14.dp) }
}

@Composable
fun ChipButton(icon: String?, label: String, onClick: () -> Unit) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (pressed) p.chipHover else p.chip)
            .topEdge(shape, c.edgeLight)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, c.textStrong, size = 15.dp)
        Txt(label, style = Nx.type.button.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong)
    }
}

@Composable
private fun Heading(text: String, count: Int? = null, countLabel: String? = null, extra: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Txt(text, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted, modifier = if (extra != null) Modifier.weight(1f) else Modifier)
        extra?.invoke()
        if (count != null) Txt(count.toString(), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600), fontFeatureSettings = "tnum"), color = c.textMuted, modifier = Modifier.semantics { if (countLabel != null) contentDescription = countLabel })
    }
}

/**
 * How loud song previews play (the web's SongVolume), on profiles and music
 * cards alike: the speaker mutes them and brings them back, and the slider
 * sets the volume for every preview at once and the one playing now.
 */
@Composable
fun SongVolume(fill: Color? = null, slider: Boolean = true) {
    val c = Nx.colors
    val volume = SongPreviews.volume
    val muted = SongPreviews.muted
    val silent = muted || volume == 0f
    val label = if (silent) t("chat.media.unmute") else t("chat.media.mute")
    val shown = if (muted) 0f else volume
    Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(Radii.Xs)
                // muted at nothing, it comes back at half rather than staying silent
                .pressable(remember { MutableInteractionSource() }) { if (silent) SongPreviews.setVolume(if (volume == 0f) 0.5f else volume, false) else SongPreviews.setVolume(volume, true) }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(if (silent) "VolumeX" else if (volume < 0.5f) "Volume1" else "Volume2", if (silent) c.textSubtle else c.textMuted, size = 16.dp) }
        if (!slider) return@Row
        var width by remember { mutableStateOf(1f) }
        fun at(x: Float) = (x / width).coerceIn(0f, 1f)
        Box(
            Modifier
                .size(96.dp, 32.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(Unit) { detectTapGestures { pos -> SongPreviews.setVolume(at(pos.x), false) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        SongPreviews.setVolume(at(change.position.x), false)
                    }
                }
                .semantics {
                    contentDescription = t("chat.audioPlayer.volume")
                    stateDescription = "${Math.round(shown * 100)}%"
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.bgActive, RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(shown).height(4.dp).background(fill ?: c.brand, RoundedCornerShape(2.dp)))
            Box(
                Modifier
                    .graphicsLayer { translationX = shown * (width - 18.dp.toPx()) }
                    .size(18.dp)
                    .dropShadow(CircleShape, androidx.compose.ui.graphics.shadow.Shadow(radius = 3.dp, color = c.shadeAt(0.7f), offset = androidx.compose.ui.unit.DpOffset(0.dp, 1.dp)))
                    .background(c.textStrong, CircleShape),
            )
        }
    }
}

/** The name on a gradient of a colour from the name: what shows where there is no picture. */
@Composable
private fun NameTile(name: String, small: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val tint = AvatarColor.of(name)
    Box(
        modifier.fillMaxSize().background(Brush.linearGradient(listOf(mixOklab(tint, Color.White, 0.22f), mixOklab(tint, Color.Black, 0.3f)), Offset.Zero, Offset(0f, Float.POSITIVE_INFINITY))),
        contentAlignment = if (small) Alignment.Center else Alignment.BottomStart,
    ) {
        Txt(
            if (small) AvatarColor.initials(name) else name,
            style = Nx.type.name.copy(fontSize = if (small) Nx.type.sp(size.value * 0.4f) else Nx.type.sp(11f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(if (small) size.value * 0.4f else 12.65f)),
            color = Color.White,
            modifier = if (small) Modifier else Modifier.padding(6.dp),
        )
    }
}

/** GameCover: 3:4 box art, or the name on its colour. */
@Composable
fun GameCover(g: Widgets.Game, css: Int, width: Dp?, shape: Shape = RoundedCornerShape(7.dp), named: Boolean = false) {
    val c = Nx.colors
    val density = LocalDensity.current.density
    val src = Widgets.gamePicture(g, css, density)
    var broken by remember(src) { mutableStateOf(false) }
    Box(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(3f / 4f)
            .boxShadow(shape, 6f, 14f, c.shadeAt(0.85f), spread = -8f)
            .ring(shape, c.textStrong.copy(alpha = 0.08f))
            .clip(shape)
            .background(c.bgActive)
            .then(if (named) Modifier.semantics { contentDescription = g.name } else Modifier),
    ) {
        if (src != null && !broken) AsyncImage(src, null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { broken = true })
        else NameTile(g.name, small = false, size = width ?: 80.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.12f), 0.28f to Color.Transparent)))
    }
}

/** MusicArt: square art (round for artists), or the name's initials or the name on its colour. */
@Composable
fun MusicArt(type: String, m: Widgets.Music, css: Int, size: Dp?) {
    val c = Nx.colors
    val density = LocalDensity.current.density
    val src = Widgets.musicPicture(type, m, css, density)
    var broken by remember(src) { mutableStateOf(false) }
    val shape = if (type == "artists") CircleShape else RoundedCornerShape(if (type == "albums") 3.dp else 6.dp)
    Box(
        (if (size != null) Modifier.size(size) else Modifier.fillMaxWidth().aspectRatio(1f))
            .boxShadow(shape, 6f, 14f, c.shadeAt(0.85f), spread = -8f)
            .ring(shape, c.textStrong.copy(alpha = 0.08f))
            .clip(shape)
            .background(c.bgActive),
    ) {
        if (src != null && !broken) AsyncImage(src, null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { broken = true })
        else NameTile(m.name, small = css < 64 || type == "artists", size = size ?: css.dp)
    }
}

@Composable
private fun Favorite(w: Widgets.GameWidget) {
    val c = Nx.colors
    val p = LocalProfile.current
    val g = w.games.firstOrNull() ?: return
    val density = LocalDensity.current.density
    val glow = Widgets.gamePicture(g, 40, density)
    Box(Modifier.fillMaxWidth().ring(Radii.Sm, c.borderSubtle).clip(Radii.Sm).background(p.chip.copy(alpha = p.chip.alpha * 0.7f)).topEdge(Radii.Sm, c.edgeLight)) {
        if (glow != null) AsyncImage(glow, null, Media.loader, Modifier.matchParentSize().graphicsLayer { alpha = if (c.isLight) 0.28f else 0.4f; scaleX = 1.8f; scaleY = 1.8f }.blur(32.dp), contentScale = ContentScale.Crop)
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GameCover(g, 76, 76.dp)
            Column(Modifier.weight(1f)) {
                Txt(t("widgets.type.favorite"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted, modifier = Modifier.padding(bottom = 2.dp))
                Txt(g.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(19f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(22.8f)), color = c.textStrong, maxLines = 2)
                w.note?.let { Txt(it, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.text, modifier = Modifier.padding(top = 5.dp)) }
            }
        }
    }
}

@Composable
private fun Playing(w: Widgets.GameWidget) {
    val c = Nx.colors
    val p = LocalProfile.current
    Column {
        Heading(t("widgets.type.playing"))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (g in w.games) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameCover(g, 36, 36.dp, RoundedCornerShape(5.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(g.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(19f)), color = c.textStrong, maxLines = 1)
                    if (g.tags.isNotEmpty()) FlowRow(Modifier.semantics { contentDescription = t("widgets.howTheyPlay") }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (tag in g.tags) Txt(
                            tag, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.textSubtle,
                            modifier = Modifier.height(20.dp).background(p.chip, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A grid of [count] columns (6 when there is room), each cell as wide as the grid allows. */
@Composable
private fun <T> Grid(items: List<T>, narrowCols: Int, wideCols: Int, rowGap: Dp, colGap: Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth >= 420.dp) wideCols else if (maxWidth <= 300.dp) 3 else narrowCols
        Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
            for (row in items.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(colGap)) {
                for (it in row) Box(Modifier.weight(1f)) { cell(it) }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MoreLink(all: Boolean, onShowAll: (() -> Unit)?, toggle: () -> Unit) {
    val c = Nx.colors
    Txt(
        if (onShowAll == null && all) t("widgets.showFewer") else t("widgets.showAll"),
        style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textBrand,
        modifier = Modifier.padding(top = 10.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { onShowAll?.invoke() ?: toggle() }.padding(vertical = 6.dp),
    )
}

@Composable
private fun Shelf(w: Widgets.GameWidget, onShowAll: (() -> Unit)?) {
    val c = Nx.colors
    var all by rememberSaveable(w.id) { mutableStateOf(false) }
    val most = if (onShowAll != null) 6 else 12
    val shown = if (all) w.games else w.games.take(most)
    Column {
        Heading(Widgets.title(w.type), w.games.size, t("widgets.gameCount", "count" to w.games.size))
        Grid(shown, 4, 6, 10.dp, 8.dp) { g ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GameCover(g, 80, null, named = true)
                Txt(g.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(14f)), color = c.textSubtle, maxLines = 2)
            }
        }
        if (w.games.size > most) MoreLink(all, onShowAll) { all = !all }
    }
}

@Composable
private fun Artists(w: Widgets.MusicWidget, onShowAll: (() -> Unit)?) {
    val c = Nx.colors
    var all by rememberSaveable(w.id) { mutableStateOf(false) }
    val most = if (onShowAll != null) 6 else 12
    val shown = if (all) w.music else w.music.take(most)
    Column {
        Heading(Widgets.title(w.type), w.music.size, Widgets.musicCount(w.type, w.music.size))
        Grid(shown, 4, 6, 14.dp, 10.dp) { m ->
            val link = Widgets.musicLink("artists", m)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (link != null) Modifier.pressable(remember { MutableInteractionSource() }) { openLink(link) }.semantics { contentDescription = "${m.name}, ${t("widgets.music.onDeezer")}" } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MusicArt("artists", m, 88, null)
                Txt(m.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(15f)), color = c.textStrong, maxLines = 2, align = TextAlign.Center)
            }
        }
        if (w.music.size > most) MoreLink(all, onShowAll) { all = !all }
    }
}

@Composable
private fun Albums(w: Widgets.MusicWidget, onShowAll: (() -> Unit)?) {
    val c = Nx.colors
    var all by rememberSaveable(w.id) { mutableStateOf(false) }
    val most = if (onShowAll != null) 5 else 12
    val shown = if (all) w.music else w.music.take(most)
    Column {
        Heading(Widgets.title(w.type), w.music.size, Widgets.musicCount(w.type, w.music.size))
        Grid(shown, 4, 5, 14.dp, 10.dp) { m ->
            val link = Widgets.musicLink("albums", m)
            Column(Modifier.fillMaxWidth().then(if (link != null) Modifier.pressable(remember { MutableInteractionSource() }) { openLink(link) }.semantics { contentDescription = "${m.name}, ${t("widgets.music.onDeezer")}" } else Modifier)) {
                // the sleeve, with its record peeking out at the end side
                Box(Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val art = maxWidth / 1.13f
                        Record(Modifier.align(Alignment.CenterEnd).size(art * 0.9f))
                        Box(Modifier.width(art)) { MusicArt("albums", m, 88, art) }
                    }
                }
                Txt(m.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(16f)), color = c.textStrong, maxLines = 1)
                m.by?.let { Txt(it, style = Nx.type.hint.copy(lineHeight = Nx.type.sp(15f)), color = c.textMuted, maxLines = 1) }
            }
        }
        if (w.music.size > most) MoreLink(all, onShowAll) { all = !all }
    }
}

/** The vinyl behind an album sleeve: grooves, a label, a sheen. */
@Composable
private fun Record(modifier: Modifier) {
    Canvas(modifier.ring(CircleShape, Color.Black.copy(alpha = 0.5f)).ring(CircleShape, Color.White.copy(alpha = 0.16f), 1.dp)) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(0f to Color(0xFF2B2830), 0.15f to Color(0xFF2B2830), 0.155f to Color(0xFF19171D), 0.64f to Color(0xFF19171D), 0.72f to Color(0xFF111014), 1f to Color(0xFF111014), radius = r))
        var g = r * 0.2f
        while (g < r * 0.96f) {
            drawCircle(Color.White.copy(alpha = 0.06f), radius = g, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.6f))
            g += 2.4f * density
        }
        drawArc(Color.White.copy(alpha = 0.18f), -38f, 52f, true)
        drawArc(Color.White.copy(alpha = 0.09f), 168f, 52f, true)
    }
}

@Composable
private fun Songs(w: Widgets.MusicWidget, onShowAll: (() -> Unit)?) {
    var all by rememberSaveable(w.id) { mutableStateOf(false) }
    val owner = remember { "songs-" + System.nanoTime() }
    DisposableEffect(owner) { onDispose { SongPreviews.stop(owner) } }
    LaunchedEffect(SongPreviews.playingId) { if (SongPreviews.playingId != null) SongPreviews.track() }
    val cap = 5
    val shown = if (all) w.music else w.music.take(cap)
    Column {
        Heading(Widgets.title(w.type), w.music.size, Widgets.musicCount(w.type, w.music.size), extra = if (w.music.any { it.id != null }) ({ SongVolume() }) else null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEachIndexed { i, m -> SongRow(m, i, owner) }
        }
        if (w.music.size > cap) MoreLink(all, onShowAll) { all = !all }
    }
}

@Composable
private fun SongRow(m: Widgets.Music, index: Int, owner: String) {
    val c = Nx.colors
    val p = LocalProfile.current
    val on = m.id != null && SongPreviews.playingId == m.id
    val state = if (on) SongPreviews.state else null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val link = Widgets.musicLink("songs", m)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (pressed) p.chip else Color.Transparent)
                    .then(
                        if (m.id != null) Modifier.pressable(interaction) { SongPreviews.toggle(m, owner) }
                            .semantics { contentDescription = t(if (on && state != "paused") "widgets.music.pause" else "widgets.music.play", "name" to m.name) }
                        else Modifier,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.widthIn(min = 18.dp).height(18.dp), contentAlignment = Alignment.CenterEnd) {
                    when {
                        state == "loading" -> Spinner(color = c.textBrand, size = 15.dp)
                        state == "playing" -> Equalizer()
                        state == "paused" -> Icon("Play", c.textBrand, size = 14.dp, stroke = 2.6f)
                        else -> Txt((index + 1).toString(), style = Nx.type.label.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650), fontFeatureSettings = "tnum"), color = c.textMuted)
                    }
                }
                MusicArt("songs", m, 40, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Txt(m.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(18f)), color = if (on) c.textBrand else c.textStrong, maxLines = 1)
                    m.by?.let { Txt(it, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), lineHeight = Nx.type.sp(16f)), color = c.textMuted, maxLines = 1) }
                }
                m.duration?.let { Txt(Widgets.songLength(it), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontFeatureSettings = "tnum"), color = c.textMuted) }
            }
            if (on) {
                val progress by animateFloatAsState(SongPreviews.progress, tween(250), label = "played")
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.textBrand.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.textBrand))
                }
            }
        }
        if (link != null) Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).pressable(remember { MutableInteractionSource() }) { openLink(link) }
                .semantics { contentDescription = t("widgets.music.openNamed", "name" to m.name) },
            contentAlignment = Alignment.Center,
        ) { Icon("ArrowUpRight", c.textMuted, size = 16.dp) }
    }
}

/** Three bars that move while a preview plays (still, at two heights, with reduced motion). */
@Composable
internal fun Equalizer(color: Color = Nx.colors.textBrand) {
    val reduced = Nx.motion.reduced
    val transition = rememberInfiniteTransition(label = "eq")
    Row(Modifier.width(13.dp).height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for ((i, ms) in listOf(820, 640, 940).withIndex()) {
            val h by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar")
            val shown = if (reduced) listOf(0.55f, 1f, 0.75f)[i] else h
            Box(Modifier.width(3.dp).fillMaxHeight(shown).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun CardWidget(w: Widgets.CardWidget) {
    val c = Nx.colors
    val p = LocalProfile.current
    val density = LocalDensity.current.density
    Column(Modifier.fillMaxWidth().ring(Radii.Sm, c.borderSubtle).clip(Radii.Sm).background(p.chip.copy(alpha = p.chip.alpha * 0.7f)).topEdge(Radii.Sm, c.edgeLight)) {
        if (w.image != null && w.shape != "square") AsyncImage(Media.widgetUrl(w.image, 520, density), null, Media.loader, Modifier.fillMaxWidth().aspectRatio(12f / 5f).background(c.bgActive), contentScale = ContentScale.Crop)
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (w.image != null && w.shape == "square") AsyncImage(Media.widgetUrl(w.image, 56, density), null, Media.loader, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(c.bgActive), contentScale = ContentScale.Crop)
                Txt(w.title, style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(21.25f)), color = c.textStrong, maxLines = 2)
            }
            w.text?.let { Txt(it, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = c.text, modifier = Modifier.padding(top = 6.dp)) }
            if (w.stats.isNotEmpty()) BoxWithConstraints(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                val cols = maxOf(1, minOf(w.stats.size, ((maxWidth + 6.dp) / (110.dp + 6.dp)).toInt()))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in w.stats.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (st in row) Column(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(p.chip).topEdge(RoundedCornerShape(9.dp), c.edgeLight).padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 8.dp)) {
                            Txt(st.label, style = Nx.type.hint.copy(fontWeight = FontWeight(600), lineHeight = Nx.type.sp(15f)), color = c.textMuted, maxLines = 2)
                            Txt(st.value, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(19f)), color = c.textStrong, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                        }
                        repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            w.link?.let { url ->
                val host = Widgets.linkHost(url)
                val showHost = w.linkLabel != null && !w.linkLabel.lowercase().contains(host.lowercase())
                Row(
                    Modifier.padding(top = 12.dp).height(40.dp).clip(RoundedCornerShape(999.dp)).background(p.chip).topEdge(RoundedCornerShape(999.dp), c.edgeLight)
                        .pressable(remember { MutableInteractionSource() }) { openLink(url) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon("ExternalLink", c.textStrong, size = 14.dp)
                    Txt(w.linkLabel ?: host, style = Nx.type.button.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (showHost) Txt(host, style = Nx.type.button.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** WidgetPeek: the first widget in one line, its covers fanned out, and how many more there are. */
@Composable
fun WidgetPeek(widgets: List<Widgets.Widget>, onOpen: (() -> Unit)?) {
    val c = Nx.colors
    val p = LocalProfile.current
    val w = widgets.firstOrNull() ?: return
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sub = when (w) {
        is Widgets.GameWidget -> if (w.games.size == 1) w.games[0].name else t("widgets.gameCount", "count" to w.games.size)
        is Widgets.MusicWidget -> if (w.music.size == 1) w.music[0].name else Widgets.musicCount(w.type, w.music.size)
        is Widgets.CardWidget -> w.text ?: ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .ring(Radii.Sm, c.borderSubtle)
            .clip(Radii.Sm)
            .background(if (pressed) p.chipHover else p.chip.copy(alpha = p.chip.alpha * 0.7f))
            .topEdge(Radii.Sm, c.edgeLight)
            .then(if (onOpen != null) Modifier.pressable(interaction, onClick = onOpen).semantics { contentDescription = t("profile.tabs.viewAllWidgets", "count" to widgets.size) } else Modifier)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Txt(Widgets.name(w), style = Nx.type.hint.copy(fontWeight = FontWeight(700), lineHeight = Nx.type.sp(15f)), color = c.textMuted, maxLines = 1)
            if (sub.isNotEmpty()) Txt(sub, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(18f)), color = c.textStrong, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (w) {
                is Widgets.GameWidget -> w.games.take(3).forEachIndexed { i, g ->
                    Box(Modifier.offset(x = (-10 * i).dp).zIndex((3 - i).toFloat())) { GameCover(g, 30, 26.dp, RoundedCornerShape(4.dp)) }
                }
                is Widgets.MusicWidget -> w.music.take(3).forEachIndexed { i, m ->
                    Box(Modifier.offset(x = (-10 * i).dp).zIndex((3 - i).toFloat())) { MusicArt(w.type, m, 30, 26.dp) }
                }
                is Widgets.CardWidget -> w.image?.let { AsyncImage(Media.widgetUrl(it, 40, density), null, Media.loader, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
            }
        }
        if (widgets.size > 1) Txt("+${widgets.size - 1}", style = Nx.type.hint.copy(fontWeight = FontWeight(700), fontFeatureSettings = "tnum"), color = c.textMuted)
    }
}
