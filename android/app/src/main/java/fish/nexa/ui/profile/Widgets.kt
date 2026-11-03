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
import androidx.compose.ui.graphics.compositeOver
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
    const val MAX = 12

    /** The most links a card holds (lib/widgets.ts MAX_CARD_LINKS). */
    const val MAX_LINKS = 3
    data class Game(val id: String?, val name: String, val cover: String?, val image: String?, val tags: List<String>)
    data class Music(val id: String?, val name: String, val by: String?, val art: String?, val image: String?, val duration: Int?)
    /** [bar], from 0 to 100, draws the stat as a progress bar; [value] is then its percentage in words. */
    data class Stat(val label: String, val value: String, val bar: Int? = null)
    data class Link(val url: String, val label: String?)

    sealed interface Widget {
        val id: String
        val type: String
    }

    data class GameWidget(override val id: String, override val type: String, val games: List<Game>, val note: String?) : Widget
    data class MusicWidget(override val id: String, override val type: String, val music: List<Music>) : Widget
    /**
     * A card of the person's own. [links] is always sent, even empty: the server
     * takes a card without it for one from an app before cards had several
     * links, colours and bars, and keeps what it had. [color] and [color2]
     * (0xRRGGBB) tint it, the second making a gradient.
     */
    data class CardWidget(override val id: String, val title: String, val image: String?, val shape: String?, val text: String?, val stats: List<Stat>, val links: List<Link>, val color: Int? = null, val color2: Int? = null) : Widget {
        override val type get() = "custom"
    }

    /** A countdown to a whole day ([date], YYYY-MM-DD, in each viewer's calendar) or a moment ([at], Unix ms). */
    data class CountdownWidget(override val id: String, val title: String, val emoji: String?, val date: String?, val at: Long?) : Widget {
        override val type get() = "countdown"
    }

    /** A book: found on Open Library ([id], [cover]), or added by name with a picture of the person's own. */
    data class Book(val id: String?, val title: String, val author: String?, val cover: String?, val image: String?, val progress: Progress? = null)
    /** How far into a book: [kind] "page" or "percent". */
    data class Progress(val kind: String, val value: Int)

    data class BookWidget(override val id: String, override val type: String, val books: List<Book>) : Widget

    val MOST_BOOKS = mapOf("reading" to 3, "books" to 12)

    fun bookPicture(b: Book, css: Int, density: Float): String? = when {
        b.cover != null -> "${base()}/media/book/${b.cover}?size=${if (css > 60) "l" else "s"}"
        b.image != null -> Media.widgetUrl(b.image, css, density)
        else -> null
    }

    /** A showcase of one or two of the person's own public sticker or emoji packs. */
    data class StickersWidget(override val id: String, val packs: List<String>) : Widget {
        override val type get() = "stickers"
    }

    const val MAX_SHOWCASE_PACKS = 2

    /** The time where someone is: an IANA time zone, shown only because they added it. */
    data class LocalTimeWidget(override val id: String, val zone: String) : Widget {
        override val type get() = "localtime"
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
                    val stats = (w["stats"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map {
                        val bar = (it["bar"] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull?.takeIf { b -> b in 0.0..100.0 }?.let { b -> Math.round(b).toInt() }
                        Stat(it.s("label", 24), it.s("value", 40), bar)
                    }?.filter { it.label.isNotEmpty() && it.value.isNotEmpty() }?.take(6) ?: emptyList()
                    val rawLinks = (w["links"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOfNotNull(w["link"] as? JsonObject)
                    val links = rawLinks.mapNotNull { l ->
                        val url = (l["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return@mapNotNull null
                        Link(url, l.s("label", 32).ifEmpty { null })
                    }.take(MAX_LINKS)
                    fun colour(k: String) = (w[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it >= 0 && it <= 0xFFFFFF && it % 1.0 == 0.0 }?.toInt()
                    val color = colour("color")
                    out += CardWidget(id, title, image, shape, w.s("text", 1000).ifEmpty { null }, stats, links, color, if (color != null) colour("color2") else null)
                }
                "countdown" -> {
                    val title = w.s("title", 40).trim()
                    val date = w.match("date", Regex("^\\d{4}-\\d{2}-\\d{2}$"))
                    val at = (w["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0 && it.isFinite() }?.toLong()
                    if (title.isEmpty() || (date == null && at == null)) continue
                    out += CountdownWidget(id, title, w.s("emoji", 32).trim().ifEmpty { null }, date, if (date == null) at else null)
                }
                "reading", "books" -> {
                    val books = (w["books"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { b ->
                        val title = b.s("title", 200).trim()
                        if (title.isEmpty()) return@mapNotNull null
                        val pr = b["progress"] as? JsonObject
                        val kind = (pr?.get("kind") as? JsonPrimitive)?.contentOrNull
                        val value = (pr?.get("value") as? JsonPrimitive)?.doubleOrNull?.toInt()
                        Book(
                            b.match("id", Regex("^OL\\d{1,12}W$")), title, b.s("author", 100).trim().ifEmpty { null },
                            b.match("cover", Regex("^\\d{1,12}$")), b.match("image", Regex("^\\d{1,20}$")),
                            if (type == "reading" && (kind == "page" || kind == "percent") && value != null) Progress(kind, value) else null,
                        )
                    }?.take(MOST_BOOKS[type]!!) ?: emptyList()
                    if (books.isNotEmpty()) out += BookWidget(id, type, books)
                }
                "stickers" -> {
                    val packs = (w["packs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> Regex("^\\d{1,20}$").matches(s) } }?.take(MAX_SHOWCASE_PACKS) ?: emptyList()
                    if (packs.isNotEmpty()) out += StickersWidget(id, packs)
                }
                "localtime" -> {
                    val zone = w.s("zone", 64).trim()
                    if (zone.isEmpty() || !fish.nexa.data.TimeWidgets.valid(zone)) continue
                    out += LocalTimeWidget(id, zone)
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
            is CountdownWidget -> {
                put("title", JsonPrimitive(w.title))
                w.emoji?.let { put("emoji", JsonPrimitive(it)) }
                if (w.date != null) put("date", JsonPrimitive(w.date)) else w.at?.let { put("at", JsonPrimitive(it)) }
            }
            is LocalTimeWidget -> put("zone", JsonPrimitive(w.zone))
            is StickersWidget -> put("packs", JsonArray(w.packs.map { JsonPrimitive(it) }))
            is BookWidget -> put("books", JsonArray(w.books.map { b ->
                buildJsonObject {
                    b.id?.let { put("id", JsonPrimitive(it)) }
                    put("title", JsonPrimitive(b.title))
                    b.author?.let { put("author", JsonPrimitive(it)) }
                    b.cover?.let { put("cover", JsonPrimitive(it)) }
                    b.image?.let { put("image", JsonPrimitive(it)) }
                    b.progress?.let { p -> put("progress", buildJsonObject { put("kind", JsonPrimitive(p.kind)); put("value", JsonPrimitive(p.value)) }) }
                }
            }))
            is CardWidget -> {
                put("title", JsonPrimitive(w.title))
                w.image?.let { put("image", JsonPrimitive(it)); put("shape", JsonPrimitive(w.shape ?: "wide")) }
                w.text?.takeIf { it.isNotBlank() }?.let { put("text", JsonPrimitive(it)) }
                if (w.stats.isNotEmpty()) put("stats", JsonArray(w.stats.map { st ->
                    buildJsonObject {
                        put("label", JsonPrimitive(st.label)); put("value", JsonPrimitive(st.value))
                        st.bar?.let { put("bar", JsonPrimitive(it)) }
                    }
                }))
                put("links", JsonArray(w.links.map { l ->
                    buildJsonObject {
                        put("url", JsonPrimitive(l.url))
                        l.label?.let { put("label", JsonPrimitive(it)) }
                    }
                }))
                w.color?.let { c -> put("color", JsonPrimitive(c)); w.color2?.let { put("color2", JsonPrimitive(it)) } }
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
        "countdown" -> t("widgets.type.countdown")
        "localtime" -> t("widgets.type.localtime")
        "stickers" -> t("widgets.type.stickers")
        "reading" -> t("widgets.type.reading")
        "books" -> t("widgets.type.books")
        else -> t("widgets.type.custom")
    }

    fun name(w: Widget) = when (w) {
        is CardWidget -> w.title
        is CountdownWidget -> w.title
        else -> title(w.type)
    }

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
        is Widgets.CountdownWidget -> Countdown(w, designing = design != null)
        is Widgets.LocalTimeWidget -> LocalTime(w)
        is Widgets.StickersWidget -> Showcase(w)
        is Widgets.BookWidget -> if (w.type == "reading") Reading(w) else BookShelf(w, onShowAll)
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

/** A game found on IGDB opens its page when pressed (web GameOpen); one added by name stays only a picture. */
@Composable
private fun Modifier.opensGame(g: Widgets.Game, shape: Shape = RoundedCornerShape(7.dp)): Modifier {
    val id = g.id ?: return this
    return this.clip(shape).pressable(remember { MutableInteractionSource() }) { openGamePage(id) }
        .semantics { contentDescription = t("games.page.openNamed", "name" to g.name) }
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
            Box(Modifier.opensGame(g)) { GameCover(g, 76, 76.dp) }
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
                Box(Modifier.opensGame(g, RoundedCornerShape(5.dp))) { GameCover(g, 36, 36.dp, RoundedCornerShape(5.dp)) }
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
                Box(Modifier.opensGame(g)) { GameCover(g, 80, null, named = g.id == null) }
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

/** The current time on the server's clock, ticking once a minute (on the minute) while shown. */
@Composable
private fun minuteClock(): Long {
    var now by remember { mutableStateOf(fish.nexa.data.ServerClock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000 + 50)
            now = fish.nexa.data.ServerClock.now()
        }
    }
    return now
}

/** A tile like the favourite's: a mark (an emoji or an icon) beside a heading, a large figure and a line under it. */
@Composable
private fun MomentTile(heading: String, big: String, sub: String, today: Boolean, mark: @Composable () -> Unit, testTag: String) {
    val c = Nx.colors
    val p = LocalProfile.current
    Row(
        Modifier.fillMaxWidth().ring(Radii.Sm, c.borderSubtle).clip(Radii.Sm).background(p.chip.copy(alpha = p.chip.alpha * 0.7f)).topEdge(Radii.Sm, c.edgeLight).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "$heading, $big, $sub" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(p.chip).topEdge(RoundedCornerShape(14.dp), c.edgeLight), contentAlignment = Alignment.Center) { mark() }
        Column(Modifier.weight(1f)) {
            Txt(heading, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted, modifier = Modifier.padding(bottom = 2.dp))
            Txt(big, style = Nx.type.name.copy(fontSize = Nx.type.sp(21f), fontWeight = FontWeight(750), lineHeight = Nx.type.sp(25f), fontFeatureSettings = "tnum"), color = if (today) c.textBrand else c.textStrong)
            Txt(sub, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)), color = c.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A countdown: gone from the profile a day after; the owner still sees it in the designer, marked as over. */
@Composable
private fun Countdown(w: Widgets.CountdownWidget, designing: Boolean) {
    val now = minuteClock()
    val state = fish.nexa.data.TimeWidgets.state(w.date, w.at, now)
    if (state is fish.nexa.data.TimeWidgets.State.Gone && !designing) return
    val big = when (state) {
        is fish.nexa.data.TimeWidgets.State.Coming -> state.left
        fish.nexa.data.TimeWidgets.State.Today -> t("widgets.countdown.today")
        else -> t("widgets.countdown.over")
    }
    MomentTile(w.title, big, fish.nexa.data.TimeWidgets.whenText(w.date, w.at), state == fish.nexa.data.TimeWidgets.State.Today, {
        if (w.emoji != null) StatusEmojiImage(w.emoji, 30.dp) else Icon("CalendarClock", Nx.colors.textSubtle, size = 26.dp)
    }, "widget-countdown")
}

/** BookCover: a book's cover in a book's shape, or its title on a colour of its own (web BookCover). */
@Composable
fun BookCover(b: Widgets.Book, css: Int, width: Dp?, named: Boolean = false) {
    val c = Nx.colors
    val density = LocalDensity.current.density
    val src = Widgets.bookPicture(b, css, density)
    var broken by remember(src) { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp)
    Box(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(2f / 3f)
            .boxShadow(shape, 6f, 14f, c.shadeAt(0.85f), spread = -8f)
            .ring(shape, c.textStrong.copy(alpha = 0.08f))
            .clip(shape)
            .background(c.bgActive)
            .then(if (named) Modifier.semantics { contentDescription = if (b.author != null) t("widgets.books.byAuthor", "title" to b.title, "author" to b.author) else b.title } else Modifier),
    ) {
        if (src != null && !broken) AsyncImage(src, null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { broken = true })
        else NameTile(b.title, small = false, size = width ?: 80.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.12f), 0.28f to Color.Transparent)))
    }
}

private fun progressWords(p: Widgets.Progress) =
    if (p.kind == "page") t("widgets.books.page", "page" to java.text.NumberFormat.getIntegerInstance(fish.nexa.ui.I18n.locale).format(p.value)) else t("widgets.books.percent", "percent" to p.value)

/** Reading Now: a row a book, its author, and how far in they are (a bar for a percentage). */
@Composable
private fun Reading(w: Widgets.BookWidget) {
    val c = Nx.colors
    Column {
        Heading(t("widgets.type.reading"))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (b in w.books) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(b, 40, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(b.title, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(19f)), color = c.textStrong, maxLines = 1)
                    b.author?.let { Txt(it, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(16f)), color = c.textMuted, maxLines = 1) }
                    b.progress?.let { p ->
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.kind == "percent") Box(
                                Modifier.weight(1f, fill = false).widthIn(max = 160.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.textStrong.copy(alpha = 0.12f))
                                    .semantics { contentDescription = b.title; stateDescription = "${p.value}%" },
                            ) { Box(Modifier.fillMaxWidth(p.value.coerceIn(0, 100) / 100f).fillMaxHeight().background(c.brand, RoundedCornerShape(3.dp))) }
                            Txt(progressWords(p), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600), fontFeatureSettings = "tnum"), color = c.textSubtle)
                        }
                    }
                }
            }
        }
    }
}

/** Favourite Books: a shelf of covers, as the games' shelves are. */
@Composable
private fun BookShelf(w: Widgets.BookWidget, onShowAll: (() -> Unit)?) {
    val c = Nx.colors
    var all by rememberSaveable(w.id) { mutableStateOf(false) }
    val most = if (onShowAll != null) 6 else 12
    val shown = if (all) w.books else w.books.take(most)
    Column {
        Heading(Widgets.title(w.type), w.books.size, t("widgets.books.count", "count" to w.books.size))
        Grid(shown, 4, 6, 10.dp, 8.dp) { b ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BookCover(b, 80, null, named = true)
                Txt(b.title, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(14f)), color = c.textSubtle, maxLines = 2)
            }
        }
        if (w.books.size > most) MoreLink(all, onShowAll) { all = !all }
    }
}

/**
 * A showcase of someone's own packs, each as the card a pack link makes in a
 * chat (its stickers and Install Pack). A pack that has gone or turned private
 * drops out; with none left the widget is not shown.
 */
@Composable
private fun Showcase(w: Widgets.StickersWidget) {
    val entries = w.packs.map { fish.nexa.ui.chat.SharedPacks.byId[it] }
    if (entries.all { it is fish.nexa.ui.chat.SharedPacks.Entry.Missing || it is fish.nexa.ui.chat.SharedPacks.Entry.Failed }) {
        // still asked for, so a pack that comes back shows again
        for (id in w.packs) LaunchedEffect(id) { fish.nexa.ui.chat.SharedPacks.load(id) }
        return
    }
    Column {
        Heading(t("widgets.type.stickers"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (id in w.packs) fish.nexa.ui.chat.PackLinkCard(id, quiet = true) }
    }
}

/** Someone's local time, ticking, with where it is and how far it is from yours. */
@Composable
private fun LocalTime(w: Widgets.LocalTimeWidget) {
    val now = minuteClock()
    MomentTile(
        t("widgets.type.localtime"),
        fish.nexa.data.TimeWidgets.time(w.zone, now),
        t("widgets.localtime.where", "place" to fish.nexa.data.TimeWidgets.place(w.zone), "gap" to fish.nexa.data.TimeWidgets.gap(w.zone, now)),
        false, { Icon("Clock", Nx.colors.textSubtle, size = 26.dp) }, "widget-localtime",
    )
}

/** Opens a card's link once its full address has been seen, as activity links do. */
private fun openCardLink(url: String) {
    fish.nexa.ui.shell.confirm(t("textMenu.openLink"), url, t("textMenu.openLink"), danger = false) { openLink(url) }
}

@Composable
private fun CardWidget(w: Widgets.CardWidget) {
    val c = Nx.colors
    val p = LocalProfile.current
    val density = LocalDensity.current.density
    val base = p.chip.copy(alpha = p.chip.alpha * 0.7f).compositeOver(c.bgFloating)
    // a card in its own colours: the tile's own fill, washed with them from one corner to the other
    val tintA = w.color?.let { Color(0xFF000000L or it.toLong()) }
    val tintB = w.color2?.let { Color(0xFF000000L or it.toLong()) } ?: tintA
    val mix = if (c.isLight) 0.18f else 0.30f
    val fill = if (tintA != null && tintB != null) Modifier.background(Brush.linearGradient(listOf(mixOklab(base, tintA, mix), mixOklab(base, tintB, mix))))
    else Modifier.background(p.chip.copy(alpha = p.chip.alpha * 0.7f))
    val edge = if (tintA != null) mixOklab(c.borderSubtle, tintA, 0.35f) else c.borderSubtle
    Column(Modifier.fillMaxWidth().ring(Radii.Sm, edge).clip(Radii.Sm).then(fill).topEdge(Radii.Sm, c.edgeLight)) {
        if (w.image != null && w.shape != "square") AsyncImage(Media.widgetUrl(w.image, 520, density), null, Media.loader, Modifier.fillMaxWidth().aspectRatio(12f / 5f).background(c.bgActive), contentScale = ContentScale.Crop)
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (w.image != null && w.shape == "square") AsyncImage(Media.widgetUrl(w.image, 56, density), null, Media.loader, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(c.bgActive), contentScale = ContentScale.Crop)
                Txt(w.title, style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(21.25f)), color = c.textStrong, maxLines = 2)
            }
            w.text?.let {
                Box(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                    fish.nexa.ui.markdown.MarkdownView(it, fish.nexa.ui.markdown.RenderCtx(color = c.text, bodyPx = 14f, bodyLine = 20.3f), Modifier.fillMaxWidth())
                }
            }
            if (w.stats.isNotEmpty()) BoxWithConstraints(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                val cols = maxOf(1, minOf(w.stats.size, ((maxWidth + 6.dp) / (110.dp + 6.dp)).toInt()))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in w.stats.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (st in row) Column(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(p.chip).topEdge(RoundedCornerShape(9.dp), c.edgeLight).padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 8.dp)) {
                            Txt(st.label, style = Nx.type.hint.copy(fontWeight = FontWeight(600), lineHeight = Nx.type.sp(15f)), color = c.textMuted, maxLines = 2)
                            Txt(st.value, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(19f)), color = c.textStrong, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                            st.bar?.let { bar ->
                                // a stat drawn as a bar: a thin bar filled in the card's colour, or the accent
                                Box(
                                    Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.textStrong.copy(alpha = 0.12f))
                                        .semantics { contentDescription = st.label; stateDescription = "$bar%" },
                                ) { Box(Modifier.fillMaxWidth(bar / 100f).fillMaxHeight().background(tintA ?: c.brand, RoundedCornerShape(3.dp))) }
                            }
                        }
                        repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            if (w.links.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in w.links) {
                    val host = Widgets.linkHost(l.url)
                    // a link with words of its own also says where it goes, as a masked link in a message does
                    val showHost = l.label != null && !l.label.lowercase().contains(host.lowercase())
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(999.dp)).background(p.chip).topEdge(RoundedCornerShape(999.dp), c.edgeLight)
                            .pressable(remember { MutableInteractionSource() }) { openCardLink(l.url) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon("ExternalLink", c.textStrong, size = 14.dp)
                        Txt(l.label ?: host, style = Nx.type.button.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (showHost) Txt(host, style = Nx.type.button.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1)
                    }
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
        is Widgets.CountdownWidget -> fish.nexa.data.TimeWidgets.whenText(w.date, w.at)
        is Widgets.LocalTimeWidget -> fish.nexa.data.TimeWidgets.place(w.zone)
        is Widgets.StickersWidget -> t("widgets.stickers.packCount", "count" to w.packs.size)
        is Widgets.BookWidget -> if (w.books.size == 1) w.books[0].title else t("widgets.books.count", "count" to w.books.size)
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
                is Widgets.CountdownWidget -> w.emoji?.let { StatusEmojiImage(it, 22.dp) }
                is Widgets.LocalTimeWidget, is Widgets.StickersWidget -> Unit
                is Widgets.BookWidget -> w.books.take(3).forEachIndexed { i, b ->
                    Box(Modifier.offset(x = (-10 * i).dp).zIndex((3 - i).toFloat())) { BookCover(b, 30, 24.dp) }
                }
            }
        }
        if (widgets.size > 1) Txt("+${widgets.size - 1}", style = Nx.type.hint.copy(fontWeight = FontWeight(700), fontFeatureSettings = "tnum"), color = c.textMuted)
    }
}
