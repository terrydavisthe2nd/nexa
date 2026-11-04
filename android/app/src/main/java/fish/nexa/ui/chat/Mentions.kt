package fish.nexa.ui.chat

import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideArea
import fish.nexa.ui.components.glideItem
import fish.nexa.ui.components.rememberGlide
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ChatMessage
import fish.nexa.net.Message
import fish.nexa.net.wireJson
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.SelectField
import fish.nexa.ui.components.ServerIcon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.ChannelGlyph
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder

/**
 * Mentions (the web's MentionsModal): every message that pinged this account,
 * from conversations and servers, narrowed to who was pinged (you, your roles,
 * everyone) and where, newest or oldest first, under a line for each day. A
 * press goes to the message. The server finds them; this phone opens them.
 */
object Mentions {
    const val PAGE = 25

    /** The filters, as the web keeps them: kind all|me|roles|everyone, place all|@me|server id, sort newest|oldest. */
    data class View(val kind: String = "all", val place: String = "all", val sort: String = "newest")

    /** Why a message is yours: you by name wins over a role, a role over everyone. */
    sealed interface Why {
        data object Me : Why
        data class OfRole(val roleId: String) : Why
        data object Everyone : Why
    }

    fun why(m: Message, meId: String, myRoles: Collection<String>): Why {
        if (meId in m.mentions) return Why.Me
        m.mentionRoles.firstOrNull { it in myRoles }?.let { return Why.OfRole(it) }
        if (m.mentionEveryone) return Why.Everyone
        m.mentionRoles.firstOrNull()?.let { return Why.OfRole(it) }
        return Why.Everyone
    }

    /** The path GET /users/@me/mentions takes for a view, continuing after [cursor]. */
    fun path(v: View, cursor: String?, limit: Int = PAGE): String {
        val q = mutableListOf<String>()
        if (v.kind != "all") q += "kinds=${v.kind}"
        if (v.place != "all") q += "server_id=" + URLEncoder.encode(v.place, "UTF-8")
        if (v.sort == "oldest") q += "sort=oldest"
        if (cursor != null) q += (if (v.sort == "oldest") "after=" else "before=") + cursor
        q += "limit=$limit"
        return "/users/@me/mentions?" + q.joinToString("&")
    }

    /** Snowflakes in order: a longer one is later. */
    fun newer(a: String, b: String): Boolean = if (a.length != b.length) a.length > b.length else a > b

    private const val PREFS = "mentions"

    fun saved(ctx: Context): View {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = p.getString("kind", "all")!!.takeIf { it in listOf("all", "me", "roles", "everyone") } ?: "all"
        return View(kind, p.getString("place", "all") ?: "all", if (p.getString("sort", "newest") == "oldest") "oldest" else "newest")
    }

    fun save(ctx: Context, v: View) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("kind", v.kind).putString("place", v.place).putString("sort", v.sort).apply()
    }
}

