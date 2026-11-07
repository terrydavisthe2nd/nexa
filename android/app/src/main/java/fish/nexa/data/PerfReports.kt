package fish.nexa.data

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import fish.nexa.Nexa
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Send performance reports: how long things take on this phone, for the
 * server's profiler (internal/perf) to find what makes Nexa slow. The web's
 * lib/perfReports.ts does the same, with the same bucket edges and stages.
 *
 * Everything is summed here into histograms and sent every few minutes while
 * the app is on screen, and once as it leaves the screen (when the radio is
 * awake anyway), never from the background. A report says the phone's tier
 * (from cores and memory) and its kind of connection, and the API routes it
 * timed with every id taken out; it never carries message content, names, ids
 * or anything typed. The server keeps none of it per account. With the synced
 * `perfReports` setting off nothing is measured, and anything gathered is
 * dropped.
 */
object PerfReports {
    /** The profiler's bucket edges in milliseconds; must equal `bounds` in internal/perf/perf.go. */
    val BOUNDS = doubleArrayOf(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0, 50.0, 75.0, 100.0, 150.0, 200.0, 300.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 7500.0, 10000.0, 15000.0, 30000.0, 60000.0)

    private const val SEND_EVERY_MS = 5 * 60_000L
    private const val MAX_ROUTES = 120
    private const val MAX_MS = 10 * 60_000.0
    /** A frame slower than this counts as slow: two frames at 60 Hz. */
    private const val SLOW_FRAME_MS = 32.0

    private class Hist {
        val h = LongArray(BOUNDS.size + 1)
        var n = 0L
        var errs = 0L
        var sum = 0.0
        var max = 0.0
        fun add(raw: Double, failed: Boolean) {
            val ms = raw.coerceIn(0.0, MAX_MS)
            var i = 0
            while (i < BOUNDS.size && ms > BOUNDS[i]) i++
            h[i]++
            n++
            sum += ms
            if (ms > max) max = ms
            if (failed) errs++
        }
        fun wire(into: kotlinx.serialization.json.JsonObjectBuilder) {
            val last = h.indexOfLast { it != 0L }
            into.put("e", errs)
            into.put("sum", Math.round(sum * 100) / 100.0)
            into.put("max", Math.round(max * 100) / 100.0)
            into.put("h", JsonArray((0..last).map { JsonPrimitive(h[it]) }))
        }
    }

    private val lock = Any()
    private var stages = HashMap<String, Hist>()
    private var routes = HashMap<String, Hist>()
    /** Every frame drawn since the last report, slow ones included, for the share that were slow. */
    private var frames = 0L
    private var firstReady = false
    /** Whether the app started with an account signed in and unlocked, so startup is not the time someone took to sign in. */
    @Volatile private var startupCounts = false

    // runCatching: Api and Gateway also run in unit tests, where the app is not set up
    private fun on(): Boolean = runCatching { Nexa.api.token != null && Nexa.settings.perfReports }.getOrDefault(false)

    private fun visible(): Boolean = runCatching { Nexa.ui.visible.value }.getOrDefault(false)

    /** Records a stage only while the app is on screen, where the wait is one someone saw. */
    fun onScreen(name: String, ms: Double) {
        if (visible()) stage(name, ms)
    }

    /** Records how long a stage took. */
    fun stage(name: String, ms: Double, failed: Boolean = false) {
        if (!ms.isFinite() || !on()) return
        synchronized(lock) { stages.getOrPut(name) { Hist() }.add(ms, failed) }
    }

    /** The route a request went to, with every id taken out (the web's routeOf). */
    fun routeOf(path: String): String {
        val bare = path.substringBefore('?').substringBefore('#')
        return "/api/v1" + bare.replace(Regex("""/\d+(?=/|$)"""), "/{id}")
    }

    /** Records an API request's whole round trip, as Api.request saw it. */
    fun request(method: String, path: String, ms: Double, failed: Boolean) {
        if (path == "/perf" || path.startsWith("/media/") || path.startsWith("http") || !on()) return
        val key = method + " " + routeOf(path)
        synchronized(lock) {
            val h = routes[key] ?: (if (routes.size >= MAX_ROUTES) return else Hist().also { routes[key] = it })
            h.add(ms, failed)
        }
    }

    /** Marks a gateway ready: the first of the process ends startup, counted from when the process began. */
    fun sawReady() {
        synchronized(lock) {
            if (firstReady) return
            firstReady = true
        }
        // a process the connection service started in the background is not an app anyone opened
        if (startupCounts && visible()) stage("startup", (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()).toDouble())
    }

    /** Whether ready has come at least once, so a conversation opened before it is counted as startup instead. */
    fun pastStartup(): Boolean = synchronized(lock) { firstReady }

    /** A sign-in or unlock screen came first: startup would be the time someone took to type. */
    fun notStartup() {
        startupCounts = false
    }

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (!on()) return@OnFrameMetricsAvailableListener
        val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        synchronized(lock) {
            frames++
            if (ms > SLOW_FRAME_MS) stages.getOrPut("slow_frame") { Hist() }.add(ms, false)
        }
    }

    private var frameThread: android.os.HandlerThread? = null

    /** Watches the window's frames while it is drawn; nothing runs while nothing is drawn. */
    fun watchFrames(window: Window) {
        val t = frameThread ?: android.os.HandlerThread("perf-frames").also { it.start(); frameThread = it }
        runCatching { window.addOnFrameMetricsAvailableListener(frameListener, android.os.Handler(t.looper)) }
    }

    fun unwatchFrames(window: Window) {
        runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
    }

    private fun tier(context: Context): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val gb = info.totalMem / (1024.0 * 1024 * 1024)
        return when {
            am?.isLowRamDevice == true || cores <= 4 || gb < 3 -> "low"
            cores >= 8 && gb >= 7 -> "high"
            else -> "mid"
        }
    }

    private fun net(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }

    /** Sends what was gathered and starts afresh; a report that fails is dropped, never kept to pile up. */
    private suspend fun send() {
        val (s, r, f) = synchronized(lock) {
            val taken = Triple(stages, routes, frames)
            stages = HashMap(); routes = HashMap(); frames = 0
            taken
        }
        if (!on() || (s.isEmpty() && r.isEmpty())) return
        s["slow_frame"]?.errs = f
        val ctx = Nexa.app
        val body = buildJsonObject {
            put("tier", tier(ctx))
            put("net", net(ctx))
            put("stages", JsonArray(s.map { (name, h) -> buildJsonObject { put("name", name); h.wire(this) } }))
            put("api", JsonArray(r.map { (key, h) ->
                buildJsonObject {
                    put("method", key.substringBefore(' ')); put("path", key.substringAfter(' ')); h.wire(this)
                }
            }))
        }
        runCatching { Nexa.api.request("POST", "/perf", body as JsonObject) }
    }

    /** Starts the reporting loop; called once from the app's start. */
    fun start() {
        startupCounts = true
        Nexa.scope.launch {
            Nexa.auth.state.collect { a ->
                if (a is AuthState.Anonymous || a is AuthState.Locked || a is AuthState.PinLocked) notStartup()
            }
        }
        Nexa.scope.launch {
            var wasVisible = false
            Nexa.ui.visible.collect { visible ->
                // leaving the screen sends what waited, while the radio is awake anyway
                if (wasVisible && !visible) send()
                wasVisible = visible
            }
        }
        Nexa.scope.launch {
            while (true) {
                delay(SEND_EVERY_MS)
                // only on screen: a background phone's radio is left alone
                Nexa.ui.visible.first { it }
                send()
            }
        }
    }
}
