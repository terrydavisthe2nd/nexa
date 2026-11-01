package fish.nexa.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import fish.nexa.Nexa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * The app's one sound player (web/src/lib/sounds.ts, soundPacks.ts; reference
 * 08b §8.3). For each sound: the active custom pack's recording if it has one
 * (sealed on the server, opened with the key in the settings), else Nexa's own
 * recording (web/public/sounds, copied into the assets at build), else the
 * tones the web synthesises; a custom recording that cannot be had plays as
 * Nexa's own. Recordings play at 0.7 x the sounds volume, tones at their gain x
 * the volume; a sound that cannot start within 1.5 s is dropped rather than
 * played late. Gating by Do Not Disturb or the notification settings is the
 * caller's; the off list is honoured here (preview ignores it).
 */
object Sounds {
    private const val TAG = "Sounds"

    /** Recordings are mastered near full scale. */
    private const val FILE_GAIN = 0.7f
    /** A sound that cannot start within this long is dropped, not played late. */
    private const val STALE_MS = 1_500L
    /** A synthesised loop replays this often. */
    private const val SYNTH_LOOP_MS = 1_500L

    enum class Name(val wire: String, val file: String?) {
        MESSAGE("message", "message.m4a"),
        MENTION("mention", "mention.m4a"),
        FRIEND("friend", "friend-request.m4a"),
        RING("ring", "incoming-call.m4a"),
        OUTGOING("outgoing", "outgoing-call.m4a"),
        JOIN("join", null),
        LEAVE("leave", "leave-call.m4a"),
        MUTE("mute", "mute.m4a"),
        UNMUTE("unmute", "unmute.m4a"),
        DEAFEN("deafen", "deafen.m4a"),
        UNDEAFEN("undeafen", "undeafen.m4a"),
        SHARE_START("shareStart", "share-start.m4a"),
        SHARE_STOP("shareStop", "share-stop.m4a");

        /** A ring rings as a phone does; call sounds go where the call's audio goes; the rest are notifications. */
        val usage: Int
            get() = when (this) {
                RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                OUTGOING, JOIN, LEAVE, MUTE, UNMUTE, DEAFEN, UNDEAFEN, SHARE_START, SHARE_STOP -> AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
                else -> AudioAttributes.USAGE_NOTIFICATION
            }

        companion object {
            fun of(id: String): Name? = entries.firstOrNull { it.wire == id }
        }
    }

    // --- settings.sounds (sanitised on read, as sanitizeSounds) ------------------

    private class Config(val pack: String, val volume: Int, val off: Set<String>, val files: Map<String, Map<String, CustomFile>>)
    private data class CustomFile(val blob: String, val key: String, val size: Long)

    private val BLOB = Regex("^\\d{1,20}$")
    private val PACK_ID = Regex("^[A-Za-z0-9_-]{4,40}$")
    private val NAMES = Name.entries.map { it.wire }.toSet()

