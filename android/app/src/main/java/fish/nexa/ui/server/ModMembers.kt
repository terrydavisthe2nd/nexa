package fish.nexa.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.AutomodEvent
import fish.nexa.data.P
import fish.nexa.net.AuditEntry
import fish.nexa.net.PublicUser
import fish.nexa.net.Snowflake
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.chat.outranks
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxCheckbox
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.dialogs.FooterButtons
import fish.nexa.ui.dialogs.DialogHint
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.people.MemberActions
import fish.nexa.ui.shell.Names
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sunken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How someone came in (MemberJoin). */
@Serializable
data class MemberJoin(
    @SerialName("join_method") val joinMethod: String = "",
    @SerialName("join_code") val joinCode: String = "",
    @SerialName("invited_by") val invitedBy: Snowflake? = null,
)

/** A member as the Members page lists them, and with Mod View's facts (MemberRow, MemberDetail). */
@Serializable
data class MemberRow(
    @SerialName("user_id") val userId: Snowflake,
    val nickname: String = "",
    @SerialName("avatar_id") val avatarId: Snowflake? = null,
    @SerialName("joined_at") val joinedAt: Long = 0,
    @SerialName("timeout_until") val timeoutUntil: Long? = null,
    val flags: Long = 0,
    @SerialName("role_ids") val roleIds: List<Snowflake> = emptyList(),
    @SerialName("account_created_at") val accountCreatedAt: Long = 0,
    val signals: List<String> = emptyList(),
    val join: MemberJoin? = null,
    @SerialName("message_count") val messageCount: Long = 0,
    @SerialName("last_seen") val lastSeen: Long? = null,
)

@Serializable
private data class MemberSearch(
    val members: List<MemberRow> = emptyList(),
    val total: Int = 0,
    val users: List<PublicUser> = emptyList(),
    @SerialName("join_codes") val joinCodes: List<String>? = null,
)

@Serializable
private data class ModViewReply(
    val member: MemberRow,
    val audit: List<AuditEntry>? = null,
    val automod: List<AutomodEvent>? = null,
    val users: List<PublicUser> = emptyList(),
)

@Serializable
private data class PruneCount(val count: Int = 0, val pruned: Int = 0)

@Serializable
private data class BulkBanReply(val banned: List<Snowflake> = emptyList(), val failed: List<Snowflake> = emptyList())

private const val MOD_PAGE = 50
private const val DAY = 86_400_000L
private val SIGNALS = listOf("timed_out", "quarantined", "new_account", "unusual_dms")

/** How far back, as the Joined and Account Age filters offer it. */
private val SPANS = listOf(1, 7, 30, 90)
private val SORTS = listOf("joined_desc", "joined_asc", "created_desc", "created_asc")

private fun spanLabel(days: Int) = when (days) {
    1 -> t("members.filter.day")
    7 -> t("members.filter.week")
    30 -> t("members.filter.month")
    else -> t("members.filter.threeMonths")
}

private fun sortLabel(sort: String) = when (sort) {
    "joined_asc" -> t("members.sort.joinedOld")
    "created_desc" -> t("members.sort.createdNew")
    "created_asc" -> t("members.sort.createdOld")
    else -> t("members.sort.joinedNew")
}

/** How someone came in, in words. */
fun joinMethodLabel(method: String?): String = when (method) {
    "invite" -> t("members.method.invite")
    "discovery" -> t("members.method.discovery")
    "tag" -> t("members.method.tag")
    "vanity" -> t("members.method.vanity")
    "owner" -> t("members.method.owner")
    else -> t("members.method.unknown")
}

private fun signalIcon(kind: String) = when (kind) {
    "timed_out" -> "Clock"
    "quarantined" -> "ShieldAlert"
    "new_account" -> "CalendarClock"
    else -> "MailWarning"
}

/** A signal's tint: what holds someone back in the danger colour, what only wants a look in the warning one. */
@Composable
private fun signalTint(kind: String): Pair<Color, Color> {
    val c = Nx.colors
    return if (kind == "quarantined" || kind == "timed_out") c.danger.copy(alpha = 0.16f) to c.textDanger else c.warning.copy(alpha = 0.16f) to c.textWarning
}

