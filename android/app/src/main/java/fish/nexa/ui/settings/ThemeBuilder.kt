package fish.nexa.ui.settings

import fish.nexa.ui.components.GlidePill
import fish.nexa.ui.components.glideArea
import fish.nexa.ui.components.glideItem
import fish.nexa.ui.components.rememberGlide
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxSlider
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.SelectField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.moderation.SearchField
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.look.Colour
import fish.nexa.ui.theme.look.Contrast
import fish.nexa.ui.theme.look.CustomTheme
import fish.nexa.ui.theme.look.CustomThemes
import fish.nexa.ui.theme.look.Derive
import fish.nexa.ui.theme.look.LookDraft
import fish.nexa.ui.theme.look.Looks
import fish.nexa.ui.theme.look.Presets
import fish.nexa.ui.theme.look.ResolvedTheme
import fish.nexa.ui.theme.look.SimpleColours
import fish.nexa.ui.theme.look.ThemeSource
import fish.nexa.ui.theme.look.ThemeTools
import fish.nexa.ui.theme.look.TokenMap
import fish.nexa.ui.theme.look.Tokens
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sunken
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A custom theme being made or changed: what is on screen with its undo and
 * redo, what was last saved, which editor shows it, and the builder's own
 * views (Compare held, the colour vision the preview shows, Tune's sliders).
 */
class ThemeBuild(initial: CustomTheme, isNew: Boolean) {
    private var now by mutableStateOf(initial)
    private var past by mutableStateOf(listOf<CustomTheme>())
    private var future by mutableStateOf(listOf<CustomTheme>())
    private var lastAt = 0L

    /**
     * The theme on screen. Setting it is a step Undo takes back; changes that
     * come quickly one after another (a picker dragged, typing) are one step.
     */
    var draft: CustomTheme
        get() = now
        set(next) {
            if (next == now) return
            val at = SystemClock.uptimeMillis()
            val merge = at - lastAt < 600 && past.isNotEmpty()
            lastAt = at
            if (!merge) past = (past + now).takeLast(100)
            future = emptyList()
            now = next
        }

    /** In place, as no step of its own (Save tidying the name). */
    fun replace(next: CustomTheme) {
        now = next
    }

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun undo() {
        endTune()
        if (past.isEmpty()) return
        lastAt = 0
        future = listOf(now) + future
        now = past.last()
        past = past.dropLast(1)
    }

    fun redo() {
        endTune()
        if (future.isEmpty()) return
        lastAt = 0
        past = past + now
        now = future.first()
        future = future.drop(1)
    }

    var saved by mutableStateOf(initial)
    var fresh by mutableStateOf(isNew)
    var tab by mutableStateOf(if (initial.source is ThemeSource.Simple && initial.overrides.isEmpty()) "simple" else "advanced")
        private set
    /** The simple colours last used, so Advanced can go back to them after trying a preset. */
    var lastSimple by mutableStateOf(initial.source as? ThemeSource.Simple)
    val dirty get() = fresh || now != saved

    /** Compare held: the saved theme (or the one it started as) shows in its place. */
    var held by mutableStateOf(false)
    var vision by mutableStateOf("typical")
    var folded by mutableStateOf(false)
    /** A colour pointed at in the preview or the palette, and how many times, so the same one twice is found again. */
    var focus by mutableStateOf<Pair<String, Int>?>(null)
        private set

    // Tune works from the theme as it was when a slider first moved
    var tune by mutableStateOf(ThemeTools.Tune())
        private set
    var tuneStatus by mutableStateOf(false)
        private set
    private var tuneFrom: Pair<CustomTheme, TokenMap>? = null

    fun endTune() {
        tuneFrom = null
        tune = ThemeTools.Tune()
    }

    fun showTab(next: String) {
        if (next != "tune") endTune()
        tab = next
    }

    fun applyTune(next: ThemeTools.Tune, status: Boolean, tokens: TokenMap) {
        val from = tuneFrom ?: (now to tokens).also { tuneFrom = it }
        tune = next
        tuneStatus = status
        draft = if (next.neutral) from.first else ThemeTools.withPalette(from.first, ThemeTools.tunePalette(from.second, next, status), from.second)
    }

    fun resetTune() {
        tuneFrom?.let { draft = it.first }
        endTune()
    }

    fun pick(token: String) {
        showTab("advanced")
        focus = token to ((focus?.second ?: 0) + 1)
    }
}

/** The Theme Builder over the Appearance page, while it is open; the whole app wears its draft meanwhile. */
object ThemeEditing {
    var build by mutableStateOf<ThemeBuild?>(null)
        private set

    fun open(theme: CustomTheme, isNew: Boolean) {
        build = ThemeBuild(theme, isNew)
    }

    fun close() {
        build = null
        LookDraft.theme = null
    }

    /** New Theme: a copy of the custom theme on screen, or the colours of the theme on screen. */
    fun startNew(shown: String, customs: List<CustomTheme>) {
        val from = customs.firstOrNull { it.id == shown }
        open(
            from?.let { copyOf(it) }
                ?: CustomTheme(CustomThemes.newId(), t("settings.themeBuilder.defaultName"), ThemeSource.Simple(startingColours(shown) ?: startingColours("dark")!!), emptyMap()),
            isNew = true,
        )
    }

    /** A new, unsaved theme with the colours of [from] (its unsaved changes included). */
    fun copyOf(from: CustomTheme): CustomTheme =
        from.copy(id = CustomThemes.newId(), name = t("settings.themeBuilder.copyOf", "name" to from.name.trim().ifEmpty { t("settings.themeBuilder.untitled") }).take(CustomThemes.MAX_NAME))
}

private fun tokenLabel(token: String): String = t("look.tokens." + token.split("-").mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString(""))

/**
 * 3.12.3: making or changing a custom theme. The whole app wears the theme
 * while it is open; above the tools a small Nexa shows the parts the settings
 * never do, and pressing any part of it opens that colour. Nothing is kept
 * until Save.
 */
@Composable
fun ThemeBuilder(build: ThemeBuild, customs: List<CustomTheme>) {
    val c = Nx.colors
    val ctx = LocalContext.current
    val host = LocalAnchors.current
    val doc = settingsDoc()
    val draft = build.draft
    val resolved = remember(draft) { CustomThemes.resolve(draft) }
    val shown = remember(build.held, resolved, build.saved) { if (build.held) CustomThemes.resolve(build.saved) else resolved }
    val simple = (draft.source as? ThemeSource.Simple)?.colours
    val derived = remember(simple) { simple?.let { Derive.deriveTheme(it) } }
    val issues = remember(resolved) { Contrast.issues(resolved.tokens) }
    // the whole app wears the draft while it is open (or the saved theme while Compare is held)
    LaunchedEffect(shown) { LookDraft.theme = shown }
    // back (and the swipe) closes the builder before the page; the page starts at its top again
    DisposableEffect(build) {
        host?.inner = { ThemeEditing.close() }
        onDispose { host?.inner = null }
    }
    LaunchedEffect(build) { host?.scroll?.scrollTo(0) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(CustomThemes.export(build.draft).toByteArray()) } }
    }

    BackRow(t("settings.appearance.appearance")) { Unsaved.unlessUnsaved { ThemeEditing.close() } }
    NameField(build)
    HeadTools(build)
    if (doc.str("accent") != null) InfoNotice(t("settings.themeBuilder.accentOverride"), Modifier.padding(top = 14.dp)) {
        NxButton(t("settings.themeBuilder.useThemeAccent"), { setSetting("accent", null as String?) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
    // on a phone the preview comes first, above the editor
    Stage(build, shown.tokens, resolved.tokens, issues)
    Column(Modifier.padding(top = 20.dp)) {
        Segmented(
            listOf(t("settings.themeBuilder.simple") to "simple", t("settings.themeBuilder.advanced") to "advanced", t("settings.themeBuilder.tune") to "tune"), build.tab,
            { k ->
                if (k == "simple" && draft.source !is ThemeSource.Simple) {
                    // Simple works from a few colours; a theme built on a preset brings its own over
                    val tk = resolved.tokens
                    build.draft = draft.copy(source = build.lastSimple ?: ThemeSource.Simple(SimpleColours(tk.getValue("bg-raised"), tk.getValue("brand"), null, resolved.scheme)))
                }
                build.showTab(k)
            },
            label = t("settings.themeBuilder.modeLabel"),
        )
    }
    Column(Modifier.padding(top = 8.dp)) {
        when {
            build.tab == "simple" && simple != null && derived != null -> SimpleEditor(build, simple, derived)
            build.tab == "tune" -> TuneEditor(build, resolved.tokens)
            else -> AdvancedEditor(build, resolved.tokens, issues)
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NxButton(t("settings.themeBuilder.export"), { export.launch(CustomThemes.fileName(build.draft.name)) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Download")
        NxButton(t("settings.themeBuilder.copy"), {
            val ok = copyText(ctx, CustomThemes.export(build.draft))
            Nexa.ui.toast(if (ok) t("settings.themeBuilder.copied") else t("common.copyFailed"), if (ok) "success" else "error")
        }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Copy")
        if (!build.fresh) NxButton(t("settings.themeBuilder.duplicate"), { ThemeEditing.open(ThemeEditing.copyOf(build.draft), isNew = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "CopyPlus")
        if (!build.fresh) NxButton(t("settings.themeBuilder.delete"), { deleteTheme(build, customs) }, kind = ButtonKind.GHOST, size = ButtonSize.SM, icon = "Trash2")
    }
}

/** The theme's name as the page's title, edited in place, with what is being looked at under it. */
@Composable
private fun NameField(build: ThemeBuild) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val style = Nx.type.title.copy(fontSize = Nx.type.sp(26f), lineHeight = Nx.type.sp(32f), fontWeight = FontWeight(700), color = c.textStrong)
    BasicTextField(
        value = build.draft.name,
        onValueChange = { build.draft = build.draft.copy(name = it.take(CustomThemes.MAX_NAME)) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.focusRing),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = t("settings.themeBuilder.name") },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                if (build.draft.name.isEmpty()) Txt(t("settings.themeBuilder.untitled"), style = style, color = c.textPlaceholder, maxLines = 1)
                inner()
            }
        },
    )
    Txt(
        when {
            build.held && build.fresh -> t("settings.themeBuilder.comparingNew")
            build.held -> t("settings.themeBuilder.comparing")
            build.fresh -> t("settings.themeBuilder.titleNew")
            else -> t("settings.themeBuilder.titleEdit")
        },
        style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.padding(top = 2.dp).semantics { heading() },
    )
}

/** Undo, Redo and Compare (held to see the theme as it was saved). */
@Composable
private fun HeadTools(build: ThemeBuild) {
    val c = Nx.colors
    Row(Modifier.padding(top = 10.dp).offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        fish.nexa.ui.components.IconButton("Undo2", t("settings.themeBuilder.undo"), { build.undo() }, tint = c.icon, size = 44.dp, iconSize = 18.dp, enabled = build.canUndo)
        fish.nexa.ui.components.IconButton("Redo2", t("settings.themeBuilder.redo"), { build.redo() }, tint = c.icon, size = 44.dp, iconSize = 18.dp, enabled = build.canRedo)
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c.borderSubtle))
        val enabled = build.held || build.draft != build.saved
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .clip(Radii.Sm)
                .background(if (build.held) c.bgActive else Color.Transparent)
                .alpha(if (enabled) 1f else 0.4f)
                .then(
                    if (enabled) Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            build.held = true
                            waitForUpOrCancellation()
                            build.held = false
                        }
                    } else Modifier,
                )
                .semantics {
                    contentDescription = t("settings.themeBuilder.compare")
                    stateDescription = if (build.fresh) t("settings.themeBuilder.compareNewHint") else t("settings.themeBuilder.compareHint")
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon("SquareSplitHorizontal", if (build.held) c.textStrong else c.icon, size = 18.dp)
            Txt(t("settings.themeBuilder.compare"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (build.held) c.textStrong else c.icon)
        }
    }
}

// ---- the stage: the preview, how it is seen, how well it reads, every colour ----

@Composable
private fun Stage(build: ThemeBuild, shown: TokenMap, current: TokenMap, issues: List<Contrast.Issue>) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(t("settings.themeBuilder.preview"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong, modifier = Modifier.weight(1f).semantics { heading() })
        NxButton(if (build.folded) t("designer.preview.show") else t("designer.preview.hide"), { build.folded = !build.folded }, kind = ButtonKind.GHOST, size = ButtonSize.SM, icon = if (build.folded) "ChevronDown" else "ChevronUp")
    }
    if (build.folded) return
    val tokens = remember(shown, build.vision) { ThemeTools.visionPalette(shown, build.vision) }
    var hot by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            MiniApp(tokens) { token ->
                hot = token
                build.pick(token)
            }
            val h = hot
            Txt(
                if (h != null) tokenLabel(h) + "  --" + h else t("settings.themeBuilder.previewPoint"),
                style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )
        }
        SelectField(null, ThemeTools.VISIONS.map { it to t("settings.themeBuilder.vision.$it") }, build.vision, Modifier.semantics { contentDescription = t("settings.themeBuilder.previewAs") }) { build.vision = it }
        Readability(issues) { build.pick(it) }
        PaletteStrip(current, build.draft.overrides.keys, issues.map { it.token }.toSet()) { build.pick(it) }
    }
}

