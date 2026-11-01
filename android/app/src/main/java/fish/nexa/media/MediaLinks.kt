package fish.nexa.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import fish.nexa.Nexa
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/**
 * lib/mediaLinks.ts: everything a link preview or a webhook shows comes
 * through Nexa's media proxy, as signed links that run out. A link close to
 * running out is re-signed (POST /proxy/refresh, in batches); an address that
 * is not a proxy address, or not this server's, is never loaded.
 */
object MediaLinks {
    private const val PROXY_PREFIX = "/media/proxy/"
    private const val MARGIN_MS = 5 * 60 * 1000L
    private const val BATCH = 100
    private const val GATHER_MS = 30L
    private const val RETRY_MS = 30_000L

    private val renewed = HashMap<String, String>()
    private val refused = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val queued = LinkedHashSet<String>()
    private val asking = HashSet<String>()
    private var timer: Job? = null
    private var retryTimer: Job? = null

    /** Bumped whenever a link was re-signed or refused, so what draws them looks again. */
    var version by mutableIntStateOf(0)
        private set

    private fun origin(): String = runCatching { URI(Nexa.api.baseUrl).let { "${it.scheme}://${it.authority}" } }.getOrDefault(Nexa.api.baseUrl)

    private fun proxyPath(url: String): String? {
        if (url.startsWith(PROXY_PREFIX)) return url
        return runCatching {
            val u = URI(url)
            if ("${u.scheme}://${u.authority}" == origin() && (u.rawPath ?: "").startsWith(PROXY_PREFIX)) u.rawPath + (u.rawQuery?.let { "?$it" } ?: "") else null
        }.getOrNull()
    }

    fun isProxied(url: String): Boolean = proxyPath(url) != null

    /** When a proxy link stops working: the seconds in its signature. */
    fun deadline(url: String): Long? {
        val path = proxyPath(url) ?: return null
        val sig = path.removePrefix(PROXY_PREFIX).substringBefore('/')
        return Regex("^(\\d{9,12})-").find(sig)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
    }

    /** What a proxy link points at, whatever it was signed with (how GIF favourites are matched). */
    fun mediaKey(url: String): String {
        val path = proxyPath(url) ?: return url
        return path.removePrefix(PROXY_PREFIX).split('/').getOrNull(1) ?: url
    }

    private fun usableAsIs(url: String): Boolean {
        if (url.startsWith("data:")) return true
        if (isProxied(url)) {
            val d = deadline(url) ?: return false
            return d - System.currentTimeMillis() > MARGIN_MS
        }
        if (Regex("[\\\\\\s\\u0000-\\u001f\\u007f]").containsMatchIn(url)) return false
        return url.startsWith("/") && !url.startsWith("//") || runCatching { URI(url).let { "${it.scheme}://${it.authority}" } == origin() }.getOrDefault(false)
    }

    private fun absolute(url: String) = if (url.startsWith("/")) Nexa.api.baseUrl + url else url

    /**
     * The address to load for a link, absolute: a usable link as it is, a
     * re-signed one, null when it will never load (not a proxy link, or the
     * server refused it), or "" while it is being re-signed.
     */
    fun src(url: String?): String? = fresh(url)?.let { if (it.isEmpty()) it else absolute(it) }

    /**
     * src as the link itself, not made absolute: what a GIF favourite is sent
     * as, so the message carries a link that works now rather than the one it
     * was saved with (lib/mediaLinks.ts mediaSrc).
     */
    @Synchronized
    fun fresh(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (usableAsIs(url)) return url
        if (!isProxied(url)) return null
        renewed[url]?.let { if (usableAsIs(it)) return it }
        if (url in refused) return null
        failed[url]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return "" }
        ask(url)
        return ""
    }

    private fun ask(url: String) {
        if (url in queued || url in asking) return
        queued += url
        if (timer == null) timer = Nexa.scope.launch {
            delay(GATHER_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val batch: List<String>
        synchronized(this) {
            timer = null
            batch = queued.take(BATCH)
            for (u in batch) {
                queued.remove(u)
                asking += u
            }
            if (queued.isNotEmpty()) timer = Nexa.scope.launch {
                delay(GATHER_MS)
                flush()
            }
        }
        try {
            val res = Nexa.api.request("POST", "/proxy/refresh", buildJsonObject { put("urls", JsonArray(batch.map { JsonPrimitive(it) })) })
            val urls = (res as? JsonObject)?.get("urls") as? JsonObject
            synchronized(this) {
                for (u in batch) {
                    failed.remove(u)
                    val fresh = (urls?.get(u) as? JsonPrimitive)?.contentOrNull
                    if (fresh != null && isProxied(fresh)) renewed[u] = fresh else refused += u
                }
            }
        } catch (_: Exception) {
            synchronized(this) {
                val now = System.currentTimeMillis()
                for (u in batch) failed[u] = now
                if (retryTimer == null) retryTimer = Nexa.scope.launch {
                    delay(RETRY_MS)
                    synchronized(this@MediaLinks) { retryTimer = null }
                    version++
                }
            }
        } finally {
            synchronized(this) { for (u in batch) asking -= u }
            version++
        }
    }

    @Synchronized
    fun reset() {
        renewed.clear(); refused.clear(); failed.clear(); queued.clear(); asking.clear()
        timer?.cancel(); timer = null
        retryTimer?.cancel(); retryTimer = null
        version++
    }
}

/** useMediaSrc: re-read when links are re-signed. Null never loads; "" is still being re-signed. */
@Composable
fun mediaSrc(url: String?): String? {
    // reading the version makes this redraw when a link is re-signed or refused
    @Suppress("UNUSED_VARIABLE") val v = MediaLinks.version
    return MediaLinks.src(url)
}
