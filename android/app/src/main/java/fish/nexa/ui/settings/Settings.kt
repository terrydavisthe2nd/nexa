package fish.nexa.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import fish.nexa.ui.legal.Legal
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.abs

/** One page of User Settings: its id (as the web names it), label, icon and group. */
data class SettingsPageInfo(val id: String, val label: String, val icon: String)
data class SettingsGroup(val head: String?, val pages: List<SettingsPageInfo>)

/**
 * User Settings (SettingsModal.tsx + SettingsLayer.tsx on a phone): a
 * full-screen layer with the list of pages first and each page pushed over
 * it, the back gesture and a swipe toward the reading end going back one
 * level, and pages with unsaved changes refusing to be left.
 */
object Settings {
    /** Opens User Settings: on the list, or on [page] scrolled to [section] (a setting's anchor). */
    fun open(page: String? = null, section: String? = null) {
        val start = when (page) {
            null -> null
            "privacy-data" -> "data"
            else -> page
        }
        Unsaved.clear()
        lateinit var layer: Layer
        layer = Layer("settings", canClose = { if (Unsaved.blocking) { Unsaved.unlessUnsaved {}; false } else true }) { close ->
            SettingsLayer(layer, start, section?.let { anchorAlias(it) }, close)
        }
        Layers.open(layer)
    }

    /** The web's anchors, with the few names other parts of this app use for them. */
    private fun anchorAlias(a: String) = when (a) {
        "typing" -> "typingStyle"
        else -> a
    }

    fun groups(): List<SettingsGroup> {
        val admin = (Nexa.users.me.value?.flags ?: 0L) and 1L != 0L
        val list = mutableListOf(
            SettingsGroup(
                t("settings.groups.account"),
                listOf(
                    SettingsPageInfo("account", t("settings.account.myAccount"), "UserRound"),
                    SettingsPageInfo("profiles", t("settings.profile.profiles"), "IdCard"),
                    SettingsPageInfo("security", t("settings.security.security"), "KeyRound"),
                    SettingsPageInfo("devices", t("settings.devices.devices"), "MonitorSmartphone"),
                    SettingsPageInfo("subscription", t("support.settingsLabel"), "Heart"),
                ),
            ),
            SettingsGroup(
                t("settings.privacy.privacySafety"),
                listOf(
                    SettingsPageInfo("privacy", t("settings.privacy.title"), "ShieldCheck"),
                    SettingsPageInfo("blocked", t("people.blockedPage.title"), "Ban"),
                    SettingsPageInfo("authorizedApps", t("apps.authorized.title"), "AppWindow"),
                    SettingsPageInfo("data", t("settings.privacy.yourData"), "DatabaseZap"),
                ),
            ),
            SettingsGroup(
                t("settings.groups.chat"),
                listOf(
                    SettingsPageInfo("chat", t("settings.chat.title"), "MessagesSquare"),
                    SettingsPageInfo("notifications", t("settings.notifications.notifications"), "Bell"),
                    SettingsPageInfo("sounds", t("settings.sounds.title"), "AudioLines"),
                    SettingsPageInfo("stickers", t("settings.groups.emojiStickers"), "Sticker"),
                ),
            ),
            SettingsGroup(
                t("settings.groups.look"),
                listOf(
                    SettingsPageInfo("appearance", t("settings.appearance.appearance"), "Palette"),
                    SettingsPageInfo("accessibility", t("settings.appearance.accessibility"), "PersonStanding"),
                    SettingsPageInfo("language", t("settings.appearance.language"), "Languages"),
                ),
            ),
            SettingsGroup(
                t("settings.groups.app"),
                listOf(
                    SettingsPageInfo("voice", t("settings.voice.voiceVideo"), "Mic"),
                    SettingsPageInfo("keybinds", t("keybinds.title"), "Keyboard"),
                    SettingsPageInfo("activity", t("settings.activity.title"), "Gamepad2"),
                    SettingsPageInfo("advanced", t("settings.privacy.advanced"), "Wrench"),
                    SettingsPageInfo("developers", t("apps.portal.title"), "Bot"),
                ),
            ),
            SettingsGroup(
                t("settings.groups.help"),
                listOf(
                    SettingsPageInfo("about", t("settings.about.title"), "Info"),
                    SettingsPageInfo("licenses", t("settings.licenses.licenses"), "Scale"),
                ),
            ),
        )
        if (admin) list += SettingsGroup(t("settings.settings.administration"), listOf(SettingsPageInfo("admin", t("settings.admin.nexaAdmin"), "Shield")))
        return list
    }

