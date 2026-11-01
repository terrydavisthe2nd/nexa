package fish.nexa.ui.settings

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.FileCrypto
import fish.nexa.ui.Icon
import fish.nexa.ui.ReadableError
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxSlider
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.NxToggle
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ---- the model (lib/soundPacks.ts), read as another device may have written it ----

val SOUND_NAMES = listOf("message", "mention", "friend", "ring", "outgoing", "join", "leave", "mute", "unmute", "deafen", "undeafen", "shareStart", "shareStop")
private const val NEXA_PACK = "nexa"
private const val MAX_PACKS = 8
private const val PACK_NAME_MAX = 40
private const val MAX_SOUND_MS = 6000
private const val MAX_LOOP_MS = 20_000
private const val MAX_SOURCE_BYTES = 30L * 1024 * 1024

data class SoundFile(val blob: String, val key: String, val name: String, val ms: Long, val size: Long)
data class SoundPack(val id: String, val name: String, val files: Map<String, SoundFile>)
data class SoundSettings(val pack: String, val volume: Int, val off: List<String>, val packs: List<SoundPack>)

private fun JsonElement?.prim() = this as? JsonPrimitive
private fun JsonElement?.string() = prim()?.takeIf { it.isString }?.contentOrNull
private fun JsonElement?.number() = prim()?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun readFile(x: JsonElement?): SoundFile? {
    val o = x as? JsonObject ?: return null
    val blob = o["blob"].string()?.takeIf { Regex("^\\d{1,20}$").matches(it) } ?: return null
    val key = o["key"].string()?.takeIf { it.length in 16..128 } ?: return null
    return SoundFile(
        blob, key,
        o["name"].string()?.take(120) ?: "",
        o["ms"].number()?.let { maxOf(0L, minOf(MAX_LOOP_MS.toLong(), Math.round(it))) } ?: 0L,
        o["size"].number()?.takeIf { it > 0 }?.let { Math.round(it) } ?: 0L,
    )
}

private fun readPack(x: JsonElement?): SoundPack? {
    val o = x as? JsonObject ?: return null
    val id = o["id"].string()?.takeIf { Regex("^[A-Za-z0-9_-]{4,40}$").matches(it) && it != NEXA_PACK } ?: return null
    val name = o["name"].string()?.takeIf { it.isNotBlank() }?.replace(Regex("\\s+"), " ")?.trim()?.take(PACK_NAME_MAX) ?: ""
    val files = (o["files"] as? JsonObject)?.let { f -> SOUND_NAMES.mapNotNull { n -> readFile(f[n])?.let { n to it } }.toMap() } ?: emptyMap()
    return SoundPack(id, name, files)
}

/** Sound settings as they can be trusted (sanitizeSounds). */
fun soundSettingsOf(x: JsonElement?): SoundSettings {
    val o = x as? JsonObject ?: return SoundSettings(NEXA_PACK, 100, emptyList(), emptyList())
    val packs = ArrayList<SoundPack>()
    val seen = HashSet<String>()
    for (raw in (o["packs"] as? JsonArray).orEmpty()) {
        val p = readPack(raw) ?: continue
        if (!seen.add(p.id)) continue
        packs += p
        if (packs.size >= MAX_PACKS) break
    }
    val pack = o["pack"].string()?.takeIf { it == NEXA_PACK || it in seen } ?: NEXA_PACK
    val volume = o["volume"].number()?.let { maxOf(0, minOf(100, Math.round(it).toInt())) } ?: 100
    val off = (o["off"] as? JsonArray)?.mapNotNull { it.string()?.takeIf { n -> n in SOUND_NAMES } }?.distinct() ?: emptyList()
    return SoundSettings(pack, volume, off, packs)
}

private fun SoundFile.json() = buildJsonObject {
    put("blob", blob)
    put("key", key)
    put("name", name)
    put("ms", ms)
    put("size", size)
}