/** `.read`: whether every colour reads where it is used, with the way to the first that does not. */
@Composable
private fun Readability(issues: List<Contrast.Issue>, onReview: (String) -> Unit) {
    val c = Nx.colors
    val bad = issues.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background((if (bad) c.warning.copy(alpha = 0.14f) else c.positive.copy(alpha = 0.12f)), Radii.Sm)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (bad) "TriangleAlert" else "CircleCheck", if (bad) c.textWarning else c.positive, size = 16.dp)
        Txt(
            if (bad) t("settings.themeBuilder.issues", "count" to issues.size) else t("settings.themeBuilder.allReadable"),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (bad) c.text else c.textSubtle, modifier = Modifier.weight(1f),
        )
        if (bad) NxButton(t("settings.themeBuilder.review"), { onReview(issues.first().token) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
}

/** Every colour as a small swatch in the editor's groups: the way a screen reader reaches one colour, and the palette at a glance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteStrip(tokens: TokenMap, changed: Set<String>, issues: Set<String>, onPick: (String) -> Unit) {
    val c = Nx.colors
    FlowRow(Modifier.fillMaxWidth().semantics { contentDescription = t("settings.themeBuilder.palette") }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((group, list) in Tokens.GROUPS) {
            FlowRow(Modifier.semantics { contentDescription = t("settings.themeBuilder.group.$group") }, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (tk in list) {
                    val shape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .size(28.dp)
                            .then(if (tk in issues) Modifier.border(2.dp, c.warning, shape) else Modifier)
                            .clip(shape)
                            .pressable(remember { MutableInteractionSource() }) { onPick(tk) }
                            .semantics { contentDescription = t("settings.themeBuilder.editColour", "name" to tokenLabel(tk)) },
                    ) {
                        Checker(Modifier.fillMaxSize(), cell = 4.dp, show = tk in Tokens.ALPHA)
                        Box(Modifier.fillMaxSize().background(Looks.color(tokens.getValue(tk))).border(1.dp, c.border, shape))
                        if (tk in changed) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(4.dp).background(c.textStrong, CircleShape))
                    }
                }
            }
        }
    }
}

/** A checkerboard behind a see-through colour, so how see-through shows. */
@Composable
private fun Checker(modifier: Modifier, cell: Dp = 6.dp, show: Boolean = true) {
    if (!show) return
    Canvas(modifier) {
        val px = cell.toPx()
        drawRect(Color.White)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else px
            while (x < size.width) {
                drawRect(Color(0xFFCFCFCF), Offset(x, y), Size(px, px))
                x += px * 2
            }
            y += px
            row++
        }
    }
}

