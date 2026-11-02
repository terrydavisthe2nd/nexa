package fish.nexa.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.data.NameStyle
import fish.nexa.net.MeUser
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.chat.RoleLook
import fish.nexa.ui.chat.RoleName
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.server.hexOf
import fish.nexa.ui.server.parseHex
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.rgbColor
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.topEdge

/**
 * Settings, Profiles, Name Style (the web's NameStyleEditor in a phone's
 * sheet): the name large on its plate, then Surprise Me and Reset, the fonts,
 * the effects and the colours. Every choice goes straight to the profile
 * being edited ([value] and [onChange]) and waits for Save Changes with the
 * rest, as on the web.
 */
/** The name style being designed, as the editor's state: plain is kept as none. */
internal fun nameStyleState(value: () -> NameStyle?, onChange: (NameStyle?) -> Unit): MutableState<NameStyle> = object : MutableState<NameStyle> {
    override var value: NameStyle
        get() = value() ?: NameStyle.PLAIN
        set(v) = onChange(v.takeIf { !it.isPlain })
    override fun component1() = this.value
    override fun component2(): (NameStyle) -> Unit = { this.value = it }
}

internal fun openNameStyleSheet(me: () -> MeUser?, name: () -> String, value: () -> NameStyle?, onChange: (NameStyle?) -> Unit) {
    val state = nameStyleState(value, onChange)
    openSheet("designer-name-style", title = t("nameStyle.title")) {
        val m = me() ?: return@openSheet
        NameStyleBody(m, name(), state)
    }
}

@Composable
internal fun NameStyleBody(me: MeUser, name: String, state: MutableState<NameStyle>) {
    val c = Nx.colors
    val style by state
    val look = RoleLook(color = null, own = style)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // the name, large, on a plate sunk into the sheet
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clip(Radii.Lg)
                .background(c.bgBase)
                .ring(Radii.Lg, c.borderSubtle)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            RoleName(name, look, Nx.type.name.copy(fontSize = Nx.type.sp(26f), lineHeight = Nx.type.sp(32f), fontWeight = FontWeight(700), textAlign = TextAlign.Center), c.textStrong)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxButton(t("nameStyle.surprise"), { state.value = NameStyle.surprise(paid = fish.nexa.data.Plans.myLimits().nameEffects) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Dices")
            NxButton(t("nameStyle.reset"), { state.value = NameStyle.PLAIN }, kind = ButtonKind.GHOST, size = ButtonSize.SM, icon = "RotateCcw", enabled = !style.isPlain)
        }

        Section(t("nameStyle.font")) {
            Grid(NameStyle.FONTS, 4) { f ->
                val label = if (f.id.isEmpty()) t("nameStyle.defaultFont") else f.label
                Tile(style.font == f.id, label, Modifier.height(66.dp), { state.value = style.copy(font = f.id) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RoleName("Ag", RoleLook(color = null, own = NameStyle(f.id, "solid", emptyList())), Nx.type.name.copy(fontSize = Nx.type.sp(24f), lineHeight = Nx.type.sp(28f), fontWeight = FontWeight(400)), c.textStrong)
                        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(11f)), color = c.textMuted, maxLines = 1)
                    }
                }
            }
        }

        // the effects that come with a plan are shown to everyone, locked without one
        val me by Nexa.users.me.collectAsState()
        val paid = fish.nexa.data.Plans.limitsOf(me?.plan).nameEffects
        Section(t("nameStyle.effect")) {
            Grid(NameStyle.EFFECTS, 4) { e ->
                val sample = RoleLook(color = null, own = NameStyle("", e.id, if (e.id == "solid") emptyList() else e.start, animated = style.animated))
                val locked = e.paid && !paid
                Tile(style.effect == e.id, t("nameStyle.effects.${e.id}"), Modifier.height(52.dp), {
                    if (locked) fish.nexa.data.Plans.offer(fish.nexa.data.Plans.Offer.Effect) else state.value = NameStyle.withEffect(style, e.id)
                }) {
                    Box(Modifier.alpha(if (locked) 0.6f else 1f)) {
                        RoleName(t("nameStyle.effects.${e.id}"), sample, Nx.type.name.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(700)), c.textStrong)
                    }
                    if (e.paid) Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 2.dp).semantics { contentDescription = t("support.effects.withPlan") }) {
                        Icon(if (paid) "Heart" else "Lock", c.textSupporter, size = 11.dp, filled = paid)
                    }
                }
            }
        }

        if (style.effect != "solid") Section(t("nameStyle.motion")) {
            Grid(listOf(false, true), 2) { animated ->
                val label = t(if (animated) "nameStyle.animated" else "nameStyle.static")
                Tile(style.isAnimated == animated, label, Modifier.height(48.dp), { state.value = style.copy(animated = animated) }) {
                    Txt(label, style = Nx.type.body, color = c.textStrong)
                }
            }
        }

        val spec = NameStyle.effect(style.effect)
        Section(if (spec.max > 1) t("nameStyle.colours") else t("nameStyle.colour")) {
            if (spec.max > 1) Stops(style, spec, state) else Singles(style, state)
        }
        Txt(t("nameStyle.note"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.9f)), color = c.textMuted)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(title, style = Nx.type.heading.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(700)), color = Nx.colors.textStrong, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Items in rows of [cols], the last row's gaps left empty so every tile is the same width. */
