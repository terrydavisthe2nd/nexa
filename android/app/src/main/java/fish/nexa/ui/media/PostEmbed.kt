package fish.nexa.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.media.Fit
import fish.nexa.media.Media
import fish.nexa.media.PostLink
import fish.nexa.media.PostMedia
import fish.nexa.media.PostPoll
import fish.nexa.media.SocialPost
import fish.nexa.media.Unfurl
import fish.nexa.media.mediaSrc
import fish.nexa.media.openExternal
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import java.net.URLEncoder

/** Each service's own colour (theirs, not the theme's), falling across the card's top corner as light. */
private fun tintOf(platform: String, brand: Color): Color = when (platform) {
    "twitter" -> Color(0xFF1D9BF0)
    "bluesky" -> Color(0xFF1185FE)
    "tiktok" -> Color(0xFFFE2C55)
    "instagram" -> Color(0xFFE1306C)
    "reddit" -> Color(0xFFFF4500)
    "telegram" -> Color(0xFF29A9EB)
    "threads" -> Color(0xFF8A8D91)
    "mastodon" -> Color(0xFF6364FF)
    "youtube" -> Color(0xFFFF0033)
    else -> brand
}

private val PROFILE: Map<String, (String) -> String> = mapOf(
    "twitter" to { n -> "https://x.com/$n" },
    "tiktok" to { n -> "https://www.tiktok.com/@$n" },
    "instagram" to { n -> "https://www.instagram.com/$n/" },
    "threads" to { n -> "https://www.threads.com/@$n" },
    "telegram" to { n -> "https://t.me/$n" },
    "youtube" to { n -> "https://www.youtube.com/@$n" },
)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private val TAG: Map<String, (String) -> String> = mapOf(
    "twitter" to { s -> "https://x.com/hashtag/${enc(s)}" },
    "tiktok" to { s -> "https://www.tiktok.com/tag/${enc(s)}" },
    "instagram" to { s -> "https://www.instagram.com/explore/tags/${enc(s)}/" },
    "threads" to { s -> "https://www.threads.com/search?q=${enc(s)}&serp_type=tags" },
    "youtube" to { s -> "https://www.youtube.com/hashtag/${enc(s.lowercase())}" },
)

private val PIECE = Regex(
    "(https?://[^\\s<>\"]+)|(^|[^\\p{L}\\p{N}_@/.])@([A-Za-z0-9_](?:[A-Za-z0-9_.]{0,28}[A-Za-z0-9_])?)|(^|[^\\p{L}\\p{N}_&/])#([\\p{L}\\p{N}_]{1,100})|(^|[^\\p{L}\\p{N}_/])/?([ru])/([A-Za-z0-9_-]{2,40})",
    RegexOption.IGNORE_CASE,
)

/** postText.tsx: a post's words with its addresses, @names, #tags and r/ u/ made links to the site. */
private fun postText(text: String, links: List<PostLink>, platform: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = linkColor))
    fun anchor(href: String, label: String) = withLink(LinkAnnotation.Url(href, style)) { append(label) }
    fun plain(s: String) {
        var at = 0
        for (m in PIECE.findAll(s)) {
            val start = m.range.first
            if (start < at) continue
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> {
                    val url = trimUrl(g[1])
                    if (!Regex("^https?://\\S", RegexOption.IGNORE_CASE).containsMatchIn(url)) continue
                    append(s.substring(at, start)); anchor(url, url); at = start + url.length
                }
                g[3].isNotEmpty() -> {
                    val to = PROFILE[platform] ?: continue
                    val lead = g[2]
                    append(s.substring(at, start + lead.length)); anchor(to(g[3]), "@${g[3]}"); at = m.range.last + 1
                }
                g[5].isNotEmpty() -> {
                    val to = TAG[platform] ?: continue
                    if (!Regex("\\p{L}").containsMatchIn(g[5])) continue
                    val lead = g[4]
                    append(s.substring(at, start + lead.length)); anchor(to(g[5]), "#${g[5]}"); at = m.range.last + 1
                }
                g[8].isNotEmpty() -> {
                    if (platform != "reddit") continue
                    val lead = g[6]
                    val kind = g[7].lowercase()
                    val shown = m.value.substring(lead.length)
                    append(s.substring(at, start + lead.length))
                    anchor(if (kind == "r") "https://www.reddit.com/r/${g[8]}/" else "https://www.reddit.com/user/${g[8]}", shown)
                    at = m.range.last + 1
                }
            }
        }
        if (at < s.length) append(s.substring(at))
    }
    var at = 0
    for (l in links) {
        if (l.text.isEmpty() || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(l.url)) continue
        val found = text.indexOf(l.text, at)
        if (found < 0) continue
        plain(text.substring(at, found))
        anchor(l.url, l.text)
        at = found + l.text.length
    }
    plain(text.substring(at))
}

