package fish.nexa.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.net.Ban
import fish.nexa.net.Invite
import fish.nexa.net.PublicUser
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.describeError
import fish.nexa.ui.markdown.AppLinks
import fish.nexa.ui.shell.Names
import fish.nexa.ui.shell.openInvite
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.Radii
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** Whether a gateway event names this server (`server_id` in its payload). */
private fun isFor(d: kotlinx.serialization.json.JsonElement?, serverId: String) =
    ((d as? JsonObject)?.get("server_id") as? JsonPrimitive)?.contentOrNull == serverId

// ---------------------------------------------------------------------------
// Encryption

/**
 * EncryptionPage: the server's key and each private channel's, who made the
 * latest and whether this device holds it; Rotate for holders; and Check Now,
 * which hands out keys members who joined while nobody was online lack.
 */
@Composable
fun EncryptionPage(serverId: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val users by Nexa.users.byId.collectAsState()
    var info by remember { mutableStateOf<List<ServerKeyInfo>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    suspend fun load() {
        try {
            info = Nexa.api.get("/servers/$serverId/keys", ListSerializer(ServerKeyInfo.serializer()))
            failed = false
        } catch (_: Exception) {
            info = null
            failed = true
        }
    }
    LaunchedEffect(attempt) { load() }
    fun rotate(conv: String) {
        busy = conv
        scope.launch {
            try {
                Nexa.keys.rotate(conv, "manual")
                Nexa.ui.toast(t("server.encryption.aNewKeyWasCreatedAnd"), "success")
                load()
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("server.encryption.couldNotRotateTheKey")), "error")
            } finally {
                busy = null
            }
        }
    }
    fun reconcile() {
        busy = "reconcile"
        scope.launch {
            val ok = try {
                Nexa.keys.reconcile()
            } catch (_: Exception) {
                false
            }
            if (ok) Nexa.ui.toast(t("server.encryption.checkedEveryoneSKeys"), "success")
            else Nexa.ui.toast(t("server.encryption.couldNotCheckEveryoneSKeys"), "error")
            busy = null
        }
    }
    val needsRotation = info?.any { it.flags?.needsRotation == true } == true
    SettingsPage {
        PageTitle(t("server.encryption.encryption"), Modifier.padding(bottom = 0.dp))
        Hint(t("server.encryption.everyMessageHereIsEndTo"), Modifier.padding(bottom = 16.dp))
        if (needsRotation) WarnBox(t("server.encryption.someoneLeftRotate"))
        when {
            info == null && !failed -> Loading(t("server.encryption.loadingKeys"))
            failed -> LoadFailed(t("server.encryption.theKeysForThisServerCould")) { attempt++ }
        }
        for (k in info.orEmpty()) key(k.convId) {
            val latest = k.epochs.lastOrNull()
            val held = Nexa.keys.ring.latest(k.convId)
            val holdLatest = held != null && latest != null && held.first == latest.epoch
            val sub = (if (latest != null) t("server.encryption.keyEpochCreatedBy", "epoch" to latest.epoch, "when" to Time.full(latest.createdAt), "who" to fish.nexa.ui.chat.PERSON_SLOT) + (if (latest.reason.isNotEmpty()) " (${latest.reason})" else "")
            else t("server.encryption.noKeyYet")) + " · " + when {
                holdLatest -> t("server.encryption.youHoldTheCurrentKey")
                held != null -> t("server.encryption.youHoldAnOlderKeyWaiting")
                else -> t("server.encryption.youDonTHoldThisKey")
            }
            KeyCard("KeyRound", if (k.flags?.needsRotation == true) c.warning else c.positive, k.name, sub, subWords = latest?.let { fish.nexa.ui.chat.PersonWords(sub, listOf(it.createdBy to Names.display(users[it.createdBy]))) }) {
                NxButton(
                    t("server.encryption.rotate"), { rotate(k.convId) },
                    kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "RefreshCw",
                    enabled = holdLatest && busy != k.convId, loading = busy == k.convId,
                )
            }
            if (!holdLatest) Hint(t("server.encryption.onlyHoldersCanRotate"), Modifier.padding(start = 4.dp, bottom = 8.dp), top = 0.dp)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp).height(1.dp).background(c.borderSubtle))
        KeyCard("ShieldCheck", c.positive, t("server.encryption.shareMissingKeys"), t("server.encryption.membersWhoJoinedWhileNobodyWith")) {
            NxButton(t("server.encryption.checkNow"), { reconcile() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = busy == "reconcile")
        }
    }
}

