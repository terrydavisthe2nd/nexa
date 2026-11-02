package fish.nexa.ui.profile

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import fish.nexa.Nexa
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The web's soundcloudWidget.ts: SoundCloud's own player, kept out of sight
 * in a web view no screen shows and driven from Nexa's music card. Its
 * widget takes play, pause, seek and volume as messages and tells how far it
 * has got. It is served through this server and sandboxed in a frame, as on
 * the web, so SoundCloud never learns who is listening. SongPreviews decides
 * when it plays.
 */
object SoundCloudWidget {
    interface Events {
        fun loading()
        fun playing()
        fun paused()
        fun progress(at: Float, seconds: Float)
        fun ended()
        fun failed()
    }

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var events: Events? = null
    private var duration = 0f
    private var ready = false
    private val pending = mutableListOf<String>()
    private val readyTimeout = Runnable { if (!ready) events?.failed() }

    val active: Boolean get() = view != null

    /** The page around the widget: it relays the widget's messages to the app and the app's to the widget. */
    private fun page(src: String): String {
        val quoted = src.replace("&", "&amp;").replace("\"", "&quot;")
        return "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;height:100%}" +
            "iframe{display:block;border:0;width:100%;height:100%}</style>" +
            "<script>addEventListener('message',function(e){var f=document.getElementById('p');" +
            "if(f&&e.source===f.contentWindow&&typeof e.data==='string')NexaWidget.heard(e.data)});" +
            "function send(m){var f=document.getElementById('p');if(f)f.contentWindow.postMessage(m,'*')}</script>" +
            "<iframe id=p src=\"$quoted\" sandbox=\"allow-scripts allow-presentation\" allow=\"autoplay; encrypted-media\" referrerpolicy=no-referrer></iframe>"
    }

    private fun post(msg: String) {
        val v = view ?: return
        if (!ready) {
            pending += msg
            return
        }
        v.evaluateJavascript("send(${Json.encodeToString(String.serializer(), msg)})", null)
    }

    private fun command(method: String, value: Number? = null) =
        post(if (value == null) "{\"method\":\"$method\"}" else "{\"method\":\"$method\",\"value\":$value}")

    private fun heard(raw: String) {
        val on = events ?: return
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val method = runCatching { o["method"]?.jsonPrimitive?.content }.getOrNull()
        when (method) {
            "ready" -> {
                if (ready) return
                ready = true
                main.removeCallbacks(readyTimeout)
                for (name in listOf("play", "pause", "finish", "playProgress", "error")) post("{\"method\":\"addEventListener\",\"value\":\"$name\"}")
                post("{\"method\":\"getDuration\"}")
                pending.toList().also { pending.clear() }.forEach { post(it) }
            }
            "getDuration" -> runCatching { o["value"]?.jsonPrimitive?.doubleOrNull }.getOrNull()?.takeIf { it > 0 }?.let { duration = (it / 1000).toFloat() }
            "play" -> on.playing()
            "pause" -> on.paused()
            "finish" -> on.ended()
            "error" -> on.failed()
            "playProgress" -> {
                val v = o["value"] as? JsonObject ?: return
                val cur = (runCatching { v["currentPosition"]?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: 0.0) / 1000
                val rel = runCatching { v["relativePosition"]?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: 0.0
                if (duration <= 0f && rel > 0) duration = (cur / rel).toFloat()
                on.progress(rel.toFloat().coerceIn(0f, 1f), duration)
            }
        }
    }

    /** Starts a SoundCloud widget (its address through this server) at a volume from 0 to 1. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun start(src: String, volume: Float, on: Events) {
        stop()
        events = on
        duration = 0f
        ready = false
        pending += "{\"method\":\"setVolume\",\"value\":${Math.round(volume * 100)}}"
        pending += "{\"method\":\"play\"}"
        val v = WebView(Nexa.app)
        v.settings.javaScriptEnabled = true
        v.settings.mediaPlaybackRequiresUserGesture = false
        v.settings.allowFileAccess = false
        v.settings.allowContentAccess = false
        v.settings.setSupportMultipleWindows(false)
        v.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        v.addJavascriptInterface(object {
            @JavascriptInterface
            fun heard(data: String) {
                // a widget's message comes on the web view's own thread
                main.post { if (view === v) this@SoundCloudWidget.heard(data) }
            }
        }, "NexaWidget")
        // a web view nothing shows has its renderer let go at once; this one is playing
        v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // laid out at a player's size, so the widget picks its usual layout; never on screen
        v.layout(0, 0, 640, 332)
        view = v
        on.loading()
        v.loadDataWithBaseURL(Nexa.api.baseUrl + "/", page(src), "text/html", "utf-8", null)
        main.postDelayed(readyTimeout, 15_000)
    }

    fun play() = command("play")
    fun pause() = command("pause")
    fun seek(at: Float) {
        if (duration > 0f) command("seekTo", Math.round(at * duration * 1000))
    }
    fun volume(v: Float) = command("setVolume", Math.round(v * 100))

    fun stop() {
        main.removeCallbacks(readyTimeout)
        view?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        view = null
        events = null
        ready = false
        pending.clear()
        duration = 0f
    }
}
