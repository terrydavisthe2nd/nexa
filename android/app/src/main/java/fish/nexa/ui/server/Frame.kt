package fish.nexa.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One page of a settings layer: its id (as the web names it), label and lucide icon. */
data class NavPage(val id: String, val label: String, val icon: String, val danger: Boolean = false)

/** A group of pages; without a heading it is set apart by space alone. */
data class NavSection(val head: String?, val pages: List<NavPage>)

/**
 * stores/unsaved.ts: while any page shows its save bar, every way of
 * leaving (back, close, another page, another role in a list) is refused and
 * every bar shakes. There is no "discard?" question: Save or Reset first.
 */
object Holds {
    private var count by mutableIntStateOf(0)

    /** Bumped each time something tried to leave with edits unsaved. */
    var nudge by mutableIntStateOf(0)
        private set

    val blocking: Boolean get() = count > 0

    fun hold() {
        count++
    }

    fun release() {
        count = maxOf(0, count - 1)
    }

    fun clear() {
        count = 0
    }

    /** Runs [fn] unless something is unsaved; then refuses and shakes the save bars. */
    fun unlessUnsaved(fn: () -> Unit) {
        if (count > 0) nudge++ else fn()
    }

    /** For a layer's canClose: true when nothing holds it, else refused with a shake. */
    fun canLeave(): Boolean {
        if (count == 0) return true
        nudge++
        return false
    }
}

/** Holds the layer open while [dirty]. */
@Composable
fun HoldWhile(dirty: Boolean) {
    DisposableEffect(dirty) {
        if (dirty) Holds.hold()
        onDispose { if (dirty) Holds.release() }
    }
}

/** Opens a settings layer (Server Settings, Channel Settings) on the layer stack. */
fun openSettingsLayer(key: String, content: @Composable (layer: Layer, close: () -> Unit) -> Unit) {
    Holds.clear()
    lateinit var layer: Layer
    layer = Layer(key, canClose = { Holds.canLeave() }) { close -> content(layer, close) }
    Layers.open(layer)
}

/**
 * SettingsLayer.tsx on a phone: one pane at a time. The list of pages (the
 * groups as raised cards of 52 dp rows) or one page pushed over it, with the
 * way back and the close button in a 52 dp top bar; Back and a drag toward
 * the reading end go back one level, unless unsaved edits hold the page.
 */
@Composable
fun SettingsFrame(
    layer: Layer,
    title: String,
    sections: List<NavSection>,
    page: String,
    setPage: (String) -> Unit,
    startOnList: Boolean,
    close: () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    var atList by remember { mutableStateOf(startOnList) }
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(200, easing = ease)) }
    layer.dismiss = {
        scope.launch {
            if (!reduced) enter.animateTo(0f, tween(150, easing = ease))
            Layers.remove(layer)
        }
    }
    val isTop = Layers.stack.lastOrNull() === layer
    // from 768 dp the list stays beside the page, as on the web
    val wide = fish.nexa.ui.nav.Nav.wide
    // On a phone the list and a page take turns: a page pushes in from the side it
    // opens towards and the list comes back from the other (web SettingsLayer's
    // arrive), both still in view as they move. Decided as they are drawn, so the
    // arriving one never shows still for a frame first.
    val listBefore = remember { booleanArrayOf(atList) }
    val arrive = remember(atList) { val d = when { atList == listBefore[0] -> 0; atList -> -1; else -> 1 }; listBefore[0] = atList; d }
    val push = remember(atList) { Animatable(if (arrive != 0 && !reduced && !wide) 0f else 1f) }
    LaunchedEffect(push) { if (push.value < 1f) push.animateTo(1f, tween(240, easing = ease)) }
    val pushRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pushed = Modifier.graphicsLayer {
        val v = push.value
        alpha = 0.5f + 0.5f * v
        translationX = (1f - v) * 32.dp.toPx() * arrive * (if (pushRtl) -1f else 1f)
    }
    BackHandler(enabled = isTop) {
        if (!atList && !wide) Holds.unlessUnsaved { atList = true } else Holds.unlessUnsaved { close() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                val s = 1.015f - 0.015f * enter.value
                scaleX = s
                scaleY = s
            }
            .background(c.bgRaised)
            // a pointer target of its own, so touches never reach the app underneath
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final) } }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(300.dp).fillMaxHeight().background(c.bgBase)) {
                NavList(title, sections, onOpen = { id -> Holds.unlessUnsaved { setPage(id) } }, onClose = { Holds.unlessUnsaved { close() } }, selected = page)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                key(page) { PageView(title, onBack = null, onClose = { Holds.unlessUnsaved { close() } }) { content(page) } }
            }
        } else if (atList) Box(pushed) {
            NavList(title, sections, onOpen = { id ->
                Holds.unlessUnsaved {
                    setPage(id)
                    atList = false
                }
            }, onClose = { Holds.unlessUnsaved { close() } })
        } else key(page) {
            Box(pushed) {
                PageView(
                    title,
                    onBack = { atList = true },
                    onClose = { Holds.unlessUnsaved { close() } },
                ) { content(page) }
            }
        }
    }
}

