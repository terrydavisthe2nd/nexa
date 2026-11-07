package fish.nexa.ui.chat

import fish.nexa.ui.collectEntryAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import fish.nexa.data.NameStyle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonElement
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ServerState
import fish.nexa.media.Media
import fish.nexa.media.TwemojiImage
import fish.nexa.net.Role
import fish.nexa.ui.components.Txt
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.rgbColor

/** A role's picture beside names: one of its own, an emoji, or one of the server's emoji (lib/roleLook.ts). */
sealed interface RoleIconInfo {
    val name: String
    data class Image(val id: String, override val name: String) : RoleIconInfo
    data class Emoji(val text: String, override val name: String) : RoleIconInfo
    data class Custom(val id: String, override val name: String) : RoleIconInfo
}

/** How a member's name is drawn in a server: their highest coloured role's colours, and their highest role's icon. */
data class RoleLook(
    val color: Color?,
    /** Two or three colours: the name is drawn in a gradient of them. */
    val colors: List<Color>? = null,
    /** Three colours shimmer across the name. */
    val shimmer: Boolean = false,
    val icon: RoleIconInfo? = null,
    /** The person's own name style (data/NameStyle): its font always shows, its colours only without a role colour. */
    val own: NameStyle? = null,
)

object RoleLooks {
    /** A role's own icon, if it has one. */
    fun iconOf(r: Role): RoleIconInfo? = when {
        r.iconId != null -> RoleIconInfo.Image(r.iconId, r.name)
        r.iconEmoji.startsWith("custom:") -> RoleIconInfo.Custom(r.iconEmoji.removePrefix("custom:"), r.name)
        r.iconEmoji.isNotEmpty() -> RoleIconInfo.Emoji(r.iconEmoji, r.name)
        else -> null
    }

    /** The colours a role draws names in: one, or a gradient of two or three. */
    fun colorsOf(r: Role): List<Color> {
        if (r.color == 0L) return emptyList()
        val out = arrayListOf(rgbColor(r.color))
        val c2 = r.color2
        val c3 = r.color3
        if (c2 != null) out += rgbColor(c2)
        if (c2 != null && c3 != null) out += rgbColor(c3)
        return out
    }

    /** A look with the person's own name style added, from the name_style the server sent. */
    fun withOwn(look: RoleLook?, nameStyle: JsonElement?): RoleLook? {
        val own = NameStyle.read(nameStyle) ?: return look
        return (look ?: RoleLook(color = null)).copy(own = own)
    }

    /** Visit only this member's roles. Hashing a ServerState here visits the entire roster. */
    fun of(st: ServerState?, userId: String): RoleLook? {
        if (st == null) return null
        val m = st.members[userId]
        var colored: Role? = null
        var iconed: Role? = null
        for (id in m?.roleIds ?: emptyList()) {
            val r = st.roles[id] ?: continue
            if (r.color != 0L && (colored == null || r.position > colored.position)) colored = r
            if ((r.iconId != null || r.iconEmoji.isNotEmpty()) && (iconed == null || r.position > iconed.position)) iconed = r
        }
        val look = if (colored == null && iconed == null) null else {
            val colors = colored?.let { colorsOf(it) } ?: emptyList()
            RoleLook(
                color = colors.firstOrNull(),
                colors = colors.takeIf { it.size > 1 },
                shimmer = colors.size > 2,
                icon = iconed?.let { iconOf(it) },
            )
        }
        return look
    }
}

/**
 * A name in its roles' colours (`.role-gradient`, `.role-shimmer`): one
 * colour, a gradient across the name, or three colours drifting along it,
 * still with Reduce Motion on. The person's own name style sets its font, and
 * draws its effect where no role colours the name (styles/nameStyle.css).
 */
