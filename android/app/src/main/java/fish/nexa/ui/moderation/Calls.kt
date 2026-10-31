package fish.nexa.ui.moderation

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.data.CallTotals
import fish.nexa.data.MeterStatus
import fish.nexa.data.ModCallRow
import fish.nexa.data.Moderation
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.NexaColors
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * A size such as "2.4 GB" or "740 MB" (web lib/callUsage.ts formatDataSize):
 * in thousands, as Cloudflare bills and the allowance is set, and gigabytes at
 * most, so "2,292 GB of 1,000 GB" compares at a glance.
 */
fun callSize(bytes: Long): String {
    val n = max(0L, bytes)
    val (size, unit) = when {
        n >= 1_000_000_000L -> 1e9 to MeasureUnit.GIGABYTE
        n >= 1_000_000L -> 1e6 to MeasureUnit.MEGABYTE
        n >= 1_000L -> 1e3 to MeasureUnit.KILOBYTE
        else -> return t("time.bytes", "n" to n)
    }
    val v = n / size
    return try {
        val number = NumberFormat.getInstance(I18n.locale).apply { maximumFractionDigits = if (v < 10) 1 else 0 }
        MeasureFormat.getInstance(I18n.locale, MeasureFormat.FormatWidth.SHORT, number).format(Measure(v, unit))
    } catch (_: Exception) {
        "${if (v < 10) (v * 10).roundToLong() / 10.0 else v.roundToLong().toDouble()} ${unit.subtype}"
    }
}

/** Time in calls such as "2 hr, 5 min" or "40 min" (web formatCallTime). */
fun callTime(seconds: Long): String {
    val total = (max(0L, seconds) / 60.0).roundToLong()
    val h = total / 60
    val m = total % 60
    return try {
        val f = MeasureFormat.getInstance(I18n.locale, MeasureFormat.FormatWidth.SHORT)
        when {
            h > 0 && m > 0 -> f.formatMeasures(Measure(h, MeasureUnit.HOUR), Measure(m, MeasureUnit.MINUTE))
            h > 0 -> f.format(Measure(h, MeasureUnit.HOUR))
            else -> f.format(Measure(m, MeasureUnit.MINUTE))
        }
    } catch (_: Exception) {
        if (h > 0) "$h h $m min" else "$m min"
    }
}

/** How much of the month's allowance a size is, from 0 to 100 (more when over). */
fun shareOfAllowance(bytes: Long, limit: Long): Double = if (limit > 0) max(0L, bytes) * 100.0 / limit else 0.0

/** The share a bar is drawn at: never wider than the bar, and never so thin it vanishes. */
private fun barFraction(percent: Double): Float = if (percent <= 0.0) 0f else (percent / 100.0).coerceIn(0.006, 1.0).toFloat()

/** `.meterEstimated`: what is still Nexa's estimate, hatched. */
private fun DrawScope.stripes(c: NexaColors): Brush {
    val px = 6.dp.toPx()
    return Brush.linearGradient(
        0f to c.brand.copy(alpha = 0.75f), 0.5f to c.brand.copy(alpha = 0.75f), 0.5f to c.brand.copy(alpha = 0.3f), 1f to c.brand.copy(alpha = 0.3f),
        start = Offset.Zero, end = Offset(px, px), tileMode = TileMode.Repeated,
    )
}

/** Draws a span of a bar from `from` to `to` (fractions of its width), mirrored for right to left. */
private fun DrawScope.span(brush: Brush, from: Float, to: Float, rtl: Boolean) {
    val w = size.width * (to - from)
    if (w <= 0f) return
    val x = if (rtl) size.width * (1f - to) else size.width * from
    drawRect(brush, topLeft = Offset(x, 0f), size = Size(w, size.height))
}

/** The panel's raised card (`.allowance`, `.list`). */
private fun Modifier.callsCard(c: NexaColors): Modifier {
    val shape = Radii.Lg
    return this.fillMaxWidth().ring(shape, c.panelEdge).shadowLow(shape, c).background(mixOklab(c.bgSurface, c.bgRaised, 0.6f), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).clip(shape)
}

/**
 * What calls use, and who uses it (web CallsTab.tsx): the month against the
 * allowance, then every account by what reached its devices, what its own
 * camera, microphone and screen sent others, or its time in calls. Every bar
 * is the month's allowance, so one account using a large share of it stands out.
 */
