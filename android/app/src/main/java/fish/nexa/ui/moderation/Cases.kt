package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.CaseMessage
import fish.nexa.data.Ids
import fish.nexa.data.ModEvent
import fish.nexa.data.Moderation
import fish.nexa.data.StaffCase
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.dialogs.promptText
import fish.nexa.ui.nexa.LockNote
import fish.nexa.ui.nexa.NoticeCard
import fish.nexa.ui.nexa.ThreadChat
import fish.nexa.ui.nexa.ThreadIntro
import fish.nexa.ui.nexa.ThreadOpener
import fish.nexa.ui.nexa.UnreadDot
import fish.nexa.ui.nexa.caseStanding
import fish.nexa.ui.nexa.line
import fish.nexa.ui.nexa.systemLineText
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch

/** Whether a case belongs in a view of the Active Cases tab. */
private fun inView(c: StaffCase, view: String) = when (view) {
    "needs_reply" -> c.status == "open" && c.awaiting == "staff"
    "open" -> c.status == "open"
    "in_effect" -> c.inEffect
    "closed" -> c.status == "closed"
    else -> true
}

private val byActivity = Comparator<StaffCase> { a, b -> if (b.lastActivityAt != a.lastActivityAt) b.lastActivityAt.compareTo(a.lastActivityAt) else Ids.compare(b.id, a.id) }

/** One line of the thread as a preview in a list: plain words, whatever formatting it had. */
private fun preview(c: StaffCase, whoName: String? = null): String {
    val last = c.lastMessage ?: return previewText(c.reason)
    if (last.kind == "system") return systemLineText(last.line(), c.action, staff = true, whoName = whoName)
    val who = whoName ?: nameOf(fish.nexa.data.NexaInbox.people.value[last.authorId]).takeIf { fish.nexa.data.NexaInbox.people.value[last.authorId] != null } ?: c.targetDisplayName
    return "$who: " + previewText(last.body, last.files.size)
}

/** The cases as rows (CaseList): who or what, the newest line, and where each stands. */
fun LazyListScope.caseItems(cases: List<StaffCase>, selected: String?, showTarget: Boolean, onPick: (String) -> Unit) {
    items(cases, key = { it.id }) { cs -> CaseRow(cs, selected == cs.id, showTarget) { onPick(cs.id) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaseRow(cs: StaffCase, selected: Boolean, showTarget: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val fresh = Moderation.unread.collectAsState().value.cases.contains(cs.id)
    val target = person(cs.userId)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Md
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(c.bgSelected, shape).background(sheen(c), shape).topEdge(shape, c.edgeLight) else Modifier.background(pressTint(pressed), shape))
            .clip(shape)
            .pressable(interaction, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTarget) Avatar(cs.userId, target?.let { nameOf(it) } ?: cs.targetDisplayName, target?.avatarId, 34.dp)
        else CaseIcon(cs.action)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fish.nexa.ui.chat.PersonName(
                    cs.userId.takeIf { showTarget && target?.deleted != true }, if (showTarget) (target?.let { nameOf(it) } ?: cs.targetDisplayName) else caseTitle(cs),
                    style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(if (fresh) 750 else 600)), color = c.textStrong, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (fresh) UnreadDot()
                Txt(Time.relative(cs.lastActivityAt), style = Nx.type.hint, color = c.textMuted, maxLines = 1)
            }
            if (showTarget) Txt(caseTitle(cs), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
            fish.nexa.ui.chat.PersonText(cs.lastMessage?.authorId, preview(cs, fish.nexa.ui.chat.PERSON_SLOT), cs.lastMessage?.authorId?.let { fish.nexa.ui.nexa.personName(fish.nexa.ui.nexa.threadPerson(it)) } ?: "", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textSubtle, maxLines = 1)
            FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cs.status == "open" && cs.awaiting == "staff") Tag(t("moderation.case.needsReply"), "danger")
                caseStanding(cs.asMember())?.let { (label, tone) -> Tag(label, tone) }
                if (cs.status == "closed") Tag(t("moderation.case.closed"))
            }
        }
    }
}

