package fish.nexa.ui.support

import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideArea
import fish.nexa.ui.components.glideItem
import fish.nexa.ui.components.rememberGlide
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Ids
import fish.nexa.data.Plans
import fish.nexa.media.openExternal
import fish.nexa.net.ApiError
import fish.nexa.net.PlanLimits
import fish.nexa.net.PublicUser
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.WhatsNew
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.openDialog
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.NavButton
import fish.nexa.ui.t
import fish.nexa.ui.theme.NexaColors
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.liftLow
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.panel
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Currency
import kotlin.math.ln

/*
 * Nexa's plans, the account's own and how to get one or give one (the web's
 * components/support): a page of the DM list (/channels/@me/support) and
 * Settings, Subscription, both [SupportPanel]. On a phone one plan shows at a
 * time, as the web's does below 768 px.
 */

private val PLANS = listOf("", "supporter", "pro")

/** What each plan costs when Stripe has not said (US dollars, in cents). */
private val LIST_PRICES = mapOf("supporter" to mapOf("month" to 500L, "year" to 4800L), "pro" to mapOf("month" to 1000L, "year" to 9600L))

fun planName(plan: String): String = when (plan) {
    "pro" -> t("support.plan.pro")
    "supporter" -> t("support.plan.supporter")
    else -> t("support.plan.free")
}

/** A plan's own light: Supporter rose, Pro the accent, the free one muted. */
fun tint(c: NexaColors, plan: String): Color = when (plan) {
    "pro" -> c.tierPro
    "supporter" -> c.tierSupporter
    else -> c.textMuted
}

fun tintText(c: NexaColors, plan: String): Color = when (plan) {
    "pro" -> c.textPro
    "supporter" -> c.textSupporter
    else -> c.textSubtle
}

private fun money(cents: Long, currency: String): String {
    val f = java.text.NumberFormat.getCurrencyInstance(fish.nexa.ui.I18n.locale)
    runCatching { f.currency = Currency.getInstance(currency.uppercase()) }
    f.minimumFractionDigits = if (cents % 100 != 0L) 2 else 0
    f.maximumFractionDigits = 2
    return f.format(cents / 100.0)
}

/** How far along a gauge a value sits, on a scale of powers so 100 MB and 4 GB both read. */
private fun gauge(v: Long, lo: Long, hi: Long): Float {
    if (hi <= lo || v <= 0) return 1f
    return 0.14f + 0.86f * (ln(v.toDouble() / lo) / ln(hi.toDouble() / lo)).toFloat().coerceIn(0f, 1f)
}

private fun errorText(e: Exception): String = (e as? ApiError)?.message ?: t("support.error")

/** Goes to the plans from wherever the app is, closing what is in the way. */
fun seePlans() {
    Layers.clear()
    Nav.go(Route.Support)
    Nav.leftOpen = false
}

/** The badge a plan wears (OwnerBadge's PlanBadge): a heart for Supporter, a gem for Pro. */
@Composable
fun PlanBadge(plan: String, modifier: Modifier = Modifier) {
    if (plan != "supporter" && plan != "pro") return
    val c = Nx.colors
    val tone = tint(c, plan)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .background(tone.copy(alpha = 0.16f), shape)
            .ring(shape, tone.copy(alpha = 0.38f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (plan == "pro") "Gem" else "Heart", tintText(c, plan), size = 12.dp, filled = plan == "supporter")
        Txt(planName(plan), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(650)), color = tintText(c, plan), maxLines = 1)
    }
}

/** The page in the DM list. */
@Composable
fun SupportPage() {
    val c = Nx.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .boxShadow(RoundedCornerShape(0.dp), 8f, 16f, c.shadeAt(0.9f), spread = -14f)
                .background(c.bgRaised)
                .background(sheen(c))
                .padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavButton()
            Icon("Heart", c.tierSupporter, size = 20.dp)
            Txt(t("support.nav"), style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(660)), color = c.textStrong, maxLines = 1)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 920.dp)) { SupportPanel() }
        }
    }
}

