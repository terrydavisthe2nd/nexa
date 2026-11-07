package fish.nexa.ui.layers

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Everything that opens over the page (dialogs, menus, pickers, panels), as
 * a stack: Back closes only the one on top, as Escape does on the web
 * (backLayers.ts). A layer may refuse (unsaved changes).
 */
class Layer(
    val key: String,
    /** Profile-type dialogs replace each other instead of stacking. */
    val replaces: String? = null,
    val canClose: () -> Boolean = { true },
    val content: @Composable (close: () -> Unit) -> Unit,
) {
    /** Set by the host: animates the layer out, then removes it. */
    var dismiss: (() -> Unit)? = null

    /** How far this layer dims and blurs what is under it, 0 to 1: a sheet's or dialog's scrim as it comes and goes. */
    var dim by mutableFloatStateOf(0f)
}

object Layers {
    val stack = mutableStateListOf<Layer>()

    fun open(layer: Layer) {
        if (layer.replaces != null) stack.removeAll { it.replaces == layer.replaces }
        stack.removeAll { it.key == layer.key }
        stack.add(layer)
    }

    fun remove(layer: Layer) {
        stack.remove(layer)
    }

    fun close(key: String) {
        stack.lastOrNull { it.key == key }?.let { l -> l.dismiss?.invoke() ?: remove(l) }
    }

    /** Back: the topmost layer goes (if it agrees). True when something was open. */
    fun closeTop(): Boolean {
        val top = stack.lastOrNull() ?: return false
        if (!top.canClose()) return true
        top.dismiss?.invoke() ?: remove(top)
        return true
    }

    val isEmpty get() = stack.isEmpty()

    fun clear() = stack.clear()

    /** How far the sheets and dialogs over the page dim it (the most of any of them). */
    val dim: Float get() = stack.maxOfOrNull { it.dim } ?: 0f
}

/** Modal.module.css: what is behind a dialog or sheet blurs by 6px as its scrim shows. */
val SCRIM_BLUR = 6.dp

/**
 * Blurs this by [radius], as the web's `backdrop-filter` blurs what is behind an
 * overlay. Read while drawing, so a scrim fading in only redraws. Android 12 and
 * up; before that it only dims, as it always did.
 *
 * Offscreen keeps the blurred picture until what is in it changes. Without it the
 * whole page was blurred again on every frame anything anywhere on screen moved,
 * by far the most this app could ask of a phone.
 */
fun Modifier.blurredBy(radius: () -> Dp): Modifier = graphicsLayer {
    val r = radius().toPx()
    val blur = Build.VERSION.SDK_INT >= 31 && r >= 0.5f
    renderEffect = if (blur) BlurEffect(r, r, TileMode.Clamp) else null
    compositingStrategy = if (blur) androidx.compose.ui.graphics.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.CompositingStrategy.Auto
}

/** Draws the layer stack; mounted once, over the shell. */
@Composable
fun LayerHost() {
    val stack = Layers.stack.toList()
    for ((i, layer) in stack.withIndex()) {
        androidx.compose.runtime.key(layer.key) {
            // a sheet or dialog over this one blurs it too, as the web's overlay blurs everything behind it
            Box(Modifier.blurredBy { SCRIM_BLUR * (stack.subList(i + 1, stack.size).maxOfOrNull { it.dim } ?: 0f) }) {
                layer.content { layer.dismiss?.invoke() ?: Layers.remove(layer) }
            }
        }
    }
}

/**
 * Modal.module.css on a phone: a bottom sheet over a blurred scrim, top
 * corners 20, a grip, a title (21/680), a scrolling body and an optional
 * footer, entering with sheetUp (280 ms from 40 px down). Its physics are
 * sheetDrag.ts: drag down from the top 44 px (or from a list scrolled to its
 * top) to close past a third of its height or with a flick; pulled up it
 * resists.
 */
