package fish.nexa.net

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The realtime socket, as web/src/gateway/client.ts runs it: identify as the
 * first frame, a heartbeat answered in time (90 s before ready, which the ack
 * queues behind), every reconnect a fresh identify and a full ready, and ops
 * sent while down kept for the next open. Frames are handed on in order; the
 * caller processes them off this thread.
 *
 * Unlike a browser's, this one runs on a battery: a radio stays at full power
 * for several seconds after every packet, so how often anything moves is what
 * counts. [setRest] says how much of the connection this device wants, the
 * heartbeat and the wait between reconnects follow it, and the server does its
 * side (internal/gateway/rest.go).
 */
class Gateway(
    private val api: Api,
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val onEvent: (t: String, d: JsonElement?) -> Unit,
) {
    enum class Status { IDLE, CONNECTING, OPEN, CLOSED }

    data class State(val status: Status, val ready: Boolean = false, val closeCode: Int? = null, val closeReason: String? = null)

    private val _state = MutableStateFlow(State(Status.IDLE))
    val state: StateFlow<State> = _state

    private var ws: WebSocket? = null
    private var closedByUser = false
    private var attempt = 0
    private var crowded = 0
    private var ready = false
    /** Frames that came in on this socket; moves on when the other end has answered. */
    @Volatile var framesSeen = 0L
        private set
    private var rest = REST_NONE
    private var connectingSince = 0L
    private var heartbeatJob: Job? = null
    private var ackJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectTimer: Job? = null
    private val queue = ArrayDeque<String>()
    private val lock = Any()

    private fun gatewayUrl(): String = api.baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/gateway"

    fun connect() = synchronized(lock) {
        val token = api.token ?: return
        closedByUser = false
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.cancel()
        ready = false
        connectingSince = SystemClock.elapsedRealtime()
        // a refusal for too many connections stays named while the next try is on its way
        val last = _state.value
        _state.value = if (last.closeCode == CLOSE_TOO_MANY) State(Status.CONNECTING, closeCode = last.closeCode, closeReason = last.closeReason) else State(Status.CONNECTING)
        lateinit var socket: WebSocket
        socket = api.http.newWebSocket(Request.Builder().url(gatewayUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                if (ws !== webSocket) return
                connectTimer?.cancel()
                webSocket.send(buildJsonObject { put("op", "identify"); put("d", buildJsonObject { put("token", token) }) }.toString())
                _state.value = State(Status.OPEN, ready = false)
                while (queue.isNotEmpty()) webSocket.send(queue.removeFirst())
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(lock) {
                    if (ws !== webSocket) return
                    framesSeen++
                }
                val frame = try {
                    wireJson.parseToJsonElement(text).jsonObject
                } catch (_: Exception) {
                    return
                }
                val t = (frame["t"] as? JsonPrimitive)?.contentOrNull ?: return
                if (t == "heartbeat_ack") return
                if (t == "ready") synchronized(lock) {
                    ready = true
                    attempt = 0
                    crowded = 0
                    _state.value = State(Status.OPEN, ready = true)
                    // the server keeps this per connection, so a new one is told again
                    if (rest != REST_NONE) sendRest()
                }
                onEvent(t, frame["d"])
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, 1006, t.message ?: "")
        })
        ws = socket
        connectTimer?.cancel()
        connectTimer = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            synchronized(lock) {
                if (ws === socket && _state.value.status == Status.CONNECTING) {
                    ws = null
                    socket.cancel()
                    scheduleReconnect(4000, "connect timeout")
                }
            }
        }
    }

    private fun closed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
        if (ws !== webSocket) return
        ws = null
        connectTimer?.cancel()
        stopHeartbeat()
        ready = false
        when {
            code == 4001 -> {
                _state.value = State(Status.CLOSED, closeCode = code, closeReason = reason)
                onEvent("session_expired", JsonObject(emptyMap()))
            }
            // Banned: the session still works for the ban screen and an appeal, so it
            // is kept, but there is nothing to reconnect to until the ban ends.
            code == 4003 -> {
                closedByUser = true
                _state.value = State(Status.CLOSED, closeCode = code, closeReason = reason)
                onEvent("account_banned", null)
            }
            closedByUser -> _state.value = State(Status.CLOSED, closeCode = code, closeReason = reason)
            else -> scheduleReconnect(code, reason)
        }
    }

    private fun scheduleReconnect(code: Int?, reason: String?) {
        connectTimer?.cancel()
        _state.value = State(Status.CLOSED, closeCode = code, closeReason = reason)
        // A call needs its connection back quickly; otherwise, with no network or out of
        // sight, trying every few seconds only keeps the radio searching. A network
        // coming back or the app coming to the front tries at once (wake).
        val longest = if (rest == REST_NONE || (rest == REST_SLOW && online())) MAX_BACKOFF_MS else MAX_RESTING_BACKOFF_MS
        var delayMs = min(longest.toDouble(), 1000 * 1.7.pow(attempt)) + Random.nextDouble() * 500
        if (code == CLOSE_TOO_MANY) {
            delayMs = min(MAX_CROWDED_BACKOFF_MS.toDouble(), CROWDED_BACKOFF_MS * 2.0.pow(crowded)) + Random.nextDouble() * 5000
            crowded++
        } else if (code == 4008) {
            delayMs = maxOf(delayMs, RATE_LIMITED_BACKOFF_MS.toDouble())
        }
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs.toLong())
            synchronized(lock) { reconnectJob = null }
            if (!closedByUser) connect()
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        runHeartbeat()
    }

    /** The beat at the current pace. An answer being waited for goes on being waited for. */
    private fun runHeartbeat() {
        heartbeatJob?.cancel()
        val every = when (rest) {
            REST_NONE -> HEARTBEAT_MS
            REST_SLOW -> SLOW_HEARTBEAT_MS
            else -> DEEP_HEARTBEAT_MS
        }
        heartbeatJob = scope.launch {
            while (true) {
                delay(every)
                ping()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        ackJob?.cancel()
        ackJob = null
    }

    /** A heartbeat now; if nothing at all comes back in time, the socket is dead. */
    private fun ping() = synchronized(lock) {
        val socket = ws ?: return
        if (_state.value.status != Status.OPEN) return
        socket.send("{\"op\":\"heartbeat\"}")
        if (ackJob != null) return
        val seen = framesSeen
        // a radio waking on a poor signal takes a while, and a call is the only thing that cannot wait
        val limit = if (!ready) READY_ACK_TIMEOUT_MS else if (rest == REST_NONE) ACK_TIMEOUT_MS else RESTING_ACK_TIMEOUT_MS
        ackJob = scope.launch {
            delay(limit)
            synchronized(lock) {
                ackJob = null
                if (ws !== socket || framesSeen > seen) return@launch
                ws = null
                stopHeartbeat()
                ready = false
                socket.close(4000, "heartbeat timeout")
                socket.cancel()
                scheduleReconnect(1006, "heartbeat timeout")
            }
        }
    }

    /** Called when the network comes back or the app returns to the foreground. */
    fun wake() = synchronized(lock) {
        if (closedByUser) return
        when (_state.value.status) {
            Status.OPEN -> ping()
            Status.CONNECTING -> if (SystemClock.elapsedRealtime() - connectingSince > CONNECT_TIMEOUT_MS) {
                attempt = 0
                connect()
            }
            Status.CLOSED, Status.IDLE -> if (reconnectJob != null || _state.value.status == Status.CLOSED) {
                attempt = 0
                connect()
            }
        }
    }

    /** How much of the connection this device wants right now (REST_*). */
    fun setRest(level: Int) = synchronized(lock) {
        if (rest == level) return
        rest = level
        if (_state.value.status != Status.OPEN) return
        runHeartbeat()
        if (ready) sendRest()
    }

    private fun sendRest() {
        ws?.send(buildJsonObject { put("op", "rest"); put("d", buildJsonObject { put("level", rest) }) }.toString())
    }

    fun send(op: String, d: JsonElement? = null) = synchronized(lock) {
        val frame = if (d == null) "{\"op\":\"$op\"}" else buildJsonObject { put("op", op); put("d", d) }.toString()
        val socket = ws
        if (socket != null && _state.value.status == Status.OPEN) {
            socket.send(frame)
            return
        }
        // where a caret is, like typing, is only worth saying now; and a call's
        // ops are not replayed onto a new socket: the call resumes from ready
        if (op == "typing" || op == "note_cursor" || op == "voice_state" || op == "voice_signal") return
        queue.addLast(frame)
        if (queue.size > MAX_QUEUE) queue.removeFirst()
    }

    /** Signs out or stops: no reconnect, nothing kept. */
    fun close() = synchronized(lock) {
        closedByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        connectTimer?.cancel()
        stopHeartbeat()
        queue.clear()
        ws?.close(1000, "client closed")
        ws = null
        ready = false
        _state.value = State(Status.CLOSED, closeCode = 1000, closeReason = "client closed")
    }

    /** A fresh ready at once (after unlocking the app). */
    fun restart() = synchronized(lock) {
        ws?.close(4000, "restart")
        ws = null
        attempt = 0
        connect()
    }

    val isReady get() = _state.value.ready

    companion object {
        /** The server refused: too many connections from this network or account, or it is full (internal/gateway/hub.go). */
        const val CLOSE_TOO_MANY = 4009
        /** The whole connection: in a call. */
        const val REST_NONE = 0
        /** Everything, without keeping the radio up: on screen. */
        const val REST_SLOW = 1
        /** Only what is worth waking for straight away: out of sight. */
        const val REST_DEEP = 2

        const val HEARTBEAT_MS = 25_000L
        const val SLOW_HEARTBEAT_MS = 60_000L
        /** Out of sight the server's pings already show the line works, and the phone sleeps through most of this anyway (ConnectionService asks now and then). */
        const val DEEP_HEARTBEAT_MS = 5 * 60_000L
        const val ACK_TIMEOUT_MS = 8_000L
        const val RESTING_ACK_TIMEOUT_MS = 20_000L
        const val READY_ACK_TIMEOUT_MS = 90_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_BACKOFF_MS = 12_000L
        const val MAX_RESTING_BACKOFF_MS = 5 * 60_000L
        const val CROWDED_BACKOFF_MS = 30_000L
        const val MAX_CROWDED_BACKOFF_MS = 5 * 60_000L
        const val RATE_LIMITED_BACKOFF_MS = 5_000L
        const val MAX_QUEUE = 64

        fun typing(channelId: String, stop: Boolean = false): JsonObject = buildJsonObject {
            put("channel_id", channelId)
            if (stop) put("stop", true)
        }
    }
}
