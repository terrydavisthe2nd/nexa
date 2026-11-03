package fish.nexa.ui.nexa

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.MemberCase
import fish.nexa.data.NexaInbox
import fish.nexa.net.ApiError
import fish.nexa.notify.Notifier
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.markdown.Markdown
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.profile.NexaAvatar
import fish.nexa.ui.profile.OfficialBadge
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * lib/notify.ts onNexa and onBugAlert: what Nexa says gets the account's
 * attention. Its alerts ignore conversation mutes and the preview setting,
 * since their words hold nothing private.
 */
object NexaAlerts {
    fun install() {
        NexaInbox.onAlert = { a -> alert(a) }
    }

    private fun viewing(path: String) = Nav.route.toPath() == path

    private fun alert(a: NexaInbox.Alert) {
        when (a) {
            is NexaInbox.Alert.Notice -> Notifier.simple("ec-nexa-${a.case.id}", "nexa", "mention", "Nexa", noticeTitle(a.case.action, a.case.details), "/channels/@me/nexa", viewing("/channels/@me/nexa"))
            is NexaInbox.Alert.Reply -> {
                val path = "/channels/@me/nexa/cases/${a.case.id}"
                Notifier.simple("ec-nexa-${a.case.id}", "nexa", "message", "Nexa", t("nexa.notify.reply", "number" to a.case.number), path, viewing(path))
            }
            is NexaInbox.Alert.News -> {
                val body = a.announcement.title.ifEmpty { Markdown.plainText(Markdown.parse(a.announcement.body), fish.nexa.ui.chat.AppNames).take(140) }
                Notifier.simple("ec-nexa-a-${a.announcement.id}", "nexa", "message", "Nexa", body, "/channels/@me/nexa", viewing("/channels/@me/nexa"))
            }
            is NexaInbox.Alert.BugReply -> {
                val path = "/channels/@me/nexa/bugs/${a.report.id}"
                Notifier.simple("ec-bug-${a.report.id}", "nexa", "message", "Nexa", t("bugs.feed.replied", "number" to t("bugs.number", "number" to a.report.number)), path, viewing(path))
            }
            is NexaInbox.Alert.ReportReviewed -> Notifier.simple("ec-report-${a.report.id}", "nexa", "message", "Nexa", t("reports.outcome.notify"), "/channels/@me/nexa", viewing("/channels/@me/nexa"))
            is NexaInbox.Alert.BugStatus -> {
                val path = "/channels/@me/nexa/bugs/${a.report.id}"
                Notifier.simple("ec-bug-${a.report.id}", "nexa", "message", "Nexa", t("bugs.feed.statusChanged", "number" to t("bugs.number", "number" to a.report.number), "status" to bugStatusLabel(a.status)), path, viewing(path))
            }
        }
    }
}

/**
 * NexaNotices: every new case pops up on every open device until it is
 * dismissed, oldest first. It stays out of the way of that case's own page.
 */
@Composable
fun NoticePopupHost() {
    val standing by NexaInbox.standing.collectAsState()
    val queue = standing?.pending?.filter { it.action != "delete" } ?: emptyList()
    val head = queue.firstOrNull() ?: return
    if (NexaInbox.ban() != null) return
    // the Terms come first, as TermsGate sits above NexaNotices on the web
    if (Layers.stack.any { it.key == "terms" }) return
    val route = Nav.route
    if (route is Route.NexaCase && route.caseId == head.id) return
    NoticeSheet(head, queue.size)
}

@Composable
private fun NoticeSheet(c: MemberCase, count: Int) {
    val col = Nx.colors
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    val enter = remember(c.id) { Animatable(if (reduced) 1f else 0f) }
    val density = LocalDensity.current.density
    LaunchedEffect(c.id) { if (!reduced) enter.animateTo(1f, tween(280, easing = ease)) }
    // The ack takes the notice out of the queue at once, which takes this
    // sheet (and its scope) away, so it runs on the app's scope; the way
    // into the thread does not wait for it, as on the web.
    fun ack(then: () -> Unit = {}) {
        then()
        Nexa.scope.launch {
            try {
                NexaInbox.ack(c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message?.takeIf { it.isNotBlank() } ?: t("nexa.thread.couldNotSend"), "error")
            }
        }
    }
    // Back dismisses it, as Escape does
    BackHandler { ack() }
    Box(Modifier.fillMaxSize()) {
        // the scrim takes taps without closing: a notice is not dismissed by accident
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }.background(col.bgOverlay).pressable(remember { MutableInteractionSource() }) {})
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            val shape = RoundedCornerShape(topStart = Radii.lg, topEnd = Radii.lg)
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.92f)
                    .graphicsLayer { translationY = (1f - enter.value) * 40f * density }
                    .shadowHigh(shape, col)
                    .ring(shape, col.panelEdge)
                    .background(col.bgSurface, shape)
                    .topEdge(shape, col.edgeLight)
                    .semantics { paneTitle = t("nexa.modal.label") },
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NexaAvatar(40.dp)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Txt("Nexa", style = Nx.type.body.copy(fontWeight = FontWeight(700)), color = col.textStrong)
                                OfficialBadge(compact = false)
                            }
                            Txt(t("nexa.modal.sub"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = col.textMuted)
                        }
                        if (count > 1) ToneChip(t("nexa.modal.count", "count" to count), "")
                    }
                    NoticeCard(c)
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    NxButton(t("nexa.modal.gotIt"), { ack() }, kind = ButtonKind.SECONDARY)
                    NxButton(t("nexa.notice.openThread"), { ack { Nav.go(Route.NexaCase(c.id)) } })
                }
            }
        }
    }
}

