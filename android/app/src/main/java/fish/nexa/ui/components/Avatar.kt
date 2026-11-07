package fish.nexa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fish.nexa.media.Media
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.ring
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** lib/avatarColor.ts: one of six colours, by the id's hash. */
object AvatarColor {
    private val PALETTE = listOf(0x5865f2, 0x3d9e60, 0xfdb833, 0xda3e44, 0xeb459f, 0x8b5cf6)

    fun of(id: String): Color {
        var h = 0L
        for (ch in id) h = (h * 31 + ch.code) and 0xffffffffL
        return Color(0xFF000000L or PALETTE[(h % PALETTE.size).toInt()].toLong())
    }

    /** The first grapheme, upper-cased ("?" when empty). */
    fun initials(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "?"
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(t)
        val end = bi.next()
        val first = t.substring(0, if (end > 0) end else 1)
        val upper = first.uppercase()
        return if (upper.length == first.length) upper else first
    }
}

/** Dot and cut ring for an avatar size (Avatar.tsx). */
private fun dotFor(size: Float): Pair<Float, Float> {
    val dot = max(8f, min(size * 0.3125f, 10f + (size - 32f) * 0.125f)).roundToInt().toFloat()
    val ring = max(2f, (dot * 0.3f).roundToInt().toFloat())
    return dot to ring
}

/** Whether a status shows typing on its dot (Avatar.tsx showsTyping): whoever shows as offline, or is invisible, shows nothing. */
fun showsTyping(status: String?): Boolean = status == "online" || status == "idle" || status == "dnd"

/**
 * A circle with someone's picture or their initial on their colour, and,
 * when asked, their presence dot floating in a gap cut out of it.
 *
 * `typing` (Avatar.tsx): someone types in the conversation the avatar stands
 * for, and the dot grows into a pill of three dots taking turns, in the
 * status's colour, its cut growing with it. Never for someone who shows as
 * offline. `face` draws in place of the picture (a group's glyph), with the
 * dot and its cut kept.
 */
