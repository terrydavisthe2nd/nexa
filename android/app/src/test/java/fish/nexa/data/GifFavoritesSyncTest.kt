package fish.nexa.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** settings.test.ts "keeps a GIF favourited on another device when this one favourites another". */
class GifFavoritesSyncTest {
    private fun fav(url: String) = buildJsonObject { put("url", url) }
    private fun doc(vararg urls: String) = JsonObject(mapOf("favoriteGifs" to JsonArray(urls.map { fav(it) })))
    private fun urls(d: JsonObject) = (d["favoriteGifs"] as JsonArray).map { it.jsonObject["url"]!!.jsonPrimitive.content }

    @Test
    fun anotherDevicesFavouriteSurvivesThisDevicesSave() {
        val mine = doc("a/old.gif", "a/gone.gif")
        // this device stars one and drops another on its older copy
        val change = doc("a/mine.gif", "a/old.gif")
        val edits = SettingsStore.gifEdits(mine, change)
        // meanwhile another device starred one and saved first
        val arriving = doc("a/theirs.gif", "a/old.gif", "a/gone.gif")
        val merged = SettingsStore.layerGifs(SettingsStore.merge(arriving, change), arriving, change, edits)
        assertEquals(listOf("a/mine.gif", "a/theirs.gif", "a/old.gif"), urls(merged))
    }

    @Test
    fun anotherDevicesFavouriteStickerSurvivesThisDevicesSave() {
        fun stickers(vararg ids: String) = JsonObject(mapOf("favoriteStickers" to JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })))
        val change = stickers("mine", "old")
        val edits = SettingsStore.stickerEdits(stickers("old", "gone"), change)
        val arriving = stickers("theirs", "old", "gone")
        val merged = SettingsStore.layerStickers(SettingsStore.merge(arriving, change), arriving, change, edits)
        assertEquals(listOf("mine", "theirs", "old"), (merged["favoriteStickers"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun aChangeElsewhereIsLeftAloneWhenNothingChangedHere() {
        val arriving = doc("x.gif")
        val unsaved = JsonObject(mapOf("theme" to JsonPrimitive("light")))
        assertEquals(arriving, SettingsStore.layerGifs(arriving, arriving, unsaved, null))
    }

    @Test
    fun resignedLinksAreTheSameFavourite() {
        val a = "https://nexa.fish/media/proxy/1790000000-abc/aHR0cHM6Ly9leGFtcGxl/x.gif"
        val b = "/media/proxy/1800000000-def/aHR0cHM6Ly9leGFtcGxl/x.gif"
        assertEquals(SettingsStore.gifKey(a), SettingsStore.gifKey(b))
        assertEquals(null, SettingsStore.gifEdits(doc(a), doc(b)))
        assertEquals("attach:12", SettingsStore.gifKey("attach:12"))
    }
}
