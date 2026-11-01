package fish.nexa.ui.theme.look

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.YearMonth

/** A seasonal theme (lib/look/seasonal.ts Season); [from] and [to] are (month, day), both days included. */
data class Season(
    val id: String,
    val nameKey: String,
    val scheme: String,
    val background: String,
    val accent: String,
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>,
    /** The season whose picture it shares: the night versions of the light ones. */
    val artOf: String? = null,
    /** Snow falling behind the messages (ui/chat/SnowFall.kt). */
    val snow: Boolean = false,
) {
    /** Its picture, copied from the web's assets/seasonal by the build's copyWebAssets. */
    val art: String get() = fish.nexa.data.Ota.uri("seasonal/${artOf ?: id}.svg")
}

/**
 * The web's seasonal themes (lib/look/seasonal.ts): palettes offered in the
 * gallery only while their season lasts, each with a picture that sits on the
 * message box. Out of season one draws as Dark, and the app sets the account's
 * theme back to Dark (ui/App.kt).
 */
object Seasonal {
    val ALL = listOf(
        Season("season-halloween", "look.seasonal.halloween", "dark", "#1c1322", "#f07a1a", 10 to 1, 11 to 2),
        Season("season-fall", "look.seasonal.fall", "dark", "#221915", "#cc5a2a", 9 to 1, 11 to 30),
        Season("season-winter", "look.seasonal.winter", "light", "#f2f6fa", "#2b6aa3", 12 to 1, 2 to 29),
        Season("season-winter-night", "look.seasonal.winterNight", "dark", "#0f1a2a", "#5b9be0", 12 to 1, 2 to 29, artOf = "season-winter", snow = true),
        Season("season-holidays", "look.seasonal.holidays", "dark", "#10201a", "#c62f3a", 12 to 1, 1 to 6),
        Season("season-valentines", "look.seasonal.valentines", "dark", "#2a1219", "#ff5d8a", 2 to 1, 2 to 15),
        Season("season-spring", "look.seasonal.spring", "light", "#f6faf1", "#b23b7f", 3 to 1, 5 to 31),
        Season("season-spring-night", "look.seasonal.springNight", "dark", "#16181f", "#e06aa8", 3 to 1, 5 to 31, artOf = "season-spring"),
        Season("season-summer", "look.seasonal.summer", "light", "#f1fafa", "#d4532d", 6 to 1, 8 to 31),
        Season("season-summer-night", "look.seasonal.summerNight", "dark", "#0e1c22", "#f07850", 6 to 1, 8 to 31, artOf = "season-summer"),
    )

    private val BY_ID = ALL.associateBy { it.id }
    private val made = java.util.concurrent.ConcurrentHashMap<String, TokenMap>()

    private val _today = MutableStateFlow(LocalDate.now())
    /** Today, for what has to change when the day does; [tick] reads the clock again. */
    val today: StateFlow<LocalDate> = _today
    fun tick() {
        _today.value = LocalDate.now()
    }

    fun byId(id: String): Season? = BY_ID[id]

    private fun dayOf(month: Int, day: Int) = month * 100 + day

    /** For trying them out: a build made with -PallSeasons=true has every season running every day. */
    private val allSeasons = fish.nexa.BuildConfig.ALL_SEASONS

    fun inSeason(s: Season, on: LocalDate): Boolean {
        if (allSeasons) return true
        val d = dayOf(on.monthValue, on.dayOfMonth)
        val a = dayOf(s.from.first, s.from.second)
        val b = dayOf(s.to.first, s.to.second)
        return if (a <= b) d in a..b else d >= a || d <= b
    }

    /** The last day of the season running on [on]: this year's, or next year's when it runs past the new year. */
    fun end(s: Season, on: LocalDate): LocalDate {
        val (m, d) = s.to
        val year = on.year + if (dayOf(on.monthValue, on.dayOfMonth) > dayOf(m, d)) 1 else 0
        return LocalDate.of(year, m, minOf(d, YearMonth.of(year, m).lengthOfMonth()))
    }

    /** The seasonal themes that can be picked on [on], the one ending soonest first. */
    fun on(on: LocalDate): List<Season> = ALL.filter { inSeason(it, on) }.sortedBy { end(it, on) }

    /** A seasonal theme's palette, worked out the first time it is asked for. */
    fun tokens(id: String): TokenMap? {
        made[id]?.let { return it }
        val s = BY_ID[id] ?: return null
        val t = Derive.deriveTheme(SimpleColours(s.background, s.accent, null, s.scheme), false).tokens
        made[id] = t
        return t
    }

    /** One card in the gallery: a season and its night version ([dark], null when it has none), with a switch between them. */
    data class Card(val light: Season, val dark: Season?)

    /** The seasonal themes as the gallery shows them (seasonCards). */
    fun cards(seasons: List<Season>): List<Card> {
        val nights = seasons.filter { it.artOf != null && BY_ID.containsKey(it.artOf) }.map { it.id }.toSet()
        return seasons.filter { it.id !in nights }.map { s -> Card(s, seasons.firstOrNull { it.id in nights && it.artOf == s.id }) }
    }

    /** The theme [id] draws as on [on]: itself while its season lasts, Dark once it is over. */
    fun orDefault(id: String, on: LocalDate): String = BY_ID[id]?.takeIf { !inSeason(it, on) }?.let { "dark" } ?: id

    /** The season of a theme that is in season on [on], for the picture on the message box. */
    fun active(id: String, on: LocalDate): Season? = BY_ID[id]?.takeIf { inSeason(it, on) }

    /** The seasonal theme being worn and in season, if any; not while a theme is being tried on in the builder. */
    @androidx.compose.runtime.Composable
    fun worn(): Season? {
        val doc by fish.nexa.Nexa.settings.doc.collectAsState()
        val day by today.collectAsState()
        val theme = (doc["theme"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (LookDraft.theme != null) return null
        return active(theme, day)
    }
}
