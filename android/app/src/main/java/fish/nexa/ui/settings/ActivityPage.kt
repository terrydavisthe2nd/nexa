package fish.nexa.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.net.ApiError
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.describeError
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private fun JsonObject?.b(k: String) = (this?.get(k) as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject?.s(k: String) = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject?.n(k: String) = (this?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L

/**
 * What the app keeps doing for activity whatever page is open: telling the
 * server to hide Discord activity while sharing is off (alignShare), and
 * going idle after ten minutes away (lib/away.ts).
 */
object ActivitySync {
    /** GET /connections/discord, null until asked; the discord_link event replaces it. */
    val discord = MutableStateFlow<JsonObject?>(null)

    private const val AWAY_AFTER = 10 * 60_000L
    @Volatile private var lastUse = System.currentTimeMillis()
    @Volatile private var away = false
    private var started = false

    private fun sharing() = (Nexa.settings.doc.value["shareActivity"] as? JsonPrimitive)?.contentOrNull != "false"
    private fun autoIdle() = (Nexa.settings.doc.value["autoIdle"] as? JsonPrimitive)?.contentOrNull != "false"

    /** A touch or a key: present again. */
    fun touch() {
        lastUse = System.currentTimeMillis()
        if (away) setAway(false)
    }

    private fun setAway(on: Boolean) {
        away = on
        Nexa.session.send("away", buildJsonObject { put("away", on) })
    }

    suspend fun load() {
        discord.value = runCatching { Nexa.api.get("/connections/discord") as? JsonObject }.getOrNull()
        align()
    }

    /** A linked Discord account shows its activity only while sharing is on. */
    suspend fun align() {
        val st = discord.value ?: return
        val linked = st["linked"] as? JsonObject ?: return
        if (!Nexa.settings.loaded.value) return
        val share = sharing()
        if (linked.b("show") == share) return
        runCatching { Nexa.api.request("PATCH", "/connections/discord", buildJsonObject { put("show", share) }) as? JsonObject }.getOrNull()?.let { discord.value = it }
    }

    fun start() {
        if (started) return
        started = true
        Nexa.scope.launch {
            Nexa.session.events.collect { e ->
                when (e.t) {
                    "discord_link" -> (e.d as? JsonObject)?.let {
                        discord.value = it
                        align()
                    }
                    // the server keeps away per connection, so a new one is told again
                    "ready" -> if (away) setAway(true)
                }
            }
        }
        Nexa.scope.launch {
            var was: Boolean? = null
            Nexa.settings.doc.collect {
                if (!Nexa.settings.loaded.value) return@collect
                val share = sharing()
                if (share != was) {
                    if (was == null && discord.value == null) load() else align()
                    was = share
                }
            }
        }
        Nexa.scope.launch {
            while (true) {
                // sleeps until it could be time, not a little at a time
                val left = AWAY_AFTER - (System.currentTimeMillis() - lastUse)
                delay(if (away || left <= 0) AWAY_AFTER else left + 1_000)
                if (Nexa.auth.state.value !is fish.nexa.data.AuthState.Ready) continue
                val idle = System.currentTimeMillis() - lastUse >= AWAY_AFTER
                val want = autoIdle() && idle && !fish.nexa.data.AppLock.inCall()
                if (want != away) setAway(want)
            }
        }
    }
}

private val KINDS = listOf(2 to "settings.activity.kindListening", 1 to "settings.activity.kindPlaying", 4 to "settings.activity.kindWatching", 8 to "settings.activity.kindStreaming", 16 to "settings.activity.kindCompeting")

/** 3.19 Activity: sharing and going idle, and Discord. */
@Composable
fun ActivityPage() {
    val c = Nx.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = settingsDoc()
    val me = Nexa.users.me.collectAsState().value
    val share = doc.bool("shareActivity", true)
    val state by ActivitySync.discord.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { ActivitySync.load() }

    PageTitle(t("settings.activity.title"))
    SectionHeading(t("settings.activity.status"), Modifier.settingAnchor("activityStatus"), first = true)
    ToggleRow(t("settings.privacy.shareMyActivity"), share, { setSetting("shareActivity", it) }, t("settings.activity.shareHint"))
    ToggleRow(t("settings.privacy.goIdleWhenAway"), doc.bool("autoIdle", true), { setSetting("autoIdle", it) }, t("settings.privacy.goIdleWhenAwayHint"), divider = false)

    SectionHeading(t("settings.activity.discord"), Modifier.settingAnchor("discord"))
    val st = state
    val admin = (me?.flags ?: 0L) and 1L != 0L
    fun connect() {
        busy = "connect"
        scope.launch {
            try {
                val res = Nexa.api.request("POST", "/connections/discord/authorize", buildJsonObject { put("app", true) }) as? JsonObject
                val url = res.s("url") ?: throw ApiError(0, "bad_response", t("settings.activity.discordFailed"))
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                Nexa.ui.toast(t("settings.activity.finishInBrowser"), "info")
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("settings.activity.discordFailed")), "error")
            } finally {
                busy = null
            }
        }
    }
    when {
        st == null -> Hint(t("chat.pins.loading"))
        !st.b("available") -> {
            Hint(t("settings.activity.discordUnavailable"))
            if (admin) Hint(t("settings.activity.discordSetupHint"))
        }
        st["linked"] !is JsonObject -> SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(t("settings.activity.discordIntro"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong)
                Hint(t("settings.activity.discordHow"))
                NxButton(t("settings.activity.connectDiscord"), { connect() }, loading = busy == "connect")
            }
        }
        else -> {
            val linked = st["linked"] as JsonObject
            val kinds = (linked["kinds"] as? JsonPrimitive)?.intOrNull ?: 31
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.bgSurface)) {
                    linked.s("avatar_url")?.let { AsyncImage(model = Media.absolute(it), contentDescription = null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Txt(linked.s("global_name")?.ifBlank { null } ?: linked.s("username") ?: "", style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
                    Txt(linked.s("username") ?: "", style = Nx.type.bodySmall, color = c.textMuted, maxLines = 1)
                }
                NxButton(t("settings.activity.disconnect"), {
                    busy = "unlink"
                    scope.launch {
                        try {
                            Nexa.api.request("DELETE", "/connections/discord")
                            ActivitySync.discord.value = JsonObject(LinkedHashMap(st).apply { put("linked", JsonNull) })
                            Nexa.ui.toast(t("settings.activity.discordUnlinked"), "success")
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("settings.activity.unlinkFailed")), "error")
                        } finally {
                            busy = null
                        }
                    }
                }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Unplug", loading = busy == "unlink")
            }
            val inGuild = linked.b("in_guild")
            val (good, text) = when {
                !inGuild && linked.n("left_at") > 0 -> false to t("settings.activity.leftServer")
                !inGuild -> false to t("settings.activity.notInServer")
                linked.b("watching") -> true to t("settings.activity.syncing")
                else -> false to t("settings.activity.botAway")
            }
            StatusBox(good, text) {
                if (!inGuild) NxButton(t("settings.activity.rejoin"), {
                    busy = "rejoin"
                    scope.launch {
                        try {
                            (Nexa.api.request("POST", "/connections/discord/rejoin") as? JsonObject)?.let { ActivitySync.discord.value = it }
                            Nexa.ui.toast(t("settings.activity.rejoined"), "success")
                        } catch (e: ApiError) {
                            if (e.code == "reauthorize") connect() else Nexa.ui.toast(describeError(e, t("settings.activity.rejoinFailed")), "error")
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("settings.activity.rejoinFailed")), "error")
                        } finally {
                            busy = null
                        }
                    }
                }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = busy == "rejoin")
            }
            SectionHeading(t("settings.activity.whatShows"))
            for ((bit, key) in KINDS) {
                ToggleRow(t(key), kinds and bit != 0, { on ->
                    scope.launch {
                        try {
                            val next = if (on) kinds or bit else kinds and bit.inv()
                            (Nexa.api.request("PATCH", "/connections/discord", buildJsonObject { put("kinds", next) }) as? JsonObject)?.let { ActivitySync.discord.value = it }
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("settings.privacy.couldNotSave")), "error")
                        }
                    }
                }, enabled = share)
            }
            if (!share) Hint(t("settings.activity.sharingOff"), Modifier.padding(top = 6.dp))
            Hint(t("settings.activity.discordPrivacy"), Modifier.padding(top = 12.dp))
            val bot = st["bot"] as? JsonObject
            if (admin && bot.b("configured")) BotBox(bot!!)
        }
    }
}

