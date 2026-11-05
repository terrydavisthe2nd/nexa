package fish.nexa.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fish.nexa.Nexa
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxSlider
import fish.nexa.ui.components.Txt
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.sunken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * What only the phone knows about calls: which way the sound goes, and the
 * volume picked for each person. Both stay on this phone, in the call
 * engine's own store, so a call picks them up as they are set here.
 */
object VoiceLocalSettings {
    private fun prefs() = Nexa.app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** The speaker unless the earpiece was chosen (the call engine's own choice, kept as it keeps it). */
    var preferSpeaker: Boolean
        get() = prefs().getBoolean("callSpeaker", true)
        set(v) = prefs().edit().putBoolean("callSpeaker", v).apply()

    /** Which way the camera faces when it is turned on, kept as last chosen (the web keeps its camera's id). */
    var frontCamera: Boolean
        get() = prefs().getBoolean("cameraFront", true)
        set(v) = prefs().edit().putBoolean("cameraFront", v).apply()

    /** Set by the call engine, so a volume changed here reaches a call that is on. */
    var onVolume: ((userId: String, pct: Int) -> Unit)? = null

    private fun key() = Nexa.users.meId?.let { "voiceVolumes:$it" }

    fun volumes(): Map<String, Int> {
        val raw = key()?.let { prefs().getString(it, null) } ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optInt(it, 100).coerceIn(0, 200) }
        }.getOrDefault(emptyMap())
    }

    fun setVolume(userId: String, pct: Int) {
        val k = key() ?: return
        val next = volumes() + (userId to pct.coerceIn(0, 200))
        prefs().edit().putString(k, JSONObject(next).toString()).apply()
        onVolume?.invoke(userId, pct.coerceIn(0, 200))
    }
}

private fun JsonObject.voiceInt(k: String, def: Int) = (this[k] as? JsonPrimitive)?.intOrNull ?: def
private fun JsonObject.voiceBool(k: String, def: Boolean) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: def

/** `{...settings.voice, ...partial}`, as the page writes it, unknown keys kept. */
private fun patchVoice(vararg change: Pair<String, JsonElement>) = patchNested("voice", change.toMap())

/** noiseModePatch: the mode, and the older switch kept in step for clients from before modes. */
private fun noiseModePatch(mode: String) = patchVoice("noiseMode" to JsonPrimitive(mode), "noiseSuppression" to JsonPrimitive(mode != "off"))

/** A place the phone can play a call through. */
private data class Output(val kind: String, val name: String)

private fun outputs(context: Context): List<Output> {
    val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
    val list = ArrayList<Output>()
    list += Output("speaker", t("android.call.speaker"))
    if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } || context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
        list += Output("earpiece", t("android.call.earpiece"))
    }
    for (d in devices) {
        val name = d.productName?.toString().orEmpty()
        when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET ->
                list += Output("wired", t("android.call.headphones"))
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                list += Output("bluetooth", name.ifBlank { t("android.call.bluetooth") })
        }
    }
    return list.distinctBy { it.kind + it.name }
}

