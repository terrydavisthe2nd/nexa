package fish.nexa.ui.shell

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.profile.StatusLine
import fish.nexa.ui.profile.hasStatusLine
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.panel
import fish.nexa.ui.theme.ring
import fish.nexa.voice.VoiceLocal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The dock under the drawer: the call you are in (when you are) above your user panel. */
@Composable
fun Dock(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fish.nexa.ui.voice.VoiceStatus()
        UserPanel()
    }
}

/**
 * UserPanel.module.css on a phone: two rows in a raised panel. You (avatar
 * with your dot, name, status line) open the status sheet; under it the
 * microphone, headphones, activity and settings buttons, spread evenly.
 */
@Composable
fun UserPanel() {
    val c = Nx.colors
    val me by Nexa.users.me.collectAsState()
    val presences by Nexa.presence.byId.collectAsState()
    val local by VoiceLocal.state.collectAsState()
    val user = me ?: return
    val status = presences[user.id]?.status ?: user.presencePref.let { if (it == "invisible") "invisible" else "online" }
    val shape = Radii.Md
    Column(Modifier.fillMaxWidth().panel(shape, c, c.bgSurface).padding(4.dp)) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(if (pressed) c.bgHover else Color.Transparent, Radii.Sm)
                .pressable(interaction) { openStatusSheet() }
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Avatar(user.id, user.name, user.avatarId, 32.dp, status = status, speaking = fish.nexa.voice.Speaking.isSpeaking(user.id))
            Column(Modifier.weight(1f)) {
                fish.nexa.ui.chat.PersonName(user.id, user.name, nameStyle = user.nameStyle, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
                // UserPanel.tsx: what they wrote or are doing, with its emoji drawn, else where they are
                val sub = Nx.type.hint.copy(lineHeight = Nx.type.sp(14.4f))
                if (hasStatusLine(user.id)) StatusLine(user.id, sub, c.textMuted)
                else Txt(presenceWord(status), style = sub, color = c.textMuted, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            // a moderator's mute and deafen hold whatever these buttons say, so the buttons say so
            val call by fish.nexa.voice.Voice.state.collectAsState()
            val modMute = call.mine?.let { fish.nexa.ui.voice.VoiceModeration.modMuted(it) } == true
            val modDeaf = call.mine?.serverDeaf == true
            DockButton(if (local.muted || modMute) "MicOff" else "Mic", if (modMute) t("voice.moderation.mutedByModerator") else if (local.muted) t("voice.voice.unmute") else t("voice.voice.mute"), off = local.muted || modMute) {
                if (modMute) Nexa.ui.toast(t("voice.moderation.mutedHint")) else VoiceLocal.toggleMute()
            }
            DockButton(if (local.deafened || modDeaf) "HeadphoneOff" else "Headphones", if (modDeaf) t("voice.moderation.deafenedByModerator") else if (local.deafened) t("shell.user.undeafen") else t("shell.user.deafen"), off = local.deafened || modDeaf) {
                if (modDeaf) Nexa.ui.toast(t("voice.moderation.deafenedHint")) else VoiceLocal.toggleDeafen()
            }
            // every unread mention, from every conversation and server (UserPanel.tsx)
            val reads by Nexa.readStates.byChannel.collectAsState()
            val mentionCount = reads.values.sumOf { it.mentions }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                DockButton("AtSign", if (mentionCount > 0) t("mentions.buttonUnread", "count" to mentionCount) else t("mentions.title"), off = false) { fish.nexa.ui.chat.openMentions() }
                if (mentionCount > 0) {
                    val pill = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 0.dp, y = 2.dp).ring(pill, c.bgSurface, 2.dp).background(c.danger, pill).defaultMinSize(minWidth = 16.dp).height(16.dp).padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(if (mentionCount > 99) "99+" else mentionCount.toString(), style = Nx.type.hint.copy(fontWeight = FontWeight(700), fontSize = Nx.type.sp(11f), lineHeight = Nx.type.sp(11f)), color = Color.White, maxLines = 1) }
                }
            }
            DockButton("Settings", t("settings.settings.userSettings"), off = false) { openSettings() }
            // a new version of the app is downloaded: a green button to install it, as the desktop app's restart button is
            val update by fish.nexa.ui.AppUpdate.state.collectAsState()
            (update as? fish.nexa.ui.AppUpdate.State.Ready)?.let { r ->
                Box(Modifier.size(40.dp).clip(Radii.Sm), contentAlignment = Alignment.Center) {
                    IconButton("Download", t("android.update.installVersion", "version" to r.version), { fish.nexa.ui.AppUpdate.install() }, tint = c.positive, size = 40.dp, iconSize = 20.dp)
                }
            }
        }
    }
}

fun presenceWord(status: String): String = when (status) {
    "online" -> t("shell.user.online")
    "idle" -> t("shell.user.idle")
    "dnd" -> t("shell.user.doNotDisturb")
    "invisible" -> t("shell.user.invisible")
    else -> t("chat.memberList.offline")
}