    fun page(id: String?): SettingsPageInfo? = groups().flatMap { it.pages }.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------
// Anchors: a setting a search or a link goes to is scrolled into view and lit.

class AnchorHost(val scroll: ScrollState) {
    var content: LayoutCoordinates? = null
    var viewportHeight = 0
    val positions = HashMap<String, LayoutCoordinates>()
    var found by mutableStateOf<String?>(null)
    var foundAt by mutableLongStateOf(0L)

    /** A level inside the page (the Theme Builder over Appearance): back and the swipe close it before the page. */
    var inner by mutableStateOf<(() -> Unit)?>(null)
}

val LocalAnchors = compositionLocalOf<AnchorHost?> { null }

/** Marks a setting (`data-setting`) so search results and links can find it; lit for a moment when found. */
fun Modifier.settingAnchor(id: String?): Modifier = if (id == null) this else composed {
    val host = LocalAnchors.current ?: return@composed this
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    val glow = remember { Animatable(0f) }
    LaunchedEffect(host.found, host.foundAt) {
        if (host.found != id) return@LaunchedEffect
        glow.snapTo(1f)
        if (reduced) {
            delay(1800); glow.snapTo(0f)
        } else {
            delay(480)
            glow.animateTo(0f, tween(1120, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        }
    }
    val shape = Radii.Sm
    this
        .onGloballyPositioned { host.positions[id] = it }
        .then(
            if (glow.value > 0f) Modifier
                .boxShadow(shape, 0f, 24f, c.brand.copy(alpha = 0.7f * glow.value), spread = -4f)
                .ring(shape, c.brand.copy(alpha = 0.55f * glow.value), 3.dp)
                .background(c.brand.copy(alpha = 0.12f * glow.value), shape)
            else Modifier,
        )
}

// ---------------------------------------------------------------------------

@Composable
private fun SettingsLayer(layer: Layer, startPage: String?, startAnchor: String?, close: () -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    var page by remember { mutableStateOf(startPage?.takeIf { Settings.page(it) != null }) }
    var anchor by remember { mutableStateOf(startAnchor) }
    var query by remember { mutableStateOf("") }
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(200, easing = ease)) }
    layer.dismiss = {
        scope.launch {
            if (!reduced) enter.animateTo(0f, tween(150, easing = ease))
            Layers.remove(layer)
        }
    }
    val isTop = Layers.stack.lastOrNull() === layer
    // from 768 dp the list stays beside the page, as on the web, with My Account open first
    val wide = fish.nexa.ui.nav.Nav.wide
    BackHandler(enabled = isTop) {
        if (page != null && !wide) Unsaved.unlessUnsaved { page = null } else Unsaved.unlessUnsaved { close() }
    }
    fun openPage(id: String, at: String?) {
        query = ""
        anchor = at
        page = id
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                val s = 1.015f - 0.015f * enter.value
                scaleX = s; scaleY = s
            }
            .background(c.bgRaised)
            // being a pointer target keeps touches off the app underneath
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final) } }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val current = page
        if (wide) Row(Modifier.fillMaxSize()) {
            val shown = current ?: "account"
            Box(Modifier.width(300.dp).fillMaxHeight().background(c.bgBase)) {
                SettingsList(query, { query = it }, onOpen = { id, at -> Unsaved.unlessUnsaved { openPage(id, at) } }, onClose = { Unsaved.unlessUnsaved { close() } }, selected = shown)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                key(shown) {
                    PageView(
                        shown,
                        anchor,
                        isTop = isTop,
                        onAnchorDone = { anchor = null },
                        onBack = null,
                        onClose = { Unsaved.unlessUnsaved { close() } },
                        openPage = ::openPage,
                    )
                }
            }
        } else if (current == null) SettingsList(query, { query = it }, onOpen = ::openPage, onClose = { Unsaved.unlessUnsaved { close() } })
        else key(current) {
            PageView(
                current,
                anchor,
                isTop = isTop,
                onAnchorDone = { anchor = null },
                onBack = { page = null },
                onClose = { Unsaved.unlessUnsaved { close() } },
                openPage = ::openPage,
            )
        }
    }
}

