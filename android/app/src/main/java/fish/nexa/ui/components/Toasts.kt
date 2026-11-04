package fish.nexa.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Toast
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.panel
import fish.nexa.ui.theme.ring
import kotlinx.coroutines.delay

/**
 * Toasts.module.css on a phone: a column at the top, under the page header,
 * newest at the bottom. Info and success stay five seconds, errors eight, and
 * none goes while a finger is on it; a tap dismisses it.
 */
@Composable
fun ToastHost() {
    val toasts by Nexa.ui.toasts.collectAsState()
    // A toast that goes stays drawn while it leaves: it rises a little and fades
    // as the space it took closes, so the ones under it slide up instead of
    // jumping (web Toasts.tsx). What is drawn keeps its place; new ones join the end.
    val drawn = remember { mutableListOf<Toast>() }
    var dropped by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    for (t in toasts) if (drawn.none { it.id == t.id }) drawn.add(t)
    val live = toasts.mapTo(HashSet()) { it.id }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            dropped // read, so a toast that has finished leaving is let go of
            for (toast in drawn.toList()) key(toast.id) {
                ToastSlot(toast, toast.id in live) {
                    drawn.removeAll { it.id == toast.id }
                    dropped++
                }
            }
        }
    }
}

/** One toast in the column, with the gap under it, which closes with it as it leaves. */
@Composable
private fun ToastSlot(toast: Toast, live: Boolean, gone: () -> Unit) {
    val still = Nx.motion.reduced
    val state = remember { androidx.compose.animation.core.MutableTransitionState(true) }
    LaunchedEffect(live) { state.targetState = live }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = state,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = if (still) androidx.compose.animation.ExitTransition.None
        else androidx.compose.animation.fadeOut(tween(200)) +
            androidx.compose.animation.shrinkVertically(tween(200), shrinkTowards = Alignment.Top) +
            androidx.compose.animation.slideOutVertically(tween(200)) { -it / 6 },
    ) {
        Box(Modifier.padding(bottom = 8.dp)) { ToastView(toast) }
    }
    if (!live && state.isIdle && !state.currentState) LaunchedEffect(Unit) { gone() }
}

@Composable
private fun ToastView(toast: Toast) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val spring = Nx.motion.spring
    val interaction = remember { MutableInteractionSource() }
    val held by interaction.collectIsPressedAsState()
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320, easing = spring)) }
    LaunchedEffect(held) {
        if (held) return@LaunchedEffect
        delay(if (toast.kind == "error") 8000 else 5000)
        Nexa.ui.dismissToast(toast.id)
    }
    val dot = when (toast.kind) {
        "error" -> c.danger
        "success" -> c.positive
        else -> c.brand
    }
    val kindLabel = when (toast.kind) {
        "error" -> t("toasts.error")
        "success" -> t("toasts.done")
        else -> t("toasts.note")
    }
    Box(
        Modifier
            .widthIn(max = 480.dp)
            .graphicsLayer {
                val p = enter.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * -14f * density
                val s = 0.96f + 0.04f * p
                scaleX = s
                scaleY = s
            }
            .panel(Radii.Md, c, c.bgFloating.copy(alpha = 0.92f), high = true)
            .pressable(interaction) { Nexa.ui.dismissToast(toast.id) }
            .semantics {
                // a toast that ends its own sentence gets no second full stop
                contentDescription = "$kindLabel: ${toast.text}" + (if (toast.text.trimEnd().lastOrNull() in listOf('.', '!', '?', '。', '！', '？')) " " else ". ") + t("toasts.dismiss")
                liveRegion = if (toast.kind == "error") LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .padding(start = 30.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
    ) {
        // the glowing dot: a 3 px halo at 22% and a 10 px glow of its colour
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-17).dp)
                .size(8.dp)
                .ring(CircleShape, dot.copy(alpha = 0.22f), 3.dp)
                .boxShadow(CircleShape, 0f, 10f, dot)
                .background(dot, CircleShape),
        )
        Txt(toast.text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(550)), color = c.textStrong)
    }
}
