package fish.nexa.ui.settings

import androidx.compose.ui.graphics.graphicsLayer
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.profile.WidgetDesign
import fish.nexa.ui.profile.Widgets
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The pieces both profile designers are made of (designerParts.tsx): one calm
 * form of every part of the profile under three headings, the preview above
 * it, and the bar that keeps the preview a tap away once it scrolls out of view.
 * Only the page scrolls.
 */

/** The designer's shared state: the field just jumped to, and the preview bar's. */
object DesignerState {
    /** The field a part of the preview was pressed for, lit for a moment. */
    var flash by mutableStateOf<String?>(null)
    var flashAt by mutableLongStateOf(0L)
    /** The preview has scrolled out of view: the bar floats over the top of the page. */
    var away by mutableStateOf(false)
    /** Whose profile the bar shows: the picture and name, live. */
    var summary by mutableStateOf<(@Composable () -> Unit)?>(null)
    /** The preview folded to its bar, remembered on this device (the web's nexa.profilePreviewFolded). */
    var folded by mutableStateOf(readFolded())
        private set

    fun fold(v: Boolean) {
        folded = v
        runCatching { prefs().edit().putBoolean("previewFolded", v).apply() }
    }

    private fun prefs() = Nexa.app.getSharedPreferences("designer", Context.MODE_PRIVATE)
    private fun readFolded() = runCatching { prefs().getBoolean("previewFolded", false) }.getOrDefault(false)
}

private const val PREVIEW_KEY = "designer:preview"
private fun fieldKey(field: String) = "designer:field:$field"

/** Scrolls the page to a spot the designer marked (a field, or the preview), centred or at the top. */
private fun scrollToMark(host: AnchorHost, key: String, scope: CoroutineScope, center: Boolean, reduced: Boolean) {
    val target = host.positions[key] ?: return
    val content = host.content ?: return
    if (!target.isAttached || !content.isAttached) return
    val y = content.localPositionOf(target, Offset.Zero).y
    val goal = (if (center) y - host.viewportHeight / 2f + target.size.height / 2f else y - 8f).toInt().coerceIn(0, host.scroll.maxValue)
    scope.launch { if (reduced) host.scroll.scrollTo(goal) else host.scroll.animateScrollTo(goal) }
}

/** What pressing a part of the preview does: the page goes to its field, which is lit for a moment. */
fun jumpToField(host: AnchorHost?, field: String, scope: CoroutineScope, reduced: Boolean) {
    host ?: return
    scrollToMark(host, fieldKey(field), scope, center = true, reduced = reduced)
    DesignerState.flash = field
    DesignerState.flashAt = System.currentTimeMillis()
}

/** A group of fields under a heading (`.section`). */
@Composable
fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Txt(title, style = Nx.type.heading.copy(lineHeight = Nx.type.sp(24f)), color = c.textStrong, modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
    }
}

/** Marks a field so the preview can jump to it, and lights it when it does (`[data-flash]`). */
@Composable
private fun Modifier.formMark(field: String): Modifier {
    val host = LocalAnchors.current
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val glow = remember { Animatable(0f) }
    LaunchedEffect(DesignerState.flash, DesignerState.flashAt) {
        if (DesignerState.flash != field) return@LaunchedEffect
        glow.snapTo(1f)
        if (reduced) {
            kotlinx.coroutines.delay(1500); glow.snapTo(0f)
        } else {
            kotlinx.coroutines.delay(500)
            glow.animateTo(0f, tween(1000))
        }
    }
    val shape = Radii.Md
    return this
        .onGloballyPositioned { if (host != null) host.positions[fieldKey(field)] = it }
        .then(
            if (glow.value > 0f) Modifier
                .ring(shape, c.brand.copy(alpha = 0.55f * glow.value), 2.dp)
                .background(c.brand.copy(alpha = 0.16f * glow.value), shape)
            else Modifier,
        )
}

/** One part of the profile in the form: its name, what changes it, and a line of help when it needs one. */
@Composable
fun FormField(field: String, setting: String?, label: String, hint: String? = null, aside: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().formMark(field).settingAnchor(setting).padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong, modifier = Modifier.weight(1f))
            aside?.invoke()
        }
        content()
        if (hint != null) FieldHint(hint)
    }
}

@Composable
fun FieldHint(text: String) = Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = Nx.colors.textMuted)

