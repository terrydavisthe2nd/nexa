package fish.nexa.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import fish.nexa.ui.server.noSwipeWhileTouched
import fish.nexa.ui.settings.keptAboveSaveBar
import androidx.compose.ui.unit.offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fish.nexa.ui.Icon
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.dangerGradient
import fish.nexa.ui.theme.positiveGradient
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.ringGlow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset

/**
 * Lays a panel out just above the top of where it is put, [gap] clear of it,
 * taking no room there: what it floats over (the message box, under its
 * suggestions or a command's options) keeps its place and size.
 */
fun Modifier.floatAbove(gap: Dp): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    layout(p.width, 0) { p.place(0, -p.height - gap.roundToPx()) }
}

/** Plain text in the theme's colour and the body style unless told otherwise. */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Nx.type.body,
    color: Color = Nx.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    weight: Int? = null,
    align: androidx.compose.ui.text.style.TextAlign? = null,
    softWrap: Boolean = true,
) {
    BasicText(
        text = text,
        // Text ink (especially combining marks) must stay in its measured box.
        modifier = modifier.clipToBounds(),
        style = style.copy(color = color).let { s -> if (weight != null) s.copy(fontWeight = FontWeight(weight)) else s }.let { s -> if (align != null) s.copy(textAlign = align) else s },
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/**
 * Marks a finger that goes down here as this sideways scroller's while [active]
 * says it has more to show, so the page's drawer swipe leaves its drags alone
 * (TouchClaims.sideways; the web's AppShell scrollsSideways).
 */
fun Modifier.claimsSideways(active: () -> Boolean): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fish.nexa.ui.chat.TouchClaims.sideways = active()
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.none { it.pressed }) break
            }
        } finally {
            fish.nexa.ui.chat.TouchClaims.sideways = false
        }
    }
}

/** horizontalScroll, with the finger claimed while there is more to scroll to. */
@Composable
fun Modifier.sidewaysScroll(): Modifier {
    val state = rememberScrollState()
    return this.claimsSideways { state.maxValue > 0 }.horizontalScroll(state)
}

/**
 * A finger-sized target around small words without moving the line they sit
 * on (the web's padding with a negative margin): the padding inside it is
 * pressable, but the layout only counts the words. Put it before the padding.
 */
fun Modifier.touchOutset(horizontal: Dp, vertical: Dp): Modifier = this.layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val p = measurable.measure(constraints.offset(2 * h, 2 * v))
    layout(maxOf(0, p.width - 2 * h), maxOf(0, p.height - 2 * v)) { p.place(-h, -v) }
}

/**
 * The press feel: a soft tint where the finger is and, for tiles and
 * buttons, the slight sink the web gives `:active` (translateY 1px, 0.985).
 */
fun Modifier.pressable(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this.then(
    if (onLongClick == null) Modifier.clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    else Modifier.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick),
)

@Composable
fun Modifier.sink(pressed: Boolean): Modifier {
    val reduced = Nx.motion.reduced
    val s by animateFloatAsState(if (pressed && !reduced) 0.985f else 1f, tween(90), label = "sink")
    val dy by animateFloatAsState(if (pressed && !reduced) 1f else 0f, tween(90), label = "sinkY")
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
        translationY = dy * density
    }
}

enum class ButtonKind { PRIMARY, SECONDARY, DANGER, POSITIVE, GHOST, LINK }
/** TOUCH: a small button's words at a finger's height, as the web's buttons grow on a touch screen (min-height 40). */
enum class ButtonSize(val height: Dp, val pad: Dp) { SM(32.dp, 14.dp), TOUCH(40.dp, 14.dp), MD(38.dp, 18.dp), LG(46.dp, 24.dp) }