// ---- the preview: a small Nexa in the theme being made ----

/** The palette a preview is drawn in, read as colours. */
private class Paint(private val tokens: TokenMap) {
    operator fun get(token: String): Color = Looks.color(tokens.getValue(token))
}

/**
 * ThemePreview: the server rail with a tooltip, the channel list with a
 * selected and a hovered row, a conversation with a mention, code, a link,
 * reactions, a spoiler and a link preview, the message box, a menu, buttons
 * and the status colours. Every part is drawn in one colour and pressing it
 * opens that colour; the palette below stands in for it for TalkBack.
 */
@Composable
private fun MiniApp(tokens: TokenMap, onPick: (String) -> Unit) {
    val p = remember(tokens) { Paint(tokens) }
    val c = Nx.colors
    val tiny = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), lineHeight = Nx.type.sp(14.8f))
    val small = tiny.copy(fontSize = Nx.type.sp(10f))
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // one source for every part: they show no ripple, so nothing is drawn from it
    val src = remember { MutableInteractionSource() }
    fun Modifier.part(token: String): Modifier = this.clickable(src, indication = null) { onPick(token) }
    val top = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val bottom = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Row(Modifier.fillMaxWidth().height(300.dp).ring(top, c.borderSubtle).clip(top).background(p["bg-raised"])) {
            // the rail
            Box(Modifier.width(32.dp).fillMaxHeight().background(p["bg-base"]).part("bg-base")) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(24.dp).background(p["brand"], RoundedCornerShape(9.dp)).part("brand"), contentAlignment = Alignment.Center) {
                        Icon("Smile", p["on-brand"], size = 14.dp, modifier = Modifier.part("on-brand"))
                    }
                    Box(Modifier.width(16.dp).height(2.dp).background(p["border"], RoundedCornerShape(1.dp)).part("border"))
                    RailItem(p, "W", pill = true, onPick = onPick)
                    Box {
                        RailItem(p, "K", onPick = onPick)
                        Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 3.dp).size(13.dp).background(p["danger"], CircleShape).part("danger"), contentAlignment = Alignment.Center) {
                            Txt("3", style = small.copy(fontSize = Nx.type.sp(8.5f), fontWeight = FontWeight(700)), color = p["on-danger"], modifier = Modifier.part("on-danger"))
                        }
                    }
                    Box(Modifier.size(24.dp).background(p["bg-surface"], RoundedCornerShape(9.dp)).part("bg-surface"), contentAlignment = Alignment.Center) {
                        Icon("Plus", p["positive"], size = 13.dp, modifier = Modifier.part("positive"))
                    }
                }
            }
            // the channel list
            Box(Modifier.width(100.dp).fillMaxHeight().background(p["bg-base"]).part("bg-base")) {
                Column(Modifier.fillMaxSize()) {
                    Txt(t("settings.themeBuilder.sampleServer"), style = tiny.copy(fontWeight = FontWeight(700)), color = p["text-strong"], maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp).part("text-strong"))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p["border-subtle"]))
                    Column(Modifier.padding(horizontal = 6.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).background(p["bg-input"], RoundedCornerShape(5.dp)).part("bg-input").padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon("Search", p["icon"], size = 10.dp)
                            Txt(t("settings.themeBuilder.sampleSearch"), style = tiny, color = p["text-placeholder"], maxLines = 1, modifier = Modifier.part("text-placeholder"))
                        }
                        Chan(p, "Megaphone", t("settings.themeBuilder.sampleAnnouncements"), "text-channel", null, tiny, onPick)
                        Chan(p, "Hash", t("settings.themeBuilder.sampleChannel"), "text-strong", "bg-selected", tiny.copy(fontWeight = FontWeight(600)), onPick)
                        Chan(p, "Hash", t("settings.themeBuilder.sampleRandom"), "text", "bg-hover", tiny, onPick, badge = "2")
                        Chan(p, "Volume2", t("settings.themeBuilder.sampleVoice"), "text-channel", null, tiny, onPick)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().background(p["bg-surface"]).part("bg-surface").padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            MiniAvatar(p, "R", "brand", "on-brand", onPick)
                            Box(Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp).size(9.dp).background(p["bg-surface"], CircleShape).padding(1.5.dp).background(p["status-online"], CircleShape).part("status-online"))
                        }
                        Column {
                            Txt("Robin", style = tiny.copy(fontWeight = FontWeight(600)), color = p["text-strong"], maxLines = 1, modifier = Modifier.part("text-strong"))
                            Txt(t("shell.user.online"), style = small.copy(fontSize = Nx.type.sp(9.5f)), color = p["text-muted"], maxLines = 1, modifier = Modifier.part("text-muted"))
                        }
                    }
                }
            }
            // the conversation
            Box(Modifier.weight(1f).fillMaxHeight().background(p["bg-raised"]).part("bg-raised")) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon("Hash", p["icon"], size = 12.dp, modifier = Modifier.part("icon"))
                        Txt(t("settings.themeBuilder.sampleChannel"), style = tiny.copy(fontWeight = FontWeight(700)), color = p["text-strong"], maxLines = 1, modifier = Modifier.part("text-strong"))
                        Txt(t("settings.themeBuilder.sampleTopic"), style = tiny, color = p["text-muted"], maxLines = 1, modifier = Modifier.weight(1f).part("text-muted"))
                        for (i in listOf("Bell", "Pin", "Users")) Icon(i, p["icon"], size = 11.dp, modifier = Modifier.part("icon"))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p["border-subtle"]).part("border-subtle"))
                    // the newest messages at the bottom; the oldest is cut off at the top, as a scrolled chat is
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.BottomStart) {
                    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        MiniMessage(p, "R", "Robin", "brand", "on-brand", Modifier.background(p["bg-message-hover"]).part("bg-message-hover"), tiny, onPick) {
                            BasicText(
                                buildAnnotatedString {
                                    append(t("settings.themeBuilder.sampleHello") + " ")
                                    withStyle(SpanStyle(background = p["mention-bg"], color = p["mention-fg"], fontWeight = FontWeight(600))) { append("@Sam") }
                                    append(" ")
                                    withStyle(SpanStyle(background = p["code-bg"], fontFamily = Nx.type.mono, fontSize = Nx.type.sp(10f))) { append("theme.css") }
                                    append(" ")
                                    withStyle(SpanStyle(color = p["link"], textDecoration = TextDecoration.Underline)) { append("nexa.fish") }
                                },
                                style = tiny.copy(color = p["text"]),
                                modifier = Modifier.part("text"),
                            )
                            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Reaction(p, "Palette", "4", "mention-bg", "text-brand", p["brand"], small, onPick)
                                Reaction(p, "Sparkles", "2", "bg-surface", "text-subtle", null, small, onPick)
                            }
                        }
                        MiniMessage(
                            p, "S", "Sam", "positive", "on-positive",
                            Modifier.background(p["brand"].copy(alpha = 0.09f)).part("brand").drawBehind {
                                val w = 2.dp.toPx()
                                drawRect(p["focus-ring"], Offset(if (rtl) size.width - w else 0f, 2.dp.toPx()), Size(w, size.height - 4.dp.toPx()))
                            },
                            tiny, onPick,
                        ) {
                            Txt(t("settings.themeBuilder.sampleReply"), style = tiny, color = p["text"], modifier = Modifier.part("text"))
                            BasicText(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = p["code-comment"])) { append("// " + t("settings.themeBuilder.sampleComment")) }
                                    append("\n")
                                    withStyle(SpanStyle(color = p["code-keyword"])) { append("const") }
                                    append(" ")
                                    withStyle(SpanStyle(color = p["code-variable"])) { append("theme") }
                                    append(" = ")
                                    withStyle(SpanStyle(color = p["code-title"])) { append("make") }
                                    append("(")
                                    withStyle(SpanStyle(color = p["code-string"])) { append("\"dusk\"") }
                                    append(", ")
                                    withStyle(SpanStyle(color = p["code-number"])) { append("4.5") }
                                    append(");\n")
                                    withStyle(SpanStyle(color = p["code-keyword"])) { append("type") }
                                    append(" ")
                                    withStyle(SpanStyle(color = p["code-type"])) { append("Look") }
                                    append(" = ")
                                    withStyle(SpanStyle(color = p["code-type"])) { append("Theme") }
                                    append(";")
                                },
                                style = Nx.type.code.copy(fontSize = Nx.type.sp(9.5f), lineHeight = Nx.type.sp(13.8f), color = p["text"]),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .fillMaxWidth()
                                    .background(p["code-bg"], RoundedCornerShape(5.dp))
                                    .border(1.dp, p["code-border"], RoundedCornerShape(5.dp))
                                    .part("code-bg")
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                            )
                        }
                        MiniMessage(p, "K", "Kit", "warning", "on-warning", Modifier, tiny, onPick) {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Txt(t("settings.themeBuilder.sampleSpoilerLead"), style = tiny, color = p["text"], modifier = Modifier.part("text"))
                                Box(Modifier.background(p["spoiler"], RoundedCornerShape(3.dp)).part("spoiler").padding(horizontal = 3.dp)) {
                                    Txt(t("settings.themeBuilder.sampleSpoiler"), style = tiny, color = Color.Transparent)
                                }
                                Box(Modifier.background(p["spoiler-revealed"], RoundedCornerShape(3.dp)).part("spoiler-revealed").padding(horizontal = 3.dp)) {
                                    Txt(t("settings.themeBuilder.sampleSpoiler"), style = tiny, color = p["text"])
                                }
                            }
                            Column(
                                Modifier
                                    .padding(top = 2.dp)
                                    .widthIn(max = 220.dp)
                                    .background(p["embed-bg"], RoundedCornerShape(4.dp))
                                    .drawBehind {
                                        val w = 2.dp.toPx()
                                        drawRect(p["brand"], Offset(if (rtl) size.width - w else 0f, 0f), Size(w, size.height))
                                    }
                                    .part("embed-bg")
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Txt("nexa.fish", style = small.copy(fontSize = Nx.type.sp(9f)), color = p["text-brand"], modifier = Modifier.part("text-brand"))
                                Txt(t("settings.themeBuilder.sampleEmbedTitle"), style = tiny.copy(fontWeight = FontWeight(650)), color = p["link"], modifier = Modifier.part("link"))
                                Txt(t("settings.themeBuilder.sampleEmbedBody"), style = tiny, color = p["text-subtle"], modifier = Modifier.part("text-subtle"))
                            }
                        }
                    }
                    }
                    Row(
                        Modifier
                            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp)
                            .fillMaxWidth()
                            .background(p["bg-composer"], RoundedCornerShape(7.dp))
                            .part("bg-composer")
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon("Plus", p["icon"], size = 12.dp, modifier = Modifier.part("icon"))
                        Txt(t("settings.themeBuilder.samplePlaceholder"), style = tiny, color = p["text-placeholder"], maxLines = 1, modifier = Modifier.part("text-placeholder"))
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 2.dp).width(4.dp).height(70.dp).background(p["scroll-thumb"], RoundedCornerShape(2.dp)).part("scroll-thumb"))
            }
        }
        // the foot: a menu, the buttons and the status colours
        Row(Modifier.fillMaxWidth().ring(bottom, c.borderSubtle).clip(bottom).background(p["bg-base"]).part("bg-base").padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier
                    .width(112.dp)
                    .background(p["bg-floating"], RoundedCornerShape(7.dp))
                    .border(1.dp, p["border-subtle"], RoundedCornerShape(7.dp))
                    .part("bg-floating")
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Txt(t("sidebar.dmMenu.markAsRead"), style = tiny, color = p["text"], maxLines = 1, modifier = Modifier.part("text").padding(horizontal = 6.dp, vertical = 3.dp))
                Box(Modifier.fillMaxWidth().background(p["bg-hover"], RoundedCornerShape(4.dp)).part("bg-hover").padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Txt(t("textMenu.copyLink"), style = tiny, color = p["text"], maxLines = 1)
                }
                Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxWidth().height(1.dp).background(p["border-subtle"]))
                Txt(t("chat.messageMenu.deleteMessage"), style = tiny, color = p["text-danger"], maxLines = 1, modifier = Modifier.part("text-danger").padding(horizontal = 6.dp, vertical = 3.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniButton(t("settings.themeBuilder.sampleSave"), p["brand"], p["on-brand"], null, "brand", small, onPick)
                    MiniButton(t("settings.themeBuilder.sampleCancel"), p["bg-surface"], p["text"], p["border"], "border", small, onPick)
                    MiniButton(t("settings.themeBuilder.sampleAccept"), p["positive"], p["on-positive"], null, "positive", small, onPick)
                    MiniButton(t("settings.themeBuilder.sampleDelete"), p["danger"], p["on-danger"], null, "danger", small, onPick)
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((tk, word) in listOf("status-online" to t("shell.user.online"), "status-idle" to t("shell.user.idle"), "status-dnd" to t("settings.themeBuilder.sampleBusy"), "status-offline" to t("chat.memberList.offline"))) {
                        Row(Modifier.part(tk), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.size(7.dp).background(p[tk], CircleShape))
                            Txt(word, style = small, color = p["text"])
                        }
                    }
                    Txt(t("settings.themeBuilder.sampleWarning"), style = small, color = p["text-warning"], modifier = Modifier.part("text-warning"))
                    Txt(t("settings.themeBuilder.sampleError"), style = small, color = p["text-danger"], modifier = Modifier.part("text-danger"))
                }
            }
        }
    }
}