@Composable
fun RoleName(text: String, look: RoleLook?, style: TextStyle, fallback: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val own = look?.own
    val styled = nameTextStyle(style, own)
    if (look?.color == null && own != null && own.colors.isNotEmpty()) {
        OwnName(text, own, styled, modifier, maxLines)
        return
    }
    val colors = look?.colors
    if (colors == null) {
        Txt(text, style = styled, color = look?.color ?: fallback, maxLines = maxLines, modifier = modifier)
        return
    }
    val animateNames = fish.nexa.ui.shell.settingOn("animateNames")
    val phase = if (look.shimmer && animateNames && !Nx.motion.reduced) fish.nexa.ui.components.decorClock(4000).value else 0f
    // a shimmer's colours run out and back to the first, so the loop has no seam
    val stops = if (look.shimmer) colors + colors.first() else colors
    val brush = remember(stops, look.shimmer, phase) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val w = if (look.shimmer) size.width * 2f else size.width
                val x0 = -phase * w
                return LinearGradientShader(Offset(x0, 0f), Offset(x0 + w, 0f), stops, tileMode = if (look.shimmer) TileMode.Repeated else TileMode.Clamp)
            }
        }
    }
    PlainName(text, styled.copy(brush = brush), modifier, maxLines)
}

/** The same font metrics for a name renderer and an inline name's measured slot. */
fun nameTextStyle(style: TextStyle, own: NameStyle?): TextStyle {
    val family = own?.font?.takeIf { it.isNotEmpty() }?.let { NameFonts.family(it) }
    val scale = own?.font?.let { f -> NameStyle.FONTS.firstOrNull { it.id == f }?.scale } ?: 1f
    return if (family != null) style.copy(fontFamily = family, fontSize = style.fontSize * scale, letterSpacing = 0.sp) else style
}

/** A person's identity label, observing profile and role changes wherever it is shown. */
@Composable
fun PersonName(
    userId: String?,
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    serverId: String? = null,
    nameStyle: JsonElement? = null,
    showNameStyle: Boolean = true,
) {
    if (userId == null && (nameStyle == null || !showNameStyle)) {
        RoleName(text, null, style, color, modifier, maxLines)
        return
    }
    val user by Nexa.users.byId.collectEntryAsState(userId)
    val me by Nexa.users.me.collectAsState()
    val roleFlow = remember(serverId, userId) {
        Nexa.servers.byId.map { states ->
            if (userId != null && serverId != null) RoleLooks.of(states[serverId], userId) else null
        }.distinctUntilChanged()
    }
    val role by androidx.compose.runtime.key(serverId, userId) {
        roleFlow.collectAsState(initial = if (userId != null && serverId != null) RoleLooks.of(Nexa.servers.get(serverId), userId) else null)
    }
    val own = if (!showNameStyle) null else when {
        userId != null && userId == me?.id -> me?.nameStyle
        user != null -> user?.nameStyle
        else -> nameStyle
    }
    LaunchedEffect(userId) {
        if (userId != null && userId != me?.id) Nexa.session.ensureUsers(listOf(userId))
    }
    RoleName(text, RoleLooks.withOwn(role, own), style, color, modifier, maxLines)
}

/** Drawn here rather than with Txt, whose colour would take a brush's place. */
@Composable
private fun PlainName(text: String, style: TextStyle, modifier: Modifier, maxLines: Int) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier.drawWithContent {
            // Keep the intentional name-effect halo, but bound arbitrary glyph ink.
            val ink = if (style.shadow != null || style.drawStyle is Stroke) style.fontSize.toPx() * 0.5f else 0f
            clipRect(-ink, -ink, size.width + ink, size.height + ink) { this@drawWithContent.drawContent() }
        },
        style = style,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** A name in its own effect and colours (lib/nameStyle.ts, styles/nameStyle.css). */