@Composable
private fun SignalBadge(kind: String, size: Dp = 24.dp) {
    val (bg, fg) = signalTint(kind)
    Box(
        Modifier.size(size).background(bg, RoundedCornerShape(7.dp)).semantics { contentDescription = t("members.signal.$kind") },
        contentAlignment = Alignment.Center,
    ) { Icon(signalIcon(kind), fg, size = 14.dp) }
}

/**
 * The Members page as moderators see it (ModMembers.tsx): everyone in the
 * server, searched and filtered on the server (by role, when they came, how
 * old their account is, how they joined, and what might want a look), with
 * what can be done to several at once, and Mod View for one. A phone has no
 * room for columns, so each member is a card with the same facts stacked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModMembers(serverId: String) {
    val c = Nx.colors
    val servers by Nexa.servers.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val st = servers[serverId] ?: return
    val s = Nexa.servers
    val meId = Nexa.users.meId
    val scope = rememberCoroutineScope()
    val seesJoins = s.canI(st, P.MANAGE_SERVER)
    val canKick = s.canI(st, P.KICK_MEMBERS)
    val canBan = s.canI(st, P.BAN_MEMBERS)
    var q by remember { mutableStateOf("") }
    var roles by remember { mutableStateOf(listOf<String>()) }
    var joined by remember { mutableIntStateOf(0) }
    var created by remember { mutableIntStateOf(0) }
    var signals by remember { mutableStateOf(listOf<String>()) }
    var method by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("joined_desc") }
    var rows by remember { mutableStateOf<List<MemberRow>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var codes by remember { mutableStateOf(listOf<String>()) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val picked = remember { mutableStateListOf<String>() }

    // the filters as the server reads them; a new set starts the list again
    val query = remember(q, roles, joined, created, signals, method, code, sort) {
        val now = System.currentTimeMillis()
        buildString {
            append("sort=$sort")
            if (q.isNotBlank()) append("&q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"))
            if (roles.isNotEmpty()) append("&roles=" + roles.joinToString(","))
            if (joined > 0) append("&joined_after=${now - joined * DAY}")
            if (created > 0) append("&created_after=${now - created * DAY}")
            if (signals.isNotEmpty()) append("&signals=" + signals.joinToString(","))
            if (method.isNotEmpty()) append("&join_method=$method")
            if (code.isNotEmpty()) append("&join_code=" + java.net.URLEncoder.encode(code, "UTF-8"))
        }
    }
    suspend fun fetch(offset: Int): MemberSearch {
        val res = Nexa.api.get("/servers/$serverId/member-search?$query&offset=$offset&limit=$MOD_PAGE", MemberSearch.serializer())
        Nexa.users.upsert(res.users)
        return res
    }
    LaunchedEffect(serverId, query, reload) {
        if (q.isNotBlank()) delay(250)
        loading = true
        failed = false
        try {
            val res = fetch(0)
            rows = res.members
            total = res.total
            res.joinCodes?.let { codes = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    fun more() {
        val at = rows?.size ?: return
        val asked = query
        loading = true
        scope.launch {
            try {
                val res = fetch(at)
                if (asked == query) {
                    rows = rows.orEmpty() + res.members.filter { m -> rows.orEmpty().none { it.userId == m.userId } }
                    total = res.total
                }
            } catch (_: Exception) {
                Nexa.ui.toast(t("members.loadFailed"), "error")
            } finally {
                loading = false
            }
        }
    }

    val filtering = q.isNotBlank() || roles.isNotEmpty() || joined > 0 || created > 0 || signals.isNotEmpty() || method.isNotEmpty() || code.isNotEmpty()
    fun clearFilters() {
        q = ""; roles = emptyList(); joined = 0; created = 0; signals = emptyList(); method = ""; code = ""
    }
    fun pickable(id: String) = id != meId && id != st.server.ownerId && outranks(serverId, id)
    fun toggle(id: String) {
        if (id in picked) picked.remove(id) else picked.add(id)
    }
    val allRoles = s.rolesByPosition(st).filter { it.id != serverId }
    fun nameOf(m: MemberRow) = m.nickname.ifEmpty { null } ?: Names.display(users[m.userId])

    fun bulkKick() {
        val ids = picked.toList()
        confirm(t("members.bulk.kickTitle", "count" to ids.size), t("members.bulk.kickBody"), t("members.bulk.kick", "count" to ids.size)) {
            var ok = 0
            for (id in ids) {
                try {
                    MemberActions.kick(serverId, id, null)
                    ok++
                } catch (_: Exception) {
                    // the rest still go
                }
            }
            Nexa.ui.toast(t("members.bulk.kicked", "count" to ok), if (ok == ids.size) "success" else "error")
            picked.clear()
            reload++
        }
    }
    fun bulkBan() {
        val ids = picked.toList()
        confirm(t("members.bulk.banTitle", "count" to ids.size), t("members.bulk.banBody"), t("members.bulk.ban", "count" to ids.size)) {
            val res = Nexa.api.request("POST", "/servers/$serverId/bulk-ban", BulkBanReply.serializer(), buildJsonObject {
                put("user_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                put("delete_message_days", 1)
            })
            for (id in res.banned) Nexa.servers.removeMember(serverId, id)
            Nexa.ui.toast(t("members.bulk.banned", "count" to res.banned.size), if (res.failed.isNotEmpty()) "error" else "success")
            picked.clear()
            reload++
        }
    }

    SettingsPage(bar = if (picked.isEmpty()) null else ({
        BulkBar(picked.size, canKick, canBan, onClear = { picked.clear() }, onKick = ::bulkKick, onBan = ::bulkBan)
    })) {
        PageTitle(t("server.members.membersCount", "count" to st.memberCount))
        SearchBox(q, { q = it }, t("members.search"), Modifier.padding(bottom = 10.dp))
        // the filters in one row that scrolls sideways: a phone has no room for them all at once
        Row(
            Modifier
                .padding(bottom = 12.dp)
                .edgeToEdge(16.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = t("members.filter.label") },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(sortLabel(sort), icon = "ArrowDownUp", on = false, menu = true) {
                openMenu(SORTS.map { k -> MenuItem.Action(sortLabel(k), checked = sort == k) { sort = k } }, title = t("members.sort.label"))
            }
            if (allRoles.isNotEmpty()) FilterChip(if (roles.isEmpty()) t("server.members.roles") else t("members.filter.rolesN", "count" to roles.size), on = roles.isNotEmpty(), menu = true) {
                openRolePicker(t("server.members.roles"), serverId, roles) { roles = it }
            }
            FilterChip(if (joined > 0) t("members.filter.joinedIn", "span" to spanLabel(joined)) else t("members.filter.joined"), on = joined > 0, menu = true) {
                openMenu(listOf(MenuItem.Action(t("members.filter.any"), checked = joined == 0) { joined = 0 }) + SPANS.map { d -> MenuItem.Action(spanLabel(d), checked = joined == d) { joined = d } }, title = t("members.filter.joined"))
            }
            FilterChip(if (created > 0) t("members.filter.ageIn", "span" to spanLabel(created)) else t("members.filter.age"), on = created > 0, menu = true) {
                openMenu(listOf(MenuItem.Action(t("members.filter.any"), checked = created == 0) { created = 0 }) + SPANS.map { d -> MenuItem.Action(spanLabel(d), checked = created == d) { created = d } }, title = t("members.filter.age"))
            }
            if (seesJoins) FilterChip(
                when {
                    code.isNotEmpty() -> t("members.filter.invite", "code" to code)
                    method.isNotEmpty() -> joinMethodLabel(method)
                    else -> t("members.col.joinMethod")
                },
                on = method.isNotEmpty() || code.isNotEmpty(),
                menu = true,
            ) {
                val items = buildList<MenuItem> {
                    add(MenuItem.Action(t("members.filter.any"), checked = method.isEmpty() && code.isEmpty()) { method = ""; code = "" })
                    for (m in listOf("invite", "vanity", "discovery", "tag")) add(MenuItem.Action(joinMethodLabel(m), checked = method == m && code.isEmpty()) { method = m; code = "" })
                    if (codes.isNotEmpty()) add(MenuItem.Heading(t("members.filter.byInvite")))
                    for (cd in codes.take(20)) add(MenuItem.Action(cd, checked = code == cd) { method = "invite"; code = cd })
                }
                openMenu(items, title = t("members.col.joinMethod"))
            }
            Box(Modifier.width(1.dp).height(20.dp).background(c.borderSubtle))
            for (sig in SIGNALS) FilterChip(t("members.signal.$sig"), icon = signalIcon(sig), on = sig in signals, toggle = true) {
                signals = if (sig in signals) signals - sig else signals + sig
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                if (rows == null) "" else if (filtering) t("members.found", "count" to total) else t("members.total", "count" to total),
                style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)),
                color = c.textMuted,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (filtering) NxButton(t("members.filter.clear"), ::clearFilters, kind = ButtonKind.LINK, inline = true)
            if (canKick) NxButton(t("members.prune.button"), { openPrune(serverId) { reload++ } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Scissors")
        }
        if (failed) LoadFailed(t("members.loadFailed")) { reload++ }
        val list = rows
        when {
            list == null && !failed -> Loading(t("chat.pins.loading"))
            list != null && list.isEmpty() -> EmptyNote(if (filtering) t("members.none") else t("members.empty"), icon = if (filtering) "SearchX" else "Users")
        }
        list?.forEach { m ->
            key(m.userId) {
                MemberCard(
                    serverId, m, nameOf(m), users[m.userId],
                    pickable = pickable(m.userId),
                    picked = m.userId in picked,
                    onPick = { toggle(m.userId) },
                    onOpen = { openModView(serverId, m.userId) { reload++ } },
                )
            }
        }
        if (list != null && list.size < total) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            NxButton(t("members.more", "count" to total - list.size), ::more, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = loading)
        }
        // room for the bar of what is picked, so it never covers the last member
        if (picked.isNotEmpty()) Spacer(Modifier.height(72.dp))
    }
}

/** Lets a row run edge to edge of the page, past its side padding, so what scrolls sideways is not cut at the margin. */
private fun Modifier.edgeToEdge(side: Dp): Modifier = layout { m, cs ->
    val extra = (side * 2).roundToPx()
    val w = cs.maxWidth + extra
    val p = m.measure(cs.copy(minWidth = w, maxWidth = w))
    layout(cs.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/** One of the filters: a pill that fills with the accent while it narrows the list. */
@Composable
private fun FilterChip(label: String, icon: String? = null, on: Boolean, menu: Boolean = false, toggle: Boolean = false, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .ring(shape, if (on) c.brand.copy(alpha = 0.7f) else c.borderSubtle)
            .clip(shape)
            .background(if (on) mixSrgb(c.brand, c.bgSurface, 0.78f) else if (pressed) c.bgHover else c.bgSurface, shape)
            .pressable(interaction, role = if (toggle) Role.Switch else Role.Button, onClick = onClick)
            .semantics { if (toggle) stateDescription = if (on) "on" else "off" else if (on) selected = true }
            .padding(start = if (icon != null) 10.dp else 12.dp, end = if (menu) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, if (on) c.textStrong else c.textMuted, size = 14.dp)
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.text, maxLines = 1)
        if (menu) Icon("ChevronDown", c.textMuted, size = 14.dp)
    }
}

