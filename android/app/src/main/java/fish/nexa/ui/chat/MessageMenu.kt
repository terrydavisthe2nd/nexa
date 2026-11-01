package fish.nexa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ChatMessage
import fish.nexa.data.EmojiUse
import fish.nexa.data.P
import fish.nexa.data.ReadMark
import fish.nexa.data.Send
import fish.nexa.net.ApiError
import fish.nexa.net.Message
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.media.ImageTarget
import fish.nexa.ui.media.imageMenuItems
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.launch

/** lib/mentionsMe.ts: you, everyone, or a role you hold in that server. */
fun mentionsMe(m: Message, meId: String): Boolean {
    if (m.mentionEveryone || meId in m.mentions) return true
    if (m.mentionRoles.isEmpty()) return false
    val sid = Nexa.channels.get(m.channelId)?.serverId ?: return false
    val mine = Nexa.servers.get(sid)?.me?.roleIds ?: return false
    return m.mentionRoles.any { it in mine }
}

fun canDelete(m: Message): Boolean {
    val me = Nexa.users.me.value ?: return false
    if (m.authorId == me.id) return true
    if (Nexa.servers.isInstanceAdmin(me.id)) return true
    val ch = Nexa.channels.get(m.channelId) ?: return false
    if (ch.serverId != null) return Nexa.servers.canI(Nexa.servers.get(ch.serverId), P.MANAGE_MESSAGES, ch.id)
    return ch.type == "group_dm" && ch.ownerId == me.id
}

fun canEdit(cm: ChatMessage): Boolean {
    val me = Nexa.users.me.value ?: return false
    return cm.authorId == me.id && (cm.m.type == "default" || cm.m.type == "reply") && cm.payload != null && cm.payload.poll == null
}

fun canPin(m: Message): Boolean {
    val ch = Nexa.channels.get(m.channelId) ?: return true
    val sid = ch.serverId ?: return true
    return Nexa.servers.canI(Nexa.servers.get(sid), P.PIN_MESSAGES, ch.id)
}

/** Whether a thread can be started from a message: something someone said, in a channel threads start in, that has none yet. */
fun canThread(cm: ChatMessage): Boolean {
    val m = cm.m
    if (m.thread != null || (m.type != "default" && m.type != "reply" && m.type != "crosspost")) return false
    val ch = Nexa.channels.get(m.channelId) ?: return false
    return !ch.isThread && fish.nexa.ui.threads.ThreadsUi.canStart(ch)
}

/** Whether a post of an announcement channel has gone out to the channels following it. */
fun isPublished(m: Message): Boolean = m.published || m.flags and Message.MSG_FLAG_PUBLISHED != 0L

/**
 * Whether this account may publish a message: one written in an announcement
 * channel, not yet published, by this account (who can send there) or by
 * anyone when this account may manage messages there.
 */
fun canPublish(cm: ChatMessage): Boolean {
    val m = cm.m
    if ((m.type != "default" && m.type != "reply") || cm.payload == null || isPublished(m)) return false
    val ch = Nexa.channels.get(m.channelId) ?: return false
    val sid = ch.serverId ?: return false
    if (!ch.isAnnouncement) return false
    val st = Nexa.servers.get(sid)
    val me = Nexa.users.meId ?: return false
    if (m.authorId == me && Nexa.servers.canI(st, P.SEND_MESSAGES, ch.id)) return true
    return Nexa.servers.canI(st, P.MANAGE_MESSAGES, ch.id)
}

/** Publishes a post to every channel following its channel, each copy sealed to that follow. */
fun publish(cm: ChatMessage) {
    val payload = cm.payload ?: return
    Nexa.scope.launch {
        try {
            val copies = fish.nexa.data.Follows.publish(cm.channelId, cm.id, payload)
            Nexa.messages.setFlags(cm.channelId, cm.id, cm.m.flags or Message.MSG_FLAG_PUBLISHED)
            Nexa.ui.toast(if (copies == 0) t("follows.publishedNone") else t("follows.publishedTo", "count" to copies), "success")
        } catch (e: Exception) {
            Nexa.ui.toast(if ((e as? ApiError)?.code == "publish_limit") t("follows.publishLimit") else (e as? ApiError)?.message ?: t("follows.publishFailed"), "error")
        }
    }
}

/** An edit to a published post goes out to its copies too. */
fun republish(channelId: String, messageId: String, payload: fish.nexa.crypto.MessagePayload) {
    Nexa.scope.launch {
        try {
            fish.nexa.data.Follows.publish(channelId, messageId, payload)
        } catch (_: Exception) {
            Nexa.ui.toast(t("follows.republishFailed"), "error")
        }
    }
}