@Composable
fun Avatar(
    id: String,
    name: String,
    avatarId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    status: String? = null,
    dot: Dp? = null,
    icon: Boolean = false,
    shape: Shape = CircleShape,
    speaking: Boolean = false,
    typing: Boolean? = null,
    face: (@Composable () -> Unit)? = null,
) {
    val c = Nx.colors
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val px = size.value
    val (dotDp, ringDp) = dot?.let { it.value to max(2f, (it.value * 0.3f).roundToInt().toFloat()) } ?: dotFor(px)
    val animate = fish.nexa.ui.shell.settingOn(if (icon) "animateIcons" else "animateAvatars")
    val url = remember(avatarId, size, density.density, icon, animate) {
        if (icon) Media.iconUrl(avatarId, px.toInt(), density.density) else Media.avatarUrl(avatarId, px.toInt(), density.density)
    }
    val statusColor = status?.let { c.statusColor(it) }
    val pill = typing == true && showsTyping(status)
    val reduced = Nx.motion.reduced
    // the pill's width, in dots (1 is the plain dot); it keeps the dot's height (Avatar.module.css .typable)
    val grow by androidx.compose.animation.core.animateFloatAsState(if (pill) 2.2f else 1f, if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(220, easing = Nx.motion.easeOut), label = "typing")
    // the three dots take turns: a phase from 0 to 1, over and over, while the pill shows
    val phase = if (pill) decorClock(1200, informs = true) else null
    val onStatus = when (status) {
        "idle" -> c.onWarning
        "dnd" -> c.onDanger
        else -> c.onPositive
    }
    /**
     * The rounded box the dot (or pill) takes, grown by `by` on every side. A
     * quarter of what the pill grows goes out past the picture's edge
     * (Avatar.module.css --over), so less of the face is covered.
     */
    fun DrawScope.dotBox(by: Float, offset: Float = 0f): androidx.compose.ui.geometry.RoundRect {
        val d = dotDp * density.density
        val w = d * grow
        val over = (w - d) / 4
        val right = if (rtl) w + offset - over else this.size.width - offset + over
        val bottom = this.size.height - offset
        val r = d / 2 + by
        return androidx.compose.ui.geometry.RoundRect(right - w - by, bottom - d - by, right + by, bottom + by, androidx.compose.ui.geometry.CornerRadius(r, r))
    }
    Box(
        modifier
            .size(size)
            .then(
                when {
                    !speaking -> Modifier
                    // round a dot, the ring is cut away round it as the picture is, so it never runs through it
                    statusColor != null && shape == CircleShape -> Modifier.drawBehind {
                        val w = 2.dp.toPx()
                        val cut = androidx.compose.ui.graphics.Path().apply { addRoundRect(dotBox(ringDp * density.density)) }
                        clipPath(cut, androidx.compose.ui.graphics.ClipOp.Difference) {
                            drawCircle(c.positive, radius = this.size.minDimension / 2 + w / 2, style = Stroke(w))
                        }
                    }
                    else -> Modifier.ring(shape, c.positive, 2.dp)
                },
            )
            // the pill, over the picture's layer rather than in it: the layer ends at the
            // picture's edge, and the pill reaches a little past it (a plain dot stays in
            // the layer, where its idle and do-not-disturb shapes are cut out of it)
            .drawWithContent {
                drawContent()
                // a pill, or the dot on its way to and from one
                if (statusColor != null && typing != null && (pill || grow > 1.001f)) {
                    val d = dotDp * density.density
                    val box = dotBox(0f)
                    drawRoundRect(statusColor, Offset(box.left, box.top), Size(box.width, box.height), androidx.compose.ui.geometry.CornerRadius(d / 2))
                    if (pill) {
                        val p = phase?.value ?: 0f
                        // dots two fifths of the pill's height, a fifth apart, so they read at a glance in a list
                        val dotR = max(1.5f, d * 0.2f)
                        val gap = max(1f, d * 0.2f)
                        val cy = box.top + box.height / 2
                        val cx0 = box.left + box.width / 2 - (2 * dotR + gap)
                        for (i in 0..2) {
                            // each dot brightest a third of a turn after the one before it
                            val local = ((p - i * 0.133f) % 1f + 1f) % 1f
                            val beat = if (local < 0.3f) local / 0.3f else if (local < 0.6f) 1f - (local - 0.3f) / 0.3f else 0f
                            val alpha = 0.45f + 0.55f * beat
                            val scale = if (reduced) 1f else 0.85f + 0.2f * beat
                            drawCircle(onStatus.copy(alpha = alpha), radius = dotR * scale, center = Offset(cx0 + i * (2 * dotR + gap), cy))
                        }
                    }
                }
            }
            // the ring round the dot (or the pill) cut out of the picture, and a plain dot,
            // in a layer of their own: both are cut with Clear
            .graphicsLayer { compositingStrategy = if (statusColor != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
            .drawWithContent {
                drawContent()
                if (statusColor != null) {
                    val d = dotDp * density.density
                    val r = ringDp * density.density
                    val center = Offset(if (rtl) d / 2 else this.size.width - d / 2, this.size.height - d / 2)
                    if (typing == null) {
                        drawCircle(Color.Black, radius = d / 2 + r, center = center, blendMode = BlendMode.Clear)
                        drawPresence(status ?: "offline", statusColor, center, d)
                    } else {
                        // the cut grows with the pill, so the pill keeps the ring the dot has
                        val cut = dotBox(r)
                        drawRoundRect(Color.Black, Offset(cut.left, cut.top), Size(cut.width, cut.height), androidx.compose.ui.geometry.CornerRadius(cut.topLeftCornerRadius.x), blendMode = BlendMode.Clear)
                        if (!pill && grow <= 1.001f) drawPresence(status ?: "offline", statusColor, center, d)
                    }
                }
            },
    ) {
        if (face != null) {
            Box(Modifier.fillMaxSize().clip(shape), contentAlignment = Alignment.Center) { face() }
        } else if (url != null) {
            AsyncImage(
                model = url,
                imageLoader = Media.loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape).background(c.bgSurface),
            )
        } else {
            val bg = AvatarColor.of(id)
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(bg)
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.12f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f))),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    remember(name) { AvatarColor.initials(name) },
                    style = TextStyle(
                        fontFamily = Nx.type.display,
                        fontWeight = FontWeight(660),
                        fontSize = (max(10f, px * 0.42f) / density.fontScale).sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.2f), Offset(0f, 1f * density.density), 2f * density.density),
                    ),
                )
            }
        }
    }
}

/** online: a dot; idle: a crescent; dnd: a dot with a bar cut out; offline: a ring (Avatar.module.css masks). */
fun DrawScope.drawPresence(status: String, color: Color, center: Offset, d: Float) {
    val r = d / 2
    when (status) {
        "online" -> drawCircle(color, r, center)
        "idle" -> {
            drawCircle(color, r, center)
            val holeR = d * 0.375f
            drawCircle(Color.Black, holeR, Offset(center.x - r + d * 0.25f, center.y - r + d * 0.25f), blendMode = BlendMode.Clear)
        }
        "dnd" -> {
            drawCircle(color, r, center)
            drawRect(Color.Black, Offset(center.x - r + d * 0.25f, center.y - d * 0.125f), Size(d * 0.5f, d * 0.25f), blendMode = BlendMode.Clear)
        }
        else -> drawCircle(color, r * 0.75f, center, style = Stroke(width = r * 0.5f))
    }
}

/** A presence dot on its own (status pickers, member rows' word). */
@Composable
fun PresenceDot(status: String, size: Dp, modifier: Modifier = Modifier) {
    val color = Nx.colors.statusColor(status)
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawPresence(status, color, center, this.size.minDimension)
    }
}

/** An avatar drawn square, as the web's `.square` avatar (Avatar.module.css): a corner of 30%. Apps' icons, and servers' on the consent sheet. */
val SQUARE_AVATAR = RoundedCornerShape(percent = 30)

/** A server's or group's icon: its picture, or its initial on the surface. Square-ish tiles round to 16. */
@Composable
fun ServerIcon(id: String, name: String, iconId: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 16.dp) {
    Avatar(id, name, iconId, size, modifier, icon = true, shape = RoundedCornerShape(radius))
}