/** A part whose tools take room (name style, server tag, typing style): its value in a line, opened in place. */
@Composable
fun FormExpander(field: String, setting: String?, label: String, open: Boolean, onToggle: (Boolean) -> Unit, value: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(Modifier.fillMaxWidth().formMark(field).settingAnchor(setting), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(Radii.Sm)
                .background(if (pressed) c.bgHover else Color.Transparent)
                .pressable(interaction, role = Role.Button) { onToggle(!open) }
                .semantics { stateDescription = if (open) "expanded" else "collapsed" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
            // the chevron turns as it opens and closes (web .expanderChevron)
            val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, androidx.compose.animation.core.tween(Nx.motion.normal, easing = Nx.motion.easeOut), label = "expander")
            Icon("ChevronDown", c.textMuted, size = 18.dp, modifier = Modifier.rotate(turn))
        }
        // what it holds unfolds as it opens, as a server's category does (web expanderIn); closing is immediate
        if (open) {
            val still = Nx.motion.reduced
            val ease = Nx.motion.easeOut
            val unfold = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
            LaunchedEffect(unfold) { if (unfold.value < 1f) unfold.animateTo(1f, androidx.compose.animation.core.tween(220, easing = ease)) }
            Column(
                Modifier.fillMaxWidth().graphicsLayer { alpha = unfold.value; translationY = (unfold.value - 1f) * 6.dp.toPx() }.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A part's current value in an expander's line or a field's row. */
@Composable
fun FieldValue(text: String, empty: Boolean = false) =
    Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = Nx.colors.textMuted, maxLines = 1)

/**
 * The widgets in the form, in the order the profile shows them: the first is
 * the one the card shows, and each can be moved, changed or taken off.
 */
@Composable
fun WidgetList(widgets: List<Widgets.Widget>, tools: WidgetDesign?) {
    val c = Nx.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (widgets.isNotEmpty()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            widgets.forEachIndexed { i, w ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(Radii.Sm)
                        .background(c.bgInput)
                        .ring(Radii.Sm, c.borderSubtle)
                        .padding(start = 2.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconTool("Pin", if (i == 0) t("designer.widget.onCard") else t("designer.widget.showOnCard"), tint = if (i == 0) c.textBrand else c.textMuted) { tools?.feature?.invoke(w.id) }
                    Txt(
                        Widgets.name(w),
                        style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)),
                        color = c.textStrong,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }, role = Role.Button) { tools?.edit?.invoke(w.id) }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    IconTool("ArrowUp", t("reorder.moveUp"), enabled = i > 0) { tools?.move?.invoke(w.id, -1) }
                    IconTool("ArrowDown", t("reorder.moveDown"), enabled = i < widgets.size - 1) { tools?.move?.invoke(w.id, 1) }
                    IconTool("Pencil", t("designer.widget.editShort")) { tools?.edit?.invoke(w.id) }
                    IconTool("Trash2", t("widgets.remove")) { tools?.remove?.invoke(w.id) }
                }
            }
        }
        NxButton(t("widgets.addOne"), { tools?.add?.invoke() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Plus", enabled = tools != null)
    }
}

@Composable
private fun IconTool(icon: String, label: String, tint: Color = Nx.colors.textMuted, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(40.dp)
            .clip(Radii.Xs)
            .background(if (pressed && enabled) c.bgHover else Color.Transparent)
            .pressable(interaction, role = Role.Button, enabled = enabled) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (enabled) tint else tint.copy(alpha = 0.35f), size = 16.dp) }
}

/** The bar that says whose profile it is and folds the preview open or shut (`.previewToggle`). */
@Composable
private fun PreviewBar(away: Boolean, onPress: () -> Unit, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val folded = DesignerState.folded
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (away) Modifier.shadowMedium(Radii.Md, c) else Modifier)
            .clip(Radii.Md)
            .background(if (pressed) c.bgHover else c.bgSurface)
            .background(sheen(c))
            .ring(Radii.Md, c.borderSubtle)
            .topEdge(Radii.Md, c.edgeLight)
            .pressable(interaction, role = Role.Button) { onPress() }
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { DesignerState.summary?.invoke() }
        Txt(if (folded || away) t("designer.preview.show") else t("designer.preview.hide"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1)
        Icon("ChevronDown", c.textMuted, size = 18.dp, modifier = Modifier.rotate(if (!folded && !away) 180f else 0f))
    }
}

/**
 * The preview above the form: its bar, then (unless folded) the view switch,
 * the lit stage and its caption. Once it scrolls away the bar floats over the
 * top of the page ([ProfilesPreviewBar]) and brings it back.
 */
