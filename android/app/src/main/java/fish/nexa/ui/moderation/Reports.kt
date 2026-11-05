package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.*
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.*
import fish.nexa.ui.describeError
import fish.nexa.ui.nexa.PersonChip
import fish.nexa.ui.reports.ReasonGlyph
import fish.nexa.ui.reports.StaffStatusPill
import fish.nexa.ui.reports.TargetCard
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** A search the queue opens with once, from a person's Reports About Them. */
object ReportSearch {
    var query by mutableStateOf<String?>(null)
}

/** The queue's views, as the chips across its top offer them (ReportsTab.tsx VIEWS). */
private val VIEWS = listOf("active", "mine", "closed", "all")

/** Two reports about the same reported thing ("something else" names none). */
internal fun sameItem(a: SafetyReport, b: SafetyReport) = a.targetId != "0" && a.targetKind == b.targetKind && a.targetId == b.targetId

private fun fits(r: SafetyReport, view: String, kind: String, reason: String, unclaimed: Boolean, me: String?): Boolean {
    if ((view == "active" || view == "mine") && Reports.closing(r.status)) return false
    if (view == "closed" && !Reports.closing(r.status)) return false
    if (view == "mine" && r.assignedTo != me) return false
    if (kind.isNotEmpty() && r.targetKind != kind) return false
    if (reason.isNotEmpty() && r.reason != reason) return false
    if (unclaimed && r.assignedTo != "0") return false
    return true
}

private fun personName(p: NexaPerson?) = p?.displayName?.ifEmpty { p.username } ?: ""

