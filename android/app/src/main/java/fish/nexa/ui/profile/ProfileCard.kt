package fish.nexa.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.data.People
import fish.nexa.data.Perms
import fish.nexa.data.Send
import fish.nexa.media.Media
import fish.nexa.ui.media.LightboxItem
import fish.nexa.ui.media.openLightbox
import fish.nexa.net.Member
import fish.nexa.net.PublicUser
import fish.nexa.net.Relationship
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ServerIcon
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sink
import fish.nexa.ui.describeError
import fish.nexa.ui.dialogs.PromptField
import fish.nexa.ui.dialogs.promptFields
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.LocalColors
import fish.nexa.ui.theme.NexaColors
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.rgbColor
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The parts of the full profile behind its tabs. A phone has room for one column, so About is one of them. */
enum class ProfileTab { ABOUT, WIDGETS, ACTIVITY, MUTUALS }

/** What the profile endpoint adds to the user record: what you have in common, and their widgets. */
class ProfileExtras {
    var mutualFriends by mutableStateOf<List<String>>(emptyList())
    var mutualServers by mutableStateOf<List<String>>(emptyList())
    var widgets by mutableStateOf<List<Widgets.Widget>>(emptyList())
}

@Composable
fun rememberProfileExtras(userId: String, serverId: String? = null): ProfileExtras {
    val x = remember(userId, serverId) { ProfileExtras() }
    LaunchedEffect(userId, serverId) {
        try {
            val p = People.profile(userId, serverId)
            x.mutualFriends = p.mutualFriends
            x.mutualServers = p.mutualServers
            x.widgets = Widgets.read(p.widgets)
        } catch (_: Exception) {
            // a profile already on hand still shows
        }
    }
    return x
}

/**
 * Inside a server, what someone set for that server stands in for their own:
 * their name there, picture, banner, pronouns, About Me and colours.
 */
@Composable
fun withServerProfile(user: PublicUser, serverId: String?): PublicUser {
    val servers by Nexa.servers.byId.collectAsState()
    val m = serverId?.let { servers[it]?.members?.get(user.id) } ?: return user
    return user.copy(
        displayName = m.nickname.ifEmpty { null } ?: user.displayName,
        avatarId = m.avatarId ?: user.avatarId,
        bannerId = m.bannerId ?: user.bannerId,
        pronouns = m.pronouns.ifEmpty { null } ?: user.pronouns,
        aboutMe = m.bio.ifEmpty { null } ?: user.aboutMe,
        accentColor = if (m.accentColor != null) m.accentColor else user.accentColor,
        accentColor2 = if (m.accentColor != null) m.accentColor2 else user.accentColor2,
    )
}

/** Someone as the app knows them: the account's own record for yourself, else the users store. */
@Composable
fun rememberPerson(userId: String): PublicUser? {
    val me by Nexa.users.me.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    return if (me?.id == userId) me?.asPublic() else users[userId]
}

/** The colours one profile is drawn in: its two theme colours, or a glow from its banner or picture. */
class ProfileLook(val theme: ProfileTheme.Theme?, val tint: Color?, val tokens: ProfileTokens, val colors: NexaColors, val base: NexaColors)

@Composable
fun rememberProfileLook(user: PublicUser): ProfileLook {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val theme = remember(user.accentColor, user.accentColor2) { ProfileTheme.of(user.accentColor, user.accentColor2) }
    val tint = ProfileTheme.rememberDominant(if (theme != null) null else Media.bannerUrl(user.bannerId, 64, 1f) ?: Media.avatarUrl(user.avatarId, 64, 1f))
    return remember(c, theme, tint) { ProfileLook(theme, tint, ProfileTheme.tokens(c, theme, tint), ProfileTheme.colors(c, theme), c) }
}

/** The card's own background: the theme's gradient, the glow from the top, or the floating panel's colour. */
fun Modifier.profileBackground(look: ProfileLook): Modifier {
    val theme = look.theme
    val tint = look.tint
    return when {
        theme != null -> background(Brush.verticalGradient(listOf(theme.top, theme.bottom)))
        tint != null -> background(look.base.bgFloating).drawBehind {
            // radial-gradient(130% 75% at 30% 0%): the tint, gone by 72% of the way out
            val cx = size.width * 0.3f
            val rx = size.width * 1.3f
            val ry = size.height * 0.75f
            scale(1f, ry / rx, Offset(cx, 0f)) {
                drawCircle(
                    Brush.radialGradient(0f to tint.copy(alpha = if (look.base.isLight) 0.14f else 0.24f), 0.72f to Color.Transparent, center = Offset(cx, 0f), radius = rx),
                    radius = rx,
                    center = Offset(cx, 0f),
                )
            }
        }
        else -> background(look.base.bgFloating)
    }
}

/** Draws [content] with the card's colours over the app's. */
@Composable
fun ProfileColors(look: ProfileLook, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides look.colors, LocalProfile provides look.tokens, content = content)
}

private fun themedShadow(theme: ProfileTheme.Theme?): Shadow? = when (theme?.text) {
    "light" -> Shadow(Color.Black.copy(alpha = 0.28f), Offset(0f, 2f), 20f)
    "dark" -> Shadow(Color.White.copy(alpha = 0.35f), Offset(0f, 2f), 20f)
    else -> null
}

// ---------------------------------------------------------------------------
// The top: the banner with a hole cut for the avatar, its buttons, the status bubble

@Composable
private fun Banner(user: PublicUser, theme: ProfileTheme.Theme?, css: Int, holeX: Dp, holeY: Dp, holeR: Dp, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val url = Media.bannerUrl(user.bannerId, css, density)
    // with a theme and no picture the banner is see-through, so the gradient runs unbroken from the top
    val fill = if (url == null && theme == null) ProfileTheme.bannerFill(user.accentColor, null, user.id, c.brand) else null
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val x = if (rtl) size.width - holeX.toPx() else holeX.toPx()
                drawCircle(Color.Black, radius = holeR.toPx(), center = Offset(x, holeY.toPx()), blendMode = BlendMode.Clear)
            },
    ) {
        if (fill != null) Box(Modifier.fillMaxSize().background(fill))
        if (url != null) AsyncImage(url, null, Media.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // light from above, and a little shade where it meets the card
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.08f), 0.3f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f))))
    }
}

/** A round button laid over the banner, dark and frosted whatever the picture under it. */
@Composable
private fun BannerButton(icon: String, label: String, busy: Boolean = false, iconSize: Dp = 18.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(44.dp).pressable(interaction, enabled = !busy, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .sink(pressed)
                .boxShadow(CircleShape, 4f, 12f, Color.Black.copy(alpha = 0.25f))
                .ring(CircleShape, Color.White.copy(alpha = 0.1f))
                .background(Color(12, 8, 22).copy(alpha = if (pressed) 0.72f else 0.5f), CircleShape)
                .topEdge(CircleShape, Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) Spinner(Color.White, size = 16.dp) else Icon(icon, Color.White, size = iconSize)
        }
    }
}