@Composable
fun DesignerPreview(content: @Composable ColumnScope.() -> Unit) {
    val host = LocalAnchors.current
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (host != null) host.positions[PREVIEW_KEY] = coords
                // bounds in the window are clipped to the page's viewport: little of it left means it is away
                val shown = coords.boundsInWindow().height
                DesignerState.away = !DesignerState.folded && shown < coords.size.height * 0.25f
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewBar(false, { DesignerState.fold(!DesignerState.folded) })
        if (!DesignerState.folded) content()
    }
}

/** Over the top of the page while the preview is out of view: the bar again, which scrolls back to it. */
@Composable
fun BoxScope.ProfilesPreviewBar(host: AnchorHost?) {
    if (!DesignerState.away || DesignerState.folded) return
    val c = Nx.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to c.bgRaised, 0.7f to c.bgRaised, 1f to Color.Transparent))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
    ) {
        PreviewBar(true, { if (host != null) scrollToMark(host, PREVIEW_KEY, scope, center = false, reduced = reduced) })
    }
}

/**
 * The account's badges in the form (BadgeEditor.tsx), in the two groups a
 * profile draws them in: the tags (staff rank, plan) and, under them, the
 * icon badges. Each moves within its group and hides, saved as it changes.
 * A staff rank never hides, so staff are always known as staff.
 */
@Composable
fun BadgeEditor(userId: String, flags: Long, plan: String) {
    val c = Nx.colors
    val list by fish.nexa.data.Badges.mine.collectAsState()
    val failed by fish.nexa.data.Badges.mineFailed.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { fish.nexa.data.Badges.loadMine() }
    fun commit(next: List<fish.nexa.data.Badges.Badge>) = scope.launch {
        try {
            fish.nexa.data.Badges.save(next)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fish.nexa.Nexa.ui.toast(fish.nexa.ui.describeError(e, t("settings.privacy.couldNotSave")), "error")
        }
    }
    val all = list
    when {
        all == null && failed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(t("badges.couldNotLoad"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.weight(1f))
            NxButton(t("invite.tryAgain"), { scope.launch { fish.nexa.data.Badges.loadMine() } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        }
        all == null -> FieldHint(t("chat.pins.loading"))
        all.isEmpty() -> FieldHint(t("badges.none"))
        else -> {
            val (tags, icons) = all.partition { it.id == "staff" || it.id == "plan" }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((tagGroup, group) in listOf(true to tags, false to icons)) {
                    if (group.isEmpty()) continue
                    fun set(next: List<fish.nexa.data.Badges.Badge>) = commit(if (tagGroup) next + icons else tags + next)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt(if (tagGroup) t("badges.tags") else t("badges.icons"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted)
                            if (!tagGroup) Txt(t("badges.shownCount", "shown" to group.count { !it.hidden }, "total" to group.size), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted.copy(alpha = 0.8f))
                        }
                        group.forEachIndexed { i, b ->
                            val name = fish.nexa.data.Badges.name(b, userId, flags, plan)
                            val locked = b.id == "staff"
                            val why = when {
                                b.hidden -> t("badges.hiddenWhy")
                                locked -> t("badges.staffAlways")
                                b.id == "plan" -> t("badges.planWhy")
                                else -> fish.nexa.data.Badges.look(b)?.tip ?: ""
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clip(Radii.Sm)
                                    .background(c.bgInput)
                                    .ring(Radii.Sm, c.borderSubtle)
                                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.alpha(if (b.hidden) 0.4f else 1f)) {
                                    fish.nexa.ui.profile.BadgePill(b, userId, flags, plan, still = true)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    if (!tagGroup) Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(650)), color = if (b.hidden) c.textMuted else c.textStrong)
                                    if (why.isNotEmpty()) Txt(why, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)), color = c.textMuted)
                                }
                                IconTool("ArrowUp", t("badges.moveUp", "name" to name), enabled = i > 0) {
                                    set(group.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) })
                                }
                                IconTool("ArrowDown", t("badges.moveDown", "name" to name), enabled = i < group.size - 1) {
                                    set(group.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) })
                                }
                                IconTool(if (b.hidden) "EyeOff" else "Eye", if (locked) t("badges.staffAlways") else if (b.hidden) t("badges.show", "name" to name) else t("badges.hide", "name" to name), enabled = !locked) {
                                    set(group.map { if (it.id == b.id) it.copy(hidden = !it.hidden) else it })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