@Composable
fun CallsTab() {
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf("today") }
    var sort by remember { mutableStateOf("usage") }
    var page by remember { mutableStateOf<Moderation.CallsPage?>(null) }
    val rows = remember { mutableStateListOf<ModCallRow>() }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf(0) }
    val list = rememberLazyListState()

    fun load(offset: Int) {
        val id = ++request
        loading = true
        failed = false
        scope.launch {
            try {
                val r = Moderation.calls(period, sort, offset)
                if (id != request) return@launch
                page = r
                if (offset == 0) rows.clear()
                rows.addAll(r.accounts.filter { x -> rows.none { it.userId == x.userId } })
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (id == request) failed = true
            } finally {
                if (id == request) loading = false
            }
        }
    }
    LaunchedEffect(period, sort) {
        request++
        loaded = false
        rows.clear()
        list.scrollToItem(0)
        load(0)
    }
    // the next page as the end of the list comes into view
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == "rows" }
            item != null && item.offset + item.size - info.viewportEndOffset < 600
        }
    }
    val more = page?.more == true
    LaunchedEffect(nearEnd, more, loading, failed) { if (nearEnd && more && !loading && !failed && loaded) load(rows.size) }

    val meter = page?.meter
    val measurements = page?.measurements == true
    // The month's card arrives above the list once it is composed, and a lazy
    // list keeps its place on what it showed first, which leaves the card
    // just out of sight: bring it into view, unless the list has been moved.
    LaunchedEffect(meter != null) { if (meter != null && list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (meter != null) item("allowance") { Allowance(meter, measurements, loading) { load(0) } }
        item("tools") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipRow(t("moderation.calls.periodLabel")) {
                    for ((id, label) in listOf("today" to t("moderation.calls.today"), "week" to t("moderation.calls.week"), "month" to t("moderation.calls.month"))) {
                        ModChip(label, period == id, { period = id })
                    }
                }
                SortField(listOf("usage" to t("moderation.calls.sortUsage"), "sent" to t("moderation.calls.sortSent"), "time" to t("moderation.calls.sortTime")), sort) { sort = it }
            }
        }
        when {
            !loaded && !failed -> item("loading") { LoadingRow() }
            failed && rows.isEmpty() -> item("failed") { LoadFailed({ load(0) }, loading) }
            rows.isEmpty() -> item("empty") { EmptyState("PhoneOff", t("moderation.calls.none")) }
            else -> item("rows") { CallList(rows, sort, meter?.limit ?: 0L, measurements, page?.turnDailyLimit ?: 0L) }
        }
        val unnamed = page?.unattributed
        if (loaded && !more && unnamed != null && unnamed.received + unnamed.relay + unnamed.sent > 0) item("unattributed") { Unattributed(unnamed, sort) }
        if (loading && loaded) item("more") { LoadingRow() }
    }
}

/** `.allowance`: the month against the allowance, what Cloudflare measured solid and the estimate since hatched. */
@Composable
private fun Allowance(meter: MeterStatus, measurements: Boolean, refreshing: Boolean, onRefresh: () -> Unit) {
    val c = Nx.colors
    val measured = if (meter.measured) meter.measuredSfu + meter.measuredTurn else 0L
    val tone = when {
        meter.callsPaused -> "stop"
        meter.videoPaused -> "warn"
        else -> ""
    }
    Column(Modifier.callsCard(c).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(t("settings.admin.callsThisMonth"), style = Nx.type.name.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton("RefreshCw", t("moderation.calls.refresh"), { if (!refreshing) onRefresh() }, tint = c.textMuted, size = 44.dp, iconSize = 16.dp)
        }
        Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                t("settings.admin.callUsage", "used" to callSize(meter.used), "limit" to callSize(meter.limit)),
                style = Nx.type.name.copy(fontSize = Nx.type.sp(20f), fontWeight = FontWeight(750), fontFeatureSettings = "tnum"),
                color = when (tone) {
                    "stop" -> c.danger
                    "warn" -> c.textWarning
                    else -> c.textStrong
                },
                maxLines = 1,
            )
            Meter(meter, measured, tone)
            Txt(t("moderation.calls.thresholds"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted)
            if (meter.callsPaused || meter.videoPaused) {
                Txt(t(if (meter.callsPaused) "settings.admin.callsPaused" else "settings.admin.callsVideoPaused"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650), lineHeight = Nx.type.sp(18.9f)), color = c.textWarning)
            }
            Txt(
                when {
                    meter.measured -> t("moderation.calls.measuredAgo", "when" to Time.relative(meter.measuredAt))
                    measurements -> t("moderation.calls.measuringSoon")
                    else -> t("moderation.calls.estimatedHint")
                },
                style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)),
                color = c.textSubtle,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (meter.measured) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                SplitFact(t("moderation.calls.callMedia"), callSize(meter.measuredSfu))
                SplitFact(t("moderation.calls.relay"), callSize(meter.measuredTurn))
            }
        }
    }
}

