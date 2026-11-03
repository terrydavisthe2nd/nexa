package fish.nexa.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.Invites
import fish.nexa.media.Media
import fish.nexa.media.Unfurl
import fish.nexa.net.ApiError
import fish.nexa.net.PublicUser
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.ui.Icon
import fish.nexa.ui.components.AvatarColor
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.openProfile
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import java.net.URI

private const val MAX_INVITE_CARDS = 3
private const val MAX_PROFILE_CARDS = 3
private const val MAX_PACK_CARDS = 3
private const val MAX_APP_CARDS = 3

/** Nexa's own addresses a message can link to (lib/appLinks.ts appLink): invites, profiles, and sticker and emoji packs. */
sealed interface AppLink {
    data class Invite(val code: String, val hash: String, val url: String) : AppLink
    data class Profile(val username: String, val url: String) : AppLink
    data class Pack(val id: String, val url: String) : AppLink
    /** An app's install link: the app it adds (its client id) and the link's query. */
    data class App(val clientId: String, val query: String, val url: String) : AppLink
}

private fun ownHosts(): Set<String> = listOfNotNull(
    runCatching { URI(Nexa.api.baseUrl).authority?.lowercase() }.getOrNull(),
    runCatching { URI(Nexa.auth.instance.value?.publicUrl ?: "").authority?.lowercase() }.getOrNull(),
).filter { it.isNotEmpty() }.toSet()

/** Pulls this up over what sits above it by [by], as a negative top margin does on the web. */
private fun Modifier.overlapAbove(by: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(p.width, maxOf(0, p.height - shift)) { p.placeRelative(0, -shift) }
}

private val INVITE_PATH = Regex("^/invite/([A-Za-z0-9_-]{1,64})/?$")
private val PROFILE_PATH = Regex("^/@([A-Za-z0-9_.]{2,32})/?$")
private val PACK_PATH = Regex("^/stickers/pack/(\\d{1,20})/?$")
private val AUTHORIZE_PATH = Regex("^/oauth2/authorize/?$")

fun appLink(href: String): AppLink? = runCatching {
    val u = URI(href)
    if (u.scheme != "https" && u.scheme != "http") return null
    if ((u.authority ?: "").lowercase() !in ownHosts()) return null
    val path = u.path ?: ""
    PROFILE_PATH.find(path)?.let { return AppLink.Profile(it.groupValues[1].lowercase(), href) }
    INVITE_PATH.find(path)?.let { return AppLink.Invite(it.groupValues[1], u.rawFragment?.let { f -> "#$f" } ?: "", href) }
    PACK_PATH.find(path)?.let { return AppLink.Pack(it.groupValues[1], href) }
    if (AUTHORIZE_PATH.matches(path)) {
        val query = u.rawQuery ?: ""
        val clientId = query.split('&').firstOrNull { it.startsWith("client_id=") }?.substringAfter('=')
        if (clientId != null && Regex("^\\d{1,20}$").matches(clientId)) return AppLink.App(clientId, query, href)
    }
    null
}.getOrNull()

/**
 * The links in a message's words that may become cards: web addresses not in
 * code and not written as <address> (which asks for no preview). The markdown
 * port will hand these over from its parse; until then the words are scanned.
 */
fun cardLinksIn(text: String): List<String> {
    val out = ArrayList<String>()
    val noCode = text.replace(Regex("```[\\s\\S]*?```"), " ").replace(Regex("`[^`\\n]*`"), " ")
    for (m in Regex("<?https?://[^\\s<>\"]+>?", RegexOption.IGNORE_CASE).findAll(noCode)) {
        if (m.value.startsWith("<") && m.value.endsWith(">")) continue
        out += fish.nexa.ui.media.trimUrl(m.value.removePrefix("<"))
    }
    return out
}

