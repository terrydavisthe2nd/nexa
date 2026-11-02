package fish.nexa.media

import android.content.Intent
import android.net.Uri
import fish.nexa.Nexa
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A link preview as a message carries it (api/types.ts UnfurlResult), read
 * from the sealed JSON the sender's app made, already held to
 * MessageCrypto.sanitizeEmbed.
 */
data class Unfurl(
    val url: String,
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val image: String? = null,
    val imageW: Double? = null,
    val imageH: Double? = null,
    val videoId: String? = null,
    val videoStart: Double? = null,
    val color: String? = null,
    val author: String? = null,
    val authorUrl: String? = null,
    val publishedAt: Long? = null,
    val icon: String? = null,
    val media: String? = null,
    val mediaKind: String? = null,
    val mediaW: Double? = null,
    val mediaH: Double? = null,
    val player: String? = null,
    val inviteCode: String? = null,
    val memberCount: Int? = null,
    val onlineCount: Int? = null,
    val banner: String? = null,
    val username: String? = null,
    val imageLayout: String? = null,
    val post: SocialPost? = null,
    val music: MusicCard? = null,
) {
    companion object {
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        fun of(o: JsonObject): Unfurl? {
            val url = o.s("url") ?: return null
            return Unfurl(
                url = url, type = o.s("type") ?: "link", title = o.s("title"), description = o.s("description"), siteName = o.s("site_name"),
                image = o.s("image"), imageW = o.n("image_w"), imageH = o.n("image_h"), videoId = o.s("video_id"), videoStart = o.n("video_start"),
                color = o.s("color"), author = o.s("author"), authorUrl = o.s("author_url"), publishedAt = o.n("published_at")?.toLong(), icon = o.s("icon"),
                media = o.s("media"), mediaKind = o.s("media_kind"), mediaW = o.n("media_w"), mediaH = o.n("media_h"), player = o.s("player"),
                inviteCode = o.s("invite_code"), memberCount = o.n("member_count")?.toInt(), onlineCount = o.n("online_count")?.toInt(), banner = o.s("banner"),
                username = o.s("username"), imageLayout = o.s("image_layout"), post = (o["post"] as? JsonObject)?.let { SocialPost.of(it) },
                music = (o["music"] as? JsonObject)?.let { MusicCard.of(it) },
            )
        }
    }
}

data class MusicTrack(val title: String, val artist: String?, val duration: Double?, val preview: String?, val explicit: Boolean)

/** A song, album, playlist, artist or episode on a music service (internal/unfurl/music.go), drawn as music. */
data class MusicCard(
    val platform: String,
    val kind: String,
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val art: String?,
    val duration: Double?,
    val count: Int?,
    val year: Int?,
    val explicit: Boolean,
    val preview: String?,
    val tracks: List<MusicTrack>,
    val player: String?,
    /** Where a preview comes from when it is not the link's own service: "deezer" or "apple". */
    val previewFrom: String? = null,
) {
    companion object {
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        private val PLATFORMS = setOf("spotify", "apple", "deezer", "soundcloud")
        private val KINDS = setOf("track", "album", "playlist", "artist", "episode", "show")

        fun of(o: JsonObject): MusicCard? {
            val platform = o.s("platform")?.takeIf { it in PLATFORMS } ?: return null
            val kind = o.s("kind")?.takeIf { it in KINDS } ?: return null
            val title = o.s("title")?.takeIf { it.isNotEmpty() } ?: return null
            val tracks = (o["tracks"] as? JsonArray).orEmpty().mapNotNull { t ->
                val to = t as? JsonObject ?: return@mapNotNull null
                MusicTrack(to.s("title") ?: return@mapNotNull null, to.s("artist"), to.n("duration"), to.s("preview"), to.b("explicit"))
            }.take(5)
            return MusicCard(
                platform, kind, o.s("id") ?: return null, title, o.s("artist"), o.s("album"), o.s("art"), o.n("duration"), o.n("count")?.toInt(), o.n("year")?.toInt(),
                o.b("explicit"), o.s("preview"), tracks, o.s("player"), o.s("preview_from")?.takeIf { it == "deezer" || it == "apple" },
            )
        }
    }
}

data class PostMedia(val kind: String, val url: String?, val thumb: String?, val audio: String?, val w: Double?, val h: Double?, val duration: Double?, val alt: String?)
data class PostLink(val text: String, val url: String)
data class PostChoice(val label: String, val votes: Double?, val percent: Double)
data class PostPoll(val choices: List<PostChoice>, val votes: Double?, val ends: Long?)

/** A social post as the server read it from its own site (internal/unfurl/post.go), drawn as a post. */
data class SocialPost(
    val platform: String,
    val site: String?,
    val url: String?,
    val name: String?,
    val handle: String?,
    val authorUrl: String?,
    val avatar: String?,
    val verified: Boolean,
    val community: String?,
    val communityUrl: String?,
    val title: String?,
    val text: String?,
    val links: List<PostLink>,
    val replyTo: String?,
    val created: Long?,
    val media: List<PostMedia>,
    val poll: PostPoll?,
    val quote: SocialPost?,
    val replies: Double?,
    val reposts: Double?,
    val likes: Double?,
    val views: Double?,
    val sensitive: Boolean,
    val note: String?,
    val subscribers: Double?,
    val live: String?,
) {
    companion object {
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        fun of(o: JsonObject): SocialPost? {
            val platform = o.s("platform") ?: return null
            val poll = (o["poll"] as? JsonObject)?.let { p ->
                PostPoll(p["choices"].objects().map { c -> PostChoice(c.s("label") ?: "", c.n("votes"), c.n("percent") ?: 0.0) }, p.n("votes"), p.n("ends")?.toLong())
            }
            return SocialPost(
                platform = platform, site = o.s("site"), url = o.s("url"), name = o.s("name"), handle = o.s("handle"), authorUrl = o.s("author_url"),
                avatar = o.s("avatar"), verified = o.b("verified"), community = o.s("community"), communityUrl = o.s("community_url"), title = o.s("title"),
                text = o.s("text"), links = o["links"].objects().mapNotNull { l -> l.s("text")?.let { t -> l.s("url")?.let { u -> PostLink(t, u) } } },
                replyTo = o.s("reply_to"), created = o.n("created")?.toLong(),
                media = o["media"].objects().map { m -> PostMedia(m.s("kind") ?: "photo", m.s("url"), m.s("thumb"), m.s("audio"), m.n("w"), m.n("h"), m.n("duration"), m.s("alt")) },
                poll = poll, quote = (o["quote"] as? JsonObject)?.let { of(it) },
                replies = o.n("replies"), reposts = o.n("reposts"), likes = o.n("likes"), views = o.n("views"),
                sensitive = o.b("sensitive"), note = o.s("note"), subscribers = o.n("subscribers"), live = o.s("live"),
            )
        }
    }
}

/** Opens a web address in the browser (or the app that owns it, as YouTube's does). */
fun openExternal(url: String) {
    runCatching {
        Nexa.app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** mediaLinks.ts isOnlyMediaLinks: a message that is nothing but the links of its picture or GIF previews. Audio and video keep their original links. */
fun isOnlyMediaLinks(text: String, embeds: List<Unfurl>): Boolean {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || embeds.isEmpty() || !embeds.all { it.type == "image" || it.type == "gif" }) return false
    val own = embeds.flatMap { listOfNotNull(it.url, it.image) }.toSet()
    val link = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    if (!words.all { it in own || link.matches(it) }) return false
    return embeds.size >= words.size
}
