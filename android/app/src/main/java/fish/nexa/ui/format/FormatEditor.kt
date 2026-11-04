package fish.nexa.ui.format

import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideArea
import fish.nexa.ui.components.glideItem
import fish.nexa.ui.components.rememberGlide
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sidewaysScroll
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.markdown.Align
import fish.nexa.ui.markdown.MarkdownColors
import fish.nexa.ui.markdown.Rgb
import fish.nexa.ui.markdown.format.Flag
import fish.nexa.ui.markdown.format.FormatModel
import fish.nexa.ui.markdown.format.TextKind
import fish.nexa.ui.markdown.format.Wrap
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch

internal enum class Tray { STYLE, COLOUR, CALLOUT, MORE, LINK }

private val KINDS = listOf(TextKind.P, TextKind.H1, TextKind.H2, TextKind.H3, TextKind.H4, TextKind.H5, TextKind.H6, TextKind.SUBTEXT)
private val CALLOUTS = listOf(
    Wrap.QUOTE to "TextQuote", Wrap.NOTE to "Info", Wrap.TIP to "Lightbulb",
    Wrap.IMPORTANT to "MessageSquareWarning", Wrap.WARNING to "TriangleAlert", Wrap.CAUTION to "OctagonAlert",
)
private val LANGUAGES = listOf("js", "ts", "tsx", "json", "html", "css", "py", "go", "rust", "java", "kotlin", "swift", "c", "cpp", "cs", "php", "ruby", "sql", "bash", "sh", "yaml", "toml", "md", "diff")

private fun kindLabel(kind: TextKind): String = when (kind) {
    TextKind.P -> t("composer.format.styleNormal")
    TextKind.SUBTEXT -> t("composer.format.styleSmall")
    else -> t("composer.format.styleHeading", "n" to kind.headingLevel)
}

private fun kindShort(kind: String?): String = when {
    kind != null && Regex("^h[1-6]$").matches(kind) -> "H" + kind.substring(1)
    kind == "subtext" -> t("composer.format.styleSmallShort")
    else -> t("composer.format.styleNormalShort")
}

private fun wrapLabel(wrap: Wrap): String = when (wrap) {
    Wrap.QUOTE -> t("composer.format.quote")
    Wrap.DETAILS -> t("composer.format.section")
    else -> t("render.alert.${wrap.key}")
}

/** A web address as typed, https:// added when it has none; null for anything that is not one (FormatEditor.tsx cleanUrl). */
fun cleanUrl(raw: String): String? {
    val v = raw.trim()
    if (v.isEmpty()) return null
    val withScheme = if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(v)) v else "https://$v"
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(withScheme)) return null
    val host = runCatching { java.net.URI(withScheme).host }.getOrNull() ?: return null
    return if (host.contains('.') || host == "localhost") withScheme else null
}

/**
 * The formatting editor (FormatEditor.tsx): a writing page drawn like a
 * message, with the tools at its foot where the thumb is. It opens with what
 * the message box holds and hands back markdown, to put in the box or send.
 * It fills the height above the keyboard and cannot be swiped away, so a drag
 * always edits text; closing with changes asks first.
 */
fun openFormattingEditor(
    initial: String,
    placeholder: String,
    onInsert: (String) -> Unit,
    onSend: ((String) -> Boolean)? = null,
    canSend: () -> Boolean = { true },
    title: String? = null,
    max: Int = fish.nexa.data.Send.MAX_MESSAGE_LENGTH,
) {
    val surface = FormatSurface(initial)
    var confirming by mutableStateOf(false)
    fun dirty() = surface.markdown().trim() != initial.trim()
    lateinit var layer: Layer
    layer = Layer("format-editor", canClose = {
        if (dirty() && !confirming) {
            confirming = true
            false
        } else true
    }) { close ->
        EditorFrame(surface, placeholder, title ?: t("composer.format.title"), max, confirming, { confirming = it }, close, onInsert, onSend, canSend)
    }
    Layers.open(layer)
}

