package fish.nexa.data

import android.util.Log
import fish.nexa.crypto.SettingsCrypto
import fish.nexa.net.Api
import fish.nexa.net.ApiError
import fish.nexa.net.SettingsBlob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.min

/**
 * The account's settings: one JSON document, sealed with a key that hangs
 * off the identity, stored on the server as an opaque blob, the same on every
 * device (web/src/stores/settings.ts). Keys this app does not know are kept and
 * written back unchanged. Changes apply at once and are saved 800 ms later;
 * a save that lost a race reloads the newer copy and lays its changes over it.
 */
class SettingsStore(private val api: Api, private val scope: CoroutineScope) {
    private val _doc = MutableStateFlow(DEFAULTS)
    val doc: StateFlow<JsonObject> = _doc
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    var version = 0L
        private set
    @Volatile var key: ByteArray? = null

    private var dirty: JsonObject? = null
    private var sending: JsonObject? = null
    // What this device did to the favourite GIFs, by what each points at: the
    // favourite starred here, or null for one taken away here. Saving the list
    // whole let a device with a slightly old copy put its list back over a GIF
    // just favourited elsewhere (web settings.ts ListEdits.gifs).
    private var dirtyGifs: Map<String, JsonObject?>? = null
    private var sendingGifs: Map<String, JsonObject?>? = null
    private var failures = 0
    private var debounce: Job? = null
    private val saveLock = Mutex()
    private var generation = 0

    fun load(blob: String?, ver: Long) {
        val k = key
        var parsed: JsonObject? = null
        if (blob != null && k != null) {
            parsed = SettingsCrypto.decrypt(k, blob) as? JsonObject
            if (parsed == null) {
                // unreadable: change nothing, save nothing, try again on the next ready
                Log.w(TAG, "settings could not be read; keeping what is here")
                return
            }
        }
        synchronized(this) {
            if (_loaded.value && ver < version) return
            val base = if (parsed != null) merge(DEFAULTS, parsed) else DEFAULTS
            val unsaved = mergeChanges(sending, dirty)
            _doc.value = if (unsaved != null) layerGifs(merge(base, unsaved), base, unsaved, mergeGifEdits(sendingGifs, dirtyGifs)) else base
            version = ver
            _loaded.value = true
        }
        if (dirty != null) scope.launch { flush() }
    }