fun openMentions() {
    openSheet("mentions", title = t("mentions.title")) { close ->
        val c = Nx.colors
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
        var view by remember { mutableStateOf(Mentions.saved(app)) }
        var items by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        // loading, idle (more to come), failed, end
        var state by remember { mutableStateOf("loading") }
        var attempt by remember { mutableIntStateOf(0) }
        var more by remember { mutableIntStateOf(0) }
        val servers by Nexa.servers.byId.collectAsState()
        val order by Nexa.servers.order.collectAsState()
        val reads by Nexa.readStates.byChannel.collectAsState()
        // a server left since the filter was chosen is not there to pick
        val place = if (view.place == "all" || view.place == "@me" || servers[view.place] != null) view.place else "all"
        val shownView = view.copy(place = place)

        suspend fun load(from: List<ChatMessage>) {
            state = "loading"
            try {
                val page = wireJson.decodeFromJsonElement(ListSerializer(Message.serializer()), Nexa.api.get(Mentions.path(shownView, from.lastOrNull()?.id)))
                val dec = withContext(Dispatchers.Default) { page.map { Nexa.messages.decrypt(it) } }
                val seen = from.map { it.id }.toHashSet()
                items = from + dec.filter { it.id !in seen }
                state = if (page.size < Mentions.PAGE) "end" else "idle"
                Nexa.session.ensureUsers(page.filter { it.webhookId == null }.map { it.authorId })
            } catch (_: Exception) {
                state = "failed"
            }
        }
        // a new filter starts the list again from the top
        LaunchedEffect(shownView, attempt) {
            Mentions.save(app, shownView)
            items = emptyList()
            load(emptyList())
        }
        LaunchedEffect(more) { if (more > 0) load(items) }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KindPicker(view.kind) { view = view.copy(kind = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val places = listOf("all" to t("mentions.placeAll"), "@me" to t("mentions.placeDms")) + order.mapNotNull { id -> servers[id]?.let { id to it.server.name } }
                SelectField(t("mentions.placeLabel"), places, place, modifier = Modifier.weight(1f)) { view = view.copy(place = it) }
                SelectField(t("mentions.sortLabel"), listOf("newest" to t("mentions.newest"), "oldest" to t("mentions.oldest")), view.sort, modifier = Modifier.weight(1f)) { view = view.copy(sort = it) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(c.borderSubtle))
        var lastDay = ""
        for (cm in items) {
            val day = java.time.Instant.ofEpochMilli(cm.m.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            if (day != lastDay) {
                lastDay = day
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(Time.dateDivider(cm.m.createdAt), style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1)
                    Box(Modifier.weight(1f).height(1.dp).background(c.borderSubtle))
                }
            }
            val lastRead = reads[cm.channelId]?.lastReadId
            MentionRow(cm, unread = lastRead == null || Mentions.newer(cm.id, lastRead)) {
                close()
                val ch = Nexa.channels.get(cm.channelId)
                val sid = ch?.serverId
                // the page jumps there once it has the channel (Pages.kt), as a message link does
                if (sid != null) Nav.go(Route.Server(sid, cm.channelId, jumpTo = cm.id)) else Nav.go(Route.Dm(cm.channelId, jumpTo = cm.id))
            }
        }
        val filtered = view.kind != "all" || place != "all"
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state == "failed" -> {
                    PanelNote(t("mentions.failed"))
                    NxButton(t("chat.messageList.tryAgain"), { if (items.isEmpty()) attempt += 1 else more += 1 }, kind = ButtonKind.LINK, inline = true)
                }
                state == "loading" -> PanelNote(t("chat.pins.loading"))
                items.isEmpty() -> {
                    PanelNote(if (filtered) t("mentions.emptyFiltered") else t("mentions.empty"))
                    if (filtered) NxButton(t("mentions.showAll"), { view = view.copy(kind = "all", place = "all") }, kind = ButtonKind.LINK, inline = true)
                }
                state == "idle" -> NxButton(t("mentions.loadMore"), { more += 1 }, kind = ButtonKind.LINK, inline = true)
                else -> Txt(t("mentions.end"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            }
        }
    }
}

/**
 * All, You, Your Roles, @everyone: one picked (MentionsModal.module.css
 * `.kinds`), its pill sliding to the one picked next (components/Glide.kt).
 */
@Composable
private fun KindPicker(picked: String, onPick: (String) -> Unit) {
    val c = Nx.colors
    val inner = RoundedCornerShape(6.dp)
    val glide = rememberGlide(picked)
    Box(Modifier.fillMaxWidth().background(c.bgInput, Radii.Sm).padding(3.dp).glideArea(glide).semantics { contentDescription = t("mentions.kindLabel") }) {
    GlidePill(glide, Modifier.background(c.bgFloating, inner))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((key, label) in listOf("all" to t("mentions.kindAll"), "me" to t("mentions.kindMe"), "roles" to t("mentions.kindRoles"), "everyone" to t("mentions.kindEveryone"))) {
            val on = picked == key
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .glideItem(glide, key)
                    .clip(inner)
                    .pressable(remember { MutableInteractionSource() }) { onPick(key) }
                    .semantics { role = Role.RadioButton; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted, maxLines = 1)
            }
        }
    }
    }
}