/** The exact moments, which a phone has no tooltip for. */
fun messageTimes(m: Message): List<String> {
    val out = ArrayList<String>()
    if (m.createdAt > 0) out += t("chat.messageMenu.sentAt", "time" to Time.exact(m.createdAt))
    m.editedAt?.let { out += t("chat.messageMenu.editedAt", "time" to Time.exact(it)) }
    return out
}

private fun copy(text: String, toast: String) {
    val cm = Nexa.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Nexa", text))
    // Android 13 and later show their own copied notice
    if (android.os.Build.VERSION.SDK_INT < 33) Nexa.ui.toast(toast)
}

fun messageLink(m: Message): String {
    val ch = Nexa.channels.get(m.channelId)
    val base = if (ch?.serverId != null) "/channels/${ch.serverId}" else "/channels/@me"
    return "${Nexa.api.baseUrl}$base/${m.channelId}/${m.id}"
}

fun confirmDelete(cm: ChatMessage, deleted: (() -> Unit)? = null) {
    confirm(t("chat.messageMenu.deleteMessage"), t("chat.messageMenu.areYouSureYouWantTo"), t("chat.messageItem.delete")) {
        try {
            Send.delete(cm.channelId, cm.id)
            deleted?.invoke()
        } catch (e: Exception) {
            Nexa.ui.toast((e as? ApiError)?.message ?: t("chat.messageMenu.couldNotDelete"), "error")
        }
    }
}

/** Mark Unread: the marker goes back to just before this message, with the mentions from here counted again. */
fun markUnread(cm: ChatMessage) {
    val ch = Nexa.messages.get(cm.channelId)
    val ids = ch.ids
    val idx = ids.indexOf(cm.id)
    val prev = if (idx > 0) ids[idx - 1] else "0"
    val meId = Nexa.users.meId ?: return
    var mentions = 0
    if (idx >= 0) for (id in ids.subList(idx, ids.size)) {
        val m = ch.byId[id]?.m ?: continue
        if (m.authorId != meId && mentionsMe(m, meId)) mentions += 1
    }
    Nexa.readStates.setLocal(cm.channelId, ReadMark(lastReadId = if (prev == "0") null else prev, mentions = mentions, unread = 0, manual = true))
    Nexa.scope.launch {
        if (prev != "0") runCatching { Nexa.session.ack(cm.channelId, prev) }
        runCatching { Nexa.session.ack(cm.channelId, prev, manual = true) }
    }
}

/**
 * messageMenu.ts buildMessageMenu as a text-only sheet, with a row of quick
 * reactions above it (a phone's addition: the six emoji you use most).
 */
