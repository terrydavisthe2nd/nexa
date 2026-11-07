package fish.nexa.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.net.ApiError
import fish.nexa.net.MeUser
import fish.nexa.net.wireJson
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.shell.openProfile
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator

/** PATCH /users/@me with one change; the answer replaces this account's profile. */
suspend fun patchMe(body: kotlinx.serialization.json.JsonObject): MeUser {
    val me = wireJson.decodeFromJsonElement(MeUser.serializer(), Nexa.api.request("PATCH", "/users/@me", body))
    Nexa.users.setMe(me)
    return me
}

/** 3.5 Privacy: who may start a conversation, being added to groups, encryption, the search index. */
@Composable
fun PrivacyPage() {
    val scope = rememberCoroutineScope()
    val me = Nexa.users.me.collectAsState().value ?: return
    val doc = settingsDoc()
    PageTitle(t("settings.privacy.title"))
    SectionHeading(t("settings.privacy.directMessages"), Modifier.settingAnchor("dmPrivacy"), first = true)
    for ((value, label, desc) in listOf(
        Triple("everyone", "settings.privacy.everyone", "settings.privacy.anyoneWithAnAccountCanMessage"),
        Triple("friends_servers", "settings.privacy.friendsAndServerMembers", "settings.privacy.friendsAndServerMembersDesc"),
        Triple("friends", "settings.privacy.friendsOnly", "settings.privacy.onlyYourFriendsCanStartA"),
        Triple("nobody", "settings.privacy.nobody", "settings.privacy.nobodyCanStartANewDirect"),
    )) RadioCard(t(label), me.dmPrivacy == value, {
        scope.launch {
            try {
                patchMe(buildJsonObject { put("dm_privacy", value) })
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("settings.privacy.couldNotSave"), "error")
            }
        }
    }, t(desc))

    SectionHeading(t("settings.privacy.groupChats"))
    ToggleRow(
        t("settings.privacy.letFriendsAddMe"), !me.groupLinkOnly,
        { on ->
            scope.launch {
                try {
                    patchMe(buildJsonObject { put("group_link_only", !on) })
                } catch (e: Exception) {
                    Nexa.ui.toast(describeError(e, t("settings.privacy.couldNotSave")), "error")
                }
            }
        },
        t("settings.privacy.letFriendsAddMeHint"), anchor = "groupAdd",
    )

    SectionHeading(t("server.encryption.encryption"), Modifier.settingAnchor("encryption"))
    Hint(t("settings.privacy.everyMessageAndFileYouSend"))

    SectionHeading(t("settings.privacy.search"))
    val indexOn = doc.bool("searchIndex", true)
    ToggleRow(t("settings.privacy.searchIndex"), indexOn, { setSetting("searchIndex", it) }, t("settings.privacy.searchIndexHint"))
    if (indexOn) Hint(t("android.settings.searchIndexCharging"), Modifier.padding(top = 10.dp))
    Hint(SearchIndexStatus.line(indexOn), Modifier.padding(top = 10.dp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var rebuilding by remember { mutableStateOf(false) }
        if (indexOn) NxButton(t("settings.privacy.searchRebuild"), {
            rebuilding = true
            scope.launch {
                try {
                    SearchIndexStatus.rebuild()
                    Nexa.ui.toast(t("settings.privacy.searchRebuilding"), "success")
                } finally {
                    rebuilding = false
                }
            }
        }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = rebuilding)
        if (doc.strings("searchRecent").isNotEmpty()) NxButton(t("settings.privacy.searchForgetRecent"), {
            setSetting("searchRecent", kotlinx.serialization.json.JsonArray(emptyList()))
        }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
}

/**
 * What Privacy says about the on-device search index. The index itself is
 * the search feature's; it plugs its numbers and its rebuild in here.
 */
object SearchIndexStatus {
    /** How many messages are indexed on this device, or null while the index is closed. */
    var count: () -> Int? = { null }
    var memoryOnly: () -> Boolean = { false }
    var rebuild: suspend () -> Unit = {}

    fun line(on: Boolean): String {
        if (!on || memoryOnly()) return t("settings.privacy.searchIndexMemory")
        val n = count() ?: return t("settings.privacy.searchIndexClosed")
        return t("settings.privacy.searchIndexCount", "count" to n)
    }
}

// ---------------------------------------------------------------------------

/** 3.6 Blocked Users: what happens to their messages, and everyone blocked, to unblock. */
@Composable
fun BlockedPage() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val doc = settingsDoc()
    val rels by Nexa.relationships.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val blocking = doc.obj("blocking") ?: kotlinx.serialization.json.JsonObject(emptyMap())
    val hideMessages = blocking.bool("hideMessages", false)
    val hideReplies = hideMessages && blocking.bool("hideReplies", false)
    PageTitle(t("people.blockedPage.title"))
    Hint(t("people.blockedPage.intro"))

    SectionHeading(t("people.blockedPage.messagesHeading"), Modifier.settingAnchor("blockedMessages"))
    ToggleRow(t("people.block.hideMessages"), hideMessages, { v ->
        patchNested("blocking", mapOf("hideMessages" to JsonPrimitive(v), "hideReplies" to JsonPrimitive(v && hideReplies)))
    }, t("people.block.hideMessagesHint"))
    ToggleRow(t("people.block.hideReplies"), hideReplies, { v ->
        patchNested("blocking", mapOf("hideMessages" to JsonPrimitive(hideMessages), "hideReplies" to JsonPrimitive(v)))
    }, t("people.block.hideRepliesHint"), enabled = hideMessages)
    Hint(t("people.blockedPage.quiet"), Modifier.padding(top = 10.dp))

    val blocked = rels.values.filter { it.type == "blocked" }
    val collator = remember(fish.nexa.ui.I18n.tag) { Collator.getInstance(fish.nexa.ui.I18n.locale).apply { strength = Collator.PRIMARY } }
    val sorted = blocked.sortedWith { a, b ->
        val r = collator.compare(a.user.displayName, b.user.displayName)
        if (r != 0) r else collator.compare(a.user.username, b.user.username)
    }
    var query by remember { mutableStateOf("") }
    SectionHeading(t("people.blockedPage.listHeading", "count" to blocked.size))
    if (blocked.size >= 8) NxTextField(query, { query = it }, placeholder = t("chat.chat.search"), modifier = Modifier.padding(bottom = 12.dp).semantics { contentDescription = t("people.blockedPage.search") })
    val q = query.trim().lowercase().removePrefix("@")
    val shown = if (q.isEmpty()) sorted else sorted.filter { it.user.displayName.lowercase().contains(q) || it.user.username.contains(q) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    when {
        blocked.isEmpty() -> EmptyBox(t("friends.youHaveNotBlockedAnyone"))
        shown.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(t("friends.nobodyHereMatches", "query" to query.trim()), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
            NxButton(t("friends.clearTheSearch"), { query = "" }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        }
        else -> for (r in shown) {
            val u = users[r.user.id] ?: r.user
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(Radii.Sm).pressable(remember { MutableInteractionSource() }) { openProfile(u.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(u.id, u.name, u.avatarId, 32.dp)
                    Column(Modifier.weight(1f)) {
                        fish.nexa.ui.chat.PersonName(u.id, u.name, nameStyle = u.nameStyle, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                        Txt(u.username, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                    }
                }
                NxButton(t("friends.unblock"), {
                    if (busy[u.id] == true) return@NxButton
                    busy[u.id] = true
                    scope.launch {
                        try {
                            Nexa.api.request("DELETE", "/users/@me/blocks/${u.id}")
                            Nexa.relationships.remove(u.id)
                            Nexa.ui.toast(t("friends.unblocked"), "success")
                        } catch (e: ApiError) {
                            if (e.status == 404) {
                                Nexa.relationships.remove(u.id)
                                Nexa.ui.toast(t("friends.unblocked"), "success")
                            } else Nexa.ui.toast(describeError(e, t("friends.couldNotUpdate")), "error")
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("friends.couldNotUpdate")), "error")
                        } finally {
                            busy.remove(u.id)
                        }
                    }
                }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = busy[u.id] == true, modifier = Modifier.semantics { contentDescription = t("people.blockedPage.unblockName", "name" to u.name) })
            }
        }
    }
}

/** A boxed, centred note for an empty list. */
@Composable
fun EmptyBox(text: String) {
    val c = Nx.colors
    val shape = Radii.Md
    Box(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .padding(horizontal = 16.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------

/** 3.7 Your Data: the export, and the way to account removal. */
@Composable
fun DataPage(openPage: ((String, String?) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        running = true
        scope.launch {
            try {
                DataExport.run(uri) { status = it }
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("settings.privacy.couldNotExportYourData")), "error")
            } finally {
                running = false
                status = null
            }
        }
    }
    PageTitle(t("settings.privacy.yourData"))
    SectionHeading(t("settings.data.export"), Modifier.settingAnchor("export"), first = true)
    Hint(t("settings.privacy.downloadEverythingYourProfileFriendsServers"))
    Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        NxButton(t("settings.privacy.exportMyData"), { save.launch(DataExport.fileName()) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = running)
    }
    status?.let { Hint(it, Modifier.padding(top = 8.dp)) }
    SectionHeading(t("settings.account.accountRemoval"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t("settings.data.deleteIsIn"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = Nx.colors.textMuted, modifier = Modifier.weight(1f, fill = false))
        if (openPage != null) NxButton(t("settings.data.goToMyAccount"), { openPage("account", "deleteAccount") }, kind = ButtonKind.LINK, inline = true)
    }
}