@Composable
private fun EditorFrame(
    surface: FormatSurface,
    placeholder: String,
    title: String,
    max: Int,
    confirming: Boolean,
    setConfirming: (Boolean) -> Unit,
    close: () -> Unit,
    onInsert: (String) -> Unit,
    onSend: ((String) -> Boolean)?,
    canSend: () -> Boolean,
) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    var leaving by remember { mutableStateOf(false) }
    var markdown by remember { mutableStateOf(surface.markdown()) }
    // read live from the surface: the caret, the text and a format chosen to type with (Bold pressed
    // with nothing selected) all show on the toolbar the moment they change, not after the next letter
    val state by remember(surface) { derivedStateOf { surface.state() } }
    var tray by remember { mutableStateOf<Tray?>(null) }
    val density = LocalDensity.current.density
    fun refresh() {
        markdown = surface.markdown()
    }
    fun gone() {
        if (leaving) return
        leaving = true
        scope.launch {
            if (!reduced) enter.animateTo(0f, tween(180))
            close()
        }
    }
    fun requestClose() {
        if (surface.markdown().trim() != surface.initial.trim()) setConfirming(true) else gone()
    }
    fun insert() {
        onInsert(surface.markdown())
        gone()
    }
    fun send() {
        val send = onSend ?: return insert()
        if (!canSend()) return
        val md = surface.markdown()
        if (md.isBlank()) return
        if (send(md)) gone()
    }
    LaunchedEffect(Unit) {
        surface.onChange = { refresh() }
        surface.onSubmit = { send() }
        surface.onLinkKey = { tray = Tray.LINK }
        surface.onEscape = { if (tray != null) tray = null else requestClose() }
        if (!reduced) enter.animateTo(1f, tween(280, easing = ease))
    }
    BackHandler {
        if (tray != null) tray = null else requestClose()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(enter.value)
                .background(c.bgOverlay)
                .pressable(remember { MutableInteractionSource() }, role = Role.Button) { requestClose() },
        )
        val shape = RoundedCornerShape(topStart = Radii.lg, topEnd = Radii.lg)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .padding(top = 8.dp)
                .graphicsLayer {
                    translationY = (1f - enter.value) * 40f * density
                    alpha = 0.4f + 0.6f * enter.value
                }
                .shadowHigh(shape, c)
                .ring(shape, c.panelEdge)
                .background(c.bgFloating, shape)
                .topEdge(shape, c.edgeLight)
                .clip(shape)
                .pressable(remember { MutableInteractionSource() }) {},
        ) {
            // header: close, title, the counter, Insert and Send
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { requestClose() }
                        .semantics { contentDescription = t("composer.format.close") },
                    contentAlignment = Alignment.Center,
                ) { Icon("X", c.textMuted, size = 20.dp) }
                Txt(title, style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Counter(markdown, max)
                    HeadButton(t("composer.format.insert"), null, c.bgActive, c.text, enabled = true) { insert() }
                    if (onSend != null) HeadButton(t("composer.format.send"), "SendHorizontal", c.brand, c.onBrand, enabled = canSend() && markdown.isNotBlank()) { send() }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            if (confirming) DiscardRow({ setConfirming(false) }) { gone() }
            if (surface.simplified) {
                Txt(t("composer.format.simplified"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            }
            // the page
            Box(Modifier.weight(1f).fillMaxWidth().background(c.bgBase)) {
                FormatPage(surface, placeholder)
            }
            // what the caret is in, or the tray asked for, then the tools
            val shown = settledTray(tray, state)
            if (shown != null) {
                Column(Modifier.fillMaxWidth().background(c.bgSurface).topHairline(c.borderSubtle)) {
                    TrayBody(shown, state, surface, setTray = { tray = it })
                }
            }
            val nav = WindowInsets.navigationBars.getBottom(LocalDensity.current)
            val ime = WindowInsets.ime.getBottom(LocalDensity.current)
            val bottom = with(LocalDensity.current) { maxOf(4.dp, (nav - ime).coerceAtLeast(0).toDp()) }
            Toolbar(state, tray, { tray = if (tray == it) null else it }, surface, Modifier.padding(bottom = bottom))
        }
    }
}