/** The plans, the account's standing, its uploads, boosts and gifts. Settings, Subscription shows it without the Terms link. */
@Composable
fun SupportPanel(inSettings: Boolean = false) {
    val c = Nx.colors
    val me by Nexa.users.me.collectAsState()
    val plan = me?.plan ?: ""
    val billing by Plans.billing.collectAsState()
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf("month") }
    var busy by remember { mutableStateOf<String?>(null) }
    var shown by remember(plan) { mutableStateOf(if (plan == "") "supporter" else plan) }
    val info = Plans.info()
    val enabled = billing?.enabled ?: info.enabled

    LaunchedEffect(plan) { Plans.load() }

    fun price(p: String, per: String): String {
        val got = billing?.prices?.get(p)?.get(per)
        return if (got != null) money(got.amount, got.currency) else money(LIST_PRICES[p]!![per]!!, "usd")
    }

    fun go(key: String, path: String, body: JsonObject? = null) {
        busy = key
        scope.launch {
            try {
                val res = Nexa.api.request("POST", path, body) as JsonObject
                openExternal(res["url"]!!.jsonPrimitive.content)
            } catch (e: ApiError) {
                if (e.code == "already_subscribed") {
                    busy = null
                    go("manage", "/billing/portal")
                    return@launch
                }
                Nexa.ui.toast(errorText(e), "error")
            } catch (e: Exception) {
                Nexa.ui.toast(t("support.error"), "error")
            }
            busy = null
        }
    }
    val manage = { go("manage", "/billing/portal") }
    fun buy(p: String) = go(p, "/billing/checkout", buildJsonObject { put("plan", p); put("period", period) })

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // the statement
        val heroShape = Radii.Lg
        Column(
            Modifier
                .fillMaxWidth()
                .panel(heroShape, c)
                .background(Brush.radialGradient(listOf(c.tierSupporter.copy(alpha = 0.16f), Color.Transparent), center = Offset.Zero, radius = 900f), heroShape)
                .background(Brush.radialGradient(listOf(c.tierPro.copy(alpha = 0.18f), Color.Transparent), center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), radius = 900f), heroShape)
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val heart = RoundedCornerShape(15.dp)
            Box(
                Modifier
                    .size(44.dp)
                    .boxShadow(heart, 14f, 30f, c.tierSupporter.copy(alpha = 0.9f), spread = -12f)
                    .background(Brush.linearGradient(listOf(mixOklab(c.tierSupporter, Color.White, 0.14f), c.tierSupporter, mixOklab(c.tierSupporter, c.tierPro, 0.45f))), heart)
                    .topEdge(heart, Color.White.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) { Icon("Heart", Color.White, size = 24.dp, filled = true) }
            Txt(t("support.title"), style = Nx.type.title.copy(fontSize = Nx.type.sp(23f), lineHeight = Nx.type.sp(26.5f)), color = c.textStrong, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
            Txt(t("support.lede"), style = Nx.type.body.copy(lineHeight = Nx.type.sp(24f)), color = c.textSubtle)
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (plan == "") Txt(t("support.onFree"), style = Nx.type.bodySmall, color = c.textMuted)
                else {
                    PlanBadge(plan)
                    val b = billing
                    Txt(
                        when {
                            b == null || b.periodEnd == 0L -> t("support.thanks")
                            b.cancelAtEnd -> t("support.endsOn", "date" to Time.shortDate(b.periodEnd))
                            else -> t("support.renewsOn", "date" to Time.shortDate(b.periodEnd))
                        },
                        style = Nx.type.bodySmall, color = c.textMuted,
                    )
                }
                if (billing?.manageable == true) NxButton(t("support.manage"), manage, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, loading = busy == "manage")
            }
            if (!enabled) Txt(t("support.notOpen"), style = Nx.type.bodySmall, color = c.text, modifier = Modifier.padding(top = 6.dp))
        }

        // by the month or the year
        if (plan == "") Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Segmented(listOf("month" to t("support.monthly"), "year" to t("support.yearly")), period, { period = it }, t("support.period"), fill = false) { key ->
                if (key == "year") Txt(t("support.twoMonthsFree"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(600)), color = c.textSupporter)
            }
        }

        // one plan at a time
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in PLANS) {
                val on = shown == p
                val shape = Radii.Sm
                val tone = tint(c, p)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .panel(shape, c, fill = if (on) mixOklab(c.bgRaised, tone, 0.10f) else c.bgRaised)
                        .then(if (on) Modifier.background(Brush.verticalGradient(0.92f to Color.Transparent, 0.92f to tone), shape) else Modifier)
                        .clip(shape)
                        .pressable(remember { MutableInteractionSource() }) { shown = p }
                        .semantics { role = Role.Tab; selected = on },
                    contentAlignment = Alignment.Center,
                ) { Txt(planName(p), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted) }
            }
        }

        PlanCard(shown, plan, period, enabled, busy, billing?.manageable == true, ::price, { buy(it) }, manage)
        Txt(t("support.everythingElse"), style = Nx.type.bodySmall, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())

        if (enabled) PayWhatYouWant()
        Refunds(inSettings)

        // this account's 30 days
        val used = billing?.monthUsed ?: 0L
        val room = (billing?.limits ?: Plans.limitsOf(plan)).monthUploadMax
        val freesAt = billing?.monthFreesAt ?: 0L
        Section(t("support.yourStorage"), t("support.storageOf", "used" to Time.bytes(used), "room" to Time.bytes(room))) {
            Meter(used.toFloat() / maxOf(1L, room), full = used >= room, label = t("support.yourStorage"))
            Hint(if (used >= room && freesAt > 0) t("support.storageFreesAt", "date" to Time.shortDate(freesAt)) else t("support.storageHint"))
        }

        if (plan == "pro") Boosts()
        if (!billing?.gifts.isNullOrEmpty()) GiftsGot()
        if (enabled) GiveAPlan()
        if (!billing?.giftLinks.isNullOrEmpty()) GiftLinksMade()
    }
}