/** PostEmbed.tsx: a social post drawn as the post it is: who, what, its pictures or video, its poll and counts. */
@Composable
fun PostEmbed(e: Unfurl) {
    val c = Nx.colors
    val post = e.post ?: return
    val tint = tintOf(post.platform, c.brand)
    val video = if (post.platform == "youtube" && isYouTubeId(e.videoId)) e.videoId to e.videoStart else null
    val shape = Radii.Md
    Column(
        Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .shadowLow(shape, c)
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .background(c.embedBg)
            .background(sheen(c))
            .background(Brush.radialGradient(0f to tint.copy(alpha = 0.14f), 0.72f to Color.Transparent, center = Offset.Zero, radius = 700f))
            .topEdge(shape, c.edgeLight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PostView(post, e.icon, post.url ?: e.url, quoted = false, video = video, tint = tint)
    }
}

@Composable
private fun PostView(post: SocialPost, icon: String?, link: String?, quoted: Boolean, video: Pair<String?, Double?>?, tint: Color) {
    val c = Nx.colors
    val reddit = post.platform == "reddit"
    val first = if (reddit && post.community != null) post.community else post.name ?: post.handle?.let { "@$it" } ?: post.community ?: post.site ?: ""
    val firstLink = if (reddit && post.community != null) post.communityUrl else post.authorUrl
    val second = if (reddit) (if (post.community != null) post.name else null) else if (post.name != null && post.handle != null) "@${post.handle}" else null
    val site = post.site ?: ""
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Face(mediaSrc(post.avatar), mediaSrc(icon), first, if (quoted) 20 else 32, tint)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(
                    first,
                    style = Nx.type.name.copy(fontSize = Nx.type.sp(if (quoted) 14f else 15f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).then(if (firstLink != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(firstLink) } else Modifier),
                )
                if (post.verified) Icon("BadgeCheck", tint, size = if (quoted) 14.dp else 16.dp, label = t("chat.post.verified"))
            }
            if (second != null || post.subscribers != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (second != null) Txt(
                    second, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1,
                    modifier = if (reddit && post.authorUrl != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(post.authorUrl) } else Modifier,
                )
                post.subscribers?.let { n -> Txt(t("chat.post.subscribers", "count" to n.toLong(), "n" to Time.compact(n.toLong())), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1) }
            }
        }
        if (quoted) {
            post.created?.let { at -> Txt(Time.shortDate(at), style = Nx.type.hint, color = c.textMuted, modifier = if (link != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(link) } else Modifier) }
        } else SiteChip(site, mediaSrc(icon), link)
    }
    post.replyTo?.let { Txt(t("chat.post.replyingTo", "name" to "@$it"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted) }
    if (!reddit && post.community != null) Txt(
        t("chat.post.forwardedFrom", "name" to post.community), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted,
        modifier = if (post.communityUrl != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(post.communityUrl) } else Modifier,
    )
    post.title?.let { Txt(it, style = Nx.type.name.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(19.5f)), color = c.textStrong, maxLines = 2) }
    if (video != null && video.first != null) PostMediaBlock(post, link, quoted, video)
    post.text?.takeIf { it.isNotEmpty() }?.let { text ->
        val words = remember(text, post.links, post.platform) { postText(text, post.links, post.platform, c.link) }
        Folding(words, Nx.type.body.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f), color = c.text), if (quoted) 3 else if (video != null) 2 else 6)
    }
    if (video == null || video.first == null) PostMediaBlock(post, link, quoted, null)
    post.poll?.let { PostPollView(it, tint) }
    post.note?.let { note ->
        Column(Modifier.fillMaxWidth().background(c.bgSurface, Radii.Sm).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon("UsersRound", c.textMuted, size = 14.dp)
                Txt(t("chat.post.note"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textMuted)
            }
            Txt(note, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.text)
        }
    }
    if (!quoted) post.quote?.let { q ->
        Column(
            Modifier.fillMaxWidth().clip(Radii.Sm).background(mixSrgb(c.embedBg, c.bgBase, 0.38f)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { PostView(q, icon, q.url, quoted = true, video = null, tint = tintOf(q.platform, c.brand)) }
    }
    if (!quoted) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Counts(post, Modifier.weight(1f))
            post.created?.let { at ->
                Txt(Time.shortDate(at), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted,
                    modifier = if (link != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(link) } else Modifier)
            }
        }
    }
}