internal fun Modifier.topHairline(colour: Color): Modifier = drawBehind { drawRect(colour, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }

/** MessageCounter: how many characters are left, near the limit and past it. */
@Composable
private fun Counter(text: String, max: Int) {
    val left = max - text.length
    if (left >= 200) return
    val c = Nx.colors
    Txt(
        left.toString(),
        style = Nx.type.hint.copy(fontWeight = if (left < 0) FontWeight(600) else FontWeight(400), fontFeatureSettings = "tnum"),
        color = if (left < 0) c.textDanger else c.textMuted,
        modifier = Modifier.semantics { contentDescription = if (left < 0) t("composer.limit.over", "count" to -left) else t("composer.limit.left", "count" to left) },
    )
}

@Composable
private fun HeadButton(label: String, icon: String?, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val c = Nx.colors
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(Radii.Xs)
            .background(if (pressed && bg == c.brand) c.brandHover else if (pressed) c.bgHover else bg)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, fg, size = 18.dp)
        Txt(label, style = Nx.type.buttonSmall, color = fg)
    }
}

/** "Close without inserting?": a warning bar under the header with Keep Editing and Discard. */
@Composable
private fun DiscardRow(keep: () -> Unit, discard: () -> Unit) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgRaised)
            .drawBehind {
                val w = 3.dp.toPx()
                drawRect(c.warning, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(t("composer.format.discardTitle"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeadButton(t("composer.format.keepEditing"), null, Color.Transparent, c.textSubtle, enabled = true, onClick = keep)
            HeadButton(t("composer.format.discard"), null, c.danger, c.onDanger, enabled = true, onClick = discard)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}

// ---------------------------------------------------------------------------
// the toolbar

internal sealed interface Shown {
    data class Asked(val tray: Tray) : Shown
    data object ListTools : Shown
    data object CodeTools : Shown
    data object TableTools : Shown
    data object LinkHere : Shown
}

/** What the tray shows: what was asked for, or what the caret is in. */
internal fun trayShown(tray: Tray?, s: SurfaceState): Shown? = when {
    tray != null -> Shown.Asked(tray)
    s.table != null -> Shown.TableTools
    s.kind == "code" -> Shown.CodeTools
    s.link != null -> Shown.LinkHere
    s.kind == "li" -> Shown.ListTools
    else -> null
}

/**
 * trayShown, held still while a selection is being made: what the caret was
 * in when it started stays the tray until the selection closes. Dragging a
 * selection from a list into the text under it otherwise took the list's
 * tools away and put them back as it crossed, the page's height changing
 * under the finger each time (the web's FormatEditor does the same).
 */
@Composable
internal fun settledTray(tray: Tray?, s: SurfaceState): Shown? {
    val held = androidx.compose.runtime.remember { arrayOfNulls<Shown>(1) }
    val caret = trayShown(null, s)
    if (s.collapsed) held[0] = caret
    return if (tray != null) Shown.Asked(tray) else if (s.collapsed) caret else held[0]
}

@Composable
internal fun Toolbar(s: SurfaceState, tray: Tray?, toggle: (Tray) -> Unit, surface: FormatSurface, modifier: Modifier, lead: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    val marks = s.marks
    val inCode = s.kind == "code"
    val inTable = s.table != null
    val task = s.list?.task == true
    val kind = s.kind ?: "p"
    fun run(cmd: Cmd) = surface.command(cmd)
    Row(
        modifier
            .fillMaxWidth()
            .background(c.bgSurface)
            .topHairline(c.borderSubtle)
            .sidewaysScroll()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp)
            .semantics { contentDescription = t("composer.format.toolbar") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // first, so they never scroll out of sight along with the rest
        if (lead != null) {
            lead()
            Divider()
        }
        Tool("Undo2", t("composer.format.undo"), enabled = s.canUndo, mirror = true) { run(Cmd.Undo) }
        Tool("Redo2", t("composer.format.redo"), enabled = s.canRedo, mirror = true) { run(Cmd.Redo) }
        Divider()
        Tool(null, t("composer.format.style"), text = kindShort(if (kind == "li" || kind == "note") "p" else kind), wide = true, expanded = tray == Tray.STYLE, enabled = !inCode && !inTable) { toggle(Tray.STYLE) }
        Divider()
        Tool("Bold", t("composer.format.bold"), pressed = marks.b, enabled = !inCode) { run(Cmd.Mark(Flag.B)) }
        Tool("Italic", t("composer.format.italic"), pressed = marks.i, enabled = !inCode) { run(Cmd.Mark(Flag.I)) }
        Tool("Underline", t("composer.format.underline"), pressed = marks.u, enabled = !inCode) { run(Cmd.Mark(Flag.U)) }
        Tool("Strikethrough", t("composer.format.strike"), pressed = marks.s, enabled = !inCode) { run(Cmd.Mark(Flag.S)) }
        Tool("EyeOff", t("composer.format.spoiler"), pressed = marks.spoiler, enabled = !inCode) { run(Cmd.Mark(Flag.SPOILER)) }
        Tool("Code", t("composer.format.inlineCode"), pressed = marks.code, enabled = !inCode) { run(Cmd.Mark(Flag.CODE)) }
        Divider()
        Tool("Link", t("composer.format.link"), pressed = s.link != null, expanded = tray == Tray.LINK, enabled = !inCode) { toggle(Tray.LINK) }
        Tool("Baseline", t("composer.format.textColour"), expanded = tray == Tray.COLOUR, enabled = !inCode, swatch = marks.color?.fg ?: "") { toggle(Tray.COLOUR) }
        Divider()
        Tool("List", t("composer.format.bullets"), pressed = kind == "li" && s.list?.ordered != true && !task, enabled = !inCode && !inTable) { run(Cmd.ListOf("bullet")) }
        Tool("ListOrdered", t("composer.format.numbers"), pressed = kind == "li" && s.list?.ordered == true && !task, enabled = !inCode && !inTable) { run(Cmd.ListOf("ordered")) }
        Tool("ListChecks", t("composer.format.checklist"), pressed = task, enabled = !inCode && !inTable) { run(Cmd.ListOf("task")) }
        Divider()
        Tool("TextQuote", t("composer.format.callouts"), pressed = s.wrap != null && s.wrap != Wrap.DETAILS, expanded = tray == Tray.CALLOUT) { toggle(Tray.CALLOUT) }
        Tool("SquareCode", t("composer.format.codeBlock"), pressed = inCode, enabled = !inTable) { run(Cmd.Code) }
        Tool("Table", t("composer.format.table"), enabled = !inTable) { run(Cmd.Table(3, 2)) }
        Tool("Ellipsis", t("composer.format.more"), expanded = tray == Tray.MORE) { toggle(Tray.MORE) }
        Divider()
        Tool("RemoveFormatting", t("composer.format.clear")) { run(Cmd.Clear) }
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 20.dp).background(Nx.colors.border))

/**
 * A tool: 44 dp, an 18 dp icon; on or open, the accent at 18% with its text
 * colour. It never takes the keyboard from the page, so the selection it acts
 * on is still there.
 */
@Composable
private fun Tool(
    icon: String?,
    label: String,
    text: String? = null,
    pressed: Boolean = false,
    expanded: Boolean = false,
    enabled: Boolean = true,
    wide: Boolean = false,
    mirror: Boolean = false,
    swatch: String? = null,
    onClick: () -> Unit,
) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val touched by interaction.collectIsPressedAsState()
    val on = pressed || expanded
    val fg = if (on) c.textBrand else if (touched) c.text else c.textSubtle
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .width(if (wide) 64.dp else 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Radii.Xs)
            .background(if (on) c.brand.copy(alpha = 0.18f) else if (touched) c.bgHover else Color.Transparent)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = label
                if (pressed || expanded) selected = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (icon != null) Box(Modifier.graphicsLayer { scaleX = if (mirror && rtl) -1f else 1f }) { Icon(icon, fg, size = 18.dp) }
            if (text != null) Txt(text, style = Nx.type.label.copy(fontWeight = FontWeight(700), fontSize = Nx.type.sp(13f)), color = fg)
            if (wide) Icon("ChevronDown", fg, size = 14.dp)
        }
        if (swatch != null) {
            val bar = if (swatch.isEmpty()) fg else swatchColour(swatch)
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(26.dp, 3.dp).background(bar, RoundedCornerShape(2.dp)))
        }
    }
}