private fun snapshotName(v: SafetyReport): String =
    listOf("display_name", "name", "username").firstNotNullOfOrNull { k -> (v.snapshot[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } } ?: ""

/** What was reported, in a line: a message's words, or the thing's excerpt. */
private fun reportedLine(v: SafetyReport): String {
    val content = v.snapshot["message_content"] as? JsonObject
    if (content != null) {
        val text = (content["t"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text.isNotEmpty()) return text
        val files = (content["att"] as? JsonArray)?.size ?: 0
        if (files > 0) return t("reports.queue.files", "count" to files)
        if (content["sticker"] != null) return t("reports.part.sticker")
    }
    return v.excerpt
}

/**
 * ReportsTab.tsx: the staff's report queue. Each reported thing is one row
 * however many people reported it, showing what was reported, with how
 * pressing its reason is along its edge. A report opens over it and is
 * decided where it is read; deciding moves straight on to the next one.
 */
@Composable
fun ReportsTab(id: String?) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val generation = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val asked = remember { ReportSearch.query.also { ReportSearch.query = null } }
    var view by remember { mutableStateOf(if (asked != null) "all" else "active") }
    var kind by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var unclaimed by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("newest") }
    var query by remember { mutableStateOf(asked ?: "") }
    var showFilters by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<SafetyReport>>(emptyList()) }
    var people by remember { mutableStateOf<Map<String, NexaPerson>>(emptyMap()) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    // reports that came in while the queue was open, which rise in as they land
    var fresh by remember { mutableStateOf<Set<String>>(emptySet()) }
    val counts by Reports.counts.collectAsState()
    val queueScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val me = Nexa.users.meId
    val currentId by rememberUpdatedState(id)
    LaunchedEffect(Unit) {
        Reports.changes.collect { (changed, ps) ->
            people = people + ps
            var next = list
            for (r in changed) {
                val at = next.indexOfFirst { it.id == r.id || sameItem(it, r) }
                val belongs = fits(r, view, kind, reason, unclaimed, me)
                if (at >= 0) {
                    val was = next[at]
                    next = if (belongs) next.mapIndexed { i, x -> if (i == at) (if (r.id == x.id || r.id > x.id) r else x.copy(duplicates = r.duplicates, reasons = r.reasons)) else x }
                    else if (was.id == r.id && r.id != currentId) next.filterIndexed { i, _ -> i != at }
                    else next
                } else if (belongs && sort == "newest" && query.isEmpty()) {
                    next = listOf(r) + next
                    fresh = fresh + r.id
                }
            }
            list = next
        }
    }
    if (id != null) {
        ReportWorkspace(id) { decided ->
            // the next report in the queue opens, so a run of them goes quickly
            val at = list.indexOfFirst { it.id == decided }
            val rest = list.filter { it.id != decided && !Reports.closing(it.status) }
            val next = rest.getOrNull(maxOf(0, at)) ?: rest.firstOrNull()
            if (view == "active" || view == "mine") list = list.filter { it.id != decided }
            goPanel(if (next != null) "reports/${next.id}" else "reports", replace = true)
        }
        return
    }
    suspend fun fetch(cursor: String) {
        val ticket = generation.get()
        val status = when (view) { "closed" -> "closed"; "all" -> ""; else -> "active" }
        val assigned = if (view == "mine") "me" else if (unclaimed) "none" else ""
        val r = Reports.list(status, kind, reason, assigned, if (sort == "oldest") "oldest" else "", query, cursor)
        if (ticket != generation.get()) return
        list = if (cursor.isEmpty()) r.reports else list + r.reports.filter { x -> list.none { sameItem(it, x) || it.id == x.id } }
        people = people + r.people
        NexaInbox.mergePeople(r.people)
        more = r.hasMore
    }
    LaunchedEffect(view, kind, reason, unclaimed, sort, query, refresh) {
        generation.incrementAndGet()
        list = emptyList()
        more = false
        loading = true
        error = ""
        if (query.isNotEmpty()) delay(250)
        try {
            fetch("")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = describeError(e, t("reports.failed"))
        } finally {
            loading = false
        }
    }
    val waiting = (counts?.get("open") ?: 0) + (counts?.get("in_review") ?: 0)
    fun viewCount(v: String): Int? = when (v) {
        "active" -> waiting
        "closed" -> (counts?.get("actioned") ?: 0) + (counts?.get("dismissed") ?: 0)
        "all" -> counts?.values?.sum() ?: 0
        else -> null
    }
    val narrowed = kind.isNotEmpty() || reason.isNotEmpty() || unclaimed || sort == "oldest"
    LazyColumn(Modifier.fillMaxSize(), state = queueScroll, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).claimsSideways { true }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VIEWS.forEach { v -> ViewChip(t("reports.view.$v"), viewCount(v), view == v) { view = v } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxTextField(query, { if (it.length <= 200) query = it }, Modifier.weight(1f), placeholder = t("reports.searchHint"), leading = { Icon("Search", c.textMuted, size = 16.dp) })
                    Box {
                        IconButton("SlidersHorizontal", t("reports.filters"), { showFilters = !showFilters })
                        if (narrowed) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).background(c.brand, CircleShape))
                    }
                    IconButton("RefreshCw", t("reports.refresh"), { refresh++ }, enabled = !loading)
                }
                if (showFilters || narrowed)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectField(null, listOf("" to t("reports.allReasons")) + Reports.reasons.map { it to t("reports.reason.$it") }, reason, Modifier.weight(1f)) { reason = it }
                            SelectField(null, listOf("" to t("reports.allKinds")) + Reports.kinds.map { it to t("reports.kind.$it") }, kind, Modifier.weight(1f)) { kind = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectField(null, listOf("" to t("reports.anyone"), "none" to t("reports.unassigned")), if (unclaimed) "none" else "", Modifier.weight(1f)) { unclaimed = it == "none" }
                            SelectField(null, listOf("newest" to t("reports.newestFirst"), "oldest" to t("reports.oldestFirst")), sort, Modifier.weight(1f)) { sort = it }
                        }
                    }
            }
        }
        if (error.isNotEmpty()) item { Txt(error, Modifier.padding(horizontal = 6.dp), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger) }
        if (list.isEmpty() && !loading && error.isEmpty())
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon("Inbox", c.textMuted, size = 28.dp)
                    Txt(if (!narrowed && query.isEmpty() && view == "active") t("reports.allClear") else t("reports.empty"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center)
                    if (narrowed || query.isNotEmpty())
                        NxButton(t("reports.clearFilters"), { kind = ""; reason = ""; unclaimed = false; sort = "newest"; query = "" }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH)
                }
            }
        items(list, key = { it.id }) { v -> QueueRow(v, people, v.id in fresh) { goPanel("reports/${v.id}") } }
        if (loading) item { Txt(t("reports.loading"), Modifier.fillMaxWidth().padding(16.dp), style = Nx.type.bodySmall, color = c.textMuted, align = TextAlign.Center) }
        if (more && !loading)
            item {
                NxButton(t("reports.more"), {
                    loading = true
                    scope.launch {
                        try {
                            fetch(list.lastOrNull()?.id ?: "")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = describeError(e, t("reports.failed"))
                        } finally {
                            loading = false
                        }
                    }
                }, Modifier.padding(horizontal = 6.dp), kind = ButtonKind.SECONDARY, block = true)
            }
    }
}