@Composable
private fun PlanCard(
    p: String,
    mine: String,
    period: String,
    enabled: Boolean,
    busy: String?,
    manageable: Boolean,
    price: (String, String) -> String,
    buy: (String) -> Unit,
    manage: () -> Unit,
) {
    val c = Nx.colors
    val info = Plans.info()
    val l = Plans.limitsOf(p)
    val tone = tint(c, p)
    val shape = Radii.Lg
    Column(
        Modifier
            .fillMaxWidth()
            .panel(shape, c)
            .clip(shape)
            .semantics { contentDescription = t("support.plans") },
    ) {
        // the plan's name and price, under its own light
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (p == "") Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)) else Brush.verticalGradient(listOf(tone.copy(alpha = 0.10f), Color.Transparent)))
                .then(if (p == "") Modifier else Modifier.background(Brush.verticalGradient(0f to tone, 0.04f to tone, 0.04f to Color.Transparent)))
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Txt(planName(p), style = Nx.type.heading.copy(fontSize = Nx.type.sp(19f)), color = if (p == "") c.textStrong else tintText(c, p))
            Txt(
                if (p == "") t("support.freePrice") else if (period == "month" || mine != "") t("support.perMonth", "price" to price(p, "month")) else t("support.perYear", "price" to price(p, "year")),
                style = Nx.type.bodySmall, color = c.textMuted,
            )
        }
        val wash = if (p == "") Color.Transparent else tone.copy(alpha = 0.04f)
        @Composable
        fun Line(label: String, value: @Composable ColumnScope.() -> Unit) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(label, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textSubtle, modifier = Modifier.weight(0.9f).padding(horizontal = 16.dp, vertical = 12.dp))
                Column(Modifier.weight(1f).background(wash).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = value)
            }
        }
        @Composable
        fun Value(text: String) = Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong)
        @Composable
        fun Small(text: String) = Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
        @Composable
        fun None() = Txt("—", style = Nx.type.body, color = c.textMuted, modifier = Modifier.semantics { contentDescription = t("support.notIncluded") })
        @Composable
        fun Gauge(v: Long, lo: Long, hi: Long) {
            Box(Modifier.fillMaxWidth().height(5.dp).background(c.bgActive, CircleShape)) {
                Box(Modifier.fillMaxWidth(gauge(v, lo, hi)).fillMaxHeight().background(if (p == "") c.textMuted else tone, CircleShape))
            }
        }
        Line(t("support.row.file")) { Value(Time.bytes(l.fileMax)); Gauge(l.fileMax, info.free.fileMax, info.pro.fileMax) }
        Line(t("support.row.storage")) { Value(Time.bytes(l.monthUploadMax)); Gauge(l.monthUploadMax, info.free.monthUploadMax, info.pro.monthUploadMax) }
        Line(t("support.row.share")) {
            Value(if (l.shareQuality) t("support.share.quality") else t("support.share.performance"))
            Small(if (p == "") t("support.share.performanceHint") else t("support.share.upTo", "n" to Math.round(l.shareMaxBps / 1e6)))
        }
        Line(t("support.row.effects")) { if (l.nameEffects) Value(t("support.effects.list")) else None() }
        Line(t("support.row.badge")) { if (p == "") None() else PlanBadge(p) }
        Line(t("support.row.boosts")) {
            if (l.boosts > 0) {
                Value(l.boosts.toString())
                Small(t("support.boosts.hint", "size" to Time.bytes(info.boostFileMax)))
            } else None()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
        Box(Modifier.fillMaxWidth().background(wash).padding(16.dp)) {
            when {
                p == mine -> Txt(t("support.current"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                p == "" -> Unit
                mine != "" -> if (manageable) NxButton(t("support.switch", "plan" to planName(p)), manage, kind = ButtonKind.SECONDARY, block = true, loading = busy == "manage")
                else -> TierButton(t("support.get", "plan" to planName(p)), p, { buy(p) }, enabled = enabled && busy == null, loading = busy == p)
            }
        }
    }
}

/** A buying button in its plan's own light (Support.module.css `.buy`). */
@Composable
fun TierButton(text: String, plan: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false, icon: String? = null) {
    val c = Nx.colors
    val tone = tint(c, plan)
    val shape = Radii.Sm
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (enabled) Modifier.boxShadow(shape, 8f, 20f, tone.copy(alpha = 0.9f), spread = -10f) else Modifier)
            .background(Brush.verticalGradient(listOf(mixOklab(tone, Color.White, 0.12f), tone)), shape)
            .topEdge(shape, Color.White.copy(alpha = 0.25f))
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) fish.nexa.ui.components.LoadingDots(Color.White)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, Color.White, size = 16.dp)
            Txt(text, style = Nx.type.button, color = Color.White, maxLines = 1)
        }
    }
}

/** A pill of choices, one picked (Support.module.css `.period`), the pill sliding to the next one picked (components/Glide.kt). */
@Composable
fun Segmented(
    options: List<Pair<String, String>>,
    picked: String,
    onPick: (String) -> Unit,
    label: String,
    fill: Boolean = true,
    tones: Map<String, Color> = emptyMap(),
    extra: @Composable (String) -> Unit = {},
) {
    val c = Nx.colors
    val pill = RoundedCornerShape(999.dp)
    val glide = rememberGlide(picked)
    Box(
        (if (fill) Modifier.fillMaxWidth() else Modifier)
            .background(c.bgInput, pill)
            .ring(pill, c.borderSubtle)
            .padding(4.dp)
            .glideArea(glide)
            .semantics { contentDescription = label },
    ) {
    GlidePill(glide, Modifier.liftLow(pill, c).background(c.bgSurface, pill).topEdge(pill, c.edgeLight))
    Row(
        if (fill) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((key, text) in options) {
            val on = picked == key
            Row(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .height(40.dp)
                    .glideItem(glide, key)
                    .clip(pill)
                    .pressable(remember { MutableInteractionSource() }) { onPick(key) }
                    .semantics { role = Role.RadioButton; selected = on }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Txt(text, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (on) (tones[key] ?: c.textStrong) else c.textMuted, maxLines = 1)
                extra(key)
            }
        }
    }
    }
}