/** A tool of the editor's own, before the formatting ones (a note's Add Picture and Attach File). */
@Composable
internal fun ToolButton(icon: String, label: String, onClick: () -> Unit) = Tool(icon, label, onClick = onClick)

@Composable
private fun swatchColour(token: String): Color {
    val c = Nx.colors
    val surface = MarkdownColors.Surface(Rgb.of(c.bgBase.toArgb()), Rgb.of(c.text.toArgb()))
    val hex = MarkdownColors.swatch(token, surface)
    return Color(0xFF000000 or hex.removePrefix("#").toLong(16))
}

// ---------------------------------------------------------------------------
// trays

@Composable
internal fun TrayBody(shown: Shown, s: SurfaceState, surface: FormatSurface, setTray: (Tray?) -> Unit) {
    fun run(cmd: Cmd) = surface.command(cmd)
    val inCode = s.kind == "code"
    val marks = s.marks
    when (shown) {
        is Shown.Asked -> when (shown.tray) {
            Tray.STYLE -> Chips {
                for (k in KINDS) Chip(kindLabel(k), pressed = s.kind == k.key, style = styleChip(k)) {
                    run(Cmd.Kind(k))
                    setTray(null)
                }
            }
            Tray.COLOUR -> ColourTray(s, surface)
            Tray.CALLOUT -> Chips {
                for ((wrap, icon) in CALLOUTS) Chip(wrapLabel(wrap), icon = icon, iconTint = calloutTint(wrap), pressed = s.wrap == wrap) {
                    run(Cmd.WrapIn(wrap))
                    setTray(null)
                }
            }
            Tray.MORE -> Chips {
                Chip(t("composer.format.superscript"), icon = "Superscript", pressed = marks.sup, enabled = !inCode) { run(Cmd.Mark(Flag.SUP)) }
                Chip(t("composer.format.subscript"), icon = "Subscript", pressed = marks.sub, enabled = !inCode) { run(Cmd.Mark(Flag.SUB)) }
                Chip(t("composer.format.highlight"), icon = "Highlighter", pressed = marks.mark, enabled = !inCode) { run(Cmd.Mark(Flag.MARK)) }
                Chip(t("composer.format.section"), icon = "ListCollapse", pressed = s.wrap == Wrap.DETAILS) {
                    run(Cmd.WrapIn(Wrap.DETAILS))
                    setTray(null)
                }
                Chip(t("composer.format.divider"), icon = "SeparatorHorizontal") {
                    run(Cmd.Rule)
                    setTray(null)
                }
            }
            Tray.LINK -> LinkTray(s, surface) { setTray(null) }
        }
        Shown.LinkHere -> LinkTray(s, surface) { setTray(null) }
        Shown.ListTools -> Chips {
            Chip(t("composer.format.outdent"), icon = "ListIndentDecrease", mirror = true, enabled = (s.list?.indent ?: 0) > 0) { run(Cmd.Indent(-1)) }
            Chip(t("composer.format.indent"), icon = "ListIndentIncrease", mirror = true) { run(Cmd.Indent(1)) }
            if (s.list?.task == true) Chip(t("composer.format.done"), icon = "Check", pressed = s.list.checked) { run(Cmd.Check) }
        }
        Shown.CodeTools -> CodeTray(s.codeLang ?: "", surface)
        Shown.TableTools -> {
            val table = s.table ?: return
            fun op(o: FormatModel.TableOp?) = run(Cmd.TableEdit(o))
            fun align(a: Align) = op(FormatModel.TableOp.SetAlign(table.col, if (table.align == a) null else a))
            Chips {
                Chip(t("composer.format.rowAbove"), icon = "Rows3") { op(FormatModel.TableOp.AddRow(maxOf(1, table.row))) }
                Chip(t("composer.format.rowBelow"), icon = "Rows3") { op(FormatModel.TableOp.AddRow(table.row + 1)) }
                Chip(t("composer.format.columnBefore"), icon = "Columns3") { op(FormatModel.TableOp.AddCol(table.col)) }
                Chip(t("composer.format.columnAfter"), icon = "Columns3") { op(FormatModel.TableOp.AddCol(table.col + 1)) }
                Chip(t("composer.format.alignStart"), icon = "TextAlignStart", pressed = table.align == Align.LEFT) { align(Align.LEFT) }
                Chip(t("composer.format.alignCenter"), icon = "TextAlignCenter", pressed = table.align == Align.CENTER) { align(Align.CENTER) }
                Chip(t("composer.format.alignEnd"), icon = "TextAlignEnd", pressed = table.align == Align.RIGHT) { align(Align.RIGHT) }
                Chip(t("composer.format.deleteRow"), icon = "Trash2", enabled = table.row != 0 && table.rows > 1) { op(FormatModel.TableOp.DelRow(table.row)) }
                Chip(t("composer.format.deleteColumn"), icon = "Trash2") { op(FormatModel.TableOp.DelCol(table.col)) }
                Chip(t("composer.format.deleteTable"), icon = "Trash2") { op(null) }
            }
        }
    }
}

