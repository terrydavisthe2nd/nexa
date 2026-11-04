package fish.nexa.ui.moderation

import fish.nexa.ui.components.Glide
import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideItem
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ModUser
import fish.nexa.data.Moderation
import fish.nexa.data.NexaInbox
import fish.nexa.data.NexaPerson
import fish.nexa.media.Media
import fish.nexa.net.Activity
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.AvatarColor
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.SelectField
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sink
import fish.nexa.ui.nexa.personName
import fish.nexa.ui.profile.Activities
import fish.nexa.ui.profile.ActivityIcon
import fish.nexa.ui.profile.Presences
import fish.nexa.ui.profile.StatusEmojiImage
import fish.nexa.ui.profile.statusEmojiText
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.NexaColors
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.dangerGradient
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge

// ---------------------------------------------------------------------------
// The panel's own pieces (Moderation.module.css, Sheet.module.css), shared by
// its tabs: chips, cards, flags, tags, the panels of a person's or a server's
// page with their rows, and the reason box every action asks for.
// ---------------------------------------------------------------------------

/** Moderation.module.css `--card-bg`: the cards stand a step above the panel. */
fun cardFill(c: NexaColors) = mixOklab(c.bgSurface, c.bgRaised, 0.45f)

/** The banner a profile or server shows where it has no picture: its two colours, one, or the id's own. */
fun profileBrush(primary: Long?, secondary: Long?, fallbackId: String): Brush {
    fun col(n: Long) = Color(0xFF000000L or (n and 0xFFFFFF))
    return when {
        primary != null && secondary != null -> Brush.linearGradient(listOf(col(primary), col(secondary)), Offset(0f, 0f), Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
        primary != null -> Brush.linearGradient(listOf(col(primary), col(primary)))
        else -> AvatarColor.of(fallbackId).let { Brush.linearGradient(listOf(it, it)) }
    }
}

/** The name of a person a response named, or the stand-in for someone gone. */
@Composable
fun person(id: String?): NexaPerson? {
    val people by NexaInbox.people.collectAsState()
    return id?.let { people[it] ?: Nexa.users.get(it)?.let { u -> NexaPerson(u.id, u.username, u.displayName, u.avatarId, u.flags) } }
}

/** `.chip`: a pill to pick a view with, lit in the accent when it is the one picked. */
@Composable
fun ModChip(label: String, selected: Boolean, onClick: () -> Unit, count: Int? = null, icon: String? = null, danger: Boolean = false, small: Boolean = false, pressedStyle: Boolean = false) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(20.dp)
    val lit = selected && !pressedStyle
    Row(
        Modifier
            .height(40.dp)
            .sink(pressed)
            .then(
                when {
                    lit && danger -> Modifier.boxShadow(shape, 6f, 18f, c.danger.copy(alpha = 0.8f), spread = -8f).background(dangerGradient(c), shape).topEdge(shape, Color.White.copy(alpha = 0.2f))
                    lit -> Modifier.ring(shape, c.brand.copy(alpha = 0.55f)).boxShadow(shape, 6f, 22f, c.brand.copy(alpha = 0.75f), spread = -6f).background(brandGradient(c), shape).topEdge(shape, Color.White.copy(alpha = 0.22f))
                    // a filter that is on (With Files): the selected fill and a stronger edge, not the accent
                    selected -> Modifier.ring(shape, c.borderStrong).background(c.bgSelected, shape)
                    else -> Modifier.ring(shape, if (pressed) c.borderStrong else c.borderSubtle).background(c.bgSurface, shape).topEdge(shape, c.edgeLight)
                },
            )
            .clip(shape)
            .pressable(interaction, role = if (pressedStyle) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (small) 10.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = when {
            lit -> if (danger) c.onDanger else c.onBrand
            selected -> c.textStrong
            pressed -> c.textStrong
            else -> c.textSubtle
        }
        if (icon != null) Icon(icon, fg, size = 15.dp)
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(if (small) 13f else 13.5f), fontWeight = FontWeight(600)), color = fg, maxLines = 1)
        if (count != null) Txt(count.toString(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(600), fontFeatureSettings = "tnum"), color = fg.copy(alpha = 0.85f), maxLines = 1)
    }
}

