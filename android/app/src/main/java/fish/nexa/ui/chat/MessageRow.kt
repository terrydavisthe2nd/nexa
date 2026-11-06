package fish.nexa.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ChatMessage
import fish.nexa.data.DecryptFail
import fish.nexa.data.P
import fish.nexa.data.PendingMessage
import fish.nexa.data.Send
import fish.nexa.data.ServerState
import fish.nexa.net.Channel
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.media.Unfurl
import fish.nexa.media.isOnlyMediaLinks
import fish.nexa.ui.media.Attachments
import fish.nexa.ui.media.Embeds
import fish.nexa.ui.media.HookBody
import fish.nexa.ui.media.PressedImage
import fish.nexa.ui.media.UploadCard
import fish.nexa.ui.shell.Names
import fish.nexa.ui.shell.openProfile
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import kotlinx.coroutines.delay

/** What every row in one conversation shares. */
data class ListCtx(
    val channel: Channel,
    val server: ServerState?,
    val compact: Boolean,
    val selecting: Boolean,
    val meId: String,
)

/** A message or a pending send, as the list lays it out. */
data class MsgItem(
    val key: String,
    val cm: ChatMessage? = null,
    val pending: PendingMessage? = null,
    val groupStart: Boolean,
    val mentionsMe: Boolean = false,
) {
    val authorId get() = cm?.authorId ?: pending!!.authorId
    val createdAt get() = cm?.m?.createdAt ?: pending!!.createdAt
    val replyToId get() = cm?.m?.replyToId ?: pending?.replyToId
}

/**
 * MessageItem.tsx on a phone: a flat row edge to edge, the avatar in a 64 dp
 * gutter on the first row of a group, then the name, tag and time, then what
 * was said. Every action is in the long-press menu; swiping it against the
 * reading direction replies.
 */