@Composable
private fun RailItem(p: Paint, letter: String, pill: Boolean = false, onPick: (String) -> Unit) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (pill) Box(Modifier.align(Alignment.CenterStart).offset(x = (-4).dp).width(3.dp).height(16.dp).background(p["text-strong"], RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).clickable(remember { MutableInteractionSource() }, null) { onPick("text-strong") })
        Box(Modifier.size(24.dp).background(p["bg-surface"], RoundedCornerShape(if (pill) 8.dp else 9.dp)).clickable(remember { MutableInteractionSource() }, null) { onPick("bg-surface") }, contentAlignment = Alignment.Center) {
            Txt(letter, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = p["text"], modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { onPick("text") })
        }
    }
}

@Composable
private fun Chan(p: Paint, icon: String, name: String, textToken: String, fill: String?, style: androidx.compose.ui.text.TextStyle, onPick: (String) -> Unit, badge: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .then(if (fill != null) Modifier.background(p[fill], RoundedCornerShape(5.dp)) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null) { onPick(fill ?: textToken) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, p["icon"], size = 11.dp, modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { onPick("icon") })
        Txt(name, style = style, color = p[textToken], maxLines = 1, modifier = Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onPick(textToken) })
        if (badge != null) {
            Box(Modifier.size(13.dp).background(p["danger"], CircleShape).clickable(remember { MutableInteractionSource() }, null) { onPick("danger") }, contentAlignment = Alignment.Center) {
                Txt(badge, style = Nx.type.hint.copy(fontSize = Nx.type.sp(8.5f), fontWeight = FontWeight(700)), color = p["on-danger"])
            }
        }
    }
}