/** The top bar: the layer's title on the list; on a page, the way back to it. The close button at the end. */
@Composable
private fun TopBar(title: String, onBack: (() -> Unit)?, onClose: (() -> Unit)?, showTitle: Boolean = true) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(sheen(c))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack == null && !showTitle) Spacer(Modifier.weight(1f))
        else if (onBack == null) {
            Txt(
                title,
                style = Nx.type.title.copy(fontSize = Nx.type.sp(19f), lineHeight = Nx.type.sp(24f), fontWeight = FontWeight(690), letterSpacing = TextUnit.Unspecified),
                color = c.textStrong,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
            )
        } else {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .height(44.dp)
                    .clip(Radii.Xs)
                    .background(if (pressed) c.bgHover else Color.Transparent)
                    .pressable(interaction, onClick = onBack)
                    .semantics { contentDescription = t("server.settingsLayer.backTo", "name" to title) }
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(if (rtl) "ChevronRight" else "ChevronLeft", c.textStrong, size = 22.dp)
                Txt(title, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
        }
        if (onClose != null) Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .pressable(remember { MutableInteractionSource() }, onClick = onClose)
                .semantics { contentDescription = t("server.settingsLayer.closeSettings") },
            contentAlignment = Alignment.Center,
        ) { Icon("X", c.textMuted, size = 22.dp) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}

@Composable
private fun NavList(title: String, sections: List<NavSection>, onOpen: (String) -> Unit, onClose: () -> Unit, selected: String? = null) {
    val c = Nx.colors
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack = null, onClose = if (selected == null) onClose else null)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                for (s in sections) {
                    if (s.pages.isEmpty()) continue
                    if (s.head != null) Txt(
                        s.head,
                        style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700), letterSpacing = TextUnit(0.01f, TextUnitType.Em)),
                        color = c.textMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
                    ) else Spacer(Modifier.height(20.dp))
                    NavCard {
                        s.pages.forEachIndexed { i, p -> NavRow(p, first = i == 0, selected = p.id == selected, chevron = selected == null) { onOpen(p.id) } }
                    }
                }
            }
        }
    }
}

/** A group of rows as a raised card (`.navGroup` on a phone). */
@Composable
private fun NavCard(content: @Composable () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Lg
    Column(
        Modifier
            .fillMaxWidth()
            .shadowLow(shape, c)
            .ring(shape, c.panelEdge)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape),
    ) { content() }
}

/** `.navItem` on a phone: a 30 dp icon tile, the label, a chevron; 52 dp tall, a hairline between rows. */
@Composable
private fun NavRow(p: NavPage, first: Boolean, selected: Boolean = false, chevron: Boolean = true, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) c.bgHover else if (selected) c.bgSelected else Color.Transparent)
            .pressable(interaction, onClick = onClick)
            .semantics { this.selected = selected }
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tile = RoundedCornerShape(9.dp)
        Box(
            Modifier.size(30.dp).background(if (p.danger) c.danger.copy(alpha = 0.12f) else c.textStrong.copy(alpha = 0.05f), tile),
            contentAlignment = Alignment.Center,
        ) { Icon(p.icon, if (p.danger) c.textDanger else if (selected) c.textBrand else c.icon, size = 18.dp) }
        Txt(
            p.label,
            style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(550), lineHeight = Nx.type.sp(18.75f)),
            color = if (p.danger) c.textDanger else if (selected) c.textStrong else c.text,
            modifier = Modifier.weight(1f),
        )
        if (chevron) Icon(if (rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 18.dp)
    }
}