@Composable
fun MessageRow(item: MsgItem, ctx: ListCtx, flashSeq: Int?, picked: Boolean, canPick: Boolean, preview: Boolean = false, previewAuthor: fish.nexa.data.NexaPerson? = null, evidence: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    val cm = item.cm
    val p = item.pending
    val reply = item.replyToId
    // an app's answer to a command says who used it, on the line a reply's quote takes
    val appHook = fish.nexa.ui.markdown.Webhook.of(cm?.hook)?.takeIf { it.applicationId != null }
    val command = fish.nexa.ui.apps.usedCommand(appHook?.interaction)
    // an answer only you see is nobody else's to react to or reply to: it has no menu
    val ephemeral = cm?.m?.ephemeral == true
    val start = item.groupStart || reply != null || command != null
    val showHead = start && !ctx.compact || (ctx.compact && start)
    val failed = p?.status == "failed"
    val uploading = p?.upload != null && p.upload.done.not()
    val swipe = rememberRowSwipe()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val menuOpen = cm != null && ChatUi.menuFor == cm.id

    // jump-to flash: the accent fading out over two seconds (a steady tint with reduced motion)
    val flash = remember { Animatable(0f) }
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    LaunchedEffect(flashSeq) {
        if (flashSeq == null) return@LaunchedEffect
        if (reduced) {
            flash.snapTo(1f); delay(2000); flash.snapTo(0f)
        } else {
            flash.snapTo(1f); flash.animateTo(0f, tween(2000, easing = ease))
        }
    }
    val tint = when {
        picked -> c.bgSelected
        menuOpen -> c.bgMessageHover
        item.mentionsMe -> c.brand.copy(alpha = 0.09f)
        // an answer only you see: a pane of its own, lit faintly in the accent, unlike a mention's bar
        ephemeral -> c.textBrand.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    val flashColor = if (reduced) c.mentionBg else c.brand.copy(alpha = 0.40f)

    val startPad = when {
        ctx.selecting && ctx.compact -> 48.dp
        ctx.selecting -> 98.dp
        ctx.compact -> 16.dp
        else -> 64.dp
    }
    val avatarStart = if (ctx.selecting) 46.dp else 12.dp
    val label = if (!start && cm != null) t("chat.messageItem.whoWhen", "name" to Names.member(ctx.server?.server?.id, item.authorId), "time" to Time.timestamp(item.createdAt)) else null

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = if (start && !ctx.compact) 17.dp else 0.dp)
            .then(if (ephemeral) Modifier.padding(vertical = 2.dp).border(1.dp, c.textBrand.copy(alpha = 0.15f)) else Modifier)
            .background(tint)
            .then(if (flash.value > 0f) Modifier.background(flashColor.copy(alpha = flashColor.alpha * flash.value)) else Modifier)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .then(
                if (ctx.selecting && canPick) Modifier.pressable(remember { MutableInteractionSource() }) { ChatUi.togglePick(cm!!.id) }
                else Modifier.rowTouch(
                    enabled = !preview && cm != null && !ctx.selecting && !ephemeral,
                    canReply = cm != null && canSend(ctx),
                    swipe = swipe,
                    onLongPress = {
                        cm?.let {
                            // on someone's picture or name, the menu is theirs
                            val who = PressedPerson.take()
                            if (who == null || !openPersonMenu(ctx.channel.id, who)) openMessageMenu(it, ctx, PressedImage.take())
                        }
                    },
                    onReply = { cm?.let { ChatUi.reply[ctx.channel.id] = ChatUi.Reply(it.id, ping = it.authorId != ctx.meId) } },
                ),
            ),
    ) {
        if (item.mentionsMe) MentionBar()
        // the reply arrow that shows as the row is pulled
        if (swipe.offset.value != 0f) {
            val pulled = kotlin.math.abs(swipe.offset.value) / (REPLY_AT_DP * density)
            Icon(
                "Reply", if (pulled >= 1f) c.textBrand else c.textMuted, size = 20.dp,
                modifier = Modifier
                    .align(if (rtl) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 18.dp)
                    .graphicsLayer {
                        alpha = pulled.coerceIn(0f, 1f)
                        val s = 0.6f + 0.4f * pulled.coerceIn(0f, 1f)
                        scaleX = s; scaleY = s
                    },
            )
        }
        Box(Modifier.graphicsLayer { translationX = swipe.offset.value }) {
            if (ctx.selecting) PickCircle(picked, canPick, top = if (reply != null || command != null) 35.dp else if (start) 11.dp else 3.dp)
            if (start && !ctx.compact) {
                // a webhook's message goes by the name and picture it was sent with, and a tap says what the sender is
                val hook = fish.nexa.ui.markdown.Webhook.of(item.cm?.hook)
                val hookId = item.cm?.m?.webhookId
                val authorName = Names.member(ctx.server?.server?.id, item.authorId)
                val user = if (item.authorId == ctx.meId) Nexa.users.me.value?.let { fish.nexa.net.PublicUser(it.id, it.username, it.displayName, it.avatarId) } else Nexa.users.get(item.authorId)
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Box(
                    Modifier
                        .padding(start = avatarStart, top = if (reply != null || command != null) 26.dp else 2.dp)
                        .graphicsLayer { val s = if (pressed) 0.95f else 1f; scaleX = s; scaleY = s }
                        .claimsPerson(personOf(item, ctx, hookId != null && hook != null))
                        .pressable(interaction) { if (hook != null && hookId != null) fish.nexa.ui.media.openHookCard(hook, hookId) else openProfile(item.authorId) },
                ) {
                    val copy = item.cm?.m?.crosspost?.takeIf { item.cm.m.type == "crosspost" }
                    when {
                        copy != null -> Avatar(copy.serverId, copy.serverName, copy.serverIconId, 40.dp, icon = true)
                        hook != null && hookId != null -> fish.nexa.ui.media.HookAvatar(hook, hookId, 40.dp)
                        else -> {
                            // in a server, the picture someone set for it
                            val own = ctx.server?.members?.get(item.authorId)?.avatarId
                            Avatar(item.authorId, previewAuthor?.displayName?.ifEmpty { previewAuthor.username } ?: authorName, previewAuthor?.avatarId ?: own ?: user?.avatarId, 40.dp)
                        }
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = startPad, end = 16.dp, top = 2.dp, bottom = 2.dp)
                    .heightIn(min = 22.dp)
                    .alpha(if (p != null && !failed) (if (uploading) 0.8f else 0.55f) else 1f),
            ) {
                if (command != null && cm != null) fish.nexa.ui.apps.InteractionLine(cm, command, ctx)
                else if (reply != null) ReplyLine(ctx, reply, cm?.m)
                if (showHead) MessageHeader(item, ctx, previewAuthor)
                (cm?.payload ?: p?.payload)?.fwdComment?.let { fish.nexa.ui.markdown.MarkdownView(it, fish.nexa.ui.markdown.RenderCtx(serverId = ctx.server?.server?.id, surface = c.bgRaised)) }
                val fwdAt = (cm?.payload ?: p?.payload)?.fwdAt
                if (fwdAt != null) Forwarded(fwdAt) { MessageBody(item, ctx, failed, preview) }
                else MessageBody(item, ctx, failed, preview)
                evidence?.invoke()
                if (failed && p != null) FailedRow(ctx.channel.id, p.tmpId)
                val sid = ctx.server?.server?.id
                // an announcement not yet sent on to the channels following this one
                if (cm != null && canPublish(cm)) PublishButton { publish(cm) }
                if (cm != null && !ephemeral) ReactionsRow(cm, ctx)
                if (cm != null && ephemeral) fish.nexa.ui.apps.OnlyYou(cm)
                // the thread it started, under everything else, its line from the avatar (or the row's top)
                cm?.m?.thread?.let { th ->
                    if (sid != null) fish.nexa.ui.threads.ThreadChip(sid, th, spineFrom = when {
                        ctx.compact || ctx.selecting -> null
                        start && reply != null -> 68.dp
                        start -> 44.dp
                        else -> 2.dp
                    })
                }
            }
        }
    }
}

