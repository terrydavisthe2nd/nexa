package fish.nexa.ui.profile

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Badges
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.theme.NexaColors
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.ring

/** A badge tone's colour (Badges.module.css data-tone), an event badge's own. */
fun badgeTone(c: NexaColors, look: Badges.Look): Color = when (look.tone) {
    "og" -> c.badgeOg
    "years" -> c.badgeYears
    "bug" -> c.badgeBug
    "dev" -> c.badgeDev
    "contributor" -> c.badgeContributor
    "bronze" -> c.badgeBronze
    "silver" -> c.badgeSilver
    "gold" -> c.badgeGold
    else -> look.color?.let { Color(0xFF000000 or (it.toLong() and 0xFFFFFF)) } ?: c.brand
}

/**
 * One badge (components/badges/Badges.tsx BadgePill). Staff rank and plan
 * stay the tags they always were; every other badge is its icon alone on a
 * small tile in its own tone. Pressing one says what it is, as the web's
 * tooltip does; [still] draws it with nothing to press, and [named] puts the
 * name beside it, for lists (the designer, the staff panel).
 */
@Composable
fun BadgePill(badge: Badges.Badge, userId: String, flags: Long, plan: String, still: Boolean = false, named: Boolean = false) {
    when (badge.id) {
        "staff" -> return StaffBadge(userId, flags)
        "plan" -> return fish.nexa.ui.support.PlanBadge(plan)
    }
    val look = Badges.look(badge) ?: return
    val c = Nx.colors
    val tone = badgeTone(c, look)
    val text = c.badgeText(tone)
    val shape = RoundedCornerShape(7.dp)
    val metal = look.tone == "bronze" || look.tone == "silver" || look.tone == "gold"
    val tile = @Composable {
        Box(
            Modifier
                .size(24.dp)
                .clip(shape)
                .background(tone.copy(alpha = 0.18f))
                // the metal tiers' quiet slant of light
                .then(if (metal) Modifier.drawBehind {
                    drawRect(Brush.linearGradient(
                        0.30f to Color.Transparent, 0.46f to tone.copy(alpha = 0.22f), 0.62f to Color.Transparent,
                        start = Offset(0f, size.height), end = Offset(size.width, 0f),
                    ))
                } else Modifier)
                .ring(shape, tone.copy(alpha = 0.36f))
                .then(if (still) Modifier else Modifier.pressable(remember { MutableInteractionSource() }) { Nexa.ui.toast(if (look.tip.isEmpty()) look.label else "${look.label}: ${look.tip}") })
                .semantics { contentDescription = if (look.tip.isEmpty()) look.label else "${look.label}. ${look.tip}" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (fish.nexa.ui.Lucide.exists(look.icon)) look.icon else "award", text, size = 15.dp, stroke = 2.25f)
        }
    }
    if (!named) return tile()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tile()
        Txt(look.label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
    }
}

/** The staff rank and plan tags in their holder's order, drawn on the username line beside the pronouns (BadgeRow "tags"). */
@Composable
fun BadgeTags(userId: String, flags: Long, plan: String, badges: List<Badges.Badge>?) {
    for (b in (badges ?: Badges.fallback(userId, flags, plan)).filter { !it.hidden && (it.id == "staff" || it.id == "plan") }) BadgePill(b, userId, flags, plan)
}

/** The icon badges in their holder's order, on their own line under the username line (BadgeRow "icons"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(userId: String, flags: Long, plan: String, badges: List<Badges.Badge>?, modifier: Modifier = Modifier) {
    val icons = (badges ?: return).filter { !it.hidden && it.id != "staff" && it.id != "plan" }
    if (icons.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (b in icons) BadgePill(b, userId, flags, plan)
    }
}