/** `.chips`: the pills of one choice, wrapping onto as many rows as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** `.search`: a field with the magnifier inside its start. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = Nx.colors
    NxTextField(
        value, onChange, modifier,
        placeholder = placeholder,
        imeAction = ImeAction.Search,
        leading = { Icon("Search", c.textMuted, size = 17.dp) },
        trailing = if (value.isNotEmpty()) ({ fish.nexa.ui.components.IconButton("X", t("chat.chat.clearSearch"), { onChange("") }, tint = c.textMuted, size = 40.dp, iconSize = 16.dp) }) else null,
    )
}

/** `.sort`: how a list is ordered, as the app's select. */
@Composable
fun SortField(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    // as wide as its words, as the web's select is, not the whole row
    Box(modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 150.dp)) {
        SelectField(null, options, selected) { onSelect(it) }
    }
}

/** `.card`: a card standing on the panel, lit along its top edge; its edge takes the colour of a mute or a ban. */
fun Modifier.modCard(c: NexaColors, state: String? = null): Modifier {
    val shape = Radii.Lg
    val edged = when (state) {
        // picked for an action on many: a ring of the accent (`.card[data-picked]`)
        "picked" -> this.ring(shape, c.brand, 2.dp).boxShadow(shape, 12f, 30f, c.brand.copy(alpha = 0.6f), spread = -14f)
        "banned" -> this.ring(shape, c.danger.copy(alpha = 0.55f)).boxShadow(shape, 10f, 30f, c.danger.copy(alpha = 0.55f), spread = -14f)
        "muted" -> this.ring(shape, c.warning.copy(alpha = 0.5f)).boxShadow(shape, 10f, 30f, c.warning.copy(alpha = 0.45f), spread = -14f)
        else -> this.ring(shape, c.panelEdge).shadowMedium(shape, c)
    }
    return edged.background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).clip(shape)
}

/** `.cardBanner`: a banner picture, or the colours standing in for one, dimming towards the card. */
@Composable
fun CardBanner(bannerId: String?, brush: Brush, height: Dp, modifier: Modifier = Modifier, highlight: Boolean = false) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val density = LocalDensity.current.density
    Box(modifier.fillMaxWidth().height(height).background(brush)) {
        if (bannerId != null) AsyncImage(Media.bannerUrl(bannerId, 680, density), null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.06f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f))))
        if (highlight) Box(Modifier.matchParentSize().background(c.brand.copy(alpha = 0.16f)).border(2.dp, c.focusRing))
    }
}

/** `.flag`: a state on a card's banner (banned, muted, disabled, public). */
@Composable
fun Flag(label: String, tone: String = "", icon: String? = null) {
    val c = Nx.colors
    val shape = RoundedCornerShape(11.dp)
    val (bg, fg) = when (tone) {
        "danger" -> null to c.onDanger
        "warn" -> mixSrgb(c.warning, Color.Black, 0.08f) to c.onWarning
        "muted" -> mixSrgb(c.statusOffline, Color.Black, 0.30f) to Color.White
        // glass with the words in amber (`.altBadge`), set apart from a mute's solid amber
        "alt" -> Color(0x9E0A0614) to c.warning
        else -> Color(0x8C0A0614) to Color.White
    }
    Row(
        Modifier
            .height(22.dp)
            .boxShadow(shape, 2f, 8f, Color.Black.copy(alpha = 0.25f))
            .then(if (bg == null) Modifier.background(dangerGradient(c), shape) else Modifier.background(bg, shape))
            .then(if (tone == "alt") Modifier.ring(shape, c.warning.copy(alpha = 0.55f)) else Modifier.topEdge(shape, Color.White.copy(alpha = 0.14f)))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, fg, size = 12.dp)
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(700)), color = fg, maxLines = 1)
    }
}