/** A mention of you: a 3 dp bar of the focus colour on the start edge, glowing. */
@Composable
private fun BoxScope.MentionBar() {
    val c = Nx.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .matchParentSize()
            .padding(vertical = 3.dp)
            .wrapContentWidth(Alignment.Start)
            .width(3.dp)
            .boxShadow(shape, 0f, 8f, c.focusRing.copy(alpha = 0.6f))
            .background(c.focusRing, shape),
    )
}

/** canSend: DMs always; servers need Send Messages. */
fun canSend(ctx: ListCtx): Boolean {
    val st = ctx.server ?: return true
    return Nexa.servers.canI(st, fish.nexa.data.P.SEND_MESSAGES, ctx.channel.id)
}

@Composable
private fun PickCircle(picked: Boolean, enabled: Boolean, top: androidx.compose.ui.unit.Dp) {
    val c = Nx.colors
    if (!enabled) return
    Box(
        Modifier
            .padding(start = 12.dp, top = top)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (picked) c.brand else Color.Transparent)
            .border(2.dp, if (picked) c.brand else c.borderStrong, CircleShape)
            .semantics { contentDescription = if (picked) t("chat.messageItem.unselectMessage") else t("chat.messageItem.selectMessage") },
        contentAlignment = Alignment.Center,
    ) {
        if (picked) Icon("Check", c.onBrand, size = 14.dp)
    }
}

/** HeaderTime: the time beside the name; a tap shows the exact second for four seconds. */
@Composable
private fun HeaderTime(ms: Long) {
    val c = Nx.colors
    var exact by remember { mutableStateOf(false) }
    LaunchedEffect(exact) {
        if (exact) {
            delay(4000); exact = false
        }
    }
    Txt(
        if (exact) Time.exact(ms) else Time.timestamp(ms),
        style = Nx.type.timestamp.copy(fontWeight = FontWeight(500)),
        color = if (exact) c.text else c.textMuted,
        maxLines = 1,
        modifier = Modifier.pressable(remember { MutableInteractionSource() }) { exact = true },
    )
}

/**
 * Who a long press on a message's picture or name is about, so the row opens
 * their menu, as the member list does (openPersonMenu); null, leaving the
 * message's own menu, for anyone who is not a person here (a webhook, a
 * published copy) and for a message not sent yet or one only you see.
 */
