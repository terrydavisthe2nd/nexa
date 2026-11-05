package fish.nexa.voice

import android.util.Log
import fish.nexa.net.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Peer-to-peer calls for servers without Cloudflare Realtime
 * (web/src/voice/mesh.ts): one connection per other person, perfect
 * negotiation with the lower user id polite, descriptions and candidates
 * relayed through the gateway. Media is DTLS-SRTP straight between devices;
 * there is no frame encryption on this path.
 *
 * The connection's transceivers are looked up (getTransceivers) only to act on
 * one at once and never kept: the Java wrapper disposes every transceiver it
 * handed out the next time they are listed.
 */
class MeshTransport(
    private val meId: String,
    private var ice: List<PeerConnection.IceServer>,
    private val events: TransportEvents,
    private val scope: CoroutineScope,
) : VoiceTransport {
    override val throughSfu = false
    override val playback = Playback(scope, events::onSpeaking)

    private inner class Peer(val userId: String, val polite: Boolean, val attempts: Int) {
        lateinit var pc: PeerConnection
        var makingOffer = false
        var ignoreOffer = false
        var remoteStreams = mapOf<String, String>()
        val pendingCandidates = ArrayList<IceCandidate>()
        var audioSender: RtpSender? = null
        var videoSender: RtpSender? = null
        val screenSenders = ArrayList<RtpSender>()
        /** How this link's share is sized to what it carries (ShareAdapt); made again with the share's senders. */
        var shareAdapter: ShareAdapter? = null
        var restartJob: Job? = null
        var restarts = 0
        var offerJob: Job? = null
        var renegotiate = false
        var watchdog: Job? = null
        var ops: Job? = null
        var closed = false
        /** What arrived of them, by kind, with the stream it came in. */
        val tracks = HashMap<String, MediaStreamTrack>()
    }

    private val peers = LinkedHashMap<String, Peer>()
    private var audio: AudioTrack? = null
    @Volatile private var voiceStereo = false
    private var video: VideoTrack? = null
    private var screen: VideoTrack? = null
    private val audioStreamId = UUID.randomUUID().toString()
    private val videoStreamId = UUID.randomUUID().toString()
    private var screenStreamId = UUID.randomUUID().toString()
    private var screenBitrate = 6_000_000
    private var screenFps = 30
    private var screenSmooth = false
    private val early = HashMap<String, MutableList<JsonObject>>()
    private val upBefore = HashSet<String>()
    private var lastConnection: CallConnection? = null
    private var closed = false
    private val recentLoss = RecentLoss()

    private fun lower(a: String, b: String): Boolean = if (a.length != b.length) a.length < b.length else a < b

    // --- local media -------------------------------------------------------

    /** An encoder takes its channels from the description, so a change goes through another exchange with each peer. */
    override fun setVoiceStereo(on: Boolean) {
        if (on == voiceStereo) return
        voiceStereo = on
        for (p in peers.values) enqueue(p) { negotiate(p) }
    }

    override fun setLocalAudio(track: AudioTrack?) {
        audio = track
        for (p in peers.values) syncSenders(p)
    }

    override fun setLocalVideo(track: VideoTrack?) {
        video = track
        for (p in peers.values) syncSenders(p)
    }

    override fun setLocalScreen(track: VideoTrack?, bitrate: Int, fps: Int, smooth: Boolean) {
        if (track != null && screen == null) screenStreamId = UUID.randomUUID().toString()
        screen = track
        screenBitrate = VideoCap.clampShareBitrate(bitrate)
        if (fps > 0) screenFps = fps
        screenSmooth = smooth
        for (p in peers.values) syncSenders(p)
    }

    // --- peers -------------------------------------------------------------

    override fun addPeer(userId: String) = addPeer(userId, 0)

    private fun addPeer(userId: String, attempts: Int) {
        if (closed || peers.containsKey(userId) || userId == meId) return
        val peer = Peer(userId, polite = lower(meId, userId), attempts = attempts)
        val config = PeerConnection.RTCConfiguration(ice).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableImplicitRollback = true
        }
        peer.pc = WebRtc.factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) scope.launch {
                    if (!peer.closed) events.onSignal(userId, buildJsonObject { put("candidate", JsonNull) })
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                val c = candidate ?: return
                scope.launch { if (!peer.closed) events.onSignal(userId, buildJsonObject { put("candidate", candidateJson(c)) }) }
            }

            override fun onRenegotiationNeeded() {
                scope.launch { if (!peer.closed) enqueue(peer) { negotiate(peer) } }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                scope.launch { if (!peer.closed) onPeerConnection(peer, state) }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val tr = transceiver ?: return
                // VP8 both ways, asked for while the description carrying it is applied
                if (tr.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) runCatching { VideoCap.preferVp8(tr, sending = false) }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                val streamId = streams?.firstOrNull()?.id
                scope.launch { if (!peer.closed) onRemoteTrack(peer, track, streamId) }
            }

            override fun onRemoveTrack(receiver: RtpReceiver?) {
                val id = runCatching { receiver?.track()?.id() }.getOrNull() ?: return
                scope.launch { if (!peer.closed) dropRemote(peer, id) }
            }
        }) ?: return
        peers[userId] = peer
        syncSenders(peer)
        events.onPeerState(userId, mapState(runCatching { peer.pc.connectionState() }.getOrNull()))
        reportConnection()
        peer.watchdog = scope.launch {
            delay(CONNECT_DEADLINE_MS)
            rebuild(userId, peer)
        }
        drainEarly(userId)
    }

    private fun candidateJson(c: IceCandidate): JsonObject = buildJsonObject {
        put("candidate", c.sdp)
        put("sdpMid", c.sdpMid)
        put("sdpMLineIndex", c.sdpMLineIndex)
        Regex(" ufrag (\\S+)").find(c.sdp)?.groupValues?.get(1)?.let { put("usernameFragment", it) }
    }

    private fun onPeerConnection(peer: Peer, state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                upBefore += peer.userId
                peer.watchdog?.cancel()
                peer.restartJob?.cancel()
                peer.watchdog = null
                peer.restartJob = null
                peer.restarts = 0
            }
            PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> {
                // a phone moving between networks sits in "disconnected" a long time before "failed"
                if (peer.watchdog == null) peer.watchdog = scope.launch {
                    delay(DROP_DEADLINE_MS)
                    rebuild(peer.userId, peer)
                }
                if (peer.restartJob == null) scheduleRestart(peer, if (state == PeerConnection.PeerConnectionState.FAILED) 1_500 else ICE_RESTART_MS)
            }
            else -> Unit
        }
        events.onPeerState(peer.userId, mapState(state))
        reportConnection()
    }

    /** A peer that has not connected in time is thrown away and made again, at most a few times. */
    private fun rebuild(userId: String, peer: Peer) {
        if (closed || peers[userId] !== peer) return
        peer.watchdog = null
        if (runCatching { peer.pc.connectionState() }.getOrNull() == PeerConnection.PeerConnectionState.CONNECTED) return
        if (peer.attempts + 1 >= MAX_REBUILDS) {
            Log.w(TAG, "giving up on $userId after ${peer.attempts + 1} attempts")
            return
        }
        val wasUp = userId in upBefore
        removePeer(userId)
        if (wasUp) upBefore += userId
        addPeer(userId, peer.attempts + 1)
    }

    /** ICE restarts on a dropped connection, from the impolite side so both ends do not offer at once. */
    private fun scheduleRestart(peer: Peer, wait: Long) {
        if (peer.polite) return
        peer.restartJob = scope.launch {
            delay(wait)
            peer.restartJob = null
            if (closed || peers[peer.userId] !== peer) return@launch
            val state = runCatching { peer.pc.connectionState() }.getOrNull()
            if ((state != PeerConnection.PeerConnectionState.DISCONNECTED && state != PeerConnection.PeerConnectionState.FAILED) || peer.restarts >= MAX_ICE_RESTARTS) return@launch
            peer.restarts++
            runCatching { peer.pc.restartIce() }
            scheduleRestart(peer, ICE_RESTART_MS)
        }
    }

    private fun drainEarly(userId: String) {
        val waiting = early.remove(userId) ?: return
        for (payload in waiting) handleSignal(userId, payload)
    }

    override fun removePeer(userId: String) {
        val p = peers.remove(userId) ?: return
        p.closed = true
        playback.detachVoice(userId)
        playback.detachScreenAudio(userId)
        p.restartJob?.cancel()
        p.offerJob?.cancel()
        p.watchdog?.cancel()
        p.tracks.clear()
        events.onRemoteAudio(userId, false)
        events.onRemoteVideo(userId, "video", null)
        events.onRemoteVideo(userId, "screen", null)
        runCatching { p.pc.dispose() }
        early.remove(userId)
        upBefore.remove(userId)
        reportConnection()
    }

    /**
     * Up while any peer is (or with nobody to reach); reconnecting only when
     * every link this device had went down together.
     */
    private fun reportConnection() {
        if (closed) return
        val list = peers.values.toList()
        val hadUp = list.count { it.userId in upBefore }
        val state = when {
            list.isEmpty() || list.any { runCatching { it.pc.connectionState() }.getOrNull() == PeerConnection.PeerConnectionState.CONNECTED } -> CallConnection.CONNECTED
            hadUp >= 2 -> CallConnection.RECONNECTING
            hadUp == 1 -> CallConnection.CONNECTED
            else -> CallConnection.CONNECTING
        }
        if (state == lastConnection) return
        lastConnection = state
        events.onConnection(state)
    }

    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        ice = servers
        for (p in peers.values) runCatching {
            p.pc.setConfiguration(PeerConnection.RTCConfiguration(servers).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                enableImplicitRollback = true
            })
        }
    }

    override fun peerIds(): List<String> = peers.keys.toList()
    override fun setRoster(states: List<VoiceState>) = Unit
    override fun joined(state: VoiceState) = reportConnection()
    override fun reconnected() = Unit
    override fun moveTo(channelId: String) = Unit
    override fun keysArrived() = Unit
    override fun nudge() = Unit
    override fun currentSession(): String? = null
    override fun cryptoStats(): LongArray? = null

    override fun close() {
        closed = true
        early.clear()
        for (id in peers.keys.toList()) removePeer(id)
        playback.close()
    }

    // --- signalling --------------------------------------------------------

    /** One description-touching step at a time per peer. */
    private fun enqueue(peer: Peer, step: suspend () -> Unit) {
        val before = peer.ops
        peer.ops = scope.launch {
            before?.join()
            if (peer.closed) return@launch
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "signalling with ${peer.userId}", e)
            }
        }
    }

    override fun handleSignal(from: String, payload: JsonObject) {
        val peer = peers[from]
        if (peer == null) {
            // the second one in offers at once, which can outrun the roster that introduces them
            if (closed || from == meId) return
            val queued = early.getOrPut(from) { ArrayList() }
            queued += payload
            while (queued.size > EARLY_SIGNAL_LIMIT) queued.removeAt(0)
            return
        }
        enqueue(peer) { applySignal(peer, payload) }
    }

    private suspend fun applySignal(peer: Peer, payload: JsonObject) {
        (payload["streams"] as? JsonObject)?.let { s ->
            peer.remoteStreams = peer.remoteStreams + s.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        }
        val pc = peer.pc
        try {
            val d = payload["description"] as? JsonObject
            if (d != null) {
                val type = Sdp.typeOf((d["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()) ?: return
                val sdp = (d["sdp"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val stable = pc.signalingState() == PeerConnection.SignalingState.STABLE
                val offerCollision = type == SessionDescription.Type.OFFER && (peer.makingOffer || !stable)
                peer.ignoreOffer = !peer.polite && offerCollision
                if (peer.ignoreOffer) return
                WebRtc.setRemote(pc, Sdp.tunedRemote(SessionDescription(type, sdp), voiceStereo))
                for (c in peer.pendingCandidates.toList()) runCatching { pc.addIceCandidate(c) }
                peer.pendingCandidates.clear()
                if (type == SessionDescription.Type.OFFER) {
                    // asking to play stereo stays on this side: an older app would take it from what is sent as a request to send two channels
                    val local = WebRtc.createAnswer(pc)
                    WebRtc.setLocal(pc, Sdp.tunedLocal(local))
                    events.onSignal(peer.userId, buildJsonObject {
                        put("description", buildJsonObject {
                            put("type", Sdp.wireOf(local.type))
                            put("sdp", local.description)
                        })
                        put("streams", streamIds())
                    })
                }
                if (peer.renegotiate && pc.signalingState() == PeerConnection.SignalingState.STABLE) enqueue(peer) { negotiate(peer) }
                return
            }
            if (payload.containsKey("candidate")) {
                val c = payload["candidate"] as? JsonObject ?: return
                val cand = IceCandidate(
                    (c["sdpMid"] as? JsonPrimitive)?.contentOrNull ?: "",
                    (c["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
                    (c["candidate"] as? JsonPrimitive)?.contentOrNull ?: return,
                )
                if (pc.remoteDescription == null) {
                    peer.pendingCandidates += cand
                    return
                }
                if (!pc.addIceCandidate(cand) && !peer.ignoreOffer) Log.w(TAG, "a candidate from ${peer.userId} was refused")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "signal from ${peer.userId}", e)
        }
    }

    private fun streamIds(): JsonObject = buildJsonObject {
        put("audio", audioStreamId)
        put("video", videoStreamId)
        put("screen", screenStreamId)
    }

    private suspend fun negotiate(peer: Peer) {
        val pc = peer.pc
        if (closed) return
        // mid-exchange the other side's description is on its way; a second offer now would talk over it
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            peer.renegotiate = true
            return
        }
        peer.renegotiate = false
        try {
            peer.makingOffer = true
            val renegotiating = pc.remoteDescription != null
            val local = WebRtc.createOffer(pc)
            WebRtc.setLocal(pc, Sdp.tunedLocal(local))
            events.onSignal(peer.userId, buildJsonObject {
                put("description", buildJsonObject {
                    put("type", Sdp.wireOf(local.type))
                    put("sdp", local.description)
                })
                put("streams", streamIds())
            })
            if (renegotiating) armOfferTimer(peer)
        } finally {
            peer.makingOffer = false
        }
    }

    /** An offer that is never answered is taken back and made again; the first exchange is left to the watchdog. */
    private fun armOfferTimer(peer: Peer) {
        peer.offerJob?.cancel()
        peer.offerJob = scope.launch {
            delay(OFFER_DEADLINE_MS)
            peer.offerJob = null
            if (closed || peers[peer.userId] !== peer) return@launch
            enqueue(peer) {
                if (peer.pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return@enqueue
                Log.w(TAG, "an offer to ${peer.userId} went unanswered; sending it again")
                WebRtc.setLocal(peer.pc, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                negotiate(peer)
            }
        }
    }

    // --- senders -----------------------------------------------------------

    private fun transceiverOf(pc: PeerConnection, sender: RtpSender): RtpTransceiver? {
        val id = runCatching { sender.id() }.getOrNull() ?: return null
        return runCatching { pc.transceivers.firstOrNull { runCatching { it.sender.id() }.getOrNull() == id } }.getOrNull()
    }

    private fun syncSenders(peer: Peer) {
        val pc = peer.pc
        // audio: one sender, its track replaced in place; a silence between phrases is the track's own
        val a = audio
        if (a != null) {
            val s = peer.audioSender
            if (s != null) runCatching { s.setTrack(a, false) } else peer.audioSender = runCatching { pc.addTrack(a, listOf(audioStreamId)) }.getOrNull()
        } else peer.audioSender?.let { runCatching { it.setTrack(null, false) } }
        // video keeps its sender and only pauses it: a removed track leaves its m-line behind for good
        val v = video
        if (v != null) {
            val s = peer.videoSender
            if (s != null) {
                setSending(pc, s, true)
                runCatching { s.setTrack(v, false) }
            } else {
                val added = runCatching { pc.addTrack(v, listOf(videoStreamId)) }.getOrNull()
                peer.videoSender = added
                if (added != null) {
                    transceiverOf(pc, added)?.let { VideoCap.preferVp8(it, sending = true) }
                    VideoCap.capSender(added, VideoCap.MESH_CAMERA_BPS)
                }
            }
        } else peer.videoSender?.let { s ->
            runCatching { s.setTrack(null, false) }
            setSending(pc, s, false)
        }
        // a share's senders go when it ends: a stopped transceiver's m-line is reused by the next share
        val sc = screen
        if (sc == null || peer.screenSenders.any { runCatching { it.track()?.id() }.getOrNull() != runCatching { sc.id() }.getOrNull() }) {
            for (s in peer.screenSenders) transceiverOf(pc, s)?.let { runCatching { it.stop() } } ?: runCatching { pc.removeTrack(s) }
            peer.screenSenders.clear()
        }
        if (sc != null && peer.screenSenders.isEmpty()) {
            runCatching { pc.addTrack(sc, listOf(screenStreamId)) }.getOrNull()?.let { s ->
                transceiverOf(pc, s)?.let { VideoCap.preferVp8(it, sending = true) }
                VideoCap.capShare(s, screenBitrate, screenFps)
                peer.screenSenders += s
                peer.shareAdapter = ShareAdapter(screenFps, screenSmooth)
            }
        }
    }

    /** Turns sending on or off for a sender's transceiver, keeping whatever it receives. */
    private fun setSending(pc: PeerConnection, sender: RtpSender, on: Boolean) {
        val tr = transceiverOf(pc, sender) ?: return
        val dir = runCatching { tr.direction }.getOrNull() ?: return
        if (dir == RtpTransceiver.RtpTransceiverDirection.STOPPED) return
        val receiving = dir == RtpTransceiver.RtpTransceiverDirection.SEND_RECV || dir == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        val next = if (on) {
            if (receiving) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        } else {
            if (receiving) RtpTransceiver.RtpTransceiverDirection.RECV_ONLY else RtpTransceiver.RtpTransceiverDirection.INACTIVE
        }
        if (dir != next) runCatching { tr.setDirection(next) }
    }

    // --- receiving ---------------------------------------------------------

    private fun onRemoteTrack(peer: Peer, track: MediaStreamTrack, streamId: String?) {
        var kind = if (track is AudioTrack) "audio" else "video"
        for ((k, id) in peer.remoteStreams) if (id == streamId) kind = k
        when {
            kind == "screen" && track is VideoTrack -> {
                peer.tracks["screen"] = track
                events.onRemoteVideo(peer.userId, "screen", track)
            }
            kind == "screen" && track is AudioTrack -> {
                peer.tracks["screen_audio"] = track
                playback.attachScreenAudio(peer.userId, track, false)
            }
            track is AudioTrack -> {
                peer.tracks["audio"] = track
                playback.attachVoice(peer.userId, track, false)
                events.onRemoteAudio(peer.userId, true)
            }
            track is VideoTrack -> {
                peer.tracks["video"] = track
                events.onRemoteVideo(peer.userId, "video", track)
            }
        }
    }

    private fun dropRemote(peer: Peer, trackId: String) {
        val entry = peer.tracks.entries.firstOrNull { runCatching { it.value.id() }.getOrNull() == trackId } ?: return
        peer.tracks.remove(entry.key)
        when (entry.key) {
            "audio" -> {
                playback.detachVoice(peer.userId)
                events.onRemoteAudio(peer.userId, false)
            }
            "video" -> events.onRemoteVideo(peer.userId, "video", null)
            "screen" -> events.onRemoteVideo(peer.userId, "screen", null)
            "screen_audio" -> playback.detachScreenAudio(peer.userId)
        }
    }

    override fun setWatching(userId: String, on: Boolean) = playback.setWatching(userId, on)

    /** Round trip averaged over every link's succeeded pairs, loss over every incoming stream. */
    override suspend fun stats(): Quality? {
        var rtt = 0.0
        var n = 0
        val streams = HashMap<String, Pair<Long, Long>>()
        for ((peerId, p) in peers.toMap()) {
            // a share in motion is sized to what this peer's link carries
            for (s in p.screenSenders.toList()) VideoCap.retuneShare(p.pc, s, p.shareAdapter ?: ShareAdapter(screenFps, screenSmooth).also { p.shareAdapter = it })
            val report = WebRtc.stats(p.pc) ?: continue
            for (r in report.statsMap.values) {
                if (r.type == "candidate-pair" && r.members["state"] == "succeeded") num(r.members["currentRoundTripTime"])?.let {
                    rtt += it * 1000
                    n++
                }
                if (r.type == "inbound-rtp") streams["$peerId:${r.id}"] = (num(r.members["packetsLost"])?.toLong() ?: 0L) to (num(r.members["packetsReceived"])?.toLong() ?: 0L)
            }
        }
        return Quality(if (n > 0) rtt / n else 0.0, recentLoss.measure(streams))
    }

    companion object {
        private const val TAG = "MeshTransport"
        private const val EARLY_SIGNAL_LIMIT = 32
        private const val CONNECT_DEADLINE_MS = 12_000L
        private const val MAX_REBUILDS = 3
        private const val DROP_DEADLINE_MS = 20_000L
        private const val ICE_RESTART_MS = 4_000L
        private const val MAX_ICE_RESTARTS = 3
        private const val OFFER_DEADLINE_MS = 10_000L
    }
}
