package fish.nexa.ui.stickers

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.ui.components.AvatarColor
import fish.nexa.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun isEmojiPack(p: StickerPack) = p.kind == "emoji"
fun packCover(p: StickerPack): Sticker? = p.stickers.firstOrNull { it.id == p.coverStickerId } ?: p.stickers.firstOrNull()
fun packCount(p: StickerPack): String {
    val count = p.stickerCount ?: p.stickers.size
    return if (isEmojiPack(p)) t("server.packEditor.emojiCount", "count" to count) else t("server.packEditor.stickerCount", "count" to count)
}
fun packUrl(id: String) = fish.nexa.ui.markdown.AppLinks.shareOrigin(Nexa.api.baseUrl) + "/stickers/pack/" + id
private fun moves(s: Sticker) = s.animated || s.format == "tgs" || s.format == "webm"

/** canRemovePack: installed, not mine, and not a pack of a server I am in (those are managed there). */
fun canRemovePack(p: StickerPack, me: String?): Boolean = p.installed && p.ownerUserId != me && (p.serverId == null || Nexa.servers.get(p.serverId) == null)

/** The still picture of a sticker or an emoji (the server renders a moving one's first frame). */
fun stillUrl(s: Sticker, emoji: Boolean, size: Int, density: Float): String =
    if (emoji) Media.emojiUrl(s.blobId, size, density, still = moves(s)) else Media.stickerUrl(s.blobId, size, density) + if (moves(s)) "&still=1" else ""

/** A sticker's or an emoji's picture, still. */
@Composable
fun StickerStill(s: Sticker, emoji: Boolean, size: Dp, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    AsyncImage(model = stillUrl(s, emoji, size.value.toInt(), density), contentDescription = s.name, imageLoader = Media.loader, modifier = modifier, contentScale = ContentScale.Fit)
}

/** The stickers starred in the picker, by id, newest first; synced with the rest of the settings. */
object FavoriteStickers {
    fun ids(doc: kotlinx.serialization.json.JsonObject): List<String> = (doc["favoriteStickers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    fun toggle(id: String) {
        val list = ids(Nexa.settings.doc.value)
        val next = if (id in list) list - id else (listOf(id) + list).take(fish.nexa.data.SettingsStore.MAX_FAVORITE_STICKERS)
        Nexa.settings.patch(buildJsonObject { put("favoriteStickers", JsonArray(next.map { JsonPrimitive(it) })) })
    }
}

/** The packs folded away in the picker; synced too. */
object CollapsedPacks {
    fun ids(doc: kotlinx.serialization.json.JsonObject): Set<String> = (doc["collapsedStickerPacks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()

    fun toggle(id: String) {
        val list = ids(Nexa.settings.doc.value)
        val next = if (id in list) list - id else list + id
        Nexa.settings.patch(buildJsonObject { put("collapsedStickerPacks", JsonArray(next.map { JsonPrimitive(it) })) })
    }
}

/** lib/dominantColor.ts: coarse buckets, the fullest wins, colourful pixels counting for more than grey ones. */
private object PackColors {
    private val known = object : LinkedHashMap<String, Color?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color?>?) = size > 500
    }

    fun peek(url: String): Color? = synchronized(known) { known[url] }

    suspend fun of(url: String): Color? {
        synchronized(known) { if (known.containsKey(url)) return known[url] }
        val req = ImageRequest.Builder(Nexa.app).data(url).size(Size(24, 24)).allowHardware(false).build()
        val res = Media.loader.execute(req) as? SuccessResult ?: return null
        val found = withContext(Dispatchers.Default) { dominant(res.image.toBitmap()) }
        synchronized(known) { known[url] = found }
        return found
    }

    private fun dominant(bitmap: Bitmap): Color? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        class Bucket(var weight: Float = 0f, var r: Float = 0f, var g: Float = 0f, var b: Float = 0f)
        val buckets = HashMap<Int, Bucket>()
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val weight = 0.15f + (maxOf(r, g, b) - minOf(r, g, b)) / 255f
            val cur = buckets.getOrPut(((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)) { Bucket() }
            cur.weight += weight
            cur.r += r * weight
            cur.g += g * weight
            cur.b += b * weight
        }
        val best = buckets.values.maxByOrNull { it.weight } ?: return null
        return Color(best.r / best.weight / 255f, best.g / best.weight / 255f, best.b / best.weight / 255f)
    }
}

/** A pack's own colour: its cover's, or its steady one until that has loaded or if it has no cover. */
@Composable
fun rememberPackTint(pack: StickerPack): Color {
    val density = LocalDensity.current.density
    val cover = remember(pack) { packCover(pack) }
    val url = cover?.let { stillUrl(it, isEmojiPack(pack), 48, density) }
    val found by produceState(url?.let { PackColors.peek(it) }, url) { url?.let { u -> runCatching { PackColors.of(u) }.getOrNull()?.let { value = it } } }
    return found ?: AvatarColor.of(pack.id)
}