/** Button.module.css: fills that catch the light from above, a lit top edge, a shadow in their own colour; pressed, they sink. */
@Composable
fun NxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    size: ButtonSize = ButtonSize.MD,
    icon: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    block: Boolean = false,
    /** A link set in a line of text: no padding of its own, so it lines up with the words. */
    inline: Boolean = false,
    /** After the words: a count in its own pill (`.btnCount`). */
    trailing: (@Composable () -> Unit)? = null,
    /** Before the words in place of `icon`: a picture of its own, like a chosen emoji. */
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Nx.colors
    val type = Nx.type
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = if (size == ButtonSize.LG) Radii.Md else Radii.Sm
    val fg = when (kind) {
        ButtonKind.PRIMARY -> c.onBrand
        ButtonKind.DANGER -> c.onDanger
        ButtonKind.POSITIVE -> c.onPositive
        ButtonKind.SECONDARY -> if (pressed) c.textStrong else c.text
        ButtonKind.GHOST -> if (pressed) c.text else c.textSubtle
        ButtonKind.LINK -> c.link
    }
    val style = when (size) {
        ButtonSize.SM, ButtonSize.TOUCH -> type.buttonSmall
        ButtonSize.MD -> type.button
        ButtonSize.LG -> type.buttonLarge
    }
    val base = modifier
        .then(if (block) Modifier.fillMaxWidth() else Modifier)
        .alpha(if (enabled) 1f else 0.5f)
        .then(if (kind != ButtonKind.LINK) Modifier.sink(pressed && enabled && !loading) else Modifier)
    val filled = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.DANGER, ButtonKind.POSITIVE -> {
            val fill: Brush = when (kind) {
                ButtonKind.PRIMARY -> if (pressed) SolidColor(c.brandActive) else brandGradient(c)
                ButtonKind.DANGER -> if (pressed) SolidColor(c.dangerHover) else dangerGradient(c)
                else -> if (pressed) SolidColor(c.positiveHover) else positiveGradient(c)
            }
            val glow = when (kind) {
                ButtonKind.PRIMARY -> c.brand
                ButtonKind.DANGER -> c.danger
                else -> c.positive
            }
            base
                .dropShadow(shape, Shadow(radius = 2.dp, color = c.shadeAt(0.45f), offset = DpOffset(0.dp, 1.dp)))
                .dropShadow(shape, Shadow(radius = 18.dp, color = glow.copy(alpha = 0.8f), spread = (-8).dp, offset = DpOffset(0.dp, 6.dp)))
                .background(fill, shape)
                .topEdge(shape, Color.White.copy(alpha = if (pressed) 0f else 0.22f))
        }
        ButtonKind.SECONDARY -> base
            .shadowLow(shape, c)
            .ring(shape, if (pressed) c.borderStrong else c.border)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .then(if (pressed) Modifier.background(c.bgActive, shape) else Modifier)
            .topEdge(shape, c.edgeLight)
        ButtonKind.GHOST -> base.background(if (pressed) c.bgHover else Color.Transparent, shape)
        ButtonKind.LINK -> base
    }
    Box(
        modifier = filled
            .clip(shape)
            .pressable(interaction, enabled = enabled && !loading, onClick = onClick)
            .then(if (kind == ButtonKind.LINK) Modifier.heightIn(min = 40.dp).padding(horizontal = if (inline) 0.dp else 8.dp) else Modifier.height(size.height).padding(horizontal = size.pad))
            .semantics { if (loading) stateDescription = t("chat.pins.loading") },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.alpha(if (loading) 0f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leading != null) leading()
            else if (icon != null) Icon(icon, fg, size = if (size == ButtonSize.LG) 20.dp else if (size == ButtonSize.TOUCH) 15.dp else 18.dp)
            if (text.isNotEmpty()) Txt(text, style = if (kind == ButtonKind.LINK) style.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(500)) else style, color = fg, maxLines = 1)
            trailing?.invoke()
        }
        if (loading) LoadingDots(fg)
    }
}

/** Three dots breathing in turn (Button's `.dots`). */
@Composable
fun LoadingDots(color: Color, dot: Dp = 6.dp) {
    val inf = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, delayMillis = 0), RepeatMode.Restart), label = "d$i")
            val phase = ((p * 1200 - i * 150 + 1200) % 1200) / 1200f
            val on = if (phase < 0.4f) phase / 0.4f else if (phase < 0.8f) 1f - (phase - 0.4f) / 0.4f else 0f
            Box(Modifier.size(dot).scale(0.8f + 0.2f * on).alpha(0.3f + 0.7f * on).background(color, CircleShape))
        }
    }
}

/** A round icon button (the header's, the composer's): 40 dp, the tint on press. */
@Composable
fun IconButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Nx.colors.icon,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) c.bgActive else if (active) c.bgSelected else Color.Transparent)
            .pressable(interaction, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (active) c.textStrong else tint, size = iconSize)
    }
}

