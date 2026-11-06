package fish.nexa.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.mixSrgb

/** The parts of a profile the designer changes, each pressed where it is shown. */
enum class ProfilePart { BANNER, AVATAR, STATUS, NAME, TAG, PRONOUNS, ABOUT, BADGES }

/**
 * ProfileDesign (ProfileCard.tsx): your own profile drawn in Settings,
 * Profiles, every part pressable to change it and every empty part showing
 * what it could hold. [name], [pronouns] and [about] are the editors drawn in
 * place of those parts while they are being written.
 */
class ProfileDesign(
    val edit: (ProfilePart) -> Unit,
    val name: (@Composable () -> Unit)? = null,
    val pronouns: (@Composable () -> Unit)? = null,
    val about: (@Composable () -> Unit)? = null,
    val widgets: WidgetDesign? = null,
    /** Every part outlined once as the designer opens, so nobody has to guess what can be pressed. */
    val intro: Boolean = false,
    /** Parts drawn as they are, with nothing to press: a server profile's status and tag are the account's. */
    val fixed: Set<ProfilePart> = emptySet(),
)

/** The widgets as the designer draws them: each with its tools, and after them the way to add another. */
class WidgetDesign(
    val edit: (String) -> Unit,
    val move: (String, Int) -> Unit,
    val remove: (String) -> Unit,
    /** Puts a widget first, which is the one the profile card shows. */
    val feature: (String) -> Unit,
    val add: () -> Unit,
)

/** Where a spot's pencil sits: a part's top corner, below a short word, at the end of the name, under a round picture, or inside the banner. */
enum class SpotKind { CORNER, WORD, NAME, ROUND, BANNER }

/** A dashed outline drawn [out] beyond what it surrounds, the way the web's spot sits around its part. */
private fun DrawScope.dashedOutline(color: Color, radius: Dp, outX: Dp, outY: Dp, round: Boolean) {
    val w = 1.5.dp.toPx()
    val ox = outX.toPx()
    val oy = outY.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    val tl = Offset(-ox + w / 2, -oy + w / 2)
    val sz = Size(size.width + 2 * ox - w, size.height + 2 * oy - w)
    if (round) drawOval(color, tl, sz, style = Stroke(w, pathEffect = dash))
    else drawRoundRect(color, tl, sz, CornerRadius(radius.toPx()), style = Stroke(w, pathEffect = dash))
}

/**
 * Spot: a part of the profile in the designer. Pressing it changes the part;
 * its pencil is always shown on a touch screen; an outline answers the press,
 * and goes round once as the designer opens.
 */
@Composable
fun Spotted(
    design: ProfileDesign?,
    part: ProfilePart,
    label: String,
    modifier: Modifier = Modifier,
    kind: SpotKind = SpotKind.CORNER,
    content: @Composable BoxScope.() -> Unit,
) {
    if (design == null || part in design.fixed) {
        Box(modifier, content = content)
        return
    }
    val c = Nx.colors
    val p = LocalProfile.current
    val accent = if (p.accent.isSpecified()) p.accent else c.brand
    val ring = mixSrgb(accent, Color.White, 0.2f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // the outline's once-over as the designer opens: in, and away again
    val hello = remember { Animatable(0f) }
    val reduced = Nx.motion.reduced
    // started once, as the part first shows, and run to the end: the designer's intro ending must not freeze it half-drawn
    val intro = design.intro
    LaunchedEffect(Unit) {
        if (intro && !reduced) {
            hello.snapTo(0f)
            hello.animateTo(0f, keyframes {
                durationMillis = 1900
                0f at 200
                1f at 710
                0f at 1900
            })
        }
    }
    val (outX, outY) = when (kind) {
        SpotKind.CORNER -> 7.dp to 5.dp
        SpotKind.WORD, SpotKind.NAME -> 7.dp to 5.dp
        SpotKind.ROUND -> 4.dp to 4.dp
        SpotKind.BANNER -> 0.dp to 0.dp
    }
    Box(modifier) {
        content()
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val a = if (pressed) 1f else hello.value
                    if (a > 0f) {
                        if (pressed) {
                            val fill = accent.copy(alpha = 0.12f)
                            if (kind == SpotKind.ROUND) drawOval(fill, Offset(-outX.toPx(), -outY.toPx()), Size(size.width + 2 * outX.toPx(), size.height + 2 * outY.toPx()))
                            else drawRoundRect(fill, Offset(-outX.toPx(), -outY.toPx()), Size(size.width + 2 * outX.toPx(), size.height + 2 * outY.toPx()), CornerRadius(10.dp.toPx()))
                        }
                        dashedOutline(ring.copy(alpha = a), if (kind == SpotKind.BANNER) 0.dp else 10.dp, outX, outY, kind == SpotKind.ROUND)
                    }
                }
                .pressable(interaction) { design.edit(part) }
                .semantics { contentDescription = label },
        )
        SpotPencil(
            accent,
            if (kind == SpotKind.WORD || kind == SpotKind.NAME) 18.dp else 22.dp,
            when (kind) {
                SpotKind.CORNER -> Modifier.align(Alignment.TopEnd).offset(x = outX, y = -outY - 13.dp)
                SpotKind.WORD -> Modifier.align(Alignment.BottomEnd).offset(x = outX + 8.dp, y = outY + 11.dp)
                SpotKind.NAME -> Modifier.align(Alignment.CenterEnd).offset(x = outX + 13.dp)
                SpotKind.ROUND -> Modifier.align(Alignment.BottomCenter).offset(y = outY + 7.dp)
                SpotKind.BANNER -> Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)
            },
        )
    }
}

