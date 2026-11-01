package fish.nexa.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ChatMessage
import fish.nexa.media.Fit
import fish.nexa.media.Media
import fish.nexa.media.Unfurl
import fish.nexa.media.mediaSrc
import fish.nexa.media.openExternal
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.chat.ListCtx
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.parseHex
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLDecoder

/**
 * A card's bar of colour down its start edge (an embed's or a box's accent),
 * drawn rather than laid out so the card is exactly as tall as what it holds.
 */
fun Modifier.startBar(color: Color, width: Dp = 4.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
}.padding(start = width)

/** The previews of a message's links, as the row calls for them. */
@Composable
fun Embeds(embeds: List<JsonObject>, cm: ChatMessage, ctx: ListCtx) {
    EmbedList(remember(embeds) { embeds.mapNotNull { Unfurl.of(it) } }, senderName(cm, ctx))
}

/**
 * Embeds.tsx: at most five previews under a message, in a column: a social
 * post as a post, pictures and GIFs framed like uploads, videos in the player,
 * and a lit card for anything else with a title, words or a picture. With
 * "show embedded images" off, pictures and videos from links are not drawn.
 */
@Composable
fun EmbedList(list: List<Unfurl>, from: String) {
    val doc by Nexa.settings.doc.collectAsState()
    val imagePreviews = remember(doc) { Nexa.settings.imagePreviews }
    if (list.all { it.type == "invite" || it.type == "profile" }) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
        for (e in list.take(5)) {
            when {
                e.post != null -> PostEmbed(e)
                e.type == "image" || e.type == "gif" -> if (imagePreviews) ImageEmbed(e, from)
                e.type == "video" -> if (imagePreviews) VideoEmbed(e)
                e.type == "audio" -> AudioEmbed(e)
                e.type == "invite" || e.type == "profile" -> Unit
                e.type == "youtube" || e.title != null || e.description != null || e.player != null || e.media != null -> LinkCard(e, from)
            }
        }
    }
}

private fun fileNameOf(url: String, fallback: String): String = runCatching {
    URI(url).path.split('/').lastOrNull { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
}.getOrNull() ?: fallback

/** A GIF or picture link shown like an upload: its frame, the GIF badge, the star, and the viewer on a tap. */
@Composable
private fun ImageEmbed(e: Unfurl, from: String) {
    val c = Nx.colors
    val src = mediaSrc(e.image ?: e.url) ?: return
    val fit = Fit.media(e.imageW, e.imageH)
    val name = if (e.type == "gif") t("chat.embeds.gif") else fileNameOf(e.url, t("server.emoji.image"))
    val fileName = if (e.type == "gif") "animation.gif" else fileNameOf(e.url, "image")
    Box(
        Modifier
            .width(fit.width.dp)
            .aspectRatio(fit.width.toFloat() / fit.height)
            .ring(Radii.Md, c.borderSubtle)
            .clip(Radii.Md)
            .background(c.bgInput)
            .then(if (src.isNotEmpty()) Modifier.pressable(remember { MutableInteractionSource() }) {
                openLightbox(listOf(LightboxItem(fileName, if (e.type == "gif") "image/gif" else null, src = src, from = from)), 0)
            } else Modifier),
    ) {
        if (src.isNotEmpty()) AsyncImage(src, name, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (e.type == "gif") Box(
            Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        ) { Txt(t("chat.embeds.gif"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = Color.White) }
        if (src.isNotEmpty()) GifStar(src, e.imageW, e.imageH, null, Modifier.align(Alignment.TopEnd))
    }
}

/** A link to a video file: the player on the file, through the proxy. */
@Composable
private fun VideoEmbed(e: Unfurl) {
    val src = mediaSrc(e.image ?: e.url)
    if (src.isNullOrEmpty()) return
    VideoPlayer(VideoSource.Url(src), fileNameOf(e.url, t("perms.video")), e.imageW, e.imageH, eager = false)
}

/** A linked audio file uses the upload player, only with a safe proxy address. */
@Composable
private fun AudioEmbed(e: Unfurl) {
    val src = mediaSrc(e.media)
    if (src.isNullOrEmpty()) return
    Box(Modifier.widthIn(max = 400.dp)) {
        AudioPlayer(android.net.Uri.parse(src), fileNameOf(e.url, t("chat.embeds.audio")), null, null, null, null, null)
    }
}

private val PLAYER_HOSTS = setOf("player.vimeo.com", "player.twitch.tv", "clips.twitch.tv", "www.dailymotion.com", "streamable.com", "www.loom.com")

fun isYouTubeId(id: String?): Boolean = id != null && Regex("^[A-Za-z0-9_-]{11}$").matches(id)

private fun youtubeId(e: Unfurl): String? {
    if (isYouTubeId(e.videoId)) return e.videoId
    return runCatching {
        val u = URI(e.player ?: return null)
        val m = Regex("^/embed/([A-Za-z0-9_-]{11})$").find(u.path ?: "")
        if (u.host == "www.youtube-nocookie.com" && m != null) m.groupValues[1] else null
    }.getOrNull()
}

private fun playerHost(e: Unfurl): Boolean = runCatching {
    val u = URI(e.player ?: return false)
    u.scheme == "https" && u.host in PLAYER_HOSTS
}.getOrDefault(false)

/** An author that is only the site's own @account says nothing new (siteAccount). */
private fun siteAccount(author: String?, site: String?): Boolean {
    if (author == null || !author.startsWith("@") || site == null) return false
    val handle = author.substring(1).lowercase().replace(Regex("[^a-z0-9]"), "")
    val name = site.lowercase().replace(Regex("[^a-z0-9]"), "")
    if (handle.isEmpty() || name.isEmpty()) return false
    return handle == name || (handle.length >= 3 && name.length >= 3 && (name.contains(handle) || handle.contains(name)))
}

/** Words with every web address in them made a link (linkify). */
fun linkify(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).findAll(text)) {
        val start = m.range.first
        val url = trimUrl(m.value)
        if (start < at || !Regex("^https?://\\S", RegexOption.IGNORE_CASE).containsMatchIn(url)) continue
        if (start > at) append(text.substring(at, start))
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link)))) { append(url) }
        at = start + url.length
    }
    if (at < text.length) append(text.substring(at))
}

