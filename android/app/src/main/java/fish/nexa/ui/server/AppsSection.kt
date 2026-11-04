package fish.nexa.ui.server

import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideArea
import fish.nexa.ui.components.glideItem
import fish.nexa.ui.components.rememberGlide
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.AppCommands
import fish.nexa.data.Apps
import fish.nexa.net.CommandPermission
import fish.nexa.net.IntegrationCommands
import fish.nexa.net.ServerApp
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.apps.Choice
import fish.nexa.ui.apps.EntityKind
import fish.nexa.ui.apps.ReadingGlass
import fish.nexa.ui.apps.SelectBox
import fish.nexa.ui.apps.entityChoices
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.media.HookTags
import fish.nexa.ui.shell.Names
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.rgbColor
import fish.nexa.ui.theme.ring
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Server Settings, Integrations, Apps (web components/server/pages/
 * AppsSection.tsx): the apps added to this server, for whoever manages it.
 * Each says who added it and when, what it can read, and opens to set who may
 * use its commands where, or to take it out.
 */
@Composable
fun AppsSection(serverId: String) {
    val c = Nx.colors
    val users by Nexa.users.byId.collectAsState()
    var list by remember { mutableStateOf<List<ServerApp>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverId, attempt) {
        failed = false
        try {
            list = Apps.inServer(serverId)
        } catch (_: Exception) {
            failed = true
        }
    }
    // added or taken out elsewhere: the list follows
    LaunchedEffect(serverId) {
        Nexa.session.events.collect { e ->
            if (e.t == "integrations_update" && ((e.d as? kotlinx.serialization.json.JsonObject)?.get("server_id") as? JsonPrimitive)?.contentOrNull == serverId) attempt++
        }
    }
    fun remove(app: ServerApp) = confirm(
        t("apps.server.removeNamed", "name" to app.application.name),
        t("apps.server.removeBody", "name" to app.application.name),
        t("apps.server.remove"),
    ) {
        try {
            Apps.removeFromServer(serverId, app.application.id)
            list = list?.filter { it.application.id != app.application.id }
            Nexa.ui.toast(t("apps.server.removed", "name" to app.application.name), "success")
        } catch (e: Exception) {
            Nexa.ui.toast(describeError(e, t("apps.server.removeFailed")), "error")
        }
    }
    H5(t("apps.server.title"), top = 0.dp)
    Hint(t("apps.server.intro"), Modifier.padding(bottom = 10.dp), top = 0.dp)
    val l = list
    when {
        failed -> LoadFailed(t("apps.server.couldNotLoad")) { attempt++ }
        l == null -> Loading(t("apps.portal.loading"))
        l.isEmpty() -> Box(Modifier.fillMaxWidth().dashedOutline(c.border, Radii.sm, 1.dp).padding(horizontal = 16.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
            Txt(t("apps.server.none"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (app in l.orEmpty()) key(app.application.id) {
            val expanded = open == app.application.id
            val by = app.installedBy?.let { users[it] }
            Column(Modifier.fillMaxWidth().border(1.dp, c.borderSubtle, Radii.Sm).background(c.bgSurface, Radii.Sm).clip(Radii.Sm)) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (pressed) c.bgHover else Color.Transparent)
                        .pressable(interaction) { open = if (expanded) null else app.application.id }
                        .semantics { stateDescription = if (expanded) "expanded" else "collapsed" }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(app.application.id, app.application.name, app.application.iconId ?: app.application.bot?.avatarId, 40.dp, icon = app.application.iconId != null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Txt(app.application.name, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                            HookTags(null, app = true)
                            if (app.application.intents.messageContent) Row(
                                Modifier.background(c.warning.copy(alpha = 0.14f), RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon("TriangleAlert", c.textWarning, size = 11.dp)
                                Txt(t("apps.portal.readsEverything"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(650)), color = c.textWarning, maxLines = 1)
                            }
                        }
                        fish.nexa.ui.chat.PersonText(
                            by?.id, if (by != null) t("apps.server.addedBy", "name" to fish.nexa.ui.chat.PERSON_SLOT, "date" to Time.shortDate(app.installedAt)) else t("apps.server.addedOn", "date" to Time.shortDate(app.installedAt)), Names.display(by),
                            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1,
                        )
                    }
                    Icon(if (expanded) "ChevronDown" else "ChevronRight", c.textMuted, size = 18.dp)
                }
                if (expanded) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (app.application.description.isNotEmpty()) Txt(app.application.description, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle)
                        if (app.botMember) ReadingGlass(app.application.name, app.application.intents.messageContent, compact = true)
                        CommandPermissions(serverId, app)
                        NxButton(t("apps.server.remove"), { remove(app) }, kind = ButtonKind.DANGER, size = ButtonSize.SM, icon = "Trash2")
                    }
                }
            }
        }
    }
}