/** 3.17 Voice & Video, on a phone: where calls play, the volumes, the mic test, how the mic is sent, and each person's volume. */
@Composable
fun VoicePage() {
    val ctx = LocalContext.current
    val doc = settingsDoc()
    val voice = doc.obj("voice") ?: JsonObject(emptyMap())
    val mode = (voice["mode"] as? JsonPrimitive)?.contentOrNull ?: "vad"
    val channels = (voice["voiceChannels"] as? JsonPrimitive)?.contentOrNull.takeIf { it == "dual" || it == "stereo" } ?: "mono"
    val apart = channels == "stereo"
    val mine = fish.nexa.voice.Voice.state.collectAsState().value.mine
    val inCall = mine != null
    val heldToMono = mine?.monoOnly == true
    // True Stereo has no Standard noise suppression (web voice/channels.ts): what is saved comes back when it ends
    val noise = ((voice["noiseMode"] as? JsonPrimitive)?.contentOrNull ?: "enhanced").let { if (apart && it == "standard") "enhanced" else it }
    val auto = voice.voiceBool("autoSensitivity", true)
    val sensitivity = voice.voiceInt("sensitivity", -50).coerceIn(-60, 0)
    val inputVolume = voice.voiceInt("inputVolume", 100)
    val outputVolume = voice.voiceInt("outputVolume", 100)

    PageTitle(t("settings.voice.voiceVideo"))
    // where the sound goes: the phone's own way of choosing, kept on this phone
    var devicesVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                devicesVersion++
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                devicesVersion++
            }
        }
        am?.registerAudioDeviceCallback(cb, null)
        onDispose { am?.unregisterAudioDeviceCallback(cb) }
    }
    val outs = remember(devicesVersion) { outputs(ctx) }
    var speaker by remember { mutableStateOf(VoiceLocalSettings.preferSpeaker) }
    val headset = outs.firstOrNull { it.kind == "wired" } ?: outs.firstOrNull { it.kind == "bluetooth" }
    val current = headset?.let { it.kind + it.name } ?: if (speaker || outs.none { it.kind == "earpiece" }) "speaker" else "earpiece"
    SelectRow(
        t("settings.voice.outputDevice"), current, outs.map { it.name to (it.kind + it.name) },
        { v ->
            if (v == "speaker" || v == "earpiece") {
                speaker = v == "speaker"
                VoiceLocalSettings.preferSpeaker = speaker
            }
        },
        sub = t("android.settings.voice.outputHint"), anchor = "devices",
    )
    LabeledSlider(t("settings.voice.inputVolumeValue", "percent" to inputVolume), inputVolume, 0..200) { patchVoice("inputVolume" to JsonPrimitive(it)) }
    LabeledSlider(t("settings.voice.outputVolumeValue", "percent" to outputVolume), outputVolume, 0..200) { patchVoice("outputVolume" to JsonPrimitive(it)) }

    MicTest(voice, inputVolume, noise)

    SectionHeading(t("settings.voice.inputMode"), Modifier.settingAnchor("inputMode"))
    RadioCard(t("settings.voice.voiceActivity"), mode == "vad", { patchVoice("mode" to JsonPrimitive("vad")) }, t("settings.voice.transmitWheneverYouTalk"))
    RadioCard(t("settings.voice.pushToTalk"), mode == "ptt", { patchVoice("mode" to JsonPrimitive("ptt")) }, t("settings.voice.transmitOnlyWhileYouHoldTheButton"))
    if (mode == "ptt") Hint(t("settings.voice.onThisDevicePushToTalk"))
    else {
        ToggleRow(t("settings.voice.automaticallyDetermineInputSensitivity"), auto, { patchVoice("autoSensitivity" to JsonPrimitive(it)) }, t("settings.voice.learnsYourBackgroundNoiseAndOnly"))
        if (!auto) SensitivityBar(sensitivity)
    }

    SectionHeading(t("voice.channels.title"), Modifier.settingAnchor("voiceStereo"))
    ToggleRow(t("voice.channels.stereo"), apart, { patchVoice("voiceChannels" to JsonPrimitive(if (it) "stereo" else "mono")) }, t("voice.channels.stereoHint"))
    ToggleRow(t("voice.channels.dual"), channels == "dual", { patchVoice("voiceChannels" to JsonPrimitive(if (it) "dual" else "mono")) }, t("voice.channels.dualHint"))
    ToggleRow(t("voice.channels.hearMono"), voice.voiceBool("hearMono", false), { patchVoice("hearMono" to JsonPrimitive(it)) }, t("voice.channels.hearMonoHint"), divider = false)
    if (apart) Hint(t("voice.channels.stereoNote"))
    if (inCall && apart != fish.nexa.voice.WebRtc.stereoInput) Hint(t("android.voice.channelsNextCall"))

    SectionHeading(t("voice.noise.title"), Modifier.settingAnchor("noiseSuppression"))
    RadioCard(t("voice.noise.enhanced"), noise == "enhanced", { noiseModePatch("enhanced") }, t("voice.noise.enhancedHint"))
    RadioCard(t("voice.noise.standard"), noise == "standard", { noiseModePatch("standard") }, t("voice.noise.standardHint"), enabled = !apart)
    RadioCard(t("voice.noise.off"), noise == "off", { noiseModePatch("off") }, t("voice.noise.offHint"))

    SectionHeading(t("settings.voice.voiceProcessing"), Modifier.settingAnchor("voiceProcessing"))
    ToggleRow(t("settings.voice.echoCancellation"), !apart && voice.voiceBool("echoCancellation", true), { patchVoice("echoCancellation" to JsonPrimitive(it)) }, enabled = !apart)
    ToggleRow(t("settings.voice.automaticGainControl"), !apart && voice.voiceBool("autoGain", true), { patchVoice("autoGain" to JsonPrimitive(it)) }, enabled = !apart, divider = false)

    PeopleVolumes()
}

