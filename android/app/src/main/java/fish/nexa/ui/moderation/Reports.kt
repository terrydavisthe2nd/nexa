package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
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
import fish.nexa.ui.layers.openSheet
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

/** The queue's views, as the chips across its top offer them. */
private val VIEWS = listOf("active", "open", "in_review", "closed", "all")

private fun fits(r: SafetyReport, view: String, kind: String, reason: String, assigned: String, me: String?): Boolean {
    if (view == "active" && Reports.closing(r.status)) return false
    if (view == "closed" && !Reports.closing(r.status)) return false
    if ((view == "open" || view == "in_review") && r.status != view) return false
    if (kind.isNotEmpty() && r.targetKind != kind) return false
    if (reason.isNotEmpty() && r.reason != reason) return false
    if (assigned == "me" && r.assignedTo != me) return false
    if (assigned == "none" && r.assignedTo != "0") return false
    return true
}

private fun personName(p: NexaPerson?) = p?.displayName?.ifEmpty { p.username } ?: ""

private fun snapshotName(v: SafetyReport): String =
    listOf("display_name", "name", "username").firstNotNullOfOrNull { k -> (v.snapshot[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } } ?: ""

/**
 * ReportsTab.tsx: the staff's report queue, the most pressing shown as such,
 * moving the moment anyone reports or decides; a report opens over it with
 * everything needed to decide.
 */
@Composable
fun ReportsTab(id: String?) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val generation = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var view by remember { mutableStateOf("active") }
    var kind by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var assigned by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("newest") }
    var query by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<SafetyReport>>(emptyList()) }
    var people by remember { mutableStateOf<Map<String, NexaPerson>>(emptyMap()) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val counts by Reports.counts.collectAsState()
    val queueScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val me = Nexa.users.meId
    LaunchedEffect(Unit) {
        Reports.changes.collect { (changed, ps) ->
            people = people + ps
            var next = list
            for (r in changed) {
                next = if (next.any { it.id == r.id }) next.map { if (it.id == r.id) r else it }
                else if (sort == "newest" && query.isEmpty() && fits(r, view, kind, reason, assigned, me)) listOf(r) + next
                else next
            }
            list = next
        }
    }
    if (id != null) {
        ReportWorkspace(id)
        return
    }
    suspend fun fetch(cursor: String) {
        val ticket = generation.get()
        val r = Reports.list(if (view == "all") "" else view, kind, reason, assigned, if (sort == "oldest") "oldest" else "", query, cursor)
        if (ticket != generation.get()) return
        list = if (cursor.isEmpty()) r.reports else list + r.reports.filter { x -> list.none { it.id == x.id } }
        people = people + r.people
        NexaInbox.mergePeople(r.people)
        more = r.hasMore
    }
    LaunchedEffect(view, kind, reason, assigned, sort, query, refresh) {
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
    fun viewCount(v: String) = when (v) {
        "active" -> waiting
        "closed" -> (counts?.get("actioned") ?: 0) + (counts?.get("dismissed") ?: 0)
        "all" -> counts?.values?.sum() ?: 0
        else -> counts?.get(v) ?: 0
    }
    val narrowed = kind.isNotEmpty() || reason.isNotEmpty() || assigned.isNotEmpty() || sort == "oldest"
    LazyColumn(Modifier.fillMaxSize(), state = queueScroll, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(t("reports.title"), Modifier.semantics { heading() }, style = Nx.type.heading, color = c.textStrong)
                Txt(t("reports.waitingCount", "count" to waiting), Modifier.weight(1f), style = Nx.type.bodySmall, color = c.textMuted, maxLines = 1)
                IconButton("RefreshCw", t("reports.refresh"), { refresh++ }, enabled = !loading)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).claimsSideways { true }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VIEWS.forEach { v -> ViewChip(t("reports.view.$v"), viewCount(v), view == v) { view = v } }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxTextField(query, { if (it.length <= 200) query = it }, Modifier.weight(1f), placeholder = t("reports.searchHint"), leading = { Icon("Search", c.textMuted, size = 16.dp) })
                Box {
                    IconButton("SlidersHorizontal", t("reports.filters"), { showFilters = !showFilters })
                    if (narrowed) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).background(c.brand, CircleShape))
                }
            }
        }
        if (showFilters || narrowed) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField(null, listOf("" to t("reports.allReasons")) + Reports.reasons.map { it to t("reports.reason.$it") }, reason, Modifier.weight(1f)) { reason = it }
                    SelectField(null, listOf("" to t("reports.allKinds")) + Reports.kinds.map { it to t("reports.kind.$it") }, kind, Modifier.weight(1f)) { kind = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField(null, listOf("" to t("reports.anyone"), "me" to t("reports.assignedToMe"), "none" to t("reports.unassigned")), assigned, Modifier.weight(1f)) { assigned = it }
                    SelectField(null, listOf("newest" to t("reports.newestFirst"), "oldest" to t("reports.oldestFirst")), sort, Modifier.weight(1f)) { sort = it }
                }
            }
        }
        if (error.isNotEmpty()) item { Txt(error, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger) }
        if (list.isEmpty() && !loading && error.isEmpty())
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon("Inbox", c.textMuted, size = 28.dp)
                    Txt(if (!narrowed && query.isEmpty() && view == "active") t("reports.allClear") else t("reports.empty"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center)
                    if (narrowed || query.isNotEmpty())
                        NxButton(t("reports.clearFilters"), { kind = ""; reason = ""; assigned = ""; sort = "newest"; query = "" }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH)
                }
            }
        items(list, key = { it.id }) { v -> QueueRow(v, people) { goPanel("reports/${v.id}") } }
        if (loading) item { Txt(t("reports.loading"), Modifier.fillMaxWidth(), style = Nx.type.bodySmall, color = c.textMuted, align = TextAlign.Center) }
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
                }, kind = ButtonKind.SECONDARY, block = true)
            }
    }
}

