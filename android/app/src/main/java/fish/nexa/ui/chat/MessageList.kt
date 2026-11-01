package fish.nexa.ui.chat

import fish.nexa.ui.collectEntryAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ChannelMessages
import fish.nexa.data.ChatMessage
import fish.nexa.data.Ids
import fish.nexa.net.Channel
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.LoadingDots
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.shell.Names
import fish.nexa.ui.shell.openCreateDm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.dangerGradient
import fish.nexa.ui.theme.glowBrand
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Calendar

/** One line of the list, oldest first before the list turns it over. */
sealed interface ListRow {
    val key: String

    data class Day(override val key: String, val at: Long) : ListRow
    data object NewLine : ListRow { override val key = "new" }
    data class Msg(val item: MsgItem) : ListRow { override val key get() = item.key }
    data class System(override val key: String, val cm: ChatMessage) : ListRow
    data class Blocked(override val key: String, val ids: List<String>, val shown: Boolean, val items: List<MsgItem>) : ListRow
    data object Start : ListRow { override val key = "start" }
    data object TopLoader : ListRow { override val key = "top-loader" }
    data object TopError : ListRow { override val key = "top-error" }
    data object BottomLoader : ListRow { override val key = "bottom-loader" }
    /** Nexa's note to someone a friend put in this group: drawn among the messages, not one of them. */
    data class Notice(val notice: fish.nexa.net.GroupAddNotice) : ListRow { override val key = "group-add-notice" }
}

/** Where Nexa's note about an add goes (lib/groupAdd.ts noticeSpot). */
private sealed interface NoticeSpot {
    data object Start : NoticeSpot
    data object End : NoticeSpot
    data class After(val id: String) : NoticeSpot
}

/**
 * Just after the line that says this account was added, when it is loaded;
 * else at the very start of a conversation that began after the add; else at
 * the end once the newest messages are in. An earlier add line is from a time
 * they were added before, left, and were added again.
 */
private fun noticeSpot(state: ChannelMessages, notice: fish.nexa.net.GroupAddNotice, meId: String): NoticeSpot? {
    for (id in state.ids) {
        val m = state.byId[id]?.m ?: continue
        if (m.type == "system_member_add" && m.authorId == notice.addedBy && meId in m.mentions && m.createdAt >= notice.addedAt - 1000) return NoticeSpot.After(id)
    }
    if (!state.hasOlder) {
        val first = state.ids.firstNotNullOfOrNull { state.byId[it] }
        if (first == null || first.m.createdAt >= notice.addedAt) return NoticeSpot.Start
    }
    return if (state.hasNewer) null else NoticeSpot.End
}

private const val GROUP_MS = 7 * 60 * 1000L

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private data class Prev(val authorId: String, val at: Long, val system: Boolean, val hook: String?)

/** Webhook messages group only when the webhook and the name and picture it sent match; an app's group by the app, as its bot's. */
private fun hookKey(cm: ChatMessage?): String? {
    val h = fish.nexa.data.Webhooks.content(cm?.hook) ?: return null
    h.applicationId?.let { return "app:$it" }
    return "${cm!!.m.webhookId}|${h.name}|${h.avatarId ?: h.avatarUrl ?: ""}"
}

/**
 * shouldGroup: same author within 7 minutes on the same day, a plain message, no reply, after no system line.
 * A message that started a thread shows its own avatar, which the line to the thread's card hangs from.
 */
private fun groups(prev: Prev?, authorId: String, at: Long, type: String, reply: String?, hook: String?, thread: Boolean = false): Boolean {
    if (prev == null || prev.system) return false
    if (prev.authorId != authorId || prev.hook != hook) return false
    if (type != "default" && type != "reply") return false
    if (reply != null) return false
    if (thread) return false
    if (at - prev.at > GROUP_MS) return false
    return sameDay(prev.at, at)
}

/**
 * Builds the rows (MessageList.tsx): day dividers, the New line where the
 * conversation's unread part began when it was opened, messages grouped by
 * author, blocked people's messages folded into runs, and pending sends.
 */
