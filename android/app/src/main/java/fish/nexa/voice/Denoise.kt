package fish.nexa.voice

import android.os.SystemClock
import android.util.Log
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * The microphone's last stage before the encoder (voice.md 8.2), in the
 * audio processing module's capture post-processing hook: RNNoise when
 * Enhanced noise suppression is on (the platform's own noise suppression is
 * off then), the input volume, the level that voice activity and the speaking
 * ring go by, and the gate voice activity and push to talk work through.
 *
 * The gate is here rather than on the track because libwebrtc stops recording
 * altogether once every sent track is disabled (AudioState::ShouldRecord): a
 * gate on `enabled` would leave voice activity nothing to listen to. So the
 * track stays enabled while the call may hear this device, and a shut gate
 * sends silence, measured first, 10 ms at a time on the audio thread.
 */
object Denoise : ExternalAudioProcessingFactory.AudioProcessing {
    private const val TAG = "Denoise"

    /** RNNoise on (Enhanced). */
    @Volatile var enhanced = false

    /** The input volume, 0..2. */
    @Volatile var gain = 1f

    /** A test signal in place of the microphone (debug builds): 1 steady, 2 in phrases, 3 noise. */
    @Volatile var tone = 0

    /** Whether what the microphone hears goes on to the encoder; silence goes when not. */
    @Volatile var open = true

    /** The level of the last 20 ms of processed microphone, 0..1 (RMS x 4, as the web's LevelMeter). */
    @Volatile var level = 0f
        private set

    /** When the last block went through, to tell a stopped microphone from a quiet one. */
    @Volatile var lastAt = 0L
        private set

    private var rate = 48_000
    private var ptr = 0L
    private var right = 0L

    /** Capture is in two channels now, so StereoInput does all of this before WebRTC gets it. */
    @Volatile var twoChannels = false

    /** Left and right stay as they are (True Stereo), rather than being mixed into one voice. */
    @Volatile var apart = false
    private var previous = 0f
    private var logged = false

    /** Whether RNNoise can run on this device; Enhanced falls back to Standard when not. */
    val available: Boolean
        get() = FrameCryptoNative.loaded && ensure() != 0L

    @Synchronized
    private fun ensure(): Long {
        if (ptr == 0L && FrameCryptoNative.loaded) ptr = runCatching { nativeCreate() }.getOrDefault(0L)
        return ptr
    }

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        rate = sampleRateHz
        ensure()
        Log.i(TAG, "capture processing at $sampleRateHz Hz, $numChannels channel(s)")
    }

    override fun reset(newRate: Int) {
        rate = newRate
    }

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val p = ptr
        if (p == 0L || twoChannels) return
        if (!logged) {
            logged = true
            Log.i(TAG, "first block: $numFrames frames, $numBands band(s), direct=${buffer.isDirect}, ${buffer.capacity()} bytes")
        }
        val l = nativeProcess(p, buffer, numFrames, numFrames * 100, enhanced, gain, if (tone == 4) 0 else tone, open)
        if (l < 0) return
        level = sqrt((l * l + previous * previous) / 2f)
        previous = l
        lastAt = SystemClock.elapsedRealtime()
    }

    /** One block of two-channel capture (StereoInput), which this hook never sees more than the left of. */
    fun processTwoChannels(buffer: ByteBuffer, frames: Int, apart: Boolean) {
        val left = ensure()
        synchronized(this) { if (right == 0L && left != 0L) right = runCatching { nativeCreate() }.getOrDefault(0L) }
        val l = nativeProcessTwoChannels(left, right, buffer, frames, enhanced, gain, apart, open)
        if (l < 0) return
        level = sqrt((l * l + previous * previous) / 2f)
        previous = l
        lastAt = SystemClock.elapsedRealtime()
    }

    private external fun nativeProcessTwoChannels(left: Long, right: Long, buffer: ByteBuffer, frames: Int, denoise: Boolean, gain: Float, apart: Boolean, pass: Boolean): Float
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeProcess(ptr: Long, buffer: ByteBuffer, frames: Int, rate: Int, denoise: Boolean, gain: Float, tone: Int, pass: Boolean): Float
}