@Composable
private fun MiniAvatar(p: Paint, letter: String, fill: String, on: String, onPick: (String) -> Unit) {
    Box(Modifier.size(22.dp).background(p[fill], CircleShape).clickable(remember { MutableInteractionSource() }, null) { onPick(fill) }, contentAlignment = Alignment.Center) {
        Txt(letter, style = Nx.type.hint.copy(fontSize = Nx.type.sp(10f), fontWeight = FontWeight(700)), color = p[on])
    }
}

@Composable
private fun MiniMessage(p: Paint, letter: String, name: String, fill: String, on: String, modifier: Modifier, style: androidx.compose.ui.text.TextStyle, onPick: (String) -> Unit, body: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        MiniAvatar(p, letter, fill, on, onPick)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(name, style = style.copy(fontWeight = FontWeight(650)), color = p["text-strong"], modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { onPick("text-strong") })
                Txt(t("settings.appearance.todayAt420Pm"), style = style.copy(fontSize = Nx.type.sp(9f)), color = p["text-muted"], maxLines = 1, modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { onPick("text-muted") })
            }
            body()
        }
    }
}

@Composable
private fun Reaction(p: Paint, icon: String, count: String, fill: String, fg: String, edge: Color?, style: androidx.compose.ui.text.TextStyle, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .height(17.dp)
            .background(p[fill], shape)
            .then(if (edge != null) Modifier.border(1.dp, edge, shape) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null) { onPick(fill) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, p[fg], size = 10.dp)
        Txt(count, style = style.copy(fontWeight = FontWeight(600)), color = p[fg], modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { onPick(fg) })
    }
}

@Composable
private fun MiniButton(text: String, fill: Color, fg: Color, edge: Color?, token: String, style: androidx.compose.ui.text.TextStyle, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .height(22.dp)
            .background(fill, shape)
            .then(if (edge != null) Modifier.border(1.dp, edge, shape) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null) { onPick(token) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, style = style.copy(fontSize = Nx.type.sp(10.5f), fontWeight = FontWeight(650)), color = fg, maxLines = 1) }
}

/** The way back to the page a level is opened over. */
@Composable
private fun BackRow(label: String, onBack: () -> Unit) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .heightIn(min = 44.dp)
            .clip(Radii.Sm)
            .pressable(remember { MutableInteractionSource() }, onClick = onBack)
            .semantics { contentDescription = t("server.settingsLayer.backTo", "name" to label) }
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (rtl) "ChevronRight" else "ChevronLeft", c.textMuted, size = 18.dp)
        Txt(label, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textMuted)
    }
}

/**
 * Two or three choices side by side in a sunken strip (the builder's tabs,
 * Dark and Light), the chosen one's pill sliding to the next (components/Glide.kt).
 */
@Composable
fun <T> Segmented(options: List<Pair<String, T>>, value: T, onPick: (T) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val c = Nx.colors
    val shape = Radii.Sm
    val inner = RoundedCornerShape(8.dp)
    val glide = rememberGlide(value)
    Box(
        modifier
            .fillMaxWidth()
            .sunken(shape, c)
            .ring(shape, c.borderSubtle)
            .padding(3.dp)
            .glideArea(glide)
            .semantics { if (label != null) contentDescription = label },
    ) {
    GlidePill(glide, Modifier.ring(inner, c.panelEdge).background(c.bgFloating, inner))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((name, v) in options) {
            val on = v == value
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .glideItem(glide, v as Any)
                    .clip(inner)
                    .pressable(remember { MutableInteractionSource() }, role = Role.Tab) { onPick(v) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Txt(name, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted, maxLines = 1)
            }
        }
    }
    }
}

/** `.notice`: a note in the builder, with its button. */
@Composable
private fun InfoNotice(text: String, modifier: Modifier = Modifier, warn: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    val tone = if (warn) c.warning else c.textBrand
    Column(
        modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), Radii.Sm)
            .border(1.dp, tone.copy(alpha = 0.40f), Radii.Sm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (warn) "TriangleAlert" else "Info", if (warn) c.textWarning else c.textBrand, size = 16.dp)
            Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20f)), color = c.text, modifier = Modifier.weight(1f))
        }
        if (action != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
    }
}

// ---- Simple mode ----