@Composable
private fun OwnName(text: String, own: NameStyle, style: TextStyle, outerModifier: Modifier, maxLines: Int) {
    val animate = fish.nexa.ui.shell.settingOn("animateNames") && own.isAnimated && !Nx.motion.reduced
    val phase = if (animate && own.effect in listOf("gradient", "candy", "chrome", "glow", "ink", "pop")) {
        val duration = when (own.effect) { "gradient", "chrome" -> 4000; "candy" -> 3000; else -> 1500 }
        if (own.effect in listOf("chrome", "glow", "ink", "pop")) fish.nexa.ui.components.decorSwing(duration, FastOutSlowInEasing::transform)
        else fish.nexa.ui.components.decorClock(duration).value
    } else 0f
    val modifier = if (own.effect in listOf("glow", "ink", "pop")) outerModifier.alpha(1f - 0.35f * phase) else outerModifier
    val light = Nx.colors.isLight
    val cs = own.colors.map { rgbColor(it) }
    val c1 = cs.first()
    val c2 = cs.getOrElse(1) { c1 }
    val em = with(LocalDensity.current) { style.fontSize.toPx() }
    when (own.effect) {
        "gradient" -> {
            val brush = remember(cs, phase, animate) {
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader {
                        if (!animate) return LinearGradientShader(Offset.Zero, Offset(size.width, 0f), listOf(c1, c2))
                        val w = size.width * 2f
                        val x = -phase * w
                        return LinearGradientShader(Offset(x, 0f), Offset(x + w, 0f), listOf(c1, c2, c1), tileMode = TileMode.Repeated)
                    }
                }
            }
            PlainName(text, style.copy(brush = brush), modifier, maxLines)
        }
        "glow" -> PlainName(
            text,
            style.copy(
                color = if (light) lerp(Color.Black, c1, 0.75f) else lerp(Color.White, c1, 0.35f),
                shadow = Shadow(if (light) c1.copy(alpha = 0.7f) else c1, Offset.Zero, em * if (light) 0.35f else 0.4f),
            ),
            modifier, maxLines,
        )
        "ink" -> {
            // the colour, a dark line round it and a shadow under it
            val line = lerp(Color(0xFF12060C), c1, 0.35f)
            Box(modifier) {
                PlainName(text, style.copy(color = line, drawStyle = Stroke(width = em * 0.08f, join = StrokeJoin.Round), shadow = Shadow(line, Offset(em * 0.03f, em * 0.1f), 0f)), Modifier, maxLines)
                PlainName(text, style.copy(color = c1), Modifier, maxLines)
            }
        }
        "pop" -> {
            val fill = if (light) Color.White else lerp(Color.White, c1, 0.1f)
            Box(modifier) {
                PlainName(text, style.copy(color = fill, shadow = Shadow(c1, Offset(em * 0.05f, em * 0.09f), 0f)), Modifier, maxLines)
                // Match the web's dark edge so the white face stays legible on light themes.
                if (light) PlainName(text, style.copy(color = fill, shadow = Shadow(lerp(Color.Black, c1, 0.7f), Offset.Zero, em * 0.04f)), Modifier, maxLines)
            }
        }
        "candy" -> {
            // hard stripes, leaning, like boiled sweets
            val band = em * 0.42f
            val brush = remember(cs, band, phase) {
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader {
                        val run = band * cs.size
                        val colors = cs.flatMap { listOf(it, it) }
                        val stops = cs.indices.flatMap { i -> listOf(i.toFloat() / cs.size, (i + 1f) / cs.size) }
                        val x = phase * run
                        return LinearGradientShader(Offset(x, 0f), Offset(x + run * 0.985f, run * 0.174f), colors, stops, TileMode.Repeated)
                    }
                }
            }
            PlainName(text, style.copy(brush = brush), modifier, maxLines)
        }
        "aurora" -> {
            // every colour flowing through the name and round again
            val phase = if (animate) fish.nexa.ui.components.decorSwing(6000, FastOutSlowInEasing::transform) else 0f
            val stops = cs + cs.first()
            val brush = remember(stops, phase) {
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader {
                        val w = size.width * 3f
                        val x0 = -phase * (w - size.width)
                        return LinearGradientShader(Offset(x0, 0f), Offset(x0 + w, 0f), stops, tileMode = TileMode.Clamp)
                    }
                }
            }
            PlainName(text, style.copy(brush = brush), modifier, maxLines)
        }
        "chrome" -> {
            // polished metal tinted by the colour: a bright sky, a hard horizon, a dark ground
            val brush = remember(c1, phase, animate) {
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader {
                        val h = size.height * if (animate) 1.8f else 1f
                        val y = -phase * (h - size.height)
                        return LinearGradientShader(
                            Offset(0f, y), Offset(0f, y + h),
                            listOf(lerp(Color.White, c1, 0.2f), lerp(Color.White, c1, 0.55f), lerp(Color(0xFF10131A), c1, 0.6f), c1, lerp(Color.White, c1, 0.35f)),
                            listOf(0f, 0.46f, 0.52f, 0.78f, 1f),
                        )
                    }
                }
            }
            PlainName(text, style.copy(brush = brush), modifier, maxLines)
        }
        "holo" -> {
            // holographic foil: the colour with a sheen of every hue sweeping across it
            val phase = if (animate) fish.nexa.ui.components.decorClock(5000).value else 0f
            val stops = listOf(
                0f to c1, 0.18f to mixOklab(c1, Color(0xFF7FF3FF), 0.55f), 0.30f to mixOklab(c1, Color.White, 0.70f), 0.44f to mixOklab(c1, Color(0xFFFF8FF0), 0.55f),
                0.58f to mixOklab(c1, Color(0xFFFFF38A), 0.55f), 0.72f to c1, 0.86f to mixOklab(c1, Color(0xFF7FF3FF), 0.55f), 1f to c1,
            )
            val brush = remember(c1, phase) {
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader {
                        // the CSS's 105deg gradient 2.6 names wide, running from 100% to -160%
                        val w = size.width * 2.6f
                        val dx = w * 0.966f
                        val dy = w * 0.259f
                        // one whole turn of the foil a loop, starting and ending on the colour, so it has no seam
                        val from = Offset(-phase * dx, -phase * dy)
                        return LinearGradientShader(from, Offset(from.x + dx, from.y + dy), stops.map { it.second }, stops.map { it.first }, TileMode.Repeated)
                    }
                }
            }
            PlainName(text, style.copy(brush = brush), modifier, maxLines)
        }
        "neon" -> {
            // a tube lit from inside: a pale core, a line of the colour round it, and its glow, with a flicker now and then
            val flicker = if (animate) {
                val p = fish.nexa.ui.components.decorClock(7000).value
                if (p in 0.92f..0.935f || p in 0.945f..0.96f) 0.72f else 1f
            } else 1f
            Box(modifier.alpha(flicker)) {
                if (light) PlainName(text, style.copy(color = c1, shadow = Shadow(c1.copy(alpha = 0.55f), Offset.Zero, em * 0.3f)), Modifier, maxLines)
                else {
                    PlainName(text, style.copy(color = c1.copy(alpha = 0.7f), shadow = Shadow(c1.copy(alpha = 0.7f), Offset.Zero, em * 0.5f)), Modifier, maxLines)
                    PlainName(text, style.copy(color = c1, drawStyle = Stroke(width = em * 0.07f, join = StrokeJoin.Round), shadow = Shadow(c1, Offset.Zero, em * 0.2f)), Modifier, maxLines)
                    PlainName(text, style.copy(color = lerp(Color.White, c1, 0.18f), shadow = Shadow(Color.White, Offset.Zero, em * 0.05f)), Modifier, maxLines)
                }
            }
        }
        "flame" -> {
            // the colours rising through the name, with their warmth over it
            val phase = if (animate) fish.nexa.ui.components.decorClock(1800).value else 0f
            val mid = mixOklab(c1, c2, 0.5f)
            val brush = remember(c1, c2, phase) {
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader {
                        val h = size.height * 2f
                        val y0 = size.height + phase * h
                        return LinearGradientShader(Offset(0f, y0), Offset(0f, y0 - h), listOf(c1, mid, c2, mid, c1), listOf(0f, 0.25f, 0.5f, 0.75f, 1f), TileMode.Repeated)
                    }
                }
            }
            Box(modifier) {
                PlainName(text, style.copy(color = c1.copy(alpha = 0.55f), shadow = Shadow(c1.copy(alpha = 0.7f), Offset(0f, -em * 0.04f), em * 0.16f)), Modifier, maxLines)
                PlainName(text, style.copy(brush = brush), Modifier, maxLines)
            }
        }
        else -> PlainName(text, style.copy(color = c1), modifier, maxLines)
    }
}

/** A role's picture beside a name, named for the role it stands for. */
@Composable
fun RoleIcon(icon: RoleIconInfo, size: Dp) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    Box(Modifier.size(size).semantics { contentDescription = icon.name }, contentAlignment = Alignment.Center) {
        when (icon) {
            is RoleIconInfo.Image -> AsyncImage(Media.iconUrl(icon.id, size.value.toInt(), density), null, Media.loader, modifier = Modifier.size(size))
            is RoleIconInfo.Custom -> Nexa.expressions.emoji(icon.id)?.let { e ->
                AsyncImage(Media.emojiUrl(e.blobId, size.value.toInt(), density), null, Media.loader, modifier = Modifier.size(size))
            }
            is RoleIconInfo.Emoji -> TwemojiImage(icon.text, size * 0.9f)
        }
    }
}

/** The brush a gradient name is painted with, for places that draw names themselves. */
fun RoleLook.staticBrush(): Brush? = colors?.let { Brush.horizontalGradient(it) }