private fun buildRows(
    state: ChannelMessages,
    meId: String,
    newAfter: String?,
    blockedShown: Set<String>,
    hideBlocked: Boolean,
    hideBlockedReplies: Boolean,
    notice: fish.nexa.net.GroupAddNotice?,
): List<ListRow> {
    val out = ArrayList<ListRow>()
    if (state.loaded && !state.hasOlder) out += ListRow.Start
    else if (state.failed > 0 && !state.loaded) out += ListRow.TopError
    else out += ListRow.TopLoader
    var prev: Prev? = null
    // Nexa's note about who added this account, where noticeSpot says; whatever follows it starts a group of its own
    val spot = if (notice != null && state.loaded) noticeSpot(state, notice, meId) else null
    var noticePlaced = false
    fun placeNotice() {
        if (notice == null || noticePlaced) return
        out += ListRow.Notice(notice)
        noticePlaced = true
        prev = null
    }
    if (spot == NoticeSpot.Start) placeNotice()
    var lastDay: Long? = null
    var newPlaced = newAfter == null
    var run: ArrayList<MsgItem>? = null
    var runKey: String? = null
    fun closeRun() {
        val r = run ?: return
        val key = runKey!!
        out += ListRow.Blocked(key, r.map { it.key }, key in blockedShown, r.toList())
        run = null
        runKey = null
    }
    for (id in state.ids) {
        val cm = state.byId[id] ?: continue
        val m = cm.m
        val at = m.createdAt
        if (lastDay == null || !sameDay(lastDay, at)) {
            closeRun()
            out += ListRow.Day("day-$id", at)
            lastDay = at
            prev = null
        }
        val blocked = Nexa.relationships.isBlocked(m.authorId) && !m.type.startsWith("system_")
        if (!newPlaced && newAfter != null && Ids.compare(id, newAfter) > 0 && m.authorId != meId && !blocked) {
            closeRun()
            out += ListRow.NewLine
            newPlaced = true
        }
        if (m.type.startsWith("system_") && m.type != "system_call") {
            closeRun()
            out += ListRow.System(id, cm)
            prev = Prev(m.authorId, at, true, null)
            if (spot == NoticeSpot.After(id)) placeNotice()
            continue
        }
        if (blocked) {
            if (hideBlocked) continue
            if (run == null) {
                run = ArrayList()
                runKey = "blocked-$id"
            }
            run!! += MsgItem(id, cm, groupStart = true)
            prev = null
            continue
        }
        if (hideBlockedReplies && m.replyToId != null) {
            val orig = state.byId[m.replyToId] ?: (fish.nexa.data.ReplyTargets.byId.value[m.replyToId] as? fish.nexa.data.ReplyTarget.Found)?.cm
            if (orig != null && Nexa.relationships.isBlocked(orig.authorId)) continue
        }
        closeRun()
        val hook = hookKey(cm)
        val start = !groups(prev, m.authorId, at, m.type, m.replyToId, hook, thread = m.thread != null)
        val mentioned = m.authorId != meId && mentionsMe(m, meId)
        out += ListRow.Msg(MsgItem(id, cm, groupStart = start, mentionsMe = mentioned))
        prev = Prev(m.authorId, at, m.type == "system_call", hook)
        if (spot == NoticeSpot.After(id)) placeNotice()
    }
    closeRun()
    // at the end, or there after all when the line it follows is not shown
    if (spot == NoticeSpot.End || (spot != null && !noticePlaced && !state.hasNewer)) placeNotice()
    if (!state.hasNewer) {
        for (p in state.pending) {
            if (lastDay == null || !sameDay(lastDay, p.createdAt)) {
                out += ListRow.Day("day-${p.tmpId}", p.createdAt)
                lastDay = p.createdAt
                prev = null
            }
            val start = !groups(prev, p.authorId, p.createdAt, if (p.replyToId != null) "reply" else "default", p.replyToId, null)
            out += ListRow.Msg(MsgItem(p.tmpId, pending = p, groupStart = start))
            prev = Prev(p.authorId, p.createdAt, false, null)
        }
    } else out += ListRow.BottomLoader
    return out
}