/** Colours to try with one press, under the colour they would replace. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ideas(label: String, colours: List<String>, current: String, onPick: (String) -> Unit) {
    val c = Nx.colors
    FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp).semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (hex in colours) {
            val on = hex == current
            Box(
                Modifier
                    .size(32.dp)
                    .then(if (on) Modifier.border(2.dp, c.textStrong, CircleShape).padding(4.dp) else Modifier)
                    .clip(CircleShape)
                    .background(Looks.color(hex))
                    .border(1.dp, Color(0x737F7F7F), CircleShape)
                    .pressable(remember { MutableInteractionSource() }) { onPick(hex) }
                    .semantics {
                        contentDescription = t("settings.themeBuilder.useColour", "hex" to hex)
                        selected = on
                    },
            )
        }
    }
}

@Composable
private fun SimpleEditor(build: ThemeBuild, value: SimpleColours, derived: fish.nexa.ui.theme.look.Derived) {
    fun set(v: SimpleColours) {
        build.draft = build.draft.copy(source = ThemeSource.Simple(v))
        build.lastSimple = ThemeSource.Simple(v)
    }
    // ideas come from the accent as it was before one of them was tried, so they stay put while tried one after another
    var anchor by remember { mutableStateOf(value.accent) }
    var tried by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value.accent) { if (value.accent != tried) anchor = value.accent }
    val bgIdeas = remember(anchor, value.scheme) { ThemeTools.backgroundIdeas(anchor, value.scheme) }
    val acIdeas = remember(anchor, value.scheme) { ThemeTools.accentIdeas(anchor, value.scheme) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(t("settings.themeBuilder.simpleHint"), Modifier.weight(1f))
    }
    NxButton(t("settings.themeBuilder.surprise"), { set(ThemeTools.surprise()) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Dices", modifier = Modifier.padding(bottom = 4.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Txt(t("settings.themeBuilder.mode"), style = Nx.type.body.copy(fontWeight = FontWeight(550)), color = Nx.colors.text)
        Segmented(listOf(t("settings.themeBuilder.modeDark") to "dark", t("settings.themeBuilder.modeLight") to "light"), value.scheme, { scheme ->
            // the same tint, carried over to a background of the other kind
            if (scheme != value.scheme) set(value.copy(scheme = scheme, background = Derive.flipBackground(value.background, scheme)))
        }, Modifier.weight(1f), label = t("settings.themeBuilder.mode"))
    }
    // a background picked on the other side of middle grey takes its scheme with it
    ColourField(t("settings.themeBuilder.background"), value.background, { set(value.copy(background = it, scheme = Derive.schemeOf(it))) }, hint = t("settings.themeBuilder.backgroundHint"))
    Ideas(t("settings.themeBuilder.backgroundIdeas"), bgIdeas, value.background) { set(value.copy(background = it)) }
    ColourField(t("settings.themeBuilder.accent"), value.accent, { set(value.copy(accent = it)) }, hint = t("settings.themeBuilder.accentHint"))
    Ideas(t("settings.themeBuilder.accentIdeas"), acIdeas, value.accent) {
        tried = it
        set(value.copy(accent = it))
    }
    ToggleRow(
        t("settings.themeBuilder.textOwn"), value.text != null,
        { on -> set(value.copy(text = if (on) derived.tokens.getValue("text") else null)) },
        if (value.text == null) t("settings.themeBuilder.textAuto") else null,
        divider = value.text == null,
    )
    value.text?.let { text -> ColourField(t("settings.themeBuilder.text"), text, { set(value.copy(text = it)) }) }
    val moved = listOfNotNull(
        t("settings.themeBuilder.adjustedBackground").takeIf { derived.adjustedBackground },
        t("settings.themeBuilder.adjustedAccent").takeIf { derived.adjustedAccent },
        t("settings.themeBuilder.adjustedText").takeIf { derived.adjustedText },
    )
    if (moved.isNotEmpty()) InfoNotice(moved.joinToString("\n"), Modifier.padding(top = 12.dp))
    val handSet = build.draft.overrides.size
    if (handSet > 0) InfoNotice(t("settings.themeBuilder.handSet", "count" to handSet), Modifier.padding(top = 12.dp)) {
        NxButton(t("settings.themeBuilder.clearHandSet"), { build.draft = build.draft.copy(overrides = emptyMap()) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
    SelectRow(
        t("settings.themeBuilder.startFrom"), "", Presets.ALL.map { t(it.nameKey) to it.id },
        { id -> startingColours(id)?.let { set(it) } },
        sub = t("settings.themeBuilder.startFromHint"), divider = false, placeholder = t("settings.themeBuilder.chooseTheme"),
    )
}

// ---- Tune ----

@Composable
private fun TuneEditor(build: ThemeBuild, tokens: TokenMap) {
    val c = Nx.colors
    Hint(t("settings.themeBuilder.tuneHint"), Modifier.padding(top = 4.dp, bottom = 8.dp))
    val tune = build.tune
    for ((key, range) in listOf("hue" to -180f..180f, "saturation" to -100f..100f, "brightness" to -100f..100f, "contrast" to -100f..100f)) {
        val v = when (key) {
            "hue" -> tune.hue
            "saturation" -> tune.saturation
            "brightness" -> tune.brightness
            else -> tune.contrast
        }
        fun with(n: Int) = when (key) {
            "hue" -> tune.copy(hue = n)
            "saturation" -> tune.copy(saturation = n)
            "brightness" -> tune.copy(brightness = n)
            else -> tune.copy(contrast = n)
        }
        val name = t("settings.themeBuilder.tune.$key")
        val unit = if (key == "hue") "°" else ""
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.text, modifier = Modifier.weight(1f))
                Txt(ThemeTools.signed(v, unit), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                Box(Modifier.size(40.dp).alpha(if (v != 0) 1f else 0f)) {
                    if (v != 0) fish.nexa.ui.components.IconButton("RotateCcw", t("settings.themeBuilder.resetNamed", "name" to name), { build.applyTune(with(0), build.tuneStatus, tokens) }, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
                }
            }
            Txt(t("settings.themeBuilder.tune.${key}Hint"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            NxSlider(v.toFloat(), { f ->
                val n = Math.round(f)
                if (n != v) build.applyTune(with(n), build.tuneStatus, tokens)
            }, range, modifier = Modifier.padding(top = 6.dp).semantics { stateDescription = ThemeTools.signed(v, unit) }, label = name)
            if (key == "hue") Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(
                    // OKLCH 0.7 0.15 every 60 degrees, as the web's .hueStrip
                    Brush.horizontalGradient(listOf(0xFFE7729B, 0xFFE18528, 0xFF93AB2C, 0xFF00B8A1, 0xFF26A9F1, 0xFFAD87ED, 0xFFE7729B).map { Color(it) }),
                ),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
    }
    ToggleRow(t("settings.themeBuilder.tuneStatus"), build.tuneStatus, { on -> build.applyTune(tune, on, tokens) }, t("settings.themeBuilder.tuneStatusHint"))
    NxButton(t("settings.themeBuilder.tuneReset"), { build.resetTune() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "RotateCcw", enabled = !tune.neutral, modifier = Modifier.padding(top = 12.dp))
}

// ---- Advanced mode ----

/** `.chip`: a pill that is on or off (Changed Only), or a plain action beside one. */
@Composable
private fun Chip(text: String, on: Boolean?, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (on == true) c.bgSelected else Color.Transparent)
            .ring(shape, if (on == true) c.borderStrong else c.border)
            .pressable(remember { MutableInteractionSource() }, role = if (on != null) Role.Checkbox else Role.Button, onClick = onClick)
            .semantics { if (on != null) selected = on }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (on == true) c.textStrong else c.textMuted, maxLines = 1) }
}

