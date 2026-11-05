package fish.nexa.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import fish.nexa.net.IceServer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.ExternalAudioProcessingFactory
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The WebRTC stack for this process: a factory, one EGL context, an audio
 * device module with the phone's echo canceller, and the capture hook RNNoise
 * runs in. Made on the first call, never before.
 */
object WebRtc {
    private const val TAG = "WebRtc"
    private lateinit var app: Context

    /** Told when the microphone could not be opened or stopped working. */
    @Volatile var onMicError: ((String) -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    val eglBase: EglBase by lazy { EglBase.create() }

    /**
     * Debug builds only: encoders to use in place of the phone's own, set
     * before the first call (CallTestHooks SOFTAVC), so an emulator, which
     * has no hardware encoder, can share its screen in H.264 as a phone does.
     */
    @Volatile var testPlayback: JavaAudioDeviceModule.PlaybackSamplesReadyCallback? = null

    @Volatile var testEncoders: ((EglBase.Context) -> org.webrtc.VideoEncoderFactory)? = null

    private val initialized by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
    }
    private val factories = HashMap<Boolean, PeerConnectionFactory>()

    /**
     * Whether calls capture two channels. An audio device module opens the
     * microphone one way for its whole life, and phones are safest with the mono
     * voice path, so True Stereo has a factory of its own, chosen as a call starts
     * (Engine.join) and kept: closing one under a connection still shutting down
     * is not safe, and one not in a call holds no microphone.
     */
    @Volatile var stereoInput = false
        private set

    /** Only between calls. */
    fun useStereoInput(on: Boolean) {
        stereoInput = on
    }

    val factory: PeerConnectionFactory
        @Synchronized get() = factories.getOrPut(stereoInput) { build(stereoInput) }

    private fun build(stereo: Boolean): PeerConnectionFactory {
        initialized
        val adm = JavaAudioDeviceModule.builder(app)
            // Use WebRTC AEC3 with its playback reference, rather than a vendor effect
            // whose quality and interaction with capture processing vary by phone.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            // voices and a share's sound can arrive in stereo
            .setUseStereoOutput(true)
            .setUseStereoInput(stereo)
            .setAudioBufferCallback(StereoInput)
            .apply { testPlayback?.let { setPlaybackSamplesReadyCallback(it) } }
            .apply { if (stereo) setAudioSource(MediaRecorder.AudioSource.MIC) }
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) = micError(errorMessage)
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) = micError(errorMessage)
                override fun onWebRtcAudioRecordError(errorMessage: String?) = micError(errorMessage)
            })
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setAudioProcessingFactory(ExternalAudioProcessingFactory().also { it.setCapturePostProcessing(Denoise) })
            .setVideoEncoderFactory(testEncoders?.invoke(eglBase.eglBaseContext) ?: DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun micError(message: String?) {
        Log.w(TAG, "microphone: $message")
        onMicError?.invoke(message.orEmpty())
    }

    /** The ICE servers chatd hands out (each `urls` a string or a list), as WebRTC takes them. */
    fun iceServers(list: List<IceServer>): List<PeerConnection.IceServer> = list.mapNotNull { s ->
        val urls = when (val u = s.urls) {
            is JsonArray -> u.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(u.contentOrNull)
            else -> emptyList()
        }.filter { it.isNotBlank() }
        if (urls.isEmpty()) null
        else PeerConnection.IceServer.builder(urls).setUsername(s.username.orEmpty()).setPassword(s.credential.orEmpty()).createIceServer()
    }

    // --- descriptions, as suspending calls ---------------------------------

    class SdpError(message: String) : Exception(message)

    private abstract class Observer : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    suspend fun createOffer(pc: PeerConnection, iceRestart: Boolean = false): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply { if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        pc.createOffer(object : Observer() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (cont.isActive) if (sdp != null) cont.resume(sdp) else cont.resumeWithException(SdpError("no offer"))
            }
            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpError("offer: $error"))
            }
        }, constraints)
    }

    suspend fun createAnswer(pc: PeerConnection): SessionDescription = suspendCancellableCoroutine { cont ->
        pc.createAnswer(object : Observer() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (cont.isActive) if (sdp != null) cont.resume(sdp) else cont.resumeWithException(SdpError("no answer"))
            }
            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpError("answer: $error"))
            }
        }, MediaConstraints())
    }

    suspend fun setLocal(pc: PeerConnection, sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        pc.setLocalDescription(object : Observer() {
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpError("local: $error"))
            }
        }, sdp)
    }

    /** setLocalDescription() with nothing: an offer or an answer, whichever the state calls for. */
    suspend fun setLocalImplicit(pc: PeerConnection): Unit = suspendCancellableCoroutine { cont ->
        pc.setLocalDescription(object : Observer() {
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpError("local: $error"))
            }
        })
    }

    suspend fun setRemote(pc: PeerConnection, sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        pc.setRemoteDescription(object : Observer() {
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpError("remote: $error"))
            }
        }, sdp)
    }

    /** Takes back an offer nobody answered, so the next exchange starts clean. */
    suspend fun rollback(pc: PeerConnection) {
        if (runCatching { pc.signalingState() }.getOrNull() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        runCatching { setLocal(pc, SessionDescription(SessionDescription.Type.ROLLBACK, "")) }
            .onFailure { Log.w(TAG, "could not take the offer back", it) }
    }

    /** A stats report, or null when the connection does not answer within 2 s. */
    suspend fun stats(pc: PeerConnection): RTCStatsReport? = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine { cont ->
            try {
                pc.getStats { report -> if (cont.isActive) cont.resume(report) }
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    /** One sender's stats report, or null when the connection does not answer within 2 s. */
    suspend fun stats(pc: PeerConnection, sender: RtpSender): RTCStatsReport? = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine { cont ->
            try {
                pc.getStats(sender) { report -> if (cont.isActive) cont.resume(report) }
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }
}