@Composable
private fun styleChip(k: TextKind): TextStyle {
    val base = Nx.type.label.copy(fontWeight = FontWeight(500))
    val sp = Nx.type::sp
    return when (k) {
        TextKind.H1 -> base.copy(fontSize = sp(17f), fontWeight = FontWeight(700))
        TextKind.H2 -> base.copy(fontSize = sp(16f), fontWeight = FontWeight(700))
        TextKind.H3, TextKind.H4 -> base.copy(fontSize = sp(14f), fontWeight = FontWeight(700))
        TextKind.H5 -> base.copy(fontSize = sp(13f), fontWeight = FontWeight(700))
        TextKind.H6 -> base.copy(fontSize = sp(13f), fontWeight = FontWeight(600), color = Nx.colors.textMuted)
        TextKind.SUBTEXT -> base.copy(fontSize = sp(12f), color = Nx.colors.textMuted)
        else -> base
    }
}

@Composable
private fun calloutTint(wrap: Wrap): Color {
    val c = Nx.colors
    return when (wrap) {
        Wrap.QUOTE -> c.textMuted
        Wrap.NOTE -> c.link
        Wrap.TIP -> mixSrgb(c.textStrong, c.positive, 0.8f)
        Wrap.IMPORTANT -> c.textBrand
        Wrap.WARNING -> c.textWarning
        Wrap.CAUTION -> c.textDanger
        Wrap.DETAILS -> c.textMuted
    }
}