private fun SoundSettings.json() = buildJsonObject {
    put("pack", pack)
    put("volume", volume)
    put("off", JsonArray(SOUND_NAMES.filter { it in off }.map { JsonPrimitive(it) }))
    put("packs", JsonArray(packs.map { p ->
        buildJsonObject {
            put("id", p.id)
            put("name", p.name)
            put("files", JsonObject(SOUND_NAMES.mapNotNull { n -> p.files[n]?.let { n to it.json() } }.toMap()))
        }
    }))
}

private fun saveSounds(s: SoundSettings) = setSetting("sounds", s.json())

/** A new pack's id: random, so two devices making one at once never pick the same. */
private fun newPackId(): String {
    val bytes = Bytes.random(9)
    return "p" + bytes.joinToString("") { (it.toInt() and 0xff).toString(36).padStart(2, '0') }.take(15)
}

// ---- turning a picked file into a sound (prepareSound) ----

/** Why a picked file cannot be a sound. */
class SoundProblem(val code: String) : Exception(code)

/**
 * prepareSound: the picked file decoded, resampled to 32 kHz, its silence
 * trimmed (10 ms kept before, 40 ms after), cut to the longest a sound may
 * play, mixed to mono, brought up to full level and written as a 16-bit
 * WAV. Returns the WAV and how long it plays.
 */
object SoundPrep {
    private const val RATE = 32_000
    private const val SILENCE = 0.004f

    fun prepare(context: android.content.Context, uri: Uri, loop: Boolean): Pair<ByteArray, Long> {
        val (channels, rate, interleaved) = decode(context, uri)
        if (interleaved.isEmpty() || channels <= 0) throw SoundProblem("unreadable")
        val frames = interleaved.size / channels
        // resampled to 32 kHz, each channel kept (linear between neighbours)
        val n = maxOf(1, (frames.toLong() * RATE / rate).toInt())
        val data = Array(channels) { ch ->
            FloatArray(n) { i ->
                val pos = i.toDouble() * rate / RATE
                val a = minOf(frames - 1, pos.toInt())
                val b = minOf(frames - 1, a + 1)
                val f = (pos - a).toFloat()
                interleaved[a * channels + ch] * (1 - f) + interleaved[b * channels + ch] * f
            }
        }
        var first = -1
        var last = -1
        var peak = 0f
        for (i in 0 until n) for (ch in 0 until channels) {
            val v = abs(data[ch][i])
            if (v > peak) peak = v
            if (v > SILENCE) {
                if (first < 0) first = i
                last = i
            }
        }
        val pad = (RATE * 0.010).roundToInt()
        val start = if (first < 0) 0.0 else maxOf(0, first - pad).toDouble() / RATE
        val end = if (last < 0) 0.0 else minOf(n, last + 4 * pad).toDouble() / RATE
        if (end <= start || peak < 0.008f) throw SoundProblem("silent")
        val limit = (if (loop) MAX_LOOP_MS else MAX_SOUND_MS) / 1000.0
        val length = minOf(limit, end - start)
        val cut = length < end - start
        val outFrames = ceil(length * RATE).toInt()
        val level = minOf(8f, 0.89f / peak)
        val from = (start * RATE).roundToInt()
        val fadeFrom = ((length - 0.12) * RATE).toInt()
        val pcm = ShortArray(outFrames) { i ->
            val src = from + i
            var v = 0f
            if (src < n) {
                for (ch in 0 until channels) v += data[ch][src]
                v /= channels
            }
            var g = level
            if (cut && i >= fadeFrom) g *= maxOf(0f, 1f - (i - fadeFrom).toFloat() / maxOf(1, outFrames - fadeFrom))
            val s = (v * g).coerceIn(-1f, 1f)
            (if (s < 0) s * 0x8000 else s * 0x7FFF).toInt().toShort()
        }
        return wav(pcm) to (length * 1000).roundToLong()
    }