/**
 * A custom status: a thought bubble beside the avatar, across the banner's
 * edge. A long one is cut to `lines` there, and a tap opens it to every word
 * (`open`, which the card lifts over what is below it).
 */
@Composable
private fun StatusBubble(custom: Presences.Custom, lines: Int, modifier: Modifier, design: ProfileDesign? = null, open: Boolean = false, onOpen: (Boolean) -> Unit = {}) {
    val c = Nx.colors
    val p = LocalProfile.current
    val reduced = Nx.motion.reduced
    val spring = Nx.motion.spring
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) shown.animateTo(1f, tween(360, delayMillis = 80, easing = spring)) }
    val style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(18f), fontWeight = FontWeight(500))
    val text = buildAnnotatedString {
        if (custom.emoji.isNotEmpty()) appendInlineContent("emoji", statusEmojiText(custom.emoji))
        append(custom.text)
    }
    // an 18 px emoji and 6 px after it, at 14 px text
    val inline = if (custom.emoji.isEmpty()) emptyMap() else mapOf(
        "emoji" to InlineTextContent(Placeholder((24f / 14f).em, (18f / 14f).em, PlaceholderVerticalAlign.Center)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { StatusEmojiImage(custom.emoji, 18.dp) }
        },
    )
    val bubble = RoundedCornerShape(18.dp)
    Box(modifier) {
        Box(
            Modifier.graphicsLayer {
                alpha = shown.value.coerceIn(0f, 1f)
                val s = 0.92f + 0.08f * shown.value
                scaleX = s
                scaleY = s
                translationY = (1f - shown.value) * 4f * density
            },
        ) {
            // the two small rounds that make it a thought
            Box(Modifier.offset((-13).dp, (-14).dp).size(5.dp).boxShadow(CircleShape, 2f, 6f, c.shadeAt(0.3f)).background(p.bubble, CircleShape))
            Box(Modifier.offset((-6).dp, (-8).dp).size(10.dp).boxShadow(CircleShape, 2f, 6f, c.shadeAt(0.3f)).background(p.bubble, CircleShape))
            Spotted(design, ProfilePart.STATUS, t("designer.spot.status")) {
                var cut by remember(custom) { mutableStateOf(false) }
                BasicText(
                    text,
                    Modifier
                        .then(
                            if (design == null && (cut || open)) Modifier.clip(bubble).pressable(remember { MutableInteractionSource() }) { onOpen(!open) }
                                .semantics { if (open) collapse { onOpen(false); true } else expand { onOpen(true); true } }
                            else Modifier,
                        )
                        .boxShadow(bubble, 6f, 18f, c.shadeAt(0.6f), spread = -6f)
                        .ring(bubble, c.borderSubtle)
                        .background(p.bubble, bubble)
                        .topEdge(bubble, c.edgeLight)
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                    style = style.copy(color = c.textStrong),
                    maxLines = if (open) Int.MAX_VALUE else lines,
                    overflow = TextOverflow.Ellipsis,
                    inlineContent = inline,
                    onTextLayout = { if (!open) cut = it.hasVisualOverflow },
                )
            }
        }
    }
}

/**
 * The banner (17:6), the avatar sitting in a hole cut out of it (60% over the
 * banner, 40% below), the buttons over it and the status bubble beside the avatar.
 */
@Composable
private fun ProfileTop(
    user: PublicUser,
    theme: ProfileTheme.Theme?,
    av: Dp,
    ring: Dp,
    avLeft: Dp,
    dot: Dp,
    status: String?,
    custom: Presences.Custom?,
    statusLines: Int,
    bannerCss: Int,
    onAvatar: (() -> Unit)?,
    design: ProfileDesign? = null,
    avatarLabel: String? = null,
    onBanner: (() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = Nx.colors
    var statusOpen by remember(custom) { mutableStateOf(false) }
    // an opened status runs over what is below the banner: drawn after it, and touched first
    BoxWithConstraints(Modifier.fillMaxWidth().zIndex(if (statusOpen) 1f else 0f)) {
        val bannerH = maxWidth * (6f / 17f)
        Box(Modifier.fillMaxWidth().height(bannerH + av * 0.4f)) {
            Spotted(
                design, ProfilePart.BANNER, if (user.bannerId != null) t("designer.spot.banner") else t("designer.spot.addBanner"),
                Modifier.fillMaxWidth().height(bannerH), kind = SpotKind.BANNER,
            ) {
                Banner(
                    user, theme, bannerCss, avLeft + av / 2, bannerH - av * 0.1f, av / 2 + ring,
                    if (onBanner != null) Modifier.fillMaxSize().pressable(remember { MutableInteractionSource() }, onClick = onBanner)
                        .semantics { contentDescription = t("profile.profileCard.viewBanner") }
                    else Modifier.fillMaxSize(),
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp), content = buttons)
            Box(Modifier.padding(start = avLeft).offset(y = bannerH - av * 0.6f)) {
                Spotted(design, ProfilePart.AVATAR, t("designer.spot.avatar"), kind = SpotKind.ROUND) {
                    Box(
                        Modifier
                            .boxShadow(CircleShape, 8f, 16f, c.shadeAt(0.55f))
                            .then(
                                // not clipped to the circle: the status dot sits over its edge
                                if (onAvatar != null) Modifier.pressable(remember { MutableInteractionSource() }, onClick = onAvatar)
                                    .semantics { contentDescription = avatarLabel ?: t("profile.profileCard.viewFullProfileOf", "name" to Names.display(user)) }
                                else Modifier,
                            ),
                    ) {
                        Avatar(user.id, Names.display(user), user.avatarId, av, status = status, dot = dot)
                    }
                }
            }
            val bubbleAt = Modifier.padding(start = avLeft + av + 14.dp, end = 16.dp).offset(y = bannerH - 18.dp)
            // in the designer, an empty status is a bubble waiting to be filled
            if (custom != null) StatusBubble(custom, statusLines, bubbleAt, design, statusOpen) { statusOpen = it }
            else if (design != null && ProfilePart.STATUS !in design.fixed) GhostBubble(design, bubbleAt)
        }
    }
}

// ---------------------------------------------------------------------------
// Who they are

/** A member of Nexa's staff's rank: its mark and name on a pill tinted with the accent. */
@Composable
private fun StaffBadge(userId: String, flags: Long) {
    val c = Nx.colors
    val rank = fish.nexa.data.StaffRanks.of(userId, flags) ?: return
    val icon = rank.icon
    val label = rank.label
    val tip = rank.tip
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .height(20.dp)
            .ring(shape, c.brand.copy(alpha = 0.3f))
            .background(c.brand.copy(alpha = 0.16f), shape)
            .semantics { contentDescription = "$label. $tip" }
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, c.textBrand, size = 12.dp)
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = c.textBrand, maxLines = 1)
    }
}

