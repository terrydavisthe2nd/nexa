package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Moderation
import fish.nexa.ui.Icon
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.claimsSideways
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.NavButton
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch

/** Somewhere in the panel, by the web's own path below @admin. */
object ReportNavigation {
 private var owner:String?=null
 private var stored by mutableStateOf<String?>(null)
 var origin:String?
  get()=stored.takeIf {owner==Nexa.users.meId}
  set(value){owner=Nexa.users.meId;stored=value}
}
fun openReportTarget(path:String,reportId:String) {ReportNavigation.origin="reports/$reportId";goPanel(path)}
fun panelBack(fallback:String) {val origin=ReportNavigation.origin;ReportNavigation.origin=null;Nav.returnTo(Route.Admin(origin ?: fallback))}
fun goPanel(path: String, replace: Boolean = false) {if(path.startsWith("reports")) ReportNavigation.origin=null;Nav.go(Route.Admin(path), replace = replace)}

/**
 * The menu of the panel's place in the drawer: Mark as Read, which reads
 * every case thread and bug report waiting on the staff, as any
 * conversation is read from its menu (modMenu.ts).
 */
fun openModerationMenu() {
    val unread = Moderation.unread.value.count > 0
    openMenu(
        listOf(MenuItem.Action(t("sidebar.dmMenu.markAsRead"), disabled = !unread) {
            Nexa.scope.launch {
                try {
                    Moderation.readAll()
                } catch (e: Exception) {
                    Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
                }
            }
        }),
        title = t("moderation.title"),
    )
}

private data class PanelTab(val id: String, val label: String, val icon: String, val badge: Int = 0, val badgeLabel: String? = null)

/**
 * The Admin & Moderation panel (AdminPanelPage.tsx): every account at a
 * glance, the cases waiting on the staff, the record of everything done, and
 * announcements to everyone. Every rank of staff gets here, a mod to the
 * people, cases and log alone (data/StaffRanks); the server refuses the rest.
 */
@Composable
fun AdminPanel(route: Route.Admin) {
 DisposableEffect(Unit){onDispose {ReportNavigation.origin=null}}
    val c = Nx.colors
    val me by Nexa.users.me.collectAsState()
    val unread by Moderation.unread.collectAsState()
    val rank = fish.nexa.data.StaffRanks.of(me?.id, me?.flags ?: 0L)
    if (me != null && rank == null) {
        LaunchedEffect(Unit) { Nav.go(Route.Friends, replace = true) }
        return
    }
    LaunchedEffect(Unit) { runCatching { Moderation.summary() } }
    val all = listOf(
        PanelTab("reports", t("reports.title"), "Flag"),
        PanelTab("users", t("moderation.tabs.users"), "LayoutGrid"),
        PanelTab("bots", t("moderation.tabs.bots"), "Bot"),
        PanelTab("servers", t("moderation.tabs.servers"), "Server"),
        PanelTab("discovery", t("moderation.tabs.discovery"), "Compass"),
        PanelTab("expressions", t("moderation.tabs.expressions"), "Sticker"),
        // what calls use, and who uses it, against the month's allowance
        PanelTab("calls", t("moderation.tabs.calls"), "Phone"),
        // what waits on the staff and is new to this person; reading it, or Mark as Read, takes it off
        PanelTab("cases", t("moderation.tabs.cases"), "FolderOpen", unread.cases.size, t("moderation.needsReplyCount", "count" to unread.cases.size)),
        PanelTab("bugs", t("moderation.tabs.bugs"), "Bug", unread.bugs.size, t("bugs.staff.waitingCount", "count" to unread.bugs.size)),
        PanelTab("logs", t("moderation.tabs.logs"), "ScrollText"),
        PanelTab("announcements", t("moderation.tabs.announcements"), "Megaphone"),
    )
    // a mod has the people, cases and log; admins and above every tab
    val tabs = if (rank != null && rank.atLeast(fish.nexa.data.StaffRank.ADMIN)) all else all.filter { it.id in listOf("reports", "users", "cases", "logs") }
    val tab = route.tab.takeIf { t -> tabs.any { it.id == t } } ?: "users"
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(sheen(c))
                .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .padding(start = 18.dp, end = 14.dp, bottom = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NavButton()
                Icon("Shield", c.textBrand, size = 20.dp)
                Txt(t("moderation.title"), style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(680)), color = c.textStrong, maxLines = 1)
            }
            ReportNavigation.origin?.let { origin -> NxButton(t("reports.returnToReport"),{panelBack(origin)},kind=ButtonKind.SECONDARY,size=ButtonSize.SM) }
            TabStripRow(tabs, tab)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val parts = route.parts
            when (tab) {
                "users" -> if (parts.size >= 2) PersonPage(parts[1], cases = parts.getOrNull(2) == "cases", caseId = parts.getOrNull(3)) else UsersTab()
                "bots" -> BotsTab()
                "calls" -> CallsTab()
                "discovery" -> ServersTab(discovery = true)
                "servers" -> if (parts.size >= 2) StaffServerPage(parts[1]) else ServersTab()
                "expressions" -> when {
                    parts.getOrNull(1) == "packs" && parts.size >= 3 -> PackPage(parts[2])
                    else -> ExpressionsTab(uploads = parts.getOrNull(1) == "uploads")
                }
                "cases" -> CasesTab(parts.getOrNull(1))
                "reports" -> ReportsTab(parts.getOrNull(1))
                "bugs" -> BugsTab(parts.getOrNull(1))
                "logs" -> LogsTab()
                "announcements" -> AnnouncementsTab()
            }
        }
    }
}

