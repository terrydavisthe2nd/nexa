package fish.nexa.ui.moderation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.key
import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideArea
import fish.nexa.ui.components.glideItem
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
    val rank = fish.nexa.data.StaffRanks.of(me?.id, me?.flags ?: 0L)
    if (me != null && rank == null) {
        LaunchedEffect(Unit) { Nav.go(Route.Friends, replace = true) }
        return
    }
    LaunchedEffect(Unit) { runCatching { Moderation.summary() } }
    // the places are the sidebar's (StaffSidebar.kt); one this rank lacks goes to the reports
    val places = placesFor(rank)
    val here = placeOf(route.tab)
    val place = places.firstOrNull { it.id == here } ?: places.firstOrNull { it.id == "reports" }
    if (place == null) return
    if (place.id != here) {
        LaunchedEffect(here) { goPanel("reports", replace = true) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(sheen(c))
                .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .padding(start = 18.dp, end = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NavButton()
                Icon(place.icon, c.textBrand, size = 20.dp)
                Txt(t(place.labelKey), Modifier.weight(1f), style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(680)), color = c.textStrong, maxLines = 1)
                ReportNavigation.origin?.let { origin -> NxButton(t("reports.returnToReport"), { panelBack(origin) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val parts = route.parts
            when (here) {
                "users" -> if (parts.size >= 2) PersonPage(parts[1], caseId = parts.getOrNull(3)) else UsersTab()
                "bots" -> BotsTab()
                "calls" -> CallsTab()
                "servers" -> if (route.tab == "servers" && parts.size >= 2) StaffServerPage(parts[1]) else ServersTab(startListing = if (route.tab == "discovery") "all" else "")
                "expressions" -> when {
                    parts.getOrNull(1) == "packs" && parts.size >= 3 -> PackPage(parts[2])
                    else -> ExpressionsTab(uploads = parts.getOrNull(1) == "uploads")
                }
                "cases" -> CasesTab(parts.getOrNull(1))
                "reports" -> ReportsTab(parts.getOrNull(1))
                "bugs" -> BugsTab(parts.getOrNull(1))
                "logs" -> LogsTab()
                "announcements" -> AnnouncementsTab()
                "badges" -> BadgesTab()
            }
        }
    }
}

/** The panel's scrolling body padding on a phone (`.scroll`). */
val PanelPad = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 24.dp)

/** A tint for a pressed row in the panel's lists. */
@Composable
fun pressTint(pressed: Boolean): Color = if (pressed) Nx.colors.bgHover else Color.Transparent