/** Their name (a nickname you gave first), their tag, the note button; then username, pronouns and staff badge. */
@Composable
private fun Identity(user: PublicUser, rel: Relationship?, full: Boolean, theme: ProfileTheme.Theme?, onName: (() -> Unit)?, onNote: (() -> Unit)?, noteLabel: String, design: ProfileDesign? = null) {
    val c = Nx.colors
    val friend = rel?.type == "friend"
    val nick = if (friend) rel?.nickname?.takeIf { it.isNotBlank() } else null
    val shownName = nick ?: Names.display(user)
    val shadow = themedShadow(theme)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val nameEditor = design?.name
            if (nameEditor != null) Box(Modifier.weight(1f, fill = false)) { nameEditor() }
            else Spotted(design, ProfilePart.NAME, t("designer.spot.name"), Modifier.weight(1f, fill = false)) {
                // in their own name style, when they have one
                fish.nexa.ui.chat.RoleName(
                    shownName,
                    fish.nexa.ui.chat.RoleLooks.withOwn(null, user.nameStyle),
                    style = Nx.type.name.copy(
                        fontSize = Nx.type.sp(if (full) 28f else 20f),
                        lineHeight = Nx.type.sp(if (full) 30.8f else 23f),
                        fontWeight = FontWeight(700),
                        letterSpacing = (-0.01).em,
                        shadow = shadow,
                    ),
                    fallback = c.textStrong,
                    maxLines = 1,
                    modifier = Modifier
                        .semantics { heading() }
                        .then(if (onName != null) Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }, onClick = onName) else Modifier),
                )
            }
            val tag = user.serverTag
            if (tag != null) Spotted(design, ProfilePart.TAG, t("designer.spot.tag"), kind = SpotKind.WORD) { fish.nexa.ui.chat.ServerTagChip(tag, small = false, open = design == null) }
            else if (design != null && ProfilePart.TAG !in design.fixed) GhostChip(design)
            // an app's bot says so, and that what it sends is not end-to-end encrypted
            if (user.isBot) fish.nexa.ui.media.HookTags(null, app = true)
            if (onNote != null) Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).pressable(remember { MutableInteractionSource() }, onClick = onNote).semantics { contentDescription = noteLabel },
                contentAlignment = Alignment.Center,
            ) { Icon("NotebookPen", c.textMuted, size = 18.dp) }
        }
        val line = Nx.type.bodySmall.copy(
            fontSize = Nx.type.sp(if (full) 15.5f else 13.5f),
            lineHeight = Nx.type.sp(if (full) 22f else 19f),
            shadow = shadow,
        )
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // a nickname you gave hides their own name, so it is added here (not twice)
            val display = user.displayName
            if (nick != null && display.isNotBlank() && display != nick && !display.equals(user.username, ignoreCase = true)) {
                Txt(display, style = line, color = c.textSubtle)
                Txt("·", style = line, color = c.textMuted)
            }
            Txt(user.username, style = line, color = c.textSubtle)
            val pronounsEditor = design?.pronouns
            if (pronounsEditor != null) {
                Txt("·", style = line, color = c.textMuted)
                pronounsEditor()
            } else if (user.pronouns.isNotBlank()) {
                Txt("·", style = line, color = c.textMuted)
                Spotted(design, ProfilePart.PRONOUNS, t("designer.spot.pronouns"), kind = SpotKind.WORD) {
                    Txt(user.pronouns, style = line, color = c.textSubtle, modifier = Modifier.semantics { contentDescription = "${t("settings.profile.pronouns")}: ${user.pronouns}" })
                }
            } else if (design != null) {
                Txt("·", style = line, color = c.textMuted)
                GhostInline(design, line)
            }
            StaffBadge(user.id, user.flags)
            fish.nexa.ui.support.PlanBadge(user.plan)
        }
    }
}

/** RelationshipBar.relationshipLine: what the bar says, also read out when it changes. */
fun relationshipLine(rel: Relationship?): String = when (rel?.type) {
    "incoming" -> t("friends.incomingFriendRequest")
    "outgoing" -> t("friends.outgoingFriendRequest")
    "blocked" -> t("people.request.blocked")
    else -> ""
}

/** Where things stand with someone who is not (yet) a friend: a request either way, or a block, with what can be done. */
@Composable
fun RelationshipBar(user: PublicUser, rel: Relationship?) {
    val c = Nx.colors
    val p = LocalProfile.current
    if (rel == null || rel.type == "friend") return
    val busy = People.isBusy(user.id)
    val icon = when (rel.type) {
        "incoming" -> "UserPlus"
        "outgoing" -> "Clock"
        else -> "Ban"
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .clip(Radii.Md)
            .background(p.chip)
            .topEdge(Radii.Md, c.edgeLight)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.widthIn(min = 150.dp).weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, c.textStrong.copy(alpha = 0.8f), size = 16.dp)
            Txt(
                relationshipLine(rel),
                style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600), lineHeight = Nx.type.sp(18f)),
                color = c.textStrong,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (rel.type) {
                "incoming" -> {
                    RequestButton(t("people.request.accept"), primary = true, enabled = !busy) { Nexa.scope.launch { People.accept(user) } }
                    RequestButton(t("friends.ignore"), label = t("people.menu.ignoreRequest"), enabled = !busy) { Nexa.scope.launch { People.dropRequest(user.id) } }
                }
                "outgoing" -> RequestButton(t("people.request.cancel"), label = t("people.menu.cancelRequest"), enabled = !busy) { Nexa.scope.launch { People.dropRequest(user.id) } }
                else -> RequestButton(t("friends.unblock"), enabled = !busy) { Nexa.scope.launch { People.unblock(user.id) } }
            }
        }
    }
}

@Composable
private fun RequestButton(text: String, primary: Boolean = false, label: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalProfile.current
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .height(40.dp)
            .sink(pressed)
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .background(if (primary) p.accent else p.chipHover, shape)
            .clip(shape)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, style = Nx.type.button.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(650)), color = if (primary) Color.White else c.textStrong, maxLines = 1)
    }
}

/** What you have in common, in a line: a few of the mutual friends' faces, and the counts. */
@Composable
private fun MutualRow(friends: List<String>, servers: Int, onClick: () -> Unit) {
    val c = Nx.colors
    val users by Nexa.users.byId.collectAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val words = listOfNotNull(
        if (friends.isNotEmpty()) t("profile.profileCard.mutualFriendCount", "count" to friends.size) else null,
        if (servers > 0) t("profile.profileCard.mutualServerCount", "count" to servers) else null,
    ).joinToString(" • ")
    Row(
        Modifier.heightIn(min = 40.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val faces = friends.take(3)
        if (faces.isNotEmpty()) Box(Modifier.width((20 + 14 * (faces.size - 1)).dp).height(20.dp)) {
            faces.forEachIndexed { i, id ->
                val last = i == faces.size - 1
                Box(
                    Modifier
                        .padding(start = (14 * i).dp)
                        .size(20.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // each face gives way to the one overlapping it
                            if (!last) drawCircle(Color.Black, radius = 12.dp.toPx(), center = Offset(if (rtl) -4.dp.toPx() else size.width + 4.dp.toPx(), size.height / 2), blendMode = BlendMode.Clear)
                        },
                ) { Avatar(id, Names.display(users[id]), users[id]?.avatarId, 20.dp) }
            }
        }
        Txt(words, style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(20f)), color = c.textSubtle)
    }
}

