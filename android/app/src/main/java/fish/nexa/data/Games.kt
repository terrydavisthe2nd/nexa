package fish.nexa.data

import fish.nexa.Nexa
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder

/**
 * A game's page (web lib/games.ts, server internal/games/details.go): what
 * IGDB says about a game, its screenshots, artwork and trailers, similar
 * games and where it is sold, asked of the server once a session. Opened
 * from a game on a profile's widgets, or from an activity whose game IGDB
 * knows by name.
 */
object Games {
    data class Ref(val id: String, val name: String, val cover: String?, val year: Int?)
    data class Picture(val id: String, val w: Int?, val h: Int?)
    data class Video(val id: String, val name: String?, val thumb: String?, val poster: String?)
    data class Link(val kind: String, val url: String)
    data class AgeRating(val board: String, val rating: String)

    data class Details(
        val ref: Ref,
        val summary: String?,
        val storyline: String?,
        /** Unix milliseconds. */
        val released: Long?,
        val genres: List<String>,
        val themes: List<String>,
        val modes: List<String>,
        val platforms: List<String>,
        val developers: List<String>,
        val publishers: List<String>,
        val rating: Int?,
        val ratingCount: Int?,
        val ageRatings: List<AgeRating>,
        val screenshots: List<Picture>,
        val artworks: List<Picture>,
        val videos: List<Video>,
        val similar: List<Ref>,
        val links: List<Link>,
    )

    /** The stores' own names, which are not translated; "official" is translated where it is drawn. */
    val STORE_NAMES = mapOf(
        "steam" to "Steam", "epic" to "Epic Games Store", "gog" to "GOG", "itch" to "itch.io",
        "playstation" to "PlayStation Store", "xbox" to "Xbox", "nintendo" to "Nintendo eShop",
        "apple" to "App Store", "googleplay" to "Google Play", "gamejolt" to "Game Jolt", "wikipedia" to "Wikipedia",
    )

    /** A screenshot or artwork: "t" for the strip, "b" for the stage and the viewer. */
    fun pictureUrl(id: String, size: String) = "${Nexa.api.baseUrl}/media/game/$id?size=$size"

    fun coverUrl(id: String, size: String) = "${Nexa.api.baseUrl}/media/game/$id?size=$size"

    private val pages = HashMap<String, Deferred<Details>>()
    private val matches = HashMap<String, Deferred<String?>>()

    /** A game's page, asked once a session (the server keeps it a day). */
    suspend fun load(id: String): Details {
        val mine = CompletableDeferred<Details>()
        val running = synchronized(pages) { pages[id] ?: run { pages[id] = mine; null } }
        if (running != null) return running.await()
        return try {
            val d = parse(Nexa.api.get("/games/$id") as JsonObject) ?: throw IllegalStateException("no game")
            mine.complete(d)
            d
        } catch (e: Throwable) {
            synchronized(pages) { pages.remove(id) }
            mine.completeExceptionally(e)
            throw e
        }
    }

    /** IGDB's id for the game an activity names, if one has exactly that name; asked once a session per name. */
    suspend fun match(name: String): String? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        val mine = CompletableDeferred<String?>()
        val running = synchronized(matches) { matches[key] ?: run { matches[key] = mine; null } }
        if (running != null) return running.await()
        return try {
            val res = Nexa.api.get("/games/match?name=" + URLEncoder.encode(name.trim(), "UTF-8")) as? JsonObject
            val id = (res?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("^\\d{1,10}$")) }
            mine.complete(id)
            id
        } catch (e: Throwable) {
            synchronized(matches) { matches.remove(key) }
            mine.completeExceptionally(e)
            throw e
        }
    }

    /** Forgets everything asked, when the account changes. */
    fun reset() {
        synchronized(pages) { pages.clear() }
        synchronized(matches) { matches.clear() }
    }

    /** IGDB lists a game's videos in no order: trailers go first, the launch or official one before others. */
    fun trailersFirst(videos: List<Video>): List<Video> {
        fun rank(v: Video): Int {
            val n = (v.name ?: "").lowercase()
            if (!Regex("trailer|teaser").containsMatchIn(n)) return 3
            if (Regex("launch|official|release|reveal|announce").containsMatchIn(n)) return 0
            return if ("gameplay" in n) 2 else 1
        }
        return videos.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    /** The picture across the top: wide artwork when there is some big enough, else the first screenshot, else any artwork. */
    fun backdrop(d: Details): String? {
        val wide = d.artworks.firstOrNull { (it.w ?: 0) >= 1280 && (it.w ?: 0) >= (it.h ?: 1) * 1.4 }
        return (wide ?: d.screenshots.firstOrNull() ?: d.artworks.firstOrNull())?.id
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } } ?: emptyList()
    private val IMAGE = Regex("^[a-z0-9]{6,32}$")

    private fun ref(o: JsonObject): Ref? {
        val id = o.s("id")?.takeIf { it.matches(Regex("^\\d{1,10}$")) } ?: return null
        val name = o.s("name") ?: return null
        return Ref(id, name, o.s("cover")?.takeIf { IMAGE.matches(it) }, o.i("year")?.toInt())
    }

    private fun pictures(e: JsonElement?) = e.objects().mapNotNull { p -> p.s("id")?.takeIf { IMAGE.matches(it) }?.let { Picture(it, p.i("w")?.toInt(), p.i("h")?.toInt()) } }

    fun parse(o: JsonObject): Details? {
        val ref = ref(o) ?: return null
        return Details(
            ref = ref,
            summary = o.s("summary"),
            storyline = o.s("storyline"),
            released = o.i("released")?.takeIf { it > 0 },
            genres = o["genres"].strings(),
            themes = o["themes"].strings(),
            modes = o["modes"].strings(),
            platforms = o["platforms"].strings(),
            developers = o["developers"].strings(),
            publishers = o["publishers"].strings(),
            rating = o.i("rating")?.toInt()?.takeIf { it in 1..100 },
            ratingCount = o.i("rating_count")?.toInt(),
            ageRatings = o["age_ratings"].objects().mapNotNull { a -> val b = a.s("board"); val r = a.s("rating"); if (b != null && r != null) AgeRating(b, r) else null },
            screenshots = pictures(o["screenshots"]),
            artworks = pictures(o["artworks"]),
            videos = o["videos"].objects().mapNotNull { v ->
                v.s("id")?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{11}$")) }?.let { Video(it, v.s("name"), v.s("thumb"), v.s("poster")) }
            },
            similar = o["similar"].objects().mapNotNull { ref(it) },
            links = o["links"].objects().mapNotNull { l ->
                val kind = l.s("kind") ?: return@mapNotNull null
                val url = l.s("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
                if (kind != "official" && kind !in STORE_NAMES) null else Link(kind, url)
            },
        )
    }
}
