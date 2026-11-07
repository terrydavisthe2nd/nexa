package fish.nexa.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.data.TypingStyle
import fish.nexa.media.Media
import fish.nexa.media.TwemojiImage
import fish.nexa.net.Channel
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.AnchorRef
import fish.nexa.ui.layers.anchor
import fish.nexa.ui.layers.openPopout
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import kotlinx.coroutines.delay

/**
 * TypingBar.tsx: always 24 dp between the list and the composer, so nothing
 * jumps; who is typing in the last ten seconds, never you or anyone you
 * blocked. One person with a typing style of their own types their way.
 */
@Composable
fun TypingBar(channel: Channel) {
    val c = Nx.colors
    val all by Nexa.typing.byChannel.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    val meId = Nexa.users.meId
    LaunchedEffect(all[channel.id]?.size ?: 0) {
        while ((Nexa.typing.byChannel.value[channel.id]?.size ?: 0) > 0) {
            delay(2000)
            Nexa.typing.sweep()
        }
    }
    val now = System.currentTimeMillis()
    val ids = all[channel.id]?.filter { (id, until) -> until > now && id != meId && !Nexa.relationships.isBlocked(id) }?.keys?.toList() ?: emptyList()
    val sid = channel.serverId
    val names = ids.map { Names.member(sid, it) }
    Row(
        Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ids.isEmpty()) return@Row
        // a server channel can take typing styles away (Use Custom Typing Indicators); roles not loaded count as allowed
        val st = sid?.let { servers[it] }
        val allowed = ids.size != 1 || st == null || (st.server.ownerId != ids[0] && st.members[ids[0]] == null) ||
            P.has(Nexa.servers.channelPerms(st, channel.id, ids[0]), P.USE_CUSTOM_TYPING)
        val style = if (ids.size == 1 && allowed && Nexa.settings.customTyping) TypingStyle.read(Nexa.users.get(ids[0])?.typingStyle) else null
        val textStyle = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), color = c.textSubtle)
        val bold = SpanStyle(fontWeight = FontWeight(600), color = c.text)
        if (style != null) {
            val ref = remember { AnchorRef() }
            Row(
                Modifier
                    .anchor(ref)
                    .padding(start = 0.dp)
                    .pressable(remember { MutableInteractionSource() }) { openTypingCard(ids[0], style, ref) }
                    .padding(horizontal = 0.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (style.emoji.isNotEmpty()) TypingEmoji(style, 18.dp, big = false)
                TypingWords(style, names[0], textStyle, bold, wrap = false)
            }
        } else {
            TypingDots()
            val (key, args) = when (ids.size) {
                1 -> "chat.typingBar.oneTyping" to listOf("a" to names[0])
                2 -> "chat.typingBar.twoTyping" to listOf("a" to names[0], "b" to names[1])
                3 -> "chat.typingBar.threeTyping" to listOf("a" to names[0], "b" to names[1], "c" to names[2])
                else -> "chat.typingBar.severalPeopleAreTyping" to emptyList()
            }
            BasicText(boldNames(t(key, *args.map { (k, v) -> k to "\u0000$v\u0001" }.toTypedArray()), bold), style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** TypingLine.tsx StyledTyping: one person typing the way their style says, or with the plain dots and words without one. */
@Composable
fun StyledTyping(style: TypingStyle?, name: String) {
    val c = Nx.colors
    val textStyle = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), color = c.textSubtle)
    val bold = SpanStyle(fontWeight = FontWeight(600), color = c.text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (style != null && style.emoji.isNotEmpty()) TypingEmoji(style, 18.dp, big = false) else TypingDots()
        TypingWords(style ?: TypingStyle(emptyList(), "wave"), name, textStyle, bold, wrap = true)
    }
}

/** Puts the names (marked with \u0000 … \u0001 while translating) in bold. */
private fun boldNames(s: String, bold: SpanStyle) = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val a = s.indexOf('\u0000', i)
        if (a < 0) {
            append(s.substring(i)); break
        }
        append(s.substring(i, a))
        val b = s.indexOf('\u0001', a).let { if (it < 0) s.length else it }
        withStyle(bold) { append(s.substring(a + 1, b)) }
        i = b + 1
    }
}

@Composable
private fun TypingWords(style: TypingStyle, name: String, textStyle: androidx.compose.ui.text.TextStyle, bold: SpanStyle, wrap: Boolean) {
    val (key, words) = TypingStyle.wordsKey(style)
    val args = buildList {
        add("a" to "\u0000$name\u0001")
        if (words != null) add("words" to words)
    }
    BasicText(boldNames(t(key, *args.toTypedArray()), bold), modifier = Modifier.clipToBounds(), style = textStyle, maxLines = if (wrap) 3 else 1, overflow = TextOverflow.Ellipsis)
}