    suspend fun reload() {
        val res = try {
            api.get("/users/@me/settings")
        } catch (_: Exception) {
            return
        }
        if (res is JsonNull) {
            load(null, 0)
            return
        }
        val blob = res.jsonObject["blob"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val ver = (res.jsonObject["version"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        load(blob, ver)
    }

    fun loadFromReady(s: SettingsBlob?) = load(s?.blob?.takeIf { it.isNotEmpty() }, s?.version ?: 0)

    /** Every settings control calls this: applied now, saved shortly. */
    fun patch(partial: JsonObject) {
        synchronized(this) {
            gifEdits(_doc.value, partial)?.let { dirtyGifs = mergeGifEdits(dirtyGifs, it) }
            _doc.value = merge(_doc.value, partial)
            dirty = mergeChanges(dirty, partial)
        }
        debounce?.cancel()
        debounce = scope.launch {
            delay(800)
            flush()
        }
    }

    fun set(key: String, value: JsonElement) = patch(JsonObject(mapOf(key to value)))

    /** Saves what is waiting; true when nothing made here is still unsent. */
    /** The draft for a conversation, kept with the account so it follows you to other devices. */
    fun setDraft(channelId: String, text: String) {
        val drafts = LinkedHashMap(drafts)
        if (text.isNotEmpty()) drafts[channelId] = JsonPrimitive(text) else drafts.remove(channelId)
        patch(buildJsonObject { put("drafts", JsonObject(drafts)) })
    }

    fun draft(channelId: String): String = (drafts[channelId] as? JsonPrimitive)?.contentOrNull ?: ""

    suspend fun flush(): Boolean {
        val gen = generation
        saveLock.withLock {
            while (true) {
                if (gen != generation) return false
                val k = key ?: return dirty == null
                if (!_loaded.value) return dirty == null
                val toSend = synchronized(this) {
                    val d = dirty ?: return true
                    sending = d
                    dirty = null
                    sendingGifs = dirtyGifs
                    dirtyGifs = null
                    _doc.value
                }
                try {
                    val res = api.request("PUT", "/users/@me/settings", buildJsonObject {
                        put("blob", SettingsCrypto.encrypt(k, toSend))
                        put("version", version)
                    })
                    synchronized(this) {
                        sending = null
                        sendingGifs = null
                        failures = 0
                        version = (res.jsonObject["version"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: version
                    }
                } catch (e: ApiError) {
                    synchronized(this) {
                        dirty = mergeChanges(sending, dirty)
                        sending = null
                        dirtyGifs = mergeGifEdits(sendingGifs, dirtyGifs)
                        sendingGifs = null
                    }
                    if (e.status == 409 && failures < 3) {
                        failures++
                        reload()
                        continue
                    }
                    if (e.isTransient || e.status == 409) {
                        val wait = min(60_000L, 2_000L shl failures)
                        failures++
                        scope.launch {
                            delay(wait)
                            flush()
                        }
                    }
                    return false
                }
                if (dirty == null) return true
            }
        }
    }

    fun reset() {
        generation++
        debounce?.cancel()
        synchronized(this) {
            dirty = null
            sending = null
            dirtyGifs = null
            sendingGifs = null
            failures = 0
            _doc.value = DEFAULTS
            version = 0
            _loaded.value = false
            key = null
        }
    }

    // ---- typed reads ----
    private fun str(k: String) = (_doc.value[k] as? JsonPrimitive)?.contentOrNull
    private fun bool(k: String, def: Boolean) = (_doc.value[k] as? JsonPrimitive)?.booleanOrNull ?: def
    private fun obj(k: String) = _doc.value[k] as? JsonObject

    val theme get() = str("theme") ?: "dark"
    val accent get() = str("accent")
    val fontScale get() = ((_doc.value["fontScale"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).coerceIn(0.75, 1.5)
    val fontFace get() = str("fontFace") ?: "default"
    val compact get() = str("messageDisplay") == "compact"
    val reducedMotion get() = bool("reducedMotion", false)
    /** Set by the theme in use: Greyscale and High Contrast underline links whatever the setting says. */
    @Volatile var lookUnderlines = false
    val underlineLinks get() = bool("underlineLinks", false) || lookUnderlines
    val locale get() = str("locale")
    val linkPreviews get() = bool("linkPreviews", true)
    val imagePreviews get() = bool("imagePreviews", true)
    val developerMode get() = bool("developerMode", false)
    /** Tell others when this account is typing. */
    val sendTyping get() = bool("sendTyping", true)
    val customTyping get() = bool("customTyping", true)
    /** Someone typing turns their presence dot into a pill of dots (DM list, member lists). */
    val typingAvatars get() = bool("typingAvatars", true)
    /** Dots on a server's channels while someone types in them. */
    val typingChannels get() = bool("typingChannels", true)
    val smartEmoji get() = bool("smartEmoji", true)
    val skinTone get() = (_doc.value["skinTone"] as? JsonPrimitive)?.intOrNull ?: 0
    val whatsNewSeen get() = str("whatsNewSeen")
    /** The newest release the Support Nexa prompt was shown for (components/support/Upsell.tsx). */
    val supportPromptSeen get() = str("supportPromptSeen")
    val notifications get() = obj("notifications") ?: DEFAULT_NOTIFICATIONS
    val voice get() = obj("voice") ?: DEFAULT_VOICE
    val drafts get() = obj("drafts") ?: JsonObject(emptyMap())
    val collapsedCategories: Set<String> get() = (_doc.value["collapsedCategories"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
    val recentEmoji: List<String> get() = (_doc.value["recentEmoji"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val blocking get() = obj("blocking")
    val hideBlockedMessages get() = (blocking?.get("hideMessages") as? JsonPrimitive)?.booleanOrNull ?: false
    val hideBlockedReplies get() = hideBlockedMessages && ((blocking?.get("hideReplies") as? JsonPrimitive)?.booleanOrNull ?: false)
    val userNotes get() = obj("userNotes") ?: JsonObject(emptyMap())

    companion object {
        private const val TAG = "NexaSettings"

        val DEFAULT_NOTIFICATIONS = buildJsonObject {
            put("desktop", false); put("app", true); put("sounds", true)
            put("perChannel", JsonObject(emptyMap())); put("muted", JsonArray(emptyList())); put("preview", "full")
        }

        val DEFAULT_VOICE = buildJsonObject {
            put("inputDeviceId", JsonNull); put("inputDeviceLabel", JsonNull)
            put("outputDeviceId", JsonNull); put("outputDeviceLabel", JsonNull)
            put("cameraDeviceId", JsonNull); put("cameraDeviceLabel", JsonNull)
            put("inputVolume", 100); put("outputVolume", 100)
            put("mode", "vad"); put("pttKey", "`"); put("sensitivity", -50); put("autoSensitivity", true)
            put("noiseMode", "enhanced"); put("noiseSuppression", true); put("echoCancellation", true); put("autoGain", true)
            put("shareResolution", "1080p"); put("shareFps", 30); put("shareAudio", true)
        }

        val DEFAULTS: JsonObject = buildJsonObject {
            put("theme", "dark"); put("accent", JsonNull); put("fontScale", 1); put("fontFace", "default")
            put("messageDisplay", "cozy"); put("reducedMotion", false); put("underlineLinks", false); put("locale", JsonNull)
            put("spellcheck", true); put("spellWords", JsonArray(emptyList())); put("sendButton", false)
            put("linkPreviews", true); put("imagePreviews", true); put("tts", true); put("developerMode", false)
            put("shareActivity", true); put("autoIdle", true); put("keybinds", JsonObject(emptyMap())); put("backgroundKeybinds", true)
            put("sendTyping", true); put("customTyping", true); put("typingAvatars", true); put("typingChannels", true); put("seasonalSnow", true); put("sidebarWidth", 240); put("showMembers", true)
            put("recentEmoji", JsonArray(emptyList())); put("smartEmoji", true); put("emojiUsage", JsonObject(emptyMap())); put("emojiLearned", false)
            put("favoriteGifs", JsonArray(emptyList())); put("skinTone", 0)
            put("notifications", DEFAULT_NOTIFICATIONS)
            put("sounds", buildJsonObject { put("pack", "nexa"); put("volume", 100); put("off", JsonArray(emptyList())); put("packs", JsonArray(emptyList())) })
            put("drafts", JsonObject(emptyMap())); put("voice", DEFAULT_VOICE)
            put("serverPositions", JsonArray(emptyList())); put("collapsedCategories", JsonArray(emptyList())); put("userNotes", JsonObject(emptyMap()))
            put("appLock", JsonNull); put("rail", JsonNull)
            put("dmList", buildJsonObject { put("pins", JsonArray(emptyList())); put("groups", JsonArray(emptyList())) })
            put("nexaClosed", 0)
            put("blocking", buildJsonObject { put("hideMessages", false); put("hideReplies", false) })
            put("customThemes", JsonArray(emptyList()))
            put("systemThemes", buildJsonObject { put("dark", "dark"); put("light", "light") })
            put("customCss", buildJsonObject { put("snippet", ""); put("snippetOn", true); put("links", JsonArray(emptyList())) })
            put("plugins", JsonArray(emptyList())); put("whatsNewSeen", JsonNull); put("searchIndex", true); put("searchRecent", JsonArray(emptyList()))
        }

        private val SHALLOW = setOf("notifications", "blocking", "systemThemes", "customCss", "voice")

        /** `{...base, ...incoming}` with the nested objects merged one level, unknown keys kept. */
        fun merge(base: JsonObject, incoming: JsonObject): JsonObject {
            val out = LinkedHashMap(base)
            for ((k, v) in incoming) {
                out[k] = if (k in SHALLOW && v is JsonObject && base[k] is JsonObject) {
                    JsonObject(LinkedHashMap(base[k] as JsonObject).apply { putAll(v) })
                } else v
            }
            sanitizeVoice(out)
            return JsonObject(out)
        }

        private fun sanitizeVoice(out: MutableMap<String, JsonElement>) {
            val v = out["voice"] as? JsonObject ?: return
            val m = LinkedHashMap(v)
            val mode = (m["noiseMode"] as? JsonPrimitive)?.contentOrNull
            var noise = if (mode in listOf("enhanced", "standard", "off")) mode!! else "enhanced"
            val ns = (m["noiseSuppression"] as? JsonPrimitive)?.booleanOrNull
            if (ns == false) noise = "off" else if (ns == true && noise == "off") noise = "enhanced"
            m["noiseMode"] = JsonPrimitive(noise)
            m["noiseSuppression"] = JsonPrimitive(noise != "off")
            val res = (m["shareResolution"] as? JsonPrimitive)?.contentOrNull
            if (res !in listOf("720p", "1080p", "1440p", "source")) m["shareResolution"] = JsonPrimitive("1080p")
            val fps = (m["shareFps"] as? JsonPrimitive)?.intOrNull
            if (fps !in listOf(15, 30, 60)) m["shareFps"] = JsonPrimitive(30)
            out["voice"] = JsonObject(m)
        }

        /** Later changes win; notifications and voice merge one level deep. */
        /** What a favourite GIF points at, whatever its link was signed with (MediaLinks.mediaKey). */
        fun gifKey(url: String): String {
            val path = if (url.startsWith("/media/proxy/")) url
            else if (url.startsWith("https://") || url.startsWith("http://")) runCatching { java.net.URI(url).rawPath }.getOrNull()?.takeIf { it.startsWith("/media/proxy/") } ?: return url
            else return url
            return path.removePrefix("/media/proxy/").split('/').getOrNull(1)?.ifEmpty { null } ?: url
        }

        private fun gifList(doc: JsonObject?): List<JsonObject> = (doc?.get("favoriteGifs") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        private fun gifUrl(f: JsonObject) = (f["url"] as? JsonPrimitive)?.contentOrNull ?: ""

        /** The favourites a change starred (the entry) or took away (null), or null when it leaves them alone. */
        fun gifEdits(before: JsonObject, change: JsonObject): Map<String, JsonObject?>? {
            if (change["favoriteGifs"] !is JsonArray) return null
            val was = gifList(before).map { gifKey(gifUrl(it)) }.toSet()
            val now = LinkedHashMap<String, JsonObject>()
            for (f in gifList(change)) now.putIfAbsent(gifKey(gifUrl(f)), f)
            val out = LinkedHashMap<String, JsonObject?>()
            for ((k, f) in now) if (k !in was) out[k] = f
            for (k in was) if (k !in now) out[k] = null
            return out.ifEmpty { null }
        }

        fun mergeGifEdits(a: Map<String, JsonObject?>?, b: Map<String, JsonObject?>?): Map<String, JsonObject?>? {
            if (a == null) return b
            if (b == null) return a
            return LinkedHashMap(a).apply { putAll(b) }
        }

        /**
         * The arriving favourites with this device's own changes laid over
         * them: what was starred here in front (in this device's order), what
         * was taken away here gone, and everything else as it arrived.
         */
        fun layerGifs(merged: JsonObject, base: JsonObject, unsaved: JsonObject, edits: Map<String, JsonObject?>?): JsonObject {
            if (unsaved["favoriteGifs"] !is JsonArray) return merged
            val changed = edits ?: emptyMap()
            val added = gifList(unsaved).filter { changed[gifKey(gifUrl(it))] != null }
            val kept = gifList(base).filter { gifKey(gifUrl(it)) !in changed }
            return JsonObject(LinkedHashMap(merged).apply { put("favoriteGifs", JsonArray((added + kept).take(500))) })
        }

        fun mergeChanges(a: JsonObject?, b: JsonObject?): JsonObject? {
            if (a == null) return b
            if (b == null) return a
            val out = LinkedHashMap(a)
            for ((k, v) in b) {
                out[k] = if ((k == "notifications" || k == "voice") && v is JsonObject && a[k] is JsonObject) JsonObject(LinkedHashMap(a[k] as JsonObject).apply { putAll(v) }) else v
            }
            return JsonObject(out)
        }
    }
}