/** `.tabs`: one segmented control, a well with the open tab lifted out of it, sliding sideways on a phone. */
@Composable
private fun TabStripRow(tabs: List<PanelTab>, selected: String) {
    val c = Nx.colors
    val state = rememberLazyListState()
    val at = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    // the open tab is always in view
    LaunchedEffect(at) {
        val seen = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == at }
        val end = state.layoutInfo.viewportEndOffset
        if (seen == null || seen.offset < 0 || seen.offset + seen.size > end) state.animateScrollToItem(at)
    }
    val well = RoundedCornerShape(12.dp)
    LazyRow(
        Modifier
            .fillMaxWidth()
            .sunken(well, c)
            .ring(well, c.borderSubtle)
            .claimsSideways { state.canScrollForward || state.canScrollBackward }
            .semantics { contentDescription = t("moderation.title") },
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(tabs, key = { _, it -> it.id }) { _, tb ->
            val on = tb.id == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val shape = RoundedCornerShape(9.dp)
            Row(
                Modifier
                    .height(40.dp)
                    .then(if (on) Modifier.shadowLow(shape, c).ring(shape, c.borderSubtle).background(c.bgSurface, shape).background(sheen(c), shape).topEdge(shape, c.edgeLight) else Modifier)
                    .clip(shape)
                    .pressable(interaction, role = Role.Tab) { if (!on) {ReportNavigation.origin=null;goPanel(tb.id, replace = true)} }
                    .semantics {
                        this.selected = on
                        if (tb.badge > 0 && tb.badgeLabel != null) contentDescription = "${tb.label}, ${tb.badgeLabel}"
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(tb.icon, if (on) c.textBrand else if (pressed) c.textStrong else c.textMuted, size = 17.dp)
                Txt(tb.label, style = Nx.type.button.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(600)), color = if (on || pressed) c.textStrong else c.textMuted, maxLines = 1)
                if (tb.badge > 0) TabBadge(tb.badge, tb.badgeLabel ?: "")
            }
        }
    }
}

/** The panel's scrolling body padding on a phone (`.scroll`). */
val PanelPad = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 24.dp)

/** A tint for a pressed row in the panel's lists. */
@Composable
fun pressTint(pressed: Boolean): Color = if (pressed) Nx.colors.bgHover else Color.Transparent