/** Up to three invite cards under a message, from its links and the invite previews it carries. */
@Composable
fun InviteCards(text: String?, embeds: List<Unfurl>) {
    val links = remember(text, embeds) {
        val out = ArrayList<AppLink.Invite>()
        for (url in cardLinksIn(text ?: "")) {
            val l = appLink(url) as? AppLink.Invite ?: continue
            if (out.none { it.code == l.code }) out += l
            if (out.size >= MAX_INVITE_CARDS) break
        }
        for (e in embeds) {
            val code = e.inviteCode ?: continue
            if (e.type != "invite" || !Regex("^[A-Za-z0-9_-]{1,64}$").matches(code)) continue
            if (out.none { it.code == code } && out.size < MAX_INVITE_CARDS) out += AppLink.Invite(code, e.url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }, e.url)
        }
        out
    }
    if (links.isEmpty()) return
    val sealed = embeds.filter { it.type == "invite" }.associateBy { it.inviteCode }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (l in links) InviteCard(l, sealed[l.code])
    }
}

/** What an invite card shows: a server or a group, with its pictures and counts. */
data class InviteTarget(val kind: String, val id: String?, val name: String, val description: String, val iconId: String?, val bannerId: String?, val online: Int?, val members: Int?)

private fun mediaId(url: String?, kind: String): String? = url?.let { Regex("/media/$kind/(\\d{1,20})(?:\\?|$)").find(it)?.groupValues?.get(1) }

@Composable
private fun InviteCard(link: AppLink.Invite, sealedEmbed: Unfurl?) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(link.code) { Invites.load(link.code) }
    val entry = Invites.byCode[link.code]
    val servers by Nexa.servers.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val target = when (entry) {
        is Invites.Entry.Ready -> entry.preview.server?.let { s -> InviteTarget("server", s.id, s.name, s.description, s.iconId, s.bannerId, s.onlineCount, s.memberCount) }
            ?: entry.preview.group?.let { g -> InviteTarget("group", g.id, g.name, "", g.iconId, null, null, g.memberCount) }
        is Invites.Entry.Invalid -> null
        else -> null
    }
    when {
        entry is Invites.Entry.Ready && entry.preview.ageGate -> GoneCard("ShieldCheck", t("nsfw.warningTitle"), t("nsfw.warningBody")) {
            NxButton(t("nsfw.confirm"), { Invites.confirmAge(link.code); Invites.load(link.code, force = true) }, size = ButtonSize.SM)
        }
        entry is Invites.Entry.Invalid -> GoneCard("Link2Off", t("invite.invalidInvite"), t("invite.thisInviteIsInvalidOrHasExpired") + " " + t("invite.askForANewOne"))
        target == null && entry is Invites.Entry.Failed -> GoneCard(null, t("invite.couldNotLoadTheInvite"), t("invite.checkYourConnection")) {
            NxButton(t("invite.tryAgain"), { Invites.load(link.code, force = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "RotateCw")
        }
        target == null -> InviteSkeleton()
        else -> {
            val member = target.id != null && (if (target.kind == "group") channels.containsKey(target.id) else servers.containsKey(target.id))
            Box(Modifier.semantics { contentDescription = t("invite.inviteTo", "name" to target.name) }) {
                InviteCardView(target, lead = null, action = {
                    if (member) NxButton(t("chat.embeds.open"), {
                        Nav.go(if (target.kind == "group") Route.Dm(target.id!!) else Route.Server(target.id!!))
                    }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    else NxButton(t("chat.embeds.join"), {
                        busy = true
                        scope.launch {
                            try {
                                Nav.go(Invites.accept(link.code, link.hash))
                                Invites.load(link.code, force = true)
                            } catch (e: Exception) {
                                Nexa.ui.toast((e as? ApiError)?.message?.takeIf { it.isNotBlank() } ?: t("invite.couldNotAcceptTheInvite"), "error")
                            } finally {
                                busy = false
                            }
                        }
                    }, kind = ButtonKind.POSITIVE, size = ButtonSize.SM, loading = busy)
                })
            }
        }
    }
}

/**
 * InviteView, card form: the server's banner (or a strip in its colour)
 * across the top, its icon set into the band's edge, the name and who is
 * here, the action beside them, and two lines of what it is about.
 */
@Composable
fun InviteCardView(target: InviteTarget, lead: (@Composable () -> Unit)?, action: (@Composable () -> Unit)?, inPopout: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val density = LocalDensity.current.density
    val surface = if (inPopout) c.bgFloating else c.embedBg
    val shape = Radii.Lg
    Column(
        Modifier
            .then(if (inPopout) Modifier.fillMaxWidth() else Modifier.widthIn(max = 432.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.borderSubtle).clip(shape))
            .background(surface)
            .then(if (inPopout) Modifier else Modifier.topEdge(shape, c.edgeLight)),
    ) {
        var bannerFailed by remember(target.bannerId) { mutableStateOf(false) }
        if (target.bannerId != null && !bannerFailed) {
            AsyncImage(
                Media.bannerUrl(target.bannerId, 432, density), null, imageLoader = Media.loader, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(18f / 5f).background(c.bgActive), onError = { bannerFailed = true },
            )
        } else {
            Box(Modifier.fillMaxWidth().height(44.dp).background(if (target.id != null && target.iconId == null) AvatarColor.of(target.id) else c.bgActive))
        }
        // the icon, ringed in the card's own colour so it reads as set into the band, half over its edge
        Box(Modifier.padding(start = 12.dp).overlapAbove(32.dp).background(surface, RoundedCornerShape(22.dp)).padding(4.dp)) {
            if (target.iconId != null) AsyncImage(Media.iconUrl(target.iconId, 56, density), null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)))
            else Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(target.id?.let { AvatarColor.of(it) } ?: c.bgActive),
                contentAlignment = Alignment.Center,
            ) { Txt(AvatarColor.initials(target.name), style = Nx.type.body.copy(fontSize = Nx.type.sp(20f), fontWeight = FontWeight(700)), color = if (target.id != null) Color.White else c.textStrong) }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lead?.invoke()
                    Txt(target.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(680), lineHeight = Nx.type.sp(21.45f)), color = c.textStrong, maxLines = 2)
                    InviteCounts(target)
                }
                action?.invoke()
            }
            if (target.description.isNotBlank()) Txt(target.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.text, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            extra?.invoke()
        }
    }
}

