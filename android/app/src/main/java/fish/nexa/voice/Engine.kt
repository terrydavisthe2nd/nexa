package fish.nexa.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import fish.nexa.Nexa
import fish.nexa.net.VoiceState
import fish.nexa.ui.t
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What one camera or screen capture owns, let go of in the one order libwebrtc takes safely. */
internal class Capture(val capturer: VideoCapturer, val source: VideoSource, val track: VideoTrack, val helper: SurfaceTextureHelper) {
    fun release() {
        runCatching { capturer.stopCapture() }
        runCatching { capturer.dispose() }
        runCatching { track.dispose() }
        runCatching { source.dispose() }
        runCatching { helper.dispose() }
    }
}

/**
 * The call itself (web/src/voice/engine.ts): the microphone, camera and
 * screen this device sends, the transport (SFU or mesh), when the voice is
 * let through, and what is measured while the call lasts. Everything here
 * runs on the voice thread.
 */
internal object Engine {
    private const val TAG = "VoiceEngine"
    private const val VAD_HOLD_MS = 400L
    private const val VAD_EVERY_MS = 20L
    private const val LEVEL_EVERY = 4
    private const val QUALITY_EVERY_MS = 3_000L
    private const val GUEST_RELAY_MS = 1_000L
    private const val ICE_REFRESH_MS = 10 * 60 * 1000L

    class Session(var channelId: String, val transport: VoiceTransport) {
        var micSource: AudioSource? = null
        var micTrack: AudioTrack? = null
        var micSerial = 0
        var camera: Capture? = null
        var cameraFront = fish.nexa.ui.settings.VoiceLocalSettings.frontCamera
        var screen: Capture? = null
        var cameraPending = false
        var screenPending = false
        var micRetrying = false
        var noiseFloor = 0.01f
        var pttHeld = false
        var vadOpen = false
        var vadHoldUntil = 0L
        val speaking = SpeakingGate()
        var announced = false
        var noiseNoticed = false
        var monoNoticed = false
        val jobs = ArrayList<Job>()
        val relayedTo = HashMap<String, String>()
    }

    var session: Session? = null
        private set

    private val scope get() = Calls.scope
    private fun me(): String? = Nexa.users.meId

    // --- transmit -------------------------------------------------------------

    /** The microphone may be on at all: not muted, not server-muted, and in push to talk the button held. */
    private fun shouldTransmit(): Boolean {
        val local = VoiceLocal.state.value
        val st = Voice.state.value
        // a moderator's deafen takes the voice away too, as it does the sound
        if (local.muted || local.deafened || st.mine?.serverMute == true || st.mine?.serverDeaf == true || st.interrupted) return false
        if (CallPerms.pushToTalkOn()) return session?.pttHeld == true
        return true
    }

    /** Voice activity lets the voice through: it heard speech, or push to talk is doing the job. */
    private fun gateOpen(): Boolean {
        val s = session ?: return true
        if (s.pttHeld) return true
        if (CallPerms.pushToTalkOn()) return true
        return s.vadOpen
    }

    /**
     * The sent track is never taken away: a track taken away between phrases
     * froze its timestamps and made every listener's jitter buffer grow
     * (voice.md 6.6). Muted, deafened, server-muted or interrupted, the track
     * is disabled, and libwebrtc then stops the microphone itself (the
     * system's indicator goes out). Between phrases, and with push to talk
     * let go, the capture hook sends silence instead, so voice activity keeps
     * listening; where the hook cannot run, `enabled` does both jobs.
     */
    fun applyTransmit() {
        val s = session ?: return
        val transmit = shouldTransmit()
        val gate = gateOpen()
        if (Denoise.available) {
            Denoise.open = gate
            s.micTrack?.let { runCatching { it.setEnabled(transmit) } }
        } else {
            Denoise.open = true
            s.micTrack?.let { runCatching { it.setEnabled(transmit && gate) } }
        }
    }

    fun setPttHeld(on: Boolean) {
        val s = session ?: return
        if (s.pttHeld == on) return
        s.pttHeld = on
        Voice.update { it.copy(pttHeld = on) }
        applyTransmit()
    }