/** markdown trimUrl: the punctuation a sentence puts after an address is not part of it. */
fun trimUrl(url: String): String {
    var open = 0; var close = 0; var openSq = 0; var closeSq = 0
    for (ch in url) when (ch) {
        '(' -> open++
        ')' -> close++
        '[' -> openSq++
        ']' -> closeSq++
    }
    var end = url.length
    while (end > 0) {
        val last = url[end - 1]
        if (last in ".,;:!?") { end--; continue }
        if (last == ')' && close > open) { close--; end--; continue }
        if (last == ']' && closeSq > openSq) { closeSq--; end--; continue }
        break
    }
    return url.substring(0, end)
}

/** Words that fold after a number of lines, with Show More and Show Less when they run longer. */
@Composable
fun Folding(text: AnnotatedString, style: TextStyle, lines: Int) {
    val c = Nx.colors
    var open by remember(text) { mutableStateOf(false) }
    var folds by remember(text) { mutableStateOf(false) }
    BasicText(
        text,
        style = style,
        maxLines = if (open) Int.MAX_VALUE else lines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!open) folds = it.hasVisualOverflow },
    )
    if (folds || open) Txt(
        if (open) t("chat.embeds.showLess") else t("chat.embeds.showMore"),
        style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textMuted,
        modifier = Modifier.pressable(remember { MutableInteractionSource() }) { open = !open }.padding(vertical = 4.dp),
    )
}

/** The card a link gets: the site, the title, the words, a picture or the page's own media. */
@Composable
private fun LinkCard(e: Unfurl, from: String) {
    val c = Nx.colors
    val image = mediaSrc(e.image)
    val media = mediaSrc(e.media)
    val yt = youtubeId(e)
    val player = yt == null && playerHost(e)
    val big = e.image != null && (e.imageLayout == "large" || (e.imageLayout != "thumbnail" && (e.imageW ?: 0.0) >= 400))
    val bar = e.color?.let { parseHex(it) } ?: c.brand
    val shape = Radii.Md
    Box(
        Modifier
            .widthIn(max = 440.dp)
            .shadowLow(shape, c)
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .background(c.embedBg)
            .background(sheen(c))
            .topEdge(shape, c.edgeLight)
            .startBar(bar, 3.dp),
    ) {
        Column(Modifier.padding(start = 11.dp, end = 12.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CardHead(e)
                    e.title?.let { title ->
                        Txt(
                            title, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600), lineHeight = Nx.type.sp(19.5f)), color = c.link,
                            modifier = Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(e.url) },
                            maxLines = 2,
                        )
                    }
                    e.description?.let { d -> Folding(linkify(d, c.link), Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f), color = c.text), 3) }
                }
                if (!image.isNullOrEmpty() && !big && e.media == null && yt == null && !player) {
                    AsyncImage(
                        image, t("chat.embeds.linkPreviewImage"), imageLoader = Media.loader, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(Radii.Sm).pressable(remember { MutableInteractionSource() }) {
                            openLightbox(listOf(LightboxItem(fileNameOf(image, "image"), src = image, from = from)), 0)
                        },
                    )
                }
            }
            when {
                yt != null -> YouTubeBox(yt, e.title ?: t("perms.video"), image, e.videoStart, null, null, e.imageW, e.imageH)
                player -> PlayerPoster(e, image)
                !media.isNullOrEmpty() && e.mediaKind == "video" -> VideoPlayer(VideoSource.Url(media), fileNameOf(e.url, t("perms.video")), e.mediaW, e.mediaH, eager = false)
                !media.isNullOrEmpty() && e.mediaKind == "audio" -> Box(Modifier.widthIn(max = 400.dp)) {
                    AudioPlayer(android.net.Uri.parse(media), fileNameOf(e.url, t("chat.embeds.audio")), null, null, null, null, null)
                }
                !image.isNullOrEmpty() && big -> {
                    val fit = Fit.within(e.imageW, e.imageH, 360, 240)
                    AsyncImage(
                        image, e.title ?: e.siteName ?: t("chat.embeds.linkPreviewImage"), imageLoader = Media.loader, contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .then(if (fit != null) Modifier.width(fit.width.dp).aspectRatio(fit.width.toFloat() / fit.height) else Modifier.widthIn(max = 360.dp).heightIn(max = 240.dp))
                            .clip(Radii.Sm)
                            .pressable(remember { MutableInteractionSource() }) { openLightbox(listOf(LightboxItem(fileNameOf(image, "image"), src = image, from = from)), 0) },
                    )
                }
            }
        }
    }
}