@Composable
private fun LabeledSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Txt(label, style = Nx.type.label, color = Nx.colors.textSubtle)
        NxSlider(value.toFloat(), { v ->
            val next = v.toInt().coerceIn(range.first, range.last)
            if (next != value) onChange(next)
        }, range.first.toFloat()..range.last.toFloat(), modifier = Modifier.heightIn(min = 48.dp), label = label)
    }
}

/** Levels the mic test and the sensitivity bar share: the raw mic, and what a call would send. */
private object MicLevels {
    var raw by mutableFloatStateOf(0f)
    var sent by mutableFloatStateOf(0f)
    var running by mutableStateOf(false)
}

/** Mic Test: the microphone opened as a call opens it, with both meters, until stopped. */
@Composable
private fun MicTest(voice: JsonObject, inputVolume: Int, noise: String) {
    val c = Nx.colors
    val ctx = LocalContext.current
    var want by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) want = true else error = t("android.call.micBlocked")
    }
    val echo = voice.voiceBool("echoCancellation", true)
    val gain = voice.voiceBool("autoGain", true)
    LaunchedEffect(want, noise, echo, gain) {
        if (!want) return@LaunchedEffect
        error = null
        MicLevels.running = true
        try {
            withContext(Dispatchers.IO) { runMic(echo, gain, noise != "off", inputVolume / 100f) { diag = it } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            error = t("settings.voice.theMicrophoneCouldNotBeOpened2")
            want = false
        } finally {
            MicLevels.running = false
            MicLevels.raw = 0f
            MicLevels.sent = 0f
        }
    }
    DisposableEffect(Unit) { onDispose { MicLevels.running = false } }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t("settings.voice.micTest"), style = Nx.type.label, color = c.textSubtle)
        Hint(t("settings.voice.speakAndWatchBothMetersThe"))
        NxButton(
            if (want) t("settings.voice.stopTest") else t("settings.voice.letSCheck"),
            {
                if (want) want = false
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) want = true
                else ask.launch(Manifest.permission.RECORD_AUDIO)
            },
            kind = if (want) ButtonKind.DANGER else ButtonKind.SECONDARY, size = ButtonSize.SM,
        )
        Meter(MicLevels.raw, t("settings.voice.inputLevel"))
        if (want) {
            Meter(MicLevels.sent, t("settings.voice.levelOthersWouldHear"))
            Txt(t("settings.voice.topYourMicrophoneBottomWhatThe"), style = Nx.type.hint, color = c.textMuted)
        }
        error?.let { NoticeBox(it, c.danger) }
        if (want) diag?.let { MonoBox(it, size = 12f) }
    }
}

/** An 8 dp bar, filled green to the level. */
@Composable
private fun Meter(level: Float, label: String) {
    val c = Nx.colors
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .sunken(shape, c)
            .semantics {
                contentDescription = label
                stateDescription = "${(level * 100).toInt()}%"
            },
    ) {
        Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(8.dp).background(c.positive, shape))
    }
}