/** `.staffBadge`: Owner or Staff beside a name. */
@Composable
fun StaffBadge(label: String, icon: String?) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.height(20.dp).ring(shape, c.brand.copy(alpha = 0.30f)).background(c.brand.copy(alpha = 0.16f), shape).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, c.textBrand, size = 12.dp)
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = c.textBrand, maxLines = 1)
    }
}

/** Someone's staff rank as the panel marks them, nothing for anyone else. */
@Composable
fun RankBadge(userId: String, flags: Long, withIcon: Boolean = true) {
    val owner = Moderation.ownerId.collectAsState().value
    val rank = fish.nexa.data.StaffRanks.of(userId, flags, owner?.ifEmpty { null }) ?: return
    StaffBadge(rank.label, if (withIcon) rank.icon else null)
}

/** `.tag`: a small label in a list (Needs Reply, a mute's end, Closed). */
@Composable
fun Tag(label: String, tone: String = "") {
    val c = Nx.colors
    val (fg, bg) = when (tone) {
        "danger" -> c.onDanger to c.danger
        "warn" -> c.textWarning to c.warning.copy(alpha = 0.16f)
        "done" -> c.positive to c.positive.copy(alpha = 0.14f)
        "info" -> c.textBrand to c.brand.copy(alpha = 0.16f)
        else -> c.textSubtle to c.bgActive
    }
    Box(Modifier.height(18.dp).background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = fg, maxLines = 1)
    }
}

/** `.caseIcon`: an action's icon in a round tile of its colour. */
@Composable
fun CaseIcon(action: String, size: Dp = 34.dp) {
    val c = Nx.colors
    val (fg, bg) = toneColours(fish.nexa.ui.nexa.severity(action), c)
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(fish.nexa.ui.nexa.actionIcon(action), fg, size = size * 0.5f)
    }
}

fun toneColours(tone: String, c: NexaColors): Pair<Color, Color> = when (tone) {
    "warn" -> c.warning to c.warning.copy(alpha = 0.14f)
    "danger" -> c.danger to c.danger.copy(alpha = 0.14f)
    else -> c.textBrand to c.brand.copy(alpha = 0.14f)
}

/** `.badge`: a count on a tab, in the danger gradient. */
@Composable
fun TabBadge(n: Int, label: String) {
    val c = Nx.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .heightIn(min = 19.dp)
            .widthIn(min = 19.dp)
            .boxShadow(shape, 2f, 6f, c.danger.copy(alpha = 0.4f))
            .background(dangerGradient(c), shape)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Txt(if (n > 99) "99+" else n.toString(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700), fontFeatureSettings = "tnum"), color = c.onDanger, maxLines = 1) }
}

/** `.iconBtn`: a square button with only an icon, raised like a secondary one. */
@Composable
fun ModIconButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, positive: Boolean = false) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Sm
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .sink(pressed)
            .then(
                // a positive one (Lift) is filled in its colour, as the web's positive button is
                if (positive) Modifier.boxShadow(shape, 6f, 18f, c.positive.copy(alpha = 0.8f), spread = -8f).background(if (pressed) androidx.compose.ui.graphics.SolidColor(c.positiveHover) else fish.nexa.ui.theme.positiveGradient(c), shape).topEdge(shape, Color.White.copy(alpha = 0.22f))
                else Modifier.ring(shape, c.border).background(c.bgSurface, shape).background(sheen(c), shape).then(if (pressed) Modifier.background(c.bgActive, shape) else Modifier).topEdge(shape, c.edgeLight),
            )
            .clip(shape)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // back points the other way in a right-to-left language
        Icon(icon, if (positive) c.onPositive else if (pressed) c.textStrong else c.icon, size = 18.dp, modifier = if (icon == "ChevronLeft" || icon == "ArrowLeft") Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f } else Modifier)
    }
}