/** A page pushed over the list: it comes in from 6 dp below and goes back to the list with a drag toward the reading end. */
@Composable
private fun PageView(title: String, onBack: (() -> Unit)?, onClose: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // No fade of its own as it appears: on a phone the page pushes in (SettingsFrame's
    // arrive), and beside the list a page that faded in from nothing on every switch
    // read as a blink (the web's desktop Settings changes page in place).
    val drag = remember { Animatable(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val dir = if (rtl) -1f else 1f
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = drag.value
                    alpha = if (drag.value != 0f) maxOf(0.4f, 1f - abs(drag.value) / (600f * density)) else 1f
                }
                .pointerInput(rtl, widthPx, onBack == null) {
                    if (onBack == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val t0 = System.currentTimeMillis()
                        var total = Offset.Zero
                        var taken = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            total += ch.positionChange()
                            if (!taken) {
                                if (abs(total.x) < 14 * density && abs(total.y) < 14 * density) continue
                                if (total.x * dir > 0 && abs(total.x) >= 1.4f * abs(total.y) && !NoSwipe.active) taken = true else return@awaitEachGesture
                            }
                            ch.consume()
                            val dx = (total.x * dir).coerceAtLeast(0f)
                            scope.launch { drag.snapTo(dx * dir) }
                        }
                        if (!taken) return@awaitEachGesture
                        val dx = (total.x * dir).coerceAtLeast(0f)
                        val speed = dx / density / maxOf(1L, System.currentTimeMillis() - t0)
                        val done = dx > widthPx / 3f || (speed > 0.5f && dx > 40f * density)
                        scope.launch {
                            if (done && !Holds.blocking) {
                                drag.animateTo(dir * widthPx, tween(if (reduced) 0 else 160, easing = ease))
                                onBack?.invoke()
                            } else {
                                if (done) Holds.unlessUnsaved {}
                                drag.animateTo(0f, tween(if (reduced) 0 else 200, easing = ease))
                            }
                        }
                    }
                },
        ) {
            TopBar(title, onBack = onBack?.let { b -> { Holds.unlessUnsaved(b) } }, onClose = onClose, showTitle = onBack != null)
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/**
 * Something on the page that takes sideways drags of its own (a slider, a
 * reorder handle, a crop frame) sets this while the finger is on it, so the
 * swipe back leaves it alone (`[data-no-swipe]`).
 */
object NoSwipe {
    @Volatile var active = false
}

/** Marks a control (a slider) whose sideways drags are its own, for as long as a finger is on it. */
fun Modifier.noSwipeWhileTouched(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            NoSwipe.active = ev.changes.any { it.pressed }
        }
    }
}

/** A dashed outline (an empty list's box, the custom colour swatch). */
fun Modifier.dashedOutline(color: Color, radius: Dp, width: Dp): Modifier = this.drawBehind {
    drawRoundRect(
        color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
    )
}

/** What a page's save bar needs to know. */
class SaveState(val dirty: Boolean, val busy: Boolean, val onReset: () -> Unit, val onSave: () -> Unit, val label: String? = null)

/**
 * A settings page: its content scrolling with room under the last setting,
 * and, while it has unsaved edits, the save bar floating over its bottom.
 */
@Composable
fun SettingsPage(save: SaveState? = null, bar: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    HoldWhile(save?.dirty == true)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
            content = content,
        )
        if (save?.dirty == true) SaveBar(save, Modifier.align(Alignment.BottomCenter))
        // a page's own bar (what is picked, and what can be done to it) floats where the save bar would
        else if (bar != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) { bar() }
    }
}

/** The "You have unsaved changes." bar: it slides up when it appears and shakes red when something tries to leave. */
@Composable
private fun SaveBar(s: SaveState, modifier: Modifier) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val spring = Nx.motion.spring
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = spring)) }
    val calm = remember { Holds.nudge }
    val shake = remember { Animatable(0f) }
    var red by remember { mutableStateOf(false) }
    LaunchedEffect(Holds.nudge) {
        if (Holds.nudge == calm) return@LaunchedEffect
        red = true
        if (!reduced) shake.animateTo(0f, keyframes {
            durationMillis = 420
            0f at 0
            -8f at 63
            8f at 147
            -8f at 231
            8f at 315
            0f at 420
        })
        else kotlinx.coroutines.delay(420)
        red = false
    }
    val shape = Radii.Md
    FlowRow(
        modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1f - enter.value) * 12f * density
                alpha = enter.value.coerceIn(0f, 1f)
                translationX = shake.value * density
            }
            .shadowHigh(shape, c)
            .ring(shape, c.panelEdge)
            .background(if (red) c.danger else mixSrgb(c.bgFloating, c.bgBase, 0.06f).copy(alpha = 0.96f), shape)
            .topEdge(shape, c.edgeLight)
            .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            t("server.settingsLayer.carefulYouHaveUnsavedChanges"),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(550)),
            color = if (red) c.onDanger else c.text,
            modifier = Modifier.weight(1f).widthIn(min = 160.dp).padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxButton(t("common.imageCrop.reset"), s.onReset, kind = ButtonKind.GHOST, enabled = !s.busy)
            NxButton(s.label ?: t("server.settingsLayer.saveChanges"), s.onSave, kind = ButtonKind.POSITIVE, loading = s.busy)
        }
    }
}