/** No picture of the author: the service's icon on a tile of its colour, or the first letter. */
@Composable
private fun Face(avatar: String?, icon: String?, name: String, size: Int, tint: Color) {
    val c = Nx.colors
    var broken by remember(avatar) { mutableStateOf(false) }
    if (!avatar.isNullOrEmpty() && !broken) {
        AsyncImage(avatar, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).ring(CircleShape, c.borderSubtle).clip(CircleShape).background(c.bgInput), onError = { broken = true })
        return
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(mixSrgb(c.bgSurface, tint, 0.18f)), contentAlignment = Alignment.Center) {
        if (!icon.isNullOrEmpty()) AsyncImage(icon, null, imageLoader = Media.loader, modifier = Modifier.size((size * 0.56f).dp).clip(RoundedCornerShape(4.dp)))
        else Txt(name.trimStart('@', '/').take(1).uppercase(), style = Nx.type.body.copy(fontSize = Nx.type.sp(if (size < 30) 10f else 14f), fontWeight = FontWeight(700)), color = c.textMuted)
    }
}

/** The way out to the post: the service's icon and name on a small chip; a phone shows only the icon. */
@Composable
private fun SiteChip(site: String, icon: String?, link: String?) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    BoxWithConstraints {
        Row(
            Modifier
                .clip(shape)
                .background(c.textStrong.copy(alpha = 0.05f))
                .topEdge(shape, c.edgeLight)
                .then(if (link != null) Modifier.pressable(remember { MutableInteractionSource() }) { openExternal(link) } else Modifier)
                .semantics { if (site.isNotEmpty()) contentDescription = t("chat.post.openOn", "site" to site) }
                .padding(horizontal = if (icon != null) 6.dp else 9.dp, vertical = if (icon != null) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!icon.isNullOrEmpty()) AsyncImage(icon, null, imageLoader = Media.loader, modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)))
            else Txt(site, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.textMuted)
        }
    }
}

