package fish.nexa.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.net.Channel
import fish.nexa.net.Member
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.ChoiceCard
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.ToggleRow
import fish.nexa.ui.components.Txt
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun open(key: String, content: @Composable (layer: Layer, close: () -> Unit) -> Unit) {
    lateinit var layer: Layer
    layer = Layer(key, replaces = key) { close -> content(layer, close) }
    Layers.open(layer)
}

/** The two buttons at the foot of most dialogs: Cancel, and the one that does it. */
@Composable
internal fun FooterButtons(confirm: String, onConfirm: () -> Unit, close: () -> Unit, enabled: Boolean = true, busy: Boolean = false, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        NxButton(t("chat.editMessage.cancel"), close, kind = ButtonKind.GHOST)
        NxButton(confirm, onConfirm, kind = if (danger) ButtonKind.DANGER else ButtonKind.PRIMARY, enabled = enabled, loading = busy)
    }
}

@Composable
internal fun DialogHint(text: String) = Txt(text, style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(19.6f)), color = Nx.colors.textMuted)

// ---------------------------------------------------------------------------
// ServerProfileModal: someone else's profile in a server, for a moderator

/**
 * Someone else's profile in a server, for a moderator with Manage Nicknames:
 * their nickname to change, and what they set for the server (picture,
 * banner, theme, About Me, widgets) to take away if it breaks the rules.
 * One's own server profile is designed in Settings, Profiles.
 */
@Composable
internal fun ServerProfileSheet(layer: Layer, serverId: String, userId: String?, close: () -> Unit) {
    val servers by Nexa.servers.byId.collectAsState()
    val st = servers[serverId]
    val target = userId ?: ""
    val member = st?.members?.get(target)
    var nick by remember { mutableStateOf(member?.nickname ?: "") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mod = Nexa.servers.canI(st, P.MANAGE_NICKNAMES)
    val serverName = st?.server?.name ?: t("modals.serverProfile.thisServer")
    fun save() {
        busy = true
        scope.launch {
            try {
                if (nick.trim() != (member?.nickname ?: "")) {
                    val res = Nexa.api.request("PATCH", "/servers/$serverId/members/$target", Member.serializer(), buildJsonObject {
                        val v = nick.trim()
                        if (v.isEmpty()) put("nick", JsonNull) else put("nick", v)
                    })
                    Nexa.servers.upsertMember(res)
                }
                Nexa.ui.toast(t("serverProfile.nickSaved"), "success")
                close()
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("modals.serverProfile.couldNotChangeTheNickname")), "error")
            } finally {
                busy = false
            }
        }
    }
    fun resetOthers() {
        busy = true
        scope.launch {
            try {
                var m = Nexa.api.request("PATCH", "/servers/$serverId/members/$target", Member.serializer(), buildJsonObject {
                    put("bio", "")
                    put("pronouns", "")
                    put("accent_color", JsonNull)
                    put("accent_color_2", JsonNull)
                    put("widgets", JsonNull)
                })
                if (m.avatarId != null) m = Nexa.api.request("DELETE", "/servers/$serverId/members/$target/avatar", Member.serializer())
                if (m.bannerId != null) m = Nexa.api.request("DELETE", "/servers/$serverId/members/$target/banner", Member.serializer())
                Nexa.servers.upsertMember(m)
                Nexa.ui.toast(t("serverProfile.resetDone"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("serverProfile.uploadFailed")), "error")
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, title = t("modals.serverProfile.changeTheirNickname"), maxWidth = 440.dp, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            NxButton(t("chat.editMessage.cancel"), close, kind = ButtonKind.GHOST, enabled = !busy)
            NxButton(t("chat.editMessage.save"), ::save, loading = busy)
        }
    }) {
        NxTextField(
            nick, { nick = it.take(32) },
            label = t("modals.serverProfile.nickname"),
            hint = t("serverProfile.theirNickHint", "name" to serverName),
            placeholder = Nexa.users.get(target)?.displayName,
            imeAction = ImeAction.Done,
            onIme = ::save,
            capitalize = true,
        )
        if (mod) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogHint(t("serverProfile.othersResetHint"))
            NxButton(t("serverProfile.resetOthers"), ::resetOthers, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = busy)
        }
    }
}

// ---------------------------------------------------------------------------
// ServerNotificationsModal

private fun levels() = listOf(
    Triple("all", t("server.serverOverview.allMessages"), t("modals.serverNotifications.everyMessageInThisServerNotifies")),
    Triple("mentions", t("server.serverOverview.onlyMentions"), t("modals.serverNotifications.onlyMessagesThatMentionYouNotify")),
    Triple("none", t("modals.serverNotifications.nothing"), t("modals.serverNotifications.noNotificationsFromThisServerBut")),
)

/** Copies the notifications settings with one change, as the web patches them. */
private fun patchNotifications(change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
    val now = LinkedHashMap(Nexa.settings.notifications)
    change(now)
    Nexa.settings.set("notifications", JsonObject(now))
}