    /** WAV, PCM 16-bit, mono, 32 kHz, with its 44-byte header. */
    private fun wav(pcm: ShortArray): ByteArray {
        val bytes = pcm.size * 2
        val b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(bytes)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    /** Every sample of the first audio track, interleaved, from -1 to 1. */
    private fun decode(context: android.content.Context, uri: Uri): Triple<Int, Int, FloatArray> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw SoundProblem("unreadable")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val out = ByteArrayOutputStream()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var guard = 0
                while (!outputDone && guard++ < 2_000_000) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(chunk)
                            out.write(chunk)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            // decoded audio far longer than any sound could keep is not worth holding
                            if (out.size() > 64 * 1024 * 1024) outputDone = true
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val raw = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = FloatArray(raw.remaining()) { raw.get(it) / 32768f }
            return Triple(channels, rate, samples)
        } catch (e: SoundProblem) {
            throw e
        } catch (_: Exception) {
            throw SoundProblem("unreadable")
        } finally {
            ex.release()
        }
    }
}

// ---- the server's files ----

/** Seals a sound and keeps it on the server (POST /users/@me/sounds); returns its blob, key and sealed size. */
private suspend fun uploadSound(wav: ByteArray): Triple<String, String, Long> = withContext(Dispatchers.IO) {
    val key = FileCrypto.newFileKey()
    val sealed = FileCrypto.encryptBytes(wav, key)
    val req = Nexa.api.requestBuilder("/users/@me/sounds").post(sealed.toRequestBody("application/octet-stream".toMediaType())).build()
    val res = Nexa.api.execute(Nexa.api.transfer, req, true) as JsonObject
    Triple(res["blob_id"].string() ?: res["blob_id"]!!.toString(), Bytes.b64(key), res["size"].number()?.toLong() ?: sealed.size.toLong())
}

private suspend fun deleteBlob(blob: String) {
    runCatching { Nexa.api.request("DELETE", "/users/@me/sounds/$blob") }
}

/** tidySoundFiles: server files no pack names, more than an hour old (another device may be about to name a fresh one). */
private suspend fun tidySoundFiles(s: SoundSettings) {
    if (!Nexa.settings.loaded.value) return
    val used = s.packs.flatMap { p -> p.files.values.map { it.blob } }.toSet()
    val list = runCatching { Nexa.api.get("/users/@me/sounds") as? JsonObject }.getOrNull() ?: return
    val now = System.currentTimeMillis()
    for (f in (list["sounds"] as? JsonArray).orEmpty()) {
        val o = f as? JsonObject ?: continue
        val blob = o["blob_id"].string() ?: o["blob_id"]?.prim()?.contentOrNull ?: continue
        val at = o["created_at"].number() ?: continue
        if (blob !in used && now - at > 3_600_000) deleteBlob(blob)
    }
}

// ---- the page ----

private val SOUND_ICONS = mapOf(
    "message" to "MessageSquare", "mention" to "AtSign", "friend" to "UserPlus", "ring" to "PhoneIncoming", "outgoing" to "PhoneOutgoing",
    "join" to "LogIn", "leave" to "LogOut", "mute" to "MicOff", "unmute" to "Mic",
    "deafen" to "HeadphoneOff", "undeafen" to "Headphones", "shareStart" to "MonitorUp", "shareStop" to "MonitorOff",
)

private fun cap(id: String) = id.replaceFirstChar { it.uppercase() }
private fun soundName(id: String) = t("settings.sounds.sound${cap(id)}")
private fun packName(p: SoundPack) = p.name.ifEmpty { t("settings.sounds.untitledPack") }

