package fish.nexa.ui.voice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.net.Channel
import fish.nexa.ui.Icon
import fish.nexa.ui.chat.ComposerSlot
import fish.nexa.ui.chat.HeaderButton
import fish.nexa.ui.chat.MessageList
import fish.nexa.ui.components.CountBadge
import fish.nexa.ui.components.Txt
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.ChannelGlyph
import fish.nexa.ui.shell.PageHeader
import fish.nexa.ui.shell.Unread
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.voice.Voice

private const val CHAT_PREF = "chat_open"

/**
 * The voice channel whose chat was asked for: by the channel list's chat button
 * (no call started), a link to one of its messages or the header's button. Kept
 * out here because the list's press has to reach a page already on screen, and
 * pressing the channel's row, which joins, clears it to show the call again.
 */
object VoiceChatAsked {
    var channelId by mutableStateOf<String?>(null)
}

/**
 * ServerPage.tsx's VoiceRoom (VoiceRoom.tsx): a server's voice channel, the
 * call filling the page and the channel's own chat. From 1100 dp the chat
 * stands beside the call and whether it is open is remembered; narrower, it
 * covers the call until it is closed (Back closes it too), and the call
 * carries on underneath.
 */
@Composable
fun VoiceChannelPage(channel: Channel, openChat: Boolean = false) {
    // A moderator moved the call to another voice channel of the server while
    // this one was on screen: the screen follows it there, rather than leave
    // its owner looking at the lobby they were taken out of.
    val callAt = Voice.state.collectAsState().value.mine?.channelId
    var callWas by remember { mutableStateOf(callAt) }
    LaunchedEffect(callAt) {
        val was = callWas
        callWas = callAt
        val sid = channel.serverId
        if (sid != null && was == channel.id && callAt != null && callAt != channel.id && Nexa.channels.get(callAt)?.serverId == sid) Nav.go(Route.Server(sid, callAt))
    }
    val prefs = remember { Nexa.app.getSharedPreferences("voice_room", android.content.Context.MODE_PRIVATE) }
    val roomy = Nav.roomy
    var wideOpen by remember { mutableStateOf(prefs.getBoolean(CHAT_PREF, false)) }
    // a link to one of its messages opens the chat, as the channel list's button does
    remember(channel.id, openChat) { if (openChat) VoiceChatAsked.channelId = channel.id }
    val asked = VoiceChatAsked.channelId == channel.id
    val open = asked || (roomy && wideOpen)
    fun close() { if (asked) VoiceChatAsked.channelId = null }
    fun toggle() {
        if (!roomy) { if (open) close() else VoiceChatAsked.channelId = channel.id }
        else {
            if (open) close()
            wideOpen = !open
            prefs.edit().putBoolean(CHAT_PREF, wideOpen).apply()
        }
    }
    BackHandler(enabled = open && !roomy) { close() }
    // While the chat shows, this is the channel being read: what arrives is
    // marked read, and its notifications stay quiet.
    DisposableEffect(open, channel.id) {
        if (open) Nexa.ui.open(channel.serverId, channel.id)
        onDispose { if (open) Nexa.ui.open(channel.serverId, null) }
    }
    val reads by Nexa.readStates.byChannel.collectAsState()
    val read = reads[channel.id]
    val mentions = read?.mentions ?: 0
    val unread = !open && Unread.isUnread(read, channel.lastMessageId)
    val c = Nx.colors
    Column(Modifier.fillMaxSize()) {
        PageHeader(channel.name, icon = { ChannelGlyph(channel, 22.dp, c.textMuted) }) {
            Box {
                HeaderButton("MessageSquareText", if (open) t("voiceChat.hide") else t("voiceChat.show"), on = open) { toggle() }
                when {
                    !open && mentions > 0 -> CountBadge(mentions, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp), cut = c.bgRaised)
                    unread -> Box(
                        Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(12.dp)
                            .background(c.bgRaised, CircleShape).padding(2.dp).background(c.textStrong, CircleShape),
                    )
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // under a chat that covers it, the call is hidden from TalkBack
                VoiceView(channel.id, Modifier.fillMaxSize().then(if (open && !roomy) Modifier.semantics { hideFromAccessibility() } else Modifier))
                if (!roomy) androidx.compose.animation.AnimatedVisibility(open, enter = fadeIn() + slideInVertically { it / 24 }, exit = fadeOut()) {
                    VoiceChat(channel, onClose = { close() })
                }
            }
            if (roomy && open) {
                val edge = c.borderSubtle
                Box(Modifier.width(400.dp).fillMaxHeight().drawBehind { drawLine(edge, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }) {
                    VoiceChat(channel, onClose = null)
                }
            }
        }
    }
}

/** The channel's messages and its box; covering the call it has a line of its own with the way back. */
@Composable
private fun VoiceChat(channel: Channel, onClose: (() -> Unit)?) {
    val c = Nx.colors
    Column(Modifier.fillMaxSize().background(c.bgRaised).imePadding().navigationBarsPadding()) {
        if (onClose != null) Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon("MessageSquareText", c.textMuted, size = 18.dp)
            Txt(
                t("voiceChat.title", "name" to channel.name),
                style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)),
                color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f),
            )
            HeaderButton("X", t("voiceChat.hide")) { onClose() }
        }
        MessageList(channel, Modifier.weight(1f))
        ComposerSlot(channel)
    }
}