// ---------------------------------------------------------------------------
// What they say and what is known of them

@Composable
private fun SectionTitle(text: String) {
    Txt(
        text,
        style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), lineHeight = Nx.type.sp(16f), fontWeight = FontWeight(700), letterSpacing = 0.01.em),
        color = Nx.colors.textMuted,
        modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun SectionText(text: String) = Txt(text, style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(18f)), color = Nx.colors.text)

/** Your private note about someone, kept in your synced settings (256 characters). */
@Composable
private fun NoteSection(userId: String, focus: Boolean, onDone: () -> Unit) {
    val c = Nx.colors
    val doc by Nexa.settings.doc.collectAsState()
    val saved = ((doc["userNotes"] as? JsonObject)?.get(userId) as? JsonPrimitive)?.contentOrNull ?: ""
    var text by remember(userId) { mutableStateOf(saved) }
    val requester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // opened by its button: ready to type; left: it folds away again (when empty)
    var had by remember { mutableStateOf(false) }
    LaunchedEffect(focus) { if (focus) requester.requestFocus() }
    fun save(value: String) {
        val notes = LinkedHashMap((Nexa.settings.doc.value["userNotes"] as? JsonObject) ?: JsonObject(emptyMap()))
        if (value.isNotEmpty()) notes[userId] = JsonPrimitive(value) else notes.remove(userId)
        Nexa.settings.set("userNotes", JsonObject(notes))
    }
    Column {
        SectionTitle(t("profile.profileCard.note"))
        val style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(18f), color = c.text)
        BasicTextField(
            text,
            { v ->
                val next = v.take(256)
                text = next
                save(next)
            },
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) had = true
                    else if (had) {
                        had = false
                        onDone()
                    }
                }
                .then(if (focused) Modifier.ring(RoundedCornerShape(8.dp), c.focusRing.copy(alpha = 0.6f)) else Modifier)
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) c.bgInput else Color.Transparent)
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .semantics { contentDescription = t("profile.profileCard.note") },
            textStyle = style,
            interactionSource = interaction,
            cursorBrush = SolidColor(c.focusRing),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Txt(t("profile.profileCard.tapToAddANote"), style = style, color = c.textMuted)
                    inner()
                }
            },
        )
    }
}

/** Their about me; in the popout only its start, faded, with the way to the rest. */
@Composable
private fun AboutSection(about: String, clamp: Dp?, heading: Boolean, title: String = t("settings.profile.aboutMe"), onMore: (() -> Unit)?) {
    val c = Nx.colors
    var full by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }
    val over = clamp != null && full > shown + 1
    Column {
        if (heading) SectionTitle(title)
        val body = @Composable {
            fish.nexa.ui.markdown.MarkdownView(about, fish.nexa.ui.markdown.RenderCtx(color = c.text), Modifier.fillMaxWidth())
        }
        if (clamp == null) body()
        else Box(
            Modifier
                .heightIn(max = clamp)
                .clipToBounds()
                .onSizeChanged { shown = it.height }
                .then(
                    if (over) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    } else Modifier,
                ),
        ) {
            Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { full = it.height }) { body() }
        }
        if (over && onMore != null) Txt(
            t("profile.profileCard.viewFullBio"),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)),
            color = c.textBrand,
            modifier = Modifier.padding(top = 2.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }, onClick = onMore).padding(vertical = 8.dp),
        )
    }
}

/** About Me in the designer: written in place, a dashed section waiting to be written, or the words with a spot on them. */
@Composable
private fun DesignedAbout(user: PublicUser, design: ProfileDesign, clamp: Dp?) {
    val editor = design.about
    when {
        editor != null -> Column {
            SectionTitle(t("settings.profile.aboutMe"))
            editor()
        }
        user.aboutMe.isBlank() -> GhostSection(design)
        // a section's pencil sits small at its foot on a touch screen, as the short words' do (`.spotWrap`)
        else -> Spotted(design, ProfilePart.ABOUT, t("designer.spot.about"), Modifier.fillMaxWidth(), kind = SpotKind.WORD) {
            AboutSection(user.aboutMe, clamp = clamp, heading = clamp == null, onMore = null)
        }
    }
}

/** A role in its colour: a dot and the name, and (where it may be taken off) an x. */
@Composable
private fun RoleChip(name: String, color: Long, busy: Boolean, onRemove: (() -> Unit)?, removeLabel: String) {
    val c = Nx.colors
    val p = LocalProfile.current
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier
            .height(30.dp)
            .widthIn(max = 280.dp)
            .graphicsLayer { alpha = if (busy) 0.6f else 1f }
            .ring(shape, c.borderSubtle)
            .background(p.chip, shape)
            .topEdge(shape, c.edgeLight)
            .padding(start = 9.dp, end = if (onRemove != null) 3.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(12.dp).ring(CircleShape, c.shadeAt(0.18f), 2.dp).background(if (color != 0L) rgbColor(color) else c.textMuted, CircleShape))
        Txt(name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (onRemove != null) Box(
            Modifier.size(24.dp).clip(CircleShape).pressable(remember { MutableInteractionSource() }, enabled = !busy, onClick = onRemove).semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) { Icon("X", c.textMuted, size = 12.dp) }
    }
}

/** A member of a big server may not be loaded yet: they are asked for, once. */
@Composable
private fun rememberMember(serverId: String, userId: String): Member? {
    val servers by Nexa.servers.byId.collectAsState()
    val member = servers[serverId]?.members?.get(userId)
    var missing by remember(serverId, userId) { mutableStateOf(false) }
    LaunchedEffect(serverId, userId, member == null) {
        if (member != null || missing) return@LaunchedEffect
        try {
            Nexa.servers.upsertMember(Nexa.api.request("GET", "/servers/$serverId/members/$userId", Member.serializer()))
        } catch (_: Exception) {
            // not (or no longer) a member: there is nothing of theirs here to show
            missing = true
        }
    }
    return member
}

/**
 * Their roles in the server the profile was opened from, highest first. In
 * the popout, whoever may manage them takes one off with its x or gives
 * another with the + after them; the full profile lists them and when they joined.
 */
