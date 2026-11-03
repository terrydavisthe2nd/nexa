package fish.nexa.ui.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.dangerGradient
import fish.nexa.ui.theme.positiveGradient
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.topEdge
import fish.nexa.voice.Calls
import fish.nexa.voice.Voice
import kotlinx.coroutines.launch

/**
 * The incoming call (IncomingCall.tsx): the caller's picture with a halo that
 * pulses as a phone rings, their name, and Decline, Ignore and Accept. The
 * same card shows in the app and full screen over the lock screen.
 */
@Composable
fun IncomingCallCard(channelId: String, from: String, onDecline: () -> Unit, onIgnore: () -> Unit, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val caller = Nexa.users.get(from)
    val name = Names.display(caller)
    val ch = Nexa.channels.get(channelId)
    val sub = if (ch?.type == "group_dm") t("voice.incomingCall.inGroup", "name" to Names.channel(ch)) else t("voice.incomingCall.incomingCall")
    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .background(c.bgSurface, Radii.Md)
            .padding(24.dp)
            .semantics { paneTitle = t("voice.incomingCall.incomingCall") },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val halo = if (reduced) 5f else {
                val pulse = rememberInfiniteTransition(label = "ring")
                val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), RepeatMode.Restart), label = "halo")
                // 0 -> 14 px and back to 0 over the beat, fading as it grows
                (if (p < 0.5f) p * 2 else (1 - p) * 2) * 14f
            }
            val haloAlpha = if (reduced) 0.5f else 0.6f * (1f - halo / 14f)
            Avatar(from, name, caller?.avatarId, 80.dp, modifier = Modifier.ring(CircleShape, c.statusOnline.copy(alpha = haloAlpha), halo.dp))
        }
        fish.nexa.ui.chat.PersonName(from, name, style = Nx.type.heading.copy(fontSize = Nx.type.sp(22f), fontWeight = FontWeight(690), lineHeight = Nx.type.sp(28f), textAlign = TextAlign.Center), color = c.textStrong, maxLines = 1)
        Txt(sub, style = Nx.type.bodySmall, color = c.textMuted, align = TextAlign.Center, maxLines = 2)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RingAction(t("voice.incomingCall.decline"), Modifier.semantics { contentDescription = t("voice.incomingCall.decline") }, kind = "decline", onClick = onDecline)
            RingAction(t("voice.incomingCall.ignore"), Modifier.semantics { contentDescription = t("voice.incomingCall.ignoreHint") }, kind = "ignore", onClick = onIgnore)
            RingAction(t("voice.incomingCall.accept"), Modifier.semantics { contentDescription = t("voice.incomingCall.accept") }, kind = "accept", onClick = onAccept)
        }
    }
}

/** Each choice is its round button with its name under it, all one target. */
@Composable
private fun RingAction(label: String, modifier: Modifier, kind: String, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier.widthIn(min = 64.dp).pressable(interaction, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val circle = Modifier.size(58.dp).scale(if (pressed) 0.94f else 1f)
        when (kind) {
            "decline" -> Box(circle.boxShadow(CircleShape, 10f, 26f, c.danger.copy(alpha = 0.95f), spread = -10f).background(dangerGradient(c), CircleShape).topEdge(CircleShape, Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                HangUpIcon(Color.White, 24.dp)
            }
            "accept" -> Box(circle.boxShadow(CircleShape, 10f, 26f, c.positive.copy(alpha = 0.95f), spread = -10f).background(positiveGradient(c), CircleShape).topEdge(CircleShape, Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon("Phone", Color.White, size = 24.dp)
            }
            else -> Box(circle.background(if (pressed) c.bgSelected else c.bgActive, CircleShape).topEdge(CircleShape, Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon("BellOff", c.text, size = 22.dp)
            }
        }
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = if (pressed) c.text else c.textSubtle)
    }
}

/**
 * The incoming call in the app, as a bare bottom sheet: swiping it away or
 * Back is Ignore; a tap beside it does nothing, so a call is never declined by
 * a stray touch.
 */
@Composable
internal fun IncomingCallOverlay(layer: Layer) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val ringing by Voice.ringing.collectAsState()
    val entry = ringing.entries.minByOrNull { it.value.at }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val offset = remember { Animatable(if (reduced) 0f else 40f * density) }
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    var height by remember { mutableFloatStateOf(1f) }
    fun ignore() {
        val ch = entry?.key
        if (ch != null) Calls.ignore(ch)
        Layers.remove(layer)
    }
    layer.dismiss = { ignore() }
    LaunchedEffect(Unit) {
        if (!reduced) {
            launch { shown.animateTo(1f, tween(200)) }
            offset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        }
    }
    // the last ring answered, declined or gone: nothing left to show
    LaunchedEffect(entry == null) { if (entry == null) Layers.remove(layer) }
    // a call arriving mid-sentence closes the keyboard, which would otherwise
    // cover the card and its buttons
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(entry?.key) {
        if (entry != null) { focus.clearFocus(force = true); keyboard?.hide() }
    }
    if (entry == null) return
    val (channelId, ring) = entry
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().alpha(shown.value).background(c.bgOverlay).pressable(remember { MutableInteractionSource() }, role = Role.Button) { })
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .padding(12.dp)
                    .graphicsLayer {
                        translationY = offset.value
                        alpha = shown.value
                    }
                    .onSizeChanged { height = it.height.toFloat() }
                    .pointerInput(channelId) {
                        var velocity = 0f
                        var lastT = 0L
                        detectVerticalDragGestures(
                            onDragStart = { velocity = 0f; lastT = System.nanoTime() },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val now = System.nanoTime()
                                velocity = 0.7f * velocity + 0.3f * (dy / ((now - lastT) / 1_000_000f).coerceAtLeast(1f))
                                lastT = now
                                scope.launch { offset.snapTo((offset.value + dy).coerceAtLeast(0f)) }
                            },
                            onDragEnd = {
                                if (offset.value > minOf(160f * density, height / 3) || velocity / density > 0.55f) ignore()
                                else scope.launch { offset.animateTo(0f, tween(240, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) }
                            },
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = Radii.Md
                Box(Modifier.shadowHigh(shape, c).ring(shape, c.panelEdge).topEdge(shape, c.edgeLight)) {
                    IncomingCallCard(
                        channelId = channelId,
                        from = ring.from,
                        onDecline = { Calls.decline(channelId) },
                        onIgnore = { ignore() },
                        onAccept = { Calls.accept(channelId) },
                    )
                    // the grip the card is swiped away by
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(36.dp, 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