    private fun config(): Config {
        val s = Nexa.settings.doc.value["sounds"] as? JsonObject ?: return Config("nexa", 100, emptySet(), emptyMap())
        val packs = LinkedHashMap<String, Map<String, CustomFile>>()
        for (raw in (s["packs"] as? JsonArray).orEmpty()) {
            val p = raw as? JsonObject ?: continue
            val id = (p["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (!PACK_ID.matches(id) || id == "nexa" || id in packs) continue
            val files = HashMap<String, CustomFile>()
            val fo = p["files"] as? JsonObject
            if (fo != null) for (n in NAMES) {
                val f = fo[n] as? JsonObject ?: continue
                val blob = (f["blob"] as? JsonPrimitive)?.contentOrNull ?: continue
                val key = (f["key"] as? JsonPrimitive)?.contentOrNull ?: continue
                if (!BLOB.matches(blob) || key.length !in 16..128) continue
                files[n] = CustomFile(blob, key, (f["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: 0L)
            }
            packs[id] = files
            if (packs.size >= 8) break
        }
        val pack = (s["pack"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "nexa" || it in packs } ?: "nexa"
        val volume = ((s["volume"] as? JsonPrimitive)?.doubleOrNull ?: 100.0).roundToInt().coerceIn(0, 100)
        val off = (s["off"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in NAMES }.toSet()
        return Config(pack, volume, off, packs)
    }

    /** Whether a sound is switched off in the settings. */
    fun isOff(id: String): Boolean = id in config().off

    // --- playing -----------------------------------------------------------------

    /** Plays a sound by id ("message", "mention", ...), unless it is switched off. */
    fun play(id: String) {
        val name = Name.of(id) ?: return
        play(name)
    }

    fun play(name: Name) {
        val cfg = config()
        if (name.wire in cfg.off) return
        start(name, cfg, cfg.pack)
    }

    /** Plays a sound to hear it (the settings' play buttons): from the pack named or the one in use, even when switched off. */
    fun preview(id: String, packId: String? = null) {
        val name = Name.of(id) ?: return
        val cfg = config()
        start(name, cfg, packId ?: cfg.pack)
    }

    private fun attributes(name: Name): AudioAttributes = AudioAttributes.Builder()
        .setUsage(name.usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun start(name: Name, cfg: Config, pack: String) {
        val asked = SystemClock.elapsedRealtime()
        Nexa.scope.launch(Dispatchers.IO) {
            try {
                val mp = player(name, cfg, pack, loop = false)
                if (SystemClock.elapsedRealtime() - asked > STALE_MS) {
                    mp?.release()
                    return@launch
                }
                if (mp != null) {
                    mp.setOnCompletionListener { it.release() }
                    mp.start()
                } else synth(name, cfg.volume)
            } catch (e: Throwable) {
                Log.w(TAG, "could not play ${name.wire}", e)
            }
        }
    }

    /**
     * A prepared player for a sound's recording: the pack's own, else Nexa's;
     * null when only the tones are left.
     */
    private suspend fun player(name: Name, cfg: Config, pack: String, loop: Boolean): MediaPlayer? {
        val gain = FILE_GAIN * cfg.volume / 100f
        val custom = cfg.files[pack]?.get(name.wire)
        if (custom != null) {
            val file: File? = runCatching { Media.decrypted(custom.blob, custom.key, custom.size.takeIf { it > 0 }) }
                .onFailure { Log.w(TAG, "the pack's ${name.wire} could not be opened", it) }
                .getOrNull()
            if (file != null) prepare(name, gain, loop) { setDataSource(file.absolutePath) }?.let { return it }
        }
        val asset = name.file ?: return null
        return prepare(name, gain, loop) {
            fish.nexa.data.Ota.setDataSource(this, "sounds/$asset")
        }
    }

    private fun prepare(name: Name, gain: Float, loop: Boolean, source: MediaPlayer.() -> Unit): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attributes(name))
            mp.source()
            mp.isLooping = loop
            mp.setVolume(gain, gain)
            mp.setOnErrorListener { p, _, _ -> runCatching { p.release() }; true }
            mp.prepare()
            mp
        } catch (e: Throwable) {
            Log.w(TAG, "could not prepare ${name.wire}", e)
            runCatching { mp.release() }
            null
        }
    }

    /** Fetches the active pack's custom recordings ahead of time, so the first sound plays at once. */
    fun preload() {
        val cfg = config()
        val files = cfg.files[cfg.pack] ?: return
        Nexa.scope.launch(Dispatchers.IO) {
            for (f in files.values) runCatching { Media.decrypted(f.blob, f.key, f.size.takeIf { it > 0 }) }
        }
    }

    // --- loops (the ring, the outgoing tone) ---------------------------------------

    private class Loop {
        var player: MediaPlayer? = null
        var job: Job? = null
        @Volatile var stopped = false
    }

    private var ringing: Loop? = null
    private var calling: Loop? = null

    /** Someone is calling: rings until stopRinging, unless the ring is switched off. */
    @Synchronized
    fun startRinging() {
        stopRinging()
        if (!isOff(Name.RING.wire)) ringing = loop(Name.RING)
    }

    @Synchronized
    fun stopRinging() {
        ringing?.let { stop(it) }
        ringing = null
    }

    /** Calling someone who has not picked up yet: the outgoing tone until stopCalling, unless it is switched off. */
    @Synchronized
    fun startCalling() {
        stopCalling()
        if (!isOff(Name.OUTGOING.wire)) calling = loop(Name.OUTGOING)
    }

    @Synchronized
    fun stopCalling() {
        calling?.let { stop(it) }
        calling = null
    }

    val isRinging: Boolean get() = ringing != null

    private fun loop(name: Name): Loop {
        val l = Loop()
        val cfg = config()
        l.job = Nexa.scope.launch(Dispatchers.IO) {
            val mp = runCatching { player(name, cfg, cfg.pack, loop = true) }.getOrNull()
            synchronized(this@Sounds) {
                if (l.stopped) {
                    mp?.release()
                    return@launch
                }
                l.player = mp
                mp?.start()
            }
            if (mp != null) return@launch
            // no recording to be had: the tones again every 1.5 s
            while (isActive && !l.stopped) {
                synth(name, cfg.volume)
                delay(SYNTH_LOOP_MS)
            }
        }
        return l
    }

    private fun stop(l: Loop) {
        l.stopped = true
        l.job?.cancel()
        l.player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        l.player = null
    }

    // --- synthesised tones (synth() / tone(), exact parameters) -------------------------

    private class Tone(val freq: Double, val start: Double, val dur: Double, val wave: String = "sine", val gain: Double = 0.18)

    private fun tones(name: Name): List<Tone> = when (name) {
        Name.MESSAGE -> listOf(Tone(880.0, 0.0, 0.09), Tone(1174.7, 0.09, 0.12))
        Name.MENTION -> listOf(Tone(784.0, 0.0, 0.08), Tone(987.8, 0.09, 0.08), Tone(1318.5, 0.18, 0.16))
        Name.FRIEND -> listOf(Tone(587.3, 0.0, 0.10, "triangle", 0.2), Tone(880.0, 0.10, 0.10, "triangle", 0.2), Tone(1174.7, 0.20, 0.22, "triangle", 0.2))
        Name.JOIN -> listOf(Tone(523.3, 0.0, 0.08, "triangle"), Tone(784.0, 0.08, 0.12, "triangle"))
        Name.LEAVE -> listOf(Tone(784.0, 0.0, 0.08, "triangle"), Tone(523.3, 0.08, 0.12, "triangle"))
        Name.MUTE -> listOf(Tone(440.0, 0.0, 0.07, "square", 0.08), Tone(330.0, 0.07, 0.10, "square", 0.08))
        Name.UNMUTE -> listOf(Tone(330.0, 0.0, 0.07, "square", 0.08), Tone(440.0, 0.07, 0.10, "square", 0.08))
        Name.DEAFEN -> listOf(Tone(392.0, 0.0, 0.07, "square", 0.08), Tone(261.6, 0.07, 0.12, "square", 0.08))
        Name.UNDEAFEN -> listOf(Tone(261.6, 0.0, 0.07, "square", 0.08), Tone(392.0, 0.07, 0.12, "square", 0.08))
        Name.SHARE_START -> listOf(Tone(659.3, 0.0, 0.08, "triangle"), Tone(880.0, 0.08, 0.08, "triangle"), Tone(1318.5, 0.16, 0.14, "triangle"))
        Name.SHARE_STOP -> listOf(Tone(1318.5, 0.0, 0.08, "triangle"), Tone(880.0, 0.08, 0.08, "triangle"), Tone(659.3, 0.16, 0.14, "triangle"))
        Name.RING, Name.OUTGOING -> listOf(Tone(659.3, 0.0, 0.25, "sine", 0.15), Tone(880.0, 0.30, 0.25, "sine", 0.15))
    }

    /**
     * Renders a sound's tones and plays them. Each tone: 0 at its start, a
     * linear ramp to max(0.0002, gain x volume) over 10 ms, then an exponential
     * ramp to 0.0001 at start + dur; it stops 20 ms after that.
     */
    private fun synth(name: Name, volume: Int) {
        val rate = 48_000
        val list = tones(name)
        val end = list.maxOf { it.start + it.dur + 0.02 }
        val mix = DoubleArray((end * rate).toInt() + 1)
        for (t in list) {
            val s0 = (t.start * rate).toInt()
            val n = ((t.dur + 0.02) * rate).toInt()
            val peak = maxOf(0.0002, t.gain * volume / 100.0)
            val decay = t.dur - 0.01
            for (i in 0 until n) {
                val at = i.toDouble() / rate
                val env = when {
                    at < 0.01 -> peak * at / 0.01
                    at < t.dur -> peak * exp(ln(0.0001 / peak) * (at - 0.01) / decay)
                    else -> 0.0001
                }
                val phase = 2 * PI * t.freq * at
                val w = when (t.wave) {
                    "triangle" -> 2 / PI * asin(sin(phase))
                    "square" -> sign(sin(phase))
                    else -> sin(phase)
                }
                val k = s0 + i
                if (k < mix.size) mix[k] += w * env
            }
        }
        val samples = ShortArray(mix.size) { (mix[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes(name))
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        Nexa.scope.launch {
            delay((end * 1000).toLong() + 250)
            runCatching { track.release() }
        }
    }
}