/** 3.10 Sounds: Nexa's sounds or a pack of your own, the volume, and each sound. */
@Composable
fun SoundsPage() {
    val c = Nx.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = settingsDoc()
    val s = remember(doc["sounds"]) { soundSettingsOf(doc["sounds"]) }
    val notifySounds = doc.obj("notifications")?.bool("sounds", true) ?: true
    val custom = s.packs.firstOrNull { it.id == s.pack }
    var working by remember { mutableStateOf<String?>(null) }
    var uploadFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { tidySoundFiles(s) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val id = uploadFor ?: return@rememberLauncherForActivityResult
        val packId = custom?.id ?: return@rememberLauncherForActivityResult
        uploadFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        working = id
        scope.launch {
            try {
                val size = runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } }.getOrNull() ?: -1L
                if (size > MAX_SOURCE_BYTES) throw SoundProblem("too_big")
                val name = runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: ""
                val (wav, ms) = withContext(Dispatchers.Default) { SoundPrep.prepare(ctx, uri, id == "ring" || id == "outgoing") }
                val (blob, key, sealedSize) = uploadSound(wav)
                val now = soundSettingsOf(Nexa.settings.doc.value["sounds"])
                val old = now.packs.firstOrNull { it.id == packId }?.files?.get(id)
                saveSounds(now.copy(packs = now.packs.map { p -> if (p.id == packId) p.copy(files = p.files + (id to SoundFile(blob, key, name.take(120), ms, sealedSize))) else p }))
                // the settings that stop naming the old file are saved before it goes
                Nexa.settings.flush()
                old?.let { deleteBlob(it.blob) }
                SoundPreview.preview(id, packId)
            } catch (e: SoundProblem) {
                Nexa.ui.toast(
                    when (e.code) {
                        "too_big" -> t("settings.sounds.errTooBig")
                        "silent" -> t("settings.sounds.errSilent")
                        else -> t("settings.sounds.errUnreadable")
                    },
                    "error",
                )
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("settings.sounds.errUpload")), "error")
            } finally {
                working = null
            }
        }
    }

    PageTitle(t("settings.sounds.title"))
    if (!notifySounds) NoticeBox(t("settings.sounds.notificationsOff"), c.warning, Modifier.padding(bottom = 6.dp))
    SectionHeading(t("settings.sounds.packHeading"), Modifier.settingAnchor("soundPack"), first = notifySounds)
    Hint(t("settings.sounds.packIntro"), Modifier.padding(bottom = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PackTile(t("settings.sounds.packNexa"), t("settings.sounds.packNexaDesc"), null, "Sparkles", s.pack == NEXA_PACK, { saveSounds(s.copy(pack = NEXA_PACK)) }) {
            SoundPreview.preview("mention", NEXA_PACK)
        }
        for (p in s.packs) {
            val count = p.files.size
            PackTile(
                packName(p), t("settings.sounds.customPackDesc", "base" to t("settings.sounds.packNexa")),
                if (count > 0) t("settings.sounds.replacedCount", "count" to count) else null, "Upload", s.pack == p.id,
                { saveSounds(s.copy(pack = p.id)) },
            ) { SoundPreview.preview("mention", p.id) }
        }
        if (s.packs.size < MAX_PACKS) NewPackTile { promptPackName(t("settings.sounds.createPack"), "", t("settings.sounds.create")) { name -> createPack(name) } }
    }

    SectionHeading(t("settings.sounds.volume"), Modifier.settingAnchor("soundVolume"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NxSlider(s.volume.toFloat(), { v ->
            val next = ((v / 5).roundToInt() * 5).coerceIn(0, 100)
            if (next != s.volume) saveSounds(s.copy(volume = next))
        }, 0f..100f, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onDone = { SoundPreview.preview("message", null) }, label = t("settings.sounds.volume"))
        Txt("${s.volume}%", style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textMuted, modifier = Modifier.width(44.dp))
    }

    if (custom != null) {
        Column(Modifier.padding(top = 28.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(packName(custom), style = Nx.type.heading.copy(fontSize = Nx.type.sp(17f)), color = c.textStrong)
            Hint(t("settings.sounds.customIntro", "base" to t("settings.sounds.packNexa")))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                NxButton(t("settings.sounds.rename"), { promptPackName(t("settings.sounds.renamePack"), custom.name, t("chat.editMessage.save")) { renamePack(custom.id, it) } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                NxButton(t("settings.sounds.deletePack"), { confirmDeletePack(custom) }, kind = ButtonKind.GHOST, size = ButtonSize.SM, icon = "Trash2")
            }
        }
        Hint(t("settings.sounds.uploadHint"), Modifier.padding(bottom = 6.dp))
    } else {
        SectionHeading(t("settings.sounds.soundsHeading"))
        Hint(t("settings.sounds.soundsIntro"), Modifier.padding(bottom = 6.dp))
    }
    for (id in SOUND_NAMES) {
        val file = custom?.files?.get(id)
        val sub = when {
            file != null -> t("settings.sounds.fileLine", "name" to file.name.ifEmpty { t("settings.sounds.yourRecording") }, "seconds" to String.format(java.util.Locale.ROOT, "%.1f", file.ms / 1000.0))
            custom != null -> t("settings.sounds.fromPack", "name" to t("settings.sounds.packNexa"))
            else -> t("settings.sounds.when${cap(id)}")
        }
        SoundRow(
            id, sub, on = id !in s.off,
            onToggle = { on -> saveSounds(s.copy(off = if (on) s.off - id else s.off + id)) },
            custom = custom != null, hasFile = file != null, working = working, uploadDisabled = working != null,
            onUpload = {
                uploadFor = id
                pick.launch(arrayOf("audio/*"))
            },
            onReset = {
                val packId = custom?.id ?: return@SoundRow
                scope.launch {
                    val now = soundSettingsOf(Nexa.settings.doc.value["sounds"])
                    val old = now.packs.firstOrNull { it.id == packId }?.files?.get(id) ?: return@launch
                    saveSounds(now.copy(packs = now.packs.map { p -> if (p.id == packId) p.copy(files = p.files - id) else p }))
                    Nexa.settings.flush()
                    deleteBlob(old.blob)
                }
            },
            packId = s.pack,
        )
    }
    if (custom != null) Hint(t("settings.sounds.limits", "short" to "6.0", "long" to "20.0"), Modifier.padding(top = 10.dp))
}

/** `.packTile`: a pack to choose, with a button to hear it. */
@Composable
private fun PackTile(name: String, desc: String, extra: String?, icon: String, on: Boolean, onPick: () -> Unit, onPlay: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .then(
                    if (on) Modifier.ring(shape, c.brand, 2.dp).background(mixSrgb(c.bgSurface, c.brand, 0.10f), shape)
                    else Modifier.ring(shape, c.borderSubtle).background(c.bgSurface, shape),
                )
                .background(sheen(c), shape)
                .topEdge(shape, c.edgeLight)
                .clip(shape)
                .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton, onClick = onPick)
                .semantics { selected = on }
                .padding(start = 14.dp, end = 52.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).then(if (on) Modifier.background(brandGradient(c), Radii.Sm) else Modifier.sunken(Radii.Sm, c).ring(Radii.Sm, c.borderSubtle)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, if (on) c.onBrand else c.icon, size = 20.dp) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(name, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (on) Box(Modifier.size(18.dp).background(c.positive, CircleShape), contentAlignment = Alignment.Center) { Icon("Check", c.onPositive, size = 12.dp) }
                }
                Txt(desc, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                if (extra != null) Txt(extra, style = Nx.type.hint, color = c.textSubtle)
            }
        }
        IconButton("Play", t("settings.sounds.playPack", "name" to name), onPlay, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), tint = c.text, size = 44.dp, iconSize = 16.dp)
    }
}