// ---------------------------------------------------------------------------
// The parts every page is made of (Settings.module.css).

/** `.title`: the page's heading, 26/700 in the display face. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, gap: Dp = 22.dp) {
    Txt(
        text,
        style = Nx.type.title.copy(fontSize = Nx.type.sp(26f), lineHeight = Nx.type.sp(30f), fontWeight = FontWeight(700), letterSpacing = TextUnit(-0.012f, TextUnitType.Em)),
        color = Nx.colors.textStrong,
        // `.title`'s 22 px under it; a page whose intro is drawn up close to it asks for less
        modifier = modifier.padding(bottom = gap).semantics { heading() },
    )
}

/** `.hint`: a short muted sentence. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, top: Dp = 4.dp) {
    Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = Nx.colors.textMuted, modifier = modifier.padding(top = top))
}

/** `.h5`: a heading inside a page. */
@Composable
fun H5(text: String, modifier: Modifier = Modifier, top: Dp = 28.dp) {
    Txt(
        text,
        style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(700), letterSpacing = TextUnit(-0.005f, TextUnitType.Em)),
        color = Nx.colors.textStrong,
        modifier = modifier.padding(top = top, bottom = 10.dp).semantics { heading() },
    )
}

/** A field's caption, as TextInput's Field draws it (13/650, the required star in red). */
@Composable
fun FieldLabel(text: String, required: Boolean = false, error: Boolean = false) {
    val c = Nx.colors
    Txt(
        if (required) "$text *" else text,
        style = Nx.type.label,
        color = if (error) c.textDanger else c.textSubtle,
    )
}

/** A captioned control with its hint or error under it. */
@Composable
fun Field(label: String?, modifier: Modifier = Modifier, hint: String? = null, error: String? = null, required: Boolean = false, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) FieldLabel(label, required, error != null)
        content()
        if (error != null) Txt(error, style = Nx.type.hint.copy(fontWeight = FontWeight(500)), color = Nx.colors.textDanger)
        else if (hint != null) Txt(hint, style = Nx.type.hint, color = Nx.colors.textMuted)
    }
}

/** `.form`: fields one under another, 20 dp apart. */
@Composable
fun Form(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

/** `.card`-like surface: the panel's sheen on the surface, a hairline ring and the lit top edge. */
fun Modifier.surfaceCard(c: fish.nexa.ui.theme.NexaColors, shape: androidx.compose.ui.graphics.Shape = Radii.Md, edge: Color = c.borderSubtle): Modifier =
    this.ring(shape, edge).background(c.bgSurface, shape).background(sheen(c), shape).topEdge(shape, c.edgeLight)

/**
 * A `<select>`: the current choice in a sunken well with a chevron; tapped,
 * the choices open as a menu with the current one marked.
 */
@Composable
fun <T> SelectField(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier, title: String? = null, enabled: Boolean = true, optionPersonId: ((T) -> String?)? = null) {
    val c = Nx.colors
    val shape = Radii.Sm
    val current = options.firstOrNull { it.first == value }?.second ?: ""
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .sunken(shape, c)
            .border(1.dp, c.borderSubtle, shape)
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled) {
                openMenu(options.map { (v, label) -> MenuItem.Action(label, checked = v == value, personId = optionPersonId?.invoke(v)) { onChange(v) } }, title = title, haptic = false)
            }
            .semantics { if (title != null) contentDescription = title }
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fish.nexa.ui.chat.PersonName(optionPersonId?.invoke(value), current, style = Nx.type.input, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
        Icon("ChevronDown", c.textMuted, size = 18.dp)
    }
}