private fun personOf(item: MsgItem, ctx: ListCtx, byHook: Boolean): String? {
    val cm = item.cm ?: return null
    if (byHook || cm.m.ephemeral || cm.m.type == "crosspost") return null
    return item.authorId
}

/**
 * The person under the finger while it is down, for the row's long press
 * (rowTouch, which sees the press first): PressedImage's way, for people.
 * Cleared when the finger lifts, so a tap leaves nothing for a later press.
 */
object PressedPerson {
    var userId: String? = null

    fun take(): String? = userId.also { userId = null }
}

private fun Modifier.claimsPerson(userId: String?): Modifier = if (userId == null) this else pointerInput(userId) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        PressedPerson.userId = userId
        waitForUpOrCancellation(PointerEventPass.Initial)
        PressedPerson.userId = null
    }
}

@Composable
private fun MessageHeader(item: MsgItem, ctx: ListCtx, previewAuthor: fish.nexa.data.NexaPerson? = null) {
    val c = Nx.colors
    val sid = ctx.server?.server?.id
    val hook = fish.nexa.ui.markdown.Webhook.of(item.cm?.hook)
    val hookId = item.cm?.m?.webhookId?.takeIf { hook != null }
    // a published post's copy speaks for a channel elsewhere, not for a person
    val copy = item.cm?.m?.crosspost?.takeIf { item.cm.m.type == "crosspost" }
    val name = when {
        previewAuthor != null -> previewAuthor.displayName.ifEmpty { previewAuthor.username }
        copy != null -> "${copy.serverName} #${copy.channelName}"
        hook != null && hookId != null -> hook.name
        else -> Names.member(sid, item.authorId)
    }
    // a webhook and a copy have no roles, so no role colours or icon
    val user = Nexa.users.get(item.authorId)
    val look = if (hookId != null || copy != null) null else fish.nexa.ui.chat.RoleLooks.withOwn(ctx.server?.let { fish.nexa.ui.chat.RoleLooks.of(it, item.authorId) }, user?.nameStyle)
    val open = {
        when {
            copy != null -> Unit
            hook != null && hookId != null -> fish.nexa.ui.media.openHookCard(hook, hookId)
            else -> openProfile(item.authorId)
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoleName(
            name,
            look,
            style = Nx.type.body.copy(fontWeight = FontWeight(650)),
            fallback = c.textStrong,
            modifier = Modifier.weight(1f, fill = false).claimsPerson(personOf(item, ctx, hookId != null)).pressable(remember { MutableInteractionSource() }, onClick = open),
        )
        look?.icon?.let { RoleIcon(it, 18.dp) }
        when {
            copy != null -> CopyTag()
            hook != null -> fish.nexa.ui.media.HookTags(open, app = hook.applicationId != null)
            else -> user?.serverTag?.takeIf { it.text.isNotEmpty() }?.let { ServerTagChip(it, small = false, open = true) }
        }
        HeaderTime(item.createdAt)
    }
}

/** `.publishBtn`: Publish, under a post of an announcement channel that has not gone out yet. */
@Composable
private fun PublishButton(onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .padding(top = 6.dp)
            .heightIn(min = 36.dp)
            .clip(Radii.Sm)
            .background(if (pressed) c.bgSelected else c.bgActive)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon("Megaphone", c.textStrong, size = 15.dp)
        Txt(t("follows.publish"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
    }
}

/** A published post's copy is marked as one: an announcement from a channel this one follows. */
@Composable
private fun CopyTag() {
    val c = Nx.colors
    Row(
        Modifier
            .background(c.brand.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .semantics { contentDescription = t("follows.tagHint") }
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon("Megaphone", c.textBrand, size = 11.dp)
        Txt(t("follows.tag"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(650)), color = c.textBrand, maxLines = 1)
    }
}

/**
 * The curved line from the avatar column up into the replied-to message, then
 * who and what. Most originals are not in the stretch that is loaded: the
 * server sends them along, and anything it did not is fetched (ReplyTargets).
 */
@Composable
private fun ReplyLine(ctx: ListCtx, replyId: String, msg: fish.nexa.net.Message?) {
    val c = Nx.colors
    val inList = Nexa.messages.get(ctx.channel.id).byId[replyId]
    val targets by fish.nexa.data.ReplyTargets.byId.collectAsState()
    val kept = if (inList == null) targets[replyId] else null
    val original = inList ?: (kept as? fish.nexa.data.ReplyTarget.Found)?.cm?.takeIf { it.channelId == ctx.channel.id }
    // a message on its way has no word from the server about its original
    val referenced = msg?.referenced ?: if (msg == null) fish.nexa.net.Message.NOT_SAID else null
    val deleted = original == null && (kept == fish.nexa.data.ReplyTarget.Deleted || (kept == null && referenced == null))
    val failed = original == null && kept == fish.nexa.data.ReplyTarget.Failed
    LaunchedEffect(replyId, inList == null, kept) {
        if (inList != null) return@LaunchedEffect
        if (kept is fish.nexa.data.ReplyTarget.Found) {
            fish.nexa.data.ReplyTargets.used(replyId)
            return@LaunchedEffect
        }
        if (kept != null || referenced == null) return@LaunchedEffect
        // sent along but not kept (yet, or any more): opened here, without asking anyone
        if (referenced != fish.nexa.net.Message.NOT_SAID && msg != null) fish.nexa.data.ReplyTargets.ingest(listOf(msg))
        else fish.nexa.data.ReplyTargets.request(ctx.channel.id, replyId)
    }
    ReplyFrame(
        when {
            // gone is gone: there is nothing to go to
            deleted -> Modifier
            failed -> Modifier.pressable(remember { MutableInteractionSource() }) { fish.nexa.data.ReplyTargets.retry(ctx.channel.id, replyId) }
                .semantics { contentDescription = t("chat.messageList.tryAgain") }
            else -> Modifier.pressable(remember { MutableInteractionSource() }) { ChatUi.jumpTo(ctx.channel.id, replyId) }
                .semantics { contentDescription = if (original == null) t("chat.messageItem.originalLoading") else t("chat.messageItem.goToOriginal") }
        },
    ) {
        if (deleted) {
            Txt(t("chat.messageItem.originalDeleted"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.textMuted, maxLines = 1)
        } else if (failed) {
            Icon("RotateCw", c.textMuted, size = 13.dp)
            Txt(t("chat.messageItem.originalFailed"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, maxLines = 1)
        } else if (original == null) {
            // waiting for it: a quiet bar where the quote goes
            Box(Modifier.size(16.dp).background(c.bgActive, CircleShape))
            Box(Modifier.size(width = 64.dp, height = 10.dp).background(c.bgActive, RoundedCornerShape(5.dp)))
            Box(Modifier.size(width = 140.dp, height = 10.dp).background(c.bgHover, RoundedCornerShape(5.dp)))
        } else {
            val blocked = Nexa.relationships.isBlocked(original.authorId)
            if (blocked) {
                Txt(t("people.blocked.replyPreview"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, maxLines = 1)
            } else {
                // a webhook's message goes by the name it was sent with; an app's, by its bot's
                val hook = fish.nexa.ui.markdown.Webhook.of(original.hook)
                val hookId = original.m.webhookId?.takeIf { hook != null }
                val who = if (hook != null && hookId != null) hook.name else Names.member(ctx.server?.server?.id, original.authorId)
                if (hook != null && hookId != null) fish.nexa.ui.media.HookAvatar(hook, hookId, 16.dp)
                else Avatar(original.authorId, who, ctx.server?.members?.get(original.authorId)?.avatarId ?: Nexa.users.get(original.authorId)?.avatarId, 16.dp)
                PersonName(
                    original.authorId.takeIf { hookId == null }, who, serverId = ctx.server?.server?.id, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(500)), color = c.text, maxLines = 1,
                    // a webhook has no profile; its message is where to go
                    // the person a reply quotes has the same menu as the one who wrote it
                    modifier = Modifier.widthIn(max = 160.dp).claimsPerson(original.authorId.takeIf { hookId == null }).pressable(remember { MutableInteractionSource() }) {
                        if (hookId != null) ChatUi.jumpTo(ctx.channel.id, replyId) else openProfile(original.authorId)
                    },
                )
                ReplyExcerpt(original, ctx.server?.server?.id, Modifier.weight(1f, fill = false))
            }
        }
    }
}

/**
 * The frame of the line over a message that points elsewhere (a reply's
 * quote, or who used an app's command): the curved line from the avatar
 * column up into it, then what it says, in a row of its own.
 */
@Composable
internal fun ReplyFrame(rowModifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val stroke = c.borderStrong
    Box(Modifier.fillMaxWidth().height(24.dp)) {
        // ::before: 32 wide, 15 tall, from 38 dp before the content, a 2 dp border on its start and top, 6 dp corner
        Canvas(Modifier.offset(x = if (rtl) 0.dp else (-38).dp, y = 11.dp).size(32.dp, 15.dp)) {
            val w = 2.dp.toPx()
            val r = 6.dp.toPx()
            val path = Path().apply {
                if (!rtl) {
                    moveTo(w / 2, size.height)
                    lineTo(w / 2, r)
                    quadraticTo(w / 2, w / 2, r, w / 2)
                    lineTo(size.width, w / 2)
                } else {
                    moveTo(size.width - w / 2, size.height)
                    lineTo(size.width - w / 2, r)
                    quadraticTo(size.width - w / 2, w / 2, size.width - r, w / 2)
                    lineTo(0f, w / 2)
                }
            }
            drawPath(path, stroke, style = Stroke(width = w))
        }
        Row(
            Modifier.fillMaxWidth().height(22.dp).then(rowModifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** ReplyWords as plain text: the original in one line, or what it holds when it has no words. */
fun replyWords(cm: ChatMessage): String {
    if (cm.error != null) return t("chat.messageItem.encryptedMessage")
    val p = cm.payload
    p?.poll?.let { poll -> return (listOf(poll.question) + poll.answers.map { it.text }).joinToString(" ") }
    val text = p?.t?.takeIf { it.isNotBlank() }
    if (text != null) return fish.nexa.ui.markdown.Markdown.plainText(fish.nexa.ui.markdown.Markdown.parse(text, inline = true), AppNames).replace(Regex("\\s+"), " ").trim()
    // a message with nothing in it (a thread started without one) says nothing, as on the web
    return if (!p?.att.isNullOrEmpty() || p?.sticker != null) t("chat.messageItem.tapToSeeAttachment") else ""
}

/** ReplyWords: the original in one line with its formatting kept but inert (spoilers stay covered). */
@Composable
fun ReplyExcerpt(cm: ChatMessage, serverId: String?, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f))
    val text = cm.payload?.t?.takeIf { it.isNotBlank() && cm.error == null && cm.payload.poll == null }
    if (text != null) fish.nexa.ui.markdown.MarkdownLine(text, c.textMuted, style, modifier, serverId)
    else replyWords(cm).takeIf { it.isNotEmpty() }?.let { Txt(it, style = style, color = c.textMuted, maxLines = 1, modifier = modifier) }
}

/** Names as the app knows them, for plain-text versions of messages (copying, notifications, previews). */
object AppNames : fish.nexa.ui.markdown.NameLookup {
    override fun user(id: String): fish.nexa.ui.markdown.UserName? {
        val u = Nexa.users.get(id) ?: Nexa.users.me.value?.takeIf { it.id == id }?.let { fish.nexa.net.PublicUser(it.id, it.username, it.displayName) } ?: return null
        return fish.nexa.ui.markdown.UserName(u.displayName, u.username)
    }
    override fun channel(id: String): String? = Nexa.channels.get(id)?.name
    override fun role(id: String): String? = Nexa.servers.byId.value.values.firstNotNullOfOrNull { it.roles[id]?.name }
}

/** A message passed on from another (data/Forward.kt): a bar down its start side, and when the original was sent (Chat.module.css `.forwarded`). */
@Composable
private fun Forwarded(at: Long, body: @Composable () -> Unit) {
    val c = Nx.colors
    // Draw the bar after normal measurement. IntrinsicSize asks the whole
    // message for intrinsic height, which crashes for BoxWithConstraints in
    // video players and post previews (including pending forwards).
    Column(
        Modifier.padding(vertical = 2.dp).drawBehind {
            val width = 3.dp.toPx()
            drawRect(c.borderStrong, Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f), Size(width, size.height))
        }.padding(start = 13.dp),
    ) {
        Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon("CornerUpRight", c.textMuted, size = 13.dp)
            Txt(t("forward.forwarded"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(600)), color = c.textMuted)
            Txt(Time.shortDate(at), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(500)), color = c.textMuted)
        }
        body()
    }
}

@Composable
internal fun MessageBody(item: MsgItem, ctx: ListCtx, failed: Boolean, preview: Boolean = false) {
    val c = Nx.colors
    val cm = item.cm
    // AutoMod keeps what got past it hidden: a moderator can still read it, on purpose
    @Suppress("UNUSED_VARIABLE") val automodVersion by fish.nexa.data.Automod.version.collectAsState()
    val held = cm?.let { fish.nexa.data.Automod.heldBy(it, ctx.meId) }
    var reveal by remember(cm?.id) { mutableStateOf(false) }
    if (held != null && !reveal) {
        val mayReveal = ctx.server != null && Nexa.servers.canI(ctx.server, P.MANAGE_MESSAGES, ctx.channel.id)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon("ShieldAlert", c.textMuted, size = 15.dp)
            Txt(t("automod.held.text"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.textMuted)
            if (mayReveal) Txt(
                t("automod.held.show"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.link,
                modifier = Modifier.pressable(remember { MutableInteractionSource() }) { reveal = true }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        return
    }
    if (cm != null && cm.m.type == "crosspost" && cm.m.crosspost?.deleted == true) {
        Txt(t("follows.originalDeleted"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.textMuted)
        return
    }
    // an app said it would answer, and has not yet
    if (cm != null && cm.m.loading && fish.nexa.ui.markdown.Webhook.of(cm.hook)?.applicationId != null) {
        fish.nexa.ui.apps.Thinking(cm)
        return
    }
    val payload = cm?.payload ?: item.pending?.payload
    if (cm != null && cm.error != null && cm.call == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (cm.error == DecryptFail.INVALID) "ShieldAlert" else "Lock", c.textMuted, size = 14.dp)
            Txt(
                when (cm.error) {
                    DecryptFail.NO_KEY -> t("chat.messageItem.waitingForTheEncryptionKey")
                    DecryptFail.INVALID -> t("chat.messageItem.breaksLimits")
                    else -> t("chat.messageItem.thisMessageCouldNotBeDecrypted")
                },
                style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = c.textMuted,
            )
        }
        return
    }
    // a link to a channel or message is a pill in the text; a card sent for one by an older app is left off
    val embeds = if (cm != null && !cm.m.suppressEmbeds) remember(payload?.embeds) {
        payload?.embeds?.filter { e ->
            val url = (e["url"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            url == null || fish.nexa.ui.markdown.AppLinks.jumpTarget(url, Nexa.api.baseUrl) == null
        }?.takeIf { it.isNotEmpty() }
    } else null
    // Pictures and GIFs stand alone; audio and video keep their original link above the player.
    val doc by Nexa.settings.doc.collectAsState()
    val mediaOnly = embeds != null && remember(doc) { Nexa.settings.imagePreviews } &&
        remember(payload?.t, embeds) { isOnlyMediaLinks(payload?.t ?: "", embeds.mapNotNull { Unfurl.of(it) }) }
    val hiddenGif = !mediaOnly && fish.nexa.media.isBareGifPath(payload?.t ?: "")
    val text = if (mediaOnly || hiddenGif) null else payload?.t
    // Use External Emoji and Use External Stickers, held by every reader: what the author may not use here draws as words
    val emojiMap by Nexa.expressions.emojis.collectAsState()
    val packMap by Nexa.expressions.packs.collectAsState()
    val serverId = ctx.server?.server?.id
    val plainEmoji: ((String) -> Boolean)? = if (cm != null && serverId != null && fish.nexa.data.AuthorPerms.has(ctx.server, cm, P.USE_EXTERNAL_EMOJIS) == false) {
        val own = emojiMap[serverId].orEmpty().map { it.blobId }.toSet()
        val outside: (String) -> Boolean = { id -> id !in own }
        outside
    } else null
    val stickerOutside = cm != null && serverId != null && payload?.sticker != null && fish.nexa.data.AuthorPerms.has(ctx.server, cm, P.USE_EXTERNAL_STICKERS) == false &&
        packMap.values.none { p -> p.serverId == serverId && p.stickers.any { it.id == payload.sticker.id } }
    val edited = cm?.m?.editedAt?.let { t("chat.messageItem.edited") }
    if (cm != null && ChatUi.editing[cm.channelId]?.messageId == cm.id) EditMessage(cm)
    else if (hiddenGif) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon("ImageOff", c.textMuted, size = 14.dp)
        Txt(t("chat.messageItem.gifNotShown"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.textMuted)
    }
    else if (!text.isNullOrEmpty()) fish.nexa.ui.markdown.MarkdownView(
        text,
        fish.nexa.ui.markdown.RenderCtx(serverId = ctx.server?.server?.id, color = if (failed) c.textDanger else null, edited = edited, surface = c.bgRaised, plainEmoji = plainEmoji),
    )
    payload?.sticker?.let { st ->
        if (stickerOutside) Txt(st.name?.let { ":$it:" } ?: t("chat.messageItem.sticker"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(10f)), color = c.textMuted)
        else StickerView(st)
    }
    payload?.poll?.let { if (cm != null) PollCard(cm, ctx, readOnly = preview) }
    // files on their way up show their progress until the message is in (Sending… once they are up)
    val up = item.pending?.upload
    if (up != null) UploadCard(ctx.channel.id, item.pending, up)
    else payload?.att?.takeIf { it.isNotEmpty() }?.let { Attachments(it, cm, ctx) }
    if (cm != null && embeds != null) Embeds(embeds, cm, ctx)
    if (cm?.hook != null) HookBody(if (preview) cm.copy(local = true) else cm, ctx)
    // cards for the Nexa invites, profiles, notes and packs the message links to (none once its embeds were removed)
    if (cm != null && !cm.m.suppressEmbeds) {
        val sealed = remember(embeds, cm.hook) {
            (embeds ?: fish.nexa.ui.markdown.Webhook.of(cm.hook)?.linkEmbeds?.takeIf { it.isNotEmpty() })?.mapNotNull { Unfurl.of(it) } ?: emptyList()
        }
        InviteCards(payload?.t, sealed)
        ProfileCards(payload?.t, sealed)
        fish.nexa.ui.notes.NoteCards(payload?.t)
        PackCards(payload?.t)
        AppLinkCards(payload?.t)
    }
}

/** The failed-send line: what happened, then Retry and Delete. */
@Composable
private fun FailedRow(channelId: String, tmpId: String) {
    val c = Nx.colors
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t("chat.messageItem.messageFailedToSend"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textDanger, modifier = Modifier.weight(1f, fill = false))
        FailedButton("RotateCw", t("chat.messageItem.retry"), c.brand, c.onBrand) { Send.retryPending(channelId, tmpId) }
        FailedButton(null, t("chat.messageItem.delete"), c.danger, Color.White) { Send.discardPending(channelId, tmpId) }
    }
}

@Composable
private fun FailedButton(icon: String?, label: String, pressedBg: Color, pressedFg: Color, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .height(32.dp)
            .clip(Radii.Xs)
            .background(if (pressed) pressedBg else c.bgActive)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, if (pressed) pressedFg else c.text, size = 13.dp)
        // one word that never breaks: on some phones text measures a hair short of what it draws, which put "y" under "Retr"
        Txt(label, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = if (pressed) pressedFg else c.text, softWrap = false)
    }
}