/** One row that scrolls sideways (a phone keeps the tray to one line). */
@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().sidewaysScroll().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Chip(
    label: String,
    icon: String? = null,
    iconTint: Color? = null,
    pressed: Boolean = false,
    enabled: Boolean = true,
    mirror: Boolean = false,
    style: TextStyle? = null,
    onClick: () -> Unit,
) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val touched by interaction.collectIsPressedAsState()
    val shape = Radii.Sm
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(if (pressed) c.brand.copy(alpha = 0.16f) else if (touched) c.bgHover else c.bgRaised, shape)
            .border(1.dp, if (pressed) c.brand.copy(alpha = 0.7f) else c.border, shape)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .semantics { if (pressed) selected = true }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Box(Modifier.graphicsLayer { scaleX = if (mirror && rtl) -1f else 1f }) { Icon(icon, iconTint ?: if (pressed) c.textBrand else c.text, size = 16.dp) }
        Txt(label, style = (style ?: Nx.type.label.copy(fontWeight = FontWeight(500))).let { if (pressed) it.copy(color = c.textBrand) else it }, color = if (pressed) c.textBrand else (style?.color?.takeIf { it != Color.Unspecified } ?: c.text), maxLines = 1)
    }
}

/** A small field for a tray: a web address, a colour code, a language. */
@Composable
private fun TrayField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier,
    bad: Boolean = false,
    url: Boolean = false,
    onDone: () -> Unit,
) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = Radii.Xs
    BasicTextField(
        value, onChange,
        modifier = modifier
            .height(40.dp)
            .background(c.bgInput, shape)
            .border(1.dp, if (bad) c.textDanger else if (focused) c.focusRing else c.border, shape)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = label },
        textStyle = Nx.type.input.copy(color = c.text),
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(c.textBrand),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = if (url) KeyboardType.Uri else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(placeholder, style = Nx.type.input, color = c.textPlaceholder, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun ErrorLine(text: String) = Txt(
    text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = Nx.colors.textDanger,
    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
)

@Composable
private fun ColourTray(s: SurfaceState, surface: FormatSurface) {
    val c = Nx.colors
    var part by remember { mutableStateOf("fg") }
    var hex by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val current = if (part == "fg") s.marks.color?.fg else s.marks.color?.bg
    fun apply(value: String?) = surface.command(Cmd.Colour(part, value))
    fun applyHex() {
        val token = MarkdownColors.token(if (hex.startsWith("#")) hex else "#$hex")
        if (token == null || !token.startsWith("#")) {
            bad = true
            return
        }
        bad = false
        apply(token)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().sidewaysScroll().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // what to colour: the text or its highlight, the pill sliding between them (components/Glide.kt)
            val partGlide = rememberGlide(part)
            Box(Modifier.background(c.bgInput, Radii.Sm).padding(2.dp).glideArea(partGlide).semantics { contentDescription = t("composer.format.colourFor") }) {
            GlidePill(partGlide, Modifier.background(c.bgRaised, RoundedCornerShape(6.dp)))
            Row {
                for (p in listOf("fg", "bg")) {
                    val on = part == p
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .glideItem(partGlide, p)
                            .clip(RoundedCornerShape(6.dp))
                            .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton) { part = p }
                            .semantics { selected = on }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(if (p == "fg") t("composer.format.colourText") else t("composer.format.highlight"), style = Nx.type.label.copy(fontWeight = FontWeight(600)), color = if (on) c.text else c.textMuted)
                    }
                }
            }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(null, current == null, t("composer.format.colourNone"), part) { apply(null) }
                for (name in MarkdownColors.NAMES) Swatch(name, current == name, t("composer.format.colour.$name"), part) { apply(name) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrayField(hex, { hex = it.take(7); bad = false }, "#ff8800", t("composer.format.colourHex"), Modifier.width(96.dp), bad = bad) { applyHex() }
                Chip(t("composer.format.apply")) { applyHex() }
            }
        }
        if (bad) ErrorLine(t("composer.format.colourHexInvalid"))
    }
}