/** The Active Cases tab (CasesTab): every case, in the view picked, most recently active first; one open in its place on a phone. */
@Composable
fun CasesTab(caseId: String?) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf("needs_reply") }
    val cases = remember { mutableStateListOf<StaffCase>() }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val counts by Moderation.counts.collectAsState()

    // only the newest request may fill the list: a slower answer for the view picked before would land in this one
    var request by remember { mutableStateOf(0) }
    fun load(after: StaffCase? = null) {
        val id = ++request
        loading = true
        failed = false
        scope.launch {
            try {
                val r = Moderation.cases(view, after, 50)
                if (id != request) return@launch
                if (after == null) cases.clear()
                cases.addAll(r.cases.filter { x -> cases.none { it.id == x.id } })
                more = r.cases.size == 50
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // said as a failure, never as an empty queue: "all caught up" during an outage would tell staff nobody waits
                if (id == request) failed = true
            } finally {
                if (id == request) loading = false
            }
        }
    }
    LaunchedEffect(view) {
        loaded = false
        cases.clear()
        load()
    }
    fun upsert(cs: StaffCase) {
        val rest = cases.filter { it.id != cs.id }
        val next = if (inView(cs, view) || cs.id == caseId) (listOf(cs) + rest).sortedWith(byActivity) else rest
        cases.clear()
        cases.addAll(next)
    }
    LaunchedEffect(Unit) { Moderation.events.collect { e -> if (e is ModEvent.Case && loaded) upsert(e.case) } }

    if (caseId != null) {
        StaffCasePane(caseId, showMember = true, onBack = { goPanel("cases", replace = true) }) { upsert(it) }
        return
    }
    val views = listOf(
        "needs_reply" to (t("moderation.view.needsReply") to counts?.needsReply),
        "open" to (t("moderation.view.open") to counts?.open),
        "in_effect" to (t("moderation.view.inEffect") to counts?.inEffect),
        "closed" to (t("moderation.view.closed") to counts?.closed),
        "all" to (t("moderation.view.all") to counts?.all),
    )
    Column(Modifier.fillMaxSize().background(c.bgBase)) {
        ChipRow(t("moderation.view.label"), Modifier.fillMaxWidth().drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(12.dp)) {
            for ((id, pair) in views) ModChip(pair.first, view == id, { view = id }, count = pair.second, small = true)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
            when {
                failed && cases.isEmpty() -> item("failed") { LoadFailed({ load() }, loading) }
                !loaded -> item("loading") { LoadingRow() }
                cases.isEmpty() -> item("empty") { EmptyState("Inbox", if (view == "needs_reply") t("moderation.view.caughtUp") else t("moderation.view.none")) }
                else -> {
                    caseItems(cases, null, showTarget = true) { goPanel("cases/$it") }
                    if (more) item("more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            NxButton(t("moderation.loadMore"), { load(cases.lastOrNull()) }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH, loading = loading)
                        }
                    }
                }
            }
        }
    }
}