@Composable
private fun NewPackTile(onClick: () -> Unit) {
    val c = Nx.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(Radii.Md)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(c.borderStrong, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon("Plus", c.textMuted, size = 18.dp)
        Txt(t("settings.sounds.createPack"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textMuted)
    }
}

/** One sound: hear it, see where it comes from, give it a recording of your own, or switch it off. */
@Composable
private fun SoundRow(
    id: String,
    sub: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    custom: Boolean,
    hasFile: Boolean,
    working: String?,
    uploadDisabled: Boolean,
    onUpload: () -> Unit,
    onReset: () -> Unit,
    packId: String,
) {
    val c = Nx.colors
    val name = soundName(id)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .ring(CircleShape, c.borderSubtle)
                    .clip(CircleShape)
                    .sunken(CircleShape, c)
                    .pressable(remember { MutableInteractionSource() }) { SoundPreview.preview(id, packId) }
                    .semantics { contentDescription = t("settings.sounds.playSound", "name" to name) },
                contentAlignment = Alignment.Center,
            ) { Icon("Play", c.text, size = 15.dp) }
            Row(Modifier.weight(1f).alpha(if (on) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(SOUND_ICONS.getValue(id), c.icon, size = 18.dp)
                Column {
                    Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(550)), color = c.text)
                    Txt(sub, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
                }
            }
            NxToggle(on, onToggle, label = t("settings.sounds.playThisSound", "name" to name))
        }
        if (custom) Row(Modifier.padding(start = 52.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (working == id) Spinner(size = 20.dp)
            else NxButton(if (hasFile) t("settings.sounds.replace") else t("settings.sounds.upload"), onUpload, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Upload", enabled = !uploadDisabled)
            if (hasFile && working != id) IconButton("RotateCcw", t("settings.sounds.useDefault", "name" to name), onReset, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(c.borderSubtle))
    }
}

// ---- packs ----

private fun promptPackName(title: String, initial: String, confirm: String, onDone: (String) -> Unit) {
    val ctl = SheetCtl(title)
    var name by mutableStateOf(initial)
    fun submit() {
        val v = name.replace(Regex("\\s+"), " ").trim().take(PACK_NAME_MAX)
        if (v.isEmpty()) return
        ctl.close()
        onDone(v)
    }
    openSettingsSheet("sound-pack-name", ctl, footer = {
        NxButton(t("chat.editMessage.cancel"), { it.close() }, kind = ButtonKind.GHOST)
        NxButton(confirm, { submit() }, enabled = name.isNotBlank())
    }) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        NxTextField(
            name, { name = it.take(PACK_NAME_MAX) },
            modifier = Modifier.focusRequester(focus),
            label = t("settings.sounds.packNameLabel"), placeholder = t("settings.sounds.packNamePlaceholder"),
            imeAction = ImeAction.Done, onIme = { submit() }, capitalize = true,
        )
    }
}