/** The top bar: the title on the list; back to User Settings on a page; the close button at the end. */
@Composable
private fun TopBar(onBack: (() -> Unit)?, onClose: (() -> Unit)?, title: Boolean = true) {
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
        if (onBack == null && !title) Spacer(Modifier.weight(1f))
        else if (onBack == null) {
            Txt(
                t("settings.settings.userSettings"),
                style = Nx.type.title.copy(fontSize = Nx.type.sp(19f), lineHeight = Nx.type.sp(24f), fontWeight = FontWeight(690)),
                color = c.textStrong,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
            )
        } else {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                Modifier
                    .height(44.dp)
                    .clip(Radii.Xs)
                    .background(if (pressed) c.bgHover else Color.Transparent)
                    .pressable(interaction, onClick = onBack!!)
                    .semantics { contentDescription = t("server.settingsLayer.backTo", "name" to t("settings.settings.userSettings")) }
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(if (rtl) "ChevronRight" else "ChevronLeft", c.textStrong, size = 22.dp)
                Txt(t("settings.settings.userSettings"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
        }
        if (onClose != null) Box(
            Modifier
                .size(44.dp)
                .pressable(remember { MutableInteractionSource() }, onClick = onClose)
                .semantics { contentDescription = t("server.settingsLayer.closeSettings") },
            contentAlignment = Alignment.Center,
        ) { Icon("X", c.textMuted, size = 22.dp) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}

/** The list: a search box, then the groups as cards of rows, then Report a Bug and Log Out. */
@Composable
private fun SettingsList(query: String, setQuery: (String) -> Unit, onOpen: (String, String?) -> Unit, onClose: () -> Unit, selected: String? = null) {
    val c = Nx.colors
    val context = LocalContext.current
    val me by Nexa.users.me.collectAsState()
    val groups = remember(me?.flags, fish.nexa.ui.I18n.tag) { Settings.groups() }
    Column(Modifier.fillMaxSize()) {
        TopBar(onBack = null, onClose = if (selected == null) onClose else null)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                SearchField(query, setQuery) {
                    val first = searchSettings(groups, query).firstOrNull()
                    if (first != null) onOpen(first.page, first.anchor)
                }
                if (query.isNotBlank()) {
                    val results = searchSettings(groups, query)
                    if (results.isEmpty()) {
                        Txt(t("settings.search.none", "query" to query.trim()), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.textMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    } else NavCard {
                        results.forEachIndexed { i, r ->
                            val info = groups.flatMap { it.pages }.first { it.id == r.page }
                            NavRow(info.icon, r.label, sub = if (r.anchor != null) info.label else null, first = i == 0) { onOpen(r.page, r.anchor) }
                        }
                    }
                } else {
                    for (g in groups) {
                        if (g.head != null) Txt(
                            g.head,
                            style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700), letterSpacing = androidx.compose.ui.unit.TextUnit(0.01f, androidx.compose.ui.unit.TextUnitType.Em)),
                            color = c.textMuted,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
                        ) else Spacer(Modifier.height(20.dp))
                        NavCard {
                            g.pages.forEachIndexed { i, p -> NavRow(p.icon, p.label, first = i == 0, selected = p.id == selected, chevron = selected == null) { onOpen(p.id, null) } }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    NavCard {
                        NavRow("CodeXml", t("project.github"), first = true, chevron = false) { Legal.openGitHub(context) }
                        NavRow("Flag", t("reports.report"), chevron = false) { fish.nexa.ui.reports.openReport() }
                        NavRow("Bug", t("bugs.report"), chevron = false) { fish.nexa.ui.bugs.openBugReport("settings") }
                        NavRow("LogOut", t("settings.settings.logOut"), danger = true, chevron = false) { fish.nexa.ui.shell.confirmLogout() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, setQuery: (String) -> Unit, onEnter: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Sm
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .heightIn(min = 44.dp)
            .ring(shape, c.borderSubtle)
            .sunken(shape, c)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon("Search", c.textMuted, size = 18.dp)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Txt(t("settings.search.placeholder"), style = Nx.type.body, color = c.textPlaceholder, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = setQuery,
                singleLine = true,
                textStyle = Nx.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onEnter() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = t("settings.search.placeholder") },
            )
        }
        if (query.isNotEmpty()) Box(
            Modifier.size(40.dp).pressable(remember { MutableInteractionSource() }) { setQuery("") }.semantics { contentDescription = t("composer.composer.dismiss") },
            contentAlignment = Alignment.Center,
        ) { Icon("X", c.textMuted, size = 16.dp) }
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
private fun NavRow(icon: String, label: String, sub: String? = null, first: Boolean = false, danger: Boolean = false, chevron: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
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
        val tile = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)
        Box(
            Modifier
                .size(30.dp)
                .background(if (danger) c.danger.copy(alpha = 0.12f) else c.textStrong.copy(alpha = 0.05f), tile),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, if (danger) c.textDanger else if (selected) c.textBrand else c.icon, size = 18.dp) }
        Column(Modifier.weight(1f)) {
            Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(550), lineHeight = Nx.type.sp(18.75f)), color = if (danger) c.textDanger else if (selected) c.textStrong else c.text)
            if (sub != null) Txt(sub, style = Nx.type.hint.copy(fontWeight = FontWeight(500)), color = c.textMuted)
        }
        if (chevron) Icon(if (rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 18.dp)
    }
}

/**
 * A page, pushed over the list: the top bar with the way back, then the
 * page itself, scrolling, with room under its last setting. A drag toward
 * the reading end takes it back to the list.
 */
@Composable
private fun PageView(
    id: String,
    anchor: String?,
    isTop: Boolean,
    onAnchorDone: () -> Unit,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    openPage: (String, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = ease)) }
    val drag = remember { Animatable(0f) }
    val scroll = rememberScrollState()
    val host = remember { AnchorHost(scroll) }
    val back = { Unsaved.unlessUnsaved { host.inner?.invoke() ?: onBack?.invoke() } }
    BackHandler(enabled = isTop && host.inner != null) { Unsaved.unlessUnsaved { host.inner?.invoke() } }

    // a search result or link: bring the setting into view (it may take a moment to appear) and light it
    LaunchedEffect(anchor) {
        val a = anchor ?: return@LaunchedEffect
        val until = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < until) {
            val target = host.positions[a]
            val content = host.content
            if (target != null && content != null && target.isAttached && content.isAttached) {
                val y = content.localPositionOf(target, Offset.Zero).y
                val goal = (y - host.viewportHeight / 2f + target.size.height / 2f).toInt().coerceIn(0, scroll.maxValue)
                if (reduced) scroll.scrollTo(goal) else scroll.animateScrollTo(goal)
                host.found = a
                host.foundAt = System.currentTimeMillis()
                break
            }
            delay(16)
        }
        onAnchorDone()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val dir = if (rtl) -1f else 1f
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = drag.value
                    alpha = (if (drag.value != 0f) maxOf(0.4f, 1f - abs(drag.value) / (600f * density)) else 1f) * enter.value
                    translationY = (1f - enter.value) * 6f * density
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
                                if (total.x * dir > 0 && abs(total.x) >= 1.4f * abs(total.y) && !fish.nexa.ui.server.NoSwipe.active) taken = true else return@awaitEachGesture
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
                            val inner = host.inner
                            if (done && !Unsaved.blocking && inner != null) {
                                drag.animateTo(0f, tween(if (reduced) 0 else 200, easing = ease))
                                inner()
                            } else if (done && !Unsaved.blocking) {
                                drag.animateTo(dir * widthPx, tween(if (reduced) 0 else 160, easing = ease))
                                onBack?.invoke()
                            } else {
                                if (done) Unsaved.unlessUnsaved {}
                                drag.animateTo(0f, tween(if (reduced) 0 else 200, easing = ease))
                            }
                        }
                    }
                },
        ) {
            TopBar(onBack = if (onBack != null || host.inner != null) back else null, onClose = onClose, title = onBack != null)
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { host.viewportHeight = it.size.height }) {
                CompositionLocalProvider(LocalAnchors provides host) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .onGloballyPositioned { host.content = it }
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                    ) {
                        SettingsPageContent(id, anchor, openPage)
                    }
                    // pages with a save bar draw it over the bottom of the page
                    PageOverlay(id)
                }
            }
        }
    }
}

