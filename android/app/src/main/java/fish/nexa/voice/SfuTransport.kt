package fish.nexa.voice

import android.util.Log
import fish.nexa.net.ApiError
import fish.nexa.net.VoiceState
import fish.nexa.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.CryptoOptions
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A call through Cloudflare Realtime (web/src/voice/sfu.ts): one connection
 * for everything this device publishes and pulls, through chatd, which decides
 * who may pull what. Every frame is sealed with the conversation's key on the
 * way out and opened on the way in, so Cloudflare forwards media it cannot
 * read and nobody in the call learns anybody else's address.
 *
 * Three rules from the old Android app keep this alive on a real phone. The
 * connection's transceivers, senders and receivers are never listed: the
 * Java wrapper disposes every one it handed out before it lists them again,
 * the microphone's sender with them. Every receiver gets its decryptor inside
 * onTrack, before the answer is made, and the connection refuses frames that
 * are not sealed. And candidates are gathered once, so a live call is moved to
 * a new network only by an ICE restart the SFU takes part in.
 */
class SfuTransport(
    private val meId: String,
    private var channelId: String,
    private var ice: List<PeerConnection.IceServer>,
    private val events: TransportEvents,
    private val scope: CoroutineScope,
) : VoiceTransport {
    override val throughSfu = true
    override val playback = Playback(scope, events::onSpeaking)

    private class Stale : Exception()
    private class SyncLater(message: String) : Exception(message)

    /** A track pulled from someone else, arriving on one of this connection's mids. */
    private class Pulled(val userId: String, val kind: PubKind, val tag: String, val asked: String, val mid: String, val mono: Boolean = false) {
        @Volatile var transceiver: RtpTransceiver? = null
        @Volatile var track: MediaStreamTrack? = null
        @Volatile var decryptor: NativeFrameDecryptor? = null
        var arrivedAt = 0L
        var opened = false
        var tooLarge = false
    }

    private class Want(val userId: String, val kind: PubKind, val tag: String, val mono: Boolean = false)

    private class Shown(val audio: AudioTrack?, val video: VideoTrack?, val screen: VideoTrack?, val screenAudio: AudioTrack?, val videoTooLarge: Boolean, val screenTooLarge: Boolean) {
        val empty get() = audio == null && video == null && screen == null
    }

    private val noneShown = Shown(null, null, null, null, false, false)
    private val crypto = FrameKeys()
    private var pc: PeerConnection
    /** The header extension ids this connection gave each mid (ExtmapHistory). */
    private var extmaps = ExtmapHistory()
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var sessionId: String? = null
    private var connecting = false
    private var ops: Job? = null

    private var audioTrack: AudioTrack? = null
    @Volatile private var voiceStereo = false
    @Volatile private var hearMono = false
    private var videoTrack: VideoTrack? = null
    private var screenTrack: VideoTrack? = null
    private var screenBitrate = 6_000_000
    private var screenFps = 30
    /** How the share is sized to what the link carries; made again for every share. */
    private var shareAdapter = ShareAdapter(30)
    /** Whether the screen's transceiver was published in H.264 (preferH264) rather than VP8. */
    private var screenH264 = false
    /** A republishScreen waiting its turn (done, too, when a reconnect skipped it). */
    private var republishing: Job? = null
    private val senders = HashMap<PubKind, RtpTransceiver>()
    private val encryptors = HashMap<PubKind, NativeFrameEncryptor>()
    private val published = HashSet<PubKind>()

    private var roster = mapOf<String, VoiceState>()
    private var policyGeneration: Long? = null
    private val peers = LinkedHashSet<String>()
    private val watching = HashSet<String>()
    private val pulled = HashMap<String, Pulled>()
    /** Read on WebRTC's signalling thread in onTrack, written on the voice thread. */
    private val byMid = ConcurrentHashMap<String, Pulled>()
    /** Decryptors of receivers nobody asked for (a mid handed out again), let go with the connection. */
    private val orphans = ConcurrentHashMap.newKeySet<NativeFrameDecryptor>()
    private val shown = HashMap<String, Shown>()
    private var syncQueued = false

    private var keyVersion = ""
    private var keys: VoiceKeys? = null
    private var sendIndex: Int? = null
    private var sendSwitch: Job? = null

    private var everUp = false
    private val refusedPulls = HashMap<String, Pair<String, Int>>()
    private var pullRetries = 0
    private var recoverJob: Job? = null
    private var iceRestarts = 0
    private var downSince: Long? = null
    private var wasUp = false
    /** The ICE username of an answered restart that has not produced a path yet. */
    @Volatile private var pendingUfrag: String? = null
    private var retryJob: Job? = null
    private var retryRebuild = false
    private var failures = 0
    private var lastFailure = 0L
    private var videoPausedUntil = 0L
    /** Whether cameras and watched shares are pulled: their pictures are on screen, or left it less than VIDEO_HIDDEN_GRACE_MS ago. */
    private var videoShown = true
    private var hiddenJob: Job? = null
    private val recentLoss = RecentLoss()
    private val lossSeen = HashMap<String, List<VoiceCounts>>()
    private val lossTold = HashMap<String, Long>()
    private var rtt: Double? = null
    private val timers = ArrayList<Job>()

    private data class VoiceCounts(val at: Long, val received: Long, val lost: Long, val concealed: Long, val samples: Long, val jbDelay: Double, val jbEmitted: Long)

    init {
        pc = newConnection()
        refreshKeys()
        timers += scope.launch { while (isActive) { delay(KEY_CHECK_MS); refreshKeys() } }
        timers += scope.launch { while (isActive) { delay(1_000); checkReceivers() } }
        timers += scope.launch { while (isActive) { delay(LOSS_CHECK_MS); runCatching { checkLoss() }; retuneShare() } }
    }

    private fun slot(userId: String, kind: PubKind) = "$userId:${kind.wire}"

    // --- the connection ----------------------------------------------------

    private fun newConnection(): PeerConnection {
        val mine = ++generation
        val config = PeerConnection.RTCConfiguration(ice).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // a frame without its encryptor or decryptor is dropped, never sent or played as it is
            cryptoOptions = CryptoOptions.builder().setEnableGcmCryptoSuites(true).setRequireFrameEncryption(true).createCryptoOptions()
        }
        return requireNotNull(WebRtc.factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
                if (mine != generation || closed || event == null) return
                val ufrag = Regex(" ufrag (\\S+)").find(event.local?.sdp.orEmpty())?.groupValues?.get(1)
                val want = pendingUfrag
                if (want != null && (ufrag == null || ufrag == want)) pendingUfrag = null
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (mine != generation || closed) return
                scope.launch { if (mine == generation && !closed) onConnectionState(state) }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                // WebRTC's signalling thread: anything thrown here has nowhere to go
                runCatching { attach(mine, transceiver ?: return) }.onFailure { Log.w(TAG, "could not take a pulled track", it) }
            }
        })) { "the call connection could not be made" }
    }

    /**
     * A pulled track arriving, inside setRemoteDescription. Every receiver
     * gets its decryptor at once, even one this call does not know: the mid
     * may be handed out again later.
     */
    private fun attach(seen: Int, transceiver: RtpTransceiver) {
        if (seen != generation || closed) return
        val mid = transceiver.mid
        val p = mid?.let { byMid[it] }
        val video = p?.kind?.video ?: (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        if (video) VideoCap.acceptCallVideo(transceiver)
        val decryptor = crypto.decryptor() ?: return
        transceiver.receiver.setFrameDecryptor(decryptor)
        if (p == null) {
            orphans += decryptor
            return
        }
        val track = transceiver.receiver.track()
        scope.launch {
            if (seen != generation || closed || byMid[p.mid] !== p) {
                decryptor.release()
                return@launch
            }
            p.decryptor?.release()
            p.decryptor = decryptor
            p.transceiver = transceiver
            p.track = track
            p.arrivedAt = System.currentTimeMillis()
            p.opened = false
            p.tooLarge = false
            present()
        }
    }

    /** Runs a step after the ones before it; steps of a connection since thrown away are skipped. */
    private fun enqueue(step: suspend () -> Unit): Job {
        val gen = generation
        val before = ops
        val job = scope.launch {
            before?.join()
            if (closed || gen != generation) return@launch
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Stale) {
            } catch (e: Throwable) {
                if (closed || gen != generation) return@launch
                fail(e)
            }
        }
        ops = job
        return job
    }

    /**
     * Whether this device decodes a track in one channel: everything when it
     * chose that, and the voice of anyone the server says lacks Use Stereo Voice.
     * Their own app may send what it likes; this end is what holds it.
     */
    private fun monoFor(st: VoiceState, kind: PubKind) = hearMono || (kind == PubKind.AUDIO && st.monoOnly)

    override fun setHearMono(on: Boolean) {
        if (on == hearMono) return
        hearMono = on
        scheduleSync()
    }

    /**
     * Every sound this device pulls plays in stereo whenever it is sent that way. Its own
     * section stays as it was made, since an encoder goes by the remote description.
     */
    private fun tuneLocal(desc: SessionDescription): SessionDescription {
        val stereo = HashSet<String>()
        val mono = HashSet<String>()
        for (p in pulled.values) if (!p.kind.video) (if (p.mono) mono else stereo) += p.mid
        return Sdp.tunedLocal(desc, stereo, mono)
    }

    /** Stops a step that waited while its connection was replaced or closed. */
    /** Applies this device's own description, noting the extension ids it gives each mid. */
    private suspend fun setLocal(of: PeerConnection, desc: SessionDescription) {
        WebRtc.setLocal(of, tuneLocal(desc))
        // leaving while this waited disposed the connection (on this same thread), and reading a disposed
        // one crashes in native code, past what runCatching can catch
        if (of === pc && !closed) extmaps.note(runCatching { of.localDescription }.getOrNull()?.description ?: desc.description)
    }

    /** Applies the SFU's description, without extension ids that would mean something else on a mid than before. */
    private suspend fun setRemote(of: PeerConnection, desc: SessionDescription) {
        val fitted = extmaps.fit(desc)
        WebRtc.setRemote(of, fitted)
        if (of === pc) extmaps.note(fitted.description)
    }

    private fun live(of: PeerConnection) {
        if (closed || of !== pc) throw Stale()
    }

    private fun fail(err: Throwable) {
        if (err is ApiError && err.code == "video_paused") {
            pauseVideo(err.message ?: t("sfu.videoIsPausedUntilNextMonth"))
            return
        }
        Log.w(TAG, "an SFU step failed", err)
        // a refused pull changed nothing here; anything else may have left the
        // connection half way through an exchange, so it is made again
        retryLater(err !is SyncLater)
    }

    /** Tries again after a wait that grows while failures keep coming; a rebuild replaces a waiting sync. */
    private fun retryLater(rebuild: Boolean, baseMs: Long = RETRY_BASE_MS, expected: Boolean = false) {
        if (closed) return
        if (retryJob?.isActive == true) {
            if (!rebuild || retryRebuild) return
            retryJob?.cancel()
        } else if (expected) {
            pullRetries++
        } else {
            val now = System.currentTimeMillis()
            if (now - lastFailure > FAILURES_FORGOTTEN_MS) failures = 0
            lastFailure = now
            failures++
        }
        val tries = if (expected) pullRetries else failures
        // a publisher refused again and again is not one still connecting
        val stuck = expected && refusedPulls.values.any { it.second >= STUCK_PULLS }
        val cap = if (expected && !stuck) PULL_RETRY_MAX_MS else RETRY_MAX_MS
        val wait = min(cap, baseMs * (1L shl max(0, min(10, tries - 1))))
        retryRebuild = rebuild
        retryJob = scope.launch {
            delay(wait)
            retryJob = null
            if (rebuild) rebuild() else scheduleSync()
        }
    }

    private fun refreshKeys() {
        if (closed) return
        if ((CallKeys.versionFor(channelId) ?: "none:$channelId") == keyVersion) return
        // without a key for the channel nothing is sent at all, never in the clear or under another channel's key
        val k = CallKeys.keysFor(channelId) ?: VoiceKeys(emptyList(), 0, "none:$channelId")
        if (k.version == keyVersion) return
        keyVersion = k.version
        keys = k
        val before = sendIndex
        // a newer key is opened with at once, and sealed with a moment later (SEND_SWITCH_MS)
        if (before != null && before != k.send && k.keys.any { it.first == before }) {
            crypto.set(VoiceKeys(k.keys, before, k.version))
            if (sendSwitch?.isActive != true) {
                sendSwitch = scope.launch {
                    delay(SEND_SWITCH_MS)
                    val now = keys ?: return@launch
                    if (closed) return@launch
                    sendIndex = now.send
                    crypto.set(now)
                }
            }
            return
        }
        sendSwitch?.cancel()
        sendSwitch = null
        sendIndex = if (k.keys.isNotEmpty()) k.send else null
        crypto.set(k)
    }

    override fun joined(state: VoiceState) {
        roster = roster + (meId to state)
        policyGeneration = state.sfuGeneration
        connect()
    }

    override fun reconnected() = reset()

    override fun keysArrived() = refreshKeys()

    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        ice = servers
        runCatching {
            val config = PeerConnection.RTCConfiguration(servers).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                cryptoOptions = CryptoOptions.builder().setEnableGcmCryptoSuites(true).setRequireFrameEncryption(true).createCryptoOptions()
            }
            pc.setConfiguration(config)
        }
    }

    override fun moveTo(channelId: String) {
        if (channelId == this.channelId) return
        this.channelId = channelId
        // another room's keys: nothing from the old one is kept to seal with
        sendSwitch?.cancel()
        sendSwitch = null
        sendIndex = null
        refreshKeys()
        roster = emptyMap()
        reset()
    }

    private fun connect() {
        if (closed || sessionId != null || connecting) return
        connecting = true
        val of = pc
        enqueue {
            val session = try {
                VoiceApi.sfuSession()
            } finally {
                if (of === pc) connecting = false
            }
            live(of)
            sessionId = session
            // a rebuild still waiting from an earlier failure would tear down the session just made
            if (retryJob?.isActive == true && retryRebuild) {
                retryJob?.cancel()
                retryJob = null
            }
            publishNow(wantedKinds())
            scheduleSync()
        }
    }

    private fun rebuild() {
        reset()
        connect()
    }

    /** A fresh connection with no session, nothing published and nothing pulled. */
    private fun reset() {
        if (closed) return
        val old = pc
        ops = null
        recoverJob?.cancel()
        recoverJob = null
        retryJob?.cancel()
        retryJob = null
        iceRestarts = 0
        downSince = null
        wasUp = false
        pendingUfrag = null
        // everyone's tracks go from the screen before the connection that owns them is disposed
        for (p in pulled.values) {
            p.track = null
            p.transceiver = null
        }
        present()
        releasePulled()
        for (e in encryptors.values) e.release()
        encryptors.clear()
        senders.clear()
        published.clear()
        pc = newConnection()
        extmaps = ExtmapHistory()
        runCatching { old.dispose() }
        sessionId = null
        connecting = false
        lossSeen.clear()
        lossTold.clear()
        pullRetries = 0
        refusedPulls.clear()
        present()
        for (id in peers) events.onPeerState(id, mapState(runCatching { pc.connectionState() }.getOrNull()))
        events.onConnection(if (everUp) CallConnection.RECONNECTING else CallConnection.CONNECTING)
    }

    private fun releasePulled() {
        for (p in pulled.values) {
            p.decryptor?.release()
            p.decryptor = null
        }
        pulled.clear()
        byMid.clear()
        for (d in orphans) d.release()
        orphans.clear()
    }

    private fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        val mapped = mapState(state)
        for (id in peers) events.onPeerState(id, mapped)
        if (state == PeerConnection.PeerConnectionState.CONNECTED) everUp = true
        events.onConnection(if (state == PeerConnection.PeerConnectionState.CONNECTED) CallConnection.CONNECTED else if (everUp) CallConnection.RECONNECTING else CallConnection.CONNECTING)
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                iceRestarts = 0
                failures = 0
                val downFor = downSince?.let { System.currentTimeMillis() - it } ?: 0
                downSince = null
                wasUp = true
                // Cloudflare lets go of tracks after 30 s without their media
                if (downFor >= TRACKS_GONE_MS) retryLater(true, 0)
                if (pendingUfrag == null) {
                    recoverJob?.cancel()
                    recoverJob = null
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> {
                if (wasUp && downSince == null) downSince = System.currentTimeMillis()
                scheduleRecover(if (state == PeerConnection.PeerConnectionState.FAILED) 0 else RECOVER_AFTER_MS)
            }
            else -> Unit
        }
    }

    override fun nudge() {
        if (closed) return
        if (retryJob?.isActive == true && retryRebuild) {
            retryJob?.cancel()
            retryJob = null
            failures = 0
            rebuild()
            return
        }
        val state = runCatching { pc.connectionState() }.getOrNull()
        if (state != PeerConnection.PeerConnectionState.DISCONNECTED && state != PeerConnection.PeerConnectionState.FAILED) return
        recoverJob?.cancel()
        recoverJob = null
        scheduleRecover(0)
    }

    override fun currentSession(): String? = sessionId

    /**
     * One job for the whole outage: restart ICE (keeping the session, its
     * tracks and everyone's pulls) every 8 s while the connection stays down;
     * make it again from nothing only when restarts were answered and never
     * took, or the SFU no longer has the session.
     */
    private fun scheduleRecover(wait: Long) {
        if (closed || recoverJob?.isActive == true) return
        val of = pc
        recoverJob = scope.launch {
            delay(wait)
            while (isActive && !closed && of === pc) {
                val state = runCatching { of.connectionState() }.getOrNull()
                val down = state == PeerConnection.PeerConnectionState.DISCONNECTED || state == PeerConnection.PeerConnectionState.FAILED
                val stuck = pendingUfrag != null
                if (!down && !stuck) return@launch
                val anchor = PubKind.entries.firstOrNull { it in published && runCatching { senders[it]?.mid }.getOrNull() != null }
                if (iceRestarts >= MAX_ICE_RESTARTS || (stuck && iceRestarts >= 2) || sessionId == null || anchor == null) {
                    retryLater(true, 0)
                    return@launch
                }
                val session = sessionId!!
                val mid = senders[anchor]!!.mid
                enqueue {
                    try {
                        of.restartIce()
                        val offer = WebRtc.createOffer(of, iceRestart = true)
                        live(of)
                        setLocal(of, offer)
                        live(of)
                        pendingUfrag = Regex("a=ice-ufrag:(\\S+)").find(offer.description)?.groupValues?.get(1)
                        val res = VoiceApi.sfuRestart(session, offer, mid, anchor.wire)
                        live(of)
                        val answer = res.answer?.toWebRtc() ?: throw IllegalStateException("no answer to the ICE restart")
                        setRemote(of, Sdp.tunedRemote(answer, voiceStereo))
                        iceRestarts++
                    } catch (e: Stale) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        pendingUfrag = null
                        WebRtc.rollback(of)
                        // a session the SFU let go of cannot come back; anything else waits for the next try
                        if (e is ApiError && (e.code == "sfu_session_gone" || e.code == "not_in_voice")) throw e
                        Log.w(TAG, "the ICE restart did not go through", e)
                    }
                }.join()
                delay(RESTART_EVERY_MS)
            }
        }
    }

    // --- publishing --------------------------------------------------------

    private fun trackFor(kind: PubKind): MediaStreamTrack? = when (kind) {
        PubKind.AUDIO -> audioTrack
        PubKind.VIDEO -> videoTrack
        PubKind.SCREEN -> screenTrack
        PubKind.SCREEN_AUDIO -> null
    }

    private fun videoPaused() = System.currentTimeMillis() < videoPausedUntil

    private fun mayPublish(kind: PubKind): Boolean {
        val me = roster[meId]
        return (kind != PubKind.AUDIO && kind != PubKind.SCREEN_AUDIO) || !(me?.serverMute == true || me?.serverDeaf == true || me?.suppress == true)
    }

    private fun wantedKinds(): List<PubKind> {
        val kinds = ArrayList<PubKind>()
        if (audioTrack != null && mayPublish(PubKind.AUDIO)) kinds += PubKind.AUDIO
        if (!videoPaused()) {
            if (videoTrack != null) kinds += PubKind.VIDEO
            if (screenTrack != null) kinds += PubKind.SCREEN
        }
        return kinds
    }

    private fun publish(kinds: List<PubKind>) {
        if (sessionId == null) return // connecting publishes whatever is there by then
        enqueue {
            publishNow(kinds)
            scheduleSync()
        }
    }

    /** Adds the missing transceivers, each with its encryptor before any offer, and publishes them in one offer. */
    private suspend fun publishNow(kinds: List<PubKind>) {
        val session = sessionId ?: return
        val of = pc
        val fresh = kinds.filter { it !in published && mayPublish(it) }
        if (fresh.isEmpty()) return
        val added = ArrayList<PubKind>()
        for (kind in fresh) {
            if (senders.containsKey(kind)) continue
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            val track = trackFor(kind)
            val tr = if (track != null) of.addTransceiver(track, init)
            else of.addTransceiver(if (kind.video) MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO else MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
            if (kind == PubKind.SCREEN) {
                screenH264 = wantsH264() && VideoCap.preferH264(tr)
                if (!screenH264) VideoCap.preferVp8(tr, sending = true)
            } else if (kind.video) VideoCap.preferVp8(tr, sending = true)
            val encryptor = crypto.encryptor()
            if (encryptor == null) {
                runCatching { tr.stop() }
                throw IllegalStateException("no frame keys")
            }
            tr.sender.setFrameEncryptor(encryptor)
            encryptors[kind] = encryptor
            if (kind == PubKind.VIDEO) VideoCap.capSender(tr.sender, VideoCap.CAMERA_BPS)
            if (kind == PubKind.SCREEN) VideoCap.capShare(tr.sender, screenBitrate, screenFps)
            senders[kind] = tr
            added += kind
        }
        var failed = 0
        try {
            val offer = WebRtc.createOffer(of)
            live(of)
            setLocal(of, offer)
            live(of)
            val local = of.localDescription ?: offer
            val res = VoiceApi.sfuPublish(session, local, fresh.map { Triple(senders[it]!!.mid, it.wire, if (it == PubKind.SCREEN) screenBitrate else if (it == PubKind.AUDIO && voiceStereo) Sdp.VOICE_STEREO_BITRATE else 0) })
            live(of)
            val answer = res.answer?.toWebRtc() ?: throw IllegalStateException("no answer to the offer")
            setRemote(of, Sdp.tunedRemote(answer, voiceStereo))
            live(of)
            failed = res.failed
        } catch (e: Throwable) {
            if (of !== pc || closed) throw Stale()
            // take the offer back, and the transceivers it added, so the next exchange starts clean
            WebRtc.rollback(of)
            for (kind in added) {
                senders.remove(kind)?.let { runCatching { it.stop() } }
                encryptors.remove(kind)?.release()
            }
            throw e
        }
        published += fresh
        // the SFU turned a track down: the session is made again rather than left without it
        if (failed > 0) throw IllegalStateException("the call service refused $failed track(s)")
    }

    /**
     * Whether the screen goes out in H.264: this phone's hardware encoder can
     * make it, and everyone else in the call said they can watch it (an older
     * app cannot, and gets VP8).
     */
    private fun wantsH264(): Boolean =
        VideoCap.sendsH264 && roster.values.all { it.userId == meId || it.codecs?.contains("h264") == true }

    /**
     * The share published again, in the codec the call can watch now. One at a
     * time: it reads the call as it is when its turn comes, and looks again
     * once it is done, so states arriving together cost one change, not one each.
     */
    private fun republishScreen() {
        if (sessionId == null || republishing?.isActive == true) return
        Log.i(TAG, "the share goes out again in ${if (wantsH264()) "H.264" else "VP8"}")
        republishing = enqueue {
            unpublishNow(PubKind.SCREEN)
            if (screenTrack != null) publishNow(listOf(PubKind.SCREEN))
            republishing = null
            if (screenTrack != null && senders[PubKind.SCREEN] != null && wantsH264() != screenH264) republishScreen()
            scheduleSync()
        }
    }

    /** Takes a published track back from the SFU; publishing it again gives it a new name everyone pulls afresh. */
    private suspend fun unpublishNow(kind: PubKind) {
        val session = sessionId ?: return
        val tr = senders.remove(kind) ?: return
        published -= kind
        encryptors.remove(kind)?.release()
        val mid = tr.mid
        runCatching { tr.stop() }
        if (mid == null) return
        val of = pc
        val offer = WebRtc.createOffer(of)
        live(of)
        setLocal(of, offer)
        live(of)
        val res = VoiceApi.sfuClose(session, listOf(mid), of.localDescription ?: offer)
        live(of)
        val answer = res.answer?.toWebRtc() ?: throw IllegalStateException("no answer to closing a track")
        setRemote(of, Sdp.tunedRemote(answer, voiceStereo))
        live(of)
    }

    /**
     * An encoder takes its channels from the description, so a voice that changes
     * is published again, as a share changing codec is: everyone pulls it afresh.
     */
    override fun setVoiceStereo(on: Boolean) {
        if (on == voiceStereo) return
        voiceStereo = on
        if (sessionId == null || PubKind.AUDIO !in published) return
        enqueue {
            unpublishNow(PubKind.AUDIO)
            if (audioTrack != null) publishNow(listOf(PubKind.AUDIO))
            scheduleSync()
        }
    }

    override fun setLocalAudio(track: AudioTrack?) {
        audioTrack = track
        val tr = senders[PubKind.AUDIO]
        if (tr != null) runCatching { tr.sender.setTrack(track, false) }
        else if (track != null) publish(listOf(PubKind.AUDIO))
    }

    override fun setLocalVideo(track: VideoTrack?) {
        videoTrack = track
        val tr = senders[PubKind.VIDEO]
        if (track == null && tr != null) { enqueue { unpublishNow(PubKind.VIDEO); if (videoTrack != null) publishNow(listOf(PubKind.VIDEO)) }; return }
        if (tr != null) runCatching { tr.sender.setTrack(track, false) }
        else if (track != null) publish(listOf(PubKind.VIDEO))
    }

    override fun setLocalScreen(track: VideoTrack?, bitrate: Int, fps: Int, smooth: Boolean) {
        screenTrack = track
        screenBitrate = VideoCap.clampShareBitrate(bitrate)
        if (fps > 0) screenFps = fps
        if (track != null) shareAdapter = ShareAdapter(screenFps, smooth)
        val tr = senders[PubKind.SCREEN]
        if (track == null && tr != null) { enqueue { unpublishNow(PubKind.SCREEN); if (screenTrack != null) publishNow(listOf(PubKind.SCREEN)) }; return }
        // a share starting on a transceiver of the other codec is published again in the one the call can watch
        if (tr != null && track != null && wantsH264() != screenH264) {
            republishScreen()
            return
        }
        if (tr != null) {
            runCatching { tr.sender.setTrack(track, false) }
            if (track != null) VideoCap.capShare(tr.sender, screenBitrate, screenFps)
        } else if (track != null) publish(listOf(PubKind.SCREEN))
    }

    /**
     * A share in motion is sized to what the link carries (ShareAdapt). The
     * camera needs nothing: capture sizes are fixed at the source here
     * (1280x720, shares within their box), inside the cap.
     */
    private suspend fun retuneShare() {
        val tr = senders[PubKind.SCREEN] ?: return
        if (screenTrack == null) return
        VideoCap.retuneShare(pc, tr.sender, shareAdapter)
    }

    private fun pauseVideo(message: String) {
        videoPausedUntil = System.currentTimeMillis() + VIDEO_PAUSE_MS
        events.onError("video_paused", message)
        publish(wantedKinds())
        scheduleSync()
    }

    // --- pulling -----------------------------------------------------------

    override fun addPeer(userId: String) {
        if (userId == meId || !peers.add(userId)) return
        events.onPeerState(userId, mapState(runCatching { pc.connectionState() }.getOrNull()))
        scheduleSync()
    }

    override fun removePeer(userId: String) {
        if (!peers.remove(userId)) return
        lossTold.remove(userId)
        // silent at once; the next exchange tells the SFU to stop sending their tracks
        present()
        scheduleSync()
    }

    override fun peerIds(): List<String> = peers.toList()

    /** A listener's loss report: copies are not sent from Android (no RTP timestamps at the encryptor), so it changes nothing here. */
    override fun handleSignal(from: String, payload: JsonObject) = Unit

    override fun setRoster(states: List<VoiceState>) {
        roster = states.filter { it.channelId == channelId }.associateBy { it.userId }
        val policy = roster[meId]?.sfuGeneration ?: 0L
        val changed = policyGeneration != null && policyGeneration != policy
        policyGeneration = policy
        if (changed && sessionId != null) rebuild()
        // someone who cannot watch H.264 came in, or the last of them left: the share changes codec
        if (screenTrack != null && senders[PubKind.SCREEN] != null && wantsH264() != screenH264) republishScreen()
        // a camera turned on or off shows at once: its track stays pulled either way
        present()
        scheduleSync()
    }

    override fun setVideoShown(on: Boolean) {
        if (on) {
            hiddenJob?.cancel()
            hiddenJob = null
            if (videoShown) return
            videoShown = true
            scheduleSync()
            return
        }
        if (!videoShown || hiddenJob != null) return
        hiddenJob = scope.launch {
            delay(VIDEO_HIDDEN_GRACE_MS)
            hiddenJob = null
            videoShown = false
            scheduleSync()
        }
    }

    override fun setWatching(userId: String, on: Boolean) {
        if (on) watching += userId else watching -= userId
        playback.setWatching(userId, on)
        scheduleSync()
    }

    /** Everyone's voice and camera, and the shares being watched. */
    private fun wanted(): Map<String, Want> {
        val out = LinkedHashMap<String, Want>()
        // pictures come in while the server allows video and someone can see them
        val pictures = !videoPaused() && videoShown
        for ((userId, st) in roster) {
            if (userId == meId || userId !in peers) continue
            fun add(kind: PubKind) {
                val tag = st.media?.get(kind.wire)
                if (!tag.isNullOrEmpty()) out[slot(userId, kind)] = Want(userId, kind, tag, monoFor(st, kind))
            }
            if (roster[meId]?.serverDeaf != true && !st.serverMute && !st.serverDeaf && !st.suppress) add(PubKind.AUDIO)
            if (pictures) add(PubKind.VIDEO)
            if (st.streaming && userId in watching) {
                if (pictures) add(PubKind.SCREEN)
                if (roster[meId]?.serverDeaf != true && !st.serverMute && !st.serverDeaf && !st.suppress) add(PubKind.SCREEN_AUDIO)
            }
        }
        return out
    }

    private fun scheduleSync() {
        if (syncQueued || closed) return
        syncQueued = true
        scope.launch {
            syncQueued = false
            enqueue { syncNow() }
        }
    }

    private suspend fun syncNow() {
        if (sessionId == null) return
        val want = wanted()
        val drop = pulled.entries.filter { (key, p) ->
            val w = want[key]
            // a track published again has a new tag, and is pulled again
            // and so is one to decode differently, which only a new exchange can change
            w == null || (w.tag != p.tag && w.tag != p.asked) || w.mono != p.mono
        }.map { it.value }
        val add = want.entries.filter { (key, _) -> !pulled.containsKey(key) || drop.contains(pulled[key]) }.map { it.value }
        if (drop.isNotEmpty()) unsubscribe(drop)
        if (add.isNotEmpty()) subscribe(add)
    }

    private suspend fun subscribe(list: List<Want>) {
        val session = sessionId ?: return
        val of = pc
        val res = try {
            VoiceApi.sfuSubscribe(session, list.map { it.userId to it.kind.wire })
        } catch (e: Throwable) {
            live(of)
            if (e is ApiError && (e.code == "sfu_session_gone" || e.code == "not_in_voice")) throw e
            throw SyncLater(e.message ?: "subscribe failed")
        }
        live(of)
        val asked = list.associate { slot(it.userId, it.kind) to it.tag }
        var paused = false
        var refused = false
        for (t in res.tracks) {
            val kind = PubKind.of(t.kind) ?: continue
            val key = slot(t.userId, kind)
            if (t.error == "video_paused") paused = true
            else if (t.error != null || t.mid == null) {
                refused = true
                // counted per track, and from nothing again when its publisher publishes it anew
                val tag = asked[key].orEmpty()
                val prev = refusedPulls[key]
                refusedPulls[key] = tag to (if (prev != null && prev.first == tag) prev.second + 1 else 1)
            }
            if (t.mid == null || t.error != null) continue
            refusedPulls.remove(key)
            val p = Pulled(t.userId, kind, t.tag.orEmpty(), asked[key].orEmpty(), t.mid, list.firstOrNull { it.userId == t.userId && it.kind == kind }?.mono == true)
            pulled[key]?.let { old -> if (byMid[old.mid] === old) byMid.remove(old.mid) }
            pulled[key] = p
            // filled before the offer is applied: onTrack fires inside setRemoteDescription
            byMid[p.mid] = p
        }
        val offer = res.offer?.toWebRtc()
        if (offer != null) {
            setRemote(of, Sdp.tunedRemote(offer, voiceStereo))
            live(of)
            val answer = WebRtc.createAnswer(of)
            live(of)
            setLocal(of, answer)
            live(of)
            VoiceApi.sfuRenegotiate(session, of.localDescription ?: answer)
            live(of)
        }
        if (paused && !videoPaused()) pauseVideo(t("sfu.videoIsPausedUntilNextMonth"))
        // a track not there yet (its publisher still connecting) is asked for again shortly
        if (refused) retryLater(false, PULL_RETRY_BASE_MS, true) else pullRetries = 0
    }

    private suspend fun unsubscribe(list: List<Pulled>) {
        val session = sessionId ?: return
        val of = pc
        for (p in list) {
            val key = slot(p.userId, p.kind)
            if (pulled[key] === p) pulled.remove(key)
            if (byMid[p.mid] === p) byMid.remove(p.mid)
            // the mid is handed back and can be used again, so nothing keyed on it may outlive this track
            lossSeen.remove(p.mid)
            p.track = null
            val tr = p.transceiver
            p.transceiver = null
            p.decryptor?.release()
            p.decryptor = null
            tr?.let { runCatching { it.stop() } }
        }
        present()
        val offer = WebRtc.createOffer(of)
        live(of)
        setLocal(of, offer)
        live(of)
        val res = VoiceApi.sfuClose(session, list.map { it.mid }, of.localDescription ?: offer)
        live(of)
        val answer = res.answer?.toWebRtc() ?: throw IllegalStateException("no answer to closing tracks")
        setRemote(of, Sdp.tunedRemote(answer, voiceStereo))
        live(of)
    }

    /** Every second: which pulled tracks have opened a frame (their sound may play), and which are too large to show. */
    private fun checkReceivers() {
        if (closed) return
        var changed = false
        for (p in pulled.values) {
            val d = p.decryptor ?: continue
            val c = runCatching { d.counts() }.getOrNull() ?: continue
            if (c[1] > 0 && !p.opened) {
                p.opened = true
                if (p.kind == PubKind.AUDIO) playback.setHeld(p.userId, "voice", false)
                if (p.kind == PubKind.SCREEN_AUDIO) playback.setHeld(p.userId, "screen", false)
            }
            val big = c[2] == 1L
            if (big != p.tooLarge) {
                p.tooLarge = big
                changed = true
            }
        }
        if (changed) present()
    }

    private fun held(userId: String, kind: PubKind): Boolean = pulled[slot(userId, kind)]?.let { !it.opened } ?: false

    private fun liveTrack(t: MediaStreamTrack?): MediaStreamTrack? =
        t?.takeIf { runCatching { it.state() == MediaStreamTrack.State.LIVE }.getOrDefault(false) }

    /** Reports what can be heard and seen of each person, where that changed. */
    private fun present() {
        if (closed) return
        val users = HashSet(shown.keys)
        for (p in pulled.values) users += p.userId
        for (userId in users) {
            val st = if (userId in peers) roster[userId] else null
            fun track(kind: PubKind): MediaStreamTrack? = if (st == null) null else liveTrack(pulled[slot(userId, kind)]?.track)
            val prev = shown[userId] ?: noneShown
            val audio = track(PubKind.AUDIO) as? AudioTrack
            val video = if (st?.video == true) track(PubKind.VIDEO) as? VideoTrack else null
            // a share shows once its picture is there; its sound comes with it
            val screen = if (st?.streaming == true) track(PubKind.SCREEN) as? VideoTrack else null
            val screenAudio = if (screen != null) track(PubKind.SCREEN_AUDIO) as? AudioTrack else null
            val next = Shown(
                audio, video, screen, screenAudio,
                videoTooLarge = video != null && pulled[slot(userId, PubKind.VIDEO)]?.tooLarge == true,
                screenTooLarge = screen != null && pulled[slot(userId, PubKind.SCREEN)]?.tooLarge == true,
            )
            if (next.audio !== prev.audio) {
                if (next.audio != null) playback.attachVoice(userId, next.audio, held(userId, PubKind.AUDIO)) else playback.detachVoice(userId)
                events.onRemoteAudio(userId, next.audio != null)
            }
            if (next.video !== prev.video) events.onRemoteVideo(userId, "video", next.video)
            if (next.screenAudio !== prev.screenAudio) {
                if (next.screenAudio != null) playback.attachScreenAudio(userId, next.screenAudio, held(userId, PubKind.SCREEN_AUDIO)) else playback.detachScreenAudio(userId)
            }
            if (next.screen !== prev.screen) events.onRemoteVideo(userId, "screen", next.screen)
            if (next.videoTooLarge != prev.videoTooLarge || (next.videoTooLarge && next.video !== prev.video)) events.onTooLarge(userId, "video", next.videoTooLarge)
            if (next.screenTooLarge != prev.screenTooLarge || (next.screenTooLarge && next.screen !== prev.screen)) events.onTooLarge(userId, "screen", next.screenTooLarge)
            if (next.empty) shown.remove(userId) else shown[userId] = next
        }
    }

    // --- loss reports (voice.md 3.11) ----------------------------------------

    /** Measures what each pulled voice loses, and tells its speaker when it is enough to matter. */
    private suspend fun checkLoss() {
        if (closed || sessionId == null) return
        val of = pc
        val report = WebRtc.stats(of) ?: return
        if (of !== pc || closed) return
        val now = System.currentTimeMillis()
        for (r in report.statsMap.values) {
            if (r.type == "candidate-pair" && r.members["nominated"] == true && r.members["state"] == "succeeded") {
                num(r.members["currentRoundTripTime"])?.let { rtt = it * 1000 }
            }
        }
        for (r in report.statsMap.values) {
            if (r.type != "inbound-rtp" || r.members["kind"] != "audio") continue
            val mid = r.members["mid"] as? String ?: continue
            val p = byMid[mid] ?: continue
            if (p.kind != PubKind.AUDIO) continue
            val counts = VoiceCounts(
                at = now,
                received = num(r.members["packetsReceived"])?.toLong() ?: 0,
                lost = max(0L, num(r.members["packetsLost"])?.toLong() ?: 0),
                concealed = num(r.members["concealedSamples"])?.toLong() ?: 0,
                samples = num(r.members["totalSamplesReceived"])?.toLong() ?: 0,
                jbDelay = num(r.members["jitterBufferDelay"]) ?: 0.0,
                jbEmitted = num(r.members["jitterBufferEmittedCount"])?.toLong() ?: 0,
            )
            val seen = lossSeen[mid].orEmpty()
            val last = seen.lastOrNull()
            // counters that went back are a new stream
            val kept = if (last != null && counts.received >= last.received) seen else emptyList()
            lossSeen[mid] = (kept + counts).takeLast(LOSS_CHECKS_KEPT)
            val told = lossTold[p.userId] ?: 0L
            if (kept.isEmpty() || p.arrivedAt == 0L || now - p.arrivedAt < LOSS_GRACE_MS || now - told < LOSS_REPORT_MS) continue
            data class Over(val from: VoiceCounts, val total: Long, val missed: Long, val loss: Double)
            fun over(from: VoiceCounts): Over {
                val received = counts.received - from.received
                val missed = max(0L, counts.lost - from.lost)
                val total = received + missed
                return Over(from, total, missed, if (total > 0) missed.toDouble() / total else 0.0)
            }
            val recent = over(kept.last())
            val span = over(kept.firstOrNull { it.at >= told } ?: kept.first())
            val tell = when {
                recent.total >= LOSS_HEAVY_MIN_PACKETS && recent.missed >= LOSS_HEAVY_MIN_MISSED && recent.loss >= LOSS_HEAVY -> recent
                span.total >= LOSS_SPAN_MIN_PACKETS && span.missed >= LOSS_SPAN_MIN_MISSED && span.loss >= LOSS_ONE_COPY -> span
                else -> null
            } ?: continue
            lossTold[p.userId] = now
            val samples = counts.samples - tell.from.samples
            val emitted = counts.jbEmitted - tell.from.jbEmitted
            val conceal = if (samples > 0) ((counts.concealed - tell.from.concealed).toDouble() / samples).coerceIn(0.0, 1.0) else tell.loss
            val wait = if (emitted > 0) (counts.jbDelay - tell.from.jbDelay) / emitted * 1000 else 0.0
            fun share(v: Double) = (v * 1000).roundToInt() / 1000.0
            events.onSignal(p.userId, buildJsonObject {
                put("voice_loss", share(tell.loss))
                put("voice_conceal", share(conceal))
                put("voice_delay", (wait + (rtt ?: 0.0)).roundToLong())
                put("voice_span", now - tell.from.at)
            })
        }
    }

    // --- output ------------------------------------------------------------

    override suspend fun stats(): Quality? {
        val of = pc
        val report = WebRtc.stats(of) ?: return null
        var selected: String? = null
        val pairs = HashMap<String, org.webrtc.RTCStats>()
        val streams = HashMap<String, Pair<Long, Long>>()
        for (r in report.statsMap.values) {
            when (r.type) {
                "transport" -> selected = r.members["selectedCandidatePairId"] as? String
                "candidate-pair" -> pairs[r.id] = r
                "inbound-rtp" -> streams[r.id] = (num(r.members["packetsLost"])?.toLong() ?: 0L) to (num(r.members["packetsReceived"])?.toLong() ?: 0L)
            }
        }
        val pair = selected?.let { pairs[it] } ?: pairs.values.firstOrNull { it.members["nominated"] == true && it.members["currentRoundTripTime"] != null }
        val connected = runCatching { of.connectionState() }.getOrNull() == PeerConnection.PeerConnectionState.CONNECTED
        // a pair that is down still reports the round trip it last had
        val rtt = if (connected) (num(pair?.members?.get("currentRoundTripTime")) ?: 0.0) * 1000 else 0.0
        return Quality(rtt, recentLoss.measure(streams))
    }

    override fun cryptoStats(): LongArray = crypto.stats()

    override fun close() {
        if (closed) return
        closed = true
        generation++
        for (t in timers) t.cancel()
        sendSwitch?.cancel()
        recoverJob?.cancel()
        retryJob?.cancel()
        hiddenJob?.cancel()
        for (p in pulled.values) {
            p.track = null
            p.transceiver = null
        }
        shown.clear()
        playback.close()
        releasePulled()
        for (e in encryptors.values) e.release()
        encryptors.clear()
        senders.clear()
        runCatching { pc.dispose() }
        crypto.release()
    }

    companion object {
        private const val TAG = "SfuTransport"
        private const val RECOVER_AFTER_MS = 3_000L
        private const val RESTART_EVERY_MS = 8_000L
        private const val MAX_ICE_RESTARTS = 4
        private const val TRACKS_GONE_MS = 20_000L
        private const val KEY_CHECK_MS = 1_000L
        private const val SEND_SWITCH_MS = 3_000L
        private const val STUCK_PULLS = 8
        private const val RETRY_BASE_MS = 1_000L
        private const val PULL_RETRY_BASE_MS = 400L
        private const val RETRY_MAX_MS = 8_000L
        private const val PULL_RETRY_MAX_MS = 1_600L
        private const val FAILURES_FORGOTTEN_MS = 60_000L
        private const val LOSS_CHECK_MS = 2_000L
        private const val LOSS_REPORT_MS = 4_000L
        private const val LOSS_CHECKS_KEPT = 3
        private const val LOSS_SPAN_MIN_PACKETS = 150L
        private const val LOSS_SPAN_MIN_MISSED = 6L
        private const val LOSS_HEAVY = 0.08
        private const val LOSS_HEAVY_MIN_PACKETS = 40L
        private const val LOSS_HEAVY_MIN_MISSED = 4L
        private const val LOSS_ONE_COPY = 0.02
        private const val LOSS_GRACE_MS = 4_000L
        private const val VIDEO_PAUSE_MS = 10 * 60_000L
        /**
         * How long the call's pictures stay pulled after they leave the screen:
         * a look at another channel, or a moment in another app, finds them
         * still there, rather than waiting on a pull and a keyframe.
         */
        private const val VIDEO_HIDDEN_GRACE_MS = 15_000L
    }
}