@Composable
private fun SplitFact(label: String, value: String) {
    val c = Nx.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650)), color = c.textMuted, maxLines = 1)
        Txt(value, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(650), fontFeatureSettings = "tnum"), color = c.textStrong, maxLines = 1)
    }
}

/** `.meter`: the month's allowance as a bar, with where video pauses (90 %) and new calls stop (99 %). */
@Composable
private fun Meter(meter: MeterStatus, measured: Long, tone: String) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measuredF = barFraction(shareOfAllowance(measured, meter.limit))
    val usedF = barFraction(shareOfAllowance(meter.used, meter.limit))
    val fill = when (tone) {
        "stop" -> SolidColor(c.danger)
        "warn" -> SolidColor(c.warning)
        else -> brandGradient(c)
    }
    val shape = RoundedCornerShape(999.dp)
    val described = t("moderation.calls.shareOfMonth", "percent" to percentText(shareOfAllowance(meter.used, meter.limit)))
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .height(12.dp)
            .sunken(shape, c)
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .drawBehind {
                if (measuredF > 0f) span(fill, 0f, measuredF, rtl)
                if (usedF > measuredF) span(stripes(c), measuredF, max(usedF, measuredF + 0.006f), rtl)
                for (f in floatArrayOf(0.9f, 0.99f)) {
                    val x = (if (rtl) 1f - f else f) * size.width
                    drawRect(c.textStrong.copy(alpha = 0.55f), topLeft = Offset(x - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
                }
            }
            .semantics {
                contentDescription = described
                progressBarRangeInfo = ProgressBarRangeInfo(usedF, 0f..1f)
            },
    )
}

/** A share as the language writes a percentage, "15%". */
private fun percentText(percent: Double): String = try {
    NumberFormat.getPercentInstance(I18n.locale).apply { maximumFractionDigits = 0 }.format(percent / 100.0)
} catch (_: Exception) {
    "${percent.roundToLong()}%"
}

/** `.list`: every account, its column named for the order, in one card. */
@Composable
private fun CallList(rows: List<ModCallRow>, sort: String, limit: Long, measurements: Boolean, turnLimit: Long) {
    val c = Nx.colors
    val head = when (sort) {
        "sent" -> t("moderation.calls.sentHead")
        "time" -> t("moderation.calls.timeHead")
        else -> t("moderation.calls.receivedHead")
    }
    Column(Modifier.callsCard(c)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650))
            Txt(t("moderation.calls.account"), style = style, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            Txt(head, style = style, color = c.textMuted, maxLines = 1)
        }
        Rule()
        rows.forEachIndexed { i, r ->
            if (i > 0) Rule()
            CallRow(r, sort, limit, measurements, turnLimit)
        }
    }
}

/** `.badge`: In a Call, Relay Cut Off, Estimated. */
@Composable
private fun CallBadge(label: String, icon: String? = null, tone: String = "") {
    val c = Nx.colors
    val shape = RoundedCornerShape(10.dp)
    val (fg, edge) = when (tone) {
        "live" -> c.positive to c.positive.copy(alpha = 0.45f)
        "warn" -> c.textWarning to c.warning.copy(alpha = 0.5f)
        else -> c.textSubtle to c.borderSubtle
    }
    Row(
        Modifier.height(20.dp).ring(shape, edge).background(c.bgSurface, shape).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, fg, size = 12.dp)
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(650)), color = fg, maxLines = 1)
    }
}