@Composable
internal fun ServerNotificationsSheet(layer: Layer, serverId: String, close: () -> Unit) {
    val doc by Nexa.settings.doc.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    val st = servers[serverId]
    val notes = doc["notifications"] as? JsonObject ?: Nexa.settings.notifications
    val muted = (notes["muted"] as? kotlinx.serialization.json.JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == serverId } == true
    val level = ((notes["perChannel"] as? JsonObject)?.get(serverId) as? JsonPrimitive)?.contentOrNull
        ?: if (st?.server?.defaultNotifications == 1) "mentions" else "all"
    val name = st?.server?.name ?: ""
    Sheet(layer, title = t("modals.serverNotifications.notificationSettings"), maxWidth = 440.dp, footer = {
        NxButton(t("modals.serverNotifications.done"), close, block = true)
    }) {
        DialogHint(name)
        ToggleRow(muted, { on ->
            patchNotifications { n ->
                val list = (n["muted"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toMutableList() ?: ArrayList()
                if (on) { if (serverId !in list) list += serverId } else list.remove(serverId)
                n["muted"] = kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) })
            }
        }, t("modals.serverNotifications.muteName", "name" to name), t("modals.serverNotifications.mutingAServerPreventsUnreadIndicators"))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((v, label, desc) in levels()) ChoiceCard(level == v, label, desc, radioFirst = true) {
                patchNotifications { n ->
                    val per = LinkedHashMap((n["perChannel"] as? JsonObject) ?: JsonObject(emptyMap()))
                    per[serverId] = JsonPrimitive(v)
                    n["perChannel"] = JsonObject(per)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CreateChannelModal and CreateCategoryModal

/** A text channel's name as it is typed: lower case, a dash for a space, as the server stores it. */
fun typedChannelName(value: String): String = value

/** The finished name: as typed, without dashes left hanging at either end. */
fun channelSlug(name: String): String = name.trim().replace(Regex("\\s+"), " ")

@Composable
internal fun CreateChannelSheet(layer: Layer, serverId: String, parentId: String?, close: () -> Unit) {
    val parent = parentId?.let { Nexa.channels.get(it) }
    var type by remember { mutableStateOf("text") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var urlProblem by remember { mutableStateOf<String?>(null) }
    var private by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // voice and link names keep their capitals and spaces; the rest are written as text channels' are
    val freeName = type == "voice" || type == "link"
    val link = type == "link"
    fun create() {
        val clean = if (!freeName) channelSlug(name) else name.trim()
        if (clean.isEmpty() || busy) return
        // a link channel opens its address, so it has to be one the apps will open
        val dest = if (link) fish.nexa.ui.shell.LinkChannels.destination(url) else null
        if (link && dest == null) {
            urlProblem = t("channelLink.badAddress")
            return
        }
        busy = true
        scope.launch {
            var made: Channel? = null
            try {
                val raw = Nexa.api.request("POST", "/servers/$serverId/channels", buildJsonObject {
                    put("type", type)
                    put("name", clean)
                    if (parentId != null) put("parent_id", parentId)
                    if (dest != null) put("url", dest.href)
                }) as JsonObject
                val res = fish.nexa.net.wireJson.decodeFromJsonElement(Channel.serializer(), raw)
                made = res
                Nexa.channels.upsert(listOf(res))
                // made in a private category, it starts private like it: its key is made now
                if (((raw["key_actions"] as? JsonObject)?.get("needs_own_key") as? JsonPrimitive)?.contentOrNull == "true") {
                    Nexa.keys.ensureConvKey(res.id)
                    refreshChannels(serverId)
                }
                if (private && !link) {
                    // @everyone may not see it, and it gets its own key, so members outside it never hold one
                    val ow = Nexa.api.request("PUT", "/channels/${res.id}/permissions/$serverId", buildJsonObject {
                        put("type", "role")
                        put("allow", "0")
                        put("deny", P.VIEW_CHANNEL.toString())
                    }) as? JsonObject
                    refreshChannels(serverId)
                    val needsKey = ((ow?.get("key_actions") as? JsonObject)?.get("needs_own_key") as? JsonPrimitive)?.contentOrNull == "true"
                    if (needsKey) Nexa.keys.ensureConvKey(res.id)
                    refreshChannels(serverId)
                }
                close()
                // a link channel has no page of its own: it waits in the list to be opened
                if (!link) Nav.go(Route.Server(serverId, res.id))
            } catch (e: Exception) {
                val ch = made
                if (ch != null && private) {
                    // it may be open to everyone or lack its own key: taken back down, so nothing is posted where all can read
                    val gone = runCatching { Nexa.api.request("DELETE", "/channels/${ch.id}") }.isSuccess
                    if (gone) Nexa.channels.remove(ch.id)
                    else {
                        close()
                        Nav.go(Route.Server(serverId, ch.id))
                        Nexa.ui.toast(t("modals.createChannel.theChannelWasCreatedButMay"), "error")
                        return@launch
                    }
                }
                Nexa.ui.toast(describeError(e, t("modals.createChannel.couldNotCreateTheChannel")), "error")
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, title = t("modals.createChannel.createChannel"), maxWidth = 460.dp, footer = {
        FooterButtons(t("modals.createChannel.createChannel"), ::create, close, enabled = name.isNotBlank() && (!link || url.isNotBlank()), busy = busy)
    }) {
        if (parent != null) DialogHint(t("modals.createChannel.inCategory", "name" to parent.name))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(t("modals.createChannel.channelType"), style = Nx.type.label, color = Nx.colors.textSubtle)
            ChoiceCard(type == "text", t("modals.createChannel.text"), t("modals.createChannel.sendMessagesImagesGifsEmojiOpinions"), icon = "Hash") {
                type = "text"
                name = typedChannelName(name)
            }
            ChoiceCard(type == "voice", t("modals.createChannel.voice"), t("modals.createChannel.hangOutTogetherWithVoiceVideo"), icon = "Volume2") { type = "voice" }
            ChoiceCard(type == "announcement", t("modals.createChannel.announcement"), t("modals.createChannel.announcementDesc"), icon = "Megaphone") {
                type = "announcement"
                name = typedChannelName(name)
            }
            ChoiceCard(type == "forum", t("modals.createChannel.forum"), t("modals.createChannel.forumDesc"), icon = "MessagesSquare") {
                type = "forum"
                name = typedChannelName(name)
            }
            ChoiceCard(type == "media", t("modals.createChannel.media"), t("modals.createChannel.mediaDesc"), icon = "Images") {
                type = "media"
                name = typedChannelName(name)
            }
            ChoiceCard(type == "link", t("modals.createChannel.link"), t("modals.createChannel.linkDesc"), icon = "Link2") { type = "link" }
        }
        NxTextField(
            name, { v -> name = (if (!freeName) typedChannelName(v) else v).take(100) },
            label = t("modals.createChannel.channelName"),
            required = true,
            placeholder = if (link) t("channelLink.namePlaceholder") else t("modals.createChannel.namePlaceholder"),
            imeAction = if (link) ImeAction.Next else ImeAction.Done,
            onIme = if (link) null else ::create,
            capitalize = freeName,
        )
        if (link) NxTextField(
            url, { v -> url = v.take(1024); urlProblem = null },
            label = t("channelLink.address"),
            required = true,
            placeholder = "https://",
            hint = t("channelLink.addressHint"),
            error = urlProblem,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onIme = ::create,
        )
        if (!link) ToggleRow(private, { private = it }, t("modals.createChannel.privateChannel"), t("modals.createChannel.onlySelectedMembersAndRolesWill"))
        if (private && !link) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon("Lock", Nx.colors.textMuted, size = 12.dp)
            Txt(t("modals.createChannel.addRolesOrMembersFromThe"), style = Nx.type.hint, color = Nx.colors.textMuted)
        }
    }
}

private suspend fun refreshChannels(serverId: String) {
    val list = Nexa.api.request("GET", "/servers/$serverId/channels", ListSerializer(Channel.serializer()))
    Nexa.channels.upsert(list)
}

@Composable
internal fun CreateCategorySheet(layer: Layer, serverId: String, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun create() {
        if (name.isBlank() || busy) return
        busy = true
        scope.launch {
            try {
                val ch = Nexa.api.request("POST", "/servers/$serverId/channels", Channel.serializer(), buildJsonObject {
                    put("type", "category")
                    put("name", name.trim())
                })
                Nexa.channels.upsert(listOf(ch))
                close()
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("modals.createCategory.couldNotCreateTheCategory")), "error")
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, title = t("modals.createCategory.createCategory"), maxWidth = 440.dp, footer = {
        FooterButtons(t("modals.createCategory.createCategory"), ::create, close, enabled = name.isNotBlank(), busy = busy)
    }) {
        NxTextField(name, { name = it.take(100) }, label = t("modals.createCategory.categoryName"), required = true, placeholder = t("modals.createCategory.newCategory"), imeAction = ImeAction.Done, onIme = ::create, capitalize = true)
    }
}

// ---------------------------------------------------------------------------
// ChannelTopicModal

@Composable
internal fun ChannelTopicSheet(layer: Layer, channelId: String, close: () -> Unit) {
    val channels by Nexa.channels.byId.collectAsState()
    val ch = channels[channelId] ?: return
    Sheet(layer, title = (if (ch.isVoice) "" else "#") + ch.name, maxWidth = 480.dp, footer = {
        NxButton(t("chat.attachments.close"), close, block = true)
    }) {
        fish.nexa.ui.markdown.MarkdownView(ch.topic, fish.nexa.ui.markdown.RenderCtx(serverId = ch.serverId, surface = Nx.colors.bgFloating), Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }
}