@Composable
private fun ServerRoles(serverId: String, userId: String, popout: Boolean) {
    val c = Nx.colors
    val p = LocalProfile.current
    val servers by Nexa.servers.byId.collectAsState()
    val st = servers[serverId] ?: return
    val member = rememberMember(serverId, userId) ?: return
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val roles = member.roleIds.mapNotNull { st.roles[it] }.filter { it.id != serverId }.sortedByDescending { it.position }
    val meId = Nexa.users.meId
    val mine = if (meId != null) Nexa.servers.memberHighest(st, meId) else 0 to false
    val theirs = Nexa.servers.memberHighest(st, userId)
    val canRoles = meId != null && Nexa.servers.canI(st, P.MANAGE_ROLES) && (meId == userId || Perms.canActOn(mine.first, theirs.first, mine.second, theirs.second))
    fun allowed(position: Int) = canRoles && Perms.canManageRole(mine.first, mine.second, position)
    fun change(roleId: String, on: Boolean) {
        busy = roleId
        scope.launch {
            try {
                val m = Nexa.api.request(if (on) "PUT" else "DELETE", "/servers/$serverId/members/$userId/roles/$roleId", Member.serializer())
                Nexa.servers.upsertMember(m)
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("server.memberAction.couldNotChangeRoles")), "error")
            } finally {
                busy = null
            }
        }
    }
    if (popout) {
        val addable = Nexa.servers.rolesByPosition(st).filter { it.id != serverId && it.id !in member.roleIds && allowed(it.position) }
        if (roles.isEmpty() && !(canRoles && addable.isNotEmpty())) return
        FlowRow(
            Modifier.semantics { contentDescription = t("profile.profileCard.rolesIn", "server" to st.server.name) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (r in roles) RoleChip(r.name, r.color, busy == r.id, if (allowed(r.position) && busy == null) ({ change(r.id, false) }) else null, t("profile.roles.remove", "role" to r.name))
            if (canRoles && addable.isNotEmpty()) Box(
                Modifier
                    .size(30.dp)
                    .ring(CircleShape, c.borderSubtle)
                    .background(p.chip, CircleShape)
                    .topEdge(CircleShape, c.edgeLight)
                    .clip(CircleShape)
                    .pressable(remember { MutableInteractionSource() }, enabled = busy == null) {
                        openMenu(addable.map { r -> MenuItem.Action(r.name) { change(r.id, true) } }, title = t("profile.roles.add"))
                    }
                    .semantics { contentDescription = t("profile.roles.add") },
                contentAlignment = Alignment.Center,
            ) { Icon("Plus", c.textMuted, size = 14.dp) }
        }
        return
    }
    Column {
        SectionTitle(t("profile.profileCard.rolesIn", "server" to st.server.name))
        if (roles.isEmpty()) Txt(t("profile.profileCard.noRoles"), style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(18f)), color = c.textMuted)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in roles) RoleChip(r.name, r.color, false, null, "")
        }
    }
    Column {
        SectionTitle(t("profile.profileCard.joinedServer", "server" to st.server.name))
        SectionText(Time.shortDate(member.joinedAt))
    }
}

/** What is known of them: roles here, the key, when they joined and when you became friends. */
@Composable
private fun Facts(user: PublicUser, rel: Relationship?, isMe: Boolean, serverId: String?, interactive: Boolean = true) {
    val c = Nx.colors
    if (serverId != null) ServerRoles(serverId, user.id, popout = false)
    if (user.publicKey.isNotEmpty() && interactive) Column {
        SectionTitle(t("encryption.encryptionKey"))
        KeyStatusChip(user.id)
    }
    val friendsSince = if (!isMe && rel?.type == "friend" && rel.createdAt > 0) rel.createdAt else null
    FlowRow(
        Modifier.fillMaxWidth().drawBehind { drawRect(c.borderSubtle, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            SectionTitle(t("profile.profileCard.memberSince"))
            SectionText(Time.shortDate(user.createdAt))
        }
        if (friendsSince != null) Column {
            SectionTitle(t("profile.profileCard.friendsSince"))
            SectionText(Time.shortDate(friendsSince))
        }
    }
}

/** The popout's own way to say something: a message sent in your DM with them, which then opens. */
@Composable
private fun MessageBox(user: PublicUser, name: String, onSent: () -> Unit) {
    val c = Nx.colors
    var text by remember(user.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = t("profile.popout.message", "name" to name)
    fun send() {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true
        Nexa.scope.launch {
            try {
                val ch = People.openDm(user.id)
                // sealed and sent as the composer would; it shows in the DM at once, and says so there if it fails
                Send.sendMessage(ch.id, body)
                text = ""
                onSent()
                Nav.go(Route.Dm(ch.id))
            } catch (e: Exception) {
                Nexa.ui.toast(dmOpenError(e, t("profile.profileCard.couldNotOpenAConversation")), "error")
            } finally {
                sending = false
            }
        }
    }
    val shape = Radii.Sm
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(if (focused) Modifier.ring(shape, c.focusRing.copy(alpha = 0.28f), 3.dp) else Modifier)
            .background(c.bgInput, shape)
            .ring(shape, if (focused) c.focusRing.copy(alpha = 0.75f) else c.borderSubtle)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = Nx.type.input.copy(fontSize = Nx.type.sp(14.5f), color = c.text)
        BasicTextField(
            text,
            { text = it.take(4000) },
            Modifier.weight(1f).semantics { contentDescription = label },
            textStyle = style,
            singleLine = true,
            interactionSource = interaction,
            cursorBrush = SolidColor(c.focusRing),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Txt(label, style = style, color = c.textPlaceholder, maxLines = 1)
                    inner()
                }
            },
        )
        val can = text.isNotBlank() && !sending
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .pressable(remember { MutableInteractionSource() }, enabled = can) { send() }
                .semantics { contentDescription = t("profile.popout.send") }
                .graphicsLayer { alpha = if (can || sending) 1f else 0.4f },
            contentAlignment = Alignment.Center,
        ) {
            if (sending) Spinner(c.textMuted, size = 16.dp) else Icon("SendHorizontal", if (can) c.textStrong else c.textMuted, size = 16.dp)
        }
    }
}

// ---------------------------------------------------------------------------
// The More menu

private fun editNickname(user: PublicUser, rel: Relationship) {
    promptFields(
        title = t("profile.profileCard.friendNickname"),
        fields = listOf(PromptField(key = "nickname", label = t("modals.serverProfile.nickname"), initial = rel.nickname, placeholder = user.displayName, maxLength = 32, hint = t("profile.profileCard.onlyYouSeeIt"))),
        confirmLabel = t("chat.editMessage.save"),
        allowEmpty = true,
    ) { values ->
        val next = values["nickname"]?.trim() ?: return@promptFields
        if (next == rel.nickname) return@promptFields
        Nexa.scope.launch {
            try {
                People.setNickname(user.id, next)
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("profile.profileCard.couldNotSaveTheNickname")), "error")
            }
        }
    }
}