/** `.row`: an account, what its calls used, and that as a share of the month's allowance across the whole width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallRow(r: ModCallRow, sort: String, limit: Long, measurements: Boolean, turnLimit: Long) {
    val c = Nx.colors
    val received = r.received + r.relay
    val figure = when (sort) {
        "sent" -> callSize(r.sent)
        "time" -> callTime(r.seconds)
        else -> callSize(received)
    }
    val share = shareOfAllowance(if (sort == "sent") r.sent else received, limit)
    val name = r.user?.let { it.displayName.ifEmpty { it.username } } ?: t("moderation.calls.guest")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .fillMaxWidth()
            .background(pressTint(pressed))
            .then(if (r.user != null) Modifier.pressable(interaction, role = Role.Button) { goPanel("users/${r.userId}") } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(r.userId, name, r.user?.avatarId, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fish.nexa.ui.chat.PersonName(r.user?.id, name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (r.inCall) CallBadge(t("moderation.calls.inCall"), "Phone", "live")
                        if (r.relayCutOff) CallBadge(t("moderation.calls.relayCutOff"), "Waypoints", "warn")
                        if (measurements && !r.measured) CallBadge(t("moderation.calls.estimatedTag"))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (sort != "usage") MetaItem("Download", t("moderation.calls.receivedFact", "size" to callSize(received)))
                        if (sort != "sent") MetaItem("Upload", t("moderation.calls.sentFact", "size" to callSize(r.sent)))
                        if (r.relay > 0) MetaItem("Waypoints", t("moderation.calls.relayFact", "size" to callSize(r.relay)))
                        if (sort != "time") MetaItem("Clock", t("moderation.calls.timeFact", "time" to callTime(r.seconds)))
                    }
                    if (r.relayCutOff) Txt(t("moderation.calls.relayCutOffHint", "limit" to callSize(turnLimit)), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textWarning)
                }
                Txt(figure, style = Nx.type.name.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(750), fontFeatureSettings = "tnum"), color = c.textStrong, maxLines = 1, align = TextAlign.End)
            }
            // its own row across the width, so every account's bar is the same length of allowance
            val f = barFraction(share)
            val large = share >= 10.0
            val estimated = !r.measured
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.text.copy(alpha = 0.08f))
                    .drawBehind {
                        if (f > 0f) span(if (large) SolidColor(c.warning) else if (estimated) stripes(c) else brandGradient(c), 0f, f, rtl)
                    }
                    .semantics { contentDescription = t("moderation.calls.shareOfMonth", "percent" to percentText(share)) },
            )
        }
    }
}

/** `.unattributed`: what Cloudflare measured that no account could be named for, set apart by a dashed edge. */
@Composable
private fun Unattributed(un: CallTotals, sort: String) {
    val c = Nx.colors
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val r = 20.dp.toPx()
                drawRoundRect(
                    c.borderStrong,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(t("moderation.calls.unattributedTitle"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(650)), color = c.textStrong)
            Txt(t("moderation.calls.unattributedHint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted)
        }
        Txt(callSize(if (sort == "sent") un.sent else un.received + un.relay), style = Nx.type.name.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(750), fontFeatureSettings = "tnum"), color = c.textStrong, maxLines = 1)
    }
}

/**
 * One account's calls for the staff (web CallUsagePanel.tsx): what reached its
 * devices, what its camera, microphone and screen sent others, its TURN relay
 * and time in calls, today, over seven days and this month. Amounts only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallUsagePanel(userId: String) {
    val c = Nx.colors
    var data by remember(userId) { mutableStateOf<Moderation.UserCalls?>(null) }
    var failed by remember(userId) { mutableStateOf(false) }
    LaunchedEffect(userId) {
        try {
            data = Moderation.userCalls(userId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed = true
        }
    }
    SheetPanel(t("moderation.calls.panelTitle"), t("moderation.calls.panelHint")) {
        val d = data
        when {
            failed -> Txt(t("moderation.loadFailed"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            d == null -> LoadingRow()
            else -> {
                val cols = listOf(d.today, d.week, d.month)
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Spacer(Modifier.weight(1.25f))
                        for (label in listOf(t("moderation.calls.today"), t("moderation.calls.week"), t("moderation.calls.month"))) {
                            Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650)), color = c.textMuted, maxLines = 1, align = TextAlign.End, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1.25f))
                        Box(Modifier.weight(3f).height(1.dp).background(c.borderSubtle))
                    }
                    val lines: List<Pair<String, (CallTotals) -> String>> = listOf(
                        t("moderation.calls.receivedHead") to { x -> callSize(x.received + x.relay) },
                        t("moderation.calls.sentHead") to { x -> callSize(x.sent) },
                        t("moderation.calls.relay") to { x -> callSize(x.relay) },
                        t("moderation.calls.timeHead") to { x -> callTime(x.seconds) },
                    )
                    lines.forEachIndexed { i, (label, value) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle.copy(alpha = c.borderSubtle.alpha * 0.6f)))
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1, modifier = Modifier.weight(1.25f))
                            for (x in cols) Txt(value(x), style = Nx.type.body.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650), fontFeatureSettings = "tnum"), color = c.textStrong, maxLines = 1, align = TextAlign.End, modifier = Modifier.weight(1f))
                        }
                    }
                }
                val note = when {
                    d.relayCutOff -> t("moderation.calls.relayCutOffToday")
                    !d.measurements -> t("moderation.calls.estimatedHint")
                    d.month.measured -> t("moderation.calls.measured")
                    else -> t("moderation.calls.partlyMeasured")
                }
                FlowRow(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (d.inCall) CallBadge(t("moderation.calls.inCall"), "Phone", "live")
                    if (d.relayCutOff) CallBadge(t("moderation.calls.relayCutOff"), "Waypoints", "warn")
                    Txt(note, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted)
                }
            }
        }
    }
}