/**
 * MessageList.tsx: bottom-anchored (the newest message sits at the bottom and
 * the list grows upward), pages of fifty loading as you near either end, the
 * reading position held while things change size, and the conversation
 * marked read whenever you are at its newest message.
 */
@Composable
fun MessageList(channel: Channel, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val channelId = channel.id
    val entry by Nexa.messages.channels.collectEntryAsState(channelId)
    val state = entry ?: remember(channelId) { ChannelMessages() }
    val mark = Nexa.readStates.byChannel.collectEntryAsState(channelId).value
    // blocking someone changes which rows fold away
    val rels by Nexa.relationships.byId.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val server by Nexa.servers.byId.collectEntryAsState(channel.serverId)
    val visible by Nexa.ui.visible.collectAsState()
    val me by Nexa.users.me.collectAsState()
    val meId = me?.id ?: ""
    val compact = remember(doc) { Nexa.settings.compact }
    val selecting = ChatUi.selecting == channelId
    val ctx = remember(channel, server, compact, selecting, meId) { ListCtx(channel, server, compact, selecting, meId) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val density = LocalDensity.current.density

    // where the unread part began when this conversation was opened; moved only by Mark Unread
    var newAfter by remember(channelId) { mutableStateOf(mark?.lastReadId.takeIf { Nexa.readStates.isUnread(channelId) }) }
    LaunchedEffect(mark?.lastReadId, mark?.manual) {
        val id = mark?.lastReadId
        if (mark?.manual == true && id != null && (newAfter == null || Ids.compare(id, newAfter!!) < 0)) newAfter = id
    }

    val blockedShown = remember(channelId) { mutableStateMapOf<String, Boolean>() }
    val notice by Nexa.misc.groupAddNotices.collectEntryAsState(channelId)
    val rows = remember(state, meId, newAfter, blockedShown.toMap(), doc, rels, notice) {
        buildRows(state, meId, newAfter, blockedShown.filterValues { it }.keys, Nexa.settings.hideBlockedMessages, Nexa.settings.hideBlockedReplies, notice)
    }
    val reversed = remember(rows) { rows.asReversed() }
    val latestRows by androidx.compose.runtime.rememberUpdatedState(reversed)
    val reduced = Nx.motion.reduced

    // first page, and retries with backoff when it fails
    LaunchedEffect(channelId) { Nexa.messages.loadInitial(channelId) }
    LaunchedEffect(channelId, state.failed, state.loaded) {
        if (state.loaded || state.failed == 0) return@LaunchedEffect
        delay(minOf(30_000L, 1000L shl minOf(state.failed - 1, 5)))
        Nexa.messages.loadInitial(channelId)
    }

    // at the bottom: within 80 dp of the newest edge with the newest page loaded
    val atBottom by remember(state.hasNewer) {
        derivedStateOf { !state.hasNewer && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 80 * density }
    }
    LaunchedEffect(atBottom, channelId) { Nexa.ui.setAtBottom(channelId, atBottom) }

    // loading older and newer pages as the ends come near
    LaunchedEffect(channelId, list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            Triple(last >= info.totalItemsCount - 4, first <= 3, info.totalItemsCount)
        }.distinctUntilChanged().collect { (nearTop, nearBottom, _) ->
            val s = Nexa.messages.get(channelId)
            if (nearTop && s.loaded && s.hasOlder && !s.loading) scope.launch { Nexa.messages.loadOlder(channelId) }
            if (nearBottom && s.hasNewer && !s.loading) scope.launch { Nexa.messages.loadNewer(channelId) }
        }
    }

    // arrivals: stay pinned to the bottom when there; otherwise count others' messages for the pill
    var away by remember(channelId) { mutableStateOf<Pair<Int, Long>?>(null) }
    var lastNewest by remember(channelId) { mutableStateOf(state.ids.lastOrNull()) }
    var wasAtBottom by remember(channelId) { mutableStateOf(true) }
    LaunchedEffect(atBottom) {
        wasAtBottom = atBottom
        if (atBottom) away = null
    }
    val newest = state.ids.lastOrNull()
    val pendingCount = state.pending.size
    LaunchedEffect(newest, pendingCount) {
        val before = lastNewest
        lastNewest = newest
        if (wasAtBottom) {
            list.scrollToItem(0)
            return@LaunchedEffect
        }
        if (before == null || newest == null || newest == before) return@LaunchedEffect
        val fresh = state.ids.filter { Ids.compare(it, before) > 0 }.mapNotNull { state.byId[it] }
            .filter { it.authorId != meId && !Nexa.relationships.isBlocked(it.authorId) }
        if (fresh.isEmpty()) return@LaunchedEffect
        val cur = away
        away = if (cur == null) fresh.size to fresh.first().m.createdAt else (cur.first + fresh.size) to cur.second
    }

    // sending snaps to the bottom (and back to the present from old history)
    val snap = ChatUi.toBottom[channelId] ?: 0
    LaunchedEffect(snap) {
        if (snap == 0) return@LaunchedEffect
        if (Nexa.messages.get(channelId).hasNewer) Nexa.messages.returnToPresent(channelId)
        list.scrollToItem(0)
        wasAtBottom = true
    }

    // Read marking: newest page, at the bottom, on screen. Live arrivals can
    // fill ids before history loads, without changing newest when it finishes.
    LaunchedEffect(atBottom, visible, newest, state.loaded, state.hasNewer, mark?.lastReadId, mark?.mentions) {
        if (!atBottom || !visible || !state.loaded || state.hasNewer) return@LaunchedEffect
        val id = newest ?: return@LaunchedEffect
        val read = mark?.lastReadId
        if (read != null && Ids.compare(read, id) >= 0 && (mark.mentions == 0)) return@LaunchedEffect
        delay(250)
        Nexa.session.ack(channelId, id)
    }

    // jumping to a message: centred and flashed, fetched around it first when it is not loaded
    var flashes by remember(channelId) { mutableStateOf(mapOf<String, Int>()) }
    var flashSeq by remember { mutableIntStateOf(0) }
    val jump = ChatUi.jump
    LaunchedEffect(jump) {
        val j = jump ?: return@LaunchedEffect
        if (j.channelId != channelId) return@LaunchedEffect
        var s = Nexa.messages.get(channelId)
        if (!s.byId.containsKey(j.messageId)) {
            // the jump stays set until this load ends, so a list recomposed meanwhile (a cold open) still makes it
            Nexa.messages.loadAround(channelId, j.messageId)
            s = Nexa.messages.get(channelId)
            if (!s.byId.containsKey(j.messageId)) {
                if (ChatUi.jump == j) ChatUi.jump = null
                Nexa.ui.toast(t("chat.messageList.thatMessageCouldNotBeFound"), "error")
                return@LaunchedEffect
            }
        }
        val target = s.byId[j.messageId]!!
        if (Nexa.relationships.isBlocked(target.authorId) && Nexa.settings.hideBlockedMessages) {
            if (ChatUi.jump == j) ChatUi.jump = null
            Nexa.ui.toast(t("people.blocked.jumpHidden"))
            return@LaunchedEffect
        }
        // a message inside a folded blocked run opens the run first
        rows.firstOrNull { it is ListRow.Blocked && j.messageId in it.ids }?.let { blockedShown[it.key] = true }
        delay(32)
        val idx = latestRows.indexOfFirst { it.key == j.messageId || (it is ListRow.Blocked && j.messageId in it.ids) }
        if (idx >= 0) {
            val h = list.layoutInfo.viewportSize.height
            list.scrollToItem(idx, -h / 3)
        }
        flashSeq += 1
        flashes = flashes + (j.messageId to flashSeq)
        // done: clearing it restarts this effect with nothing to do, so it goes last
        if (ChatUi.jump == j) ChatUi.jump = null
    }

    Box(modifier.fillMaxWidth().semantics { contentDescription = t("modals.quickSwitcher.messages") }) {
        LazyColumn(
            state = list,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
        ) {
            items(reversed, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is ListRow.Day -> DateDivider(row.at)
                    ListRow.NewLine -> NewDivider()
                    is ListRow.Msg -> {
                        val cm = row.item.cm
                        if (cm?.m?.type == "system_call") CallRecordRow(cm, ctx)
                        else MessageRow(row.item, ctx, cm?.let { flashes[it.id] }, picked = cm != null && cm.id in ChatUi.picked, canPick = cm != null && !cm.m.ephemeral && canDelete(cm.m))
                    }
                    is ListRow.System -> if (row.cm.m.type == "system_automod") fish.nexa.ui.automod.AutomodAlertRow(row.cm, ctx) else SystemRow(row.cm, ctx)
                    is ListRow.Blocked -> BlockedRun(row, ctx) { blockedShown[row.key] = !row.shown }
                    ListRow.Start -> ConversationStart(channel)
                    ListRow.TopLoader -> Loader()
                    ListRow.TopError -> LoadError { scope.launch { Nexa.messages.loadInitial(channelId) } }
                    ListRow.BottomLoader -> Loader()
                    is ListRow.Notice -> GroupAddNoticeRow(row.notice)
                }
            }
        }
        val pill = away
        if (pill != null && !atBottom) NewPill(pill.first, pill.second) { jumpToPresent(channelId, list, scope, reduced) }
        else if (!atBottom && state.loaded) JumpButton { jumpToPresent(channelId, list, scope, reduced) }
    }
}