/** `.keyCard`: an icon, a name and a line about it, and its button; the button drops under on a narrow phone. */
@Composable
private fun KeyCard(icon: String, tint: androidx.compose.ui.graphics.Color, name: String, sub: String, subWords: fish.nexa.ui.chat.PersonWords? = null, action: @Composable () -> Unit) {
    val c = Nx.colors
    Wrap(Modifier.fillMaxWidth().padding(bottom = 8.dp).surfaceCard(c).padding(horizontal = 16.dp, vertical = 14.dp), gap = 12.dp) {
        Row(Modifier.weight(1f).widthIn(min = 200.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, tint, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(name, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.text)
                if (subWords != null) fish.nexa.ui.chat.PersonWordsText(subWords, Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), c.textMuted)
                else Txt(sub, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            }
        }
        action()
    }
}

// ---------------------------------------------------------------------------
// Invites

/**
 * InvitesPage: the live invite links, each with who made it, its code,
 * channel, uses and expiry, to copy (with the server's key where this device
 * holds it) or revoke. Needs Manage Server.
 */
@Composable
fun InvitesPage(serverId: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val users by Nexa.users.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    var invites by remember { mutableStateOf<List<Invite>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        try {
            invites = Nexa.api.get("/servers/$serverId/invites", ListSerializer(Invite.serializer()))
            failed = false
        } catch (_: Exception) {
            invites = null
            failed = true
        }
    }
    LaunchedEffect(attempt) { load() }
    LaunchedEffect(Unit) {
        Nexa.session.events.collect { e -> if ((e.t == "invite_create" || e.t == "invite_delete") && isFor(e.d, serverId)) load() }
    }
    fun revoke(code: String) {
        busy = code
        scope.launch {
            try {
                Nexa.api.request("DELETE", "/invites/$code")
                invites = invites?.filter { it.code != code }
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("server.invites.couldNotRevokeTheInvite")), "error")
            } finally {
                busy = null
            }
        }
    }
    val servers by Nexa.servers.byId.collectAsState()
    val pausedUntil = servers[serverId]?.server?.invitesPausedUntil
    val paused = pausedUntil != null && pausedUntil > System.currentTimeMillis()
    val forever = paused && pausedUntil!! > System.currentTimeMillis() + 365L * 24 * 3600 * 1000
    var pausing by remember { mutableStateOf(false) }
    fun setPause(until: Long?) {
        pausing = true
        scope.launch {
            try {
                patchServer(serverId, mapOf("invites_paused_until" to (until?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)))
                Nexa.ui.toast(if (until != null) t("server.invites.pausedToast") else t("server.invites.resumedToast"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("server.invites.pauseFailed")), "error")
            } finally {
                pausing = false
            }
        }
    }
    fun pauseMenu() {
        val spans = listOf("1h" to 1L, "6h" to 6L, "12h" to 12L, "1d" to 24L, "1w" to 24L * 7)
        openMenu(
            spans.map { (id, h) -> MenuItem.Action(t("server.invites.pause$id")) { setPause(System.currentTimeMillis() + h * 3600 * 1000) } } +
                MenuItem.Action(t("server.invites.pauseForever")) { setPause(32503600000000L) },
            title = t("server.invites.pauseFor"),
        )
    }
    fun revokeAll() {
        confirm(t("server.invites.revokeAllTitle"), t("server.invites.revokeAllBody", "count" to (invites?.size ?: 0)), t("server.invites.revokeAll")) {
            val res = Nexa.api.request("DELETE", "/servers/$serverId/invites") as? JsonObject
            invites = emptyList()
            val n = (res?.get("revoked") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            Nexa.ui.toast(t("server.invites.revokedAll", "count" to n), "success")
        }
    }
    SettingsPage {
        PageTitle(t("server.invites.invites"), Modifier.padding(bottom = 0.dp))
        Hint(t("server.invites.activeInviteLinksForThisServer"))
        // who can join: open, or paused (every invite, Discover and the server tag wait)
        val shape = Radii.Md
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp)
                .then(
                    if (paused) Modifier.ring(shape, c.warning.copy(alpha = 0.5f)).background(mixSrgb(c.warning, c.bgSurface, 0.9f), shape)
                    else Modifier.surfaceCard(c, shape),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics { contentDescription = t("server.invites.whoCanJoin") },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (paused) "PauseCircle" else "DoorOpen", if (paused) c.textWarning else c.positive, size = 20.dp, modifier = Modifier.padding(top = 1.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(if (paused) t("server.invites.pausedHead") else t("server.invites.openHead"), style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong)
                        Txt(
                            if (!paused) t("server.invites.openDesc") else if (forever) t("server.invites.pausedForever") else t("server.invites.pausedUntil", "time" to Time.full(pausedUntil!!)),
                            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19f)),
                            color = c.textMuted,
                        )
                    }
                    if (paused) NxButton(t("server.invites.resume"), { setPause(null) }, kind = ButtonKind.POSITIVE, size = ButtonSize.SM, icon = "Play", loading = pausing)
                    else NxButton(t("server.invites.pause"), ::pauseMenu, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Pause", loading = pausing)
                }
            }
        }
        VanityLinkSection(serverId)
        H5(t("server.invites.inviteLinks"))
        Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxButton(t("server.invites.createInvite"), { openInvite(serverId, null) }, size = ButtonSize.SM)
            if (!invites.isNullOrEmpty()) NxButton(t("server.invites.revokeAll"), ::revokeAll, kind = ButtonKind.GHOST, size = ButtonSize.SM)
        }
        when {
            invites == null && !failed -> Loading(t("server.invites.loadingInvites"))
            failed -> LoadFailed(t("server.invites.theInviteListCouldNotBe")) { attempt++ }
            invites?.isEmpty() == true -> Hint(t("server.invites.thereAreNoActiveInvites"), top = 0.dp)
        }
        // a card per invite, each value under its column's name (the web's table on a phone)
        for (i in invites.orEmpty()) key(i.code) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).surfaceCard(c).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Cell(t("server.invites.inviter"), Names.display(users[i.inviterId]), personId = i.inviterId)
                Cell(t("server.invites.code"), i.code, mono = true)
                Cell(t("server.invites.channel"), i.channelId?.let { channels[it] }?.let { "#${it.name}" } ?: t("settings.profile.none"))
                Cell(t("server.invites.uses"), i.uses.toString() + if (i.maxUses > 0) " / ${i.maxUses}" else "")
                Cell(t("server.invites.expires"), i.expiresAt?.let { Time.full(it) } ?: t("server.invites.never"))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    NxButton(t("server.invites.copyLink"), { copyAndSay(inviteLink(serverId, i.code), t("server.invites.linkCopied")) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    NxButton(t("server.invites.revoke"), { revoke(i.code) }, kind = ButtonKind.GHOST, size = ButtonSize.SM, loading = busy == i.code)
                }
            }
        }
    }
}