/** The online and member counts: each kept whole on one line, the second going under the first when they do not fit side by side (Invite.module.css .counts). */
@Composable
private fun InviteCounts(target: InviteTarget) {
    val c = Nx.colors
    if (target.online == null && target.members == null) return
    val style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18f))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        target.online?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).ring(CircleShape, c.statusOnline.copy(alpha = 0.22f), 3.dp).background(c.statusOnline, CircleShape))
                Txt(t("invite.onlineCount", "count" to n), style = style, color = c.textMuted, maxLines = 1)
            }
        }
        target.members?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(c.statusOffline, CircleShape))
                Txt(if (target.kind == "group") t("invite.inThisGroupCount", "count" to n) else t("invite.membersCount", "count" to n), style = style, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** An invite or profile that leads nowhere, or could not be loaded, with what to do about it. */
@Composable
private fun GoneCard(icon: String?, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    val shape = Radii.Lg
    Row(
        Modifier.widthIn(max = 432.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.borderSubtle).clip(shape).background(c.embedBg).topEdge(shape, c.edgeLight).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Box(Modifier.size(48.dp).background(c.bgActive, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, c.textMuted, size = 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, style = Nx.type.body.copy(fontWeight = FontWeight(700)), color = c.textStrong)
            Txt(body, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.textMuted)
        }
        action?.invoke()
    }
}

/** The card's frame while it loads: the same shapes, quiet, breathing. */
@Composable
private fun InviteSkeleton() {
    val c = Nx.colors
    val shape = Radii.Lg
    Column(
        Modifier.widthIn(max = 432.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.borderSubtle).clip(shape).background(c.embedBg)
            .semantics { contentDescription = t("invite.loading") },
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp).background(c.bgActive))
        Box(Modifier.padding(start = 12.dp).overlapAbove(32.dp).background(c.embedBg, RoundedCornerShape(22.dp)).padding(4.dp)) {
            Box(Modifier.size(56.dp).background(c.bgActive, RoundedCornerShape(17.dp)))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(16.dp).background(c.bgActive, RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(c.bgActive, RoundedCornerShape(6.dp)))
        }
    }
}