fun openMessageMenu(cm: ChatMessage, ctx: ListCtx, image: ImageTarget? = null) {
    if (cm.m.type.startsWith("system_")) return
    val items = ArrayList<MenuItem>()
    if (!cm.local) items += fish.nexa.ui.reports.reportItem(fish.nexa.data.ReportContext(kind = "message", id = cm.id, evidence = fish.nexa.data.Reports.evidence(cm)))
    // a media channel that hides downloads offers neither for its pictures
    image?.takeIf { !fish.nexa.data.MediaDownloads.hidden(cm.m.channelId) }?.let { items += imageMenuItems(it) }
    val canReactHere = canReact(cm, ctx)
    if (canReactHere) items += MenuItem.Action(t("chat.messageItem.addReaction"), separatorAfter = true) { openReactionPicker(cm, ctx) }
    if (canEdit(cm)) items += MenuItem.Action(t("chat.messageItem.editMessage")) {
        ChatUi.editing[cm.channelId] = ChatUi.Edit(cm.id, fish.nexa.data.Mentions.humanizeForEdit(cm.payload?.t ?: "", cm.channelId))
    }
    val threadable = canThread(cm)
    val publishable = canPublish(cm)
    val forwardable = fish.nexa.data.Forward.can(cm)
    items += MenuItem.Action(t("chat.messageItem.reply"), separatorAfter = !forwardable && !threadable && !publishable) { ChatUi.reply[cm.channelId] = ChatUi.Reply(cm.id, ping = true) }
    if (forwardable) items += MenuItem.Action(t("forward.menu"), separatorAfter = !threadable && !publishable) { openForward(cm) }
    if (threadable) items += MenuItem.Action(t("threads.create.title"), separatorAfter = !publishable) { fish.nexa.ui.threads.openCreateThread(cm.channelId, cm.id) }
    if (publishable) items += MenuItem.Action(t("follows.publish"), separatorAfter = true) { publish(cm) }
    items += MenuItem.Action(t("chat.messageMenu.copyText"), disabled = cm.payload?.t.isNullOrEmpty() && cm.payload?.fwdComment.isNullOrEmpty()) {
        copy(fish.nexa.data.Mentions.humanize(listOfNotNull(cm.payload?.fwdComment, cm.payload?.t).joinToString("\n")), t("clipboard.textCopied"))
    }
    if (canPin(cm.m)) items += MenuItem.Action(if (cm.m.pinned) t("chat.messageItem.unpinMessage") else t("chat.messageItem.pinMessage")) {
        Nexa.scope.launch {
            try {
                Send.setPinned(cm.channelId, cm.id, !cm.m.pinned)
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("chat.messageMenu.couldNotUpdateThePin"), "error")
            }
        }
    }
    items += MenuItem.Action(t("chat.messageItem.markUnread")) { markUnread(cm) }
    val hasEmbeds = !cm.payload?.embeds.isNullOrEmpty() || fish.nexa.data.Webhooks.content(cm.hook)?.linkEmbeds?.isNotEmpty() == true
    if (hasEmbeds && canDelete(cm.m)) {
        val hidden = cm.m.suppressEmbeds
        items += MenuItem.Action(if (hidden) t("chat.messageMenu.showEmbeds") else t("chat.messageMenu.removeEmbeds")) {
            Nexa.scope.launch {
                runCatching { Send.setEmbedsHidden(cm.channelId, cm.id, !hidden) }.onFailure { Nexa.ui.toast(t("chat.messageMenu.couldNotUpdateEmbeds"), "error") }
            }
        }
    }
    val dev = Nexa.settings.developerMode
    val deletable = canDelete(cm.m)
    // what apps can do with a message, their message commands
    val apps = fish.nexa.ui.apps.messageAppsItem(cm)
    items += MenuItem.Action(t("chat.messageItem.copyMessageLink"), separatorAfter = apps != null || dev || deletable) { copy(messageLink(cm.m), t("chat.messageMenu.linkCopied")) }
    if (apps != null) items += apps.copy(separatorAfter = dev || deletable)
    if (dev) items += MenuItem.Action(t("chat.messageItem.copyMessageId"), separatorAfter = deletable) { copy(cm.id, t("clipboard.idCopied")) }
    if (deletable) {
        items += MenuItem.Action(t("chat.messageMenu.selectMessages")) { ChatUi.startSelecting(cm.channelId, cm.id) }
        items += MenuItem.Action(t("chat.messageMenu.deleteMessage"), danger = true) { confirmDelete(cm) }
    }
    val times = messageTimes(cm.m)
    if (times.isNotEmpty()) {
        val last = items.removeAt(items.lastIndex)
        items += when (last) {
            is MenuItem.Action -> last.copy(separatorAfter = true)
            is MenuItem.Submenu -> last.copy(separatorAfter = true)
            else -> last
        }
        for (line in times) items += MenuItem.Note(line)
    }
    ChatUi.menuFor = cm.id
    openMenu(
        items,
        title = if (cm.m.editedAt != null) times.joinToString(", ") else null,
        header = if (canReactHere) { close -> QuickReactions(cm, ctx, close) } else null,
        onClosed = { if (ChatUi.menuFor == cm.id) ChatUi.menuFor = null },
        haptic = false,
    )
}

/** The emoji a phone offers at the top of the message menu: your most used, then the usual few. */
fun quickReactionKeys(): List<String> {
    val starter = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
    val mine = EmojiUse.frequent(6)
    return (mine + starter).distinct().take(6)
}

@Composable
private fun QuickReactions(cm: ChatMessage, ctx: ListCtx, close: () -> Unit) {
    val c = Nx.colors
    val mine = cm.m.reactions.filter { it.me }.map { it.emoji }.toSet()
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in quickReactionKeys()) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val on = key in mine
            Box(
                Modifier
                    .size(46.dp)
                    .graphicsLayer { val s = if (pressed) 0.9f else 1f; scaleX = s; scaleY = s }
                    .background(if (on) c.brand.copy(alpha = 0.22f) else c.bgSurface, CircleShape)
                    .pressable(interaction) {
                        close()
                        Send.react(ctx.channel.id, cm.id, key, add = !on)
                    }
                    .semantics { contentDescription = t("chat.reactions.reactWith", "emoji" to emojiLabel(key)) },
                contentAlignment = Alignment.Center,
            ) { EmojiGlyph(key, 26.dp) }
        }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(46.dp)
                .background(c.bgSurface, CircleShape)
                .pressable(interaction) {
                    close()
                    openReactionPicker(cm, ctx)
                }
                .semantics { contentDescription = t("chat.reactions.addReaction") },
            contentAlignment = Alignment.Center,
        ) { Icon("SmilePlus", c.icon, size = 22.dp) }
    }
}
