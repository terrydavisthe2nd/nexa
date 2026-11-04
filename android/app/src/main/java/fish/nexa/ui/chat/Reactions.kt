package fish.nexa.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ChatMessage
import fish.nexa.data.P
import fish.nexa.data.Send
import fish.nexa.media.Media
import fish.nexa.media.TwemojiImage
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.AnchorRef
import fish.nexa.ui.layers.anchor
import fish.nexa.ui.layers.openPopout
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.ring

/** A custom emoji as a reaction key: ":name:blob" or "a:name:blob" (stores/expressions.ts emojiReactionKey). */
data class CustomEmoji(val name: String, val blobId: String, val animated: Boolean)

fun customOf(key: String): CustomEmoji? {
    val m = Regex("^(a)?:([A-Za-z0-9_]{1,32}):(\\d{1,20})$").find(key) ?: Regex("^([A-Za-z0-9_]{1,32}):(\\d{1,20})$").find(key)?.let { return CustomEmoji(it.groupValues[1], it.groupValues[2], false) }
    return m?.let { CustomEmoji(it.groupValues[2], it.groupValues[3], it.groupValues[1] == "a") }
}

/** An emoji wherever it is drawn: a server's own picture, or Twemoji artwork for a standard one. */
@Composable
fun EmojiGlyph(key: String, size: Dp, modifier: Modifier = Modifier, source: Int = 48) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val custom = customOf(key)
    if (custom != null) {
        val density = LocalDensity.current.density
        AsyncImage(Media.emojiUrl(custom.blobId, source, density), custom.name, modifier.size(size))
    } else TwemojiImage(key, size, modifier)
}

/** How a reaction reads to a screen reader: the emoji itself, or its :name:. */
fun emojiLabel(key: String): String = customOf(key)?.let { ":${it.name}:" } ?: key

fun canReact(cm: ChatMessage, ctx: ListCtx): Boolean {
    val st = ctx.server ?: return true
    return Nexa.servers.canI(st, P.ADD_REACTIONS, ctx.channel.id)
}

/**
 * Reactions.tsx: pills under the message, yours lit in the accent; a tap
 * toggles yours, holding one shows who reacted, and the last pill adds one.
 */
@Composable
fun ReactionsRow(cm: ChatMessage, ctx: ListCtx) {
    val reactions = cm.m.reactions
    // Reactions already here when the message was drawn stay still; one that
    // arrives pops in, a removed one that comes back too (web Reactions.tsx).
    // Remembered before the row can step aside for having none, or the first
    // reaction to arrive would already be in it and never pop.
    val seen = remember(cm.id) { HashSet(reactions.map { it.emoji }) }
    if (reactions.isEmpty()) return
    val can = canReact(cm, ctx)
    FlowRow(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        for (r in reactions) androidx.compose.runtime.key(r.emoji) {
            val fresh = remember { (r.emoji !in seen).also { seen.add(r.emoji) } }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { seen.remove(r.emoji) } }
            ReactionPill(cm, ctx, r.emoji, r.count, r.me, locked = !can && !r.me, fresh = fresh)
        }
        if (can) AddReactionPill(cm, ctx)
    }
}