@Composable
private fun AdvancedEditor(build: ThemeBuild, tokens: Map<String, String>, issues: List<Contrast.Issue>) {
    val c = Nx.colors
    val theme = build.draft
    val base = remember(theme.source) { CustomThemes.sourceTokens(theme.source) }
    val byToken = issues.associateBy { it.token }
    // the first group and any with a problem open by themselves; nothing closes on its own
    val open = remember { mutableStateOf(setOf(Tokens.GROUPS[0].first) + issues.map { Tokens.groupOf(it.token) }) }
    LaunchedEffect(issues) {
        val flagged = issues.map { Tokens.groupOf(it.token) }
        if (flagged.any { it !in open.value }) open.value = open.value + flagged
    }
    var query by remember { mutableStateOf("") }
    var changedOnly by remember { mutableStateOf(false) }
    // a colour pointed at in the preview or the palette: shown, opened, scrolled to and lit for a moment
    val focus = build.focus
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        query = ""
        changedOnly = false
        open.value = open.value + Tokens.groupOf(focus.first)
    }
    val q = query.trim().lowercase()
    fun shows(tk: String) = (!changedOnly || tk in theme.overrides) && (q.isEmpty() || tk.contains(q.trimStart('-')) || tokenLabel(tk).lowercase().contains(q))
    val filtering = q.isNotEmpty() || changedOnly
    fun set(token: String, value: String) {
        val o = LinkedHashMap(theme.overrides)
        // set back to the source's own colour is not set by hand any more
        if (base[token] == value) o.remove(token) else o[token] = value
        build.draft = theme.copy(overrides = o)
    }
    fun reset(token: String) {
        build.draft = theme.copy(overrides = LinkedHashMap(theme.overrides).apply { remove(token) })
    }
    Hint(t("settings.themeBuilder.advancedHint"), Modifier.padding(top = 4.dp, bottom = 4.dp))
    val sourceValue = (theme.source as? ThemeSource.FromPreset)?.id ?: "simple"
    SelectRow(
        t("settings.themeBuilder.builtOn"), sourceValue,
        (if (build.lastSimple != null) listOf(t("settings.themeBuilder.yourColours") to "simple") else emptyList()) + Presets.ALL.map { t(it.nameKey) to it.id },
        { v ->
            if (v == "simple") build.lastSimple?.let { build.draft = theme.copy(source = it) }
            else build.draft = theme.copy(source = ThemeSource.FromPreset(v))
        },
        sub = t("settings.themeBuilder.builtOnHint"), divider = false,
    )
    if (issues.isNotEmpty()) InfoNotice(t("settings.themeBuilder.issues", "count" to issues.size), Modifier.padding(vertical = 10.dp), warn = true) {
        NxButton(t("settings.themeBuilder.fixIssues"), { build.draft = ThemeTools.withPalette(theme, Contrast.fix(tokens), tokens) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Sparkles")
    }
    SearchField(query, { query = it }, t("settings.themeBuilder.searchColours"), Modifier.fillMaxWidth().padding(top = 10.dp))
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(t("settings.themeBuilder.changedOnly"), changedOnly) { changedOnly = !changedOnly }
        if (!filtering) {
            val allOpen = Tokens.GROUPS.all { it.first in open.value }
            Chip(if (allOpen) t("settings.themeBuilder.collapseAll") else t("settings.themeBuilder.expandAll"), null) {
                open.value = if (allOpen) emptySet() else Tokens.GROUPS.map { it.first }.toSet()
            }
        }
    }
    if (filtering && Tokens.ALL.none { shows(it) }) {
        Txt(
            if (changedOnly && q.isEmpty()) t("settings.themeBuilder.noneChanged") else t("settings.themeBuilder.noMatches", "query" to query.trim()),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            align = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    for ((group, all) in Tokens.GROUPS) {
        val list = if (filtering) all.filter { shows(it) } else all
        if (list.isEmpty()) continue
        val changed = all.count { it in theme.overrides }
        val bad = all.count { it in byToken }
        val isOpen = filtering || group in open.value
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = 48.dp)
                .clip(Radii.Sm)
                .pressable(remember { MutableInteractionSource() }, enabled = !filtering) { open.value = if (isOpen) open.value - group else open.value + group }
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (isOpen) "ChevronDown" else if (rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 16.dp)
            Txt(t("settings.themeBuilder.group.$group"), style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong, modifier = Modifier.weight(1f))
            if (bad > 0) Txt(t("settings.themeBuilder.groupIssues", "count" to bad), style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.textWarning)
            else if (changed > 0) Txt(t("settings.themeBuilder.groupChanged", "count" to changed), style = Nx.type.hint, color = c.textMuted)
        }
        if (isOpen) for (token in list) {
            val issue = byToken[token]
            ColourField(
                tokenLabel(token), tokens.getValue(token), { set(token, it) },
                name = "--$token", alpha = token in Tokens.ALPHA, changed = token in theme.overrides, onReset = { reset(token) },
                // rounded down, so a 4.46 that fails is never shown as a 4.5 that should pass
                issue = issue?.let { t("settings.themeBuilder.lowContrast", "ratio" to Math.floor(it.ratio * 10) / 10, "min" to it.min) },
                found = focus?.takeIf { it.first == token }?.second,
            )
        }
    }
}

// ---- one colour ----

/**
 * ColourField: a chip that opens the colour picker, the colour's name (and
 * the web's name for it), its hex code to type, a way back to the source's
 * colour, and for a see-through colour how see-through it is. [found] counts
 * the times it was pointed at; each time it is scrolled into view and lit.
 */
