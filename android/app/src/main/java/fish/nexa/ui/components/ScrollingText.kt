package fish.nexa.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** marquee.ts: how long the words rest at their start before they move, and with their end showing. */
private const val REST_START_MS = 900L
private const val REST_END_MS = 1000L
/** How fast they travel, in dp a second (the web's CSS pixels). */
private const val SPEED_DP = 26f
/** A whole trip out or back, eased at both ends. */
private val EASE_IN_OUT = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/** How long a trip of this many dp takes; never so short that it snaps. */
private fun travelMs(dp: Float): Int = maxOf(250, (abs(dp) / SPEED_DP * 1000).roundToInt())

/**
 * ScrollingText.tsx's ScrollGroup: texts that scroll together. Everything
 * inside shares one cycle, set by its longest text: they all set off, turn and
 * come home at the same moments, each at its own speed, and every loop starts
 * on the clock's cycle boundary so texts that appeared at different times
 * still line up. A group inside a group joins the outer one.
 */
class ScrollGroup { val overs = mutableStateMapOf<Any, Int>() }
private val LocalScrollGroup = compositionLocalOf<ScrollGroup?> { null }

@Composable
fun ScrollGroup(content: @Composable () -> Unit) {
    if (LocalScrollGroup.current != null) content()
    else CompositionLocalProvider(LocalScrollGroup provides remember { ScrollGroup() }, content = content)
}

/**
 * ScrollingText.tsx: one line of words that, when it does not fit, slides
 * across to show its end and back again, resting a moment at each end, so
 * all of it can be read without opening anything. Nothing moves when it fits,
 * and nothing moves for someone who asked for less motion: they get the
 * ellipsis.
 */
@Composable
fun ScrollingText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, inlineContent: Map<String, InlineTextContent> = emptyMap()) {
    if (Nx.motion.reduced) {
        BasicText(text, modifier.clipToBounds(), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = inlineContent)
        return
    }
    var box by remember { mutableIntStateOf(0) }
    var words by remember { mutableIntStateOf(0) }
    // a pixel's difference is rounding, not overflow
    val over = if (box > 0 && words - box > 1) words - box else 0
    val group = LocalScrollGroup.current
    val key = remember { Any() }
    if (group != null) {
        DisposableEffect(group, over) {
            if (over > 0) group.overs[key] = over
            onDispose { group.overs.remove(key) }
        }
    }
    // the longest trip in the group, in dp, sets how long every cycle is
    val groupOver = if (group != null && over > 0) maxOf(over, group.overs.values.maxOrNull() ?: 0) else over
    val offset = remember { Animatable(0f) }
    val shown = remember { object { var text: AnnotatedString? = null } }
    val density = LocalDensity.current.density
    val moving = Decor.running && !LocalDecorHeld.current
    LaunchedEffect(over, groupOver, text, moving) {
        // new words start again from their beginning
        if (shown.text != text) {
            shown.text = text
            offset.snapTo(0f)
        }
        if (!moving) return@LaunchedEffect
        if (over == 0) {
            // fits now (the line grew): back to the start rather than jumping there
            if (offset.value != 0f) offset.animateTo(0f, tween(travelMs(offset.value / density), easing = EASE_IN_OUT))
            return@LaunchedEffect
        }
        val own = travelMs(over / density)
        val longest = travelMs(groupOver / density)
        val cycle = REST_START_MS + 2L * longest + REST_END_MS
        while (true) {
            if (offset.value == 0f) {
                // in a group, wait for the cycle's boundary on the shared clock (a hair past it counts as on it)
                if (group != null) {
                    val into = System.currentTimeMillis() % cycle
                    if (into > 100) delay(cycle - into)
                }
                delay(REST_START_MS)
            }
            // out to where the end shows, from wherever the words are (a line that changed width carries on)
            offset.animateTo(over.toFloat(), tween(travelMs((over - offset.value) / density), easing = EASE_IN_OUT))
            // a shorter text waits for the longest one's turn, then for its start back
            delay(REST_END_MS + (longest - own))
            offset.animateTo(0f, tween(own, easing = EASE_IN_OUT))
            delay((longest - own).toLong())
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.clipToBounds().onSizeChanged { box = it.width }) {
        BasicText(
            text,
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = if (rtl) offset.value else -offset.value },
            style = style,
            maxLines = 1,
            softWrap = false,
            inlineContent = inlineContent,
            onTextLayout = { words = it.size.width },
        )
    }
}