@Composable
private fun ViewChip(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
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
        Txt(count.toString(), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = (if (on) c.onBrand else c.textMuted).copy(alpha = 0.8f), maxLines = 1)
    }
}

@Composable
private fun DupesPill(count: Int) {
    val c = Nx.colors
    Box(Modifier.background(mixSrgb(c.bgSurface, c.danger, 0.22f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp)) {
        Txt(t("reports.duplicates", "count" to count), style = Nx.type.hint.copy(fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 1)
    }
}

/** One report in the queue: why, about whom or what, what they said, and where it stands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueRow(v: SafetyReport, people: Map<String, NexaPerson>, onOpen: () -> Unit) {
    val c = Nx.colors
    val subject = if (v.subjectId != "0") people[v.subjectId] else null
    val assignee = if (v.assignedTo != "0") people[v.assignedTo] else null
    val person = v.targetKind == "user" || v.targetKind == "message"
    val name = if (person) personName(subject).ifEmpty { snapshotName(v) } else snapshotName(v).ifEmpty { personName(subject) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .clip(Radii.Sm)
            .pressable(interaction, onClick = onOpen)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReasonGlyph(v.reason)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(t("reports.reason.${v.reason}"), Modifier.weight(1f), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 2)
                Txt(Time.relativeTo(v.createdAt), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (subject != null && person) Avatar(v.subjectId, name, subject.avatarId, 18.dp)
                if (name.isNotEmpty()) Txt(name, Modifier.weight(1f, fill = false), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
                Txt(t("reports.kind.${v.targetKind}"), style = Nx.type.bodySmall, color = c.textMuted, maxLines = 1)
            }
            Txt(v.description, style = Nx.type.bodySmall, color = c.textSubtle, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                StaffStatusPill(v.status)
                if (v.duplicates > 1) DupesPill(v.duplicates)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (assignee != null) Avatar(v.assignedTo, personName(assignee), assignee.avatarId, 16.dp)
                    Txt(if (assignee != null) personName(assignee) else t("reports.unassigned"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportWorkspace(id: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val meUser by Nexa.users.me.collectAsState()
    val rank = StaffRanks.of(meUser?.id, meUser?.flags ?: 0L)
    val me = Nexa.users.meId
    var detail by remember(id) { mutableStateOf<SafetyReportDetail?>(null) }
    var error by remember(id) { mutableStateOf("") }
    var busy by remember(id) { mutableStateOf(false) }
    var decision by remember(id) { mutableStateOf("open") }
    var note by remember(id) { mutableStateOf("") }
    var told by remember(id) { mutableStateOf("") }
    var alsoRelated by remember(id) { mutableStateOf(true) }
    var removing by remember(id) { mutableStateOf(false) }
    var removeReason by remember(id) { mutableStateOf("") }
    var saved by remember(id) { mutableStateOf("") }
    var showDetails by remember(id) { mutableStateOf(false) }
    var reload by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, reload) {
        try {
            val r = Reports.detail(id)
            NexaInbox.mergePeople(r.people)
            detail = r
            decision = r.report.status
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
        val closingNow = Reports.closing(decision)
        val changing = decision != v.status
        val others = maxOf(0, v.duplicates - 1)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ReasonGlyph(v.reason, 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(t("reports.reason.${v.reason}"), Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(21f)), color = c.textStrong)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Txt(t("reports.kindReport", "kind" to t("reports.kind.${v.targetKind}")), style = Nx.type.bodySmall, color = c.textMuted)
                        Txt(Time.relativeTo(v.createdAt), style = Nx.type.bodySmall, color = c.textMuted)
                        if (v.duplicates > 1) DupesPill(v.duplicates)
                        Row(
                            Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) {
                                val cm = Nexa.app.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("id", v.id))
                                Nexa.ui.toast(t("apps.portal.copiedShort"), "success")
                            }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon("Copy", c.textMuted, size = 13.dp)
                            Txt(t("reports.reportId"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                        }
                    }
                }
                StaffStatusPill(v.status)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(c.bgInput, Radii.Md).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Txt(t("reports.assignment"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textMuted)
                Box(Modifier.weight(1f)) {
                    if (v.assignedTo != "0") PersonChip(v.assignedTo, 20.dp) else Txt(t("reports.unassigned"), style = Nx.type.bodySmall, color = c.textMuted)
                }
                val claiming = v.assignedTo != me
                NxButton(
                    t(if (claiming) "reports.claim" else "reports.unassign"),
                    {
                        run {
                            val to = if (claiming) me ?: "0" else "0"
                            val status = if (to != "0" && v.status == "open") "in_review" else if (to == "0" && v.status == "in_review") "open" else v.status
                            val r = Reports.review(v, status, "", to)
                            detail = r
                            decision = r.report.status
                        }
                    },
                    kind = if (claiming) ButtonKind.SECONDARY else ButtonKind.GHOST,
                    size = ButtonSize.TOUCH,
                    enabled = !busy && me != null,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(t("reports.said"))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(topEnd = Radii.sm, bottomEnd = Radii.sm)).background(c.bgInput)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.borderStrong))
                    Txt(v.description, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = Nx.type.body, color = c.textStrong)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(t("reports.reporter"), style = Nx.type.bodySmall, color = c.textMuted)
                    PersonChip(v.reporterId, 18.dp, withUsername = true)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(t("reports.reportedContent"))
                Txt(t("reports.unverified"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle)
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
                    if (v.excerpt.isNotEmpty()) Txt(v.excerpt, Modifier.fillMaxWidth().background(c.bgInput, Radii.Sm).padding(12.dp), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                    d.files.forEach { ReportFile(it) }
                }
                NxButton(t("reports.sharedDetails"), { showDetails = !showDetails }, kind = ButtonKind.GHOST, icon = if (showDetails) "ChevronDown" else "ChevronRight", size = ButtonSize.TOUCH)
                if (showDetails) {
                    Txt(t("reports.snapshotHint"), style = Nx.type.bodySmall, color = c.textSubtle)
                    v.snapshot.filterKeys { it != "message_content" && it != "message_sticker" }.forEach { (key, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Txt(key.replace('_', ' '), style = Nx.type.bodySmall, color = c.textMuted)
                            Txt(if (value is JsonPrimitive) value.content else value.toString(), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text, maxLines = 8)
                        }
                    }
                    Txt(t("reports.reportId") + ": " + v.id, style = Nx.type.bodySmall, color = c.textMuted)
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
                            Chip(t(if (banned) "reports.standing.banned" else if (muted) "reports.standing.muted" else "reports.standing.clear"), if (banned) "danger" else null)
                            Chip(t("reports.standing.cases", "count" to s.caseCount))
                            Chip(t("reports.standing.reports", "count" to d.related.count { it.subjectId == v.subjectId } + 1))
                            if (s.altCount > 0) Chip(t("reports.standing.alts", "count" to s.altCount), "warning")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NxButton(t("reports.openAccount"), { openReportTarget("users/${v.subjectId}", v.id) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH)
                        if (d.subject != null && d.subject.blocked.isEmpty())
                            NxButton(t("reports.moderateAccount"), {
                                run {
                                    val p = Moderation.user(v.subjectId)
                                    openModerate(p.user, p.servers) { case ->
                                        decision = "actioned"
                                        note = listOf(note, t("reports.caseRecorded", "number" to case.number)).filter { it.isNotBlank() }.joinToString("\n")
                                    }
                                }
                            }, size = ButtonSize.TOUCH, enabled = !busy)
                    }
                }
            }
        item {
            Panel(t("reports.reporter")) {
                PersonChip(v.reporterId, 32.dp, withUsername = true)
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
        val admin = rank?.atLeast(StaffRank.ADMIN) == true
        if (admin && (v.serverId != "0" || v.targetKind == "pack" || v.targetKind == "sticker"))
            item {
                Panel(t("reports.goTo")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (v.serverId != "0") NxButton(t("reports.openServer"), { openReportTarget("servers/${v.serverId}", v.id) }, kind = ButtonKind.SECONDARY, icon = "ExternalLink", size = ButtonSize.TOUCH)
                        if (v.targetKind == "pack" || v.targetKind == "sticker") NxButton(t("reports.openPack"), {
                            val pid = if (v.targetKind == "pack") v.targetId else v.snapshot["pack_id"]!!.jsonPrimitive.content
                            openReportTarget("expressions/packs/$pid", v.id)
                        }, kind = ButtonKind.SECONDARY, icon = "ExternalLink", size = ButtonSize.TOUCH)
                    }
                }
            }
        item {
            val shape = Radii.Lg
            Column(
                Modifier.fillMaxWidth().shadowMedium(shape, c).background(c.bgRaised, shape).topEdge(shape, c.edgeLight).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle(t("reports.decision.title"))
                if (admin && v.targetKind == "message" && !Reports.closing(v.status)) {
                    if (!removing) NxButton(t("reports.decision.removeMessage"), { removing = true }, kind = ButtonKind.DANGER, icon = "Trash2", enabled = !busy)
                    else Column(Modifier.fillMaxWidth().background(mixSrgb(c.bgInput, c.danger, 0.08f), Radii.Md).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt(t("reports.decision.removeHint"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle)
                        NxTextField(removeReason, { if (it.length <= 1000) removeReason = it }, label = t("moderation.form.reason"), hint = t("moderation.form.reasonVisible"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
                        NxTextField(told, { if (it.length <= 1000) told = it }, label = t("reports.decision.told"), hint = t("reports.decision.toldHint"), placeholder = t("reports.outcome.actioned"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NxButton(t("reports.decision.cancel"), { removing = false }, kind = ButtonKind.GHOST, enabled = !busy)
                            NxButton(t("reports.decision.removeMessage"), {
                                openSheet("removeReportedMessage", title = t("reports.decision.removeMessage")) { close ->
                                    Txt(t("reports.decision.removeConfirm"), style = Nx.type.body, color = Nx.colors.text)
                                    VSpace(16.dp)
                                    NxButton(t("reports.decision.removeMessage"), {
                                        close()
                                        run {
                                            Reports.removeMessage(v, removeReason.trim(), told.trim())
                                            removing = false
                                            removeReason = ""
                                            told = ""
                                            saved = t("reports.decision.removed")
                                            reload++
                                        }
                                    }, kind = ButtonKind.DANGER, block = true, size = ButtonSize.LG)
                                }
                            }, kind = ButtonKind.DANGER, enabled = !busy && removeReason.trim().length >= 3)
                        }
                    }
                }
                if (!removing) {
                    // two by two on a phone, as the web's .outcomes
                    Column(Modifier.fillMaxWidth().background(c.bgInput, Radii.Md).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Reports.statuses.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                pair.forEach { st -> OutcomeButton(st, decision == st, Modifier.weight(1f), enabled = !busy) { decision = st } }
                            }
                        }
                    }
                    if (closingNow && changing) {
                        NxTextField(told, { if (it.length <= 1000) told = it }, label = t("reports.decision.told"), hint = t("reports.decision.toldHint"), placeholder = t(if (decision == "dismissed") "reports.outcome.dismissed" else "reports.outcome.actioned"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
                        if (others > 0) NxCheckbox(alsoRelated, { alsoRelated = it }, t("reports.decision.alsoRelated", "count" to others), enabled = !busy)
                    }
                    NxTextField(note, { if (it.length <= 2000) { note = it; saved = "" } }, label = t("reports.staffNote"), hint = t("reports.noteHint"), required = closingNow && changing, singleLine = false, minLines = 3, enabled = !busy, capitalize = true, autoCorrect = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt(saved, Modifier.weight(1f), style = Nx.type.bodySmall, color = c.positive)
                        NxButton(
                            t(if (Reports.closing(v.status) && !closingNow) "reports.decision.reopen" else "reports.decision.save"),
                            {
                                run {
                                    val r = Reports.review(v, decision, note.trim(), if (v.assignedTo == "0" && decision != "open") me ?: "0" else v.assignedTo, told.trim(), others > 0 && alsoRelated)
                                    detail = r
                                    decision = r.report.status
                                    note = ""
                                    told = ""
                                    saved = t("reports.decision.saved")
                                }
                            },
                            enabled = !busy && !(closingNow && changing && note.trim().length < 3) && (changing || note.isNotBlank()),
                            loading = busy,
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(t("reports.related"))
                if (d.related.isEmpty()) Txt(t("reports.noRelated"), style = Nx.type.bodySmall, color = c.textMuted)
                d.related.forEach { x ->
                    Row(
                        Modifier.fillMaxWidth().clip(Radii.Sm).pressable(remember(x.id) { MutableInteractionSource() }) { goPanel("reports/${x.id}") }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReasonGlyph(x.reason, 26.dp)
                        Column(Modifier.weight(1f)) {
                            Txt(t("reports.reason.${x.reason}"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textStrong)
                            Txt(
                                if (x.targetKind == v.targetKind && x.targetId == v.targetId && v.targetId != "0") t("reports.sameItem") else t("reports.sameAccount", "kind" to t("reports.kind.${x.targetKind}")),
                                style = Nx.type.bodySmall, color = c.textMuted,
                            )
                        }
                        StaffStatusPill(x.status, small = true)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SectionTitle(t("reports.history"))
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
            }
        }
    }
}

@Composable
private fun OutcomeButton(status: String, on: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Sm
    val bg = when {
        !on -> null
        status == "actioned" -> c.positive
        status == "dismissed" -> mixSrgb(c.bgSurface, c.textMuted, 0.26f)
        else -> c.bgSurface
    }
    Box(
        modifier.heightIn(min = 44.dp)
            .clip(shape)
            .then(if (bg != null) Modifier.shadowLow(shape, c).background(bg, shape).topEdge(shape, c.edgeLight) else Modifier)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(t("reports.status.$status"), style = Nx.type.body.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = if (on && status == "actioned") c.onPositive else if (on) c.textStrong else c.textMuted, align = TextAlign.Center, maxLines = 2)
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
