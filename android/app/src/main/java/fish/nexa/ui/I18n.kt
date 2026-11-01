package fish.nexa.ui

import android.content.Context
import android.icu.text.PluralRules
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.NumberFormat
import java.util.Locale

/**
 * Translation, with the web client's own catalogues (web/src/i18n/locales,
 * copied into the assets at build time): the same keys, the same {name}
 * placeholders, the same plural forms chosen by the language's CLDR rules, and
 * English behind every language, so a string translated once is translated
 * in every app.
 */
object I18n {
    data class Language(val tag: String, val endonym: String, val english: String, val rtl: Boolean = false)

    val languages = listOf(
        Language("en", "English", "English"),
        Language("es", "Español", "Spanish"),
        Language("fr", "Français", "French"),
        Language("de", "Deutsch", "German"),
        Language("sv", "Svenska", "Swedish"),
        Language("pt-BR", "Português (Brasil)", "Portuguese (Brazil)"),
        Language("ru", "Русский", "Russian"),
        Language("ja", "日本語", "Japanese"),
        Language("ko", "한국어", "Korean"),
        Language("zh-CN", "简体中文", "Chinese (Simplified)"),
        Language("hi", "हिन्दी", "Hindi"),
        Language("ar", "العربية", "Arabic", rtl = true),
    )

    private lateinit var appContext: Context
    private val json = Json { ignoreUnknownKeys = true }
    private var english: JsonObject = JsonObject(emptyMap())
    private var catalog: JsonObject = JsonObject(emptyMap())
    private var plural: PluralRules = PluralRules.forLocale(Locale.ENGLISH)
    private var numbers: NumberFormat = NumberFormat.getInstance(Locale.ENGLISH)

    /** The language in use; reading it in a composable recomposes it when the language changes. */
    var tag by mutableStateOf("en")
        private set

    val rtl: Boolean get() = languages.firstOrNull { it.tag == tag }?.rtl == true
    val locale: Locale get() = Locale.forLanguageTag(tag)

    private fun load(tag: String): JsonObject = try {
        fish.nexa.data.Ota.open("locales/$tag.json").use { json.parseToJsonElement(it.readBytes().decodeToString()) as JsonObject }
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        english = load("en")
        catalog = english
    }

    /** An exact match first, then the base language ("pt" for "pt-PT"), then English. */
    fun pick(preferred: List<String>): String {
        for (want in preferred) {
            val t = want.trim()
            if (t.isEmpty()) continue
            languages.firstOrNull { it.tag.equals(t, true) }?.let { return it.tag }
            val base = t.substringBefore('-').lowercase()
            languages.firstOrNull { it.tag.lowercase() == base || it.tag.lowercase().startsWith("$base-") }?.let { return it.tag }
        }
        return "en"
    }

    fun systemPreferred(): List<String> {
        val list = appContext.resources.configuration.locales
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }

    /** Sets the language: the account's choice, or the phone's when there is none. */
    fun use(choice: String?) {
        val next = if (choice != null && languages.any { it.tag == choice }) choice else pick(systemPreferred())
        if (next == tag && catalog.isNotEmpty()) return
        catalog = if (next == "en") english else load(next)
        val loc = Locale.forLanguageTag(next)
        plural = PluralRules.forLocale(loc)
        numbers = NumberFormat.getInstance(loc)
        tag = next
    }

    fun has(key: String) = catalog.containsKey(key) || english.containsKey(key)

    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

    fun t(key: String, vararg values: Pair<String, Any?>): String {
        @Suppress("UNUSED_VARIABLE") val read = tag
        val msg = catalog[key] ?: english[key] ?: return key
        val count = values.firstOrNull { it.first == "count" }?.second as? Number
        val template = when (msg) {
            is JsonPrimitive -> msg.contentOrNull ?: return key
            is JsonObject -> {
                val n = count?.toDouble() ?: 0.0
                val cat = try {
                    plural.select(n)
                } catch (_: Exception) {
                    if (n == 1.0) "one" else "other"
                }
                (msg[cat] as? JsonPrimitive)?.contentOrNull ?: (msg["other"] as? JsonPrimitive)?.contentOrNull ?: return key
            }
            else -> return key
        }
        if (values.isEmpty()) return template
        val map = values.toMap()
        return PLACEHOLDER.replace(template) { m ->
            val name = m.groupValues[1]
            if (!map.containsKey(name)) return@replace m.value
            when (val v = map[name]) {
                is Number -> numbers.format(v)
                null -> m.value
                else -> v.toString()
            }
        }
    }
}

/** Shorthand used everywhere: `t("key")`, `t("key", "count" to 3)`. */
fun t(key: String, vararg values: Pair<String, Any?>): String = I18n.t(key, *values)