/** View Full Profile and Copy Profile Link (popout), the friend's nickname and removal, Block or Unblock, Copy User ID. */
fun openProfileMenu(user: PublicUser, popout: Boolean, serverId: String? = null, viewFull: () -> Unit) {
    val rel = Nexa.relationships.byId.value[user.id]
    val items = ArrayList<MenuItem>()
    items += fish.nexa.ui.reports.reportItem(fish.nexa.data.ReportContext(kind = "user", id = user.id, serverId = serverId))
    if (user.isBot) items += fish.nexa.ui.reports.reportItem(fish.nexa.data.ReportContext(kind = "application", id = user.id))
    if (popout) {
        items += MenuItem.Action(t("profile.profileCard.viewFullProfile")) { viewFull() }
        items += MenuItem.Action(t("profileLink.copy"), separatorAfter = true) { ProfileLinks.copy(user.username) }
    }
    if (rel?.type == "friend") {
        items += MenuItem.Action(if (rel.nickname.isNotBlank()) t("profile.profileCard.editFriendNickname") else t("profile.profileCard.addFriendNickname")) { editNickname(user, rel) }
        // asked first, as the friends list asks: neither is quick to take back
        items += MenuItem.Action(t("profile.profileCard.removeFriend"), danger = true) { confirmRemoveFriend(user) }
    }
    items += if (rel?.type == "blocked") MenuItem.Action(t("friends.unblock"), separatorAfter = true) { Nexa.scope.launch { People.unblock(user.id) } }
    else MenuItem.Action(t("profile.profileCard.block"), danger = true, separatorAfter = true) { openBlockDialog(user.id) }
    items += MenuItem.Action(t("chat.groupMemberMenu.copyUserId")) { copyWithToast(user.id, t("clipboard.idCopied")) }
    items += copyBotIdItem(user.id)
    openMenu(items, title = Names.display(user))
}

// ---------------------------------------------------------------------------
// The two cards

/** What an app says about itself, for its bot's profile; null for a person, or while it loads. */
@Composable
private fun rememberBotApp(user: PublicUser, active: Boolean): fish.nexa.net.AppSummary? {
    val app by androidx.compose.runtime.produceState<fish.nexa.net.AppSummary?>(null, user.id, user.isBot, active) {
        value = if (user.isBot && active) runCatching { fish.nexa.data.Apps.about(user.id) }.getOrNull() else null
    }
    return app
}

/** Whether an app can be added from its bot's profile: anyone may add a public one, its owner any. */
private fun canAddApp(app: fish.nexa.net.AppSummary?): Boolean = app != null && (app.botPublic || app.ownerId == Nexa.users.meId)

/** The app's own consent page, with its install settings. */
private fun addApp(appId: String) = fish.nexa.ui.apps.openAuthorize("client_id=$appId", fromApp = true)

/** Opens your own profile in Settings, where it is changed. */
private fun editOwnProfile(anchor: String? = null) = fish.nexa.ui.settings.Settings.open("profiles", anchor)

/**
 * The popout a name or a picture opens (a sheet from the bottom on a phone):
 * who they are, what you have in common, a few lines of their own, what they
 * are doing, their first widget in a line, their roles here, and a box to
 * message them. Everything else is on the full profile.
 */
@Composable
fun ProfilePopoutCard(user: PublicUser, serverId: String?, close: () -> Unit, design: ProfileDesign? = null, previewWidgets: List<Widgets.Widget>? = null) {
    val servers by Nexa.servers.byId.collectAsState()
    val here = if (design != null) null else serverId?.takeIf { servers[it] != null }
    @Suppress("NAME_SHADOWING") val user = if (here != null) withServerProfile(user, here) else user
    val look = rememberProfileLook(user)
    val extras = rememberProfileExtras(user.id, here)
    val rels by Nexa.relationships.byId.collectAsState()
    val presence by Nexa.presence.byId.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val rel = if (design != null) null else rels[user.id]
    val isMe = user.id == Nexa.users.meId
    val pres = presence[user.id]
    // the designer's preview: online, the status as set, nothing being done, nothing to press but its parts
    val status = if (design != null) "online" else if (isMe) pres?.status ?: "online" else pres?.status ?: "offline"
    val custom = if (design != null) previewStatus(user) else Presences.customStatus(pres)
    val activities = if (design != null) emptyList() else Presences.activitiesOf(pres)
    val widgets = previewWidgets ?: extras.widgets
    val note = ((doc["userNotes"] as? JsonObject)?.get(user.id) as? JsonPrimitive)?.contentOrNull ?: ""
    var noteOpen by remember { mutableStateOf(false) }
    val busy = People.isBusy(user.id)
    val inServer = serverId?.takeIf { servers[it] != null }
    val mutualServers = extras.mutualServers.filter { servers[it] != null }
    fun viewFull(tab: ProfileTab? = null) = Profiles.openFull(user.id, tab)
    // an app's bot: what the app says about itself, and whether it can be added from here
    val bot = user.isBot
    val app = rememberBotApp(user, design == null)
    val about = user.aboutMe.ifBlank { if (bot) app?.description.orEmpty() else "" }
    ProfileColors(look) {
        Column(Modifier.fillMaxWidth().profileBackground(look)) {
            ProfileTop(
                user, look.theme, av = 80.dp, ring = 5.dp, avLeft = 14.dp, dot = 18.dp,
                status = status, custom = custom, statusLines = 2, bannerCss = 600,
                onAvatar = if (design != null) null else ({ viewFull() }),
                design = design,
            ) {
                if (design == null) {
                    if (isMe) BannerButton("Pencil", t("profile.profileCard.editProfile"), iconSize = 16.dp) { close(); editOwnProfile() }
                    else if (bot) Unit // Add App is across the card, over the message box
                    else if (rel == null) BannerButton("UserPlus", if (busy) t("people.request.sending") else t("profile.profileCard.addFriend"), busy = busy) { Nexa.scope.launch { People.sendRequest(user) } }
                    BannerButton("Maximize2", t("profile.profileCard.viewFullProfile"), iconSize = 16.dp) { viewFull() }
                    if (!isMe) BannerButton("MoreHorizontal", t("chat.chat.more")) { openProfileMenu(user, popout = true, serverId = serverId) { viewFull() } }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Identity(
                    user, rel, full = false, theme = look.theme,
                    onName = if (design != null) null else ({ viewFull() }),
                    onNote = if (isMe || design != null) null else ({ noteOpen = !noteOpen }),
                    noteLabel = if (note.isNotEmpty()) t("profile.profileCard.editNote2") else t("profile.profileCard.addNote2"),
                    design = design,
                )
                if (!isMe && !bot) RelationshipBar(user, rel)
                if (!isMe && extras.mutualFriends.size + mutualServers.size > 0) MutualRow(extras.mutualFriends, mutualServers.size) { viewFull(ProfileTab.MUTUALS) }
                val noteShown = !isMe && (noteOpen || note.isNotEmpty())
                if (noteShown || about.isNotBlank() || design != null || activities.isNotEmpty() || widgets.isNotEmpty() || inServer != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (noteShown) NoteSection(user.id, focus = noteOpen) { noteOpen = false }
                        if (design != null) DesignedAbout(user, design, clamp = 62.dp)
                        else if (about.isNotBlank()) AboutSection(about, clamp = 62.dp, heading = false) { viewFull() }
                        if (activities.isNotEmpty()) ActivityList(activities)
                        if (widgets.isNotEmpty()) WidgetPeek(widgets, if (design != null) null else ({ viewFull(ProfileTab.WIDGETS) }))
                        if (inServer != null && design == null) ServerRoles(inServer, user.id, popout = true)
                    }
                }
                // an app's card leads with adding it, the one thing people open it for
                if (bot && design == null && canAddApp(app)) PrimaryPill("CirclePlus", t("apps.profile.addApp"), modifier = Modifier.fillMaxWidth()) { close(); addApp(user.id) }
                if (!isMe && rel?.type != "blocked" && design == null) {
                    val nick = if (rel?.type == "friend") rel.nickname.takeIf { it.isNotBlank() } else null
                    MessageBox(user, name = nick ?: Names.display(user), onSent = close)
                }
            }
        }
    }
}