@Composable
private fun Section(title: String, aside: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().background(c.bgRaised, Radii.Md).ring(Radii.Md, c.panelEdge).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (aside == null) H2(title)
        else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            H2(title)
            Txt(aside, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        }
        content()
    }
}

@Composable
private fun H2(text: String) = Txt(text, style = Nx.type.heading.copy(fontSize = Nx.type.sp(16f), lineHeight = Nx.type.sp(21f)), color = Nx.colors.textStrong, modifier = Modifier.semantics { heading() })

@Composable
private fun Hint(text: String) = Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.8f)), color = Nx.colors.textMuted)

@Composable
private fun Meter(share: Float, full: Boolean, label: String) {
    val c = Nx.colors
    Box(Modifier.fillMaxWidth().height(8.dp).background(c.bgActive, CircleShape).semantics { contentDescription = label }) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().background(if (full) c.danger else c.tierSupporter, CircleShape))
    }
}

@Composable
private fun ListRow(icon: @Composable () -> Unit, name: String, personId: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = Nx.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgSurface, Radii.Sm).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        fish.nexa.ui.chat.PersonName(personId, name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Glyph(name: String, tone: Color, tone2: Color = tone) {
    Box(Modifier.size(32.dp).background(tone.copy(alpha = 0.16f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(name, tone2, size = 16.dp) }
}

/** A Pro account's two boosts: where they are, and putting one on a server. */
@Composable
private fun Boosts() {
    val c = Nx.colors
    val billing by Plans.billing.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    val scope = rememberCoroutineScope()
    val boosts = billing?.boosts ?: emptyList()
    val max = billing?.limits?.boosts ?: 2
    fun act(f: suspend () -> Unit) {
        scope.launch {
            try {
                f()
                Plans.load()
            } catch (e: Exception) {
                Nexa.ui.toast(errorText(e), "error")
            }
        }
    }
    Section(t("support.boosts.title"), t("support.boosts.used", "used" to boosts.size, "max" to max)) {
        Hint(t("support.boosts.about", "size" to Time.bytes(Plans.info().boostFileMax)))
        for (b in boosts) ListRow({ Glyph("Rocket", c.tierPro, c.textPro) }, b.serverName) {
            fish.nexa.ui.components.IconButton("X", t("support.boosts.remove", "server" to b.serverName), { act { Nexa.api.request("DELETE", "/billing/boosts/${b.id}") } })
        }
        if (boosts.size < max) {
            val choices = servers.values.map { it.server.id to it.server.name }.sortedBy { it.second.lowercase() }
            var pick by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { fish.nexa.ui.components.SelectField(null, listOf("" to t("support.boosts.pick")) + choices, pick) { pick = it } }
                NxButton(t("support.boosts.add"), {
                    val id = pick
                    act { Nexa.api.request("POST", "/billing/boosts", buildJsonObject { put("server_id", id) }); pick = "" }
                }, size = ButtonSize.TOUCH, enabled = pick.isNotEmpty())
            }
        }
    }
}

/** Plans other people gave this account, while they last. */
@Composable
private fun GiftsGot() {
    val c = Nx.colors
    val gifts = Plans.billing.collectAsState().value?.gifts.orEmpty()
    val meId = Nexa.users.me.collectAsState().value?.id
    // time this account paid for itself (pay what you want) is kept as a gift from itself
    val own = gifts.any { it.fromId == meId }
    Section(if (own) t("support.gift.timeTitle") else t("support.gift.got")) {
        for (g in gifts) ListRow(
            { if (g.fromId == meId) Glyph("HandCoins", c.tierSupporter, c.textSupporter) else Glyph("Gift", c.tierSupporter, c.textSupporter) },
            if (g.fromId == meId) t("support.gift.yours", "plan" to planName(g.plan)) else t("support.gift.from", "plan" to planName(g.plan), "name" to g.fromName),
        ) {
            Txt(t("support.gift.until", "date" to Time.shortDate(g.endsAt)), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        }
    }
}

/** Who a gift can go to: friends first, then the people of one-to-one conversations, never a bot. */
private fun giftable(): List<PublicUser> {
    val meId = Nexa.users.meId
    val ids = LinkedHashSet<String>()
    for (r in Nexa.relationships.byId.value.values) if (r.type == "friend") ids += r.user.id
    for (ch in Nexa.channels.byId.value.values) if (ch.type == "dm") ids += ch.recipientIds
    meId?.let { ids -= it }
    val users = Nexa.users.byId.value
    return ids.mapNotNull { users[it] ?: Nexa.relationships.byId.value[it]?.user }.filter { !it.isBot && !it.isSystem }
}

private fun nameOf(u: PublicUser) = u.displayName.ifEmpty { u.username }

/** The address of a gift link, as it is shared. */
fun giftLinkUrl(code: String): String = fish.nexa.ui.markdown.AppLinks.shareOrigin(Nexa.api.baseUrl).trimEnd('/') + "/gift/" + code

/** How long a gift lasts, as its title says it ("A Month of Pro"). */
fun giftTitle(plan: String, days: Int): String = t(if (days >= 365) "support.giftLink.yearOf" else "support.giftLink.monthOf", "plan" to planName(plan))

/** The gift links this account bought: copy one to send it, and who has claimed which. */
@Composable
private fun GiftLinksMade() {
    val c = Nx.colors
    val links = Plans.billing.collectAsState().value?.giftLinks.orEmpty()
    Section(t("support.giftLink.yours")) {
        Hint(t("support.giftLink.yoursHint"))
        for (l in links) {
            Row(
                Modifier.fillMaxWidth().background(c.bgSurface, Radii.Sm).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Glyph(if (l.claimed) "Check" else "Link2", c.tierSupporter, c.textSupporter)
                Column(Modifier.weight(1f)) {
                    Txt(giftTitle(l.plan, l.days), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                    Txt(
                        if (l.claimed) t("support.giftLink.claimedBy", "name" to l.claimedName.ifEmpty { t("support.giftLink.someone") }) else t("support.giftLink.waiting"),
                        style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1,
                    )
                }
                if (!l.claimed) NxButton(t("support.giftLink.copy"), { copyGiftLink(l.code) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "Copy")
            }
        }
    }
}

private fun copyGiftLink(code: String) {
    val cm = Nexa.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Nexa", giftLinkUrl(code)))
    Nexa.ui.toast(t("support.giftLink.copied"), "success")
}

/**
 * What a gift link opens (/gift/<code>): the plan someone bought as a link,
 * and Claim Gift for whoever opens it first. The web's GiftPage.
 */
fun openGiftLink(code: String) {
    Nexa.scope.launch {
        var gift = try {
            fish.nexa.net.wireJson.decodeFromJsonElement(fish.nexa.net.GiftLinkView.serializer(), Nexa.api.request("GET", "/gifts/" + java.net.URLEncoder.encode(code, "UTF-8")))
        } catch (e: Exception) {
            Nexa.ui.toast(errorText(e), "error")
            return@launch
        }
        fish.nexa.ui.layers.openSheet("gift-link") { close ->
            val c = Nx.colors
            var shown by remember { mutableStateOf(gift) }
            var busy by remember { mutableStateOf(false) }
            val g = shown
            val title = giftTitle(g.plan, g.days)
            val tone = if (g.claimedByMe) c.statusOnline else tint(c, g.plan.ifEmpty { "supporter" })
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(64.dp).background(tone.copy(alpha = 0.16f), CircleShape).ring(CircleShape, tone.copy(alpha = 0.42f)), contentAlignment = Alignment.Center) {
                    Icon(if (g.claimedByMe) "Check" else "Gift", tone, size = 28.dp)
                }
                Txt(if (g.claimedByMe) t("support.giftLink.yoursNow") else title, style = Nx.type.title.copy(fontSize = Nx.type.sp(22f)), color = c.textStrong, align = TextAlign.Center)
                Txt(
                    when {
                        g.claimedByMe -> t("support.giftLink.yoursNowBody", "gift" to title)
                        g.mine -> if (g.claimed) t("support.giftLink.mineClaimed") else t("support.giftLink.mine")
                        g.claimed -> t("support.giftLink.taken")
                        else -> t("support.giftLink.fromName", "name" to g.fromName.ifEmpty { t("support.giftLink.someone") })
                    },
                    style = Nx.type.body, color = c.textMuted, align = TextAlign.Center,
                )
                when {
                    g.claimedByMe -> NxButton(t("support.giftLink.seePlan"), { close(); Nav.go(Route.Support) }, size = ButtonSize.LG, block = true)
                    g.mine && !g.claimed -> NxButton(t("support.giftLink.copy"), { copyGiftLink(g.code) }, size = ButtonSize.LG, block = true)
                    !g.mine && !g.claimed -> NxButton(t("support.giftLink.claim"), {
                        busy = true
                        Nexa.scope.launch {
                            try {
                                shown = fish.nexa.net.wireJson.decodeFromJsonElement(fish.nexa.net.GiftLinkView.serializer(), Nexa.api.request("POST", "/gifts/" + java.net.URLEncoder.encode(g.code, "UTF-8") + "/claim"))
                                gift = shown
                                Plans.load()
                            } catch (e: Exception) {
                                Nexa.ui.toast(errorText(e), "error")
                            } finally {
                                busy = false
                            }
                        }
                    }, size = ButtonSize.LG, block = true, loading = busy)
                    else -> {}
                }
                NxButton(t("qrLogin.openNexa"), close, kind = ButtonKind.SECONDARY, size = ButtonSize.LG, block = true, enabled = !busy)
            }
        }
    }
}

/**
 * Gift a Plan: a month or a year of Supporter or Pro for a friend or someone
 * this account talks to, or as a link for anyone, paid once on Stripe's page
 * and never renewed.
 */
@Composable
private fun GiveAPlan() {
    val c = Nx.colors
    val prices = Plans.billing.collectAsState().value?.giftPrices
    val rels by Nexa.relationships.byId.collectAsState()
    val people = remember(rels) { giftable() }
    var to by remember { mutableStateOf(Plans.giftTo) }
    var query by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("supporter") }
    var period by remember { mutableStateOf("month") }
    var busy by remember { mutableStateOf(false) }
    var asLink by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val q = query.trim().lowercase()
    val found = (if (q.isEmpty()) people else people.filter { nameOf(it).lowercase().contains(q) || it.username.lowercase().contains(q) }).take(6)
    val picked = to?.let { id -> Nexa.users.byId.value[id] ?: rels[id]?.user }
    val got = prices?.get(plan)?.get(period)
    val cost = if (got != null) money(got.amount, got.currency) else money(LIST_PRICES[plan]!![period]!!, "usd")

    Column(
        Modifier.fillMaxWidth().background(c.bgRaised, Radii.Md).ring(Radii.Md, c.panelEdge).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph("Gift", c.tierSupporter, c.textSupporter)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                H2(t("support.gift.title"))
                Hint(t("support.gift.about"))
            }
        }
        Segmented(listOf("friend" to t("support.giftLink.toFriend"), "link" to t("support.giftLink.asLink")), if (asLink) "link" else "friend", { asLink = it == "link" }, t("support.gift.who"))
        if (asLink) {
            Hint(t("support.giftLink.about"))
        } else if (picked != null) {
            ListRow({ Avatar(picked.id, nameOf(picked), picked.avatarId, 32.dp) }, nameOf(picked), personId = picked.id) {
                NxButton(t("support.gift.change"), { to = null }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH)
            }
        } else {
            fish.nexa.ui.server.SearchBox(query, { query = it }, t("support.gift.search"))
            if (found.isEmpty()) Hint(if (people.isEmpty()) t("support.gift.nobody") else t("forward.none"))
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = t("support.gift.who") }) {
                for (u in found) {
                    val pill = RoundedCornerShape(999.dp)
                    Row(
                        Modifier
                            .heightIn(min = 40.dp)
                            .background(c.bgSurface, pill)
                            .ring(pill, c.borderSubtle)
                            .clip(pill)
                            .pressable(remember { MutableInteractionSource() }) { to = u.id }
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Avatar(u.id, nameOf(u), u.avatarId, 28.dp)
                        fish.nexa.ui.chat.PersonName(u.id, nameOf(u), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                    }
                }
            }
        }
        Segmented(Plans.PAID.map { it to planName(it) }, plan, { plan = it }, t("support.plans"), tones = mapOf("supporter" to c.textSupporter, "pro" to c.textPro))
        Segmented(listOf("month" to t("support.gift.aMonth"), "year" to t("support.gift.aYear")), period, { period = it }, t("support.period"))
        TierButton(t("support.gift.buy", "price" to cost), plan, {
            val id = to
            if (id == null && !asLink) return@TierButton
            busy = true
            scope.launch {
                try {
                    val body = buildJsonObject { if (asLink) put("link", true) else put("to", id); put("plan", plan); put("period", period) }
                    val res = Nexa.api.request("POST", "/billing/gift", body) as JsonObject
                    openExternal(res["url"]!!.jsonPrimitive.content)
                } catch (e: Exception) {
                    Nexa.ui.toast(errorText(e), "error")
                }
                busy = false
            }
        }, enabled = to != null || asLink, loading = busy, icon = "Gift")
        Hint(t("support.refunds.line"))
    }
}