private fun createPack(name: String) {
    val s = soundSettingsOf(Nexa.settings.doc.value["sounds"])
    if (s.packs.size >= MAX_PACKS) {
        Nexa.ui.toast(t("settings.sounds.tooManyPacks", "max" to MAX_PACKS), "error")
        return
    }
    val p = SoundPack(newPackId(), name, emptyMap())
    saveSounds(s.copy(packs = s.packs + p, pack = p.id))
}

private fun renamePack(id: String, name: String) {
    val s = soundSettingsOf(Nexa.settings.doc.value["sounds"])
    saveSounds(s.copy(packs = s.packs.map { if (it.id == id) it.copy(name = name) else it }))
}

private fun confirmDeletePack(p: SoundPack) {
    fish.nexa.ui.shell.confirm(t("settings.sounds.deletePack"), t("settings.sounds.deletePackBody", "name" to packName(p)), t("settings.sounds.deletePack")) {
        val s = soundSettingsOf(Nexa.settings.doc.value["sounds"])
        val gone = s.packs.firstOrNull { it.id == p.id } ?: return@confirm
        saveSounds(s.copy(packs = s.packs - gone, pack = if (s.pack == gone.id) NEXA_PACK else s.pack))
        // no device may be left naming a file that is gone
        if (!Nexa.settings.flush()) throw ReadableError(t("modals.confirm.somethingWentWrong"))
        for (f in gone.files.values) deleteBlob(f.blob)
    }
}