/** `.empty`: an icon, what is (not) here, and maybe a way on. */
@Composable
fun EmptyState(icon: String?, title: String?, text: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, c.textPlaceholder, size = 40.dp)
        if (title != null) Txt(title, style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(680)), color = c.textStrong, align = androidx.compose.ui.text.style.TextAlign.Center)
        if (text != null) Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** Said as a failure, never as an empty list: "nothing waiting" during an outage would tell staff nobody waits when somebody does. */
@Composable
fun LoadFailed(onRetry: () -> Unit, busy: Boolean = false) {
    EmptyState(null, null, t("moderation.loadFailed")) {
        NxButton(t("moderation.retry"), onRetry, kind = fish.nexa.ui.components.ButtonKind.SECONDARY, size = fish.nexa.ui.components.ButtonSize.SM, loading = busy)
    }
}

@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
}

/** `.presence`, `.custom` and `.activity`: where someone stands right now, for the staff (ModStatus.tsx). */
data class ModStatus(val status: String, val customText: String, val customEmoji: String, val activities: List<Activity>) {
    val hasCustom get() = customText.isNotEmpty() || customEmoji.isNotEmpty()
}

/**
 * The live presence when this account can see it, and otherwise the one the
 * grid loaded. The custom status is theirs to moderate, so it shows even
 * while they are away, unless it has run out.
 */
fun modStatus(u: ModUser, now: Long = System.currentTimeMillis()): ModStatus {
    val live = Presences.of(u.id)
    if (live != null && live.status != "offline" && live.status != "invisible") {
        val cs = Presences.customStatus(live)
        return ModStatus(live.status, cs?.text ?: "", cs?.emoji ?: "", Presences.activitiesOf(live))
    }
    val expired = u.statusExpiresAt != null && u.statusExpiresAt < now
    val status = if (live != null) "offline" else (u.presence?.status ?: "offline")
    return ModStatus(status, if (expired) "" else u.statusText, if (expired) "" else u.statusEmoji, if (status == "offline") emptyList() else u.presence?.activities ?: emptyList())
}

fun statusLabel(status: String) = when (status) {
    "online" -> t("shell.user.online")
    "idle" -> t("shell.user.idle")
    "dnd" -> t("shell.user.doNotDisturb")
    "invisible" -> t("shell.user.invisible")
    else -> t("chat.memberList.offline")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusSummary(st: ModStatus, compact: Boolean = false) {
    val c = Nx.colors
    Nexa.presence.byId.collectAsState().value
    val (dot, fg) = when (st.status) {
        "online" -> c.statusOnline to c.statusOnline
        "idle" -> c.statusIdle to c.statusIdle
        "dnd" -> c.statusDnd to c.statusDnd
        else -> c.statusOffline to c.textMuted
    }
    val presence = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .then(if (st.status == "online" || st.status == "idle" || st.status == "dnd") Modifier.ring(CircleShape, dot.copy(alpha = 0.22f), 3.dp) else Modifier)
                    .background(dot, CircleShape),
            )
            Txt(statusLabel(st.status), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = fg, maxLines = 1)
        }
    }
    val custom = @Composable {
        if (st.hasCustom) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.semantics { contentDescription = listOf(statusEmojiText(st.customEmoji), st.customText).filter { it.isNotEmpty() }.joinToString(" ") }) {
            if (st.customEmoji.isNotEmpty()) StatusEmojiImage(st.customEmoji, 16.dp)
            Txt(st.customText, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.text, maxLines = 1)
        }
    }
    val activity = @Composable {
        st.activities.firstOrNull()?.let { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ActivityIcon(a, more = maxOf(0, st.activities.size - 1), size = 13.dp)
                Txt(Activities.line(a), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(550)), color = c.textSubtle, maxLines = 1)
            }
        }
    }
    if (compact) FlowRow(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        presence(); custom(); activity()
    } else {
        val shape = Radii.Sm
        Column(
            Modifier
                .padding(top = 3.dp)
                .fillMaxWidth()
                .background(mixOklab(c.bgBase, cardFill(c), 0.45f), shape)
                .ring(shape, c.borderSubtle)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) { presence(); custom(); activity() }
    }
}