@Composable
private fun ViewChip(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 36.dp)
            .clip(shape)
            .background(if (on) c.brand else c.bgSurface, shape)
            .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = if (on) c.onBrand else c.textMuted, maxLines = 1)
        if (count != null) Txt(count.toString(), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = (if (on) c.onBrand else c.textMuted).copy(alpha = 0.8f), maxLines = 1)
    }
}

@Composable
private fun DupesPill(count: Int) {
    val c = Nx.colors
    Box(Modifier.background(mixSrgb(c.bgSurface, c.danger, 0.22f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp)) {
        Txt(t("reports.duplicates", "count" to count), style = Nx.type.hint.copy(fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 1)
    }
}

/** How pressing a reason is, as the colour along a row's edge (`.row::before`). */
@Composable
private fun toneColor(reason: String) = when (Reports.tone(reason)) {
    ReasonTone.URGENT -> Nx.colors.danger
    ReasonTone.SERIOUS -> Nx.colors.warning
    ReasonTone.ROUTINE -> Nx.colors.borderSubtle
}

/** One reported thing in the queue: why, about whom, what it said, and where it stands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueRow(v: SafetyReport, people: Map<String, NexaPerson>, fresh: Boolean, onOpen: () -> Unit) {
    val c = Nx.colors
    val subject = if (v.subjectId != "0") people[v.subjectId] else null
    val assignee = if (v.assignedTo != "0") people[v.assignedTo] else null
    val person = v.targetKind == "user" || v.targetKind == "message"
    val name = if (person) personName(subject).ifEmpty { snapshotName(v) } else snapshotName(v).ifEmpty { personName(subject) }
    val line = reportedLine(v)
    // a row standing for several reports speaks with the most pressing reason among them
    val reason = Reports.worst(v.reason, v.reasons)
    val edge = toneColor(reason)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // a report that just came in rises into place (`.row[data-fresh]`)
    val reduced = Nx.motion.reduced
    val rise = remember(v.id) { androidx.compose.animation.core.Animatable(if (fresh && !reduced) 0f else 1f) }
    val ease = Nx.motion.easeOut
    LaunchedEffect(v.id) { if (rise.value < 1f) rise.animateTo(1f, androidx.compose.animation.core.tween(260, easing = ease)) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rise.value * (if (Reports.closing(v.status)) 0.78f else 1f); translationY = (1f - rise.value) * -6f * density }
            .clip(Radii.Sm)
            .background(pressTint(pressed))
            .drawBehind {
                val inset = 10.dp.toPx()
                drawRoundRect(edge, Offset(4.dp.toPx(), inset), Size(3.dp.toPx(), size.height - 2 * inset), CornerRadius(3.dp.toPx()))
            }
            .pressable(interaction, onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReasonGlyph(reason, 26.dp)
            Txt(t("reports.reason.$reason"), Modifier.weight(1f), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 1)
            Txt(Time.relativeTo(v.createdAt), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (subject != null && person) Avatar(v.subjectId, name, subject.avatarId, 20.dp)
            if (name.isNotEmpty()) Txt(name, Modifier.weight(1f, fill = false), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
            Txt(t("reports.kind.${v.targetKind}"), style = Nx.type.bodySmall, color = c.textMuted, maxLines = 1)
            if (v.duplicates > 1) DupesPill(v.duplicates)
        }
        if (line.isNotEmpty()) Txt(line, Modifier.fillMaxWidth().background(c.bgInput, Radii.Xs).padding(horizontal = 9.dp, vertical = 6.dp), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textStrong, maxLines = 2)
        if (v.description.isNotEmpty() && v.description != line) Txt(v.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.textSubtle, maxLines = 1)
        if (v.status != "open" || assignee != null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (v.status != "open") StaffStatusPill(v.status, small = true)
                if (assignee != null)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Avatar(v.assignedTo, personName(assignee), assignee.avatarId, 16.dp)
                        Txt(personName(assignee), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                    }
            }
    }
}

/** A raised panel with a small muted heading (`.panel`). */
@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    Column(
        Modifier.fillMaxWidth().shadowLow(shape, c).background(c.bgRaised, shape).topEdge(shape, c.edgeLight).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(title, Modifier.semantics { heading() }, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textMuted)
        content()
    }
}

@Composable
private fun SectionTitle(text: String) =
    Txt(text, Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(17f)), color = Nx.colors.textStrong)