/** How long an amount buys, in words ("2 months of Pro, then 1 month of Supporter"). */
private fun timeBought(pro: Long, supporter: Long): String {
    val p = if (pro > 0) t("support.pwyw.monthsPro", "count" to pro) else ""
    val sup = if (supporter > 0) t("support.pwyw.monthsSupporter", "count" to supporter) else ""
    return if (p.isNotEmpty() && sup.isNotEmpty()) t("support.pwyw.then", "first" to p, "second" to sup) else p.ifEmpty { sup }
}

private val QUICK = listOf(300L, 500L, 1000L, 2500L, 5000L)

/** The most month tiles drawn before the rest are counted. */
private const val TILES = 24

/**
 * Pay What You Want (the web's PayWhatYouWant): one payment of any amount
 * from a dollar, never renewed, with what it buys shown month by month as it
 * is typed, before Stripe's page opens.
 */
@Composable
private fun PayWhatYouWant() {
    val c = Nx.colors
    val billing by Plans.billing.collectAsState()
    val plan = Nexa.users.me.collectAsState().value?.plan ?: ""
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("10") }
    var busy by remember { mutableStateOf(false) }
    val (supMonth, proMonth) = Plans.monthPrices(billing)
    val cents = Plans.centsOf(text)
    val valid = cents != null && cents in Plans.PWYW_MIN..Plans.PWYW_MAX
    val bought = Plans.payWhatYouWant(if (valid) cents!! else 0, supMonth, proMonth)
    val subscribed = plan != "" && billing?.status in setOf("active", "trialing", "past_due")
    fun usd(v: Long) = money(v, "usd")
    val tone = when {
        bought.pro > 0 -> c.tierPro
        bought.supporter > 0 -> c.tierSupporter
        else -> c.textMuted
    }
    val outcome = when {
        cents == null || cents < Plans.PWYW_MIN -> t("support.pwyw.tooLow", "min" to usd(Plans.PWYW_MIN))
        cents > Plans.PWYW_MAX -> t("support.pwyw.tooHigh", "max" to usd(Plans.PWYW_MAX))
        bought.months == 0L -> t("support.pwyw.thanksOnly", "price" to usd(supMonth))
        else -> t("support.pwyw.gets", "time" to timeBought(bought.pro, bought.supporter))
    }
    fun pay() {
        if (!valid || busy) return
        busy = true
        scope.launch {
            try {
                val res = Nexa.api.request("POST", "/billing/pay-what-you-want", buildJsonObject { put("amount", cents!!) }) as JsonObject
                openExternal(res["url"]!!.jsonPrimitive.content)
            } catch (e: Exception) {
                Nexa.ui.toast(errorText(e), "error")
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().background(c.bgRaised, Radii.Md).ring(Radii.Md, c.panelEdge).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph("HandCoins", c.tierSupporter, c.textSupporter)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                H2(t("support.pwyw.title"))
                Hint(t("support.pwyw.about", "min" to usd(Plans.PWYW_MIN)))
            }
        }
        // the amount, large, as the web draws it
        val field = Radii.Md
        val label = t("support.pwyw.amount")
        Row(
            Modifier
                .background(c.bgInput, field)
                .ring(field, if (valid) c.borderSubtle else c.danger)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        ) {
            val big = Nx.type.title.copy(fontSize = Nx.type.sp(34f), lineHeight = Nx.type.sp(39f), color = if (valid) c.textStrong else c.danger)
            Txt("$", style = Nx.type.title.copy(fontSize = Nx.type.sp(22f), lineHeight = Nx.type.sp(36f)), color = c.textMuted, modifier = Modifier.alignByBaseline().padding(end = 2.dp))
            BasicTextField(
                text,
                { text = it.take(14) },
                textStyle = big,
                singleLine = true,
                cursorBrush = SolidColor(c.textStrong),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { pay() }),
                modifier = Modifier.alignByBaseline().width(IntrinsicSize.Min).widthIn(min = 40.dp, max = 260.dp).semantics { contentDescription = label },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = t("support.pwyw.quick") }) {
            for (q in QUICK) {
                val pill = RoundedCornerShape(999.dp)
                val on = cents == q
                Box(
                    Modifier
                        .height(36.dp)
                        .background(c.bgSurface, pill)
                        .ring(pill, if (on) c.textMuted else c.borderSubtle)
                        .clip(pill)
                        .pressable(remember { MutableInteractionSource() }) { text = (q / 100).toString() }
                        .semantics { role = Role.Button; selected = on }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(usd(q), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted, maxLines = 1) }
            }
        }
        // what it buys: one tile a month, Pro then Supporter
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(c.bgSurface, Radii.Sm).clip(Radii.Sm)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(tone))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (bought.months > 0) {
                    val drawn = if (bought.months > TILES) TILES - 1 else bought.months.toInt()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        for (i in 0 until drawn) Box(Modifier.size(12.dp, 22.dp).background(if (i < bought.pro) c.tierPro else c.tierSupporter, RoundedCornerShape(4.dp)).topEdge(RoundedCornerShape(4.dp), Color.White.copy(alpha = 0.22f)))
                        if (bought.months > TILES) Txt("+${bought.months - drawn}", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.textSubtle, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Txt(outcome, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(21.5f), fontWeight = FontWeight(600)), color = c.textStrong, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Hint(t("support.pwyw.rule", "pro" to usd(proMonth), "supporter" to usd(supMonth)))
                if (subscribed && bought.months > 0) Hint(t("support.pwyw.alongside", "plan" to planName(plan)))
                Hint(t("support.pwyw.once"))
            }
        }
        TierButton(if (valid) t("support.pwyw.pay", "price" to usd(cents!!)) else t("support.pwyw.title"), if (bought.pro > 0) "pro" else "supporter", { pay() }, enabled = valid, loading = busy, icon = "HandCoins")
        Hint(t("support.refunds.line"))
    }
}

