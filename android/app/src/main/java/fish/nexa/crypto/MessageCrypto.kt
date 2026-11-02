package fish.nexa.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Message envelopes (web/src/crypto/message.ts).
 *
 * The server only sees {v, conv, epoch, iv, ct}. Channel and author are bound
 * into the additional data, so a ciphertext cannot be replayed into another
 * channel or pinned on another author. What comes out is held to exactly the
 * shapes the web client keeps, field by field: anyone with the key can seal
 * anything, and one wrong type must not crash every reader.
 */
data class Envelope(val v: Int, val conv: String, val epoch: Int, val iv: String, val ct: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", v)
        put("conv", conv)
        put("epoch", epoch)
        put("iv", iv)
        put("ct", ct)
    }

    companion object {
        fun from(el: JsonElement?): Envelope? {
            val o = el as? JsonObject ?: return null
            val v = o.int("v") ?: return null
            if (v != 1) return null
            val conv = o.str("conv") ?: return null
            val epochD = (o["epoch"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            if (epochD != Math.floor(epochD)) return null
            val iv = o.str("iv") ?: return null
            val ct = o.str("ct") ?: return null
            return Envelope(1, conv, epochD.toInt(), iv, ct)
        }
    }
}

data class Aad(val conv: String, val epoch: Int, val channelId: String, val authorId: String) {
    fun bytes(): ByteArray = Bytes.utf8("$conv|$epoch|$channelId|$authorId")
}

/** A picture's small copy (lib/pictureCopy.ts), encrypted under its own key. */
data class AttachmentPreview(val blobId: String, val key: String, val size: Long, val type: String, val w: Double, val h: Double)

data class AttachmentMeta(
    val blobId: String,
    val name: String,
    val type: String,
    val size: Long,
    val key: String,
    val w: Double? = null,
    val h: Double? = null,
    val spoiler: Boolean? = null,
    val duration: Double? = null,
    val preview: AttachmentPreview? = null,
    /** A webhook's file: stored as sent, not encrypted; `key` is empty. */
    val plain: Boolean = false,
    val alt: String? = null,
    /** A voice message recorded in the app: drawn as one, with the shape of its sound. */
    val voice: Boolean = false,
    /** base64 of the recording's loudness, a byte per bar (VoiceWave). */
    val wave: String? = null,
) {
    val isImage get() = type.startsWith("image/")
    val isVideo get() = type.startsWith("video/")
    val isAudio get() = type.startsWith("audio/")

    fun toJson(): JsonObject = buildJsonObject {
        put("blob_id", blobId)
        put("name", name)
        put("type", type)
        put("size", size)
        w?.let { put("w", num(it)) }
        h?.let { put("h", num(it)) }
        put("key", key)
        spoiler?.let { put("spoiler", it) }
        duration?.let { put("duration", num(it)) }
        if (voice) put("voice", true)
        wave?.let { put("wave", it) }
        preview?.let { p ->
            put("preview", buildJsonObject {
                put("blob_id", p.blobId)
                put("key", p.key)
                put("size", p.size)
                put("type", p.type)
                put("w", num(p.w))
                put("h", num(p.h))
            })
        }
    }
}

data class PollEmoji(val name: String, val id: String? = null, val animated: Boolean = false)
data class PollAnswer(val text: String, val emoji: PollEmoji? = null)
data class PollPayload(val question: String, val answers: List<PollAnswer>, val hours: Int, val multiselect: Boolean)

data class StickerRef(val id: String, val blob: String? = null, val name: String? = null, val w: Double? = null, val h: Double? = null, val animated: Boolean? = null, val format: String? = null)

/** What a message says once opened: text, files, a sticker, link cards, flags, a poll. */
data class MessagePayload(
    val t: String? = null,
    val att: List<AttachmentMeta>? = null,
    val sticker: StickerRef? = null,
    /** Link cards as the sender's app made them, already held to [MessageCrypto.sanitizeEmbed]. */
    val embeds: List<JsonObject>? = null,
    val flags: Double? = null,
    val poll: PollPayload? = null,
    /** Forwarded from another message (data/Forward.kt): when that one was sent (Unix ms). Its files are the original's own. */
    val fwdAt: Long? = null,
    val fwdComment: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        t?.let { put("t", it) }
        att?.let { list -> put("att", JsonArray(list.map { it.toJson() })) }
        sticker?.let { s ->
            put("sticker", buildJsonObject {
                put("id", s.id)
                s.blob?.let { put("blob", it) }
                s.name?.let { put("name", it) }
                s.w?.let { put("w", num(it)) }
                s.h?.let { put("h", num(it)) }
                s.animated?.let { put("animated", it) }
                s.format?.let { put("format", it) }
            })
        }
        embeds?.let { put("embeds", JsonArray(it)) }
        flags?.let { put("flags", num(it)) }
        fwdAt?.let { at -> put("fwd", buildJsonObject { put("at", at); fwdComment?.let { put("comment", it) } }) }
        poll?.let { p ->
            put("poll", buildJsonObject {
                put("question", p.question)
                put("answers", JsonArray(p.answers.map { a ->
                    buildJsonObject {
                        put("text", a.text)
                        a.emoji?.let { e ->
                            put("emoji", buildJsonObject {
                                put("name", e.name)
                                e.id?.let { put("id", it) }
                                if (e.animated) put("animated", true)
                            })
                        }
                    }
                }))
                put("hours", p.hours)
                put("multiselect", p.multiselect)
            })
        }
    }
}