/**
 * The microphone as a call opens it (voice communication, with the phone's
 * echo canceller, noise suppressor and gain control as chosen), read in 20 ms
 * steps until the test is stopped: RMS × 4, as LevelMeter measures it.
 */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun runMic(echo: Boolean, gain: Boolean, suppress: Boolean, inputGain: Float, onDiag: (String) -> Unit) {
    val rate = 48_000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4800 * 2))
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        throw IllegalStateException("no microphone")
    }
    val session = rec.audioSessionId
    val aec = if (echo && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.apply { enabled = true } else null
    val agc = if (gain && AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.apply { enabled = true } else null
    val ns = if (suppress && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.apply { enabled = true } else null
    try {
        rec.startRecording()
        val device = rec.routedDevice?.productName?.toString()?.ifBlank { null } ?: "unnamed"
        onDiag(
            "device: $device\n" +
                "format: ${rec.sampleRate} Hz, ${rec.channelCount} channel(s)\n" +
                "processing: echo=${aec != null} noise=${ns != null} gain=${agc != null}\n" +
                "input volume: ${(inputGain * 100).toInt()}%",
        )
        val buf = ShortArray(960)
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var sum = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / n).toFloat()
            MicLevels.raw = minOf(1f, rms * 4)
            MicLevels.sent = minOf(1f, rms * inputGain * 4)
        }
    } finally {
        runCatching { rec.stop() }
        rec.release()
        aec?.release()
        agc?.release()
        ns?.release()
    }
}

/** The manual threshold over a live level: green while the voice would be sent. */
@Composable
private fun SensitivityBar(db: Int) {
    val c = Nx.colors
    // clamp(10^(db/20) × 4, 0.005, 1), the level the gate opens at
    val threshold = Math.min(1.0, Math.max(0.005, Math.pow(10.0, db / 20.0) * 4)).toFloat()
    val level = if (MicLevels.running) MicLevels.sent else 0f
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t("settings.voice.inputSensitivityValue", "db" to db), style = Nx.type.label, color = c.textSubtle)
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
            val shape = RoundedCornerShape(50)
            Box(Modifier.fillMaxWidth().fillMaxHeight().sunken(shape, c))
            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).fillMaxHeight().background(if (level > threshold) c.positive else c.warning, shape))
            val pos = ((db + 60) / 60f).coerceIn(0f, 1f)
            Box(Modifier.offset(x = maxWidth * pos - 1.dp).width(2.dp).fillMaxHeight().background(c.textStrong))
        }
        NxSlider((db + 60).toFloat(), { v ->
            val next = (v.toInt() - 60).coerceIn(-60, 0)
            if (next != db) patchVoice("sensitivity" to JsonPrimitive(next))
        }, 0f..60f, modifier = Modifier.heightIn(min = 48.dp), label = t("settings.voice.inputSensitivityValue", "db" to db))
        Hint(t("settings.voice.sensitivityHint"))
        if (!MicLevels.running) Txt(t("android.settings.voice.levelHint"), style = Nx.type.hint, color = c.textMuted)
    }
}

/** The volume picked for each person in calls, kept on this phone. */
@Composable
private fun PeopleVolumes() {
    val c = Nx.colors
    val users by Nexa.users.byId.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val volumes = remember(version) { VoiceLocalSettings.volumes().filterValues { it != 100 } }
    SectionHeading(t("android.settings.voice.peopleHeading"), Modifier.settingAnchor("peopleVolumes"))
    Hint(t("android.settings.voice.peopleHint"), Modifier.padding(bottom = 10.dp))
    if (volumes.isEmpty()) {
        EmptyBox(t("android.settings.voice.peopleEmpty"))
        return
    }
    for ((id, pct) in volumes.entries.sortedBy { users[it.key]?.name ?: it.key }) {
        val u = users[id]
        val name = u?.name ?: t("names.unknownUser")
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(id, name, u?.avatarId, 28.dp)
                Txt(name, style = Nx.type.body.copy(fontWeight = FontWeight(550)), color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                Txt("$pct%", style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textMuted)
                NxButton(t("common.imageCrop.reset"), {
                    VoiceLocalSettings.setVolume(id, 100)
                    version++
                }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
            }
            NxSlider(pct.toFloat(), { v ->
                val next = v.toInt().coerceIn(0, 200)
                if (next != pct) {
                    VoiceLocalSettings.setVolume(id, next)
                    version++
                }
            }, 0f..200f, modifier = Modifier.heightIn(min = 44.dp), label = t("android.settings.voice.volumeOf", "name" to name))
        }
    }
}