@Composable
private fun DockButton(icon: String, label: String, off: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(Radii.Sm)
            .background(if (off) c.danger.copy(alpha = 0.16f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(icon, label, onClick, tint = if (off) c.textDanger else c.icon, size = 40.dp, iconSize = 20.dp)
    }
}

/** The status choices (UserPanel status popover), as a sheet on a phone. */
fun openStatusSheet() {
    lateinit var layer: Layer
    layer = Layer("status") { close ->
        Sheet(layer, closeButton = false) {
            val c = Nx.colors
            val me = Nexa.users.me.value ?: return@Sheet
            val status = Nexa.presence.byId.value[me.id]?.status ?: "online"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(4.dp)) {
                Avatar(me.id, me.name, me.avatarId, 40.dp, status = status)
                Column {
                    fish.nexa.ui.chat.PersonName(me.id, me.name, nameStyle = me.nameStyle, style = Nx.type.sheetTitle, color = c.textStrong, maxLines = 1)
                    Txt("@${me.username}", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                }
            }
            MenuSep()
            StatusOption("Pencil", if (me.statusText.isNotBlank() || me.statusEmoji.isNotBlank()) t("shell.user.editCustomStatus") else t("modals.customStatus.setACustomStatus"), null) {
                close()
                openCustomStatus()
            }
            MenuSep()
            for ((value, label, sub) in listOf(
                Triple("online", t("shell.user.online"), null),
                Triple("idle", t("shell.user.idle"), null),
                Triple("dnd", t("shell.user.doNotDisturb"), t("shell.user.youWillNotReceiveAnyDesktop")),
                Triple("invisible", t("shell.user.invisible"), t("shell.user.youWillAppearOfflineButKeep")),
            )) {
                StatusOption(null, label, sub, dot = value, chosen = me.presencePref == value || (value == "online" && me.presencePref.isBlank())) {
                    Nexa.session.send("presence", buildJsonObject { put("status", value) })
                    Nexa.users.setMe(me.copy(presencePref = value))
                    close()
                }
            }
            MenuSep()
            // the same switch as Settings, Notifications, Sounds
            val doc by Nexa.settings.doc.collectAsState()
            val sounds = ((doc["notifications"] as? kotlinx.serialization.json.JsonObject)?.get("sounds") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: true
            StatusOption("Bell", t("shell.user.notificationSounds"), null, trailing = {
                fish.nexa.ui.components.NxToggle(sounds, { fish.nexa.ui.settings.patchNested("notifications", mapOf("sounds" to kotlinx.serialization.json.JsonPrimitive(it))) }, label = t("shell.user.notificationSounds"))
            }) {
                fish.nexa.ui.settings.patchNested("notifications", mapOf("sounds" to kotlinx.serialization.json.JsonPrimitive(!sounds)))
            }
            MenuSep()
            // the accounts on this phone, in a sheet of their own (web: Switch Accounts' side menu)
            StatusOption("ArrowLeftRight", t("accounts.switchAccounts"), null, trailing = {
                fish.nexa.ui.Icon("ChevronRight", c.textMuted, size = 16.dp)
            }) {
                close()
                openAccountsSheet()
            }
            // let another device in: the camera reads the code its login page shows
            StatusOption("ScanQrCode", t("android.qrLogin.scanQrCode"), null) {
                close()
                fish.nexa.ui.auth.openQrScanner()
            }
            MenuSep()
            StatusOption("LogOut", t("settings.settings.logOut"), null, danger = true) {
                close()
                confirmLogout()
            }
        }
    }
    Layers.open(layer)
}

/** UserPanel.module.css .sep: a line between the menu's groups. */
@Composable
internal fun MenuSep() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).height(1.dp).background(Nx.colors.borderSubtle))
}

@Composable
internal fun StatusOption(icon: String?, label: String, sub: String?, dot: String? = null, chosen: Boolean = false, danger: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radii.Sm)
            .background(if (pressed) c.brand.copy(alpha = 0.2f) else if (chosen) c.bgSelected else Color.Transparent)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, if (danger) c.textDanger else c.icon, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
        if (dot != null) fish.nexa.ui.components.PresenceDot(dot, 12.dp, Modifier.padding(top = 4.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(550)), color = if (danger) c.textDanger else if (pressed) c.textStrong else c.textSubtle)
            if (sub != null) Txt(sub, style = Nx.type.hint, color = c.textMuted)
        }
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------------------

/** The member list as a drawer from the end side (servers and groups). */
@Composable
fun MemberDrawer() {
    val c = Nx.colors
    val shape = RoundedCornerShape(topStart = Radii.lg, bottomStart = Radii.lg)
    Box(Modifier.fillMaxSize().panel(shape, c, c.bgBase).statusBarsPadding().navigationBarsPadding()) {
        when (val r = Nav.route) {
            is Route.Server -> fish.nexa.ui.chat.MemberList(r.serverId, r.channelId)
            is Route.Dm -> Nexa.channels.get(r.channelId)?.takeIf { it.type == "group_dm" }?.let { fish.nexa.ui.chat.GroupMemberList(it) }
            else -> Unit
        }
    }
}

/** PageHeader.module.css: 52 high, the menu button first on a phone, the title, then actions. */
@Composable
fun PageHeader(title: String, icon: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val c = Nx.colors
    Column {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavButton()
            icon?.invoke()
            Txt(title, style = Nx.type.headerTitle, color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
    }
}

/** PageHeaderButtons: the hamburger that opens the drawer; 40 dp to touch, laid out as 32 (margin -4). */
@Composable
fun NavButton() {
    // with the sidebar always beside the page there is no drawer to open
    if (Nav.wide) return
    val c = Nx.colors
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        IconButton("Menu", t("shell.pageHeader.openNavigation"), { Nav.leftOpen = true }, Modifier.requiredSize(40.dp), tint = c.icon, size = 40.dp, iconSize = 22.dp)
    }
}