private fun jumpToPresent(channelId: String, list: LazyListState, scope: kotlinx.coroutines.CoroutineScope, reduced: Boolean) {
    scope.launch {
        if (Nexa.messages.get(channelId).hasNewer) Nexa.messages.returnToPresent(channelId)
        if (reduced) list.scrollToItem(0) else list.animateScrollToItem(0)
    }
}

@Composable
private fun Loader() {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp), contentAlignment = Alignment.Center) {
        LoadingDots(Nx.colors.textMuted, 6.dp)
    }
}

@Composable
private fun LoadError(retry: () -> Unit) {
    val c = Nx.colors
    FlowRow(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(t("chat.messageList.messagesCouldNotBeLoaded"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
        Txt(
            t("chat.messageList.tryAgain"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(500)), color = c.link,
            modifier = Modifier.pressable(remember { MutableInteractionSource() }, onClick = retry).padding(4.dp),
        )
    }
}

/** The date in a small pill, a line fading out on each side. */
@Composable
internal fun DateDivider(at: Long) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(0f to Color.Transparent, 0.3f to c.border, 1f to c.border)))
        val shape = RoundedCornerShape(999.dp)
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .ring(shape, c.borderSubtle)
                .background(c.bgSurface, shape)
                .topEdge(shape, c.edgeLight)
                .padding(horizontal = 11.dp, vertical = 3.dp),
        ) {
            Txt(Time.dateDivider(at), style = Nx.type.hint.copy(fontWeight = FontWeight(650)), color = c.textMuted, maxLines = 1)
        }
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(0f to c.border, 0.7f to c.border, 1f to Color.Transparent)))
    }
}