@Composable
private fun ReactionPill(cm: ChatMessage, ctx: ListCtx, emoji: String, count: Int, me: Boolean, locked: Boolean, fresh: Boolean = false) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !locked && !Nx.motion.reduced) 0.94f else 1f, tween(120, easing = Nx.motion.spring), label = "pill")
    val still = Nx.motion.reduced
    val springy = Nx.motion.spring
    // always here, so a pop that has begun is always finished, whatever renders on the way
    val pop = remember { androidx.compose.animation.core.Animatable(if (fresh && !still) 0f else 1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (pop.value < 1f) pop.animateTo(1f, tween(260, easing = springy)) }
    // A count that moves ticks over: up from below as it grows, down from above as it
    // shrinks (web .pillCount). Decided as the new count is drawn, so it never shows
    // still for a frame first; the count the pill came with stays still.
    val ease = Nx.motion.easeOut
    val lastCount = remember { intArrayOf(count) }
    val tickFrom = remember(count) { val d = count.compareTo(lastCount[0]); lastCount[0] = count; if (still) 0 else d }
    val tick = remember(count) { androidx.compose.animation.core.Animatable(if (tickFrom == 0) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(tick) { if (tick.value < 1f) tick.animateTo(1f, tween(200, easing = ease)) }
    val haptics = LocalHapticFeedback.current
    val ref = remember { AnchorRef() }
    val shape = RoundedCornerShape(999.dp)
    val bg = if (me) mixSrgb(c.bgSurface, c.brand, 0.20f) else c.bgSurface
    Row(
        Modifier
            .anchor(ref)
            .graphicsLayer { val k = scale * (0.6f + 0.4f * pop.value); scaleX = k; scaleY = k; alpha = pop.value }
            .then(if (me) Modifier.boxShadow(shape, 0f, 12f, c.brand.copy(alpha = 0.7f), spread = -4f) else Modifier)
            .ring(shape, if (me) c.brand.copy(alpha = 0.7f) else c.borderSubtle)
            .background(bg, shape)
            .height(36.dp)
            .pressable(
                interaction,
                enabled = true,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    openReactors(cm, emoji, count, ref)
                },
            ) {
                if (!locked) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    Send.react(ctx.channel.id, cm.id, emoji, add = !me)
                }
            }
            .padding(start = 10.dp, end = 12.dp)
            .semantics { contentDescription = t("chat.reactions.pillLabel", "emoji" to emojiLabel(emoji), "count" to count) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        EmojiGlyph(emoji, 20.dp, source = 24)
        Txt(
            count.toString(),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600), fontFeatureSettings = "tnum"),
            color = if (me) c.textStrong else c.textSubtle,
            modifier = Modifier.graphicsLayer { alpha = tick.value; translationY = (1f - tick.value) * tickFrom * 0.45f * size.height },
        )
    }
}

@Composable
private fun AddReactionPill(cm: ChatMessage, ctx: ListCtx) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .size(44.dp, 36.dp)
            .pressable(remember { MutableInteractionSource() }) { openReactionPicker(cm, ctx) }
            .semantics { contentDescription = t("chat.reactions.addReaction") },
        contentAlignment = Alignment.Center,
    ) {
        Icon("SmilePlus", c.textMuted, size = 19.dp)
    }
}

private const val REACTORS_SHOWN = 20

/** ReactorsPopover: who reacted with this emoji, the first twenty and how many more. */
private fun openReactors(cm: ChatMessage, emoji: String, count: Int, ref: AnchorRef) {
    openPopout("reactors", ref.rect, 260.dp) { _ ->
        val c = Nx.colors
        var ids by remember { mutableStateOf<List<String>?>(null) }
        LaunchedEffect(Unit) {
            ids = runCatching { Send.reactors(cm.channelId, cm.id, emoji, REACTORS_SHOWN) }.getOrDefault(emptyList())
            ids?.let { Nexa.session.ensureUsers(it) }
        }
        Column(Modifier.width(260.dp).heightIn(max = 360.dp).semantics { contentDescription = t("chat.reactions.whoReacted", "emoji" to emojiLabel(emoji)) }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmojiGlyph(emoji, 22.dp)
                Txt(customOf(emoji)?.let { ":${it.name}:" } ?: "", style = Nx.type.label, color = c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
                Txt(count.toString(), style = Nx.type.label.copy(fontWeight = FontWeight(700)), color = c.textStrong)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Column(Modifier.padding(6.dp).verticalScroll(rememberScrollState())) {
                val list = ids
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
                } else {
                    for (id in list) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val name = Names.shown(id)
                            Avatar(id, name, Nexa.users.get(id)?.avatarId, 24.dp)
                            fish.nexa.ui.chat.PersonName(id, name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(500)), color = c.text, maxLines = 1)
                        }
                    }
                    if (count > list.size) Txt(
                        t("chat.reactions.andMore", "count" to (count - list.size)),
                        style = Nx.type.hint, color = c.textMuted, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
