package fish.nexa.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fish.nexa.BuildConfig
import fish.nexa.Nexa
import fish.nexa.net.ApiError
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NexaMark
import fish.nexa.ui.components.NexaWordmark
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.legal.Legal
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.NumberFormat

/** A row of the About page: an icon, its words, and what it leads to. */
@Composable
private fun InfoRow(icon: String, title: String, sub: @Composable () -> Unit, action: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(c.brand.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, c.textBrand, size = 18.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong)
            sub()
        }
        action?.invoke()
    }
}

@Composable
private fun SubText(text: String) = Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = Nx.colors.textMuted)

/** 3.22 About: what Nexa is, its documents, how to reach the owner, and which versions this is. */
@Composable
fun AboutPage() {
    val c = Nx.colors
    val ctx = LocalContext.current
    val me = Nexa.users.me.collectAsState().value
    var server by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { server = Nexa.auth.loadInstance()?.version }

    PageTitle(t("settings.about.title"))
    // `.aboutHero`: the tile beside the wordmark and the lead, in a card lit by the brand at its corner
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .ring(Radii.Md, c.borderSubtle)
            .background(c.bgRaised, Radii.Md)
            .clip(Radii.Md)
            .drawBehind { drawRect(Brush.radialGradient(listOf(c.brand.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width)) }
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp).shadowLow(Radii.Md, c).background(brandGradient(c), Radii.Md).topEdge(Radii.Md, c.edgeLight), contentAlignment = Alignment.Center) {
            NexaMark(c.onBrand, size = 30.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NexaWordmark(height = 26.dp)
            Txt(t("legal.aboutLead"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(22f)), color = c.textSubtle)
        }
    }

    SectionHeading(t("project.openSource"))
    Hint(t("project.sourceBody"))
    InfoRow("CodeXml", t("project.contributions"), { SubText(t("project.contributeBody")) })
    NxButton(t("project.viewGithub"), { Legal.openGitHub(ctx) }, kind = ButtonKind.SECONDARY)

    SectionHeading(t("settings.about.glance"))
    // the legal words are English in every language on purpose, as the documents are
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((icon, title, body) in listOf(
            Triple("Lock", "legal.hlPrivateTitle", "legal.hlPrivateBody"),
            Triple("Eye", "legal.hlStaffTitle", "legal.hlStaffBody"),
            Triple("Gavel", "legal.hlModerationTitle", "legal.hlModerationBody"),
            Triple("ScrollText", "legal.hlRulesTitle", "legal.hlRulesBody"),
            Triple("Database", "legal.hlDataTitle", "legal.hlDataBody"),
        )) {
            val shape = Radii.Md
            Row(
                Modifier
                    .fillMaxWidth()
                    .shadowLow(shape, c)
                    .ring(shape, c.borderSubtle)
                    .background(c.bgSurface, shape)
                    .background(sheen(c), shape)
                    .topEdge(shape, c.edgeLight)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).background(c.brand.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, c.textBrand, size = 16.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Txt(t(title), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong)
                    Txt(t(body), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19.6f)), color = c.textSubtle)
                }
            }
        }
    }

    SectionHeading(t("settings.about.documents"), Modifier.settingAnchor("documents"))
    if (!I18n.tag.startsWith("en")) Hint(t("settings.about.englishOnly"), Modifier.padding(bottom = 6.dp))
    val agreed = me?.let { Legal.accepted(it.id) } == true
    for ((which, icon, title) in listOf(Triple("terms", "ScrollText", "legal.termsTitle"), Triple("privacy", "ShieldCheck", "legal.privacyTitle"))) {
        val doc = if (which == "terms") Legal.terms else Legal.privacy
        InfoRow(icon, t(title), {
            Row {
                SubText(t("settings.about.effective", "date" to doc.effective.ifEmpty { Legal.effective }))
                if (agreed) Txt(" · ✓ " + t("settings.about.agreed"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = c.statusOnline)
            }
        }) { NxButton(t("settings.about.read"), { Legal.openDocument(which) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM) }
    }

    SectionHeading(t("settings.about.contact"), Modifier.settingAnchor("contact"))
    InfoRow("Mail", t("settings.about.email"), {
        Txt(
            Legal.contactEmail, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.link,
            modifier = Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Legal.contactEmail}"))) }
            },
        )
    })
    InfoRow("AtSign", t("settings.about.inApp"), { SubText("@${Legal.ownerUsername}") })
    InfoRow("MessagesSquare", t("settings.about.appeals"), { SubText(t("settings.about.appealsSub")) })

    SectionHeading(t("settings.about.version"), Modifier.settingAnchor("version"))
    InfoRow("Info", "Nexa", {
        SubText(t("android.settings.about.app", "version" to BuildConfig.VERSION_NAME))
        SubText(t("settings.about.server", "version" to (server ?: "…")) + " · " + t("settings.about.terms", "version" to Legal.version))
    })
    // the app's own updates, as the desktop app's settings show its own (Settings, Desktop App)
    InfoRow("Download", t("settings.desktop.updates"), { fish.nexa.ui.AppUpdate.StatusLine(Modifier.padding(top = 2.dp)) })
    InfoRow("Sparkles", t("whatsNew.title"), { SubText(t("settings.about.whatsNewSub")) }) {
        NxButton(t("settings.about.show"), { fish.nexa.ui.WhatsNew.openAll() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
    SectionHeading(t("settings.about.sources"), Modifier.settingAnchor("sources"))
    InfoRow("Clapperboard", "TMDB", { SubText(t("widgets.screen.tmdbNotice")) })
    InfoRow("Library", t("settings.about.sourcesOthers"), { SubText(t("settings.about.sourcesOthersSub")) })
}

// ---------------------------------------------------------------------------

private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
private fun JsonObject?.num(k: String) = (this?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject?.flag(k: String) = (this?.get(k) as? JsonPrimitive)?.booleanOrNull ?: false

/** 3.24 Nexa Admin: the instance's figures, who may sign up, the admin panel, legal reports and crashes. */
@Composable
fun AdminPage() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val me = Nexa.users.me.collectAsState().value
    val nexa by Nexa.misc.nexa.collectAsState()
    var stats by remember { mutableStateOf<JsonObject?>(null) }
    var settings by remember { mutableStateOf<JsonObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    suspend fun load() {
        failed = false
        try {
            val s = scope.async { Nexa.api.get("/admin/stats") as? JsonObject }
            val st = scope.async { Nexa.api.get("/admin/settings") as? JsonObject }
            stats = s.await()
            settings = st.await()
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(Unit) { load() }
    val fmt = remember(I18n.tag) { NumberFormat.getInstance(I18n.locale) }

    PageTitle(t("settings.admin.nexaAdmin"))
    Hint(t("settings.admin.theseSettingsApplyToEveryone"))

    SectionHeading(t("server.channelSettings.overview"))
    val s = stats
    val st = settings
    when {
        failed -> LoadFailed(t("moderation.loadFailed")) { scope.launch { load() } }
        s == null || st == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() }
        else -> {
            Column(Modifier.fillMaxWidth()) {
                FieldRow(t("settings.admin.users"), t("settings.admin.usersOnline", "users" to fmt.format(s.num("users")), "online" to fmt.format(s.num("online"))), Modifier.padding(vertical = 8.dp))
                FieldRow(t("rail.rail.servers"), fmt.format(s.num("servers")), Modifier.padding(vertical = 8.dp))
                FieldRow(t("modals.quickSwitcher.messages"), fmt.format(s.num("messages")), Modifier.padding(vertical = 8.dp))
                FieldRow(t("settings.admin.storedFiles"), "${fmt.format(s.num("blobs"))} · ${Time.bytes(s.num("blob_bytes"))}", Modifier.padding(vertical = 8.dp))
                FieldRow(t("settings.admin.version"), s.str("version") ?: "", Modifier.padding(vertical = 8.dp))
                val rt = s["realtime"] as? JsonObject
                if (rt != null && rt.num("limit_bytes") > 0) {
                    fun gb(b: Long): String {
                        val v = b / 1e9
                        return NumberFormat.getInstance(I18n.locale).apply { maximumFractionDigits = if (v < 10) 1 else 0 }.format(v) + " GB"
                    }
                    val warn = rt.flag("calls_paused") || rt.flag("video_paused")
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Txt(t("settings.admin.callsThisMonth"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted)
                        Txt(t("settings.admin.callUsage", "used" to gb(rt.num("used_bytes")), "limit" to gb(rt.num("limit_bytes"))), style = Nx.type.body, color = if (warn) c.textWarning else c.text)
                        if (rt.flag("calls_paused")) Txt(t("settings.admin.callsPaused"), style = Nx.type.bodySmall, color = c.textWarning)
                        else if (rt.flag("video_paused")) Txt(t("settings.admin.callsVideoPaused"), style = Nx.type.bodySmall, color = c.textWarning)
                    }
                }
            }

            if (st.flag("storage_remote")) {
                FileStorageSection(s["storage"] as? JsonObject, (st["storage_budget_cents"] as? JsonPrimitive)?.longOrNull) { next ->
                    settings = next
                    // whether uploads are paused follows the new limit at once
                    scope.launch { runCatching { Nexa.api.get("/admin/stats") as? JsonObject }.getOrNull()?.let { stats = it } }
                }
            }

            SectionHeading(t("settings.admin.registration"))
            val mode = st.str("registration") ?: "open"
            fun patch(body: JsonObject, failText: String, okText: String? = null, optimistic: JsonObject? = null) {
                val before = settings
                if (optimistic != null) settings = optimistic
                scope.launch {
                    try {
                        (Nexa.api.request("PATCH", "/admin/settings", body) as? JsonObject)?.let { settings = it }
                        okText?.let { Nexa.ui.toast(it, "success") }
                    } catch (e: Exception) {
                        settings = before
                        Nexa.ui.toast((e as? ApiError)?.message ?: failText, "error")
                    }
                }
            }
            for ((value, label, desc) in listOf(
                Triple("open", "settings.admin.open", "settings.admin.regOpen"),
                Triple("invite", "settings.admin.inviteOnly", "settings.admin.regInvite"),
                Triple("closed", "settings.admin.closed", "settings.admin.regClosed"),
            )) RadioCard(t(label), mode == value, {
                if (mode != value) patch(buildJsonObject { put("registration", value) }, t("settings.admin.couldNotChangeThePolicy"), optimistic = JsonObject(LinkedHashMap(st).apply { put("registration", JsonPrimitive(value)) }))
            }, t(desc))
            val config = st.str("config_registration") ?: ""
            val overridden = (st["overrides"] as? JsonObject)?.get("registration") != null
            if (overridden) {
                Hint(t("settings.admin.overridesConfig", "value" to config))
                NxButton(t("settings.admin.useConfigFile"), { patch(buildJsonObject { put("registration", "") }, t("settings.admin.couldNotChangeThePolicy")) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, modifier = Modifier.padding(top = 8.dp))
            } else Hint(t("settings.admin.followsConfig", "value" to config))
            val keySet = st.flag("registration_key_set")
            NxTextField(
                key, { key = it.take(128) },
                modifier = Modifier.padding(top = 14.dp),
                label = t(if (keySet) "settings.admin.registrationKeySet" else "settings.admin.registrationKeyNotSet"),
                placeholder = t("settings.admin.newKey"), hint = t("settings.admin.shareThisWithPeopleYouWant"), imeAction = ImeAction.Done,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxButton(t("chat.editMessage.save"), {
                    patch(buildJsonObject { put("registration_key", key) }, t("settings.admin.couldNotSaveTheKey"), t("settings.admin.registrationKeySaved"))
                    key = ""
                }, size = ButtonSize.SM)
                if (keySet) NxButton(t("modals.quickSwitcher.clear"), { patch(buildJsonObject { put("registration_key", "") }, t("settings.admin.couldNotClearTheKey"), t("settings.admin.registrationKeyCleared")) }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
            }
            // AdminPage.tsx: the two-accounts-a-day limit per address, on unless turned off
            val signupLimit = st["signup_ip_limit"]?.let { (it as? JsonPrimitive)?.content != "false" } ?: true
            ToggleRow(t("settings.admin.signupIpLimit"), signupLimit, { on ->
                patch(buildJsonObject { put("signup_ip_limit", on) }, t("settings.admin.couldNotChangeTheSignupLimit"), optimistic = JsonObject(LinkedHashMap(st).apply { put("signup_ip_limit", JsonPrimitive(on)) }))
            }, description = t("settings.admin.signupIpLimitHint"))
            Hint(
                t(
                    "settings.admin.configLimits",
                    "uploads" to Time.bytes(st.num("max_upload_bytes")), "avatars" to Time.bytes(st.num("max_avatar_bytes")),
                    "emoji" to Time.bytes(st.num("max_emoji_bytes")), "stickers" to Time.bytes(st.num("max_sticker_bytes")),
                    "gif" to t(if (st.flag("gif_search")) "settings.admin.on" else "settings.admin.off"),
                    "turn" to t(if (st.flag("turn")) "settings.admin.configured" else "settings.admin.notConfigured"),
                ),
                Modifier.padding(top = 12.dp),
            )
        }
    }

    SectionHeading(t("settings.admin.users"))
    DeviceRow(t("moderation.title"), t("settings.admin.panelMoved"), "Shield", actions = {
        NxButton(t("settings.admin.openPanel"), {
            fish.nexa.ui.layers.Layers.clear()
            fish.nexa.ui.nav.Nav.go(fish.nexa.ui.nav.Route.Admin())
        }, size = ButtonSize.SM)
    })

    val ownerId = ((nexa as? JsonObject)?.get("staff") as? JsonObject).str("owner_id")
    if (ownerId != null && ownerId == me?.id) LegalReportSection()
    CrashesSection()
}

/** A limit typed in dollars ("50", "12.5", "7,25"), in cents, or null when it is not one (AdminPage.tsx parseDollars). */
private fun parseDollars(raw: String): Long? {
    val m = Regex("""^(\d{1,6})(?:[.,](\d{0,2}))?$""").matchEntire(raw.trim()) ?: return null
    val cents = m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
    return if (cents <= 10_000_000) cents else null
}

private fun dollars(cents: Long): String =
    NumberFormat.getCurrencyInstance(I18n.locale).apply { currency = java.util.Currency.getInstance("USD") }.format(cents / 100.0)

/**
 * File Storage (AdminPage.tsx FileStorage): what keeping attachments in
 * Cloudflare R2 costs this month, against the limit the server holds new
 * files to, and where that limit is set. Shown only while attachments go to
 * a bucket.
 */
@Composable
private fun FileStorageSection(meter: JsonObject?, limitCents: Long?, onSaved: (JsonObject) -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val shown = limitCents?.let { String.format(java.util.Locale.US, "%.2f", it / 100.0) } ?: ""
    var draft by remember(shown) { mutableStateOf(shown) }
    var busy by remember { mutableStateOf(false) }
    val cents = parseDollars(draft)
    fun save(next: Long?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val body = buildJsonObject { if (next == null) put("storage_budget", JsonNull) else put("storage_budget", next) }
                (Nexa.api.request("PATCH", "/admin/settings", body) as? JsonObject)?.let(onSaved)
                Nexa.ui.toast(t(if (next == null) "settings.admin.limitRemoved" else "settings.admin.limitSaved"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("settings.admin.couldNotChangeTheLimit"), "error")
            } finally {
                busy = false
            }
        }
    }
    SectionHeading(t("settings.admin.fileStorage"))
    Hint(t("settings.admin.fileStorageHint"))
    if (meter == null) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() }
    } else {
        val held = meter.flag("held")
        val limit = (meter["limit_cents"] as? JsonPrimitive)?.longOrNull
        val cost = meter.num("cost_cents")
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Txt(t("settings.admin.storageThisMonth"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted)
                Txt(
                    if (limit == null) t("settings.admin.storageCostNoLimit", "cost" to dollars(cost)) else t("settings.admin.storageCost", "cost" to dollars(cost), "limit" to dollars(limit)),
                    style = Nx.type.body.copy(fontFeatureSettings = "tnum"), color = if (held) c.textWarning else c.text,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (held) Txt(t("settings.admin.storagePaused"), style = Nx.type.bodySmall, color = c.textWarning)
                // the upload allowance's meter over the composer, drawn the same way
                if (limit != null) {
                    val share = (cost.toFloat() / maxOf(1L, limit)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.textStrong.copy(alpha = 0.08f))) {
                        if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(if (held) c.warning else c.textMuted, CircleShape))
                    }
                }
            }
            FieldRow(t("settings.admin.storageInBucket"), Time.bytes(meter.num("stored_bytes")), Modifier.padding(vertical = 8.dp))
            FieldRow(t("settings.admin.storageWaiting"), Time.bytes(meter.num("waiting_bytes")), Modifier.padding(vertical = 8.dp))
        }
        // where the numbers come from: Cloudflare's measurements, or this server's count alone
        val measuredAt = meter.num("measured_at")
        Hint(
            when {
                measuredAt > 0 -> t("settings.admin.storageMeasured", "time" to Time.time(measuredAt))
                meter.flag("measuring") -> t("settings.admin.storageMeasuring")
                else -> t("settings.admin.storageEstimated")
            },
        )
    }
    NxTextField(
        draft, { draft = it.take(9) },
        modifier = Modifier.padding(top = 14.dp),
        label = t("settings.admin.monthlyLimit"), placeholder = t("settings.admin.noLimit"), hint = t("settings.admin.monthlyLimitHint"),
        keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done,
        onIme = { if (cents != null && cents != limitCents) save(cents) },
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NxButton(t("chat.editMessage.save"), { cents?.let { save(it) } }, size = ButtonSize.SM, enabled = !busy && cents != null && cents != limitCents)
        if (limitCents != null) NxButton(t("settings.admin.removeLimit"), { save(null) }, kind = ButtonKind.GHOST, size = ButtonSize.SM, enabled = !busy)
    }
}

/** Everything Nexa already holds about one account, compiled for an officer with valid legal process (owner only). */
@Composable
private fun LegalReportSection() {
    val c = Nx.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var who by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<JsonObject?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    val save = rememberTextFileSaver()
    fun compile() {
        if (busy || who.isBlank()) return
        busy = true
        scope.launch {
            try {
                report = Nexa.api.get("/admin/legal-report?user=" + java.net.URLEncoder.encode(who.trim(), "UTF-8")) as? JsonObject
            } catch (e: Exception) {
                report = null
                Nexa.ui.toast((e as? ApiError)?.message ?: t("settings.admin.legal.couldNotCompileTheReport"), "error")
            } finally {
                busy = false
            }
        }
    }
    SectionHeading(t("settings.admin.legal.lawEnforcementResponse"))
    Hint(t("settings.admin.legal.compileWhatNexaHolds"), Modifier.padding(bottom = 10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NxTextField(who, { who = it }, modifier = Modifier.weight(1f), placeholder = t("settings.admin.legal.usernameOrAccountId"), imeAction = ImeAction.Go, onIme = { compile() })
        NxButton(t("settings.admin.legal.compileReport"), { compile() }, size = ButtonSize.SM, enabled = who.isNotBlank(), loading = busy)
    }
    report?.let { r ->
        val doc = r.str("document") ?: ""
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(t("settings.admin.legal.compiledForOn", "username" to (r.str("username") ?: ""), "when" to Time.full(r.num("generated_at"))), style = Nx.type.bodySmall, color = c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxButton(t("settings.admin.legal.download"), { save(r.str("filename") ?: "report.txt", doc) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                NxButton(if (copied) t("settings.admin.legal.copied") else t("settings.admin.legal.copy"), {
                    if (copyText(ctx, doc)) copied = true else Nexa.ui.toast(t("settings.admin.legal.couldNotReachTheClipboardSelect"), "error")
                }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                NxButton(t("modals.quickSwitcher.clear"), { report = null }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).ring(Radii.Sm, c.borderSubtle).padding(10.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer { Txt(doc, style = Nx.type.code.copy(fontSize = Nx.type.sp(11f), lineHeight = Nx.type.sp(15f)), color = c.text) }
                }
            }
        }
    }
}

/** What the app reported from people's browsers and phones: the error, where, and which build. */
@Composable
private fun CrashesSection() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JsonObject>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        failed = false
        list = null
        try {
            list = (Nexa.api.get("/admin/errors?limit=100") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(Unit) { load() }
    SectionHeading(t("settings.admin.crashes"))
    Hint(t("settings.admin.crashesHint"), Modifier.padding(bottom = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
        NxButton(t("settings.admin.refresh"), { scope.launch { load() } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        if (!list.isNullOrEmpty()) NxButton(t("modals.quickSwitcher.clear"), {
            scope.launch {
                try {
                    Nexa.api.request("DELETE", "/admin/errors")
                    list = emptyList()
                    Nexa.ui.toast(t("settings.admin.crashLogCleared"), "success")
                } catch (e: Exception) {
                    Nexa.ui.toast(describeError(e, t("settings.admin.couldNotClearTheLog")), "error")
                }
            }
        }, kind = ButtonKind.DANGER, size = ButtonSize.SM)
    }
    val l = list
    when {
        failed -> LoadFailed(t("moderation.loadFailed")) { scope.launch { load() } }
        l == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() }
        l.isEmpty() -> EmptyBox(t("settings.admin.nothingHasCrashedGood"))
        else -> for (e in l) {
            val id = e.str("id") ?: e["id"].toString()
            val isOpen = open == id
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(Radii.Sm)
                    .pressable(remember(id) { MutableInteractionSource() }) { open = if (isOpen) null else id }
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt(e.str("message") ?: "", style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textDanger, maxLines = if (isOpen) Int.MAX_VALUE else 2)
                val build = e.str("version")?.ifBlank { null }?.let { t("settings.admin.buildVersion", "version" to it) } ?: t("settings.admin.unknown")
                Txt("${e.str("kind") ?: ""} · ${e.str("path")?.ifBlank { null } ?: "/"} · $build · ${Time.full(e.num("created_at"))}", style = Nx.type.hint, color = c.textMuted)
                if (isOpen) {
                    val text = (e.str("stack")?.ifBlank { null } ?: t("settings.admin.noStack")) +
                        (e.str("component")?.takeIf { it.isNotBlank() }?.let { "\n\ncomponents:$it" } ?: "") +
                        "\n\n" + (e.str("user_agent") ?: "")
                    MonoBox(text, size = 11f)
                }
            }
            Box(Modifier.fillMaxWidth().size(1.dp).background(c.borderSubtle))
        }
    }
}