/** A text channel of the server, which the pickers find the server's people, roles and channels through. */
private fun someChannel(serverId: String): String =
    Nexa.channels.byId.value.values.firstOrNull { it.serverId == serverId && it.type == "text" }?.id ?: ""

/**
 * Who may use an app's commands in a server: for all of them, or for one,
 * roles and people allowed or not, and channels allowed or not. Without
 * entries, a command follows what the app set; entries for one command come
 * before those for all of them.
 */
@Composable
private fun CommandPermissions(serverId: String, app: ServerApp) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val servers by Nexa.servers.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    var data by remember { mutableStateOf<IntegrationCommands?>(null) }
    var failed by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(app.application.id) }
    val edits = remember { mutableStateMapOf<String, List<CommandPermission>>() }
    var busy by remember { mutableStateOf(false) }
    val via = remember(serverId) { someChannel(serverId) }
    LaunchedEffect(serverId, app.application.id) {
        try {
            data = Apps.serverCommands(serverId, app.application.id)
        } catch (_: Exception) {
            failed = true
        }
    }
    val d = data
    when {
        failed -> {
            Hint(t("apps.server.commandsFailed"), top = 0.dp)
            return
        }
        d == null -> {
            Loading(t("apps.portal.loading"))
            return
        }
        d.commands.isEmpty() -> {
            Hint(t("apps.server.noCommands", "app" to app.application.name), top = 0.dp)
            return
        }
    }
    d!!
    val server = servers[serverId]
    val entries = edits[target] ?: d.permissions[target] ?: emptyList()
    val changed = edits.containsKey(target)
    fun set(list: List<CommandPermission>) {
        edits[target] = list
    }
    fun add(id: String, type: Int) {
        if (entries.any { it.id == id }) return
        set(entries + CommandPermission(id, type, true))
    }
    fun save() {
        busy = true
        scope.launch {
            try {
                Apps.setCommandPermissions(serverId, app.application.id, target, entries)
                data = d.copy(permissions = d.permissions + (target to entries))
                edits.remove(target)
                Nexa.ui.toast(t("apps.server.permissionsSaved"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("apps.server.permissionsFailed")), "error")
            } finally {
                busy = false
            }
        }
    }
    fun label(e: CommandPermission): String = when (e.type) {
        3 -> if (e.id == d.allChannelsId) t("apps.server.allChannels") else "#${channels[e.id]?.name ?: e.id}"
        1 -> if (e.id == serverId) "@everyone" else "@${server?.roles?.get(e.id)?.name ?: e.id}"
        else -> Names.display(users[e.id])
    }
    @Composable
    fun EntryRow(e: CommandPermission) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (e.type) {
                3 -> Icon("Hash", c.icon, size = 18.dp)
                2 -> Avatar(e.id, label(e), users[e.id]?.avatarId, 24.dp)
                else -> {
                    val color = server?.roles?.get(e.id)?.color?.takeIf { it != 0L }
                    Box(Modifier.padding(horizontal = 6.dp).size(12.dp).background(color?.let { rgbColor(it) } ?: c.textMuted, CircleShape))
                }
            }
            Txt(label(e), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(550)), color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
            // the pill slides between Allowed and Denied, turning from green to red on its way (components/Glide.kt)
            val glide = rememberGlide(e.permission)
            val tint = glide.tint { if (it == true) c.positive else c.danger }
            val pillShape = RoundedCornerShape(7.dp)
            Box(Modifier.clip(Radii.Sm).background(c.bgInput, Radii.Sm).padding(3.dp).glideArea(glide)) {
            GlidePill(glide, Modifier.background(tint.copy(alpha = 0.18f), pillShape).ring(pillShape, tint.copy(alpha = 0.5f)))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((allow, word) in listOf(true to t("apps.server.allowed"), false to t("apps.server.denied"))) {
                    val on = e.permission == allow
                    Box(
                        Modifier
                            .glideItem(glide, allow)
                            .clip(pillShape)
                            .pressable(remember(e.id, allow) { MutableInteractionSource() }, role = Role.RadioButton) { set(entries.map { if (it === e) it.copy(permission = allow) else it }) }
                            .semantics { selected = on }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) { Txt(word, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = if (on) (if (allow) c.positive else c.textDanger) else c.textMuted) }
                }
            }
            }
            fish.nexa.ui.components.IconButton("X", t("apps.server.removeEntry", "name" to label(e)), { set(entries.filter { it !== e }) }, tint = c.icon, size = 36.dp, iconSize = 15.dp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(t("apps.server.whoCanUse"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(700)), color = c.textStrong)
        Hint(t("apps.server.whoCanUseHint"), top = 0.dp)
        // which commands the entries are for: all of them, or one
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val targets = listOf(app.application.id to t("apps.server.allCommands")) + d.commands.map { cmd -> cmd.id to (if (cmd.type == AppCommands.CMD_SLASH) "/" else "") + AppCommands.name(cmd) }
            for ((id, word) in targets) {
                val on = target == id
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .then(if (on) Modifier.background(brandGradient(c), RoundedCornerShape(999.dp)) else Modifier.border(1.dp, c.border, RoundedCornerShape(999.dp)))
                        .pressable(remember(id) { MutableInteractionSource() }, role = Role.Tab) { target = id }
                        .semantics { selected = on }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Txt(word, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = if (on) c.onBrand else c.textSubtle, maxLines = 1) }
            }
        }
        for ((head, kindIsChannel) in listOf(t("apps.server.rolesAndMembers") to false, t("apps.server.channels") to true)) {
            val shown = entries.filter { (it.type == 3) == kindIsChannel }
            Column(
                Modifier.fillMaxWidth().background(c.bgInput.copy(alpha = 0.6f), Radii.Md).border(1.dp, c.borderSubtle, Radii.Md).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt(head, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted)
                if (shown.isEmpty()) Txt(t("apps.server.noEntries"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.padding(vertical = 6.dp))
                for (e in shown) key("${e.type}:${e.id}") { EntryRow(e) }
                Box(Modifier.padding(top = 4.dp)) {
                    if (!kindIsChannel) SelectBox(
                        placeholder = t("apps.server.addRoleOrMember"),
                        title = head,
                        picked = emptyList(),
                        labelOf = { it },
                        choicesFor = { q -> entityChoices(EntityKind.MENTIONABLE, via, q) },
                        searchable = true,
                        onSearch = { q -> fish.nexa.data.Pickables.findMembers(via, q) },
                        onCommit = { v -> v.firstOrNull()?.let { add(it, if (users[it] != null) 2 else 1) } },
                    ) else SelectBox(
                        placeholder = t("apps.server.addChannel"),
                        title = head,
                        picked = emptyList(),
                        labelOf = { it },
                        choicesFor = { q -> listOf(Choice(d.allChannelsId, t("apps.server.allChannels"))) + entityChoices(EntityKind.CHANNEL, via, q) },
                        searchable = true,
                        onCommit = { v -> v.firstOrNull()?.let { add(it, 3) } },
                    )
                }
            }
        }
        if (changed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxButton(t("apps.server.savePermissions"), { save() }, size = ButtonSize.SM, loading = busy)
            NxButton(t("common.imageCrop.reset"), { edits.remove(target) }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
        }
    }
}
