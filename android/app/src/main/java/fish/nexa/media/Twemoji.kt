package fish.nexa.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Twemoji, from the web client's own sprite sheets (assets/twemoji, copied at
 * build time from web/src/assets/twemoji-sprites): 64 px cells with a 2 px
 * border, 24 to a row. Every emoji the app draws (messages, names, the
 * picker, reactions) is the same artwork the web shows.
 */
object Twemoji {
    private const val ZWJ = "‍"
    private const val VS16 = "️"
    private var cell = 64
    private var pad = 2
    private var pitch = 68
    private var cols = 24
    private val at = HashMap<String, Pair<String, Int>>()
    private lateinit var appContext: Context
    private val sheets = ConcurrentHashMap<String, ImageBitmap>()

    fun init(context: Context) {
        appContext = context.applicationContext
        val root = Json.parseToJsonElement(context.assets.open("twemoji/index.json").use { it.readBytes().decodeToString() }) as JsonObject
        cell = root["cell"]!!.jsonPrimitive.int
        pad = root["pad"]!!.jsonPrimitive.int
        pitch = root["pitch"]!!.jsonPrimitive.int
        cols = root["cols"]!!.jsonPrimitive.int
        for ((k, v) in root["at"] as JsonObject) {
            val a = v as JsonArray
            at[k] = a[0].jsonPrimitive.content to a[1].jsonPrimitive.int
        }
    }

    /** The code point sequence a file is named after (lib/twemoji.ts twemojiCode). */
    fun code(text: String): String? {
        if (text.isEmpty()) return null
        val source = if (text.contains(ZWJ)) text else text.replace(VS16, "")
        val parts = ArrayList<String>()
        var i = 0
        while (i < source.length) {
            val cp = source.codePointAt(i)
            parts += Integer.toHexString(cp)
            i += Character.charCount(cp)
        }
        return parts.joinToString("-").ifEmpty { null }
    }

    /** Where an emoji is in the sheets, trying the sequence as written and without VS16 inside a ZWJ one. */
    fun locate(text: String): Pair<String, Int>? {
        val c = code(text) ?: return null
        at[c]?.let { return it }
        if (text.contains(ZWJ)) code(text.replace(VS16, ""))?.let { at[it]?.let { hit -> return hit } }
        // the names Twemoji gives a sequence, also when it was sent without its variation selectors
        for (name in fish.nexa.ui.markdown.TwemojiFiles.files(text)) at[name]?.let { return it }
        return null
    }

    fun has(text: String) = locate(text) != null

    fun sheet(name: String): ImageBitmap? {
        sheets[name]?.let { return it }
        return try {
            val bmp = appContext.assets.open("twemoji/$name.webp").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) } ?: return null
            bmp.asImageBitmap().also { sheets[name] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun srcOffset(index: Int) = IntOffset((index % cols) * pitch + pad, (index / cols) * pitch + pad)
    val srcSize get() = IntSize(cell, cell)
}

/** One emoji drawn from the sheets, or nothing when the set has no picture for it. */
@Composable
fun TwemojiImage(text: String, size: Dp, modifier: Modifier = Modifier) = TwemojiImage(text, modifier.size(size))

/** The same, as big as [modifier] makes it. */
@Composable
fun TwemojiImage(text: String, modifier: Modifier) {
    val spot = Twemoji.locate(text) ?: return
    val bmp = produceState<ImageBitmap?>(Twemoji.sheet(spot.first).takeIf { false }, spot.first) {
        value = withContext(Dispatchers.IO) { Twemoji.sheet(spot.first) }
    }.value
    Canvas(modifier) {
        val b = bmp ?: return@Canvas
        drawImage(b, Twemoji.srcOffset(spot.second), Twemoji.srcSize, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()), filterQuality = FilterQuality.High)
    }
}

/** Splits text into plain runs and emoji clusters (whole grapheme clusters, so families and flags stay together). */
object EmojiText {
    private val isEmojiStart: (Int) -> Boolean = { cp ->
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp in 0x1F1E6..0x1F1FF || cp == 0x00A9 || cp == 0x00AE || cp in 0x2190..0x21FF || cp in 0x3030..0x303D || cp in 0x3297..0x3299 ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp in 0x24C2..0x24C2 || cp in 0x25AA..0x25FE || cp in 0x2934..0x2935
    }

    data class Run(val text: String, val emoji: Boolean)

    fun split(text: String): List<Run> {
        val out = ArrayList<Run>()
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(text)
        var start = bi.first()
        var end = bi.next()
        val plain = StringBuilder()
        while (end != android.icu.text.BreakIterator.DONE) {
            val g = text.substring(start, end)
            val cp = g.codePointAt(0)
            val keycap = g.length >= 2 && g.contains('⃣')
            if ((isEmojiStart(cp) || keycap) && Twemoji.has(g)) {
                if (plain.isNotEmpty()) {
                    out += Run(plain.toString(), false)
                    plain.clear()
                }
                out += Run(g, true)
            } else plain.append(g)
            start = end
            end = bi.next()
        }
        if (plain.isNotEmpty()) out += Run(plain.toString(), false)
        return out
    }

    /** Text made only of emoji (and spaces), up to 30 of them: drawn large ("jumbo"), as the web does. */
    fun onlyEmoji(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 0
        var n = 0
        for (r in split(t)) {
            if (r.emoji) n++ else if (r.text.isNotBlank()) return 0
        }
        return if (n in 1..30) n else 0
    }
}