/** Whole numbers go out as integers, as JSON.stringify writes them. */
internal fun num(d: Double): JsonPrimitive = if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.0e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.number(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }
internal fun JsonObject.int(k: String): Int? = number(k)?.takeIf { it == Math.floor(it) }?.toInt()
internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

object MessageCrypto {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    const val POLL_QUESTION_MAX = 300
    const val POLL_ANSWER_MAX = 55
    const val POLL_ANSWERS_MIN = 2
    const val POLL_ANSWERS_MAX = 10
    const val POLL_HOURS_MAX = 14 * 24

    fun encrypt(chanKey: ByteArray, aad: Aad, payload: MessagePayload): Envelope {
        val iv = Bytes.random(Bytes.IV_LEN)
        val ct = Bytes.gcmEncrypt(chanKey, iv, Bytes.utf8(payload.toJson().toString()), aad.bytes())
        return Envelope(1, aad.conv, aad.epoch, Bytes.b64(iv), Bytes.b64(ct))
    }

    fun decrypt(chanKey: ByteArray, env: Envelope, aad: Aad): MessagePayload {
        if (env.v != 1) throw DecryptError("unsupported envelope version")
        val iv: ByteArray
        val ct: ByteArray
        try {
            iv = Bytes.unb64(env.iv)
            ct = Bytes.unb64(env.ct)
        } catch (_: IllegalArgumentException) {
            throw DecryptError("malformed envelope")
        }
        if (iv.size != Bytes.IV_LEN) throw DecryptError("malformed envelope")
        val plaintext = try {
            Bytes.gcmDecrypt(chanKey, iv, ct, aad.bytes())
        } catch (_: DecryptError) {
            throw DecryptError("message failed to decrypt")
        }
        val parsed = try {
            json.parseToJsonElement(Bytes.str(plaintext))
        } catch (_: Exception) {
            throw DecryptError("malformed message payload")
        }
        val obj = parsed as? JsonObject ?: throw DecryptError("malformed message payload")
        return sanitizePayload(obj)
    }

    private val BLOB_ID = Regex("^\\d{1,20}$")
    private val PREVIEW_TYPES = setOf("image/webp", "image/jpeg", "image/png")
    private const val PREVIEW_MAX = 2L * 1024 * 1024

    private fun sanitizePreview(x: JsonElement?): AttachmentPreview? {
        val o = x as? JsonObject ?: return null
        val blobId = o.str("blob_id") ?: return null
        val key = o.str("key") ?: return null
        val type = o.str("type") ?: return null
        if (!BLOB_ID.matches(blobId) || type !in PREVIEW_TYPES) return null
        val size = o.number("size") ?: return null
        if (size != Math.floor(size) || size <= 0 || size > PREVIEW_MAX) return null
        val w = o.number("w") ?: return null
        val h = o.number("h") ?: return null
        if (w <= 0 || h <= 0) return null
        return AttachmentPreview(blobId, key, size.toLong(), type, w, h)
    }