/** `.radio`: an option card; chosen, it is tinted with the accent and ringed in it. */
@Composable
fun RadioCard(label: String, selected: Boolean, onClick: () -> Unit, description: String? = null, enabled: Boolean = true) {
    val c = Nx.colors
    val shape = Radii.Md
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(
                if (selected) Modifier
                    .ring(shape, c.brand.copy(alpha = 0.8f), 2.dp)
                    .background(mixSrgb(c.bgSurface, c.brand, 0.10f), shape)
                    .background(sheen(c), shape)
                    .topEdge(shape, c.edgeLight)
                else Modifier.surfaceCard(c, shape),
            )
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).then(if (selected) Modifier.border(5.dp, c.brand, CircleShape) else Modifier.border(2.dp, c.border, CircleShape)),
        )
        Column(Modifier.weight(1f)) {
            Txt(label, style = Nx.type.body, color = if (enabled) c.text else c.textMuted)
            if (description != null) Txt(description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        }
    }
}

/** A search box: the glass, the words, and a way to clear them. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, autofocus: Boolean = false) {
    val c = Nx.colors
    val shape = Radii.Sm
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autofocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .sunken(shape, c)
            .border(1.dp, c.borderSubtle, shape)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon("Search", c.textMuted, size = 18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(placeholder, style = Nx.type.input, color = c.textPlaceholder, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Nx.type.input.copy(color = c.text),
                cursorBrush = SolidColor(c.focusRing),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
            )
        }
        if (value.isNotEmpty()) Box(
            Modifier.size(40.dp).clip(CircleShape).pressable(remember { MutableInteractionSource() }) { onChange("") }
                .semantics { contentDescription = t("friends.clearTheSearch") },
            contentAlignment = Alignment.Center,
        ) { Icon("X", c.textMuted, size = 16.dp) }
    }
}

/** NoMatches: a search that found nothing, said as such, with the way to clear it. */
@Composable
fun NoMatches(query: String, onClear: () -> Unit, people: Boolean = false) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Hint(if (people) t("friends.nobodyHereMatches", "query" to query.trim()) else t("common.nothingMatches", "query" to query.trim()), top = 0.dp)
        NxButton(t("friends.clearTheSearch"), onClear, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
}

/** A list that is still on its way: the spinner and what it is. */
@Composable
fun Loading(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 32.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) { Spinner(size = 28.dp) }
}

/** A load that failed: what failed and a way to try again. */
@Composable
fun LoadFailed(text: String, retry: () -> Unit) {
    val c = Nx.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .ring(Radii.Md, c.danger.copy(alpha = 0.45f))
            .background(c.danger.copy(alpha = 0.06f), Radii.Md)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20f)), color = c.text)
        NxButton(t("chat.messageList.tryAgain"), retry, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
}

/** An empty list, said plainly and centred. */
@Composable
fun EmptyNote(text: String, icon: String? = null) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, c.textMuted, size = 28.dp)
        Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** `.danger`: a box in the danger colour that says what cannot be undone. */
@Composable
fun DangerBox(content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, c.danger.copy(alpha = 0.55f), Radii.Md)
            .background(c.danger.copy(alpha = 0.06f), Radii.Md)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** `.warn`: a box in the warning colour. */
@Composable
fun WarnBox(text: String, icon: String = "TriangleAlert") {
    val c = Nx.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .border(1.dp, c.warning.copy(alpha = 0.6f), Radii.Md)
            .background(c.warning.copy(alpha = 0.12f), Radii.Md)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, c.textWarning, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = c.text, modifier = Modifier.weight(1f))
    }
}

/** `.iconBtn`: an icon-only button a thumb can land on (40 dp), its label for TalkBack. */
@Composable
fun RowIconButton(icon: String, label: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true, size: Dp = 40.dp) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .clip(Radii.Sm)
            .background(
                when {
                    !pressed -> Color.Transparent
                    danger -> c.danger.copy(alpha = 0.14f)
                    else -> c.bgHover
                },
            )
            .alpha(if (enabled) 1f else 0.5f)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (pressed) (if (danger) c.textDanger else c.textStrong) else c.textMuted, size = 18.dp)
    }
}

/** A role's colour dot (muted grey when it has none). */
@Composable
fun ColorDot(color: Long, size: Dp = 12.dp) {
    Box(Modifier.size(size).background(if (color != 0L) fish.nexa.ui.theme.rgbColor(color) else Nx.colors.textMuted, CircleShape))
}

/** A row of things that wraps (buttons under a field, chips). */
@Composable
fun Wrap(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