/**
 * TextInput.module.css: a label (Title Case, 13/650), a well sunk into the
 * panel (42 high, the shadow inside along the top), the accent's light on its
 * edge and a soft halo while in use, an eye inside a password's end.
 */
@Composable
fun NxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    required: Boolean = false,
    placeholder: String? = null,
    error: String? = null,
    hint: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onIme: (() -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    autoCorrect: Boolean = false,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** Drawn inside the box before the words (`.iconInput`): an icon 10dp in, the words 32dp in. */
    leading: (@Composable () -> Unit)? = null,
    mono: Boolean = false,
    /** The monospace text's size; AutoMod's lists of words are a little smaller, as the web's are. */
    monoSize: Float = 15f,
    labelContent: (@Composable () -> Unit)? = null,
    hintContent: (@Composable () -> Unit)? = null,
) {
    val c = Nx.colors
    val type = Nx.type
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val shape = Radii.Sm
    val edge by animateColorAsState(
        when {
            error != null -> c.danger
            focused -> c.focusRing.copy(alpha = 0.75f)
            else -> c.borderSubtle
        },
        tween(Nx.motion.fast), label = "edge",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (labelContent != null) labelContent()
        else if (label != null) {
            BasicText(
                buildAnnotatedString {
                    append(label)
                    if (required) withStyle(SpanStyle(color = c.textDanger)) { append(" *") }
                },
                style = type.label.copy(color = if (error != null) c.textDanger else c.textSubtle),
                modifier = Modifier.clipToBounds(),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .keptAboveSaveBar(value, focused)
                .then(if (focused && error == null) Modifier.ringGlow(shape, c) else Modifier)
                .sunken(shape, c)
                .border(1.dp, edge, shape)
                .defaultMinSize(minHeight = 42.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                interactionSource = interaction,
                textStyle = (if (mono) type.code.copy(fontSize = type.sp(monoSize)) else type.input).copy(color = c.text),
                cursorBrush = SolidColor(c.focusRing),
                visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password && keyboardType != KeyboardType.NumberPassword) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = autoCorrect,
                    capitalization = if (capitalize) androidx.compose.ui.text.input.KeyboardCapitalization.Sentences else androidx.compose.ui.text.input.KeyboardCapitalization.None,
                ),
                keyboardActions = if (onIme != null) KeyboardActions(onAny = { onIme() }) else KeyboardActions.Default,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (leading != null) 32.dp else 12.dp, end = if (password || trailing != null) 46.dp else 12.dp, top = if (singleLine) 0.dp else 10.dp, bottom = if (singleLine) 0.dp else 10.dp)
                    .semantics { if (label != null) contentDescription = label },
                decorationBox = { inner ->
                    // a box of several lines starts at the top, its placeholder wrapping as the web's textarea does
                    Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart, modifier = if (singleLine) Modifier.defaultMinSize(minHeight = 42.dp) else Modifier) {
                        if (value.isEmpty() && placeholder != null) Txt(placeholder, style = if (mono) type.code.copy(fontSize = type.sp(monoSize)) else type.input, color = c.textPlaceholder, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                        inner()
                    }
                },
            )
            if (password) {
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(
                        icon = if (reveal) "EyeOff" else "Eye",
                        label = if (reveal) t("common.hidePassword") else t("common.showPassword"),
                        onClick = { reveal = !reveal },
                        tint = c.textMuted,
                        size = 44.dp,
                        iconSize = 20.dp,
                    )
                }
            } else if (trailing != null) {
                Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
            }
            if (leading != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) { leading() }
        }
        if (error != null) Txt(error, style = type.hint.copy(fontWeight = FontWeight(500)), color = c.textDanger)
        else if (hintContent != null) hintContent()
        else if (hint != null) Txt(hint, style = type.hint, color = c.textMuted)
    }
}

