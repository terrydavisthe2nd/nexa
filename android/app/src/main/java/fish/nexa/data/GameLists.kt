package fish.nexa.data

import fish.nexa.Nexa
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * The staff's side of the games people share from the desktop app (web
 * api/moderation.ts, server internal/api/game_lists.go). Android detects no
 * games itself, but what staff add here reaches every desktop app. The panel
 * learns how many people shared a program, never who.
 */
object GameLists {
    data class Found(val id: String, val name: String, val cover: String?, val year: Int?)
    data class Waiting(val process: String, val name: String, val gameId: String?, val others: List<String>, val count: Int)
    data class Decided(val process: String, val name: String, val gameId: String?, val status: String, val decidedAt: Long)
    data class Page(val waiting: List<Waiting>, val decided: List<Decided>, val games: Map<String, Found>)

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }

    private fun found(o: JsonObject): Found? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        return Found(id, name, o.str("cover"), (o["year"] as? JsonPrimitive)?.intOrNull)
    }

    suspend fun page(): Page {
        val o = Nexa.api.get("/moderation/games") as JsonObject
        val waiting = (o["waiting"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { w ->
            Waiting(
                w.str("process") ?: return@mapNotNull null,
                w.str("name") ?: return@mapNotNull null,
                w.str("game_id"),
                (w["other_names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                (w["count"] as? JsonPrimitive)?.intOrNull ?: 1,
            )
        }
        val decided = (o["decided"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { d ->
            Decided(
                d.str("process") ?: return@mapNotNull null,
                d.str("name") ?: return@mapNotNull null,
                d.str("game_id"),
                d.str("status") ?: return@mapNotNull null,
                (d["decided_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
        val games = (o["games"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonObject)?.let(::found)?.let { k to it } }?.toMap() ?: emptyMap()
        return Page(waiting, decided, games)
    }

    /** Games on IGDB by this name; the answer's first part is false where game search is not set up. */
    suspend fun search(q: String): Pair<Boolean, List<Found>> {
        val o = Nexa.api.get("/games/search?q=" + URLEncoder.encode(q, "UTF-8")) as JsonObject
        val list = (o["games"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::found) }
        return ((o["ready"] as? JsonPrimitive)?.contentOrNull != "false") to list
    }

    suspend fun waitingCount(): Int =
        ((Nexa.api.get("/moderation/games/count") as JsonObject)["waiting"] as? JsonPrimitive)?.intOrNull ?: 0

    suspend fun list(process: String, name: String, gameId: String?) {
        Nexa.api.request("POST", "/moderation/games/list", buildJsonObject {
            put("process", process)
            put("name", name)
            if (gameId != null) put("game_id", gameId)
        })
    }

    suspend fun reject(process: String, name: String) {
        Nexa.api.request("POST", "/moderation/games/reject", buildJsonObject {
            put("process", process)
            put("name", name)
        })
    }

    suspend fun forget(process: String) {
        Nexa.api.request("POST", "/moderation/games/forget", buildJsonObject { put("process", process) })
    }
}