    /** A file as someone else wrote it into a message or a note, held to what a file can be. */
    fun sanitizeAttachment(x: JsonElement?): AttachmentMeta? {
        val o = x as? JsonObject ?: return null
        val blobId = o.str("blob_id") ?: return null
        if (!BLOB_ID.matches(blobId)) return null
        val name = o.str("name") ?: return null
        val type = o.str("type") ?: return null
        val key = o.str("key") ?: return null
        val size = o.number("size") ?: return null
        if (size != Math.floor(size) || size < 0 || size > 9.007199254740991E15) return null
        return AttachmentMeta(
            blobId = blobId, name = name, type = type, size = size.toLong(), key = key,
            w = o.number("w"), h = o.number("h"), duration = o.number("duration"),
            spoiler = o.bool("spoiler"),
            preview = if (type.startsWith("image/")) sanitizePreview(o["preview"]) else null,
            voice = type.startsWith("audio/") && o.bool("voice") == true,
            wave = o.str("wave")?.takeIf { type.startsWith("audio/") && WAVE.matches(it) },
        )
    }

    /** A voice message's shape: at most 96 bytes of base64 (crypto/message.ts). */
    private val WAVE = Regex("^[A-Za-z0-9+/]{1,128}={0,2}$")

    private val EMBED_STRINGS = listOf("url", "type", "title", "description", "site_name", "image", "video_id", "mime", "color", "invite_code", "author", "author_url", "icon", "media", "media_mime", "player", "player_site", "banner")
    private val EMBED_NUMBERS = listOf("image_w", "image_h", "member_count", "online_count", "published_at", "media_w", "media_h", "video_start")
    private val EMBED_CHOICES = mapOf("media_kind" to listOf("video", "audio"), "image_layout" to listOf("large", "thumbnail"))
    private val EMBED_LINKS = setOf("url", "image", "author_url", "icon", "media", "player", "banner")
    private val BAD_LINK_CHARS = Regex("[\\\\\\s\\u0000-\\u001f\\u007f]")
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** An http(s) address or a path on this server; anything else was written to do something other than link. */
    fun webLink(v: String): Boolean {
        if (BAD_LINK_CHARS.containsMatchIn(v)) return false
        if (v.startsWith("/")) return !v.startsWith("//")
        return HTTP.containsMatchIn(v)
    }