@Composable
private fun ColourField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    name: String? = null,
    alpha: Boolean = false,
    changed: Boolean = false,
    onReset: (() -> Unit)? = null,
    issue: String? = null,
    found: Int? = null,
) {
    val c = Nx.colors
    val parsed = Colour.parseColour(value)
    var text by remember { mutableStateOf(value) }
    var bad by remember { mutableStateOf(false) }
    // a change from outside (a reset, the other editor) shows here too
    LaunchedEffect(value) {
        text = value
        bad = false
    }
    val into = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val reduced = Nx.motion.reduced
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val clear = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    LaunchedEffect(found) {
        if (found == null) return@LaunchedEffect
        // far enough to stand clear of the save bar over the bottom of the page
        into.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -clear, box.width.toFloat(), box.height + clear))
        glow.snapTo(1f)
        if (reduced) {
            kotlinx.coroutines.delay(1400)
            glow.snapTo(0f)
        } else glow.animateTo(0f, tween(1400))
    }
    fun commit(raw: String) {
        val p = Colour.parseColour(raw)
        if (p == null) {
            bad = raw.isNotBlank()
            return
        }
        bad = false
        onChange(Colour.toThemeHex(p.rgb, if (alpha) p.alpha else 1.0))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(into)
            .onSizeChanged { box = it }
            .background(c.brand.copy(alpha = 0.26f * glow.value), Radii.Sm)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .ring(Radii.Sm, c.borderStrong)
                    .clip(Radii.Sm)
                    .pressable(remember { MutableInteractionSource() }) { openColourPicker(label, value, alpha, onChange) }
                    .semantics { contentDescription = t("settings.themeBuilder.pick", "name" to label) },
            ) {
                Checker(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Looks.color(value)))
            }
            Column(Modifier.weight(1f)) {
                Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(550)), color = c.text)
                if (name != null) Txt(name, style = Nx.type.code.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted)
                if (hint != null) Txt(hint, style = Nx.type.hint, color = c.textMuted)
                if (issue != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon("TriangleAlert", c.textWarning, size = 13.dp)
                    Txt(issue, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.textWarning)
                }
            }
        }
        // on a phone the hex code and the way back sit on their own row under the name
        Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NxTextField(
                text,
                {
                    text = it
                    // taken as soon as it is a whole colour, so the preview follows the typing
                    if (Regex("^#?([0-9a-f]{6}|[0-9a-f]{8})$", RegexOption.IGNORE_CASE).matches(it.trim())) commit(it)
                },
                modifier = Modifier.width(132.dp).semantics { contentDescription = t("settings.themeBuilder.hexFor", "name" to label) },
                error = if (bad) "" else null,
                mono = true,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                onIme = {
                    commit(text)
                    if (Colour.parseColour(text) == null) text = value
                },
            )
            if (onReset != null && changed) fish.nexa.ui.components.IconButton("RotateCcw", t("settings.themeBuilder.resetNamed", "name" to label), onReset, tint = c.textMuted, size = 40.dp, iconSize = 16.dp)
        }
        if (alpha && parsed != null) {
            val pct = Colour.jsRound(parsed.alpha * 100).toInt()
            Column(Modifier.padding(start = 56.dp)) {
                Txt(t("settings.themeBuilder.opacity", "value" to pct), style = Nx.type.hint, color = c.textMuted)
                NxSlider(pct.toFloat(), { v ->
                    val next = v.toInt().coerceIn(0, 100)
                    if (next != pct) onChange(Colour.toThemeHex(parsed.rgb, next / 100.0))
                }, 0f..100f, modifier = Modifier.heightIn(min = 44.dp), label = t("settings.themeBuilder.opacityOf", "name" to label))
            }
        }
    }
}

// ---- saving and deleting ----

private fun saveTheme(build: ThemeBuild, customs: List<CustomTheme>) {
    val d = build.draft
    val next = d.copy(name = d.name.trim().take(CustomThemes.MAX_NAME).ifEmpty { t("settings.themeBuilder.untitled") })
    val list = if (customs.any { it.id == next.id }) customs.map { if (it.id == next.id) next else it } else customs + next
    // saving is choosing: the theme on screen stays on screen
    Nexa.settings.patch(buildJsonObject {
        put("customThemes", JsonArray(list.map { CustomThemes.toJson(it) }))
        put("theme", next.id)
    })
    build.replace(next)
    build.saved = next
    build.fresh = false
    Nexa.ui.toast(t("settings.themeBuilder.saved"), "success")
}

private fun deleteTheme(build: ThemeBuild, customs: List<CustomTheme>) {
    val saved = build.saved
    fish.nexa.ui.shell.confirm(
        t("settings.themeBuilder.deleteTitle", "name" to saved.name.ifEmpty { t("settings.themeBuilder.untitled") }),
        t("settings.themeBuilder.deleteBody"),
        t("settings.themeBuilder.delete"),
    ) {
        val doc = Nexa.settings.doc.value
        val gone = saved.id
        val pair = doc.obj("systemThemes")
        val pairDark = pair?.str("dark") ?: "dark"
        val pairLight = pair?.str("light") ?: "light"
        Nexa.settings.patch(buildJsonObject {
            put("customThemes", JsonArray(customs.filter { it.id != gone }.map { CustomThemes.toJson(it) }))
            if (doc.str("theme") == gone) put("theme", "dark")
            if (pairDark == gone || pairLight == gone) put("systemThemes", buildJsonObject {
                put("dark", if (pairDark == gone) "dark" else pairDark)
                put("light", if (pairLight == gone) "light" else pairLight)
            })
        })
        // nothing is left to be unsaved about
        build.saved = build.draft
        build.fresh = false
        ThemeEditing.close()
    }
}

/** The builder's save bar, over the bottom of the Appearance page while a theme is being made or changed. */
@Composable
fun BoxScope.ThemeBuilderSaveBar() {
    val build = ThemeEditing.build
    val customs = customThemes(settingsDoc())
    SaveBar(
        dirty = build?.dirty == true,
        saving = false,
        onReset = {
            build?.let {
                if (it.fresh) ThemeEditing.close()
                else {
                    it.endTune()
                    it.draft = it.saved
                }
            }
        },
        onSave = { build?.let { saveTheme(it, customs) } },
        text = if (build?.fresh == true) t("settings.themeBuilder.unsavedNew") else t("settings.themeBuilder.unsaved"),
        resetLabel = if (build?.fresh == true) t("settings.themeBuilder.discard") else t("settings.themeBuilder.reset"),
        saveLabel = t("settings.themeBuilder.save"),
    )
}

// ---- Import Theme ----

/** A theme someone shared, pasted or from its file; it opens in the builder first, unsaved. */
fun openImportSheet() {
    val ctl = SheetCtl(t("settings.themeBuilder.importTitle"))
    var text by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    fun go(raw: String) {
        if (raw.isBlank()) return
        when (val r = CustomThemes.import(raw, t("settings.themeBuilder.importedName"))) {
            is CustomThemes.Imported.Ok -> {
                ctl.close()
                ThemeEditing.open(r.theme, isNew = true)
            }
            is CustomThemes.Imported.Failed -> error = when (r.error) {
                "notJson" -> t("settings.themeBuilder.importNotJson")
                "newer" -> t("settings.themeBuilder.importNewer")
                else -> t("settings.themeBuilder.importNotTheme")
            }
        }
    }
    openSettingsSheet("theme-import", ctl, footer = {
        NxButton(t("look.common.cancel"), { it.close() }, kind = ButtonKind.GHOST)
        NxButton(t("settings.themeBuilder.importGo"), { go(text) }, enabled = text.isNotBlank())
    }) {
        val ctx = LocalContext.current
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { readAtMost(it, 256 * 1024 + 1) } }.getOrNull()
            when {
                bytes == null -> error = t("settings.themeBuilder.importNotTheme")
                bytes.size > 256 * 1024 -> error = t("settings.themeBuilder.importTooBig")
                else -> {
                    text = bytes.decodeToString()
                    error = null
                    go(text)
                }
            }
        }
        Hint(t("settings.themeBuilder.importHint"))
        NxTextField(
            text,
            {
                text = it
                error = null
            },
            label = t("settings.themeBuilder.importPaste"),
            error = error,
            singleLine = false,
            minLines = 8,
            mono = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Default,
        )
        NxButton(t("settings.themeBuilder.importFile"), { pick.launch("*/*") }, kind = ButtonKind.SECONDARY, icon = "FileUp")
    }
}

/** Up to [limit] bytes of a stream: enough to tell a theme from a file far too big to be one. */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