/** A member's card: the tick that picks them, then who they are, when and how they came, what wants a look, and their roles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberCard(serverId: String, m: MemberRow, name: String, user: PublicUser?, pickable: Boolean, picked: Boolean, onPick: () -> Unit, onOpen: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(if (picked) Modifier.ring(shape, c.brand.copy(alpha = 0.7f), 2.dp).background(c.brand.copy(alpha = 0.08f), shape) else Modifier.surfaceCard(c, shape))
            .clip(shape)
            .background(if (pressed) c.bgHover else Color.Transparent)
            // a long press picks, as it does in the member list
            .pressable(interaction, onLongClick = if (pickable) onPick else null, onClick = onOpen)
            .semantics { contentDescription = t("members.mod.title", "name" to name) }
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (pickable) Tick(picked, t("members.pick", "name" to name), onPick)
        else Spacer(Modifier.width(44.dp))
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(m.userId, name, m.avatarId ?: user?.avatarId, 36.dp)
                Column(Modifier.weight(1f)) {
                    fish.nexa.ui.chat.PersonName(m.userId, name, serverId = serverId, style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
                    Txt("@" + (user?.username ?: ""), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
                }
                if (m.signals.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (sig in m.signals) SignalBadge(sig) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val fact = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f))
                Txt(t("members.card.member", "date" to Time.dateDivider(m.joinedAt)), style = fact, color = c.textMuted)
                Txt(t("members.card.account", "age" to formatSpan(System.currentTimeMillis() - m.accountCreatedAt)), style = fact, color = c.textMuted)
                m.join?.let { Txt(joinMethodLabel(it.joinMethod), style = fact, color = c.textMuted) }
            }
            ModRoleChips(serverId, m.roleIds, max = 3)
        }
    }
}

/** The 44 dp tick that picks a member for what can be done to several at once. */
@Composable
private fun Tick(checked: Boolean, label: String, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .size(44.dp)
            .pressable(remember { MutableInteractionSource() }, role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "checked" else "unchecked"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .then(if (checked) Modifier.background(brandGradient(c), shape) else Modifier.sunken(shape, c).ring(shape, c.border, 1.5.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon("Check", c.onBrand, size = 14.dp, stroke = 3f) }
    }
}

/** A member's roles, highest first, as small pills with their colour; past [max], how many more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModRoleChips(serverId: String, ids: List<String>, max: Int = Int.MAX_VALUE, emptyText: String? = null) {
    val c = Nx.colors
    val st = Nexa.servers.byId.collectAsState().value[serverId] ?: return
    val shown = ids.mapNotNull { st.roles[it] }.sortedByDescending { it.position }
    if (shown.isEmpty()) {
        if (emptyText != null) Txt(emptyText, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in shown.take(max)) Row(
            Modifier.height(24.dp).widthIn(max = 160.dp).background(c.bgActive, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val colours = fish.nexa.ui.chat.RoleLooks.colorsOf(r)
            Box(
                Modifier.size(8.dp).background(
                    if (colours.size > 1) androidx.compose.ui.graphics.Brush.linearGradient(colours) else androidx.compose.ui.graphics.SolidColor(colours.firstOrNull() ?: c.textMuted),
                    CircleShape,
                ),
            )
            Txt(r.name, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
        }
        if (shown.size > max) Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            Txt("+${shown.size - max}", style = Nx.type.hint, color = c.textMuted)
        }
    }
}

/** What is picked, and what can be done to all of it, floating over the bottom of the page. */
@Composable
private fun BulkBar(count: Int, canKick: Boolean, canBan: Boolean, onClear: () -> Unit, onKick: () -> Unit, onBan: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    Row(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.panelEdge)
            .background(c.bgFloating, shape)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics { contentDescription = t("members.bulk.label") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(t("members.bulk.picked", "count" to count), style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 1)
        NxButton(t("members.bulk.clear"), onClear, kind = ButtonKind.GHOST, size = ButtonSize.SM)
        if (canKick) NxButton(t("members.bulk.kick", "count" to count), onKick, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        if (canBan) NxButton(t("members.bulk.ban", "count" to count), onBan, kind = ButtonKind.DANGER, size = ButtonSize.SM)
    }
}

/** Roles to narrow the list to, ticked in a sheet that stays open while several are picked. */
private fun openRolePicker(title: String, serverId: String, current: List<String>, onChange: (List<String>) -> Unit) =
    fish.nexa.ui.dialogs.open("memberRoles") { layer, close ->
        val st = Nexa.servers.byId.collectAsState().value[serverId]
        var chosen by remember { mutableStateOf(current) }
        Sheet(layer, title = title, maxWidth = 460.dp, footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (chosen.isNotEmpty()) NxButton(t("members.filter.clear"), { chosen = emptyList(); onChange(emptyList()) }, kind = ButtonKind.GHOST)
                NxButton(t("pick.done"), close)
            }
        }) {
            for (r in Nexa.servers.rolesByPosition(st).filter { it.id != serverId }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxCheckbox(r.id in chosen, { on ->
                        chosen = if (on) chosen + r.id else chosen - r.id
                        onChange(chosen)
                    }, r.name, Modifier.weight(1f))
                    Box(Modifier.size(10.dp).background(if (r.color != 0L) Color(0xFF000000 or r.color) else Nx.colors.textMuted, CircleShape))
                }
            }
        }
    }