/** The post's pictures in a grid (one alone at its shape, two side by side, three with the first tall, four square), then videos. */
@Composable
private fun PostMediaBlock(post: SocialPost, link: String?, quoted: Boolean, video: Pair<String?, Double?>?) {
    val c = Nx.colors
    var covered by remember(post) { mutableStateOf(post.sensitive) }
    val media = post.media
    if (media.isEmpty()) return
    val site = post.site ?: ""
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().then(if (covered && canBlur) Modifier.blur(30.dp) else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (covered && !canBlur) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.bgInput, Radii.Sm))
            else if (video != null && media.firstOrNull()?.kind == "video") {
                val m = media[0]
                YouTubeBox(video.first!!, post.title ?: m.alt ?: site, mediaSrc(m.thumb), video.second, m.duration, post.live, m.w, m.h)
            } else {
                val players = media.filter { it.kind == "video" && it.url != null }
                val tiles = media.filter { !(it.kind == "video" && it.url != null) }
                val photos = tiles.filter { it.kind == "photo" && it.url != null }
                val items = photos.mapIndexedNotNull { i, m -> mediaSrc(m.url)?.takeIf { it.isNotEmpty() }?.let { LightboxItem(m.alt ?: t("chat.post.pictureOf", "index" to i + 1, "count" to photos.size), src = it) } }
                val shown = tiles.take(4)
                val extra = tiles.size - shown.size
                if (shown.isNotEmpty()) PostGrid(shown, extra, quoted, link, site, items, photos)
                for (m in players) {
                    val url = mediaSrc(m.url)
                    if (url.isNullOrEmpty()) continue
                    VideoPlayer(VideoSource.Url(url, mediaSrc(m.audio)?.takeIf { it.isNotEmpty() }), "video.mp4", m.w, m.h, poster = mediaSrc(m.thumb)?.takeIf { it.isNotEmpty() }, eager = false)
                }
            }
        }
        if (covered) Column(
            Modifier.matchParentSize().background(Color(0x73000000), Radii.Sm).pressable(remember { MutableInteractionSource() }) { covered = false },
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(t("chat.post.sensitive"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = Color.White)
            Box(Modifier.background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Txt(t("chat.post.show"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = Color.White)
            }
        }
    }
}