    // --- the microphone ------------------------------------------------------

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(Nexa.app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun chosenChannels() = Voice.setting("voiceChannels").takeIf { it == "dual" || it == "stereo" } ?: "mono"

    /** How the voice goes out in this channel: what was chosen, less what the channel denies. */
    private fun voiceChannels(channelId: String) = CallPerms.allowedChannels(chosenChannels(), channelId)

    /** Says once a call that a channel holds this voice to mono, as nothing else would. */
    private fun noteMonoOnly(s: Session) {
        if (s.monoNoticed || chosenChannels() == "mono" || voiceChannels(s.channelId) != "mono") return
        s.monoNoticed = true
        Nexa.ui.toast(t("voice.channels.notAllowed"))
    }

    fun setHearMono(on: Boolean) {
        session?.transport?.setHearMono(on)
    }

    /**
     * Whether the encoder gets two channels. Capture in two channels stays that way
     * until the call ends (WebRtc.stereoInput), and a one-channel encoder fed from it
     * came out garbled, so it goes on as Dual Mono rather than Mono until then.
     */
    private fun sendsTwoChannels(channelId: String) = voiceChannels(channelId) != "mono" || WebRtc.stereoInput

    /** Whether this call keeps the microphone's channels apart: chosen, and captured that way since it began. */
    private fun trueStereo(channelId: String) = voiceChannels(channelId) == "stereo" && WebRtc.stereoInput

    /**
     * How noise is taken out: RNNoise here (Enhanced), the platform's own
     * (Standard), or not at all. Enhanced that cannot run here uses Standard
     * and says so once.
     */
    private fun noisePlan(s: Session): Pair<Boolean, Boolean> {
        // True Stereo has only RNNoise: the platform's own would make the microphone one channel
        if (trueStereo(s.channelId) && (Voice.setting("noiseMode") ?: "enhanced") != "off") {
            if (Denoise.available) return true to false
            if (!s.noiseNoticed) {
                s.noiseNoticed = true
                Nexa.ui.toast(t("voice.channels.noNoise"))
            }
            return false to false
        }
        return when (Voice.setting("noiseMode") ?: "enhanced") {
            "off" -> false to false
            "standard" -> false to true
            else -> if (Denoise.available) true to false else {
                if (!s.noiseNoticed) {
                    s.noiseNoticed = true
                    Nexa.ui.toast(t("voice.noise.fallback"))
                }
                false to true
            }
        }
    }

    /** Opens the microphone with the processing the settings ask for. */
    private fun openMic(s: Session): Pair<AudioSource, AudioTrack> {
        val (rnnoise, platformNoise) = noisePlan(s)
        Denoise.enhanced = rnnoise
        Denoise.gain = Voice.settingInt("inputVolume", 100).coerceIn(0, 200) / 100f
        // each of these would make the microphone one channel, as the browser's do (web voice/channels.ts)
        val apart = trueStereo(s.channelId)
        Denoise.apart = apart
        val echo = !apart && Voice.settingBool("echoCancellation", true)
        val agc = !apart && Voice.settingBool("autoGain", true)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", echo.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", agc.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", platformNoise.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", (!apart).toString()))
        }
        val source = WebRtc.factory.createAudioSource(constraints)
        val track = WebRtc.factory.createAudioTrack("mic-${++s.micSerial}", source)
        runCatching { track.setAudioProcessingOptions(org.webrtc.audio.AudioProcessingOptions(echo, platformNoise, agc, !apart)) }
        return source to track
    }

    private fun closeMic(s: Session) {
        val track = s.micTrack
        val source = s.micSource
        s.micTrack = null
        s.micSource = null
        track?.let { runCatching { it.setEnabled(false) }; runCatching { it.dispose() } }
        source?.let { runCatching { it.dispose() } }
    }

    /** Opens the microphone again and puts it in the call in place of whatever was there. */
    private fun swapMic(s: Session, failure: String?) {
        if (!hasMicPermission()) {
            if (failure != null) Nexa.ui.toast(t("session.microphoneAccessWasDeniedYouCan"), "error")
            Voice.update { it.copy(micBlocked = true) }
            return
        }
        try {
            val old = s.micTrack to s.micSource
            val (source, track) = openMic(s)
            s.micSource = source
            s.micTrack = track
            s.transport.setVoiceStereo(sendsTwoChannels(s.channelId))
            noteMonoOnly(s)
            s.transport.setLocalAudio(track)
            applyTransmit()
            old.first?.let { runCatching { it.dispose() } }
            old.second?.let { runCatching { it.dispose() } }
            Voice.update { it.copy(micBlocked = false) }
        } catch (e: Throwable) {
            Log.w(TAG, "could not open the microphone", e)
            if (failure != null) Nexa.ui.toast(failure, "error")
            if (s.micTrack == null) Voice.update { it.copy(micBlocked = true) }
        }
    }

    fun refreshMic() {
        val s = session ?: return
        swapMic(s, t("session.couldNotSwitchTheMicrophone"))
    }

    /** "Try again" on a microphone that was refused: asks for it, then opens it. */
    fun retryMic() {
        val s = session ?: return
        if (s.micRetrying) return
        s.micRetrying = true
        scope.launch {
            try {
                val granted = Permissions.request(arrayOf(Manifest.permission.RECORD_AUDIO))[Manifest.permission.RECORD_AUDIO] == true
                if (session !== s) return@launch
                if (!granted) {
                    Nexa.ui.toast(t("session.microphoneAccessWasDeniedYouCan"), "error")
                    return@launch
                }
                swapMic(s, t("session.microphoneAccessWasDeniedYouCan"))
            } finally {
                s.micRetrying = false
            }
        }
    }

    fun applyInputGain() {
        Denoise.gain = Voice.settingInt("inputVolume", 100).coerceIn(0, 200) / 100f
    }

    // --- joining and leaving -----------------------------------------------------

    /**
     * Joins once Calls has checked it may and knows how calls connect.
     * `wanted` says whether this join is still the one asked for. True once
     * the server has been told.
     */
    suspend fun join(channelId: String, ice: IceConfig, wanted: () -> Boolean, refuse: (String) -> Unit): Boolean {
        val myId = me() ?: return false
        WebRtc.useStereoInput(voiceChannels(channelId) == "stereo")
        if (ice.sfu) {
            if (!FrameCryptoNative.loaded) {
                refuse(t("session.thisBrowserCannotMakeEncryptedCalls"))
                return false
            }
            // the call's frames are sealed with the conversation's key, which has to be here first
            val key = runCatching { Nexa.keys.ensureConvKey(Nexa.channels.convFor(channelId)) }.getOrNull()
            if (!wanted()) return false
            if (key == null) {
                refuse(t("guestLink.thisConversationSKeysHaveNot"))
                return false
            }
        }
        lateinit var s: Session
        val events = object : TransportEvents {
            override fun onSignal(to: String, payload: JsonObject) {
                Nexa.session.send("voice_signal", buildJsonObject {
                    put("to", to)
                    put("payload", payload)
                })
            }
            override fun onPeerState(userId: String, state: PeerState) = Voice.update { it.copy(peers = it.peers + (userId to state)) }
            override fun onRemoteAudio(userId: String, on: Boolean) = Voice.update { it.copy(remoteAudio = if (on) it.remoteAudio + userId else it.remoteAudio - userId) }
            override fun onRemoteVideo(userId: String, kind: String, track: VideoTrack?) = Voice.update { st ->
                if (kind == "screen") st.copy(remoteScreen = if (track != null) st.remoteScreen + (userId to track) else st.remoteScreen - userId, tooLarge = st.tooLarge - "$userId:screen")
                else st.copy(remoteVideo = if (track != null) st.remoteVideo + (userId to track) else st.remoteVideo - userId, tooLarge = st.tooLarge - "$userId:video")
            }
            override fun onSpeaking(userId: String, on: Boolean) = Voice.setSpeaking(userId, on)
            override fun onConnection(state: CallConnection) {
                if (session !== s) return
                Voice.update { it.copy(connection = state, quality = if (state != CallConnection.CONNECTED) null else it.quality) }
                CallService.update()
            }
            override fun onError(code: String, message: String) {
                Nexa.ui.toast(message, "error")
                // near the free allowance video pauses: what was being sent is turned off
                if (code == "video_paused" && session === s) {
                    if (s.camera != null) toggleCamera()
                    if (s.screen != null) stopScreen()
                    for (uid in Voice.state.value.watching) setWatching(uid, false)
                }
            }
            override fun onTooLarge(userId: String, kind: String, on: Boolean) {
                if (session !== s) return
                val key = "$userId:$kind"
                Voice.update { it.copy(tooLarge = if (on) it.tooLarge + key else it.tooLarge - key) }
            }
        }
        val servers = WebRtc.iceServers(ice.iceServers)
        val transport: VoiceTransport = if (ice.sfu) SfuTransport(myId, channelId, servers, events, scope) else MeshTransport(myId, servers, events, scope)
        s = Session(channelId, transport)
        session = s
        Voice.update { it.copy(connection = CallConnection.CONNECTING) }
        AudioRoute.onFocus = { has -> scope.launch { onFocus(has) } }
        AudioRoute.enter()
        WebRtc.onMicError = { msg -> scope.launch { if (session === s) onMicFailed(s, msg) } }

        // A microphone that fails does not stop the join: the call goes on without sending audio until "Try again".
        val granted = hasMicPermission() || Permissions.request(arrayOf(Manifest.permission.RECORD_AUDIO))[Manifest.permission.RECORD_AUDIO] == true
        if (session !== s || !wanted()) {
            if (session === s) leave(false)
            return false
        }
        if (granted) {
            try {
                val (source, track) = openMic(s)
                s.micSource = source
                s.micTrack = track
                Voice.update { it.copy(micBlocked = false) }
            } catch (e: Throwable) {
                Log.w(TAG, "the microphone did not open", e)
                Nexa.ui.toast(t("session.microphoneAccessWasDeniedYouCan"), "error")
                Voice.update { it.copy(micBlocked = true) }
            }
        } else {
            Nexa.ui.toast(t("session.microphoneAccessWasDeniedYouCan"), "error")
            Voice.update { it.copy(micBlocked = true) }
        }
        transport.setVoiceStereo(sendsTwoChannels(channelId))
        transport.setHearMono(Voice.settingBool("hearMono", false))
        noteMonoOnly(s)
        s.micTrack?.let { transport.setLocalAudio(it) }
        transport.playback.setVolumes(Voice.mix.value)
        for (uid in Voice.state.value.watching) transport.setWatching(uid, true)
        transport.playback.setOutputMuted(outputSilenced())
        transport.playback.setMasterVolume(Voice.settingInt("outputVolume", 100))
        applyTransmit()
        s.announced = true
        Calls.sendState(channelId)
        startTimers(s)
        fish.nexa.media.Sounds.play(fish.nexa.media.Sounds.Name.JOIN)
        CallService.update()
        return true
    }

    private fun onMicFailed(s: Session, message: String) {
        Log.w(TAG, "microphone failed: $message")
        if (Voice.state.value.micBlocked) return
        Voice.update { it.copy(micBlocked = true) }
        Nexa.ui.toast(t("session.microphoneAccessWasDeniedYouCan"), "error")
    }

    /** A phone call took the audio: the call is quiet both ways until it ends. */
    private fun onFocus(has: Boolean) {
        val s = session ?: return
        Voice.update { it.copy(interrupted = !has) }
        s.transport.playback.setInterrupted(!has)
        applyTransmit()
    }

    private fun startTimers(s: Session) {
        val myId = me().orEmpty()
        // voice activity, the level meters and this device's own speaking ring, every 20 ms
        s.jobs += scope.launch {
            var tick = 0
            while (isActive && session === s) {
                delay(VAD_EVERY_MS)
                val transmit = shouldTransmit()
                // a muted microphone reads silence, as the web's meter does
                val level = if (transmit && s.micTrack != null) Denoise.level else 0f
                if (tick++ % LEVEL_EVERY == 0) Voice.level.value = level
                val threshold = if (Voice.settingBool("autoSensitivity", true)) {
                    s.noiseFloor = if (level < s.noiseFloor) level else s.noiseFloor * 0.995f + level * 0.005f
                    max(0.02f, s.noiseFloor * 2.5f)
                } else sensitivityToThreshold(Voice.settingInt("sensitivity", -50))
                val now = SystemClock.elapsedRealtime()
                if (level > threshold) s.vadHoldUntil = now + VAD_HOLD_MS
                // a stopped capture reads silence, which must not shut the mic
                val stalled = s.micTrack != null && now - Denoise.lastAt > 1_000
                val open = stalled || now < s.vadHoldUntil
                if (open != s.vadOpen) {
                    s.vadOpen = open
                    applyTransmit()
                }
                val sending = transmit && gateOpen()
                val loud = s.speaking.update(sending && level > SpeakingGate.AUDIBLE_LEVEL, System.currentTimeMillis())
                Voice.setSpeaking(myId, sending && loud)
            }
        }
        // cameras and shares come in only while the call's pictures are on screen somewhere
        s.jobs += scope.launch {
            kotlinx.coroutines.flow.combine(VideoShown.open, Nexa.ui.visible, Pip.active, Voice.state) { open, inFront, floating, _ ->
                // the call's own channel, read each time: a moderator can move it
                VideoShown.shown(open, s.channelId, inFront, floating)
            }.distinctUntilChanged().collect { shown -> if (session === s) s.transport.setVideoShown(shown) }
        }
        // the link's quality for every view that shows it
        s.jobs += scope.launch {
            while (isActive && session === s) {
                if (Voice.state.value.connection != CallConnection.CONNECTED) Voice.update { it.copy(quality = null) }
                else {
                    val q = runCatching { s.transport.stats() }.getOrNull()
                    if (session === s) Voice.update { it.copy(quality = q?.takeIf { x -> x.rtt > 0 && it.connection == CallConnection.CONNECTED }) }
                }
                delay(QUALITY_EVERY_MS)
            }
        }
        // newer call keys for the guests in an SFU call
        if (s.transport.throughSfu) s.jobs += scope.launch {
            while (isActive && session === s) {
                delay(GUEST_RELAY_MS)
                relayGuestKeys(s)
            }
        }
        // relay credentials age: the next restart or rebuild uses fresh ones
        s.jobs += scope.launch {
            while (isActive && session === s) {
                delay(ICE_REFRESH_MS)
                val ice = Calls.iceConfig() ?: continue
                if (session === s) s.transport.setIceServers(WebRtc.iceServers(ice.iceServers))
            }
        }
    }

    /** Sensitivity in dBFS (-100..0) to a 0..1 threshold comparable with the level. */
    private fun sensitivityToThreshold(db: Int): Float = min(1.0, max(0.005, 10.0.pow(db / 20.0) * 4)).toFloat()

    /**
     * Hands the guests in this call the call keys newer than their links
     * carried, sealed so only a holder of the link can open them. Everyone in
     * the call does it; a guest keeps the first copy.
     */
    private fun relayGuestKeys(s: Session) {
        if (session !== s || !s.transport.throughSfu) return
        val myId = me() ?: return
        val conv = Nexa.channels.convFor(s.channelId)
        val epochs = Nexa.keys.ring.epochs(conv)
        for (p in Voice.participants(s.channelId)) {
            val guest = p.guest ?: continue
            if (p.userId == myId) continue
            val made = "${p.joinedAt}:${guest.epoch}:${epochs.joinToString(",")}"
            if (s.relayedTo[p.userId] == made) continue
            s.relayedTo[p.userId] = made
            val payload = runCatching { CallKeys.sealForGuest(s.channelId, p.userId, guest.epoch, epochs) { Nexa.keys.ring.get(conv, it) } }.getOrNull()
            if (payload == null) continue
            Nexa.session.send("voice_signal", buildJsonObject {
                put("to", p.userId)
                put("payload", buildJsonObject { put("guest_keys", payload) })
            })
        }
    }

    /** Ends this device's call; tells the server unless `notify` is off or it was never told. */
    fun leave(notify: Boolean) {
        val s = session ?: return
        session = null
        for (j in s.jobs) j.cancel()
        AudioRoute.onFocus = null
        WebRtc.onMicError = null
        // the screen lets go of every picture before the connections that own them are disposed
        Voice.update { it.copy(remoteVideo = emptyMap(), remoteScreen = emptyMap(), localVideo = null, localScreen = null) }
        runCatching { s.transport.close() }
        closeMic(s)
        s.camera?.release()
        s.screen?.release()
        s.camera = null
        s.screen = null
        Denoise.tone = 0
        StereoInput.test = 0
        Denoise.open = true
        AudioRoute.leave()
        if (notify && s.announced) Calls.sendState(null)
        fish.nexa.media.Sounds.play(fish.nexa.media.Sounds.Name.LEAVE)
    }

    fun current(): Session? = session

    // --- camera ---------------------------------------------------------------

    private fun cameraFailed(s: Session) {
        if (session !== s || s.camera == null) return
        s.camera?.release()
        s.camera = null
        s.transport.setLocalVideo(null)
        Voice.update { it.copy(localVideo = null) }
        Calls.sendState(s.channelId)
        CallService.update()
        Nexa.ui.toast(t("session.couldNotSwitchTheCamera"), "error")
    }

    private fun openCamera(s: Session, front: Boolean): Capture? {
        val enumerator = Camera2Enumerator(Nexa.app)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) } ?: return null
        val events = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(error: String?) { scope.launch { Log.w(TAG, "camera error: $error"); cameraFailed(s) } }
            override fun onCameraDisconnected() { scope.launch { cameraFailed(s) } }
            override fun onCameraFreezed(error: String?) = Unit
            override fun onCameraOpening(cameraName: String?) = Unit
            override fun onFirstFrameAvailable() = Unit
            override fun onCameraClosed() = Unit
        }
        val capturer = enumerator.createCapturer(name, events) ?: return null
        return startCapture(capturer, VideoCap.CAMERA_WIDTH, VideoCap.CAMERA_HEIGHT, VideoCap.CAMERA_FPS, "camera")
    }

    /** Starts a capturer and wraps it in a track; what fails half way lets go of what it made. */
    private fun startCapture(capturer: VideoCapturer, width: Int, height: Int, fps: Int, label: String): Capture {
        val source = WebRtc.factory.createVideoSource(capturer.isScreencast)
        var helper: SurfaceTextureHelper? = null
        var track: VideoTrack? = null
        try {
            helper = SurfaceTextureHelper.create("$label-capture", WebRtc.eglBase.eglBaseContext)
            capturer.initialize(helper, Nexa.app, source.capturerObserver)
            capturer.startCapture(width, height, fps)
            track = WebRtc.factory.createVideoTrack("$label-${System.nanoTime()}", source)
            return Capture(capturer, source, track, helper)
        } catch (e: Throwable) {
            runCatching { capturer.stopCapture() }
            runCatching { capturer.dispose() }
            track?.let { runCatching { it.dispose() } }
            runCatching { source.dispose() }
            helper?.let { runCatching { it.dispose() } }
            throw e
        }
    }

    fun toggleCamera() {
        val s = session ?: return
        if (s.cameraPending) return
        val cam = s.camera
        if (cam != null) {
            s.camera = null
            s.transport.setLocalVideo(null)
            Voice.update { it.copy(localVideo = null) }
            cam.release()
            Calls.sendState(s.channelId)
            CallService.update()
            return
        }
        if (!CallPerms.mayStream(s.channelId)) return Nexa.ui.toast(t("voice.callPerms.noVideo"), "error")
        s.cameraPending = true
        scope.launch {
            try {
                val granted = Permissions.request(arrayOf(Manifest.permission.CAMERA))[Manifest.permission.CAMERA] == true
                if (session !== s) return@launch
                if (!granted) {
                    Nexa.ui.toast(t("session.cameraAccessWasDenied"), "error")
                    return@launch
                }
                // a camera-typed foreground service first: Android cuts a background app off from the camera otherwise
                CallService.update(camera = true)
                val capture = runCatching { openCamera(s, s.cameraFront) ?: openCamera(s, !s.cameraFront) }.getOrNull()
                if (session !== s) {
                    capture?.release()
                    return@launch
                }
                if (capture == null) {
                    Nexa.ui.toast(t("session.cameraAccessWasDenied"), "error")
                    CallService.update()
                    return@launch
                }
                s.camera = capture
                s.transport.setLocalVideo(capture.track)
                Voice.update { it.copy(localVideo = capture.track, localFront = s.cameraFront) }
                Calls.sendState(s.channelId)
                CallService.update()
            } finally {
                s.cameraPending = false
            }
        }
    }

    /**
     * Picks which way the camera faces: turned round live when it is on (the
     * other side sees the picture change without renegotiating), and kept for
     * the next time it is turned on either way.
     */
    fun setCameraFront(front: Boolean) {
        val s = session
        if (s?.camera != null) {
            if (s.cameraFront != front) flipCamera()
            return
        }
        fish.nexa.ui.settings.VoiceLocalSettings.frontCamera = front
        if (s != null) {
            s.cameraFront = front
            Voice.update { it.copy(localFront = front) }
        }
    }

    private fun flipCamera() {
        val s = session ?: return
        val cam = s.camera ?: return
        val capturer = cam.capturer as? CameraVideoCapturer ?: return
        if (s.cameraPending) return
        val enumerator = Camera2Enumerator(Nexa.app)
        val target = enumerator.deviceNames.firstOrNull { if (s.cameraFront) enumerator.isBackFacing(it) else enumerator.isFrontFacing(it) }
        if (target == null) {
            Nexa.ui.toast(t("session.thisDeviceHasNoOtherCamera"), "error")
            return
        }
        s.cameraPending = true
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                scope.launch {
                    s.cameraPending = false
                    s.cameraFront = isFrontCamera
                    fish.nexa.ui.settings.VoiceLocalSettings.frontCamera = isFrontCamera
                    if (session === s) Voice.update { it.copy(localFront = isFrontCamera) }
                }
            }

            override fun onCameraSwitchError(error: String?) {
                scope.launch {
                    s.cameraPending = false
                    Nexa.ui.toast(t("session.couldNotSwitchTheCamera"), "error")
                }
            }
        }, target)
    }

    fun cameraCount(): Int = runCatching { Camera2Enumerator(Nexa.app).deviceNames.size }.getOrDefault(0)

    // --- screen ------------------------------------------------------------------

    /** The size a share is captured at: the screen kept in shape inside the box, the box turned to the screen's orientation. */
    private fun shareSize(res: String): Pair<Int, Int> {
        val wm = Nexa.app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (sw, sh) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        val (bw, bh) = VideoCap.shareBox(res)
        val (boxW, boxH) = if (sh > sw) min(bw, bh) to max(bw, bh) else max(bw, bh) to min(bw, bh)
        val scale = min(1.0, min(boxW.toDouble() / sw, boxH.toDouble() / sh)).let { it / VideoCap.scaleFor((sw * it).roundToInt(), (sh * it).roundToInt()) }
        // even sizes, as encoders want them
        return ((sw * scale).roundToInt() / 2 * 2).coerceAtLeast(2) to ((sh * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    }

    /** Shares the screen once the system has said yes (`permission` is its answer). */
    fun startScreen(permission: Intent, plan: VideoCap.SharePlan) {
        val res = plan.res
        val fps = plan.fps
        val s = session ?: return
        if (s.screenPending || s.screen != null) return
        if (!CallPerms.mayStream(s.channelId)) return Nexa.ui.toast(t("voice.callPerms.noVideo"), "error")
        s.screenPending = true
        scope.launch {
            try {
                // Android 14 refuses a projection to an app without a running mediaProjection-typed service
                if (!CallService.awaitForeground(screen = true)) {
                    Nexa.ui.toast(t("android.call.shareFailed"), "error")
                    return@launch
                }
                if (session !== s) return@launch
                val capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
                    override fun onStop() {
                        scope.launch { if (session === s && s.screen != null) stopScreen() }
                    }
                })
                val (w, h) = shareSize(res)
                val capture = try {
                    startCapture(capturer, w, h, fps, "screen")
                } catch (e: Throwable) {
                    Log.w(TAG, "the screen would not be captured", e)
                    Nexa.ui.toast(t("android.call.shareFailed"), "error")
                    CallService.update()
                    return@launch
                }
                if (session !== s) {
                    capture.release()
                    return@launch
                }
                // the capturer leaves the frame rate to the screen (60, 90 or 120 a second): the share keeps to the one asked for
                capture.source.adaptOutputFormat(w, h, fps)
                s.screen = capture
                s.transport.setLocalScreen(capture.track, plan.bitrate, fps, plan.smooth)
                Voice.update { it.copy(localScreen = capture.track) }
                fish.nexa.media.Sounds.play(fish.nexa.media.Sounds.Name.SHARE_START)
                Calls.sendState(s.channelId)
                CallService.update()
            } finally {
                s.screenPending = false
            }
        }
    }

    fun stopScreen() {
        val s = session ?: return
        val cap = s.screen ?: return
        s.screen = null
        s.transport.setLocalScreen(null, 0, 0)
        Voice.update { it.copy(localScreen = null, focused = if (it.focused == "${me()}:screen") null else it.focused) }
        cap.release()
        fish.nexa.media.Sounds.play(fish.nexa.media.Sounds.Name.SHARE_STOP)
        Calls.sendState(s.channelId)
        CallService.update()
    }

    fun sharing(): Boolean = session?.screen != null

    // --- output ---------------------------------------------------------------

    fun setWatching(userId: String, on: Boolean) {
        Voice.setWatching(userId, on)
        session?.transport?.setWatching(userId, on)
    }

    fun applyMix() {
        session?.transport?.playback?.setVolumes(Voice.mix.value)
    }

    fun setOutputMuted(on: Boolean) {
        session?.transport?.playback?.setOutputMuted(on)
    }

    /** Whether nothing of the call may be heard here: deafened by this person, or by a moderator. */
    fun outputSilenced(): Boolean = VoiceLocal.state.value.deafened || Voice.state.value.mine?.serverDeaf == true

    /** Whoever a moderator muted or deafened stays silent here (a channel that denies Speak is the same). */
    private fun syncModMutes(s: Session, everyone: List<VoiceState>) {
        val myId = me()
        for (p in everyone) {
            if (p.userId == myId) continue
            s.transport.playback.setModMuted(p.userId, p.serverMute || p.serverDeaf)
            s.transport.playback.setPriority(p.userId, p.prioritySpeaker)
        }
    }

    fun setMasterVolume(pct: Int) {
        session?.transport?.playback?.setMasterVolume(pct)
    }

    fun nudge() {
        session?.transport?.nudge()
    }

    fun throughSfu(): Boolean = session?.transport?.throughSfu == true

    // --- gateway events ----------------------------------------------------------

    private fun syncPeers() {
        val s = session ?: return
        val myId = me() ?: return
        val everyone = Voice.participants(s.channelId)
        val wanted = everyone.map { it.userId }.filter { it != myId }.toSet()
        for (id in s.transport.peerIds()) if (id !in wanted) {
            s.transport.removePeer(id)
            Voice.removePeer(id)
        }
        syncModMutes(s, everyone)
    }

    /**
     * The server acknowledged this device in its call, maybe in another channel
     * (a moderator moved it). False when the state is an echo of a call already
     * left.
     */
    fun onMine(vs: VoiceState): Boolean {
        val s = session ?: return false
        val ch = vs.channelId ?: return false
        if (s.channelId != ch && Voice.state.value.mine?.channelId != s.channelId) return false
        Voice.update { it.copy(mine = vs, joining = null) }
        if (s.channelId != ch) {
            // moved by a moderator: the new room's peers, keys and session
            for (id in s.transport.peerIds()) {
                s.transport.removePeer(id)
                Voice.removePeer(id)
            }
            s.channelId = ch
            s.transport.moveTo(ch)
            scope.launch {
                runCatching { Nexa.keys.ensureConvKey(Nexa.channels.convFor(ch)) }
                if (session === s) s.transport.keysArrived()
            }
        }
        val everyone = Voice.participants(ch)
        for (p in everyone) if (p.userId != me()) s.transport.addPeer(p.userId)
        s.transport.setRoster(everyone)
        s.transport.joined(vs)
        syncModMutes(s, everyone)
        s.transport.playback.setOutputMuted(outputSilenced())
        applyTransmit()
        relayGuestKeys(s)
        return true
    }

    /** Someone else's state changed: "joined" when they came into this call, "left" when they went. */
    fun onOther(vs: VoiceState): String? {
        val s = session ?: return null
        var what: String? = null
        val wasHere = vs.userId in s.transport.peerIds()
        if (vs.channelId == s.channelId) {
            if (!wasHere) {
                s.transport.addPeer(vs.userId)
                what = "joined"
            }
        } else if (wasHere || vs.oldChannelId == s.channelId) {
            s.transport.removePeer(vs.userId)
            Voice.removePeer(vs.userId)
            what = "left"
        }
        syncPeers()
        // cameras, shares and what people publish change without anyone joining or leaving
        s.transport.setRoster(Voice.participants(s.channelId))
        relayGuestKeys(s)
        return what
    }

    fun onSignal(from: String, channelId: String?, payload: JsonObject) {
        val s = session ?: return
        // guest keys are for guests; an account has its own
        if (payload.containsKey("guest_keys")) return
        if (channelId == s.channelId) s.transport.handleSignal(from, payload)
    }

    /** The server let a call go while the gateway was away: it starts over as after any reconnect. */
    fun onResumed(channelId: String?, kept: Boolean) {
        val s = session ?: return
        if (kept || s.channelId != channelId) return
        for (id in s.transport.peerIds()) {
            s.transport.removePeer(id)
            Voice.removePeer(id)
        }
        Voice.update { it.copy(mine = null) }
        s.transport.reconnected()
    }

    /** The gateway reconnected: ask for the call back as it is (SFU), or rejoin. */
    fun onReady() {
        val s = session ?: return
        if (!s.announced) return
        val held = s.transport.currentSession()
        if (held != null) {
            syncPeers()
            val everyone = Voice.participants(s.channelId)
            for (p in everyone) if (p.userId != me()) s.transport.addPeer(p.userId)
            s.transport.setRoster(everyone)
            Calls.sendState(s.channelId, resume = true, sfuSession = held)
            s.transport.nudge()
            return
        }
        for (id in s.transport.peerIds()) {
            s.transport.removePeer(id)
            Voice.removePeer(id)
        }
        Voice.update { it.copy(mine = null) }
        s.transport.reconnected()
        Calls.sendState(s.channelId, resume = true)
    }
}