/** The status the designer's preview shows: the one set on the account. */
private fun previewStatus(user: PublicUser): Presences.Custom? =
    if (user.statusText.isNotBlank() || user.statusEmoji.isNotBlank()) Presences.Custom(user.statusText, user.statusEmoji) else null

/** The full profile's main button: the accent made into light, as the web's .primary. */
@Composable
private fun PrimaryPill(icon: String?, text: String, busy: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(40.dp)
            .sink(pressed && !busy)
            .graphicsLayer { alpha = if (busy) 0.65f else 1f }
            .boxShadow(shape, 6f, 18f, p.accent.copy(alpha = 0.9f), spread = -8f)
            .background(Brush.verticalGradient(0f to mixOklab(p.accent, Color.White, 0.14f), 0.6f to p.accent, 1f to p.accent), shape)
            .topEdge(shape, Color.White.copy(alpha = 0.22f))
            .clip(shape)
            .pressable(interaction, enabled = !busy, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (busy) Spinner(Color.White, size = 16.dp) else if (icon != null) Icon(icon, Color.White, size = 16.dp)
        Txt(text, style = Nx.type.button.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = Color.White, maxLines = 1)
    }
}

/** A round button beside it, in the card's chip colour. */
@Composable
private fun RoundAction(icon: String, label: String, onClick: () -> Unit) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(40.dp)
            .sink(pressed)
            .background(if (pressed) p.chipHover else p.chip, CircleShape)
            .topEdge(CircleShape, c.edgeLight)
            .clip(CircleShape)
            .pressable(interaction, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, c.textStrong, size = 18.dp) }
}

private fun tabLabel(tab: ProfileTab) = when (tab) {
    ProfileTab.ABOUT -> t("profile.tabs.about")
    ProfileTab.WIDGETS -> t("widgets.title")
    ProfileTab.ACTIVITY -> t("profile.tabs.activity")
    ProfileTab.MUTUALS -> t("profile.tabs.mutuals")
}

/** The bar holding the tabs; on a themed card it takes the card's sheet colour. */
@Composable
private fun TabsBar(tabs: List<ProfileTab>, tab: ProfileTab, mutuals: Int, themed: Boolean, modifier: Modifier = Modifier, onPick: (ProfileTab) -> Unit) {
    val c = Nx.colors
    val p = LocalProfile.current
    Box(
        modifier
            .fillMaxWidth()
            .background(if (themed) p.sheet else c.bgFloating.copy(alpha = 0.94f))
            .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    ) {
        fish.nexa.ui.components.TabStrip(
            tabs.map { it to tabLabel(it) }, tab, t("profile.tabs.label"),
            tabHeight = 36.dp,
            count = { if (it == ProfileTab.MUTUALS && mutuals > 0) mutuals else null },
            onSelect = onPick,
        )
    }
}

@Composable
private fun EmptyTab(text: String, personId: String? = null, personName: String = "") {
    fish.nexa.ui.chat.PersonText(personId,
        text, personName,
        style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(20.3f), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        color = Nx.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
    )
}

/** A person or a server in the Mutuals tab. */
@Composable
private fun ListItem(onClick: () -> Unit, picture: @Composable () -> Unit, name: String, personId: String? = null) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) c.bgHover else Color.Transparent)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        picture()
        fish.nexa.ui.chat.PersonName(personId, name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(550)), color = c.textStrong, maxLines = 1)
    }
}

/**
 * The full profile, as a phone holds it: one column, the person at the top,
 * then the tabs, which stay at the top of the sheet as it scrolls, so another
 * tab is a tap away however far down one has been read. About holds what the
 * computer's left card says. [embedded] draws it as a card inside a page that
 * scrolls itself (the profile designer), with [design] making its parts
 * pressable and [previewWidgets] the widgets as they are being edited.
 */
