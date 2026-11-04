package fish.nexa.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import fish.nexa.ui.theme.Nx
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The chosen item's pill slides from the item chosen before to the new one,
 * as the web's tab strips and segmented switches do (lib/tabGlide.ts).
 *
 * A row of choices sits in a Box marked [glideArea], each choice is marked
 * with [glideItem], and [GlidePill], drawn first in that Box, is the one pill
 * they share; a chosen item draws no background of its own. The pill starts
 * where the choice is when the row is first drawn, so it is never missing for
 * a frame, and Reduce Motion puts it in place at once. A choice made while it
 * is still moving carries on from where the pill is.
 */
@Stable
class Glide internal constructor(first: Any?) {
    private var area: LayoutCoordinates? = null
    private val placed = HashMap<Any, LayoutCoordinates>()
    private val boxes = mutableStateMapOf<Any, Rect>()
    internal var target by mutableStateOf(first)
    private var fromKey by mutableStateOf<Any?>(null)
    private var fromBox by mutableStateOf<Rect?>(null)
    internal val progress = Animatable(1f)

    /** The choice the pill is leaving, while it moves. */
    val leaving: Any? get() = if (progress.value < 1f) fromKey else null

    /** How far the pill has come, 0 to 1. */
    val fraction: Float get() = progress.value

    /** A colour on its way from the one for the choice it left to the one for the new choice. */
    fun tint(of: (Any?) -> Color): Color {
        val p = progress.value
        return if (p >= 1f || fromKey == null) of(target) else lerp(of(fromKey), of(target), p)
    }

    internal fun rect(): Rect? {
        val to = boxes[target] ?: return null
        val p = progress.value
        if (p >= 1f) return to
        val from = fromKey?.let { boxes[it] } ?: fromBox ?: return to
        return lerp(from, to, p)
    }

    internal fun startFrom(was: Any?) {
        // still under way: from where the pill is now, not from the choice it was leaving
        if (progress.value < 1f) {
            fromBox = rect()
            fromKey = null
        } else {
            fromKey = was
            fromBox = boxes[was]
        }
    }

    internal fun distanceTo(key: Any?): Float {
        val a = rect() ?: return 0f
        val b = boxes[key] ?: return 0f
        return hypot(a.left - b.left, a.top - b.top)
    }

    internal fun areaAt(c: LayoutCoordinates) {
        area = c
        for ((k, item) in placed) measure(k, item)
    }

    internal fun itemAt(key: Any, c: LayoutCoordinates) {
        placed[key] = c
        measure(key, c)
    }

    private fun measure(key: Any, c: LayoutCoordinates) {
        val a = area ?: return
        if (!a.isAttached || !c.isAttached) return
        val r = a.localBoundingBoxOf(c, clipBounds = false)
        if (boxes[key] != r) boxes[key] = r
    }
}

@Composable
fun rememberGlide(selected: Any?): Glide {
    val g = remember { Glide(selected) }
    val still = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    LaunchedEffect(selected) {
        val was = g.target
        if (was == selected) return@LaunchedEffect
        if (still || was == null) {
            g.target = selected
            g.progress.snapTo(1f)
            return@LaunchedEffect
        }
        val far = g.distanceTo(selected)
        g.startFrom(was)
        g.target = selected
        g.progress.snapTo(0f)
        // a longer way takes a little longer, within a quarter to a third of a second
        g.progress.animateTo(1f, tween((190 + far / 4f).roundToInt().coerceIn(220, 320), easing = ease))
    }
    return g
}

/** The Box the pill and the choices are measured in. */
fun Modifier.glideArea(g: Glide): Modifier = onGloballyPositioned { g.areaAt(it) }

/** One of the choices, by its key. */
fun Modifier.glideItem(g: Glide, key: Any): Modifier = onGloballyPositioned { g.itemAt(key, it) }

/** The shared pill, drawn with [look] (its background, ring and shadow), behind the choices. */
@Composable
fun BoxScope.GlidePill(g: Glide, look: Modifier) {
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val r = g.rect()
                    if (r == null || r.width <= 0f || r.height <= 0f) {
                        measurable.measure(Constraints.fixed(0, 0))
                        layout(0, 0) {}
                    } else {
                        val p = measurable.measure(Constraints.fixed(r.width.roundToInt(), r.height.roundToInt()))
                        layout(p.width, p.height) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                    }
                }
                .then(look),
        )
    }
}