/** How refunds and cancelling work, said on the page itself rather than only in the Terms. */
@Composable
private fun Refunds(inSettings: Boolean) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().background(c.bgRaised, Radii.Md).ring(Radii.Md, c.panelEdge).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph("RotateCcw", c.textStrong, c.textSubtle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                H2(t("support.refunds.title"))
                Txt(t("support.refunds.body"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(21.7f)), color = c.text)
                Hint(t("support.refunds.cancel"))
                Hint(t("support.refunds.contact", "email" to fish.nexa.ui.legal.Legal.contactEmail, "username" to fish.nexa.ui.legal.Legal.ownerUsername))
                // words that wrap in the narrow column, where a link button would cut them short
                if (!inSettings) Txt(
                    t("support.termsLink"),
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.8f)),
                    color = c.link,
                    modifier = Modifier.clip(Radii.Sm).pressable(remember { MutableInteractionSource() }) { fish.nexa.ui.legal.Legal.openDocument("terms") }.semantics { role = Role.Button },
                )
            }
        }
    }
}

/** The line both offers give pay what you want (the web's pwywLine). */
fun pwywLine(): String {
    val (sup, pro) = Plans.monthPrices()
    return t("support.pwywShort", "min" to money(Plans.PWYW_MIN, "usd"), "supporter" to money(sup, "usd"), "pro" to money(pro, "usd"))
}