/** A row of a pane's header: back, what it is, and the buttons that act on it (`.paneHeader` on a phone). */
@Composable
fun PaneHeader(onBack: () -> Unit, wrap: Boolean = false, title: @Composable () -> Unit, actions: @Composable () -> Unit) {
    val c = Nx.colors
    val rule = Modifier.drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
    if (wrap) Column(rule.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModIconButton("ChevronLeft", t("moderation.back"), onBack)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { title() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    } else Row(
        rule.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModIconButton("ChevronLeft", t("moderation.back"), onBack)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { title() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

/** A pane header's title: plain, cut short where it must. */
@Composable
fun PaneTitle(text: String) {
    Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(700)), color = Nx.colors.textStrong, maxLines = 1)
}

/**
 * A case for the staff (StaffCasePane): the notice as the member got it, its
 * thread, and what can be done about it: reply as Nexa, lift the mute or ban,
 * close it or open it again.
 */
@Composable
fun StaffCasePane(caseId: String, showMember: Boolean, onBack: () -> Unit, onChanged: (StaffCase) -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var case by remember(caseId) { mutableStateOf<StaffCase?>(null) }
    val messages = remember(caseId) { mutableStateListOf<CaseMessage>() }
    var failed by remember(caseId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun merge(lines: List<CaseMessage>) {
        val seen = messages.mapTo(HashSet()) { it.id }
        val add = lines.filter { it.id !in seen }
        if (add.isEmpty()) return
        val all = (messages + add).sortedWith { a, b -> Ids.compare(a.id, b.id) }
        messages.clear()
        messages.addAll(all)
    }
    fun apply(cs: StaffCase, lines: List<CaseMessage> = emptyList()) {
        case = cs
        merge(lines)
        onChanged(cs)
    }
    LaunchedEffect(caseId) {
        try {
            val r = Moderation.thread(caseId)
            case = r.case
            messages.clear()
            messages.addAll(r.messages)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed = true
        }
    }
    LaunchedEffect(caseId) {
        Moderation.events.collect { e -> if (e is ModEvent.Case && e.case.id == caseId && case != null) { case = e.case; merge(e.messages) } }
    }

    fun setClosed(closed: Boolean) {
        busy = true
        Nexa.scope.launch {
            try {
                apply(Moderation.setClosed(caseId, closed))
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
            } finally {
                busy = false
            }
        }
    }
    fun lift(cs: StaffCase) {
        val what = if (cs.action == "ban") t("moderation.case.liftBan") else t("moderation.case.liftMute")
        promptText(what, t("moderation.case.liftReason"), placeholder = t("moderation.case.liftPlaceholder"), maxLength = 1000, hint = t("moderation.case.liftHint"), confirmLabel = what, allowEmpty = true) { reason ->
            busy = true
            Nexa.scope.launch {
                try {
                    apply(Moderation.lift(caseId, reason.trim()))
                    Nexa.ui.toast(t("moderation.case.lifted"), "success")
                } catch (e: Exception) {
                    Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
                } finally {
                    busy = false
                }
            }
        }
    }

    val cs = case
    if (failed) {
        EmptyState(null, null, t("moderation.case.missing")) { NxButton(t("moderation.back"), onBack, kind = ButtonKind.SECONDARY, size = ButtonSize.SM) }
        return
    }
    if (cs == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) }
        return
    }
    val target = person(cs.userId)
    val gone = target == null || target.deleted
    val targetName = target?.let { nameOf(it) } ?: cs.targetDisplayName
    // a mod lifts, closes and reopens only the cases they opened; admins and above any
    val mine = fish.nexa.data.StaffRanks.mine()
    val handles = (mine != null && mine.atLeast(fish.nexa.data.StaffRank.ADMIN)) || cs.moderatorId == Nexa.users.me.value?.id
    Column(Modifier.fillMaxSize()) {
        PaneHeader(
            onBack,
            title = {
                CaseIcon(cs.action, 28.dp)
                PaneTitle(caseTitle(cs))
            },
        ) {
            // on the member's own page the way to them goes nowhere
            if (!gone && showMember) ModIconButton("UserRound", t("moderation.case.openPerson", "name" to targetName), { goPanel("users/${cs.userId}") })
            if (cs.inEffect && handles) ModIconButton("LockOpen", if (cs.action == "ban") t("moderation.case.liftBan") else t("moderation.case.liftMute"), { lift(cs) }, enabled = !busy, positive = true)
            if (handles && cs.status == "open") ModIconButton("CircleCheck", t("moderation.case.close"), { setClosed(true) }, enabled = !busy)
            else if (handles) ModIconButton("RotateCcw", t("moderation.case.reopen"), { setClosed(false) }, enabled = !busy)
        }
        ThreadChat(
            lines = messages.map { it.line() },
            staff = true,
            fileKind = "cases",
            action = cs.action,
            placeholder = t("moderation.case.replyPlaceholder"),
            intro = {
                ThreadIntro(
                    { Avatar(cs.userId, targetName, target?.avatarId, 80.dp) },
                    t("nexa.notice.caseNumber", "number" to cs.number),
                    if (target != null && !target.deleted) "@" + target.username else cs.targetUsername,
                    t("moderation.chat.caseStart", "name" to targetName),
                )
            },
            opener = ThreadOpener("nexa", cs.createdAt) { NoticeCard(cs.asMember(), staff = cs) },
            // a closed case is closed to staff too, until one of them opens it again
            onSend = if (gone || cs.status == "closed") null else ({ body, files ->
                val r = Moderation.reply(caseId, body, files)
                apply(r.case, r.messages)
            }),
            uploadPath = "/moderation/cases/attachments",
            locked = if (gone) ({ LockNote(t("moderation.case.goneNote")) }) else ({
                LockNote(t("moderation.case.closedNote"), if (!handles) null else ({
                    NxButton(t("moderation.case.reopen"), { setClosed(false) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "RotateCcw", enabled = !busy)
                }))
            }),
            note = {
                fish.nexa.ui.chat.PersonText(cs.userId, t("moderation.case.replyNote", "name" to fish.nexa.ui.chat.PERSON_SLOT), nameOf(target), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)), color = c.textMuted)
            },
            onSeen = { last -> Moderation.readThread("case", caseId, last) },
        )
    }
}