/** Taking out the people nobody has heard from in a while, who hold no role but the ones that keep them. */
private fun openPrune(serverId: String, onDone: () -> Unit) = fish.nexa.ui.dialogs.open("prune") { layer, close ->
    PruneSheet(layer, serverId, onDone, close)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PruneSheet(layer: Layer, serverId: String, onDone: () -> Unit, close: () -> Unit) {
    val c = Nx.colors
    val st = Nexa.servers.byId.collectAsState().value[serverId]
    var days by remember { mutableIntStateOf(30) }
    var keep by remember { mutableStateOf(listOf<String>()) }
    var count by remember { mutableStateOf<Int?>(null) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(days, keep) {
        count = null
        count = try {
            val q = "days=$days" + if (keep.isNotEmpty()) "&roles=" + keep.joinToString(",") else ""
            Nexa.api.get("/servers/$serverId/prune?$q", PruneCount.serializer()).count
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }
    fun prune() {
        busy = true
        scope.launch {
            try {
                val res = Nexa.api.request("POST", "/servers/$serverId/prune", PruneCount.serializer(), buildJsonObject {
                    put("days", days)
                    put("roles", JsonArray(keep.map { JsonPrimitive(it) }))
                    if (reason.isNotBlank()) put("reason", reason.trim())
                })
                Nexa.ui.toast(t("members.prune.done", "count" to res.pruned), "success")
                onDone()
                close()
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("members.mod.failed")), "error")
            } finally {
                busy = false
            }
        }
    }
    val roles = Nexa.servers.rolesByPosition(st).filter { it.id != serverId }
    Sheet(layer, title = t("members.prune.title"), maxWidth = 480.dp, footer = {
        FooterButtons(t("members.prune.confirm", "count" to (count ?: 0)), ::prune, close, enabled = (count ?: 0) > 0, busy = busy, danger = true)
    }) {
        DialogHint(t("members.prune.what"))
        Field(t("members.prune.days")) {
            SelectField(listOf(1, 7, 30, 90, 180, 365).map { it to formatSpan(it * DAY) }, days, { days = it }, title = t("members.prune.days"))
        }
        if (roles.isNotEmpty()) Field(t("members.prune.keep"), hint = t("members.prune.keepHint")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in roles) FilterChip(r.name, on = r.id in keep, toggle = true) { keep = if (r.id in keep) keep - r.id else keep + r.id }
            }
        }
        Field(t("server.memberAction.reasonOptional")) {
            NxTextField(reason, { reason = it.take(512) })
        }
        Txt(
            count?.let { t("members.prune.count", "count" to it) } ?: t("members.prune.counting"),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(550)),
            color = if ((count ?: 0) > 0) c.text else c.textMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Mod View (ModView.tsx): one member and what a moderator needs to know about
 * them in one place: who they are here, when and how they came, what might
 * want a look, what has been done about them, and what can be done now.
 */
fun openModView(serverId: String, userId: String, onChanged: () -> Unit = {}) = fish.nexa.ui.dialogs.open("modView") { layer, close ->
    ModViewSheet(layer, serverId, userId, onChanged)
}

@Composable
private fun ModViewSheet(layer: Layer, serverId: String, userId: String, onChanged: () -> Unit) {
    val c = Nx.colors
    val servers by Nexa.servers.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val st = servers[serverId]
    val user = users[userId]
    var detail by remember { mutableStateOf<MemberRow?>(null) }
    var audit by remember { mutableStateOf(listOf<AuditEntry>()) }
    var caught by remember { mutableStateOf(listOf<AutomodEvent>()) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // a change made elsewhere (a timeout from its sheet) shows here when it lands
    val live = st?.members?.get(userId)
    LaunchedEffect(serverId, userId, attempt, live?.timeoutUntil, live?.flags, live?.roleIds) {
        failed = false
        try {
            val res = Nexa.api.get("/servers/$serverId/members/$userId/mod", ModViewReply.serializer())
            Nexa.users.upsert(res.users)
            detail = res.member
            audit = res.audit.orEmpty()
            caught = res.automod.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val d = detail
    val name = d?.nickname?.ifEmpty { null } ?: Names.display(user)
    val above = outranks(serverId, userId) && userId != Nexa.users.meId
    val now = System.currentTimeMillis()
    val timedOut = (d?.timeoutUntil ?: 0) > now
    val held = d != null && (d.flags and 1L) != 0L
    val s = Nexa.servers
    fun letOut() {
        busy = true
        scope.launch {
            try {
                val m = Nexa.api.request("PATCH", "/servers/$serverId/members/$userId", fish.nexa.net.Member.serializer(), buildJsonObject { put("quarantined", false) })
                Nexa.servers.upsertMember(m)
                Nexa.ui.toast(t("members.mod.letOutDone", "name" to name), "success")
                onChanged()
                attempt++
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("members.mod.failed")), "error")
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, title = t("members.mod.heading"), maxWidth = 480.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(userId, name, d?.avatarId ?: user?.avatarId, 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    fish.nexa.ui.chat.PersonName(userId, name, serverId = serverId, style = Nx.type.title.copy(fontSize = Nx.type.sp(20f), lineHeight = Nx.type.sp(25f), fontWeight = FontWeight(700)), color = c.textStrong)
                    if (user != null) Txt("@" + user.username, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                }
            }
            if (failed) LoadFailed(t("members.mod.loadFailed")) { attempt++ }
            if (d == null && !failed) Loading(t("chat.pins.loading"))
            if (d != null) {
                if (d.signals.isNotEmpty()) Block(t("members.mod.signals")) {
                    for (sig in d.signals) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SignalBadge(sig, 28.dp)
                        Txt(
                            if (sig == "timed_out" && d.timeoutUntil != null) t("members.mod.timedOutUntil", "time" to Time.full(d.timeoutUntil)) else t("members.signal.$sig"),
                            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, modifier = Modifier.weight(1f),
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().surfaceCard(c, Radii.Md).padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Fact(t("members.col.memberSince"), Time.dateDivider(d.joinedAt))
                    Fact(t("members.col.joinedNexa"), t("members.mod.accountAge", "date" to Time.dateDivider(d.accountCreatedAt), "age" to formatSpan(now - d.accountCreatedAt)))
                    d.join?.let { j ->
                        Fact(
                            t("members.col.joinMethod"),
                            joinMethodLabel(j.joinMethod),
                            j.invitedBy?.let { t("members.mod.invitedBy", "name" to Names.display(users[it])) },
                            code = j.joinCode.ifEmpty { null },
                            subWords = j.invitedBy?.let { fish.nexa.ui.chat.PersonWords(t("members.mod.invitedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(it to Names.display(users[it]))) },
                        )
                    }
                    Fact(t("members.mod.messages"), d.messageCount.toString())
                    Fact(t("members.mod.lastSeen"), d.lastSeen?.let { Time.relative(it) } ?: t("members.mod.never"), last = true)
                }
                Block(t("server.members.roles")) { ModRoleChips(serverId, d.roleIds, emptyText = t("members.mod.noRoles")) }
                if (above) Block(t("members.mod.do")) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.canI(st, P.MANAGE_ROLES)) NxButton(t("server.members.editRoles"), { MemberActions.open(serverId, userId, MemberActions.Kind.ROLES) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                        if (held && s.canI(st, P.MODERATE_MEMBERS)) NxButton(t("members.mod.letOut"), ::letOut, kind = ButtonKind.POSITIVE, size = ButtonSize.SM, loading = busy)
                        if (s.canI(st, P.MODERATE_MEMBERS)) NxButton(if (timedOut) t("server.members.removeTimeout") else t("server.members.timeout"), { MemberActions.open(serverId, userId, MemberActions.Kind.TIMEOUT) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                        if (s.canI(st, P.KICK_MEMBERS)) NxButton(t("server.members.kick"), { MemberActions.open(serverId, userId, MemberActions.Kind.KICK) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                        if (s.canI(st, P.BAN_MEMBERS)) NxButton(t("server.members.ban"), { MemberActions.open(serverId, userId, MemberActions.Kind.BAN) }, kind = ButtonKind.DANGER, size = ButtonSize.SM)
                    }
                }
                if (caught.isNotEmpty()) Block(t("members.mod.automod")) {
                    for (ev in caught) HistoryLine(Time.full(ev.createdAt), t("members.mod.caughtBy", "rule" to ev.ruleName), null)
                }
                if (audit.isNotEmpty()) Block(t("members.mod.history")) {
                    for (e in audit) HistoryLine(
                        Time.full(e.createdAt),
                        t(
                            "members.mod.historyLine",
                            "who" to (if (e.actorId == "0") t("automod.name") else Names.display(users[e.actorId])),
                            "what" to t("server.auditLog.verb.${e.action}"),
                            "target" to Names.display(user),
                        ),
                        e.reason.ifEmpty { null }?.let { t("server.auditLog.reason", "reason" to it) },
                        words = fish.nexa.ui.chat.PersonWords(t("members.mod.historyLine",
                            "who" to (if (e.actorId == "0") t("automod.name") else fish.nexa.ui.chat.PERSON_SLOT),
                            "what" to t("server.auditLog.verb.${e.action}"), "target" to "\u0002"),
                            listOf(e.actorId.takeUnless { it == "0" } to Names.display(users[e.actorId]), userId to Names.display(user)), serverId),
                    )
                }
            }
        }
    }
}

@Composable
private fun Block(head: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(head, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = Nx.colors.textMuted, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** One fact about the member: what it is on the left, the answer beside it (with an invite's code in code type). */
@Composable
private fun Fact(label: String, value: String, sub: String? = null, last: Boolean = false, code: String? = null, subWords: fish.nexa.ui.chat.PersonWords? = null) {
    val c = Nx.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.weight(0.42f))
            Column(Modifier.weight(0.58f)) {
                if (code == null) Txt(value, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text)
                else androidx.compose.foundation.text.BasicText(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(value)
                        append(" ")
                        withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = Nx.type.code.fontFamily, fontSize = Nx.type.sp(13f))) { append(code) }
                    },
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), color = c.text),
                )
                if (subWords != null) fish.nexa.ui.chat.PersonWordsText(subWords, Nx.type.hint, c.textMuted)
                else if (sub != null) Txt(sub, style = Nx.type.hint, color = c.textMuted)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
    }
}

@Composable
private fun HistoryLine(time: String, text: String, sub: String?, words: fish.nexa.ui.chat.PersonWords? = null) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().background(c.bgSurface, Radii.Sm).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Txt(time, style = Nx.type.hint, color = c.textMuted)
        if (words != null) fish.nexa.ui.chat.PersonWordsText(words, Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), c.text)
        else Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.text)
        if (sub != null) Txt(sub, style = Nx.type.hint, color = c.textMuted)
    }
}