@Composable
fun Sheet(
    layer: Layer,
    title: String? = null,
    footer: (@Composable () -> Unit)? = null,
    maxWidth: Dp = 640.dp,
    closeButton: Boolean = true,
    bare: Boolean = false,
    scroll: Boolean = true,
    padding: Dp = 16.dp,
    /** The sheet's own colour, for content that brings its own (a profile in its colours). */
    fill: Color? = null,
    /** Whether what is behind blurs as well as dims; a menu only dims, as on the web (ContextMenu.module.css). */
    blur: Boolean = true,
    /** A line over the title: who asks, on an app's form. */
    over: (@Composable () -> Unit)? = null,
    /** Drawn in place of [title], for a title that changes or carries more than words ([SheetTitle] draws the words). */
    heading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(if (reduced) 0f else 40f) }
    val shown = remember { Animatable(if (reduced) 1f else 0.4f) }
    val scrim = remember { Animatable(if (reduced) 1f else 0f) }
    if (blur) LaunchedEffect(Unit) { snapshotFlow { scrim.value }.collect { layer.dim = it } }
    var height by remember { mutableStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    fun dismiss() {
        if (closing) return
        if (!layer.canClose()) {
            scope.launch { offset.animateTo(0f, tween(240, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) }
            return
        }
        closing = true
        scope.launch {
            if (!reduced) {
                launch { scrim.animateTo(0f, tween(200)) }
                offset.animateTo(height + 24f * density, tween(200))
            }
            Layers.remove(layer)
        }
    }
    layer.dismiss = ::dismiss

    LaunchedEffect(Unit) {
        if (!reduced) {
            launch { scrim.animateTo(1f, tween(180)) }
            launch { shown.animateTo(1f, tween(280, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) }
            offset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        }
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        var velocity = 0f
        var lastY = 0f
        var lastT = 0L
        detectVerticalDragGestures(
            onDragStart = {
                velocity = 0f
                lastT = System.nanoTime()
            },
            onVerticalDrag = { change, dy ->
                change.consume()
                val now = System.nanoTime()
                val dt = ((now - lastT) / 1_000_000f).coerceAtLeast(1f)
                velocity = 0.7f * velocity + 0.3f * (dy / dt)
                lastT = now
                lastY += dy
                val next = offset.value + dy
                scope.launch { offset.snapTo(if (next < 0) -sqrt(-next) * 2.5f else next) }
                scope.launch { scrim.snapTo((1f - (offset.value.coerceAtLeast(0f) / height) * 0.9f).coerceAtLeast(0.15f)) }
            },
            onDragEnd = {
                val o = offset.value
                if (o > minOf(160f * density, height / 3) || (velocity / density > 0.55f && o > 24f * density)) dismiss()
                else scope.launch {
                    launch { scrim.animateTo(1f, tween(240)) }
                    offset.animateTo(0f, tween(240, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        // the scrim: a tap closes, the page behind takes no touches
        Box(
            Modifier
                .fillMaxSize()
                .alpha(scrim.value)
                .background(c.bgOverlay)
                .pressable(remember { MutableInteractionSource() }, role = Role.Button) { dismiss() },
        )
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
            val maxH = maxHeight * 0.92f
            val shape = RoundedCornerShape(topStart = Radii.lg, topEnd = Radii.lg)
            Box(
                Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .graphicsLayer {
                        translationY = offset.value
                        alpha = shown.value
                    }
                    .onSizeChanged { height = it.height.toFloat() }
                    .shadowHigh(shape, c)
                    .ring(shape, c.panelEdge)
                    .background(fill ?: c.bgFloating, shape)
                    .topEdge(shape, c.edgeLight)
                    .clip(shape),
            ) {
                Column {
                    // grip and title: the handle the sheet is dragged by
                    if (!bare) Column(dragModifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.size(36.dp, 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp)))
                        }
                        if (title != null || heading != null || closeButton) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (title != null || heading != null) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    over?.invoke()
                                    if (heading != null) heading() else if (title != null) SheetTitle(title)
                                } else Spacer(Modifier.weight(1f))
                                if (closeButton) {
                                    fish.nexa.ui.components.IconButton("X", t("chat.attachments.close"), { dismiss() }, tint = c.textMuted, iconSize = 20.dp)
                                }
                            }
                        }
                    }
                    val body = Modifier.weight(1f, fill = false).fillMaxWidth()
                    Column(
                        (if (scroll) body.verticalScroll(rememberScrollState()) else body)
                            .padding(start = padding, end = padding, top = padding, bottom = padding)
                            .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content,
                    )
                    if (footer != null) {
                        // a footer with nothing in it (a step without buttons, as the made app's token) takes no room
                        var filled by remember { mutableStateOf(true) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (filled) Modifier
                                        .background(fish.nexa.ui.theme.mixOklab(c.bgBase, c.bgFloating, 0.45f))
                                        .topEdge(RoundedCornerShape(0.dp), c.borderSubtle)
                                        .navigationBarsPadding()
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                    else Modifier.navigationBarsPadding(),
                                ),
                        ) { Box(Modifier.onSizeChanged { filled = it.height > 0 }) { footer() } }
                    }
                }
                // a bare sheet's content runs to its top edge (a banner), with the grip floating over it
                if (bare) Box(dragModifier.align(Alignment.TopCenter).size(160.dp, 30.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.padding(top = 8.dp).size(38.dp, 5.dp).background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

/** Opens a sheet as a layer. */
fun openSheet(
    key: String,
    title: String? = null,
    replaces: String? = null,
    canClose: () -> Boolean = { true },
    /** Without a title, a card that needs no close button of its own (it goes with a swipe or a tap outside). */
    closeButton: Boolean = true,
    footer: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** A line over the title: who asks, on an app's form. */
    over: (@Composable () -> Unit)? = null,
    /** Drawn in place of [title]: a title that changes, or carries a tag. */
    heading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    lateinit var layer: Layer
    layer = Layer(key, replaces, canClose) { close ->
        Sheet(layer, title = title, footer = footer?.let { f -> { f(close) } }, closeButton = closeButton, over = over, heading = heading) { content(close) }
    }
    Layers.open(layer)
}

/** A sheet's title as [Sheet] draws it, for a [Sheet] heading of one's own. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Txt(
        text,
        style = Nx.type.heading.copy(fontSize = Nx.type.sp(21f), fontWeight = FontWeight(680), lineHeight = Nx.type.sp(25.2f)),
        color = Nx.colors.textStrong,
        modifier = modifier.semantics { heading() },
        maxLines = 2,
    )
}

// ---------------------------------------------------------------------------
// Context menus (ContextMenu.module.css on a phone): text only, never icons.

sealed interface MenuItem {
    data class Action(
        val label: String,
        val danger: Boolean = false,
        val strong: Boolean = false,
        val disabled: Boolean = false,
        /** A radio dot (true/false) or, for toggles, a tick box. */
        val checked: Boolean? = null,
        val toggle: Boolean = false,
        val warn: Boolean = false,
        val separatorAfter: Boolean = false,
        /** A small picture before the label: whose item it is, such as an app's for its commands. */
        val picture: (@Composable () -> Unit)? = null,
        val personId: String? = null,
        val onClick: () -> Unit,
    ) : MenuItem

    data class Submenu(val label: String, val items: List<MenuItem>, val separatorAfter: Boolean = false) : MenuItem
    data class Heading(val label: String) : MenuItem
    data class Note(val text: String) : MenuItem
    data class Slider(val label: String, val value: Float, val range: ClosedFloatingPointRange<Float>, val unit: String = "", val onChange: (Float) -> Unit) : MenuItem
}

/**
 * A text-only menu as a sheet. [header] goes above the items (the message
 * menu's quick reactions); [onClosed] runs when the sheet has gone.
 */
fun openMenu(
    items: List<MenuItem>,
    title: String? = null,
    header: (@Composable (close: () -> Unit) -> Unit)? = null,
    onClosed: (() -> Unit)? = null,
    haptic: Boolean = true,
    titleWords: fish.nexa.ui.chat.PersonWords? = null,
) {
    if (items.isEmpty()) return
    lateinit var layer: Layer
    layer = Layer("menu") { close -> MenuSheet(layer, items, title, header, onClosed, haptic, titleWords, close) }
    Layers.open(layer)
}

@Composable
private fun MenuSheet(
    layer: Layer,
    items: List<MenuItem>,
    title: String?,
    header: (@Composable (close: () -> Unit) -> Unit)?,
    onClosed: (() -> Unit)?,
    haptic: Boolean,
    titleWords: fish.nexa.ui.chat.PersonWords?,
    close: () -> Unit,
) {
    val c = Nx.colors
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { if (haptic) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onClosed?.invoke() } }
    var stackOfMenus by remember { mutableStateOf(listOf<MenuItem.Submenu>()) }
    val current = stackOfMenus.lastOrNull()
    val shown = current?.items ?: items
    Sheet(layer, title = null, closeButton = false, padding = 8.dp, blur = false) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (current != null) {
                MenuRow(MenuItem.Action(t("contextMenu.back"), onClick = {}), back = true) { stackOfMenus = stackOfMenus.dropLast(1) }
                MenuHeading(current.label)
            } else {
                if (titleWords != null) fish.nexa.ui.chat.PersonWordsText(titleWords, Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), c.textMuted, Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp), maxLines = 1)
                else if (title != null) Txt(title, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp))
                header?.invoke(close)
            }
            for (item in shown) {
                when (item) {
                    is MenuItem.Action -> {
                        MenuRow(item) {
                            close()
                            item.onClick()
                        }
                        if (item.separatorAfter) MenuSeparator()
                    }
                    is MenuItem.Submenu -> {
                        MenuRow(MenuItem.Action(item.label, onClick = {}), chevron = true) { stackOfMenus = stackOfMenus + item }
                        if (item.separatorAfter) MenuSeparator()
                    }
                    is MenuItem.Heading -> MenuHeading(item.label)
                    is MenuItem.Note -> Txt(item.text, style = Nx.type.hint.copy(lineHeight = Nx.type.sp(16.2f)), color = c.textMuted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp))
                    is MenuItem.Slider -> MenuSlider(item)
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(label: String) =
    Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650)), color = Nx.colors.textMuted, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp), maxLines = 2)