@Composable
private fun PostGrid(shown: List<PostMedia>, extra: Int, quoted: Boolean, link: String?, site: String, items: List<LightboxItem>, photos: List<PostMedia>) {
    val c = Nx.colors
    val alone = if (shown.size == 1 && !quoted) Fit.within(shown[0].w, shown[0].h, 416, 320) else null
    val shape = Radii.Sm
    val grid = Modifier
        .then(
            when {
                alone != null -> Modifier.width(alone.width.dp).aspectRatio(alone.width.toFloat() / alone.height)
                shown.size == 1 -> Modifier.widthIn(max = 416.dp).heightIn(max = 320.dp)
                quoted -> Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(16f / 10f)
                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
        )
        .ring(shape, c.borderSubtle)
        .clip(shape)
        .background(c.bgInput)
    @Composable
    fun tile(i: Int, modifier: Modifier) {
        val m = shown[i]
        val last = i == shown.size - 1 && extra > 0
        Box(modifier.background(c.bgInput)) {
            TileContent(m, items, photos, link, site)
            if (last) Box(Modifier.matchParentSize().background(Color(0x8C000000)), contentAlignment = Alignment.Center) {
                Txt("+$extra", style = Nx.type.name.copy(fontSize = Nx.type.sp(22f), fontWeight = FontWeight(650)), color = Color.White)
            }
        }
    }
    val gap = 3.dp
    when (shown.size) {
        1 -> Box(grid) { tile(0, Modifier.fillMaxSize()) }
        2 -> Row(grid, horizontalArrangement = Arrangement.spacedBy(gap)) {
            tile(0, Modifier.weight(1f).fillMaxHeight()); tile(1, Modifier.weight(1f).fillMaxHeight())
        }
        3 -> Row(grid, horizontalArrangement = Arrangement.spacedBy(gap)) {
            tile(0, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                tile(1, Modifier.weight(1f).fillMaxWidth()); tile(2, Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(grid, verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                tile(0, Modifier.weight(1f).fillMaxHeight()); tile(1, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                tile(2, Modifier.weight(1f).fillMaxHeight()); tile(3, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun BoxScope.TileContent(m: PostMedia, items: List<LightboxItem>, photos: List<PostMedia>, link: String?, site: String) {
    when (m.kind) {
        "photo" -> {
            val url = mediaSrc(m.url) ?: return
            if (url.isEmpty()) return
            val at = photos.indexOf(m)
            AsyncImage(
                url, m.alt, imageLoader = Media.loader, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().pressable(remember { MutableInteractionSource() }) {
                    val index = items.indexOfFirst { it.src == url }.takeIf { it >= 0 } ?: at.coerceAtLeast(0)
                    openLightbox(items, index)
                },
            )
        }
        "gif" -> {
            val url = mediaSrc(m.url) ?: return
            if (url.isEmpty()) return
            // a GIF from a post is a silent looping video; the player starts it muted and loops it
            LoopingVideo(url, mediaSrc(m.thumb))
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Txt(t("chat.embeds.gif"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = Color.White)
            }
        }
        else -> {
            val poster = mediaSrc(m.thumb)
            if (!poster.isNullOrEmpty()) AsyncImage(poster, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.matchParentSize().pressable(remember { MutableInteractionSource() }) { link?.let { openExternal(it) } }
                    .semantics { contentDescription = t("chat.post.watchOn", "site" to site) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(52.dp).background(Color(0xB3000000), CircleShape), contentAlignment = Alignment.Center) { Icon("Play", Color.White, size = 22.dp, filled = true) }
            }
        }
    }
}

/** The post's poll: a bar of the service's colour behind each choice, the leader stronger, then the votes and when it ends. */
@Composable
private fun PostPollView(poll: PostPoll, tint: Color) {
    val c = Nx.colors
    val top = poll.choices.maxOfOrNull { it.percent } ?: 0.0
    val closed = poll.ends != null && poll.ends < System.currentTimeMillis()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (ch in poll.choices) {
            val lead = top > 0 && ch.percent == top
            Box(Modifier.fillMaxWidth().clip(Radii.Xs)) {
                Box(Modifier.matchParentSize().fillMaxWidth((ch.percent / 100).coerceIn(0.0, 1.0).toFloat()).background(tint.copy(alpha = if (lead) 0.42f else 0.24f)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Txt(ch.label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = if (lead) FontWeight(700) else FontWeight(400)), color = c.text, modifier = Modifier.weight(1f))
                    Txt(Time.percent(ch.percent), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = if (lead) FontWeight(700) else FontWeight(400)), color = c.text)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            poll.votes?.let { v -> Txt(t("chat.post.votes", "count" to v.toLong(), "n" to Time.compact(v.toLong())), style = Nx.type.hint, color = c.textMuted) }
            if (closed) Txt(t("chat.post.pollClosed"), style = Nx.type.hint, color = c.textMuted)
            else poll.ends?.let { Txt(t("chat.post.pollEnds", "date" to Time.shortDate(it)), style = Nx.type.hint, color = c.textMuted) }
        }
    }
}

/** Replies, reposts (shares on TikTok), likes (points, reactions) and views; nothing for a count of zero. */
@Composable
private fun Counts(post: SocialPost, modifier: Modifier) {
    val c = Nx.colors
    val (likeIcon, likeKey) = when (post.platform) {
        "reddit" -> "ArrowBigUp" to "chat.post.points"
        "telegram" -> "Smile" to "chat.post.reactions"
        else -> "Heart" to "chat.post.likes"
    }
    val shares = post.platform == "tiktok"
    val all = listOf(
        Triple("MessageCircle", post.replies, "chat.post.replies"),
        Triple(if (shares) "Forward" else "Repeat2", post.reposts, if (shares) "chat.post.shares" else "chat.post.reposts"),
        Triple(likeIcon, post.likes, likeKey),
        Triple("Eye", post.views, "chat.post.views"),
    ).filter { (it.second ?: 0.0) > 0 }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((icon, n, key) in all) {
            val count = n!!.toLong()
            Row(
                Modifier.semantics { contentDescription = t(key, "count" to count, "n" to Time.compact(count)) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, c.textMuted, size = 15.dp)
                Txt(Time.compact(count), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            }
        }
    }
}
