package fish.nexa.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import fish.nexa.Nexa
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only: what the call tests drive through adb.
 *
 *   am broadcast -a fish.nexa.debug.CALL --es channel ID [--ez video true]
 *   am broadcast -a fish.nexa.debug.LEAVE
 *   am broadcast -a fish.nexa.debug.TONE --ei mode 0..4      (the microphone replaced: 1 tone, 2 tone in phrases, 3 noise, 4 a tone on each side)
 *   am broadcast -a fish.nexa.debug.MUTE
 *   am broadcast -a fish.nexa.debug.LISTEN                   (before the first call: each second, what plays on each side at 440 and 1500 Hz, logged)
 *   am broadcast -a fish.nexa.debug.CAMERA
 *   am broadcast -a fish.nexa.debug.WATCH --es user ID --ez on true
 *   am broadcast -a fish.nexa.debug.STATS [--es nonce X]     (logged as NexaCallTest, with the nonce)
 *   am broadcast -a fish.nexa.debug.SEAL                     (the frame crypto against the web's vectors)
 *   am broadcast -a fish.nexa.debug.WRONGKEY --ez on true    (this call's frames sealed and opened with wrong keys)
 *   am broadcast -a fish.nexa.debug.RTP                      (the SFU connection's media stats, logged)
 *   am broadcast -a fish.nexa.debug.SHARE --es res 1080p --ei fps 30   (the phone asks, then the screen is shared)
 *   am broadcast -a fish.nexa.debug.UNSHARE
 *   am broadcast -a fish.nexa.debug.SOFTAVC                  (before the first call: H.264 from the software encoder, as a phone's hardware would)
 */
class CallTestHooks : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "fish.nexa.debug.CALL" -> intent.getStringExtra("channel")?.let { Calls.join(it, intent.getBooleanExtra("video", false)) }
            "fish.nexa.debug.LEAVE" -> Calls.leave()
            "fish.nexa.debug.TONE" -> {
                val mode = intent.getIntExtra("mode", 0)
                Denoise.tone = mode
                StereoInput.test = mode
            }
            "fish.nexa.debug.MUTE" -> VoiceLocal.toggleMute()
            "fish.nexa.debug.LISTEN" -> WebRtc.testPlayback = org.webrtc.audio.JavaAudioDeviceModule.PlaybackSamplesReadyCallback { s -> heard(s) }
            "fish.nexa.debug.CAMERA" -> Calls.toggleCamera()
            "fish.nexa.debug.SHARE" -> Nexa.scope.launch {
                val permission = ScreenConsent.ask() ?: return@launch
                // a mode ("performance", "quality"), or Custom from res, fps and keep
                val plan = VideoCap.sharePlan(intent.getStringExtra("mode") ?: "custom", intent.getStringExtra("res") ?: "1080p", intent.getIntExtra("fps", 30), null, intent.getStringExtra("keep"))
                Calls.startScreenShare(permission, plan)
            }
            "fish.nexa.debug.UNSHARE" -> Calls.stopScreenShare()
            "fish.nexa.debug.SOFTAVC" -> {
                WebRtc.testEncoders = { egl -> org.webrtc.SoftAvcForTests.factory(egl) }
                Log.i(TAG, "softavc on (for calls made after the app started, before any other)")
            }
            "fish.nexa.debug.WATCH" -> intent.getStringExtra("user")?.let { Calls.setWatching(it, intent.getBooleanExtra("on", true)) }
            "fish.nexa.debug.STATS" -> {
                val nonce = intent.getStringExtra("nonce")
                Calls.scope.launch { Log.i(TAG, stats().put("nonce", nonce).toString()) }
            }
            "fish.nexa.debug.SEAL" -> Log.i(TAG, "seal " + vectors())
            "fish.nexa.debug.RTP" -> Calls.scope.launch { Log.i(TAG, "rtp " + rtp()) }
            "fish.nexa.debug.WRONGKEY" -> Calls.scope.launch { Log.i(TAG, "wrongkey " + wrongKeys(intent.getBooleanExtra("on", true))) }
        }
    }

    private val played = Array(2) { ArrayList<Short>() }

    /** What the speakers are given, each channel on its own. */
    private fun heard(s: org.webrtc.audio.JavaAudioDeviceModule.AudioSamples) {
        val pcm = java.nio.ByteBuffer.wrap(s.data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (s.channelCount != 2) { Log.i(TAG, "playing ${s.channelCount} channel(s)"); return }
        while (pcm.remaining() >= 2) { played[0] += pcm.get(); played[1] += pcm.get() }
        if (played[0].size < s.sampleRate) return
        fun size(b: List<Short>, f: Double): Double {
            val c = 2 * Math.cos(2 * Math.PI * f / s.sampleRate)
            var x = 0.0; var y = 0.0
            for (v in b) { val t = v / 32768.0 + c * x - y; y = x; x = t }
            return Math.sqrt(Math.max(0.0, x * x + y * y - c * x * y)) / b.size * 2
        }
        Log.i(TAG, "playing L440=%.4f L1500=%.4f R440=%.4f R1500=%.4f".format(size(played[0], 440.0), size(played[0], 1500.0), size(played[1], 440.0), size(played[1], 1500.0)))
        played[0].clear(); played[1].clear()
    }

    private suspend fun stats(): JSONObject {
        val st = Voice.state.value
        val s = Engine.current()
        val o = JSONObject()
        o.put("channel", s?.channelId)
        o.put("mine", st.mine?.channelId)
        o.put("connection", st.connection?.name)
        o.put("sfu", s?.transport?.throughSfu)
        o.put("micBlocked", st.micBlocked)
        o.put("muted", VoiceLocal.state.value.muted)
        o.put("deafened", VoiceLocal.state.value.deafened)
        o.put("peers", JSONObject(st.peers.mapValues { it.value.name }))
        o.put("remoteAudio", JSONArray(st.remoteAudio.toList()))
        o.put("remoteVideo", JSONArray(st.remoteVideo.keys.toList()))
        o.put("remoteScreen", JSONArray(st.remoteScreen.keys.toList()))
        o.put("watching", JSONArray(st.watching.toList()))
        o.put("localVideo", st.localVideo != null)
        o.put("localScreen", st.localScreen != null)
        o.put("speaking", JSONArray(Voice.speaking.value.toList()))
        o.put("level", Denoise.level.toDouble())
        o.put("enhanced", Denoise.enhanced)
        o.put("tone", Denoise.tone)
        o.put("interrupted", st.interrupted)
        o.put("vadOpen", s?.vadOpen)
        o.put("noiseFloor", s?.noiseFloor?.toDouble())
        o.put("micEnabled", s?.micTrack?.let { runCatching { it.enabled() }.getOrNull() })
        o.put("micState", s?.micTrack?.let { runCatching { it.state().name }.getOrNull() })
        o.put("quality", st.quality?.let { JSONObject().put("rtt", it.rtt).put("loss", it.loss) })
        o.put("crypto", s?.transport?.cryptoStats()?.let { JSONArray(it.toList()) })
        o.put("route", AudioRoute.current.value?.kind?.name)
        o.put("ringing", JSONArray(Voice.ringing.value.keys.toList()))
        o.put("me", Nexa.users.meId)
        return o
    }

    /** What the SFU connection sends and receives: media sources, outbound and inbound streams. */
    private suspend fun rtp(): String {
        val t = Engine.current()?.transport as? SfuTransport ?: return "no SFU call"
        val f = SfuTransport::class.java.getDeclaredField("pc").apply { isAccessible = true }
        val report = WebRtc.stats(f.get(t) as org.webrtc.PeerConnection) ?: return "no stats"
        val keep = setOf("media-source", "outbound-rtp", "inbound-rtp", "codec")
        val fields = setOf("kind", "audioLevel", "totalAudioEnergy", "totalSamplesDuration", "packetsSent", "bytesSent", "packetsReceived",
            "bytesReceived", "framesEncoded", "framesDecoded", "frameWidth", "frameHeight", "framesPerSecond", "mimeType", "sdpFmtpLine",
            "concealedSamples", "totalSamplesReceived", "targetBitrate", "encoderImplementation", "decoderImplementation", "mid", "qualityLimitationReason",
            "qualityLimitationDurations", "totalEncodeTime", "framesSent", "hugeFramesSent", "keyFramesEncoded", "frames", "width", "height",
            "totalPacketSendDelay", "pliCount", "nackCount", "retransmittedPacketsSent", "powerEfficientEncoder", "scalabilityMode")
        val out = JSONArray()
        val sendersField = SfuTransport::class.java.getDeclaredField("senders").apply { isAccessible = true }
        for ((kind, tr) in sendersField.get(t) as Map<*, *>) {
            val p = runCatching { (tr as org.webrtc.RtpTransceiver).sender.parameters }.getOrNull() ?: continue
            val e = p.encodings.firstOrNull()
            out.put(JSONObject().put("type", "sender").put("kind", kind.toString()).put("degradation", p.degradationPreference?.name)
                .put("maxFramerate", e?.maxFramerate).put("maxBitrate", e?.maxBitrateBps).put("scaleDown", e?.scaleResolutionDownBy))
        }
        for (st in report.statsMap.values) {
            if (st.type !in keep) continue
            val o = JSONObject().put("type", st.type)
            for ((k, v) in st.members) if (k in fields) o.put(k, v.toString())
            out.put(o)
        }
        return out.toString()
    }

    /** Swaps the SFU call's keys for wrong ones with the same indices (or back), so nothing either side seals can be opened. */
    private fun wrongKeys(on: Boolean): String {
        val s = Engine.current() ?: return "no call"
        val t = s.transport as? SfuTransport ?: return "not through the SFU"
        val k = CallKeys.keysFor(s.channelId) ?: return "no keys"
        val f = SfuTransport::class.java.getDeclaredField("crypto").apply { isAccessible = true }
        val keys = if (on) k.keys.map { (i, b) -> i to ByteArray(b.size) { (b[it].toInt() xor 0x5a).toByte() } } else k.keys
        (f.get(t) as FrameKeys).set(VoiceKeys(keys, k.send, k.version))
        return if (on) "wrong keys in" else "right keys back"
    }

    /** voice.md 5.10: the same key, index and iv must seal to the web's bytes, and open the web's. */
    private fun vectors(): String {
        if (!FrameCryptoNative.loaded) return "the native library did not load"
        fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val key = unhex("b12789f29993834f2127e2379c3be51b0351a9ddae44147d3570de350a7cccb9")
        val iv = unhex("a0a1a2a3a4a5a6a701020304")
        val cases = listOf(
            Triple(false, "78112233445566778899", "7816560fedd7eec1b8c3dcd52be211cafd0ccd088c18fed7f020a0a1a2a3a4a5a6a70102030405"),
            Triple(true, "5042009d012a80026801deadbeef0102", "5042009d012a80026801d9d98246838a0127617ebb177e00781ad16ae0ffb006a0a1a2a3a4a5a6a70102030405"),
            Triple(true, "310500aabbccdd", "310500adcff074e5f5692de90bf624c640c6ec3cf8370fa0a1a2a3a4a5a6a70102030405"),
            Triple(false, "78", "78651dfd182da1ad3472a408ee907eacd0a0a1a2a3a4a5a6a70102030405"),
            // H.264 (web/src/voice/h264.test.ts): an SPS, a PPS behind a 3-byte start code, an IDR slice and a P slice
            Triple(
                true,
                "000000016742c00ad902c4ec0440000003004000000503c48992" + "00000168ce3c80" + "0000000165888400a1b2c3d4e5f60718293a4b5c6d7e8f" + "000001419a0210deadbeefcafe",
                "000000016742c00ad902c4ec0440000003004000000503c48992" + "0000000168ce3c80" + "0000000165888407d58e6a566d4037423db470141ffdc4" +
                    "00000001419aadd620d77c434e73" + "e8d7635e59436cecf3bc6bd775f9b66a" + "a0a1a2a3a4a5a6a701020304" + "05",
            ),
        )
        var ok = 0
        for ((video, plain, sealed) in cases) {
            val mine = FrameCryptoNative.nativeSealForTest(key, 261 and 0xff, iv, video, unhex(plain))
            val opened = FrameCryptoNative.nativeOpenForTest(key, video, unhex(sealed))
            // an opened H.264 frame has every start code 4 bytes long, as a receiver's depacketizer writes them
            val reopened = if (plain.startsWith("000000016742")) plain.replace("00000168ce3c80", "0000000168ce3c80").replace("000001419a", "00000001419a") else plain
            if (mine != null && hex(mine) == sealed && opened != null && hex(opened) == reopened) ok++
        }
        val voiceKey = hex(fish.nexa.crypto.Conv.voiceFrameKey(unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), "1234567890123456789"))
        return "$ok/${cases.size} frames match; voiceFrameKey ${if (voiceKey == "b12789f29993834f2127e2379c3be51b0351a9ddae44147d3570de350a7cccb9") "matches" else "DIFFERS $voiceKey"}"
    }

    companion object {
        private const val TAG = "NexaCallTest"
    }
}
