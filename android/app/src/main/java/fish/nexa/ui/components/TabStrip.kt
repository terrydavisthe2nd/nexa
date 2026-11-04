package fish.nexa.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge

/**
 * The sunken strip of tabs (Friends, profile sections): each tab as wide as
 * its words at least, the strip's spare width shared out between them
 * (`flex: 1 0 auto`), sliding sideways only when they cannot all fit.
 */
@Composable
fun <T> TabStrip(
    tabs: List<Pair<T, String>>,
    selected: T,
    label: String,
    modifier: Modifier = Modifier,
    tabHeight: Dp = 38.dp,
    /** A number beside a tab's name (the Mutuals tab's count). */
    count: (T) -> Int? = { null },
    onSelect: (T) -> Unit,
) {
    val c = Nx.colors
    val stripShape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .sunken(stripShape, c)
            .ring(stripShape, c.borderSubtle)
            .semantics { contentDescription = label },
    ) {
        val inner = maxWidth - 6.dp
        val narrow = maxWidth < 420.dp
        // the chosen tab's pill slides to the tab chosen next (web lib/tabGlide.ts); the
        // position is a fractional tab index, so the pill passes over the tabs between
        val at = tabs.indexOfFirst { it.first == selected }
        val still = Nx.motion.reduced
        val ease = Nx.motion.easeOut
        val pos = remember { Animatable(at.toFloat()) }
        LaunchedEffect(at) {
            if (at < 0) return@LaunchedEffect
            if (still || pos.value < 0f) pos.snapTo(at.toFloat()) else pos.animateTo(at.toFloat(), tween(260, easing = ease))
        }
        Box(Modifier.sidewaysScroll().padding(3.dp)) {
            ShareRow(inner, gap = 2.dp, pill = if (at >= 0) ({ pos.value }) else null) {
                val shape = RoundedCornerShape(9.dp)
                Box(Modifier.shadowLow(shape, c).ring(shape, c.borderSubtle).background(c.bgSurface, shape).background(sheen(c), shape).topEdge(shape, c.edgeLight))
                for ((key, text) in tabs) StripTab(text, key == selected, tabHeight, if (narrow) 8.dp else 12.dp, count(key)) { onSelect(key) }
            }
        }
    }
}

/**
 * Lays children out at their own widths, then grows each by an equal share of
 * what is left of [fill]. The first child is the selected pill, drawn behind
 * the tabs at [pill] (a fractional tab index read here, in layout, so its
 * movement relays out without recomposing), or not at all without one.
 */
@Composable
private fun ShareRow(fill: Dp, gap: Dp, pill: (() -> Float)?, content: @Composable () -> Unit) {
    Layout(content) { all, constraints ->
        val pillM = all.first()
        val measurables = all.drop(1)
        val gapPx = gap.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val used = natural.sum() + gapPx * (measurables.size - 1).coerceAtLeast(0)
        val spare = (fill.roundToPx() - used).coerceAtLeast(0)
        val share = if (measurables.isEmpty()) 0 else spare / measurables.size
        val placeables = measurables.mapIndexed { i, m ->
            val w = natural[i] + share
            m.measure(Constraints.fixedWidth(w).copy(minHeight = 0, maxHeight = constraints.maxHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val width = placeables.sumOf { it.width } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        val xs = IntArray(placeables.size)
        run { var x = 0; for ((i, p) in placeables.withIndex()) { xs[i] = x; x += p.width + gapPx } }
        val f = pill?.invoke()?.coerceIn(0f, (placeables.size - 1).coerceAtLeast(0).toFloat())
        val pillP = if (f != null && placeables.isNotEmpty()) {
            val i = f.toInt().coerceAtMost(placeables.size - 1)
            val j = (i + 1).coerceAtMost(placeables.size - 1)
            val t = f - i
            val px = xs[i] + (xs[j] - xs[i]) * t
            val pw = placeables[i].width + (placeables[j].width - placeables[i].width) * t
            pillM.measure(Constraints.fixed(pw.roundToInt().coerceAtLeast(0), height)) to px.roundToInt()
        } else null
        layout(width, height) {
            pillP?.let { (p, x) -> p.placeRelative(x, 0) }
            for ((i, p) in placeables.withIndex()) p.placeRelative(xs[i], 0)
        }
    }
}

@Composable
private fun StripTab(text: String, on: Boolean, height: Dp, pad: Dp, count: Int?, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .height(height)
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, role = Role.Tab, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = pad),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(text, style = Nx.type.button.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted, maxLines = 1)
            if (count != null) Box(
                Modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).background(c.bgActive, RoundedCornerShape(9.dp)).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(java.text.NumberFormat.getIntegerInstance(fish.nexa.ui.I18n.locale).format(count), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(700), fontFeatureSettings = "tnum"), color = c.textSubtle, maxLines = 1)
            }
        }
    }
}

/**
 * Underlined tabs (AutoMod's Rules and Log, Profiles' Main Profile and Server
 * Profiles): the words in a row over a hairline, the chosen one in the strong
 * text colour with a bar of the focus colour under it, as the web draws them.
 */
@Composable
fun <T> UnderlineTabs(tabs: List<Pair<T, String>>, selected: T, label: String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val c = Nx.colors
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            // three long names on a narrow phone slide sideways rather than squeeze
            .sidewaysScroll()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((key, text) in tabs) {
            val on = key == selected
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .pressable(remember { MutableInteractionSource() }, role = Role.Tab) { onSelect(key) }
                    .semantics { this.selected = on }
                    .drawBehind {
                        if (on) {
                            val inset = 10.dp.toPx()
                            val h = 3.dp.toPx()
                            drawRoundRect(c.focusRing, Offset(inset, size.height - h), Size(size.width - 2 * inset, h + 3.dp.toPx()), CornerRadius(h))
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted, maxLines = 1)
            }
        }
    }
}