/** What each page id draws. */
@Composable
private fun SettingsPageContent(id: String, anchor: String?, openPage: (String, String?) -> Unit) {
    when (id) {
        "account" -> AccountPage(openPage)
        "profiles" -> ProfilesPage(anchor)
        "security" -> SecurityPage()
        "devices" -> DevicesPage()
        "subscription" -> fish.nexa.ui.support.SupportPanel(inSettings = true)
        "privacy" -> PrivacyPage()
        "blocked" -> BlockedPage()
        "data" -> DataPage(openPage)
        "chat" -> ChatSettingsPage()
        "notifications" -> NotificationsPage(openPage)
        "sounds" -> SoundsPage()
        "stickers" -> StickersPage()
        "appearance" -> AppearancePage()
        "accessibility" -> AccessibilityPage(openPage)
        "language" -> LanguagePage()
        "voice" -> VoicePage()
        "keybinds" -> KeybindsPage(openPage)
        "activity" -> ActivityPage()
        "advanced" -> AdvancedPage()
        "developers" -> DeveloperPortalPage()
        "authorizedApps" -> AuthorizedAppsPage()
        "about" -> AboutPage()
        "licenses" -> LicensesPage()
        "admin" -> AdminPage()
        else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
    }
}

/** Pages whose form has an explicit Save put their bar here, over the page's bottom. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageOverlay(id: String) {
    when (id) {
        "profiles" -> {
            ProfilesPreviewBar(LocalAnchors.current)
            ProfilesSaveBar()
        }
        "appearance" -> ThemeBuilderSaveBar()
        "stickers" -> PackSaveBar()
        "developers" -> PortalSaveBar()
    }
}

// ---------------------------------------------------------------------------
// Search (settingsIndex.ts): every page by its label, plus the settings inside them.

data class SettingResult(val page: String, val anchor: String?, val label: String)

private fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()

private fun settingsIndex(groups: List<SettingsGroup>): List<SettingResult> {
    val shown = groups.flatMap { g -> g.pages.map { it.id } }.toSet()
    val entries = ArrayList<SettingResult>()
    for (g in groups) for (p in g.pages) entries += SettingResult(p.id, null, p.label)
    val anchored = listOf(
        Triple("account", "account", "settings.account.username"),
        Triple("account", "account", "settings.account.displayName"),
        Triple("account", "password", "settings.account.changePassword"),
        Triple("account", "password", "settings.account.recoveryKey"),
        Triple("account", "deleteAccount", "settings.account.deleteAccount"),
        Triple("profiles", "avatar", "settings.profile.avatar"),
        Triple("profiles", "banner", "settings.profile.profileBanner"),
        Triple("profiles", "profileTheme", "settings.profile.profileTheme"),
        Triple("profiles", "profile", "settings.profile.pronouns"),
        Triple("profiles", "profile", "settings.profile.aboutMe"),
        Triple("profiles", "typingStyle", "typing.editor.title"),
        Triple("profiles", "nameStyle", "nameStyle.title"),
        Triple("profiles", "widgets", "widgets.title"),
        Triple("profiles", "widgets", "widgets.group.music"),
        Triple("profiles", "widgets", "widgets.type.artists"),
        Triple("profiles", "widgets", "widgets.type.albums"),
        Triple("profiles", "widgets", "widgets.type.songs"),
        Triple("profiles", "serverTag", "serverTag.choose.title"),
        Triple("security", "twoFactor", "settings.security.twoFactorAuthentication"),
        Triple("security", "twoFactor", "settings.security.backupCodes"),
        Triple("security", "appLock", "settings.security.appLock"),
        Triple("security", "appLock", "settings.security.setAPasscode"),
        Triple("security", "appLock", "settings.security.lockAfterInactivity"),
        Triple("security", "appLock", "settings.security.lockWhenTheAppLosesFocus"),
        Triple("devices", "sessions", "settings.devices.logOutAllOtherDevices"),
        Triple("subscription", "subscription", "support.manage"),
        Triple("subscription", "subscription", "support.yourStorage"),
        Triple("subscription", "subscription", "support.boosts.title"),
        Triple("privacy", "dmPrivacy", "settings.privacy.directMessages"),
        Triple("privacy", "groupAdd", "settings.privacy.letFriendsAddMe"),
        Triple("privacy", "encryption", "server.encryption.encryption"),
        Triple("privacy", "encryption", "settings.privacy.search"),
        Triple("blocked", "blockedMessages", "people.block.hideMessages"),
        Triple("data", "export", "settings.privacy.exportMyData"),
        Triple("chat", "messageDisplay", "settings.appearance.messageDisplay"),
        Triple("chat", "linkPreviews", "settings.privacy.fetchLinkPreviewsWhenISend"),
        Triple("chat", "imagePreviews", "settings.privacy.showEmbeddedImagesAndGifsFrom"),
        Triple("chat", "spellcheck", "settings.appearance.spellCheck"),
        Triple("chat", "spellcheck", "settings.appearance.yourWords"),
        Triple("chat", "smartEmoji", "settings.appearance.smartEmoji"),
        Triple("chat", "tts", "settings.notifications.readTtsAloud"),
        Triple("chat", "sendTyping", "settings.chat.sendTyping"),
        Triple("chat", "customTyping", "settings.chat.customTyping"),
        Triple("chat", "typingAvatars", "settings.chat.typingAvatars"),
        Triple("chat", "typingChannels", "settings.chat.typingChannels"),
        Triple("stickers", "packs", "modals.customStatus.emoji"),
        Triple("notifications", "desktopNotifications", "android.settings.notifications.show"),
        Triple("notifications", "sounds", "settings.notifications.sounds"),
        Triple("notifications", "notificationPreview", "settings.notifications.previewHeading"),
        Triple("notifications", "background", "android.settings.notifications.background"),
        Triple("sounds", "soundPack", "settings.sounds.packHeading"),
        Triple("sounds", "soundPack", "settings.sounds.createPack"),
        Triple("sounds", "soundVolume", "settings.sounds.volume"),
        Triple("appearance", "theme", "settings.appearance.theme"),
        Triple("appearance", "animateAvatars", "settings.appearance.animateAvatars"),
        Triple("appearance", "animateIcons", "settings.appearance.animateIcons"),
        Triple("appearance", "animateBanners", "settings.appearance.animateBanners"),
        Triple("appearance", "animateEmoji", "settings.appearance.animateEmoji"),
        Triple("appearance", "animateStickers", "settings.appearance.animateStickers"),
        Triple("appearance", "animateNames", "settings.appearance.animateNames"),

        Triple("appearance", "accent", "settings.appearance.accentColour"),
        Triple("appearance", "font", "settings.appearance.font"),
        Triple("appearance", "textSize", "settings.appearance.textSize"),
        Triple("accessibility", "reducedMotion", "settings.appearance.reducedMotion"),
        Triple("accessibility", "underlineLinks", "settings.appearance.alwaysUnderlineLinks"),
        Triple("voice", "devices", "settings.voice.outputDevice"),
        Triple("voice", "devices", "settings.voice.micTest"),
        Triple("voice", "inputMode", "settings.voice.inputMode"),
        Triple("voice", "noiseSuppression", "voice.noise.title"),
        Triple("voice", "noiseSuppression", "voice.noise.enhanced"),
        Triple("voice", "voiceProcessing", "settings.voice.echoCancellation"),
        Triple("voice", "voiceProcessing", "settings.voice.automaticGainControl"),
        Triple("keybinds", "keybinds", "keybinds.actions.mute"),
        Triple("keybinds", "keybinds", "keybinds.actions.deafen"),
        Triple("keybinds", "keybinds", "keybinds.actions.activity"),
        Triple("activity", "activityStatus", "settings.activity.status"),
        Triple("activity", "discord", "settings.activity.discord"),
        Triple("advanced", "developerMode", "settings.privacy.developerMode"),
        Triple("about", "version", "whatsNew.title"),
        Triple("about", "documents", "legal.termsTitle"),
        Triple("about", "documents", "legal.privacyTitle"),
        Triple("about", "contact", "settings.about.contact"),
        Triple("about", "version", "settings.about.version"),
    )
    for ((page, anchor, key) in anchored) if (page in shown) entries += SettingResult(page, anchor, t(key))
    // what a page is for, found by more than its name
    val within = listOf(
        "developers" to "apps.portal.newApp",
        "developers" to "apps.portal.token",
        "developers" to "apps.portal.connect",
        "authorizedApps" to "apps.menu.apps",
    )
    for ((page, key) in within) if (page in shown) entries += SettingResult(page, null, t(key))
    return entries
}

/** searchSettings: every word must occur; at the start scores 3, at a word start 2, inside 1; pages +0.5; top 12. */
fun searchSettings(groups: List<SettingsGroup>, query: String): List<SettingResult> {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val scored = ArrayList<Pair<SettingResult, Double>>()
    for (e in settingsIndex(groups)) {
        val label = fold(e.label)
        var score = 0.0
        var all = true
        for (w in words) {
            val at = label.indexOf(w)
            if (at < 0) {
                all = false
                break
            }
            score += when {
                at == 0 -> 3.0
                Regex("(^|\\s|[^\\p{L}\\p{N}])" + Regex.escape(w)).containsMatchIn(label) -> 2.0
                else -> 1.0
            }
        }
        if (!all) continue
        if (e.anchor == null) score += 0.5
        scored += e to score
    }
    val seen = HashSet<String>()
    return scored
        .sortedWith(compareByDescending<Pair<SettingResult, Double>> { it.second }.thenBy { it.first.label.length })
        .map { it.first }
        .filter { seen.add("${it.page}|${it.label}") }
        .take(12)
}
