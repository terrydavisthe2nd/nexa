package fish.nexa.voice

import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The microphone as AudioRecord read it, every channel, before anything in
 * WebRTC touches it. For two-channel capture (True Stereo) this is where a call's
 * microphone processing happens (Denoise.processTwoChannels).
 */
object StereoInput : JavaAudioDeviceModule.AudioBufferCallback {
    /** Debug builds, in place of the microphone: 4 is 440 Hz on the left and 1500 Hz on the right. */
    @Volatile var test = 0
    private var at = 0L

    override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
        val two = channelCount == 2
        Denoise.twoChannels = two
        if (test == 4) tones(buffer, channelCount, sampleRate, bytesRead)
        if (two) Denoise.processTwoChannels(buffer, bytesRead / 4, Denoise.apart)
        return captureTimeNs
    }

    private fun tones(buffer: ByteBuffer, channels: Int, rate: Int, bytes: Int) {
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until bytes / (2 * channels)) {
            val t = at++.toDouble() / rate
            val l = (sin(2 * PI * 440 * t) * 13000).toInt()
            val r = (sin(2 * PI * 1500 * t) * 13000).toInt()
            if (channels == 2) {
                pcm.putShort(i * 4, l.toShort())
                pcm.putShort(i * 4 + 2, r.toShort())
            } else pcm.putShort(i * 2, ((l + r) / 2).toShort())
        }
    }
}