// ---- profile links ----

private sealed interface ProfileEntry {
    data object Loading : ProfileEntry
    data class Ready(val user: PublicUser, val at: Long) : ProfileEntry
    data class Missing(val at: Long) : ProfileEntry
    data class Failed(val at: Long) : ProfileEntry
}

/** lib/profileLinks.ts: the people a message links to by their profile address, kept a few minutes. */
private object SharedProfiles {
    val byName = mutableStateMapOf<String, ProfileEntry>()

    fun load(name: String, force: Boolean = false) {
        val cur = byName[name]
        val now = System.currentTimeMillis()
        val due = when (cur) {
            null -> true
            ProfileEntry.Loading -> false
            is ProfileEntry.Ready -> now - cur.at > 5 * 60_000
            is ProfileEntry.Missing -> now - cur.at > 5 * 60_000
            is ProfileEntry.Failed -> now - cur.at > 30_000
        }
        if (!force && !due) return
        if (cur !is ProfileEntry.Ready) byName[name] = ProfileEntry.Loading
        Nexa.scope.launch {
            byName[name] = try {
                val u = Nexa.api.request("GET", "/profiles/${java.net.URLEncoder.encode(name, "UTF-8")}", PublicUser.serializer())
                Nexa.users.upsert(listOf(u))
                ProfileEntry.Ready(u, System.currentTimeMillis())
            } catch (e: Exception) {
                if (e is ApiError && e.status == 404) ProfileEntry.Missing(System.currentTimeMillis()) else ProfileEntry.Failed(System.currentTimeMillis())
            }
        }
    }
}

/** Up to three cards for the profiles a message links to (ProfileCards.tsx). */
@Composable
fun ProfileCards(text: String?, embeds: List<Unfurl>) {
    val names = remember(text, embeds) {
        val out = ArrayList<String>()
        for (url in cardLinksIn(text ?: "")) {
            val l = appLink(url) as? AppLink.Profile ?: continue
            if (l.username !in out) out += l.username
            if (out.size >= MAX_PROFILE_CARDS) break
        }
        for (e in embeds) {
            val u = e.username ?: continue
            if (e.type != "profile" || !Regex("^[a-z0-9_.]{2,32}$").matches(u)) continue
            if (u !in out && out.size < MAX_PROFILE_CARDS) out += u
        }
        out
    }
    if (names.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in names) ProfileLinkCard(n)
    }
}