    fun sanitizeEmbed(e: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in EMBED_STRINGS) {
            val v = e.str(k) ?: continue
            if (k !in EMBED_LINKS || webLink(v)) out[k] = JsonPrimitive(v)
        }
        for (k in EMBED_NUMBERS) e.number(k)?.let { out[k] = num(it) }
        (out["video_start"] as? JsonPrimitive)?.doubleOrNull?.let { if (!(it > 0 && it <= 86400)) out.remove("video_start") }
        for ((k, allowed) in EMBED_CHOICES) {
            val v = e.str(k) ?: continue
            if (v in allowed) out[k] = JsonPrimitive(v)
        }
        sanitizePost(e["post"], false)?.let { out["post"] = it }
        sanitizeMusic(e["music"])?.let { out["music"] = it }
        return JsonObject(out)
    }

    // A music card (internal/unfurl/music.go), as crypto/message.ts sanitizeMusic holds it.
    private val MUSIC_PLATFORMS = setOf("spotify", "apple", "deezer", "soundcloud")
    private val MUSIC_KINDS = setOf("track", "album", "playlist", "artist", "episode", "show")
    private val MUSIC_PLAYERS = setOf("spotify", "soundcloud")
    private val MUSIC_ID = Regex("^[A-Za-z0-9.-]{1,64}$")

    private fun sanitizeMusicTrack(x: JsonElement?): JsonObject? {
        val o = x as? JsonObject ?: return null
        val title = o.str("title")?.takeIf { it.isNotEmpty() } ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        out["title"] = JsonPrimitive(cap(title, 256))
        o.str("artist")?.takeIf { it.isNotEmpty() }?.let { out["artist"] = JsonPrimitive(cap(it, 256)) }
        o.number("duration")?.takeIf { it > 0 && it <= 360000 }?.let { out["duration"] = JsonPrimitive(kotlin.math.floor(it).toLong()) }
        address(o["preview"])?.let { out["preview"] = JsonPrimitive(it) }
        if (o.bool("explicit") == true) out["explicit"] = JsonPrimitive(true)
        return JsonObject(out)
    }

    fun sanitizeMusic(x: JsonElement?): JsonObject? {
        val o = x as? JsonObject ?: return null
        val platform = o.str("platform")?.takeIf { it in MUSIC_PLATFORMS } ?: return null
        val kind = o.str("kind")?.takeIf { it in MUSIC_KINDS } ?: return null
        val id = o.str("id")?.takeIf { MUSIC_ID.matches(it) } ?: return null
        val title = o.str("title")?.takeIf { it.isNotEmpty() } ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        out["platform"] = JsonPrimitive(platform); out["kind"] = JsonPrimitive(kind); out["id"] = JsonPrimitive(id); out["title"] = JsonPrimitive(cap(title, 256))
        for (k in listOf("artist", "album")) o.str(k)?.takeIf { it.isNotEmpty() }?.let { out[k] = JsonPrimitive(cap(it, 256)) }
        address(o["art"])?.let { out["art"] = JsonPrimitive(it) }
        address(o["preview"])?.let { out["preview"] = JsonPrimitive(it) }
        for ((k, max) in listOf("duration" to 360000.0, "count" to 100000.0, "year" to 3000.0)) {
            o.number(k)?.takeIf { it > 0 && it <= max }?.let { out[k] = JsonPrimitive(kotlin.math.floor(it).toLong()) }
        }
        if (o.bool("explicit") == true) out["explicit"] = JsonPrimitive(true)
        o.str("player")?.takeIf { it in MUSIC_PLAYERS && it == platform }?.let { out["player"] = JsonPrimitive(it) }
        (o["tracks"] as? JsonArray)?.take(5)?.mapNotNull { sanitizeMusicTrack(it) }?.takeIf { it.isNotEmpty() }?.let { out["tracks"] = JsonArray(it) }
        return JsonObject(out)
    }

    /** At most n code points, so an emoji is never cut in half. */
    private fun cap(v: String, n: Int): String {
        val count = v.codePointCount(0, v.length)
        return if (count > n) v.substring(0, v.offsetByCodePoints(0, n)) else v
    }

    private val POST_TEXTS = mapOf("platform" to 20, "site" to 40, "name" to 256, "handle" to 256, "community" to 256, "title" to 300, "text" to 4000, "reply_to" to 256, "note" to 2048)
    private val POST_ADDRESSES = listOf("url", "author_url", "avatar", "community_url")
    private val POST_COUNTS = listOf("created", "replies", "reposts", "likes", "views", "subscribers")
    private val POST_MEDIA_KINDS = listOf("photo", "video", "gif")
    private const val MAX_ADDRESS = 8192
    private val PLATFORM = Regex("^[a-z]{2,20}$")

    private fun address(v: JsonElement?): String? {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return if (s.length <= MAX_ADDRESS && webLink(s)) s else null
    }

    private fun sanitizePostMedia(x: JsonElement?): JsonObject? {
        val o = x as? JsonObject ?: return null
        val kind = o.str("kind")?.takeIf { it in POST_MEDIA_KINDS } ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        out["kind"] = JsonPrimitive(kind)
        for (k in listOf("url", "thumb", "audio")) address(o[k])?.let { out[k] = JsonPrimitive(it) }
        if (out["url"] == null && (kind == "photo" || out["thumb"] == null)) return null
        for (k in listOf("w", "h", "duration")) o.number(k)?.let { if (it > 0 && it <= 100000) out[k] = num(it) }
        o.str("alt")?.takeIf { it.isNotEmpty() }?.let { out["alt"] = JsonPrimitive(cap(it, 1000)) }
        return JsonObject(out)
    }

    private fun sanitizePost(x: JsonElement?, quoted: Boolean): JsonObject? {
        val o = x as? JsonObject ?: return null
        val platform = o.str("platform") ?: return null
        if (!PLATFORM.matches(platform)) return null
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, max) in POST_TEXTS) o.str(k)?.takeIf { it.isNotEmpty() }?.let { out[k] = JsonPrimitive(cap(it, max)) }
        for (k in POST_ADDRESSES) address(o[k])?.let { out[k] = JsonPrimitive(it) }
        for (k in POST_COUNTS) o.number(k)?.let { if (it > 0) out[k] = num(it) }
        for (k in listOf("verified", "sensitive")) if (o.bool(k) == true) out[k] = JsonPrimitive(true)
        o.str("live")?.let { if (it == "now" || it == "upcoming") out["live"] = JsonPrimitive(it) }
        (o["media"] as? JsonArray)?.let { arr ->
            val media = arr.take(10).mapNotNull { sanitizePostMedia(it) }
            if (media.isNotEmpty()) out["media"] = JsonArray(media)
        }
        (o["links"] as? JsonArray)?.let { arr ->
            val links = arr.take(20).mapNotNull { l ->
                val lo = l as? JsonObject ?: return@mapNotNull null
                val text = lo.str("text")?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return@mapNotNull null
                val url = address(lo["url"]) ?: return@mapNotNull null
                JsonObject(mapOf("text" to JsonPrimitive(text), "url" to JsonPrimitive(url)))
            }
            if (links.isNotEmpty()) out["links"] = JsonArray(links)
        }
        (o["poll"] as? JsonObject)?.let { poll ->
            val choices = (poll["choices"] as? JsonArray)?.take(10)?.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                val label = co.str("label") ?: return@mapNotNull null
                val percent = co.number("percent") ?: return@mapNotNull null
                val m = LinkedHashMap<String, JsonElement>()
                m["label"] = JsonPrimitive(cap(label, 200))
                m["percent"] = num(percent.coerceIn(0.0, 100.0))
                co.number("votes")?.let { if (it >= 0) m["votes"] = num(it) }
                JsonObject(m)
            } ?: emptyList()
            if (choices.size >= 2) {
                val p = LinkedHashMap<String, JsonElement>()
                p["choices"] = JsonArray(choices)
                poll.number("votes")?.let { if (it >= 0) p["votes"] = num(it) }
                poll.number("ends")?.let { if (it > 0) p["ends"] = num(it) }
                out["poll"] = JsonObject(p)
            }
        }
        if (!quoted) sanitizePost(o["quote"], true)?.let { out["quote"] = it }
        return JsonObject(out)
    }

    private val EMOJI_ID = Regex("^\\d{1,20}$")

    private fun sanitizePollEmoji(x: JsonElement?): PollEmoji? {
        val o = x as? JsonObject ?: return null
        val name = o.str("name")?.takeIf { it.isNotEmpty() } ?: return null
        return PollEmoji(cap(name, 64), o.str("id")?.takeIf { EMOJI_ID.matches(it) }, o.bool("animated") == true)
    }

    /** A question and 2 to 10 answers; an answer that cannot be read spoils the poll, since ids are places in the list. */
    private fun sanitizePoll(x: JsonElement?): PollPayload? {
        val o = x as? JsonObject ?: return null
        val q = o.str("question")?.takeIf { it.isNotBlank() } ?: return null
        val list = o["answers"] as? JsonArray ?: return null
        if (list.size < POLL_ANSWERS_MIN || list.size > POLL_ANSWERS_MAX) return null
        val answers = ArrayList<PollAnswer>()
        for (a in list) {
            val ao = a as? JsonObject ?: return null
            val text = ao.str("text") ?: return null
            answers += PollAnswer(cap(text, POLL_ANSWER_MAX), sanitizePollEmoji(ao["emoji"]))
        }
        val h = o.number("hours")
        val hours = if (h != null && h == Math.floor(h) && h >= 1 && h <= POLL_HOURS_MAX) h.toInt() else 24
        return PollPayload(cap(q, POLL_QUESTION_MAX), answers, hours, o.bool("multiselect") == true)
    }

    /** What a message may hold (the web's MESSAGE_LIMITS): past any of it, it was made by something other than an app, and is not shown. */
    object Limits {
        const val TEXT = 4000
        const val FILES = 10
        const val FILE_NAME = 255
        const val FILE_TYPE = 255
        const val FILE_KEY = 64
        const val EMBEDS = 5
        const val EMBED_TITLE = 256
        const val EMBED_TEXT = 2048
        const val ADDRESS = 8192
        const val STICKER_NAME = 30
    }

    private val TOKENS = Regex("<(?:a?:[A-Za-z0-9_]{1,32}:|@&?|#)\\d{1,20}>")

    /** A message's text as long as it was to type: each mention and custom emoji as two characters. */
    fun typedLength(t: String): Int = t.replace(TOKENS, "··").length

    private fun runes(v: String): Int = v.codePointCount(0, v.length)

    /** The first limit a payload breaks, or null when it keeps to them all (the web's brokenLimit). */
    fun brokenLimit(p: JsonObject): String? {
        val fwd = p["fwd"] as? JsonObject
        if (fwd?.containsKey("comment") == true && (fwd.str("comment")?.let { typedLength(it) > Limits.TEXT } != false)) return "text"
        if (p.containsKey("t") && (p.str("t")?.let { typedLength(it) > Limits.TEXT } != false)) return "text"
        if (p.containsKey("att")) {
            val att = p["att"] as? JsonArray ?: return "files"
            if (att.size > Limits.FILES) return "files"
            for (a in att) {
                val m = sanitizeAttachment(a) ?: return "file"
                if (runes(m.name) > Limits.FILE_NAME || m.type.length > Limits.FILE_TYPE || m.key.length > Limits.FILE_KEY) return "file"
            }
        }
        if (p.containsKey("embeds")) {
            val embeds = p["embeds"] as? JsonArray ?: return "embeds"
            if (embeds.size > Limits.EMBEDS) return "embeds"
            for (x in embeds) {
                val e = x as? JsonObject ?: return "embed"
                if (e.str("url")?.let { webLink(it) } != true) return "embed"
                for (k in listOf("title", "site_name", "author")) if ((e.str(k)?.let { runes(it) } ?: 0) > Limits.EMBED_TITLE) return "embed"
                if ((e.str("description")?.let { runes(it) } ?: 0) > Limits.EMBED_TEXT) return "embed"
                for (k in EMBED_LINKS) if ((e.str(k)?.length ?: 0) > Limits.ADDRESS) return "embed"
            }
        }
        if (p.containsKey("sticker")) {
            val st = p["sticker"] as? JsonObject ?: return "sticker"
            if ((st.str("name")?.let { runes(it) } ?: 0) > Limits.STICKER_NAME) return "sticker"
        }
        if (p.containsKey("poll")) {
            val poll = p["poll"] as? JsonObject ?: return "poll"
            val q = poll.str("question") ?: return "poll"
            val answers = poll["answers"] as? JsonArray ?: return "poll"
            if (runes(q) > POLL_QUESTION_MAX || answers.size < POLL_ANSWERS_MIN || answers.size > POLL_ANSWERS_MAX) return "poll"
            for (a in answers) if (((a as? JsonObject)?.str("text") ?: return "poll").let { runes(it) } > POLL_ANSWER_MAX) return "poll"
            if (sanitizePoll(poll) == null) return "poll"
        }
        return null
    }

    fun sanitizePayload(p: JsonObject): MessagePayload {
        // a message past what any app sends was made to be, and is not shown
        if (brokenLimit(p) != null) throw DecryptError("over_limits")
        val t = p.str("t")
        val att = (p["att"] as? JsonArray)?.mapNotNull { sanitizeAttachment(it) }?.takeIf { it.isNotEmpty() }
        val sticker = (p["sticker"] as? JsonObject)?.let { st ->
            val id = st.str("id") ?: return@let null
            StickerRef(id, st.str("blob"), st.str("name"), st.number("w"), st.number("h"), st.bool("animated"), st.str("format"))
        }
        val embeds = (p["embeds"] as? JsonArray)
            ?.mapNotNull { e -> (e as? JsonObject)?.takeIf { it.str("url") != null } }
            ?.map { sanitizeEmbed(it) }
            ?.filter { it.str("url") != null }
            ?.takeIf { it.isNotEmpty() }
        val flags = p.number("flags")
        val poll = sanitizePoll(p["poll"])
        val hadContent = p.containsKey("t") || p.containsKey("att") || p.containsKey("sticker") || p.containsKey("embeds") || p.containsKey("poll")
        if (hadContent && t == null && att == null && sticker == null && embeds == null && poll == null) {
            throw DecryptError("malformed message payload")
        }
        val fwdAt = (p["fwd"] as? JsonObject)?.number("at")?.toLong()
        return MessagePayload(t, att, sticker, embeds, flags, poll, fwdAt, if (fwdAt != null) (p["fwd"] as? JsonObject)?.str("comment") else null)
    }
}
