package fish.nexa.voice

import android.content.Context
import android.util.Log
import fish.nexa.Nexa
import fish.nexa.data.GatewayEvent
import fish.nexa.media.Sounds
import fish.nexa.net.Message
import fish.nexa.net.VoiceState
import fish.nexa.net.wireJson
import fish.nexa.ui.I18n
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.t
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * Calls as the rest of the app sees them (web/src/voice/session.ts): joining
 * and leaving, incoming calls ringing, the calling tone, the alone timer, and
 * the voice events every device of the account hears. The call itself is
 * [Engine]. All of it runs on one thread of its own, the voice thread, as the
 * web runs it on its one page thread.
 */
object Calls {
    private const val TAG = "Calls"

    /** How long a call rings, here and on the other side. */
    private const val RING_MS = 60_000L
    /** A conversation's call everyone else left hangs up after this long unless someone comes back. */
    private const val ALONE_MS = 60_000L
    /** Relay credentials expire, so the list is fetched again after this long. */
    private const val ICE_CACHE_MS = 30 * 60 * 1000L
    private val SIGNAL_ERRORS = setOf("not_in_voice", "peer_gone", "too_large")
    private val MODE_ERRORS = setOf("sfu_required", "sfu_off")

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "nexa-voice").apply { isDaemon = true } }.asCoroutineDispatcher()

    /** The voice thread. */
    val scope = CoroutineScope(SupervisorJob() + thread + CoroutineExceptionHandler { _, e -> Log.e(TAG, "call failed", e) })

    private var installed = false
    private var iceCache: Pair<IceConfig, Long>? = null
    private var joinSeq = 0
    private var mySessionId: String? = null
    private val ringTimers = HashMap<String, Job>()
    private var callingTimer: Job? = null
    private var company = false
    private var aloneTimer: Job? = null
    private var modeRetryAt = 0L
    /** Calls ignored here: they neither ring nor show until they are over, and the caller is not told. */
    private val ignoredCalls = HashSet<String>()

    private fun me(): String? = Nexa.users.meId

    // --- setup ---------------------------------------------------------------

    /** Hooks calls into the session's events, the mute buttons and the settings; once, at start. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        WebRtc.init(context)
        CallNotifications.init(context)
        scope.launch { Nexa.session.events.collect { e -> runCatching { onEvent(e) }.onFailure { Log.w(TAG, "voice event ${e.t}", it) } } }
        // A role or overwrite changing mid-call: a camera or share no longer
        // allowed stops, and push to talk comes in or goes as Use Voice Activity does
        scope.launch {
            Nexa.servers.byId.collect {
                val cur = Engine.current() ?: return@collect
                if (!CallPerms.mayStream(cur.channelId)) {
                    if (Voice.state.value.localVideo != null) Engine.toggleCamera()
                    if (Engine.sharing()) Engine.stopScreen()
                }
                Engine.applyTransmit()
            }
        }
        // mute and deafen: the sound, the output, the gate, and the server told (only once the join was announced)
        scope.launch {
            var prev = VoiceLocal.state.value
            VoiceLocal.state.collect { st ->
                // deafening mutes as well, and plays its own sound rather than both
                if (st.deafened != prev.deafened) Sounds.play(if (st.deafened) Sounds.Name.DEAFEN else Sounds.Name.UNDEAFEN)
                else if (st.muted != prev.muted) Sounds.play(if (st.muted) Sounds.Name.MUTE else Sounds.Name.UNMUTE)
                prev = st
                Engine.setOutputMuted(Engine.outputSilenced())
                Engine.applyTransmit()
                val cur = Engine.current()
                if (cur?.announced == true) sendState(cur.channelId)
                CallService.update()
            }
        }
        // the voice settings, applied in a call as they change
        scope.launch {
            var prev = Voice.settings
            Nexa.settings.doc.map { Voice.settings }.distinctUntilChanged().collect { v ->
                val before = prev
                prev = v
                if (Engine.current() == null) return@collect
                fun changed(k: String) = before[k] != v[k]
                if (changed("noiseMode") || changed("echoCancellation") || changed("autoGain") || changed("noiseSuppression")) Engine.refreshMic()
                if (changed("outputVolume")) Engine.setMasterVolume(Voice.settingInt("outputVolume", 100))
                if (changed("inputVolume")) Engine.applyInputGain()
                if (changed("mode")) Engine.applyTransmit()
            }
        }
        scope.launch { Voice.mix.collect { Engine.applyMix() } }
        // one ring for however many calls are coming in; Do Not Disturb shows a call but keeps it quiet
        scope.launch {
            var was = false
            Voice.ringing.collect { r ->
                val now = r.isNotEmpty()
                if (now && !was && !doNotDisturb()) Sounds.startRinging()
                else if (!now && was) Sounds.stopRinging()
                was = now
                IncomingCalls.sync(r)
            }
        }
        scope.launch {
            Nexa.presence.byId.collect { if (doNotDisturb()) Sounds.stopRinging() }
        }
        // whose volumes these are follows whoever is signed in; signing out ends the call without a word
        scope.launch { Nexa.users.me.collect { Voice.useAccount(it?.id) } }
        scope.launch { Nexa.auth.state.collect { if (it is fish.nexa.data.AuthState.Anonymous || it is fish.nexa.data.AuthState.Locked) signOut() } }
        // a call that rang while the app was away shows its sheet when the app comes back
        scope.launch { Nexa.ui.visible.collect { IncomingCalls.sync(Voice.ringing.value) } }
    }

    // --- state -----------------------------------------------------------------

    /** The channel of this device's call, if any. */
    fun inVoice(): String? = Engine.current()?.channelId

    /** Whether this device is in, or joining, that conversation's call. */
    fun inCallOf(channelId: String): Boolean = inVoice() == channelId || Voice.state.value.joining == channelId

    fun throughSfu(): Boolean = Engine.throughSfu()

    /**
     * The whole state again, on every change; `resume` marks a rejoin after a
     * reconnect, and with `sfuSession` asks for the very call this device holds.
     */
    fun sendState(channelId: String?, resume: Boolean = false, sfuSession: String? = null) {
        val local = VoiceLocal.state.value
        val st = Voice.state.value
        Nexa.session.send("voice_state", buildJsonObject {
            if (channelId == null) put("channel_id", kotlinx.serialization.json.JsonNull) else put("channel_id", channelId)
            put("self_mute", local.muted)
            put("self_deaf", local.deafened)
            put("video", st.localVideo != null)
            put("streaming", st.localScreen != null)
            if (channelId != null) put("sfu", Engine.throughSfu())
            // what it can watch besides VP8: a phone shares its screen in H.264 only when everyone can
            if (channelId != null && VideoCap.receivesH264) put("codecs", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("h264")) })
            if (resume) put("resume", true)
            if (resume && sfuSession != null) put("sfu_session", sfuSession)
        })
    }

    /** The ICE servers, and whether calls go through the SFU here; never guessed when it cannot be asked. */
    suspend fun iceConfig(): IceConfig? {
        iceCache?.let { (c, at) -> if (System.currentTimeMillis() - at < ICE_CACHE_MS) return c }
        return try {
            VoiceApi.ice().also { iceCache = it to System.currentTimeMillis() }
        } catch (e: Exception) {
            Log.w(TAG, "could not fetch the call settings", e)
            null
        }
    }

    /** Whether this server takes guests in calls; null before the call settings arrived. */
    fun guestLinksOn(): Boolean? = iceCache?.first?.guests

    private fun doNotDisturb(): Boolean = me()?.let { Nexa.presence.byId.value[it]?.status == "dnd" } == true

    private fun isConversation(channelId: String): Boolean = Nexa.channels.get(channelId)?.type.let { it == "dm" || it == "group_dm" }

    /** The accounts in a call other than this one; a guest waiting in it is nobody picking up. */
    private fun othersIn(channelId: String): Int = Voice.participants(channelId).count { it.userId != me() && !Voice.isGuest(it) }

    // --- joining and leaving --------------------------------------------------------

    /** Joins a voice channel or a DM or group's call (leaving the current one first); `video` turns the camera on once in. */
    fun join(channelId: String, video: Boolean = false) {
        scope.launch {
            joinNow(channelId)
            if (video && inVoice() == channelId && Voice.state.value.localVideo == null) Engine.toggleCamera()
        }
    }

    private suspend fun joinNow(channelId: String) {
        val myId = me() ?: return
        // a muted account is in no call: saying so here beats a refusal after a ring
        mutedSentence()?.let {
            Nexa.ui.toast(it, "error")
            return
        }
        if (inVoice() == channelId) return
        // moving says so first: if the new channel refuses us, the server must not keep us in the old one
        if (inVoice() != null) leaveNow(true)
        val seq = ++joinSeq
        Voice.update { it.copy(error = null, joining = channelId) }
        clearRing(channelId, false)
        val refuse = { message: String ->
            Voice.update { it.copy(joining = null, error = message) }
            Nexa.ui.toast(message, "error")
            CallService.update()
        }
        CallService.update()
        val ice = iceConfig()
        if (seq != joinSeq) return
        if (ice == null) return refuse(t("session.couldNotReachTheServerTo"))
        company = false
        stopAloneTimer()
        val announced = Engine.join(channelId, ice, { seq == joinSeq }, refuse)
        if (!announced || seq != joinSeq) {
            if (seq == joinSeq && Engine.current() == null) {
                Voice.update { it.copy(joining = null) }
                CallService.update()
            }
            return
        }
        Log.i(TAG, "joined $channelId as $myId (${if (ice.sfu) "SFU" else "peer to peer"})")
        // a channel without Use Voice Activity is push to talk only, whatever the settings say
        if (Voice.setting("mode") != "ptt" && !CallPerms.allowsVoiceActivity(channelId)) Nexa.ui.toast(t("voice.callPerms.pttRequiredButton"))
        if (isConversation(channelId) && othersIn(channelId) == 0) {
            startCallingTone(channelId)
            CallRecord.started(channelId)
        } else checkAlone(channelId)
    }

    /** Leaves; tells the server unless the connection is already gone. */
    fun leave(notify: Boolean = true) {
        scope.launch { leaveNow(notify) }
    }

    private fun leaveNow(notify: Boolean) {
        joinSeq++
        val was = Engine.current()
        Engine.leave(notify)
        if (was != null) stopCallingTone()
        stopAloneTimer()
        Voice.resetSession()
        CallService.update()
        Pip.update()
    }

    // --- the calling tone and the alone timer ----------------------------------------------

    /** Calling a DM or group nobody else is in: the outgoing tone, and a minute before the call is given up. */
    private fun startCallingTone(channelId: String) {
        Sounds.startCalling()
        Voice.update { it.copy(calling = channelId) }
        CallService.update()
        callingTimer?.cancel()
        callingTimer = scope.launch {
            delay(RING_MS)
            callingTimer = null
            stopCallingTone()
            CallRecord.timedOut(channelId)
            if (inVoice() == channelId && othersIn(channelId) == 0) {
                Nexa.ui.toast(callOutcome(channelId, declined = false))
                leaveNow(true)
            }
        }
    }

    private fun stopCallingTone() {
        callingTimer?.cancel()
        callingTimer = null
        Sounds.stopCalling()
        if (Voice.state.value.calling != null) {
            Voice.update { it.copy(calling = null) }
            CallService.update()
        }
    }

    /** What became of a call nobody picked up, in words: a DM names the person called. */
    private fun callOutcome(channelId: String, declined: Boolean): String {
        val ch = Nexa.channels.get(channelId)
        val other = if (ch?.type == "dm") ch.recipientIds.firstOrNull { it != me() } else null
        val name = other?.let { Nexa.users.get(it)?.name }.orEmpty()
        if (declined) return t("voice.call.declined", "name" to name)
        return if (name.isNotEmpty()) t("voice.call.noAnswer", "name" to name) else t("voice.call.noAnswerGroup")
    }

    /**
     * A conversation's call people talked in, that everyone else then left,
     * waits a minute for them to come back, then hangs up by itself. A
     * server's voice channel is a place to wait in and never does.
     */
    private fun checkAlone(channelId: String) {
        if (!isConversation(channelId)) return
        if (othersIn(channelId) > 0) {
            company = true
            stopAloneTimer()
            return
        }
        if (!company || aloneTimer != null) return
        Voice.update { it.copy(leftAlone = channelId) }
        aloneTimer = scope.launch {
            delay(ALONE_MS)
            aloneTimer = null
            if (inVoice() != channelId || othersIn(channelId) > 0) return@launch
            Nexa.ui.toast(t("voice.call.callEnded"))
            leaveNow(true)
        }
    }

    private fun stopAloneTimer() {
        aloneTimer?.cancel()
        aloneTimer = null
        if (Voice.state.value.leftAlone != null) Voice.update { it.copy(leftAlone = null) }
    }

    // --- rings -----------------------------------------------------------------------

    /**
     * Stops ringing for a channel. `missed` says the call ended without anyone
     * here answering it, which someone away is told by a notification and
     * someone looking by a toast.
     */
    private fun clearRing(channelId: String, missed: Boolean) {
        val was = Voice.ringing.value[channelId]
        Voice.setRinging(channelId, null)
        ringTimers.remove(channelId)?.cancel()
        if (was == null) return
        if (missed) CallNotifications.missed(channelId, was.from) else CallNotifications.cancelRing(channelId)
    }

    /** Silences an incoming call here without answering or declining it. */
    fun ignore(channelId: String) {
        scope.launch {
            ignoredCalls += channelId
            clearRing(channelId, false)
        }
    }

    /** Declines an incoming call: the caller is told. */
    fun decline(channelId: String) {
        scope.launch {
            clearRing(channelId, false)
            runCatching { VoiceApi.decline(channelId) }
        }
    }

    /** Answers: the conversation opens and its call is joined. */
    fun accept(channelId: String) {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Nav.go(Route.Dm(channelId)) }
            joinNow(channelId)
        }
    }

    // --- gateway events ----------------------------------------------------------------

    private fun JsonObject?.str(k: String): String? = (this?.get(k) as? JsonPrimitive)?.contentOrNull

    private suspend fun onEvent(e: GatewayEvent) {
        val d = e.d as? JsonObject
        when (e.t) {
            "ready" -> onReady()
            "voice_state" -> {
                val vs = runCatching { wireJson.decodeFromJsonElement(VoiceState.serializer(), e.d!!) }.getOrNull() ?: return
                onVoiceState(vs)
            }
            "voice_signal" -> {
                val from = d.str("from") ?: return
                val payload = d?.get("payload") as? JsonObject ?: return
                Engine.onSignal(from, d.str("channel_id"), payload)
            }
            "voice_resumed" -> Engine.onResumed(d.str("channel_id"), (d?.get("kept") as? JsonPrimitive)?.booleanOrNull ?: true)
            "voice_error" -> onVoiceError(d.str("code").orEmpty(), d.str("message").orEmpty())
            "call_ring" -> {
                val ch = d.str("channel_id") ?: return
                val from = d.str("from") ?: return
                // a caller who reconnects rings again; an ignored call stays quiet
                if (from == me() || inVoice() == ch || ch in ignoredCalls) return
                Voice.setRinging(ch, from)
                CallNotifications.ring(ch, from)
                ringTimers.remove(ch)?.cancel()
                ringTimers[ch] = scope.launch {
                    delay(RING_MS)
                    clearRing(ch, true)
                }
            }
            "call_end" -> onCallEnd(d.str("channel_id") ?: return, d.str("reason").orEmpty(), d.str("user_id"))
            "message_create" -> runCatching { wireJson.decodeFromJsonElement(Message.serializer(), e.d!!) }.getOrNull()?.let { CallRecord.line(it) }
            "channel_delete" -> {
                val id = d.str("id") ?: return
                // a channel that disappears takes its call with it; the server does not end a call when a DM is closed
                if (inVoice() == id) leaveNow(true)
            }
        }
    }

    private fun onVoiceState(vs: VoiceState) {
        val myId = me()
        // a share that ended, or left with its sharer, leaves nothing to watch
        if (!(vs.channelId != null && vs.streaming) && vs.userId in Voice.state.value.watching) Engine.setWatching(vs.userId, false)
        CallRecord.observeAll()
        if (ignoredCalls.isNotEmpty()) ignoredCalls.removeAll { Voice.participants(it).isEmpty() }
        val cur = inVoice()
        if (vs.userId == myId) {
            // the server keeps one call per account and tells all its devices; another device's session is that device's call
            val elsewhere = mySessionId != null && vs.sessionId.isNotEmpty() && vs.sessionId != mySessionId
            val ch = vs.channelId
            if (ch != null) {
                if (elsewhere) {
                    clearRing(ch, false)
                    if (cur != null) {
                        leaveNow(false)
                        Nexa.ui.toast(t("session.yourCallMovedToAnotherDevice"))
                    } else if (Voice.state.value.mine != null) Voice.update { it.copy(mine = null) }
                    return
                }
                // a join this device has since given up on; its leave is on the way
                val was = Voice.state.value.mine
                if (cur == null || !Engine.onMine(vs)) return
                moderatorNotice(was, vs, cur)
                checkAlone(ch)
                CallService.update()
                return
            }
            if (cur == null || (vs.oldChannelId != null && vs.oldChannelId != cur)) return
            if (vs.reason == "moved") {
                leaveNow(false)
                Nexa.ui.toast(t("session.yourCallMovedToAnotherDevice"))
            } else if (vs.reason == "disconnected") {
                leaveNow(false)
                Nexa.ui.toast(t("voice.moderation.youWereDisconnected"))
            } else if (!elsewhere) {
                // put out by the server: a kick, a deleted channel, a moderator
                leaveNow(false)
            }
            return
        }
        if (cur == null) return
        when (Engine.onOther(vs)) {
            "joined" -> {
                // someone picked up
                stopCallingTone()
                Sounds.play(Sounds.Name.JOIN)
            }
            "left" -> Sounds.play(Sounds.Name.LEAVE)
        }
        checkAlone(cur)
    }

    /**
     * Says what a moderator just did to this account's own call: moved it to
     * another channel, muted or deafened it, or lifted either (session.ts
     * moderatorNotice). A channel that lets nobody speak mutes too, but that
     * is the channel's doing and is said nowhere here.
     */
    private fun moderatorNotice(was: VoiceState?, now: VoiceState, cur: String) {
        val ch = now.channelId ?: return
        if (ch != cur) {
            val name = Nexa.channels.get(ch)?.name
            Nexa.ui.toast(if (name != null) t("voice.moderation.youWereMovedTo", "channel" to name) else t("voice.moderation.youWereMoved"))
            return
        }
        if (was == null || was.channelId != ch) return
        fun modMute(v: VoiceState) = v.serverMute && !v.suppress
        if (modMute(now) != modMute(was)) Nexa.ui.toast(if (modMute(now)) t("voice.moderation.youWereMuted") else t("voice.moderation.youWereUnmuted"))
        if (now.serverDeaf != was.serverDeaf) Nexa.ui.toast(if (now.serverDeaf) t("voice.moderation.youWereDeafened") else t("voice.moderation.youWereUndeafened"))
    }

    private suspend fun onVoiceError(code: String, message: String) {
        if (code in SIGNAL_ERRORS) {
            Log.w(TAG, "a signal was refused: $code")
            return
        }
        val cur = inVoice()
        if (code in MODE_ERRORS && cur != null && System.currentTimeMillis() - modeRetryAt > 30_000) {
            // ask the server again how calls connect, and join that way
            modeRetryAt = System.currentTimeMillis()
            iceCache = null
            leaveNow(false)
            joinNow(cur)
            return
        }
        val said = if (code == "account_muted") mutedSentence() ?: message else message
        Voice.update { it.copy(joining = null, error = said) }
        Nexa.ui.toast(said, "error")
        // the server may think we are in the call: telling it costs one frame, not telling it leaves a stuck tile
        if (cur != null && Voice.state.value.mine == null) leaveNow(true)
    }

    private fun onCallEnd(ch: String, reason: String, who: String?) {
        if (reason == "ended") {
            // the call is over: a ring still going here was never answered
            clearRing(ch, true)
            ignoredCalls.remove(ch)
            CallRecord.ended(ch)
            return
        }
        // answered or declined on another of this account's devices
        if (who == me()) clearRing(ch, false)
        if (reason != "declined" || who == null || who == me()) return
        CallRecord.declined(ch, who)
        if (inVoice() != ch || Voice.state.value.calling != ch) return
        val type = Nexa.channels.get(ch)?.type
        // the one person called said no, or everyone in a group did: the call is given up, and says why
        if (type == "dm" || (type == "group_dm" && CallRecord.allDeclined(ch))) {
            stopCallingTone()
            Nexa.ui.toast(if (type == "dm") callOutcome(ch, declined = true) else t("voice.call.everyoneDeclined"))
            leaveNow(true)
        }
    }

    private fun onReady() {
        mySessionId = Nexa.misc.sessionId.value
        // a reconnect may follow a restart that changed how calls connect
        iceCache = null
        val states = Nexa.voiceStates.byUser.value
        // a call that ended while the gateway was away would ring on for its full minute
        for (ch in Voice.ringing.value.keys.toList()) if (states.values.none { it.channelId == ch }) clearRing(ch, true)
        CallRecord.afterReady { inVoice() == it }
        val cur = inVoice()
        if (cur != null && Nexa.channels.get(cur) == null) {
            leaveNow(true)
            return
        }
        Engine.onReady()
    }

    // --- in the call ---------------------------------------------------------------------

    fun toggleCamera() = scope.launch { Engine.toggleCamera() }
    fun flipCamera() = scope.launch { Engine.setCameraFront(!Voice.state.value.localFront) }
    fun setCameraFront(front: Boolean) = scope.launch { Engine.setCameraFront(front) }
    fun retryMic() = scope.launch { Engine.retryMic() }
    fun holdToTalk(on: Boolean) = scope.launch { Engine.setPttHeld(on) }
    fun stopScreenShare() = scope.launch { Engine.stopScreen() }

    /** Shares the screen at a quality from the share sheet, once the system said yes. */
    fun startScreenShare(permission: android.content.Intent, plan: VideoCap.SharePlan) = scope.launch { Engine.startScreen(permission, plan) }

    fun setWatching(userId: String, on: Boolean) = scope.launch { Engine.setWatching(userId, on) }
    fun setUserVolume(userId: String, pct: Int) = Voice.setVolume(userId, pct)
    fun setStreamVolume(userId: String, pct: Int) = Voice.setStreamVolume(userId, pct)
    fun setUserLocalMute(userId: String, on: Boolean) = Voice.setLocalMute(userId, on)
    fun setStreamLocalMute(userId: String, on: Boolean) = Voice.setStreamMute(userId, on)

    /** The network is back: whatever is down tries again now. */
    fun nudge() = scope.launch { Engine.nudge() }

    /** The guest link sheet for a call (from the conversation's menu). */
    fun openGuestLink(channelId: String) = fish.nexa.ui.voice.openGuestLink(channelId)

    /** Signing out: the call ends without a word to the server (the socket is going), and nothing of it is kept. */
    fun signOut() = scope.launch {
        leaveNow(false)
        for (ch in Voice.ringing.value.keys.toList()) clearRing(ch, false)
        ignoredCalls.clear()
        CallRecord.clear()
        iceCache = null
    }

    // --- an account mute (lib/moderation.ts mutedSentence) ---------------------------------

    /** The sentence for an account mute in force, or null when there is none. */
    private fun mutedSentence(): String? {
        val mute = fish.nexa.data.NexaInbox.mute() ?: return null
        val expires = mute.expiresAt
        val now = System.currentTimeMillis()
        if (expires == null) return t("nexa.muted.toastPermanent")
        return t("nexa.muted.toast", "when" to fish.nexa.ui.Time.relativeTo(expires, now))
    }
}