// --- offers -----------------------------------------------------------------

/** The offer while there is one (the web's UpsellHost): what the plan stopped, and the way to the plans. Shell runs it. */
@Composable
fun UpsellHost() {
    val offer by Plans.offered.collectAsState()
    LaunchedEffect(offer) {
        val o = offer ?: return@LaunchedEffect
        val free = Plans.limitsOf("")
        val sup = Plans.limitsOf("supporter")
        val pro = Plans.limitsOf("pro")
        val (title, body, icon) = when (o) {
            is Plans.Offer.File -> Triple(
                t("support.upsell.fileTitle"),
                t("support.upsell.fileBody", "name" to o.name, "size" to Time.bytes(o.size), "free" to Time.bytes(free.fileMax), "supporter" to Time.bytes(sup.fileMax), "pro" to Time.bytes(pro.fileMax)),
                "Upload",
            )
            Plans.Offer.Uploads -> Triple(t("support.upsell.storageTitle"), t("support.upsell.storageBody", "free" to Time.bytes(free.monthUploadMax), "supporter" to Time.bytes(sup.monthUploadMax), "pro" to Time.bytes(pro.monthUploadMax)), "HardDrive")
            Plans.Offer.Share -> Triple(t("support.upsell.shareTitle"), t("support.upsell.shareBody"), "MonitorUp")
            Plans.Offer.Effect -> Triple(t("support.upsell.effectTitle"), t("support.upsell.effectBody"), "Sparkles")
        }
        Plans.dismissOffer()
        openDialog(
            "upsell", title, width = 420.dp,
            footer = { close ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxButton(t("support.upsell.notNow"), close, kind = ButtonKind.SECONDARY)
                    Box(Modifier.width(150.dp)) { TierButton(t("support.upsell.seePlans"), "supporter", { close(); seePlans() }) }
                }
            },
        ) {
            val c = Nx.colors
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                val shape = RoundedCornerShape(14.dp)
                Box(Modifier.size(44.dp).background(c.tierSupporter.copy(alpha = 0.16f), shape), contentAlignment = Alignment.Center) { Icon(icon, c.textSupporter, size = 22.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(body, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(22f)), color = c.textSubtle)
                    Txt(pwywLine(), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19.5f)), color = c.textMuted)
                }
            }
        }
    }
}