@Composable
fun FullProfileCard(
    user: PublicUser,
    initialTab: ProfileTab?,
    serverId: String?,
    close: () -> Unit,
    design: ProfileDesign? = null,
    previewWidgets: List<Widgets.Widget>? = null,
    embedded: Boolean = false,
    /** Changed to show [initialTab] again on a card already drawn (the designer's parts). */
    tabNonce: Int = 0,
) {
    val servers by Nexa.servers.byId.collectAsState()
    val here = if (design != null) null else serverId?.takeIf { servers[it] != null }
    @Suppress("NAME_SHADOWING") val user = if (here != null) withServerProfile(user, here) else user
    val look = rememberProfileLook(user)
    val extras = rememberProfileExtras(user.id, here)
    val rels by Nexa.relationships.byId.collectAsState()
    val presence by Nexa.presence.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val preview = design != null
    val rel = if (preview) null else rels[user.id]
    val isMe = user.id == Nexa.users.meId
    val pres = presence[user.id]
    val status = if (preview) "online" else if (isMe) pres?.status ?: "online" else pres?.status ?: "offline"
    val custom = if (preview) previewStatus(user) else Presences.customStatus(pres)
    val activities = if (preview) emptyList() else Presences.activitiesOf(pres)
    val widgets = previewWidgets ?: extras.widgets
    val note = ((doc["userNotes"] as? JsonObject)?.get(user.id) as? JsonPrimitive)?.contentOrNull ?: ""
    var noteOpen by remember { mutableStateOf(false) }
    val busy = People.isBusy(user.id)
    val inServer = if (preview) null else serverId?.takeIf { servers[it] != null }
    val mutualServers = extras.mutualServers.filter { servers[it] != null }
    val mutualCount = if (preview) 0 else extras.mutualFriends.size + mutualServers.size
    val friend = rel?.type == "friend"
    val shownName = (if (friend) rel?.nickname?.takeIf { it.isNotBlank() } else null) ?: Names.display(user)
    val tabs = buildList {
        add(ProfileTab.ABOUT)
        add(ProfileTab.WIDGETS)
        add(ProfileTab.ACTIVITY)
        if (!isMe && mutualCount > 0) add(ProfileTab.MUTUALS)
    }
    var chosen by remember(user.id, tabNonce) { mutableStateOf(initialTab) }
    val tab = chosen?.takeIf { it in tabs } ?: ProfileTab.ABOUT
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var tabsTop by remember { mutableIntStateOf(Int.MAX_VALUE) }
    fun pick(next: ProfileTab) {
        chosen = next
        // the tabs may be scrolled out of sight by the time something below picks one
        if (!embedded && scroll.value > tabsTop) scope.launch { scroll.animateScrollTo(tabsTop) }
    }
    fun message() = openDmWith(user.id) { close() }
    // an app's bot: what the app says about itself, and whether it can be added from here
    val bot = user.isBot
    val app = rememberBotApp(user, !preview)
    val about = user.aboutMe.ifBlank { if (bot) app?.description.orEmpty() else "" }
    fun editWidgets() {
        close()
        editOwnProfile("widgets")
    }

    @Composable
    fun Body(tabsModifier: Modifier) {
        val c = Nx.colors
        val p = LocalProfile.current
        ProfileTop(
            user, look.theme, av = 120.dp, ring = 7.dp, avLeft = 24.dp, dot = 26.dp,
            status = status, custom = custom, statusLines = 3, bannerCss = 600,
            onAvatar = if (design != null || user.avatarId == null) null else ({
                Media.avatarOriginalUrl(user.avatarId)?.let { openLightbox(listOf(LightboxItem(Names.display(user), src = it)), 0) }
            }),
            design = design, avatarLabel = t("profile.profileCard.viewProfilePicture"),
            onBanner = if (design != null || user.bannerId == null) null else ({
                Media.bannerOriginalUrl(user.bannerId)?.let { openLightbox(listOf(LightboxItem(Names.display(user), src = it)), 0) }
            }),
        ) {
            if (!embedded) BannerButton("X", t("chat.attachments.close")) { close() }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Identity(
                user, rel, full = true, theme = look.theme, onName = null,
                onNote = if (isMe || preview) null else ({
                    noteOpen = !noteOpen
                    if (noteOpen) pick(ProfileTab.ABOUT)
                }),
                noteLabel = if (note.isNotEmpty()) t("profile.profileCard.editNote2") else t("profile.profileCard.addNote2"),
                design = design,
            )
            if (!isMe && !bot) RelationshipBar(user, rel)
            if (!preview) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                when {
                    isMe -> PrimaryPill("Pencil", t("profile.profileCard.editProfile")) { close(); editOwnProfile() }
                    bot -> if (canAddApp(app)) PrimaryPill("CirclePlus", t("apps.profile.addApp")) { close(); addApp(user.id) } else PrimaryPill("MessageCircle", t("profile.profileCard.message")) { message() }
                    rel?.type == "blocked" -> Unit
                    rel != null -> PrimaryPill("MessageCircle", t("profile.profileCard.message")) { message() }
                    else -> PrimaryPill("UserPlus", if (busy) t("people.request.sending") else t("profile.profileCard.addFriend"), busy = busy) {
                        Nexa.scope.launch { People.sendRequest(user) }
                    }
                }
                if (!isMe && rel == null && !(bot && !canAddApp(app))) RoundAction("MessageCircle", t("profile.profileCard.message")) { message() }
                RoundAction("Link2", t("profileLink.copy")) { ProfileLinks.copy(user.username) }
                if (!isMe) RoundAction("MoreHorizontal", t("chat.chat.more")) { openProfileMenu(user, popout = false, serverId = serverId) {} }
            }
            if (!isMe && mutualCount > 0) MutualRow(extras.mutualFriends, mutualServers.size) { pick(ProfileTab.MUTUALS) }
        }
        TabsBar(tabs, tab, mutualCount, look.theme != null, tabsModifier) { pick(it) }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .ring(Radii.Md, c.borderSubtle)
                    .background(p.sheet, Radii.Md)
                    .topEdge(Radii.Md, c.edgeLight)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (tab) {
                    ProfileTab.ABOUT -> {
                        if (!isMe && !preview && (noteOpen || note.isNotEmpty())) NoteSection(user.id, focus = noteOpen) { noteOpen = false }
                        if (design != null) DesignedAbout(user, design, clamp = null)
                        else if (about.isNotBlank()) AboutSection(about, clamp = null, heading = true, onMore = null, title = if (bot && user.aboutMe.isBlank()) t("apps.profile.about") else t("settings.profile.aboutMe"))
                        Facts(user, rel, isMe, inServer, interactive = !preview)
                    }
                    ProfileTab.WIDGETS -> {
                        if (isMe && !preview && widgets.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Txt(t("profile.tabs.yourWidgets"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted, modifier = Modifier.semantics { heading() })
                            ChipButton("Pencil", t("profile.tabs.editWidgets")) { editWidgets() }
                        }
                        val tools = design?.widgets
                        ProfileWidgets(widgets, onAdd = tools?.add ?: if (isMe && !preview) ({ editWidgets() }) else null, design = tools)
                        if (widgets.isEmpty() && !isMe) EmptyTab(t("profile.tabs.noWidgets", "name" to fish.nexa.ui.chat.PERSON_SLOT), user.id, shownName)
                    }
                    ProfileTab.ACTIVITY -> {
                        if (activities.isNotEmpty()) ActivityList(activities)
                        else EmptyTab(if (isMe) t("profile.tabs.noActivityOwn") else t("profile.tabs.noActivity", "name" to fish.nexa.ui.chat.PERSON_SLOT), user.id, shownName)
                    }
                    ProfileTab.MUTUALS -> {
                        if (extras.mutualFriends.isNotEmpty()) Column {
                            SectionTitle(t("profile.profileCard.mutualFriendsCount", "count" to extras.mutualFriends.size))
                            for (id in extras.mutualFriends) ListItem({ Profiles.openFull(id) }, { Avatar(id, Names.display(users[id]), users[id]?.avatarId, 32.dp) }, Names.display(users[id]), personId = id)
                        }
                        if (mutualServers.isNotEmpty()) Column {
                            SectionTitle(t("profile.profileCard.mutualServersCount", "count" to mutualServers.size))
                            for (id in mutualServers) {
                                val sv = servers[id]?.server ?: continue
                                ListItem({ close(); Nav.go(Route.Server(id)) }, { ServerIcon(id, sv.name, sv.iconId, 32.dp, radius = 10.dp) }, sv.name)
                            }
                        }
                    }
                }
            }
        }
    }

    ProfileColors(look) {
        if (embedded) {
            val c = Nx.colors
            Column(
                Modifier
                    .fillMaxWidth()
                    .boxShadow(Radii.Lg, 12f, 32f, c.shadeAt(0.6f), spread = -12f)
                    .ring(Radii.Lg, c.borderSubtle)
                    .clip(Radii.Lg)
                    .profileBackground(look),
            ) { Body(Modifier) }
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Column(Modifier.fillMaxWidth().heightIn(min = viewport).profileBackground(look)) {
                    Body(Modifier.onGloballyPositioned { tabsTop = it.positionInParent().y.toInt() })
                }
            }
            // stuck to the top once scrolled past
            if (scroll.value >= tabsTop) TabsBar(tabs, tab, mutualCount, look.theme != null, Modifier.align(Alignment.TopCenter)) { pick(it) }
        }
    }
}