@Composable
private fun MenuSeparator() = Box(Modifier.padding(horizontal = 6.dp, vertical = 5.dp).fillMaxWidth().background(Nx.colors.borderSubtle).size(1.dp))

@Composable
private fun MenuRow(item: MenuItem.Action, back: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (item.danger) c.danger else c.brand
    val shape = Radii.Md
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (item.disabled) 0.5f else 1f)
            .then(if (pressed) Modifier.ring(shape, tint.copy(alpha = if (item.danger) 0.30f else 0.26f)).background(tint.copy(alpha = 0.20f), shape) else Modifier)
            .clip(shape)
            .pressable(interaction, enabled = !item.disabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (back) Icon("ChevronLeft", c.textMuted, size = 16.dp)
        item.picture?.let { Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { it() } }
        val color = when {
            item.danger -> c.textDanger
            back -> c.textMuted
            item.strong || pressed -> c.textStrong
            else -> c.textSubtle
        }
        fish.nexa.ui.chat.PersonName(item.personId, item.label, style = Nx.type.body.copy(fontWeight = FontWeight(if (item.strong) 700 else 550)), color = color, maxLines = 1, modifier = Modifier.weight(1f))
        if (item.warn) Icon("TriangleAlert", c.warning, size = 15.dp)
        if (chevron) Icon(if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 16.dp)
        item.checked?.let { on ->
            if (item.toggle) {
                // a border, not ring(): ring is a shadow of the whole shape, and behind an empty box it reads as filled
                Box(
                    Modifier.size(16.dp).then(if (on) Modifier.background(c.brand, RoundedCornerShape(4.dp)) else Modifier.border(2.dp, color, RoundedCornerShape(4.dp))),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon("Check", Color.White, size = 12.dp, stroke = 3.5f) }
            } else {
                Box(Modifier.size(14.dp).alpha(if (on) 1f else 0.7f).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(6.dp).background(color, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun MenuSlider(item: MenuItem.Slider) {
    val c = Nx.colors
    var value by remember { mutableStateOf(item.value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Txt(item.label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650)), color = c.textMuted, modifier = Modifier.weight(1f))
            Txt("${value.toInt()}${item.unit}", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.text)
        }
        fish.nexa.ui.components.NxSlider(value, { value = it; item.onChange(it) }, item.range)
    }
}