/** The New line: a red rule with a "New" pill at its end, where unread messages begin. */
@Composable
private fun NewDivider() {
    val c = Nx.colors
    // the pill sits across the line and overlaps the row above it, so it draws above its neighbours
    Box(Modifier.zIndex(1f).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp).height(1.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.danger.copy(alpha = 0.8f)))
        val shape = RoundedCornerShape(999.dp)
        // 18 dp tall on a 1 dp line: held at its full height and centred across the line
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .wrapContentHeight(unbounded = true)
                .requiredHeight(18.dp)
                .boxShadow(shape, 2f, 6f, c.danger.copy(alpha = 0.45f))
                .background(dangerGradient(c), shape)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(t("chat.messageList.new"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = c.onDanger)
        }
    }
}

@Composable
private fun BoxScope.NewPill(count: Int, since: Long, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    val enter = remember { Animatable(0f) }
    val spring = Nx.motion.spring
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = spring)) }
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 6.dp, start = 16.dp, end = 16.dp)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = (1f - enter.value) * -8f * density
                val s = 0.98f + 0.02f * enter.value
                scaleX = s; scaleY = s
            }
            .glowBrand(shape, c)
            .background(brandGradient(c), shape)
            .topEdge(shape, Color.White.copy(alpha = 0.2f))
            .heightIn(min = 36.dp)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(
            t("chat.messageList.newMessagesSince", "count" to count, "time" to Time.time(since)),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(550)), color = c.onBrand, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Txt(t("chat.messageList.jumpToPresent"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.onBrand, maxLines = 1)
    }
}

