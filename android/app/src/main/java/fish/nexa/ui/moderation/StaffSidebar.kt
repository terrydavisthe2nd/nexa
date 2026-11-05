package fish.nexa.ui.moderation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Moderation
import fish.nexa.data.Reports
import fish.nexa.data.StaffRank
import fish.nexa.data.StaffRanks
import fish.nexa.ui.Icon
import fish.nexa.ui.components.CountBadge
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.selectedRow
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx

/** One place in the panel, by its path below @admin, and the least rank that has it. */
data class StaffPlace(val id: String, val labelKey: String, val icon: String, val rank: StaffRank)

/**
 * Every place in the panel in the groups the sidebar shows them in, kept equal
 * to the web's STAFF_GROUPS (components/moderation/StaffSidebar.tsx).
 */
val STAFF_GROUPS: List<Pair<String, List<StaffPlace>>> = listOf(
    "moderation.nav.attention" to listOf(
        StaffPlace("reports", "reports.title", "Flag", StaffRank.MOD),
        StaffPlace("cases", "moderation.tabs.cases", "FolderOpen", StaffRank.MOD),
        StaffPlace("bugs", "moderation.tabs.bugs", "Bug", StaffRank.ADMIN),
    ),
    "moderation.nav.lookUp" to listOf(
        StaffPlace("users", "moderation.tabs.users", "Users", StaffRank.MOD),
        StaffPlace("servers", "moderation.tabs.servers", "Server", StaffRank.ADMIN),
        StaffPlace("expressions", "moderation.tabs.expressions", "Sticker", StaffRank.ADMIN),
        StaffPlace("bots", "moderation.tabs.bots", "Bot", StaffRank.ADMIN),
    ),
    "moderation.nav.records" to listOf(
        StaffPlace("logs", "moderation.tabs.logs", "ScrollText", StaffRank.MOD),
    ),
    "moderation.nav.running" to listOf(
        StaffPlace("announcements", "moderation.tabs.announcements", "Megaphone", StaffRank.ADMIN),
        StaffPlace("badges", "moderation.tabs.badges", "Award", StaffRank.ADMIN),
        StaffPlace("calls", "moderation.tabs.calls", "Phone", StaffRank.ADMIN),
    ),
)

/** The places this rank has, in order. */
fun placesFor(rank: StaffRank?): List<StaffPlace> = STAFF_GROUPS.flatMap { it.second }.filter { rank != null && rank.atLeast(it.rank) }

/** Which place a panel path is in (Discovery lives in Servers). */
fun placeOf(tab: String): String = if (tab == "discovery") "servers" else tab

/**
 * What every badge for the panel counts (stores/moderation.ts attentionCount):
 * the threads waiting unread, plus reports nobody has picked up yet.
 */
@Composable
fun attentionCount(): Int {
    val unread by Moderation.unread.collectAsState()
    val counts by Reports.counts.collectAsState()
    return unread.count + (counts?.get("open") ?: 0)
}

/**
 * The panel's own sidebar, in place of the conversations while it is open, as
 * a server's channels take their place (StaffSidebar.tsx): every part of it by
 * name, with what waits on the staff counted where it waits.
 */
@Composable
fun StaffSidebar(route: Route.Admin) {
    val c = Nx.colors
    val me by Nexa.users.me.collectAsState()
    val rank = StaffRanks.of(me?.id, me?.flags ?: 0L)
    val here = placeOf(route.tab)
    val unread by Moderation.unread.collectAsState()
    val counts by Reports.counts.collectAsState()
    val waitingReports = (counts?.get("open") ?: 0) + (counts?.get("in_review") ?: 0)
    fun badge(id: String): Pair<Int, String>? = when (id) {
        "reports" -> if (waitingReports > 0) waitingReports to t("reports.waitingCount", "count" to waitingReports) else null
        "cases" -> if (unread.cases.isNotEmpty()) unread.cases.size to t("moderation.needsReplyCount", "count" to unread.cases.size) else null
        "bugs" -> if (unread.bugs.isNotEmpty()) unread.bugs.size to t("bugs.staff.waitingCount", "count" to unread.bugs.size) else null
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon("Shield", c.textBrand, size = 18.dp)
            Txt(t("moderation.navLabel"), Modifier.weight(1f), style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 1)
            IconButton("BellRing", t("moderation.alerts.open"), { fish.nexa.ui.settings.Settings.open("notifications", "staffAlerts") }, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
        }
        LazyColumn(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
            for ((group, all) in STAFF_GROUPS) {
                val places = all.filter { rank != null && rank.atLeast(it.rank) }
                if (places.isEmpty()) continue
                item("head-$group") {
                    Txt(t(group), Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp, top = 18.dp).semantics { heading() }, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
                }
                for (p in places) item(p.id) {
                    val b = badge(p.id)
                    PlaceRow(p, here == p.id, b?.first ?: 0, b?.second) {
                        goPanel(p.id, replace = route.tab != "reports" || here != p.id)
                        Nav.leftOpen = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: StaffPlace, selected: Boolean, badge: Int, badgeLabel: String?, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = t(p.labelKey)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .height(42.dp)
            .selectedRow(selected, pressed)
            .pressable(interaction, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = if (badge > 0 && badgeLabel != null) "$label, $badgeLabel" else label
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Icon(p.icon, if (selected) c.textBrand else c.textChannel, size = 20.dp) }
        Txt(label, Modifier.weight(1f), style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(550)), color = if (selected) c.textStrong else c.textChannel, maxLines = 1)
        CountBadge(badge)
    }
}