private var promptedThisRun = false

/**
 * Once each update, to an account without a plan: what running Nexa costs
 * and what a plan brings, with a small way to say no. It waits for What's New
 * and for nothing else to be open, opens only while plans can be bought, and
 * only for an account that was there before the update (the web's SupportPrompt).
 */
suspend fun maybePrompt() {
    if (promptedThisRun) return
    val me = Nexa.users.me.value ?: return
    while (!Nexa.settings.loaded.value) delay(1000)
    val newest = WhatsNew.releases.firstOrNull()?.id ?: return
    if (me.plan != "" || !Plans.info().enabled || Nexa.settings.supportPromptSeen == newest) return
    if (WhatsNew.unseen(null, Ids.createdAt(me.id)).none { it.id == newest }) return
    // What's New goes first, and the Terms before it
    while (!Layers.isEmpty || WhatsNew.unseen(Nexa.settings.whatsNewSeen, Ids.createdAt(me.id)).isNotEmpty()) delay(1000)
    if (Nexa.users.me.value?.plan != "") return
    promptedThisRun = true
    val sup = Plans.limitsOf("supporter")
    val pro = Plans.limitsOf("pro")
    val perks = listOf(
        "Upload" to t("support.prompt.perkFiles", "supporter" to Time.bytes(sup.fileMax), "pro" to Time.bytes(pro.fileMax)),
        "HardDrive" to t("support.prompt.perkStorage", "supporter" to Time.bytes(sup.monthUploadMax), "pro" to Time.bytes(pro.monthUploadMax)),
        "MonitorUp" to t("support.prompt.perkShare"),
        "Sparkles" to t("support.prompt.perkEffects"),
        "Gem" to t("support.prompt.perkBoosts", "size" to Time.bytes(Plans.info().boostFileMax)),
    )
    openDialog("support-prompt", t("support.prompt.title"), width = 440.dp, onClosed = {
        Nexa.settings.patch(buildJsonObject { put("supportPromptSeen", newest) })
    }) { close ->
        val c = Nx.colors
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val heart = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .size(56.dp)
                    .boxShadow(heart, 14f, 30f, c.tierSupporter.copy(alpha = 0.9f), spread = -12f)
                    .background(Brush.linearGradient(listOf(mixOklab(c.tierSupporter, Color.White, 0.14f), c.tierSupporter, mixOklab(c.tierSupporter, c.tierPro, 0.45f))), heart)
                    .topEdge(heart, Color.White.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) { Icon("Heart", Color.White, size = 28.dp, filled = true) }
            Txt(t("support.prompt.lede"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(23f)), color = c.textSubtle, align = TextAlign.Center)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((icon, text) in perks) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, c.textSupporter, size = 18.dp)
                    Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14.5f)), color = c.text, modifier = Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth().background(c.tierSupporter.copy(alpha = 0.09f), Radii.Sm).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon("HandCoins", c.textSupporter, size = 18.dp)
                Txt(pwywLine(), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, modifier = Modifier.weight(1f))
            }
            TierButton(t("support.prompt.cta"), "supporter", { close(); seePlans() })
            Txt(t("support.refunds.line"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, align = TextAlign.Center)
            NxButton(t("support.prompt.noThanks"), close, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH)
        }
    }
}