/** Where a mention was: the server and channel, or the conversation. */
@Composable
private fun MentionPlace(channelId: String, modifier: Modifier) {
    val c = Nx.colors
    val ch = Nexa.channels.get(channelId)
    val style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f))
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ch == null) {
            Txt(t("mentions.unknownPlace"), style = style, color = c.textMuted, maxLines = 1)
            return@Row
        }
        val server = ch.serverId?.let { Nexa.servers.get(it)?.server }
        if (server != null) {
            ServerIcon(server.id, server.name, server.iconId, 18.dp, radius = 5.dp)
            Txt(server.name, style = style.copy(fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1, modifier = Modifier.weight(1.4f, fill = false))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f, fill = false)) {
                ChannelGlyph(ch, 14.dp, c.textMuted)
                Txt(ch.name, style = style, color = c.textMuted, maxLines = 1)
            }
        } else {
            val meId = Nexa.users.meId
            val other = if (ch.type == "dm") ch.recipientIds.firstOrNull { it != meId } else null
            val name = Names.channel(ch)
            if (other != null) Avatar(other, name, Nexa.users.get(other)?.avatarId, 18.dp) else ChannelGlyph(ch, 16.dp, c.textMuted)
            Txt(name, style = style.copy(fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun MentionRow(cm: ChatMessage, unread: Boolean, onJump: () -> Unit) {
    val c = Nx.colors
    val ch = Nexa.channels.get(cm.channelId)
    val sid = ch?.serverId
    val st = sid?.let { Nexa.servers.get(it) }
    val why = Mentions.why(cm.m, Nexa.users.meId ?: "", st?.me?.roleIds ?: emptyList())
    val role = (why as? Mentions.Why.OfRole)?.let { st?.roles?.get(it.roleId) }
    val roleColour = role?.color?.takeIf { it != 0L }?.let { Color(0xFF000000 or it) }
    val edge = when (why) {
        Mentions.Why.Me -> c.mentionFg
        is Mentions.Why.OfRole -> roleColour ?: c.brand
        Mentions.Why.Everyone -> c.borderStrong
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        why == Mentions.Why.Me -> if (pressed) c.mentionBgHover else c.mentionBg
        pressed -> c.bgHover
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(Radii.Sm)
            .background(bg)
            // the edge in the colour of why it is yours, drawn rather than measured: the body may hold players that cannot answer intrinsic sizes
            .drawBehind {
                val w = 3.dp.toPx()
                val inset = 8.dp.toPx()
                drawRoundRect(edge, topLeft = Offset(4.dp.toPx(), inset), size = Size(w, size.height - inset * 2), cornerRadius = CornerRadius(w / 2))
            }
            .pressable(interaction, onClick = onJump)
            .padding(start = 15.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // where, then why, taking what room the Jump button leaves
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MentionPlace(cm.channelId, Modifier.weight(1f, fill = false))
                    val (tagText, tagColour) = when (why) {
                        Mentions.Why.Me -> t("mentions.tagYou") to c.mentionFg
                        is Mentions.Why.OfRole -> "@" + (role?.name ?: t("mentions.deletedRole")) to (roleColour ?: c.textSubtle)
                        Mentions.Why.Everyone -> t("mentions.tagEveryone") to c.textSubtle
                    }
                    Pill(tagText, tagColour, if (why == Mentions.Why.Everyone) c.bgActive else tagColour.copy(alpha = 0.16f))
                    if (unread) Pill(t("mentions.new"), Color.White, c.danger)
                }
                JumpButton(onJump)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val hook = fish.nexa.ui.markdown.Webhook.of(cm.hook)
                val hookId = cm.m.webhookId?.takeIf { hook != null }
                val name = if (hook != null && hookId != null) hook.name else Names.member(sid, cm.authorId)
                if (hook != null && hookId != null) fish.nexa.ui.media.HookAvatar(hook, hookId, 36.dp)
                else Avatar(cm.authorId, name, Nexa.users.get(cm.authorId)?.avatarId, 36.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val color = st?.let { Nexa.servers.memberColor(it, cm.authorId) }?.let { Color(0xFF000000 or it) } ?: c.textStrong
                        if (hook != null && hookId != null) Txt(name, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        else PersonName(cm.authorId, name, serverId = sid, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = color, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Txt(Time.timestamp(cm.m.createdAt), style = Nx.type.timestamp, color = c.textMuted, maxLines = 1)
                    }
                    if (ch != null) MessageBody(MsgItem(cm.id, cm, groupStart = true), ListCtx(ch, st, compact = false, selecting = false, meId = Nexa.users.meId ?: ""), failed = false)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) {
    Box(Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Txt(text, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(650)), color = fg, maxLines = 1)
    }
}

@Composable
private fun JumpButton(onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.clip(Radii.Xs).background(if (pressed) c.bgSelected else c.bgActive).pressable(interaction, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(t("chat.pins.jump"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (pressed) c.textStrong else c.textSubtle, maxLines = 1)
    }
}
