package fish.nexa.voice

import fish.nexa.net.VoiceState
import kotlinx.serialization.json.JsonObject
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/** What a call transport reports (web/src/voice/transport.ts). Always called on the voice thread. */
interface TransportEvents {
    fun onSignal(to: String, payload: JsonObject)
    fun onPeerState(userId: String, state: PeerState)
    fun onRemoteAudio(userId: String, on: Boolean)
    /** `kind` is "video" (a camera) or "screen". */
    fun onRemoteVideo(userId: String, kind: String, track: VideoTrack?)
    fun onSpeaking(userId: String, on: Boolean)
    fun onConnection(state: CallConnection)
    fun onError(code: String, message: String)
    fun onTooLarge(userId: String, kind: String, on: Boolean)
}

/**
 * One call's media: peer to peer (mesh) or through Cloudflare's SFU. Every
 * method runs on the voice thread; the transports hop WebRTC's own callbacks
 * onto it.
 */
interface VoiceTransport {
    val throughSfu: Boolean
    val playback: Playback

    /** The voice to send: one track for the whole call, silenced with `enabled`, never taken away between phrases. */
    fun setLocalAudio(track: AudioTrack?)
    /** Whether this device's voice goes out in two channels. */
    fun setVoiceStereo(on: Boolean)
    fun setLocalVideo(track: VideoTrack?)
    /** `smooth`: the share keeps its frames and is made smaller when the link is short, rather than keeping its size (VideoCap.SharePlan). */
    fun setLocalScreen(track: VideoTrack?, bitrate: Int, fps: Int, smooth: Boolean = fps >= 50)
    fun addPeer(userId: String)
    fun removePeer(userId: String)
    fun peerIds(): List<String>
    fun handleSignal(from: String, payload: JsonObject)
    /** Everyone in the call as the server last described them, this device included. */
    fun setRoster(states: List<VoiceState>)
    /** The server acknowledged this device's voice state. */
    fun joined(state: VoiceState)
    /** The gateway reconnected and the server forgot this call. */
    fun reconnected()
    /** A moderator moved this device's call to another channel. */
    fun moveTo(channelId: String)
    /** The conversation's keys may have arrived: read them now rather than on the next check. */
    fun keysArrived()
    /** The network looks back: whatever is down tries again now. */
    fun nudge()
    /** The SFU session a reconnect can resume; null peer to peer. */
    fun currentSession(): String?
    fun setIceServers(servers: List<PeerConnection.IceServer>)
    fun setWatching(userId: String, on: Boolean)
    /**
     * Whether the call's pictures are on screen anywhere (VideoShown). While
     * they are not, cameras and shares need not come in; voices always do.
     */
    fun setVideoShown(on: Boolean) = Unit
    /** Round trip and loss since the last reading; null with nothing to measure. */
    suspend fun stats(): Quality?
    /** [sealed, opened, dropped, oversize] frames, or null peer to peer. */
    fun cryptoStats(): LongArray?
    fun close()
}

/** What a device publishes and pulls (voice.md 3.4). */
enum class PubKind(val wire: String, val video: Boolean) {
    AUDIO("audio", false),
    VIDEO("video", true),
    SCREEN("screen", true),
    SCREEN_AUDIO("screen_audio", false);

    companion object {
        fun of(wire: String): PubKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** RecentLoss: packet loss since the previous reading, from getStats' running totals. */
class RecentLoss {
    private var prev = mapOf<String, Pair<Long, Long>>()

    /** `streams`: id to (lost, received). */
    fun measure(streams: Map<String, Pair<Long, Long>>): Double {
        var lost = 0L
        var received = 0L
        for ((id, now) in streams) {
            val before = prev[id] ?: continue
            // a stream whose counters went back has been replaced
            if (now.first < before.first || now.second < before.second) continue
            lost += now.first - before.first
            received += now.second - before.second
        }
        prev = streams
        val total = lost + received
        return if (total > 0) lost.toDouble() / total else 0.0
    }
}

internal fun mapState(s: PeerConnection.PeerConnectionState?): PeerState = when (s) {
    PeerConnection.PeerConnectionState.CONNECTING -> PeerState.CONNECTING
    PeerConnection.PeerConnectionState.CONNECTED -> PeerState.CONNECTED
    PeerConnection.PeerConnectionState.DISCONNECTED -> PeerState.DISCONNECTED
    PeerConnection.PeerConnectionState.FAILED -> PeerState.FAILED
    PeerConnection.PeerConnectionState.CLOSED -> PeerState.CLOSED
    else -> PeerState.NEW
}

internal fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is java.math.BigInteger -> v.toDouble()
    else -> null
}