@Composable
private fun Chip(text: String, tone: String? = null) {
    val c = Nx.colors
    val (fg, bg) = when (tone) {
        "danger" -> c.textDanger to mixSrgb(c.bgSurface, c.danger, 0.16f)
        "warning" -> c.textWarning to mixSrgb(c.bgSurface, c.warning, 0.14f)
        else -> c.textSubtle to c.bgSurface
    }
    Box(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp)) {
        Txt(text, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = fg, maxLines = 1)
    }
}

private enum class Deciding { NONE, ACT, DISMISS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportWorkspace(id: String, onDecided: (String) -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val me = Nexa.users.meId
    var detail by remember(id) { mutableStateOf<SafetyReportDetail?>(null) }
    var error by remember(id) { mutableStateOf("") }
    var busy by remember(id) { mutableStateOf(false) }
    var deciding by remember(id) { mutableStateOf(Deciding.NONE) }
    var note by remember(id) { mutableStateOf("") }
    var showHistory by remember(id) { mutableStateOf(false) }
    var showDetails by remember(id) { mutableStateOf(false) }
    var reload by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, reload) {
        try {
            val r = Reports.detail(id)
            NexaInbox.mergePeople(r.people)
            detail = r
            error = ""
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = describeError(e, t("reports.failed"))
        }
    }
    // another member of staff decided or claimed it: show what stands now
    LaunchedEffect(id) {
        Reports.changes.collect { (changed, _) -> if (!busy && changed.any { it.id == id && it.version != detail?.report?.version }) reload++ }
    }
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                work()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = describeError(e, t("reports.failed"))
            } finally {
                busy = false
            }
        }
    }
    val v = detail?.report
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            NxButton(t("reports.back"), { fish.nexa.ui.nav.Nav.returnTo(fish.nexa.ui.nav.Route.Admin("reports")) }, kind = ButtonKind.GHOST, icon = "ArrowLeft", size = ButtonSize.TOUCH)
        }
        if (error.isNotEmpty())
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(error, Modifier.weight(1f), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger)
                    NxButton(t("reports.retry"), { reload++ }, kind = ButtonKind.LINK, enabled = !busy)
                }
            }
        if (v == null || detail == null) {
            if (error.isEmpty()) item { Txt(t("reports.loading"), style = Nx.type.body, color = c.textMuted) }
            return@LazyColumn
        }
        val d = detail!!
        val sameItemReports = d.related.filter { sameItem(it, v) }
        val aboutAccount = d.related.filter { !sameItem(it, v) }
        val openSame = sameItemReports.count { !Reports.closing(it.status) }
        val closed = Reports.closing(v.status)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ReasonGlyph(v.reason, 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(t("reports.reason.${v.reason}"), Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(21f)), color = if (Reports.tone(v.reason) == ReasonTone.URGENT) c.textDanger else c.textStrong)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Txt(t("reports.kindReport", "kind" to t("reports.kind.${v.targetKind}")), style = Nx.type.bodySmall, color = c.textMuted)
                        Txt(Time.relativeTo(v.createdAt), style = Nx.type.bodySmall, color = c.textMuted)
                        if (openSame + (if (closed) 0 else 1) > 1) DupesPill(openSame + 1)
                    }
                }
                StaffStatusPill(v.status)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.assignedTo != "0") {
                    Txt(t("reports.assignment"), style = Nx.type.bodySmall, color = c.textMuted)
                    PersonChip(v.assignedTo, 18.dp)
                } else Txt(t("reports.unassigned"), style = Nx.type.bodySmall, color = c.textMuted)
                if (!closed) {
                    val claiming = v.assignedTo != me
                    NxButton(
                        t(if (claiming) "reports.claim" else "reports.unassign"),
                        {
                            run {
                                val to = if (claiming) me ?: "0" else "0"
                                val status = if (to != "0" && v.status == "open") "in_review" else if (to == "0" && v.status == "in_review") "open" else v.status
                                detail = Reports.review(v, status, "", to)
                            }
                        },
                        kind = ButtonKind.GHOST,
                        size = ButtonSize.TOUCH,
                        enabled = !busy && me != null,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton("Copy", t("reports.copyId"), {
                    val cm = Nexa.app.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("id", v.id))
                    Nexa.ui.toast(t("apps.portal.copiedShort"), "success")
                }, tint = c.textMuted, iconSize = 15.dp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val content = v.snapshot["message_content"] as? JsonObject
                if (content != null) {
                    val ch = v.snapshot["channel_id"]!!.jsonPrimitive.content
                    val channel = Nexa.channels.get(ch) ?: fish.nexa.net.Channel(id = ch)
                    val cm = ChatMessage(
                        fish.nexa.net.Message(v.targetId, ch, v.subjectId, type = content["type"]?.jsonPrimitive?.content ?: "default", createdAt = v.snapshot["created_at"]?.jsonPrimitive?.longOrNull ?: v.createdAt, editedAt = v.snapshot["edited_at"]?.jsonPrimitive?.longOrNull),
                        payload = fish.nexa.crypto.MessageCrypto.sanitizePayload(JsonObject(content.filterKeys { it !in listOf("att", "sticker") })),
                        hook = content["hook"] as? JsonObject,
                        call = content["call"] as? JsonObject,
                    )
                    Box(Modifier.fillMaxWidth().clip(Radii.Sm).background(c.bgRaised).border(1.dp, c.borderSubtle, Radii.Sm).padding(bottom = 12.dp)) {
                        fish.nexa.ui.chat.MessageRow(
                            fish.nexa.ui.chat.MsgItem(cm.id, cm = cm, groupStart = true),
                            fish.nexa.ui.chat.ListCtx(channel, Nexa.servers.byId.value[channel.serverId], false, false, Nexa.users.meId ?: ""),
                            null, false, false, preview = true, previewAuthor = d.people[v.subjectId],
                            evidence = {
                                (v.snapshot["message_sticker"] as? JsonObject)?.let { sticker ->
                                    ReportFile(NexaFile(sticker["blob_id"]!!.jsonPrimitive.content, sticker["name"]?.jsonPrimitive?.content ?: "", "image/*"), sticker["format"]?.jsonPrimitive?.content)
                                }
                                d.files.forEach { ReportFile(it) }
                            },
                        )
                    }
                } else {
                    if (v.targetKind != "other") TargetCard(v.targetKind, v.snapshot) { blob, _, format -> ReportFile(NexaFile(blob, "", "image/*"), format, bare = true) }
                    if (v.part.isNotEmpty() && v.part != "whole")
                        Txt(t("reports.reportedPart", "part" to if (v.part == "custom") v.customPart.ifEmpty { t("reports.part.custom") } else t("reports.part.${v.part}")), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(600)), color = c.textStrong)
                    if (v.excerpt.isNotEmpty()) Txt(v.excerpt, Modifier.fillMaxWidth().background(c.bgInput, Radii.Sm).padding(12.dp), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                    d.files.forEach { ReportFile(it) }
                }
                Txt(t("reports.unverified"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            }
        }
        item {
            if (!closed) {
                when (deciding) {
                    Deciding.NONE -> {
                        val shape = Radii.Lg
                        Column(
                            Modifier.fillMaxWidth().shadowMedium(shape, c).background(c.bgRaised, shape).topEdge(shape, c.edgeLight).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                // a helper triages and dismisses; taking action is a mod's
                                if (StaffRanks.can(StaffCan.TAKE_DOWN_CONTENT)) NxButton(t("reports.act.takeAction"), { deciding = Deciding.ACT }, Modifier.weight(1f), kind = ButtonKind.DANGER, icon = "Gavel", enabled = !busy)
                                NxButton(t("reports.act.dismiss"), { deciding = Deciding.DISMISS }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = "ThumbsDown", enabled = !busy)
                            }
                            if (v.subjectId != "0") NxButton(t("reports.openAccount"), { openReportTarget("users/${v.subjectId}", v.id) }, kind = ButtonKind.GHOST, icon = "UserRound", size = ButtonSize.TOUCH, block = true)
                        }
                    }
                    Deciding.ACT -> ActionPanel(d, openSame, onCancel = { deciding = Deciding.NONE }, onDone = { onDecided(v.id) }, onChanged = { reload++ })
                    Deciding.DISMISS -> DismissPanel(d, openSame, onCancel = { deciding = Deciding.NONE }, onDone = { onDecided(v.id) })
                }
            } else {
                val last = d.events.lastOrNull { Reports.closing(it.status) }
                val shape = Radii.Lg
                Row(
                    Modifier.fillMaxWidth().shadowLow(shape, c).background(c.bgRaised, shape).topEdge(shape, c.edgeLight).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon("Check", if (v.status == "dismissed") c.textMuted else c.positive, size = 18.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(t("reports.status.${v.status}"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(700)), color = c.textStrong)
                        if (last != null)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PersonChip(last.actorId, 16.dp)
                                Txt(Time.relativeTo(last.createdAt), style = Nx.type.bodySmall, color = c.textMuted)
                            }
                        if (!last?.note.isNullOrEmpty()) Txt(last!!.note, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = c.text)
                    }
                    NxButton(t("reports.decision.reopen"), { run { detail = Reports.review(v, "open", "", v.assignedTo) } }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, enabled = !busy)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(t("reports.whatTheySaid", "count" to sameItemReports.size + 1))
                (listOf(v) + sameItemReports).forEach { x ->
                    Column(Modifier.fillMaxWidth().background(c.bgInput, Radii.Sm).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f, fill = false)) { PersonChip(x.reporterId, 18.dp) }
                            if (x.reason != v.reason) Txt(t("reports.reason.${x.reason}"), style = Nx.type.bodySmall, color = c.textMuted, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            Txt(Time.relativeTo(x.createdAt), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
                            if (x.id != v.id) IconButton("ExternalLink", t("reports.openThis"), { goPanel("reports/${x.id}") }, tint = c.textMuted, size = 32.dp, iconSize = 13.dp)
                        }
                        if (x.description.isNotEmpty()) Txt(x.description, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textStrong)
                    }
                }
            }
        }
        if (aboutAccount.isNotEmpty())
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(t("reports.aboutAccount", "count" to aboutAccount.size))
                    aboutAccount.forEach { x ->
                        Row(
                            Modifier.fillMaxWidth().clip(Radii.Sm).pressable(remember(x.id) { MutableInteractionSource() }) { goPanel("reports/${x.id}") }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ReasonGlyph(x.reason, 26.dp)
                            Column(Modifier.weight(1f)) {
                                Txt(t("reports.reason.${x.reason}"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textStrong)
                                Txt(t("reports.kind.${x.targetKind}"), style = Nx.type.bodySmall, color = c.textMuted)
                            }
                            StaffStatusPill(x.status, small = true)
                        }
                    }
                }
            }
        if (v.subjectId != "0")
            item {
                Panel(t("reports.subject")) {
                    PersonChip(v.subjectId, 32.dp, withUsername = true)
                    d.subject?.let { s ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val banned = s.ban != null && s.ban !is JsonNull
                            val muted = s.mute != null && s.mute !is JsonNull
                            Chip(t(if (banned) "reports.standing.banned" else if (muted) "reports.standing.muted" else "reports.standing.clear"), if (banned) "danger" else if (muted) "warning" else null)
                            Chip(t("reports.standing.cases", "count" to s.caseCount))
                            Chip(t("reports.standing.reports", "count" to aboutAccount.size + sameItemReports.size + 1))
                            if (s.altCount > 0) Chip(t("reports.standing.alts", "count" to s.altCount), "warning")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NxButton(t("reports.openAccount"), { openReportTarget("users/${v.subjectId}", v.id) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH)
                        if (v.serverId != "0") NxButton(t("reports.openServer"), { openReportTarget("servers/${v.serverId}", v.id) }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH)
                        val packId = if (v.targetKind == "pack") v.targetId else if (v.targetKind == "sticker") (v.snapshot["pack_id"] as? JsonPrimitive)?.contentOrNull else null
                        if (packId != null) NxButton(t("reports.openPack"), { openReportTarget("expressions/packs/$packId", v.id) }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH)
                    }
                }
            }
        item {
            Panel(t("reports.reporter")) {
                PersonChip(v.reporterId, 28.dp, withUsername = true)
                val stats = d.reporterStats
                if (stats.isNotEmpty())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("sent" to stats.values.sum(), "actioned" to (stats["actioned"] ?: 0), "dismissed" to (stats["dismissed"] ?: 0)).forEach { (k, n) ->
                            Column(Modifier.weight(1f).background(c.bgInput, Radii.Sm).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Txt(n.toString(), style = Nx.type.heading.copy(fontSize = Nx.type.sp(20f)), color = c.textStrong)
                                Txt(t("reports.stats.$k"), style = Nx.type.hint, color = c.textMuted, align = TextAlign.Center)
                            }
                        }
                    }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(Radii.Sm).pressable(remember { MutableInteractionSource() }) { showHistory = !showHistory }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(if (showHistory) "ChevronDown" else "ChevronRight", c.textMuted, size = 16.dp)
                    SectionTitle(t("reports.history"))
                }
                if (showHistory) {
                    VSpace(10.dp)
                    TimelineItem(v.createdAt, null, last = d.events.isEmpty() && v.outcomeAt == 0L) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PersonChip(v.reporterId, 16.dp)
                            Txt(t("reports.timeline.sent"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                        }
                    }
                    d.events.forEachIndexed { i, e ->
                        TimelineItem(e.createdAt, e.status, last = i == d.events.lastIndex && v.outcomeAt == 0L) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PersonChip(e.actorId, 16.dp)
                                Txt(t("reports.timeline.status", "status" to t("reports.status.${e.status}")), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                            }
                            if (e.note.isNotEmpty()) Txt(e.note, Modifier.fillMaxWidth().padding(top = 4.dp).background(c.bgInput, Radii.Sm).padding(horizontal = 10.dp, vertical = 8.dp), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                        }
                    }
                    if (v.outcomeAt > 0)
                        TimelineItem(v.outcomeAt, null, last = true) {
                            Txt(t("reports.timeline.told"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                            Txt(v.outcome.ifEmpty { t(if (v.status == "dismissed") "reports.outcome.dismissed" else "reports.outcome.actioned") }, Modifier.fillMaxWidth().padding(top = 4.dp).background(c.bgInput, Radii.Sm).padding(horizontal = 10.dp, vertical = 8.dp), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                        }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NxTextField(note, { if (it.length <= 2000) note = it }, Modifier.weight(1f), placeholder = t("reports.noteHint"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
                        NxButton(t("reports.addNote"), { run { detail = Reports.review(v, v.status, note.trim(), v.assignedTo); note = "" } }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, enabled = !busy && note.isNotBlank())
                    }
                    VSpace(8.dp)
                    NxButton(t("reports.sharedDetails"), { showDetails = !showDetails }, kind = ButtonKind.GHOST, icon = if (showDetails) "ChevronDown" else "ChevronRight", size = ButtonSize.TOUCH)
                    if (showDetails) {
                        Txt(t("reports.snapshotHint"), style = Nx.type.bodySmall, color = c.textSubtle)
                        v.snapshot.filterKeys { it != "message_content" && it != "message_sticker" }.forEach { (key, value) ->
                            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Txt(key.replace('_', ' '), style = Nx.type.bodySmall, color = c.textMuted)
                                Txt(if (value is JsonPrimitive) value.content else value.toString(), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text, maxLines = 8)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(at: Long, status: String?, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val dot = when (status) {
        "actioned" -> c.positive
        "in_review" -> c.warning
        "open" -> c.brand
        else -> c.textMuted
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(10.dp).fillMaxHeight()) {
            if (!last) Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).width(2.dp).fillMaxHeight().background(c.borderSubtle))
            Box(Modifier.padding(top = 6.dp).size(10.dp).background(dot, CircleShape))
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
            Txt(Time.full(at), style = Nx.type.bodySmall, color = c.textMuted)
        }
    }
}

@Composable
private fun ReportFile(file: NexaFile, format: String? = null, bare: Boolean = false) {
    val c = Nx.colors
    var local by remember(file.id) { mutableStateOf<java.io.File?>(null) }
    var error by remember(file.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var loading by remember(file.id) { mutableStateOf(false) }
    suspend fun load() {
        loading = true
        error = false
        try {
            local = PrivateFiles.file("reports", file.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = true
        } finally {
            loading = false
        }
    }
    LaunchedEffect(file.id) {
        if (
            file.size <= 256L * 1024 * 1024 &&
                (file.mime.startsWith("image/") || file.mime.startsWith("video/") || format != null)
        )
            load()
    }
    // a saved picture filling its slot (the target card's), with nothing around it
    if (bare) {
        local?.let {
            if (!format.isNullOrEmpty()) fish.nexa.ui.chat.StickerMedia(format, android.net.Uri.fromFile(it).toString(), file.name, 48.dp)
            else AsyncImage(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        return
    }
    Column {
        if (format != null && local != null)
            fish.nexa.ui.chat.StickerMedia(
                format,
                android.net.Uri.fromFile(local).toString(),
                file.name,
                180.dp,
            )
        else if (file.mime.startsWith("image/") && local != null)
            AsyncImage(
                local,
                contentDescription = file.name,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            )
        Txt(file.name, style = Nx.type.bodySmall, color = c.text)
        if (error) Txt(t("reports.failed"), style = Nx.type.bodySmall, color = c.textDanger)
        NxButton(
            t("reports.openFile"),
            {
                local?.let { openEvidence(it, file.mime, file.name) }
                    ?: scope.launch {
                        load()
                        local?.let { openEvidence(it, file.mime, file.name) }
                    }
            },
            kind = ButtonKind.SECONDARY,
            enabled = !loading,
            loading = loading,
        )
    }
}

private fun openEvidence(file: java.io.File, mime: String, name: String) {
    Nexa.scope.launch {
        try {
            val context = Nexa.app
            val copy =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                    val out =
                        java.io.File(
                            dir,
                            "report-" +
                                file.name +
                                "." +
                                (android.webkit.MimeTypeMap.getSingleton()
                                    .getExtensionFromMimeType(mime) ?: "bin"),
                        )
                    file.copyTo(out, true)
                    out
                }
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    copy,
                )
            val intent =
                android.content
                    .Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
            context.startActivity(
                android.content.Intent.createChooser(intent, name)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Nexa.ui.toast(t("reports.failed"), "error")
        }
    }
}