@Composable
private fun Swatch(token: String?, on: Boolean, label: String, part: String, onClick: () -> Unit) {
    val c = Nx.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (on) Modifier.border(2.dp, c.text, CircleShape) else Modifier)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        if (token == null) {
            val muted = c.textMuted
            Box(
                Modifier.size(20.dp).border(1.5.dp, muted, CircleShape).drawBehind {
                    drawLine(muted, Offset(size.width * 0.15f, size.height * 0.85f), Offset(size.width * 0.85f, size.height * 0.15f), strokeWidth = 2.dp.toPx())
                },
            )
        } else {
            val colour = swatchColour(token)
            Box(
                Modifier
                    .size(20.dp)
                    .alpha(if (part == "bg") 0.55f else 1f)
                    .background(colour, CircleShape)
                    .border(1.dp, c.text.copy(alpha = 0.18f), CircleShape),
            )
        }
    }
}

@Composable
private fun LinkTray(s: SurfaceState, surface: FormatSurface, done: () -> Unit) {
    val existing = s.link
    var url by remember(existing?.url) { mutableStateOf(existing?.url ?: "") }
    var words by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val needsWords = existing == null && s.collapsed
    fun apply() {
        val clean = cleanUrl(url)
        if (clean == null) {
            bad = true
            return
        }
        surface.command(Cmd.Link(clean, words))
        done()
    }
    Column {
        Row(
            Modifier.fillMaxWidth().sidewaysScroll().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (needsWords) TrayField(words, { words = it }, t("composer.format.linkWords"), t("composer.format.linkWords"), Modifier.widthIn(min = 160.dp).width(200.dp)) { apply() }
            TrayField(url, { url = it; bad = false }, "https://", t("composer.format.linkAddress"), Modifier.width(220.dp), bad = bad, url = true) { apply() }
            Chip(t("composer.format.apply")) { apply() }
            if (existing != null) Chip(t("composer.format.linkRemove")) {
                surface.command(Cmd.Link(null))
                done()
            }
        }
        if (bad) ErrorLine(t("composer.format.linkInvalid"))
    }
}

@Composable
private fun CodeTray(lang: String, surface: FormatSurface) {
    var value by remember(lang) { mutableStateOf(lang) }
    Column {
        Row(
            Modifier.fillMaxWidth().sidewaysScroll().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TrayField(value, { value = it }, t("composer.format.languageHint"), t("composer.format.language"), Modifier.width(240.dp)) { surface.command(Cmd.Lang(value)) }
            Chip(t("composer.format.codeToText")) { surface.command(Cmd.Code) }
        }
        // the datalist's suggestions, as chips under the field
        val q = value.trim().lowercase()
        val picks = LANGUAGES.filter { it != q && (q.isEmpty() || it.startsWith(q)) }
        if (picks.isNotEmpty()) Chips {
            for (l in picks) Chip(l) {
                value = l
                surface.command(Cmd.Lang(l))
            }
        }
    }
}