@Composable
private fun ProfileLinkCard(username: String) {
    LaunchedEffect(username) { SharedProfiles.load(username) }
    when (val e = SharedProfiles.byName[username]) {
        is ProfileEntry.Missing -> GoneCard("UserX", t("profileLink.missingTitle"), t("profileLink.missingBody"))
        is ProfileEntry.Failed -> GoneCard(null, t("profileLink.couldNotLoad"), t("invite.checkYourConnection")) {
            NxButton(t("invite.tryAgain"), { SharedProfiles.load(username, force = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "RotateCw")
        }
        is ProfileEntry.Ready -> ProfileLinkCardView(e.user)
        else -> ProfileSkeleton()
    }
}

/**
 * A profile a message links to, as a card: the banner, the avatar over its
 * edge, the names, and what they say about themselves; a tap opens the whole
 * profile. (The profile popout card itself takes this place once it exists.)
 */
@Composable
private fun ProfileLinkCardView(user: PublicUser) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val density = LocalDensity.current.density
    val shape = Radii.Lg
    Column(
        Modifier
            .width(320.dp)
            .shadowMedium(shape, c)
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .background(c.bgFloating)
            .topEdge(shape, c.edgeLight)
            .pressable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { openProfile(user.id) }
            .semantics { contentDescription = user.name },
    ) {
        // a profile with no colour of its own takes its avatar's (profileTheme in lib/avatarColor.ts)
        Box(Modifier.fillMaxWidth().aspectRatio(17f / 6f).background(user.accentColor?.let { fish.nexa.ui.theme.rgbColor(it) } ?: AvatarColor.of(user.id))) {
            if (user.bannerId != null) AsyncImage(Media.bannerUrl(user.bannerId, 320, density), null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val presences by Nexa.presence.byId.collectAsState()
        Box(Modifier.padding(start = 9.dp).overlapAbove(50.dp).background(c.bgFloating, CircleShape).padding(5.dp)) {
            Avatar(user.id, user.name, user.avatarId, 80.dp, status = presences[user.id]?.status ?: "offline")
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(user.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(20f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 1)
            Txt(user.username, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textSubtle, maxLines = 1)
            if (user.aboutMe.isNotBlank()) Txt(user.aboutMe, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ProfileSkeleton() {
    val c = Nx.colors
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(1f, 0.55f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val shape = Radii.Lg
    Column(
        Modifier.width(320.dp).clip(shape).background(c.bgFloating).alpha(if (Nx.motion.reduced) 1f else pulse)
            .semantics { contentDescription = t("profileLink.loading") },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(17f / 6f).background(c.bgActive))
        Box(Modifier.padding(start = 9.dp).overlapAbove(50.dp).background(c.bgFloating, CircleShape).padding(5.dp)) {
            Box(Modifier.size(80.dp).background(c.bgActive, CircleShape))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.46f).height(16.dp).background(c.bgActive, RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(0.3f).height(12.dp).background(c.bgActive, RoundedCornerShape(6.dp)))
        }
    }
}

// ---- sticker and emoji packs (components/chat/PackCards.tsx) ----

/** The packs links lead to, as the server last said (lib/packLinks.ts), kept a few minutes. */
internal object SharedPacks {
    sealed interface Entry {
        data object Loading : Entry
        data class Ready(val pack: StickerPack, val at: Long) : Entry
        /** private, or gone */
        data class Missing(val at: Long) : Entry
        data class Failed(val at: Long) : Entry
    }

    private const val FRESH_MS = 5 * 60_000L
    private const val RETRY_MS = 30_000L

    val byId = mutableStateMapOf<String, Entry>()
    private val inflight = HashSet<String>()

    fun load(id: String, force: Boolean = false) {
        synchronized(inflight) { if (id in inflight) return }
        val cur = byId[id]
        val now = System.currentTimeMillis()
        val due = when (cur) {
            null -> true
            Entry.Loading -> false
            is Entry.Ready -> now - cur.at > FRESH_MS
            is Entry.Missing -> now - cur.at > FRESH_MS
            is Entry.Failed -> now - cur.at > RETRY_MS
        }
        if (!force && !due) return
        // what was shown stays while it is asked again
        if (cur !is Entry.Ready) byId[id] = Entry.Loading
        synchronized(inflight) { inflight += id }
        Nexa.scope.launch {
            try {
                byId[id] = Entry.Ready(Nexa.api.get("/stickers/packs/$id", StickerPack.serializer()), System.currentTimeMillis())
            } catch (e: Exception) {
                byId[id] = if (e is ApiError && e.status == 404) Entry.Missing(System.currentTimeMillis()) else Entry.Failed(System.currentTimeMillis())
            } finally {
                synchronized(inflight) { inflight -= id }
            }
        }
    }
}

/** Up to three cards under a message for the sticker and emoji packs it links to. */
@Composable
fun PackCards(text: String?) {
    val ids = remember(text) {
        val out = ArrayList<String>()
        for (url in cardLinksIn(text ?: "")) {
            val l = appLink(url) as? AppLink.Pack ?: continue
            if (l.id !in out) out += l.id
            if (out.size >= MAX_PACK_CARDS) break
        }
        out
    }
    if (ids.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (id in ids) PackLinkCard(id)
    }
}

/**
 * A pack's card by its id, loaded as a pack link's is. [quiet] draws nothing
 * for a pack that has gone or turned private (a profile's showcase, web
 * PackLinkCard's `quiet`).
 */
@Composable
fun PackLinkCard(id: String, quiet: Boolean = false) {
    LaunchedEffect(id) { SharedPacks.load(id) }
    val entry = SharedPacks.byId[id]
    if (quiet && (entry is SharedPacks.Entry.Missing || entry is SharedPacks.Entry.Failed)) return
    when (entry) {
        is SharedPacks.Entry.Missing -> GoneCard("PackageX", t("stickerPack.packUnavailable"), t("stickerPack.thisPackIsPrivate"))
        is SharedPacks.Entry.Failed -> GoneCard("PackageX", t("stickerPack.packUnavailable"), t("stickerPack.couldNotLoadThePack")) {
            NxButton(t("invite.tryAgain"), { SharedPacks.load(id, force = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "RotateCw")
        }
        is SharedPacks.Entry.Ready -> PackCard(entry.pack)
        else -> PackSkeleton()
    }
}

/** How many of a pack's stickers or emoji a card shows. */
private const val PACK_SHOWN = 8

/** One of a pack's stickers or emoji, still: the card is a preview, not a place for them to play. */
@Composable
private fun PackItem(s: Sticker, emoji: Boolean, size: Dp, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    val moving = s.animated || s.format == "tgs" || s.format == "webm"
    val url = if (emoji) Media.emojiUrl(s.blobId, size.value.toInt(), density, still = moving) else Media.stickerUrl(s.blobId, size.value.toInt(), density) + if (moving) "&still=1" else ""
    AsyncImage(url, null, imageLoader = Media.loader, contentScale = ContentScale.Fit, modifier = modifier.size(size))
}

/**
 * PackCards.tsx: the invite card's frame and type with no band. The pack's
 * cover leads, then what kind of pack it is, its name and how many it holds,
 * the button beside them (add it, or open the packs this account has), and a
 * row of the first few of what is in it.
 */
@Composable
private fun PackCard(pack: StickerPack) {
    val c = Nx.colors
    val shape = Radii.Lg
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val emoji = pack.kind == "emoji"
    val cover = pack.stickers.firstOrNull { it.id == pack.coverStickerId } ?: pack.stickers.firstOrNull()
    val added = pack.installed || pack.ownerUserId == Nexa.users.meId
    val open = { fish.nexa.ui.settings.Settings.open("stickers") }
    Column(
        Modifier.widthIn(max = 432.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.borderSubtle).clip(shape).background(c.embedBg).topEdge(shape, c.edgeLight)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).ring(RoundedCornerShape(17.dp), c.borderSubtle).clip(RoundedCornerShape(17.dp)).background(c.bgActive),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) PackItem(cover, emoji, if (emoji) 32.dp else 56.dp)
                else Txt(pack.name.take(1).uppercase(), style = Nx.type.body.copy(fontSize = Nx.type.sp(22f), fontWeight = FontWeight(700)), color = c.textStrong)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(if (emoji) t("stickerPack.emojiPack") else t("stickerPack.stickerPack"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                Txt(pack.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(680), lineHeight = Nx.type.sp(21.45f)), color = c.textStrong, maxLines = 2)
                Txt(
                    if (emoji) t("server.packEditor.emojiCount", "count" to pack.stickers.size) else t("server.packEditor.stickerCount", "count" to pack.stickers.size),
                    style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18f)), color = c.textMuted, maxLines = 1,
                )
            }
            if (added) NxButton(t("chat.embeds.open"), open, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
            else NxButton(t("stickerPack.installPack"), {
                busy = true
                scope.launch {
                    try {
                        val res = Nexa.api.request("PUT", "/stickers/packs/${pack.id}/install", StickerPack.serializer())
                        Nexa.expressions.upsertPack(res)
                        SharedPacks.byId[pack.id] = SharedPacks.Entry.Ready(res, System.currentTimeMillis())
                    } catch (e: Exception) {
                        Nexa.ui.toast((e as? ApiError)?.message?.takeIf { it.isNotBlank() } ?: t("server.packEditor.couldNotChangeThePack"), "error")
                    } finally {
                        busy = false
                    }
                }
            }, size = ButtonSize.SM, loading = busy)
        }
        if (pack.description.isNotBlank()) Txt(pack.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.text, maxLines = 2)
        val shown = pack.stickers.take(PACK_SHOWN)
        if (shown.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val box = if (emoji) 36.dp else 44.dp
            // color-mix(var(--bg-active) 70%, transparent): the tint's own alpha, at 70%
            for (s in shown) Box(Modifier.size(box).clip(RoundedCornerShape(if (emoji) 9.dp else 10.dp)).background(c.bgActive.copy(alpha = c.bgActive.alpha * 0.7f)), contentAlignment = Alignment.Center) {
                PackItem(s, emoji, if (emoji) 24.dp else box)
            }
            val more = pack.stickers.size - shown.size
            if (more > 0) Box(Modifier.height(box).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Txt("+$more", style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textMuted)
            }
        }
    }
}

/** A pack card's frame while the server is asked: the cover and two lines, quiet. */
@Composable
private fun PackSkeleton() {
    val c = Nx.colors
    val shape = Radii.Lg
    Row(
        Modifier.widthIn(max = 432.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.borderSubtle).clip(shape).background(c.embedBg).padding(16.dp)
            .semantics { contentDescription = t("stickerPack.loading") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.bgActive, RoundedCornerShape(17.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(150.dp).height(16.dp).background(c.bgActive, RoundedCornerShape(6.dp)))
            Box(Modifier.width(100.dp).height(12.dp).background(c.bgActive, RoundedCornerShape(6.dp)))
        }
    }
}


/** What each app link said, asked once a session: the app as its install page shows it, or null when it cannot be added. */
private val appLinkInfo = androidx.compose.runtime.mutableStateMapOf<String, fish.nexa.net.AuthorizeInfo?>()

/** Up to three cards under a message for the apps it links to the install page of (web AppLinkCards.tsx). */
@Composable
fun AppLinkCards(text: String?) {
    val links = remember(text) {
        val out = ArrayList<AppLink.App>()
        for (url in cardLinksIn(text ?: "")) {
            val l = appLink(url) as? AppLink.App ?: continue
            if (out.none { it.clientId == l.clientId }) out += l
            if (out.size >= MAX_APP_CARDS) break
        }
        out
    }
    if (links.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (l in links) AppLinkCard(l)
    }
}

/**
 * A pack card's shape: the app's picture, its name with the APP tag, Add App
 * beside them (the consent sheet over this conversation), and what the app
 * says about itself.
 */
@Composable
private fun AppLinkCard(link: AppLink.App) {
    LaunchedEffect(link.clientId) {
        if (link.clientId !in appLinkInfo) appLinkInfo[link.clientId] = runCatching { fish.nexa.data.Apps.authorizeInfo("client_id=${link.clientId}") }.getOrNull()
    }
    if (link.clientId !in appLinkInfo) return PackSkeleton()
    val info = appLinkInfo[link.clientId] ?: return GoneCard("Bot", t("apps.linkCard.goneTitle"), t("apps.linkCard.goneBody"))
    val app = info.application
    val c = Nx.colors
    val shape = Radii.Lg
    Column(
        Modifier.widthIn(max = 432.dp).fillMaxWidth().shadowMedium(shape, c).ring(shape, c.borderSubtle).clip(shape).background(c.embedBg).topEdge(shape, c.edgeLight)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            fish.nexa.ui.components.Avatar(app.id, app.name, app.iconId ?: app.bot?.avatarId, 56.dp, icon = app.iconId != null, shape = RoundedCornerShape(17.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(app.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(680), lineHeight = Nx.type.sp(21.45f)), color = c.textStrong, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                fish.nexa.ui.media.AppTag()
            }
            NxButton(t("apps.profile.addApp"), { fish.nexa.ui.apps.openAuthorize(link.query, fromApp = true) }, size = ButtonSize.SM)
        }
        if (app.description.isNotBlank()) Txt(app.description, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.text, maxLines = 2)
    }
}