/** `.cardMeta` and the like: a small icon and words, muted. */
@Composable
fun MetaItem(icon: String?, text: String) {
    val c = Nx.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Icon(icon, c.textMuted, size = 13.dp)
        Txt(text, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// a person's or a server's page (Sheet.module.css)

/** `.panel`: a section of a person's or a server's page, with its title and a hint under it. */
@Composable
fun SheetPanel(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val shape = Radii.Lg
    Column(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.panelEdge)
            .shadowLow(shape, c)
            .background(mixOklab(c.bgSurface, c.bgRaised, 0.6f), shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, style = Nx.type.name.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(700)), color = c.textStrong, modifier = Modifier.semantics { heading() })
            if (hint != null) Txt(hint, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted)
        }
        content()
        Box(Modifier.height(6.dp))
    }
}

/** A hairline across the top of a row in a panel. */
fun Modifier.topRule(c: NexaColors): Modifier = this.drawBehind { drawRect(c.borderSubtle, Offset.Zero, Size(size.width, 1.dp.toPx())) }

/**
 * `.row` on a phone: a part of a profile anyone can see, its name over what
 * it says now, and the button that takes it down beside them.
 */
@Composable
fun FieldRowView(label: String, empty: Boolean, action: @Composable () -> Unit, value: @Composable () -> Unit) {
    val c = Nx.colors
    Row(
        Modifier.fillMaxWidth().topRule(c).heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.textMuted)
            value()
        }
        action()
    }
}

/** What a field row says when the part is empty, or a short value of it. */
@Composable
fun FieldValue(text: String, empty: Boolean = false, maxLines: Int = 2) {
    val c = Nx.colors
    Txt(
        text,
        style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(20f), fontStyle = if (empty) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal),
        color = if (empty) c.textMuted else c.text,
        maxLines = maxLines,
    )
}

/** `.serverRow`: a server, a pack, a case or a person as a row that opens it. */
@Composable
fun OpenRow(name: String, meta: String?, onClick: () -> Unit, trailing: String? = null, personId: String? = null, leading: @Composable () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .fillMaxWidth()
            .topRule(c)
            .background(if (pressed) c.bgHover else Color.Transparent)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            fish.nexa.ui.chat.PersonName(personId, name, style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
            if (!meta.isNullOrEmpty()) Txt(meta, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
        }
        if (trailing != null) Txt(trailing, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
        Icon(if (rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 18.dp)
    }
}

/** `.facts`: facts about an account, a server or a pack; one to a row on a phone. */
@Composable
fun FactsList(facts: List<Triple<String?, String, @Composable () -> Unit>>) {
    val c = Nx.colors
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((icon, label, value) in facts) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (icon != null) Icon(icon, c.textMuted, size = 14.dp)
                Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650)), color = c.textMuted)
            }
            value()
        }
    }
}

/** A fact's value. */
@Composable
fun FactText(text: String) = Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = Nx.colors.text)

/** `.copy`: an id in code type that copies itself. */
@Composable
fun CopyId(id: String, label: String) {
    val c = Nx.colors
    Row(
        Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { fish.nexa.ui.shell.copyText(id) }.semantics { contentDescription = label }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(id, style = Nx.type.code.copy(fontSize = Nx.type.sp(13f)), color = c.text)
        Icon("Copy", c.textMuted, size = 13.dp)
    }
}

/** A server's icon as the app draws it everywhere, its initials on a tile where it has none (ProfileSheet ServerIcon). */
@Composable
fun ServerIcon(id: String, iconId: String?, name: String, size: Dp) {
    Avatar(id, name, iconId, size, icon = true, shape = RoundedCornerShape(30))
}