private val EASE_IN_OUT = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val CSS_EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** A CSS keyframe track: (offset 0..1, value) stops, eased between them. */
private fun keyframes(p: Float, stops: List<Pair<Float, Float>>, easing: Easing): Float {
    for (i in 0 until stops.size - 1) {
        val (a, va) = stops[i]
        val (b, vb) = stops[i + 1]
        if (p <= b) {
            val f = if (b > a) ((p - a) / (b - a)).coerceIn(0f, 1f) else 1f
            return va + (vb - va) * easing.transform(f)
        }
    }
    return stops.last().second
}

/** A 0..1 clock running round every [periodMs]. */
@Composable
private fun clock(periodMs: Int): State<Float> {
    return fish.nexa.ui.components.decorClock(periodMs, informs = true)
}

/** Three dots taking turns to hop (tdot); with reduced motion they breathe in place. */
@Composable
fun TypingDots() {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val period = if (reduced) 1600 else 1400
    val t by clock(period)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val p = ((t - i * 200f / period) % 1f + 1f) % 1f
            val (dy, alpha) = if (reduced) 0f to keyframes(p, listOf(0f to 0.3f, 0.5f to 1f, 1f to 0.3f), CSS_EASE_IN_OUT)
            else keyframes(p, listOf(0f to 0f, 0.3f to -3f, 0.6f to 0f, 1f to 0f), CSS_EASE_IN_OUT) to keyframes(p, listOf(0f to 0.4f, 0.3f to 1f, 0.6f to 0.4f, 1f to 0.4f), CSS_EASE_IN_OUT)
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { translationY = dy * density; this.alpha = alpha }
                    .background(c.textMuted, CircleShape),
            )
        }
    }
}

/** A typing style's emoji, each moving the chosen way a beat after the one before. */
@Composable
fun TypingEmoji(style: TypingStyle, size: Dp, big: Boolean) {
    val reduced = Nx.motion.reduced
    val motion = style.motion
    val period = when {
        reduced && motion != "still" -> 1600
        motion == "wiggle" -> 1500
        else -> 1300
    }
    val t by clock(period)
    val stagger = when {
        reduced -> 200f
        motion == "wave" -> 160f
        motion == "pulse" -> 180f
        else -> 200f
    }
    val density = LocalDensity.current.density
    Row(horizontalArrangement = Arrangement.spacedBy(if (big) 5.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        style.emoji.forEachIndexed { i, token ->
            val p = ((t - i * stagger / period) % 1f + 1f) % 1f
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0.7f)
                        if (reduced && motion != "still") {
                            alpha = keyframes(p, listOf(0f to 0.55f, 0.5f to 1f, 1f to 0.55f), CSS_EASE_IN_OUT)
                        } else when (motion) {
                            "wave" -> translationY = keyframes(p, listOf(0f to 0f, 0.25f to (if (big) -7f else -4f), 0.55f to 0f, 1f to 0f), EASE_IN_OUT) * density
                            "pulse" -> {
                                val s = keyframes(p, listOf(0f to 1f, 0.25f to 1.3f, 0.55f to 1f, 1f to 1f), CSS_EASE_IN_OUT)
                                scaleX = s; scaleY = s
                            }
                            "wiggle" -> rotationZ = keyframes(p, listOf(0f to 0f, 0.15f to -16f, 0.35f to 14f, 0.6f to 0f, 1f to 0f), CSS_EASE_IN_OUT)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { TypingGlyph(token, size) }
        }
    }
}

@Composable
private fun TypingGlyph(token: String, size: Dp) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    val image = TypingStyle.image(token)
    val custom = TypingStyle.custom(token)
    when {
        image != null -> AsyncImage(Media.typingUrl(image, size.value.toInt(), density), null, Modifier.size(size))
        custom != null -> AsyncImage(Media.emojiUrl(custom.id, size.value.toInt(), density), custom.name, Modifier.size(size))
        else -> TwemojiImage(token, size)
    }
}

/** TypingCard: the style shown off, what it is, and a way to make your own. */
private fun openTypingCard(userId: String, style: TypingStyle, ref: AnchorRef) {
    openPopout("typing-card", ref.rect, 320.dp) { close ->
        val c = Nx.colors
        val name = Names.shown(userId)
        Column(
            Modifier.width(320.dp).padding(16.dp).semantics { contentDescription = t("typing.card.label", "name" to name) },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .background(mixOklab(c.bgFloating, c.bgBase, 0.55f), Radii.Sm)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (style.emoji.isNotEmpty()) TypingEmoji(style, 28.dp, big = true)
                TypingWords(style, name, Nx.type.body.copy(fontSize = Nx.type.sp(16f), color = c.textSubtle), SpanStyle(fontWeight = FontWeight(600), color = c.text), wrap = true)
            }
            Txt(t("typing.card.body"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = c.textMuted)
            NxButton(t("typing.card.makeYourOwn"), {
                close()
                fish.nexa.ui.settings.Settings.open("profiles", "typing")
            }, size = ButtonSize.SM, block = true)
        }
    }
}