/** A status line in green (all is well) or amber, with its button. */
@Composable
private fun StatusBox(good: Boolean, text: String, action: @Composable () -> Unit) {
    val c = Nx.colors
    val tone = if (good) c.positive else c.warning
    Column(
        Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), Radii.Sm)
            .border(1.dp, tone.copy(alpha = 0.45f), Radii.Sm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (good) "CircleCheck" else "TriangleAlert", if (good) c.statusOnline else c.textWarning, size = 16.dp)
            Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20f)), color = c.text, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
    }
}

/** The Discord bot's state, for the people who run Nexa. */
@Composable
private fun BotBox(bot: JsonObject) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, c.borderSubtle, Radii.Md).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(t("settings.activity.botHead"), style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong)
        Txt(
            if (bot.b("connected")) t("settings.activity.botConnected") else t("settings.activity.botDisconnected", "error" to (bot.s("error")?.ifBlank { null } ?: t("settings.desktop.unknownError"))),
            style = Nx.type.bodySmall, color = c.text,
        )
        bot.s("redirect")?.let { MonoBox(t("settings.activity.botRedirect", "url" to it), size = 12f) }
        if ((bot["redirect_listed"] as? JsonPrimitive)?.booleanOrNull == false) NoticeBox(t("settings.activity.botRedirectUnlisted"), c.warning)
    }
}
