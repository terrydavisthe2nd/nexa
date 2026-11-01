package fish.nexa.ui.chat

import fish.nexa.ui.nav.Nav
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ButtonKind
import fish.nexa.net.wireJson
import fish.nexa.net.Channel
import fish.nexa.net.ApiError
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.PageHeader
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx

/** GET /servers/{id}: the server and the people in it. */
@kotlinx.serialization.Serializable
private class ServerFetched(val server: fish.nexa.net.ServerView, val users: List<fish.nexa.net.PublicUser> = emptyList())

/**
 * ServerPage.tsx: the open channel of a server. One this device has not
 * loaded yet (a link, a notification, one just joined elsewhere) is fetched
 * once; one that was on screen and then went away (kicked, banned, deleted,
 * left on another device) sends you home. A link to a channel that is gone
 * opens the server's first text channel, and one to a category opens the server.
 */
@Composable
fun ServerPage(route: Route.Server) {
    val servers by Nexa.servers.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val known = servers[route.serverId] != null
    val ch = route.channelId?.let { channels[it] }
    // which server was seen, not merely that one was: following a link from one server to another keeps this page
    var seen by remember { mutableStateOf<String?>(null) }
    var failed by remember(route.serverId) { mutableStateOf(false) }
    var attempt by remember(route.serverId) { mutableIntStateOf(0) }
    LaunchedEffect(known, route.serverId) {
        if (known) seen = route.serverId
        else if (seen == route.serverId) Nav.go(Route.Friends, replace = true)
    }
    LaunchedEffect(route.serverId, known, attempt) {
        if (known || seen == route.serverId) return@LaunchedEffect
        failed = false
        try {
            val got = Nexa.api.get("/servers/${route.serverId}", ServerFetched.serializer())
            Nexa.session.putServer(got.server, got.users)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiError) {
            // gone is not the same as a request that failed: only gone goes home
            if (e.status == 403 || e.status == 404) {
                Nexa.ui.toast(t(if (e.status == 403) "server.youAreNoLongerInThatServer" else "server.thatServerIsGone"), "error")
                Nav.go(Route.Friends, replace = true)
            } else failed = true
        } catch (_: Exception) {
            failed = true
        }
    }
    // a thread this device does not hold (closed, or never opened here) is asked for before giving up on it
    var asked by remember(route.channelId) { mutableStateOf(route.channelId == null) }
    LaunchedEffect(known, route.channelId, ch == null) {
        val id = route.channelId
        if (!known || id == null || ch != null || asked) return@LaunchedEffect
        try {
            val got = wireJson.decodeFromJsonElement(Channel.serializer(), Nexa.api.get("/channels/$id"))
            if (got.serverId == route.serverId) Nexa.channels.upsert(listOf(got))
        } catch (_: Exception) {
        } finally {
            asked = true
        }
    }
    val first = if (known && asked && (ch == null || ch.serverId != route.serverId)) Nav.firstTextChannel(route.serverId) else null
    LaunchedEffect(known, ch?.id, ch?.type, first) {
        when {
            !known -> Unit
            // a channel that is gone (or never named) gives way to the first text channel
            ch == null && first != null -> Nav.go(Route.Server(route.serverId, first), replace = true)
            ch?.isCategory == true -> Nav.go(Route.Server(route.serverId), replace = true)
        }
    }
    // a link to a message: open there
    LaunchedEffect(route) { if (ch != null && route.jumpTo != null) ChatUi.jumpTo(ch.id, route.jumpTo) }
    when {
        !known && failed -> Column(Modifier.fillMaxSize()) {
            PageHeader("")
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(t("server.thatServerCouldNotBeOpened"), style = Nx.type.heading, color = Nx.colors.textStrong, align = TextAlign.Center)
                Txt(t("server.theRequestDidNotGetThrough"), style = Nx.type.body, color = Nx.colors.textMuted, align = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                NxButton(t("chat.messageList.tryAgain"), { attempt += 1 }, kind = ButtonKind.SECONDARY)
            }
        }
        !known -> Column(Modifier.fillMaxSize()) {
            PageHeader("")
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spinner(size = 40.dp)
                Txt(t("server.openingServer"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = Nx.colors.textMuted)
            }
        }
        ch == null || ch.serverId != route.serverId || ch.isCategory || ch.isLink -> NoChannel(route.serverId)
        ch.type == "voice" -> fish.nexa.ui.voice.VoiceChannelPage(ch, openChat = route.jumpTo != null)
        ch.isForum -> fish.nexa.ui.forum.ForumView(ch)
        else -> ChatView(ch.id)
    }
}

@Composable
private fun NoChannel(serverId: String) {
    val c = Nx.colors
    val name = Nexa.servers.get(serverId)?.server?.name ?: ""
    Column(Modifier.fillMaxSize()) {
        PageHeader(name)
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon("Hash", c.textMuted, size = 48.dp)
            Txt(t("server.noTextChannels"), style = Nx.type.body, color = c.textMuted)
        }
    }
}

/**
 * DMPage.tsx: a DM, a group or a subchat. One this device has not loaded yet
 * (a link, a notification) is fetched; one that was on screen and then left
 * the list was closed elsewhere, so the page goes back to Friends.
 */
@Composable
fun DmPage(route: Route.Dm) {
    val channels by Nexa.channels.byId.collectAsState()
    val channel = channels[route.channelId]
    var shown by remember { mutableStateOf<String?>(null) }
    var failed by remember(route.channelId) { mutableStateOf(false) }
    var attempt by remember(route.channelId) { mutableIntStateOf(0) }
    LaunchedEffect(channel?.id, route.channelId) {
        if (channel != null) shown = channel.id
        else if (shown == route.channelId) Nav.go(Route.Friends, replace = true)
    }
    LaunchedEffect(route.channelId, channel == null, attempt) {
        if (channel != null || shown == route.channelId) return@LaunchedEffect
        failed = false
        try {
            val ch = wireJson.decodeFromJsonElement(Channel.serializer(), Nexa.api.get("/channels/${route.channelId}"))
            Nexa.channels.upsert(listOf(ch))
        } catch (e: ApiError) {
            if (e.status == 403 || e.status == 404) {
                Nexa.ui.toast(t("dM.thatConversationIsGone"), "error")
                Nav.go(Route.Friends, replace = true)
            } else failed = true
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(route) { route.jumpTo?.let { ChatUi.jumpTo(route.channelId, it) } }
    when {
        channel != null -> ChatView(route.channelId)
        failed -> Column(Modifier.fillMaxSize()) {
            PageHeader("")
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(t("dM.thatConversationCouldNotBeOpened"), style = Nx.type.heading, color = Nx.colors.textStrong, align = TextAlign.Center)
                Txt(t("server.theRequestDidNotGetThrough"), style = Nx.type.body, color = Nx.colors.textMuted, align = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                NxButton(t("chat.messageList.tryAgain"), { attempt += 1 }, kind = ButtonKind.SECONDARY)
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            PageHeader("")
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spinner(size = 40.dp)
                Txt(t("dM.openingConversation"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = Nx.colors.textMuted)
            }
        }
    }
}