/** `.userHeader`: who or what a page is about, with a way back and what can be done. */
@Composable
fun PageHead(back: String, onBack: () -> Unit, leading: @Composable () -> Unit, title: String, sub: String?, badge: (@Composable () -> Unit)? = null, below: (@Composable () -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null, titlePersonId: String? = null) {
    val c = Nx.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgRaised)
            .background(Brush.verticalGradient(listOf(c.brand.copy(alpha = 0.07f), Color.Transparent)))
            .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModIconButton("ChevronLeft", back, onBack)
            leading()
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fish.nexa.ui.chat.PersonName(titlePersonId, title, style = Nx.type.name.copy(fontSize = Nx.type.sp(20f), lineHeight = Nx.type.sp(25f), fontWeight = FontWeight(690)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    badge?.invoke()
                }
                if (!sub.isNullOrEmpty()) Txt(sub, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(18f)), color = c.textMuted)
                below?.invoke()
            }
        }
        if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/**
 * `.subTab`: a person's page, one view at a time. With a [glide] the chosen
 * one draws no background of its own: the row's shared pill slides to it
 * ([SubTabPill], components/Glide.kt).
 */
@Composable
fun SubTab(label: String, icon: String, selected: Boolean, count: Int? = null, badge: Int = 0, glide: Glide? = null, key: Any = label, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .height(40.dp)
            .then(if (glide != null) Modifier.glideItem(glide, key) else Modifier)
            .background(if (selected && glide == null) c.bgSelected else if (pressed && !selected) c.bgHover else Color.Transparent, shape)
            .then(if (selected && glide == null) Modifier.topEdge(shape, c.edgeLight) else Modifier)
            .clip(shape)
            .pressable(interaction, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val fg = if (selected || pressed) c.textStrong else c.textMuted
        Icon(icon, fg, size = 16.dp)
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = fg, maxLines = 1)
        if (count != null) Txt(count.toString(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = fg.copy(alpha = 0.85f))
        if (badge > 0) TabBadge(badge, t("moderation.needsReplyCount", "count" to badge))
    }
}

/** The pill a row of [SubTab]s shares, drawn behind them in their [glideArea] Box. */
@Composable
fun BoxScope.SubTabPill(glide: Glide) {
    val c = Nx.colors
    val shape = RoundedCornerShape(10.dp)
    GlidePill(glide, Modifier.background(c.bgSelected, shape).topEdge(shape, c.edgeLight))
}

/** A rule across the page, under a header or a row of sub tabs. */
@Composable
fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Nx.colors.borderSubtle))

// ---------------------------------------------------------------------------
// the dialogs' parts (the Moderate dialogs' `.section`, `.pick`, `.note`, `.danger`, `.quick`)

@Composable
fun SectionLabel(text: String) = Txt(text, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700), letterSpacing = androidx.compose.ui.unit.TextUnit(0.01f, androidx.compose.ui.unit.TextUnitType.Em)), color = Nx.colors.textMuted)

/** `.note`: what an action will do, said before it is done. */
@Composable
fun NoteBox(text: String) {
    val c = Nx.colors
    Txt(
        text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19.6f)), color = c.text,
        modifier = Modifier.fillMaxWidth().background(c.brand.copy(alpha = 0.09f), Radii.Sm).border(1.dp, c.brand.copy(alpha = 0.26f), Radii.Sm).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** `.danger`: a warning box, or an error. */