/** A checkbox row: an 18 dp box in the accent when ticked, the words beside it. */
@Composable
fun NxCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressable(interaction, enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .heightIn(min = 40.dp)
            .semantics { stateDescription = if (checked) "checked" else "unchecked" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TickBox(checked, Modifier.padding(top = 2.dp))
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

/** A checkbox's box: 20 dp, filled with the accent and ticked when checked. */
@Composable
fun TickBox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .size(20.dp)
            .then(if (checked) Modifier.background(brandGradient(c), shape) else Modifier.sunken(shape, c).border(1.5.dp, c.border, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon("Check", c.onBrand, size = 14.dp, stroke = 3f)
    }
}

/** Toggle.module.css: a pill track that fills with the accent, its knob sliding over. */
@Composable
fun NxToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String? = null) {
    val c = Nx.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val x by animateFloatAsState(if (checked) 1f else 0f, tween(Nx.motion.normal, easing = Nx.motion.easeOut), label = "knob")
    val track = RoundedCornerShape(50)
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (checked) Modifier.background(brandGradient(c), track) else Modifier.sunken(track, c, c.bgActive))
            .pressable(interaction, enabled = enabled, role = Role.Switch) {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                onChange(!checked)
            }
            .semantics {
                stateDescription = if (checked) "on" else "off"
                if (label != null) contentDescription = label
            },
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .graphicsLayer { translationX = x * 18.dp.toPx() }
                .size(20.dp)
                .dropShadow(CircleShape, Shadow(radius = 3.dp, color = c.shadeAt(0.5f), offset = DpOffset(0.dp, 1.dp)))
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
fun Spinner(color: Color = Nx.colors.textMuted, size: Dp = 22.dp) {
    val inf = rememberInfiniteTransition(label = "spin")
    val r by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = androidx.compose.animation.core.LinearEasing)), label = "r")
    val reduced = Nx.motion.reduced
    Box(Modifier.size(size).graphicsLayer { rotationZ = if (reduced) 0f else r }) {
        Icon("LoaderCircle", color, size = size)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

val NoPadding = PaddingValues(0.dp)

/** A range slider: a sunken track, the accent's lit fill, a white knob. */
@Composable
fun NxSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    label: String? = null,
) {
    val c = Nx.colors
    var width by remember { mutableStateOf(1f) }
    val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    fun at(x: Float) = (range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width.toFloat() }
            // its sideways drags are its own, not a settings page's swipe back
            .noSwipeWhileTouched()
            .pointerInputDrag(onDrag = { x -> onChange(at(x)) }, onEnd = { onDone?.invoke() })
            .semantics {
                if (label != null) contentDescription = label
                stateDescription = value.toInt().toString()
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val track = RoundedCornerShape(50)
        Box(Modifier.fillMaxWidth().height(6.dp).sunken(track, c, c.bgActive))
        Box(Modifier.fillMaxWidth(frac).height(6.dp).background(brandGradient(c), track))
        Box(
            Modifier
                .graphicsLayer { translationX = frac * (width - 20.dp.toPx()) }
                .size(20.dp)
                .dropShadow(CircleShape, Shadow(radius = 3.dp, color = c.shadeAt(0.5f), offset = DpOffset(0.dp, 1.dp)))
                .background(Color.White, CircleShape),
        )
    }
}

private fun Modifier.pointerInputDrag(onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectTapGestures { pos -> onDrag(pos.x); onEnd() }
    }.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { pos -> onDrag(pos.x) },
            onDragEnd = { onEnd() },
            onHorizontalDrag = { change: PointerInputChange, _: Float -> change.consume(); onDrag(change.position.x) },
        )
    },
)

/** A count pill: the danger gradient, 20 high, "99+" past 99. `cut` rings it in the colour behind it. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, cut: Color? = null) {
    val c = Nx.colors
    if (count <= 0) return
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .then(if (cut != null) Modifier.ring(shape, cut, 3.dp) else Modifier)
            .dropShadow(shape, Shadow(radius = 6.dp, color = c.danger.copy(alpha = if (cut != null) 0.5f else 0.4f), offset = DpOffset(0.dp, 2.dp)))
            .background(dangerGradient(c), shape)
            .defaultMinSize(minWidth = 20.dp)
            .height(20.dp)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(if (count > 99) "99+" else count.toString(), style = Nx.type.hint.copy(fontWeight = FontWeight(700), fontSize = Nx.type.sp(12f), fontFeatureSettings = "tnum"), color = c.onDanger, maxLines = 1)
    }
}