@Composable
private fun <T> Grid(items: List<T>, cols: Int, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (it in row) Box(Modifier.weight(1f)) { cell(it) }
            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A choice drawn in what it gives; the chosen one takes the accent's ring. */
@Composable
private fun Tile(selected: Boolean, label: String, modifier: Modifier, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val c = Nx.colors
    Box(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.ring(Radii.Md, c.brand, 2.dp) else Modifier.ring(Radii.Md, c.borderSubtle))
            .clip(Radii.Md)
            .background(c.bgRaised)
            .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** One colour: the usual one (Solid only), your own, and the swatches. */
@Composable
private fun Singles(style: NameStyle, state: MutableState<NameStyle>) {
    val c = Nx.colors
    val chosen = style.colors.firstOrNull()
    val own = chosen != null && chosen !in NameStyle.SWATCHES
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (style.effect == "solid") Swatch(c.bgRaised, chosen == null, t("nameStyle.usualColour"), { state.value = style.copy(colors = emptyList()) }) {
            Txt("Aa", style = Nx.type.body.copy(fontWeight = FontWeight(700)), color = c.textStrong)
        }
        Swatch(if (own) rgbColor(chosen!!) else c.bgRaised, own, t("nameStyle.pickColour"), {
            openColourPicker(t("nameStyle.pickColour"), hexOf(chosen ?: 0x8a6cf2L), alpha = false) { hex -> parseHex(hex)?.let { state.value = state.value.copy(colors = listOf(it)) } }
        }) { Icon("Pipette", if (own) Color.White else c.icon, size = 16.dp) }
        for (col in NameStyle.SWATCHES) Swatch(rgbColor(col), chosen == col, hexOf(col), { state.value = style.copy(colors = listOf(col)) })
    }
}

/** Many colours: the run of them with a dropper on each, adding and taking away, then ready-made sets. */
@Composable
private fun Stops(style: NameStyle, spec: NameStyle.Effect, state: MutableState<NameStyle>) {
    val c = Nx.colors
    val cs = style.colors.map { rgbColor(it) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(Radii.Md)
                .background(Brush.horizontalGradient(if (cs.size > 1) cs else cs + cs))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            style.colors.forEachIndexed { i, col ->
                Box {
                    Box(
                        Modifier
                            .size(44.dp)
                            .ring(CircleShape, Color.White.copy(alpha = 0.85f), 2.dp)
                            .clip(CircleShape)
                            .background(rgbColor(col))
                            .pressable(remember { MutableInteractionSource() }, role = Role.Button) {
                                openColourPicker(t("nameStyle.stopColour", "index" to i + 1), hexOf(col), alpha = false) { hex ->
                                    parseHex(hex)?.let { v -> state.value = state.value.copy(colors = state.value.colors.mapIndexed { j, x -> if (j == i) v else x }) }
                                }
                            }
                            .semantics { contentDescription = t("nameStyle.stopColour", "index" to i + 1) },
                        contentAlignment = Alignment.Center,
                    ) { Icon("Pipette", Color.White, size = 15.dp) }
                    if (style.colors.size > spec.min) Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(c.bgFloating)
                            .pressable(remember { MutableInteractionSource() }, role = Role.Button) {
                                state.value = state.value.copy(colors = state.value.colors.filterIndexed { j, _ -> j != i })
                            }
                            .semantics { contentDescription = t("nameStyle.removeColour", "index" to i + 1) },
                        contentAlignment = Alignment.Center,
                    ) { Icon("Minus", c.textStrong, size = 11.dp) }
                }
            }
            if (style.colors.size < spec.max) Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.bgFloating)
                    .pressable(remember { MutableInteractionSource() }, role = Role.Button) {
                        state.value = state.value.copy(colors = state.value.colors + (state.value.colors.lastOrNull() ?: 0xFFFFFFL))
                    }
                    .semantics { contentDescription = t("nameStyle.addColour") },
                contentAlignment = Alignment.Center,
            ) { Icon("Plus", c.textStrong, size = 15.dp) }
        }
        val sets = NameStyle.PALETTES[style.effect] ?: emptyList()
        Grid(sets, 3) { p ->
            val on = p.take(spec.max) == style.colors
            val pc = p.map { rgbColor(it) }
            val brush = if (style.effect == "candy") Brush.horizontalGradient(*pc.flatMapIndexed { k, col -> listOf(k.toFloat() / pc.size to col, (k + 1f) / pc.size to col) }.toTypedArray())
            else Brush.horizontalGradient(pc)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .then(if (on) Modifier.ring(Radii.Md, c.textStrong, 2.dp) else Modifier)
                    .shadowLow(Radii.Md, c)
                    .clip(Radii.Md)
                    .background(brush)
                    .topEdge(Radii.Md, Color.White.copy(alpha = 0.22f))
                    .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton) { state.value = state.value.copy(colors = p.take(spec.max)) }
                    .semantics {
                        selected = on
                        contentDescription = t("nameStyle.paletteN", "index" to sets.indexOf(p) + 1)
                    },
                contentAlignment = Alignment.Center,
            ) { if (on) Icon("Check", Color.White, size = 16.dp) }
        }
    }
}