@Composable
fun DangerBox(content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().background(c.danger.copy(alpha = 0.09f), Radii.Sm).border(1.dp, c.danger.copy(alpha = 0.40f), Radii.Sm).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** `.pick`: a choice with a tick box, the part it is about beside it. */
@Composable
fun PickBox(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, danger: Boolean = false, label: String, content: @Composable RowScope.() -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    val tone = if (danger) c.danger else c.brand
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (checked) mixSrgb(c.bgRaised, tone, 0.10f) else c.bgSurface, shape)
            .border(1.dp, if (checked) tone else c.borderSubtle, shape)
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .semantics {
                contentDescription = label
                toggleableState = androidx.compose.ui.state.ToggleableState(checked)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val box = RoundedCornerShape(4.dp)
        Box(
            Modifier.size(18.dp).then(if (checked) Modifier.background(tone, box) else Modifier.border(1.5.dp, c.borderStrong, box)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon("Check", Color.White, size = 13.dp, stroke = 3f) }
        content()
    }
}

/** The words of a pick: its name, and what it says now (two lines at most). */
@Composable
fun RowScope.PickText(name: String, value: String?) {
    val c = Nx.colors
    Column(Modifier.weight(1f)) {
        Txt(name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.textStrong)
        if (value != null) Txt(value, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)), color = c.textMuted, maxLines = 2)
    }
}

const val MAX_REASON = 1000

/**
 * The reason every action asks for: the box, a few common ones to add with a
 * tap, and what it says about who will read it, or that it is too short yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReasonSection(reason: String, onChange: (String) -> Unit, placeholder: String, visible: String, quick: List<String>, personId: String? = null, personName: String = "") {
    val c = Nx.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(t("moderation.form.reason"))
        NxTextField(reason, { onChange(it.take(MAX_REASON + 50)) }, placeholder = placeholder, singleLine = false, minLines = 3, capitalize = true, imeAction = ImeAction.Default)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (q in quick) {
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .background(c.bgSurface, shape)
                        .border(1.dp, c.borderSubtle, shape)
                        .clip(shape)
                        .pressable(remember { MutableInteractionSource() }) { onChange(if (reason.isNotBlank()) reason.trim() + " " + q else q) }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(q, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textSubtle, maxLines = 1) }
            }
        }
        val length = reason.codePointCount(0, reason.length)
        val trimmed = reason.trim()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fish.nexa.ui.chat.PersonText(personId, visible, personName, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, modifier = Modifier.weight(1f))
            // Submit waits for three characters, and says so
            if (trimmed.codePointCount(0, trimmed.length) < 3) Txt(t("moderation.form.reasonTooShort", "min" to 3), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textWarning)
            else Txt("$length/$MAX_REASON", style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontFeatureSettings = "tnum"), color = if (length > MAX_REASON) c.textDanger else c.textMuted)
        }
    }
}

/** Whether a reason will do: three characters at least, the limit at most. */
fun reasonOk(reason: String): Boolean {
    val trimmed = reason.trim()
    return trimmed.codePointCount(0, trimmed.length) >= 3 && reason.codePointCount(0, reason.length) <= MAX_REASON
}

/** The usual reasons, as the web offers them. */
fun quickReasons(vararg extra: String): List<String> = listOf(
    t("moderation.quick.hateful"), t("moderation.quick.impersonation"), t("moderation.quick.spam"), t("moderation.quick.harassment"), t("moderation.quick.explicit"),
) + extra.toList()

/** A person's name, or "Unknown User", as the panel shows it. */
fun nameOf(p: NexaPerson?) = personName(p)

/**
 * Holds what is inside to [max] tall until opened, and says whether it is
 * taller than that (so a Show More is worth offering).
 */
@Composable
fun FoldBox(max: Dp, open: Boolean, onFolds: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val maxPx = with(LocalDensity.current) { max.roundToPx() }
    Box(Modifier.fillMaxWidth().then(if (open) Modifier else Modifier.heightIn(max = max)).clipToBounds()) {
        Box(Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true).onSizeChanged { onFolds(it.height > maxPx) }) { content() }
    }
}

/** A negative top margin: drawn [by] higher, taking that much less room (`margin-top: -48px`). */
fun Modifier.pullUp(by: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val up = by.roundToPx()
        layout(p.width, maxOf(0, p.height - up)) { p.placeRelative(0, -up) }
    },
)