/** An invite's link, carrying the server's current key when this device holds it (lib/inviteLink.ts). */
fun inviteLink(serverId: String, code: String): String {
    val key = Nexa.keys.ring.latest(serverId)?.let { (epoch, root) -> AppLinks.InviteKey(epoch.toLong(), root) }
    return AppLinks.inviteUrl(AppLinks.shareOrigin(Nexa.api.baseUrl), code, key)
}

/** One value under its label, as a phone draws a table row (`.stack td`). */
@Composable
private fun Cell(label: String, value: String, mono: Boolean = false, personId: String? = null) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(label, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted)
        fish.nexa.ui.chat.PersonName(
            personId, value,
            style = (if (mono) Nx.type.code.copy(fontSize = Nx.type.sp(13f)) else Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f))).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
            color = c.text,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Bans

@Serializable
private data class BansReply(val bans: List<Ban> = emptyList(), val users: List<PublicUser> = emptyList())

/** BansPage: who is banned, by whom, when and why, each with Revoke Ban. Needs Ban Members. */
@Composable
fun BansPage(serverId: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val users by Nexa.users.byId.collectAsState()
    var bans by remember { mutableStateOf<List<Ban>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        try {
            val res = Nexa.api.get("/servers/$serverId/bans", BansReply.serializer())
            Nexa.users.upsert(res.users)
            bans = res.bans
            failed = false
        } catch (_: Exception) {
            bans = null
            failed = true
        }
    }
    LaunchedEffect(attempt) { load() }
    LaunchedEffect(Unit) {
        Nexa.session.events.collect { e -> if ((e.t == "ban_add" || e.t == "ban_remove") && isFor(e.d, serverId)) load() }
    }
    fun revoke(userId: String) {
        busy = userId
        scope.launch {
            try {
                Nexa.api.request("DELETE", "/servers/$serverId/bans/$userId")
                bans = bans?.filter { it.userId != userId }
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("server.bans.couldNotRevokeTheBan")), "error")
            } finally {
                busy = null
            }
        }
    }
    val query = q.trim().lowercase()
    val shown = bans.orEmpty().filter { b -> query.isEmpty() || Names.display(users[b.userId]).lowercase().contains(query) || (users[b.userId]?.username ?: "").contains(query) }
    SettingsPage {
        PageTitle(bans?.let { t("server.bans.bansCount", "count" to it.size) } ?: t("server.bans.bans"))
        SearchBox(q, { q = it }, t("server.bans.searchBans"), Modifier.padding(bottom = 12.dp))
        when {
            bans == null && !failed -> Loading(t("server.bans.loadingBans"))
            failed -> LoadFailed(t("server.bans.theBanListCouldNotBe")) { attempt++ }
            bans?.isEmpty() == true -> Hint(t("server.bans.noOneIsBannedKeepIt"), top = 0.dp)
            shown.isEmpty() -> NoMatches(q, { q = "" }, people = true)
        }
        for (b in shown) key(b.userId) {
            val u = users[b.userId]
            val name = Names.display(u)
            Wrap(Modifier.fillMaxWidth().padding(bottom = 6.dp).surfaceCard(c).padding(horizontal = 12.dp, vertical = 10.dp), gap = 12.dp) {
                Row(Modifier.weight(1f).widthIn(min = 200.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(b.userId, name, u?.avatarId, 40.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        fish.nexa.ui.chat.PersonName(b.userId, name, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
                        fish.nexa.ui.chat.PersonText(
                            b.bannedBy, "@" + (u?.username ?: b.userId) + " · " + t("server.bans.bannedByOn", "when" to Time.full(b.createdAt), "who" to fish.nexa.ui.chat.PERSON_SLOT) + (if (b.reason.isNotEmpty()) " · ${b.reason}" else ""), Names.display(users[b.bannedBy]),
                            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)),
                            color = c.textMuted,
                        )
                    }
                }
                NxButton(t("server.bans.revokeBan"), { revoke(b.userId) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = busy == b.userId)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Delete Server

/** Irons out what phone keyboards change on their own: stray spaces and curly quotes. */
private fun norm(x: String) = x.trim().replace(Regex("[‘’]"), "'").replace(Regex("[“”]"), "\"")

/** DeleteServerPage: what goes, the name typed to confirm, then Delete Server. The owner only. */
@Composable
fun DeleteServerPage(serverId: String, onDone: () -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val servers by Nexa.servers.byId.collectAsState()
    val server = servers[serverId]?.server ?: return
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SettingsPage {
        PageTitle(t("server.deleteServer.deleteServer"))
        DangerBox {
            val whole = t("server.deleteServer.areYouSure", "name" to "\u0000")
            val parts = whole.split("\u0000")
            androidx.compose.foundation.text.BasicText(
                buildAnnotatedString {
                    append(parts.first())
                    withStyle(SpanStyle(fontWeight = FontWeight(700), color = c.textStrong)) { append(server.name) }
                    if (parts.size > 1) append(parts.drop(1).joinToString(server.name))
                },
                style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f), color = c.textMuted),
            )
            NxTextField(typed, { typed = it }, label = t("server.deleteServer.typeToConfirm", "name" to server.name), placeholder = server.name)
            Box {
                NxButton(t("server.deleteServer.deleteServer"), {
                    busy = true
                    scope.launch {
                        try {
                            Nexa.api.request("DELETE", "/servers/$serverId")
                            Nexa.servers.remove(serverId)
                            onDone()
                        } catch (e: Exception) {
                            Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("server.deleteServer.couldNotDeleteTheServer"), "error")
                            busy = false
                        }
                    }
                }, kind = ButtonKind.DANGER, icon = "Trash2", enabled = norm(typed) == norm(server.name), loading = busy)
            }
        }
    }
}
