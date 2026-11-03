package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Announcement
import fish.nexa.data.ModEvent
import fish.nexa.data.ModLogEntry
import fish.nexa.data.Moderation
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.markdown.MarkdownView
import fish.nexa.ui.markdown.RenderCtx
import fish.nexa.ui.nexa.PersonChip
import fish.nexa.ui.profile.NexaAvatar
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE = 50

/**
 * Every action staff took, newest first, as it was taken (LogsTab): the names
 * are the ones recorded at the time, so a later rename or deletion does not
 * rewrite history. Nothing here can be edited or removed, by anyone.
 */
@Composable
fun LogsTab() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf("all") }
    var q by remember { mutableStateOf("") }
    val entries = remember { mutableStateListOf<ModLogEntry>() }
    var loaded by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // only the newest request may fill the table: a slower answer for the filter picked before would land in this one
    var request by remember { mutableStateOf(0) }
    fun load(before: String? = null) {
        val id = ++request
        loading = true
        failed = false
        scope.launch {
            try {
                val r = Moderation.log(if (group == "all") null else LOG_GROUPS[group], q, before, PAGE)
                if (id != request) return@launch
                if (before == null) entries.clear()
                entries.addAll(r.filter { x -> entries.none { it.id == x.id } })
                more = r.size == PAGE
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (id == request) failed = true
            } finally {
                if (id == request) loading = false
            }
        }
    }
    LaunchedEffect(group, q) {
        request++
        more = false
        if (q.isNotEmpty()) delay(250)
        loaded = false
        entries.clear()
        load()
    }
    LaunchedEffect(Unit) {
        Moderation.events.collect { e ->
            if (e !is ModEvent.Log || !loaded) return@collect
            val matches = (group == "all" || LOG_GROUPS[group]?.contains(e.entry.action) == true) && q.isEmpty()
            if (matches && entries.none { it.id == e.entry.id }) entries.add(0, e.entry)
        }
    }
    val groups = listOf("all" to t("moderation.logs.all"), "punishments" to t("moderation.logs.punishments"), "lifts" to t("moderation.logs.lifts"), "announcements" to t("moderation.logs.announcements"), "access" to t("moderation.logs.access"), "badges" to t("moderation.logs.badges"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("tools") {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(q, { q = it }, t("moderation.logs.search"), Modifier.fillMaxWidth())
                ChipRow(t("moderation.logs.filter")) { for ((id, label) in groups) ModChip(label, group == id, { group = id }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon("LockKeyhole", c.textMuted, size = 15.dp)
                    Txt(t("moderation.logs.immutable"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                }
            }
        }
        when {
            failed && entries.isEmpty() -> item("failed") { LoadFailed({ load() }, loading) }
            !loaded -> item("loading") { LoadingRow() }
            entries.isEmpty() -> item("empty") { EmptyState("ScrollText", if (q.isNotEmpty()) t("moderation.noMatches") else t("moderation.logs.empty")) }
            else -> {
                items(entries, key = { it.id }) { e -> LogCard(e) }
                if (more) item("more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        NxButton(t("moderation.loadMore"), { load(entries.lastOrNull()?.id) }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH, loading = loading)
                    }
                }
            }
        }
    }
}

/** `.logCard`: one action, what it was, on whom, by whom, why, and its case. */
@Composable
private fun LogCard(e: ModLogEntry) {
    val c = Nx.colors
    val shape = Radii.Lg
    val (tone, _) = toneColours(logSeverity(e.action), c)
    Column(
        Modifier.fillMaxWidth().shadowLow(shape, c).ring(shape, c.panelEdge).background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // a longer action wraps beside the time, which stays on its first line (.logCardTop)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(logIcon(e.action), tone, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
            Txt(actionLabel(e.action), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.text, modifier = Modifier.weight(1f).alignByBaseline())
            Txt(Time.timestamp(e.createdAt), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(t("moderation.logs.target"), style = Nx.type.hint, color = c.textMuted, modifier = Modifier.heightIn(min = 22.dp))
                Txt(t("moderation.logs.moderator"), style = Nx.type.hint, color = c.textMuted, modifier = Modifier.heightIn(min = 22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
                    val tid = e.targetId
                    if (tid != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PersonChip(tid, 18.dp)
                        // the name it had then, when it is not the one it has now
                        val now = person(tid)
                        if (e.targetUsername.isNotEmpty() && now?.username != e.targetUsername) Txt(t("moderation.logs.thenNamed", "name" to e.targetUsername), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                    } else Txt(t("moderation.logs.everyone"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                }
                Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) { PersonChip(e.moderatorId, 18.dp) }
            }
        }
        val detail = logDetail(e)
        if (e.reason.isEmpty() && detail.isEmpty()) Txt(t("moderation.form.none"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (e.reason.isNotEmpty()) Txt(e.reason, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.textSubtle)
            if (detail.isNotEmpty()) Txt(detail, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        }
        val caseId = e.caseId
        val target = e.targetId
        if (caseId != null && target != null) Txt(
            "#" + (e.caseNumber ?: ""), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.link,
            modifier = Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { goPanel("users/$target/cases/$caseId") }.padding(vertical = 4.dp),
        )
    }
}

private const val MAX_TITLE = 120
private const val MAX_BODY = 4000

/**
 * Messages to everyone on Nexa, sent through Nexa (AnnouncementsTab). Each
 * lands in every account's Nexa conversation with the name of whoever wrote
 * it, and can be taken back; the log keeps both.
 */
@Composable
fun AnnouncementsTab() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Announcement>() }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var recipients by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun loadList() {
        failed = false
        scope.launch {
            try {
                val r = Moderation.announcements()
                list.clear()
                list.addAll(r.announcements)
                recipients = r.recipients
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }
    LaunchedEffect(Unit) { loadList() }
    LaunchedEffect(Unit) {
        Moderation.events.collect { e ->
            when (e) {
                is ModEvent.News -> if (loaded && list.none { it.id == e.announcement.id }) list.add(0, e.announcement)
                is ModEvent.NewsGone -> list.removeAll { it.id == e.id }
                else -> Unit
            }
        }
    }
    val bodyLength = body.codePointCount(0, body.length)
    val titleLength = title.codePointCount(0, title.length)
    val ready = body.isNotBlank() && bodyLength <= MAX_BODY && titleLength <= MAX_TITLE

    fun send() = confirm(t("moderation.ann.confirmTitle"), t("moderation.ann.confirmBody", "count" to recipients), t("moderation.ann.send"), danger = false) {
        busy = true
        try {
            val a = Moderation.announce(title.trim(), body.trim())
            if (list.none { it.id == a.id }) list.add(0, a)
            title = ""
            body = ""
            previewing = false
            Nexa.ui.toast(t("moderation.ann.sent"), "success")
        } finally {
            busy = false
        }
    }
    fun retract(a: Announcement) = confirm(t("moderation.ann.retractTitle"), t("moderation.ann.retractBody"), t("moderation.ann.retract")) {
        Moderation.retract(a.id)
        list.removeAll { it.id == a.id }
        Nexa.ui.toast(t("moderation.ann.retracted"), "success")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("compose") {
            val shape = Radii.Lg
            Column(
                Modifier.fillMaxWidth().shadowMedium(shape, c).ring(shape, c.panelEdge).background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon("Megaphone", c.textStrong, size = 18.dp)
                    Txt(t("moderation.ann.new"), style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(680)), color = c.textStrong, modifier = Modifier.semantics { heading() })
                }
                Txt(t("moderation.ann.hint", "count" to recipients), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19.6f)), color = c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NxTextField(title, { title = it.take(MAX_TITLE + 10) }, label = t("moderation.ann.titleLabel"), placeholder = t("moderation.ann.titlePlaceholder"), hint = if (titleLength > MAX_TITLE - 20) "$titleLength/$MAX_TITLE" else null, capitalize = true)
                    if (previewing) Box(
                        Modifier.fillMaxWidth().heightIn(min = 80.dp).border(1.dp, c.border, Radii.Sm).padding(12.dp),
                    ) {
                        if (body.isNotBlank()) MarkdownView(body, RenderCtx(surface = cardFill(c)))
                        else Txt(t("moderation.ann.nothingYet"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                    } else NxTextField(body, { body = it.take(MAX_BODY + 50) }, label = t("moderation.ann.bodyLabel"), placeholder = t("moderation.ann.bodyPlaceholder"), hint = t("moderation.ann.bodyHint"), singleLine = false, minLines = 6, capitalize = true, imeAction = ImeAction.Default)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NxButton(if (previewing) t("moderation.ann.edit") else t("moderation.ann.preview"), { previewing = !previewing }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH, icon = if (previewing) "PenLine" else "Eye")
                        Spacer(Modifier.weight(1f))
                        Txt("$bodyLength/$MAX_BODY", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontFeatureSettings = "tnum"), color = if (bodyLength > MAX_BODY) c.textDanger else c.textMuted)
                        NxButton(t("moderation.ann.send"), ::send, size = ButtonSize.TOUCH, icon = "Send", enabled = ready, loading = busy)
                    }
                }
            }
        }
        item("history") {
            Txt(t("moderation.ann.history"), style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(680)), color = c.textStrong, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        }
        when {
            failed && list.isEmpty() -> item("failed") { LoadFailed({ loadList() }) }
            !loaded -> item("loading") { LoadingRow() }
            list.isEmpty() -> item("empty") { EmptyState("Megaphone", t("moderation.ann.none")) }
            else -> items(list, key = { it.id }) { a -> AnnouncementCard(a) { retract(a) } }
        }
    }
}

@Composable
private fun AnnouncementCard(a: Announcement, onRetract: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Lg
    Column(Modifier.fillMaxWidth().shadowMedium(shape, c).ring(shape, c.panelEdge).background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            NexaAvatar(28.dp)
            Txt(a.title.ifEmpty { t("moderation.ann.untitled") }, style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(700)), color = c.textStrong, modifier = Modifier.weight(1f), maxLines = 2)
            ModIconButton("Trash2", t("moderation.ann.retract"), onRetract)
        }
        Box(Modifier.padding(end = 8.dp)) { MarkdownView(a.body, RenderCtx(surface = cardFill(c))) }
        Row(Modifier.padding(top = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(t("nexa.postedBy"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            PersonChip(a.authorId, 18.dp, withUsername = true)
            Txt("· " + Time.timestamp(a.createdAt), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
        }
    }
}