private fun Color.isSpecified() = this != Color.Unspecified

/** The pencil on a spot: the profile's accent, lifted off the card. */
@Composable
private fun SpotPencil(accent: Color, size: Dp, modifier: Modifier) {
    Box(
        modifier
            .size(size)
            .boxShadow(CircleShape, 4f, 12f, Color.Black.copy(alpha = 0.55f), spread = -4f)
            .background(accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon("Pencil", Color.White, size = if (size < 20.dp) 10.dp else 11.dp) }
}

/** The dashed edge of an empty part in the designer: quiet, and the way to fill it. */
private fun Modifier.dashedEdge(color: Color, radius: Dp): Modifier = drawBehind {
    val w = 1.5.dp.toPx()
    drawRoundRect(
        color,
        Offset(w / 2, w / 2),
        Size(size.width - w, size.height - w),
        CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** An empty status in the designer: a bubble waiting to be filled. */
@Composable
fun GhostBubble(design: ProfileDesign, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val edge = if (pressed) p.accent else c.textMuted.copy(alpha = 0.65f)
    Box(modifier) {
        // the two small rounds that make it a thought, as on a status that is set
        Box(Modifier.offset((-13).dp, (-14).dp).size(5.dp).background(p.bubble.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.offset((-6).dp, (-8).dp).size(10.dp).background(p.bubble.copy(alpha = 0.6f), CircleShape))
        Row(
            Modifier
                .background(p.bubble.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .dashedEdge(edge, 18.dp)
                .pressable(interaction) { design.edit(ProfilePart.STATUS) }
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon("Plus", if (pressed) c.textStrong else c.textSubtle, size = 14.dp)
            Txt(t("designer.ghost.status"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(18f), fontWeight = FontWeight(500)), color = if (pressed) c.textStrong else c.textSubtle, maxLines = 1)
        }
    }
}

/** No server tag worn: a dashed chip beside the name. */
@Composable
fun GhostChip(design: ProfileDesign) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .height(24.dp)
            .dashedEdge(if (pressed) p.accent else c.textMuted.copy(alpha = 0.65f), 7.dp)
            .pressable(interaction) { design.edit(ProfilePart.TAG) }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon("Plus", if (pressed) c.textStrong else c.textSubtle, size = 12.dp)
        Txt(t("designer.ghost.tag"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(650)), color = if (pressed) c.textStrong else c.textSubtle, maxLines = 1)
    }
}

/** No pronouns: the words that add them, underlined with dashes. */
@Composable
fun GhostInline(design: ProfileDesign, style: androidx.compose.ui.text.TextStyle) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color = if (pressed) c.textStrong else c.textMuted
    Txt(
        t("designer.ghost.pronouns"),
        style = style,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .drawBehind {
                // text-decoration: underline dashed, 3 px below the words
                val y = size.height - 1.dp.toPx()
                drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
            .pressable(interaction) { design.edit(ProfilePart.PRONOUNS) },
    )
}

/** No About Me: a dashed section saying what goes there. */
@Composable
fun GhostSection(design: ProfileDesign) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) p.accent.copy(alpha = 0.06f) else Color.Transparent, RoundedCornerShape(10.dp))
            .dashedEdge(if (pressed) p.accent else c.textMuted.copy(alpha = 0.55f), 10.dp)
            .pressable(interaction) { design.edit(ProfilePart.ABOUT) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt(t("settings.profile.aboutMe"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), lineHeight = Nx.type.sp(16f), fontWeight = FontWeight(700), letterSpacing = 0.01.em), color = c.textMuted)
        Txt(t("designer.ghost.about"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.textSubtle)
    }
}

/** The way to add another widget, after the ones there are. */
@Composable
fun AddWidgetTile(onClick: () -> Unit) {
    val c = Nx.colors
    val p = LocalProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (pressed) p.accent.copy(alpha = 0.06f) else Color.Transparent, RoundedCornerShape(10.dp))
            .dashedEdge(if (pressed) p.accent else c.textMuted.copy(alpha = 0.55f), 10.dp)
            .pressable(interaction, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon("Plus", if (pressed) c.textStrong else c.textSubtle, size = 16.dp)
        Txt(t("widgets.addOne"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(650)), color = if (pressed) c.textStrong else c.textSubtle)
    }
}