/** The site's icon and name, who wrote it and when, in the card's first line. */
@Composable
private fun CardHead(e: Unfurl) {
    val c = Nx.colors
    val icon = mediaSrc(e.icon)
    val byline = if (siteAccount(e.author, e.siteName)) null else e.author
    val when_ = e.publishedAt?.let { Time.shortDate(it) }
    if (e.siteName == null && byline == null && e.authorUrl == null && when_ == null && icon == null) return
    val style = Nx.type.hint.copy(color = c.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        var broken by remember(icon) { mutableStateOf(false) }
        if (!icon.isNullOrEmpty() && !broken) AsyncImage(icon, null, imageLoader = Media.loader, modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)), onError = { broken = true })
        e.siteName?.let { BasicText(it, style = style) }
        val author = when {
            e.authorUrl != null -> byline ?: e.authorUrl.removePrefix("https://").removePrefix("http://")
            else -> byline
        }
        if (author != null) {
            BasicText("·", style = style.copy(color = c.textMuted.copy(alpha = 0.6f)))
            BasicText(
                author, style = style,
                modifier = if (e.authorUrl != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(e.authorUrl) } else Modifier,
            )
        }
        if (when_ != null) {
            BasicText("·", style = style.copy(color = c.textMuted.copy(alpha = 0.6f)))
            BasicText(when_, style = style)
        }
    }
}

/** A video site's player shown as its poster with a play button; the video plays on its own site. */
@Composable
private fun PlayerPoster(e: Unfurl, poster: String?) {
    Box(
        Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(Radii.Sm)
            .background(Color.Black)
            .pressable(remember { MutableInteractionSource() }) { openExternal(e.url) }
            .semantics { contentDescription = t("chat.embeds.playVideo") },
        contentAlignment = Alignment.Center,
    ) {
        if (!poster.isNullOrEmpty()) AsyncImage(poster, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(56.dp).background(Color(0xB3000000), CircleShape), contentAlignment = Alignment.Center) { Icon("Play", Color.White, size = 26.dp, filled = true) }
    }
}

/**
 * YouTubePlayer.tsx's box: the thumbnail, a play button and the length (or
 * Live, or Upcoming). Nothing from YouTube loads until it is tapped; then the
 * video opens in the YouTube app or the browser at the link's start time.
 */
@Composable
fun YouTubeBox(id: String, title: String, poster: String?, start: Double?, duration: Double?, live: String?, w: Double?, h: Double?) {
    val tall = w != null && h != null && h > w
    val maxTall = min(480.dp, (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
    Box(
        Modifier
            .then(if (tall) Modifier.height(maxTall).aspectRatio(9f / 16f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            .ring(Radii.Sm, Nx.colors.borderSubtle)
            .clip(Radii.Sm)
            .background(Color.Black)
            .pressable(remember { MutableInteractionSource() }) {
                val at = start?.takeIf { it > 0 }?.let { "&t=${it.toInt()}" } ?: ""
                openExternal("https://www.youtube.com/watch?v=$id$at")
            }
            .semantics { contentDescription = t("chat.embeds.playVideoNamed", "name" to title) },
        contentAlignment = Alignment.Center,
    ) {
        if (!poster.isNullOrEmpty()) AsyncImage(poster, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(60.dp).background(Color(0xB8000000), CircleShape), contentAlignment = Alignment.Center) { Icon("Play", Color.White, size = 26.dp, filled = true) }
        val badge: (@Composable () -> Unit)? = when {
            live == "now" -> ({
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon("Radio", Color.White, size = 13.dp)
                    Txt(t("chat.embeds.live"), style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = Color.White)
                }
            })
            live == "upcoming" -> ({ Txt(t("chat.embeds.upcoming"), style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = Color.White) })
            duration != null && duration > 0 -> ({ Txt(Time.duration(duration), style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = Color.White) })
            else -> null
        }
        if (badge != null) Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(if (live == "now") Color(0xFFEE0000) else Color(0xC7000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) { badge() }
    }
}