@Composable
internal fun BoxScope.JumpButton(onClick: () -> Unit) {
    val c = Nx.colors
    val enter = remember { Animatable(0f) }
    val spring = Nx.motion.spring
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = spring)) }
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp, bottom = 10.dp)
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = (1f - enter.value) * -8f * density
            }
            .size(40.dp)
            .shadowHigh(CircleShape, c)
            .ring(CircleShape, c.panelEdge)
            .background(c.bgFloating, CircleShape)
            .topEdge(CircleShape, c.edgeLight)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = t("chat.messageList.jumpToPresent") },
        contentAlignment = Alignment.Center,
    ) {
        Icon("ArrowDown", c.text, size = 18.dp)
    }
}

/** The top of a conversation's history: who or where this is, and that it is encrypted. */
@Composable
private fun ConversationStart(channel: Channel) {
    if (channel.isThread) return fish.nexa.ui.threads.ThreadIntro(channel)
    val c = Nx.colors
    val endToEnd = t("chat.messageList.endToEnd")
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        val other = if (channel.type == "dm") Names.otherOf(channel) else null
        when {
            channel.type == "dm" && other != null -> {
                val name = Names.shown(other)
                StartAvatar { Avatar(other, name, Nexa.users.get(other)?.avatarId, 80.dp) }
                RoleName(name, RoleLooks.withOwn(null, Nexa.users.byId.collectAsState().value[other]?.nameStyle), Nx.type.title.copy(fontSize = Nx.type.sp(28f), lineHeight = Nx.type.sp(30.8f), fontWeight = FontWeight(700)), c.textStrong)
                Txt(Nexa.users.get(other)?.username ?: "", style = Nx.type.body.copy(fontSize = Nx.type.sp(18f), fontWeight = FontWeight(500)), color = c.textSubtle, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                PersonText(other, t("chat.messageList.dmStart", "name" to PERSON_SLOT), name, Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(23.25f)), c.textMuted, Modifier.widthIn(max = 560.dp), nameTextStyle = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(23.25f), fontWeight = FontWeight(700)))
            }
            channel.type == "group_dm" -> {
                val name = Names.channel(channel)
                if (channel.iconId != null) StartAvatar { Avatar(channel.id, name, channel.iconId, 80.dp, icon = true) }
                StartTitle(name)
                Spacer(Modifier.height(8.dp))
                val alone = channel.recipientIds.none { it != Nexa.users.meId }
                if (alone) {
                    StartText(t("chat.messageList.groupAlone") + " " + endToEnd)
                    Box(Modifier.padding(top = 12.dp)) {
                        // whoever is picked here joins this group, and is handed its key like anyone added later
                        NxButton(t("chat.chat.addFriends"), { fish.nexa.ui.dialogs.Dialogs.createDm(groupId = channel.id) }, size = ButtonSize.SM, icon = "UserPlus")
                    }
                } else StartWithName("chat.messageList.groupStart", "name", name, " $endToEnd")
            }
            channel.type == "subchat" -> {
                StartTitle(channel.name)
                Spacer(Modifier.height(8.dp))
                val parent = channel.parentId?.let { Nexa.channels.get(it) }?.let { Names.channel(it) } ?: t("chat.messageList.thisConversation")
                StartWithName("chat.messageList.subchatStart", "where", parent)
            }
            else -> {
                StartTitle(t("chat.messageList.welcomeToChannel", "name" to channel.name))
                Spacer(Modifier.height(8.dp))
                val topic = channel.topic.takeIf { it.isNotBlank() }?.let { fish.nexa.ui.markdown.Markdown.plainText(fish.nexa.ui.markdown.Markdown.parse(it), AppNames).replace(Regex("\\s+"), " ").trim() }
                // apps that read every message here: the start of the channel names them with the encryption line
                val readers = fish.nexa.ui.apps.readingApps(channel.serverId, channel.id)
                val encryption = if (readers.isEmpty()) endToEnd else t("apps.reading.endToEndExcept", "apps" to Names.list(readers.map { Names.shown(it) }))
                StartWithName("chat.messageList.channelStart", "name", "#${channel.name}", listOfNotNull(topic, encryption).joinToString(" ", prefix = " "))
            }
        }
    }
}

