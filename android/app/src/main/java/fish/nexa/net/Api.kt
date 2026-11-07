package fish.nexa.net

import android.os.Build
import fish.nexa.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What a request that did not work says (web/src/api/client.ts ApiError):
 * the HTTP status (0 for no answer at all), the server's machine-readable
 * code and its sentence, and how long to wait when it was too fast.
 */
class ApiError(
    val status: Int,
    val code: String,
    message: String,
    val retryAfter: Int? = null,
    val details: JsonObject? = null,
    /** On a refusal for want of a captcha, the action one has to be solved for. */
    val captchaAction: String? = null,
) : Exception(message) {
    /** No answer came back. The app's own errors (a captcha cancelled, say) are status 0 too, with codes of their own. */
    val isNetwork get() = status == 0 && (code == "network" || code == "timeout")
    /** Worth trying again later rather than telling the person it was refused. */
    val isTransient get() = status == 0 || status == 408 || status == 429 || status >= 500
}

/** The HTTP side of chatd: one client, the bearer token, the error envelope. */
class Api(val baseUrl: String) {
    @Volatile var token: String? = null

    /** A 401 `unauthorized` on a request that carried the token: the session is gone. */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    /**
     * Solves a captcha for an action the server named (X-Captcha-Action) on a
     * request sent without one; null when none can be solved here. Set by Auth.
     */
    @Volatile var captchaSolver: (suspend (action: String) -> String?)? = null

    val userAgent: String = "NexaAndroid/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /** Uploads and downloads: no overall deadline (a big file takes as long as it takes). */
    val transfer: OkHttpClient = http.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun url(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/media/") || path.startsWith("/api/") || path.startsWith("/yt/") -> baseUrl + path
        else -> "$baseUrl/api/v1$path"
    }

    private val JSON_TYPE = "application/json".toMediaType()

    fun requestBuilder(path: String, auth: Boolean = true): Request.Builder {
        val b = Request.Builder().url(url(path)).header("Accept", "application/json")
        val t = token
        if (auth && t != null) b.header("Authorization", "Bearer $t")
        return b
    }

    /** Sends a JSON request and returns the parsed body (JsonNull for 204). */
    suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        captcha: String? = null,
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
    ): JsonElement {
        val rb: RequestBody? = when {
            body != null -> body.toString().toRequestBody(JSON_TYPE)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val b = requestBuilder(path, auth).method(method, rb)
        // tokens are Turnstile's; the server checks unmarked ones with hCaptcha, kept for older apps
        if (captcha != null) b.header("X-Captcha-Token", captcha).header("X-Captcha-Provider", "turnstile")
        for ((k, v) in headers) b.header(k, v)
        val began = android.os.SystemClock.elapsedRealtimeNanos()
        fun timed(failed: Boolean) = fish.nexa.data.PerfReports.request(method, path, (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1e6, failed)
        try {
            return execute(http, b.build(), auth && token != null).also { timed(false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            timed(true)
            throw e
        } catch (e: ApiError) {
            timed(e.status == 0 || e.status >= 500)
            // A request a captcha guards, sent without one: the server names
            // the action, so it is solved and the request sent again, once.
            val action = e.captchaAction
            val solver = captchaSolver
            if (e.code != "captcha_required" || action == null || solver == null || captcha != null || headers.keys.any { it.equals("X-Captcha-Token", ignoreCase = true) }) throw e
            val solved = solver(action) ?: throw e
            return request(method, path, body, solved, headers, auth)
        }
    }

    suspend fun <T> request(
        method: String,
        path: String,
        strategy: DeserializationStrategy<T>,
        body: JsonElement? = null,
        captcha: String? = null,
        auth: Boolean = true,
    ): T {
        val el = request(method, path, body, captcha, auth = auth)
        try {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                wireJson.decodeFromJsonElement(strategy, el)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiError(200, "bad_response", "The server's answer could not be read.")
        }
    }

    suspend fun get(path: String): JsonElement = request("GET", path)
    suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>): T = request("GET", path, strategy)

    /** Runs a request and reads its answer off the main thread, whoever calls it. */
    suspend fun execute(client: OkHttpClient, req: Request, carriedToken: Boolean): JsonElement = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val res = await(client.newCall(req))
        res.use { r ->
            val text = r.body.string()
            if (r.isSuccessful) {
                if (r.code == 204 || r.code == 205 || text.isEmpty()) return@withContext JsonNull
                return@withContext try {
                    wireJson.parseToJsonElement(text)
                } catch (_: Exception) {
                    throw ApiError(r.code, "bad_response", "The server's answer could not be read.")
                }
            }
            throw errorOf(r.code, text, r.header("Retry-After"), carriedToken, r.header("X-Captcha-Action"))
        }
    }

    fun errorOf(status: Int, text: String, retryAfterHeader: String?, carriedToken: Boolean, captchaAction: String? = null): ApiError {
        val retry = retryAfterHeader?.trim()?.toIntOrNull()
        var code = "http_$status"
        var message = when {
            status == 429 -> "You are doing that too quickly."
            status >= 500 -> "The server ran into a problem."
            else -> "The server turned that down."
        }
        var details: JsonObject? = null
        try {
            val o = wireJson.parseToJsonElement(text).jsonObject
            val err = o["error"]?.jsonObject
            if (err != null) {
                code = err["code"]?.jsonPrimitive?.contentOrNull ?: code
                message = err["message"]?.jsonPrimitive?.contentOrNull ?: message
                details = JsonObject(o.filterKeys { it != "error" })
            }
        } catch (_: Exception) {
        }
        val e = ApiError(status, code, message, retry, details, captchaAction?.trim()?.takeIf { it.isNotEmpty() })
        if (status == 401 && code == "unauthorized" && carriedToken) onUnauthorized?.invoke()
        return e
    }

    suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val err = if (e is java.io.InterruptedIOException) ApiError(0, "timeout", "The server took too long to answer.") else ApiError(0, "network", "Nexa could not be reached. Check your connection.")
                if (cont.isActive) cont.resumeWithException(err)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        fun str(el: JsonElement?, key: String): String? = (el as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }
    }
}