/**
 * BannedPage: while the account is banned, this stands in place of the whole
 * app. The notice, and the case's thread to appeal in; it looks again every
 * half minute and comes away by itself when the ban ends.
 */
@Composable
fun BanScreen() {
    val c = Nx.colors
    val standing by NexaInbox.standing.collectAsState()
    val threads by NexaInbox.threads.collectAsState()
    val ban = standing?.ban ?: return
    var tick by remember { mutableStateOf(0) }
    // the standing again every 30 s, the thread every 15 s
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { (Nexa.api.get("/nexa") as? kotlinx.serialization.json.JsonObject)?.get("state")?.let { NexaInbox.setState(it) } }
            delay(30_000)
        }
    }
    LaunchedEffect(ban.caseId) {
        while (true) {
            runCatching { NexaInbox.loadThread(ban.caseId) }
            // the page counts as reading the notice
            if (NexaInbox.standing.value?.pending?.any { it.id == ban.caseId } == true) runCatching { NexaInbox.ack(ban.caseId) }
            delay(15_000)
        }
    }
    // when a timed ban runs out, the app comes back by itself
    LaunchedEffect(ban.expiresAt) {
        val until = ban.expiresAt ?: return@LaunchedEffect
        delay((until - System.currentTimeMillis() + 250).coerceAtLeast(0))
        tick++
    }
    if (tick >= 0 && !ban.inForce()) return
    val thread = threads[ban.caseId]
    val danger = c.danger.copy(alpha = 0.16f)
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgBase)
            .background(Brush.radialGradient(listOf(danger, danger.copy(alpha = 0f)), center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY / 2, 0f), radius = 900f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            fish.nexa.ui.components.NexaWordmark(28.dp)
            Box(Modifier.weight(1f))
            NxButton(t("settings.settings.logOut"), { Nexa.scope.launch { Nexa.auth.logout() } }, kind = ButtonKind.GHOST, icon = "LogOut")
        }
        Txt(t("nexa.ban.title"), style = Nx.type.title.copy(fontSize = Nx.type.sp(22f)), color = c.textStrong)
        Txt(if (ban.expiresAt == null) t("nexa.ban.leadPermanent") else t("nexa.ban.lead"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(22.5f)), color = c.textSubtle)
        ban.expiresAt?.let { until ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon("Clock", c.textMuted, size = 16.dp)
                Txt(t("nexa.ban.endsAt", "when" to Time.full(until), "rel" to Time.relativeTo(until)), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (thread == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) }
            else Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon("MessagesSquare", c.textStrong, size = 18.dp)
                    Txt(t("nexa.ban.appealTitle"), style = Nx.type.body.copy(fontWeight = FontWeight(700)), color = c.textStrong)
                }
                Box(Modifier.weight(1f)) { BanThread(thread) }
            }
        }
        Txt(t("nexa.ban.fine"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
    }
}

/**
 * DeletedNotice: shown over sign-in after a moderator deleted the account,
 * so the member can read why on the way out.
 */
@Composable
fun DeletedNoticeHost() {
    val deleted by NexaInbox.deleted.collectAsState()
    val (case, _) = deleted ?: return
    val c = Nx.colors
    BackHandler { NexaInbox.deleted.value = null }
    Box(Modifier.fillMaxSize().background(c.bgOverlay).pressable(remember { MutableInteractionSource() }) {}, contentAlignment = Alignment.BottomCenter) {
        val shape = RoundedCornerShape(topStart = Radii.lg, topEnd = Radii.lg)
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .shadowHigh(shape, c)
                .background(c.bgSurface, shape)
                .topEdge(shape, c.edgeLight)
                .navigationBarsPadding()
                .padding(18.dp)
                .semantics { contentDescription = t("nexa.notice.titleDelete") },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NoticeCard(case)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                NxButton(t("nexa.modal.gotIt"), { NexaInbox.deleted.value = null })
            }
        }
    }
}