@Composable
internal fun StartAvatar(content: @Composable () -> Unit) {
    val c = Nx.colors
    Box(
        Modifier
            .padding(bottom = 14.dp)
            .boxShadow(CircleShape, 18f, 40f, c.brand.copy(alpha = 0.6f), spread = -16f)
            .ring(CircleShape, c.borderSubtle, 5.dp)
            .ring(CircleShape, c.bgRaised, 4.dp),
    ) { content() }
}

@Composable
internal fun StartTitle(text: String) = Txt(
    text,
    style = Nx.type.title.copy(fontSize = Nx.type.sp(28f), lineHeight = Nx.type.sp(30.8f), fontWeight = FontWeight(700)),
    color = Nx.colors.textStrong,
    maxLines = 2,
)

@Composable
internal fun StartText(text: String) = StartText(androidx.compose.ui.text.AnnotatedString(text))

@Composable
internal fun StartText(text: androidx.compose.ui.text.AnnotatedString) = androidx.compose.foundation.text.BasicText(
    text,
    modifier = Modifier.widthIn(max = 560.dp).clipToBounds(),
    style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(23.25f), color = Nx.colors.textMuted),
)

/** Keep a conversation name to one inline slot without clipping the explanatory prose. */
@Composable
private fun StartWithName(key: String, arg: String, name: String, after: String = "") {
    val style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(23.25f))
    PersonText(null, t(key, arg to PERSON_SLOT) + after, name, style, Nx.colors.textMuted,
        Modifier.widthIn(max = 560.dp), nameTextStyle = style.copy(fontWeight = FontWeight(700)))
}

/** A sentence with one of its values in bold, as the web's `<Tr values={{ name: <b>…</b> }}>` draws it. */
internal fun boldIn(key: String, arg: String, value: String, after: String = ""): androidx.compose.ui.text.AnnotatedString {
    val mark = "\u0000"
    val parts = t(key, arg to mark).split(mark)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(parts.first())
        if (parts.size > 1) {
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight(700)))
            append(value)
            pop()
            append(parts.drop(1).joinToString(value))
        }
        append(after)
    }
}

/** A stretch of messages from people you blocked: folded to a count, opened on request. */
@Composable
private fun BlockedRun(row: ListRow.Blocked, ctx: ListCtx, toggle: () -> Unit) {
    val c = Nx.colors
    Column(Modifier.fillMaxWidth().padding(top = 17.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(start = 64.dp, end = 16.dp, top = 3.dp, bottom = 3.dp)) {
            Icon("Ban", c.textMuted.copy(alpha = 0.8f), size = 16.dp, modifier = Modifier.padding(start = 0.dp).graphicsLayer { translationX = -40f * density })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(t("people.blockedRun.count", "count" to row.ids.size), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20f)), color = c.textMuted)
                Txt(
                    if (row.shown) t("people.blockedRun.hide") else t("people.blockedRun.show"),
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.link,
                    modifier = Modifier.pressable(remember { MutableInteractionSource() }, onClick = toggle).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (row.shown) for (item in row.items) MessageRow(item, ctx, null, picked = false, canPick = false)
    }
}

/** A call's line (SystemMessage.tsx system_call): told from where the reader stands, with Call Back or Join. */
@Composable
fun CallRecordRow(cm: ChatMessage, ctx: ListCtx) {
    fish.nexa.ui.voice.CallLine(cm, Modifier.padding(start = if (ctx.selecting) 34.dp else 0.dp))
}
