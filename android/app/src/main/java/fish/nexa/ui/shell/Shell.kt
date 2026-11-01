package fish.nexa.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.net.Gateway
import fish.nexa.ui.components.Txt
import fish.nexa.ui.people.FriendsPage
import fish.nexa.ui.chat.ServerPage
import fish.nexa.ui.chat.DmPage
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.LayerHost
import fish.nexa.ui.layers.SCRIM_BLUR
import fish.nexa.ui.layers.blurredBy
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The signed-in app on a phone (web AppShell, phone branch): one page at a
 * time, the navigation drawer over it from the start side, the member list
 * from the end side, and the layer stack over everything.
 */
/** The rail and sidebar pinned beside the page on a tablet (the web's 72 + 240, with the panel's margin). */
private val SIDE_W = 328.dp

/** The member list beside the chat (MemberList.module.css). */
private val MEMBERS_W = 248.dp

@Composable
fun MainShell() {
    val c = Nx.colors
    val route = Nav.route
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current.density
    val reduced = Nx.motion.reduced
    val ease = Nx.motion.easeOut

    // Android 13 asks before an app may notify: once, when the signed-in app first shows
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val prefs = Nexa.app.getSharedPreferences("notify", android.content.Context.MODE_PRIVATE)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(Nexa.app, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted || prefs.getBoolean("asked", false)) return@LaunchedEffect
        prefs.edit().putBoolean("asked", true).apply()
        askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // the Terms first (per device, as on the web), then What's New once per update, when nothing else is open
    LaunchedEffect(Unit) {
        fish.nexa.ui.legal.Legal.gateIfNeeded()
        fish.nexa.ui.WhatsNew.maybeShow()
        fish.nexa.ui.support.maybePrompt()
    }

    // A drawer dragged or opened over the chat puts the keyboard away with it.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val putKeyboardAway = {
        keyboard?.hide()
        focus.clearFocus()
    }
    LaunchedEffect(Nav.leftOpen, Nav.rightOpen) { if (Nav.leftOpen || Nav.rightOpen) putKeyboardAway() }

    // How far the drawer is open, 0..1; follows the finger during a swipe.
    val drawer = remember { Animatable(0f) }
    val members = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(Nav.leftOpen) {
        if (!dragging) drawer.animateTo(if (Nav.leftOpen) 1f else 0f, tween(if (reduced) 0 else 260, easing = ease))
    }
    LaunchedEffect(Nav.rightOpen) {
        members.animateTo(if (Nav.rightOpen) 1f else 0f, tween(if (reduced) 0 else 250, easing = ease))
    }
    // a changed identity key is asked about as soon as it is seen (stores/keyAlerts.ts)
    LaunchedEffect(Unit) { fish.nexa.ui.profile.KeyDialogs.install() }

    // Back: the topmost layer, then the member drawer, then the drawer, then history.
    BackHandler(enabled = !Layers.isEmpty || Nav.rightOpen || Nav.leftOpen || Nav.canGoBack) {
        when {
            Layers.closeTop() -> Unit
            Nav.rightOpen -> Nav.rightOpen = false
            Nav.leftOpen -> Nav.leftOpen = false
            else -> Nav.back()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bgRaised)) {
        val screenW = maxWidth
        val drawerW: Dp = (screenW - 56.dp).coerceIn(240.dp, 420.dp)
        val drawerPx = drawerW.value * density
        val dir = if (rtl) -1f else 1f
        // a tablet keeps the sidebar beside the page as the web does from 768 px, and the member list from 1100
        val wide = screenW >= 768.dp
        val roomy = screenW >= 1100.dp
        LaunchedEffect(wide, roomy) {
            Nav.wide = wide
            Nav.roomy = roomy
            if (wide) Nav.leftOpen = false
            if (roomy) Nav.rightOpen = false
        }

        // The one horizontal-swipe recogniser over the whole page (AppShell touch handlers).
        val swipe = Modifier.pointerInput(rtl, drawerPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!Layers.isEmpty || Nav.roomy) return@awaitEachGesture
                var total = Offset.Zero
                var decided: Boolean? = null
                val t0 = System.currentTimeMillis()
                val wasOpen = Nav.leftOpen
                val membersOpen = Nav.rightOpen
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    // the lift can carry the last stretch of the swipe
                    total += ch.positionChange()
                    if (!ch.pressed) break
                    if (decided == null) {
                        if (abs(total.x) >= 12 * density || abs(total.y) >= 12 * density) {
                            decided = abs(total.x) > 1.3f * abs(total.y)
                            if (decided == false) return@awaitEachGesture
                            // a swipe inside something that scrolls sideways belongs to that thing
                            if (fish.nexa.ui.chat.TouchClaims.sideways) return@awaitEachGesture
                            // a swipe against the drawer's direction that starts on a message replies to it
                            if (!wasOpen && !membersOpen && total.x * dir < 0 && fish.nexa.ui.chat.TouchClaims.onMessageRow) return@awaitEachGesture
                            putKeyboardAway()
                        } else continue
                    }
                    ch.consume()
                    val dx = total.x * dir
                    if (!membersOpen && !Nav.wide) {
                        dragging = true
                        val off = ((if (wasOpen) drawerPx else 0f) + dx).coerceIn(0f, drawerPx)
                        scope.launch { drawer.snapTo(off / drawerPx) }
                    }
                }
                val dx = total.x * dir
                val elapsed = max(1L, System.currentTimeMillis() - t0)
                val speed = dx / density / elapsed
                dragging = false
                if (decided != true) return@awaitEachGesture
                if (membersOpen && !wasOpen) {
                    if (dx > 60 * density || speed > 0.4f) Nav.rightOpen = false
                    return@awaitEachGesture
                }
                if (Nav.wide) {
                    if (dx < -60 * density && Nav.hasMembers) Nav.rightOpen = true
                    return@awaitEachGesture
                }
                // a slow drag settles toward the side it was dragged to once it has gone 40% of the way
                val open = if (abs(speed) > 0.4f) dx > 0 else if (wasOpen) drawer.value > 0.6f else drawer.value > 0.4f
                if (open) {
                    Nav.leftOpen = true
                    scope.launch { drawer.animateTo(1f, tween(if (reduced) 0 else 200, easing = ease)) }
                } else {
                    // only a page with members has a member drawer: a server channel, or a group
                    if (!wasOpen && dx < -60 * density && Nav.hasMembers) Nav.rightOpen = true
                    Nav.leftOpen = false
                    scope.launch { drawer.animateTo(0f, tween(if (reduced) 0 else 200, easing = ease)) }
                }
            }
        }

        // On the whole stack, not the page: an open drawer and its scrim sit above the page, and a
        // swipe that starts on them has to close them.
        Box(Modifier.fillMaxSize().then(swipe)) {
        Row(Modifier.fillMaxSize()) {
        if (wide) {
            Box(Modifier.width(SIDE_W).fillMaxHeight().blurredBy { SCRIM_BLUR * Layers.dim }) { NavDrawer(open = true) }
        }
        // what is behind a dialog or sheet blurs, and a little behind an open drawer (AppShell.module.css)
        Column(Modifier.weight(1f).fillMaxHeight().blurredBy { maxOf(SCRIM_BLUR * Layers.dim, 2.dp * maxOf(drawer.value, members.value)) }.statusBarsPadding()) {
            ConnectionBanner()
            MaintenanceBanner()
            ViewAsBanner()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (route) {
                    is Route.Friends -> FriendsPage()
                    is Route.Server -> ServerPage(route)
                    is Route.Dm -> DmPage(route)
                    is Route.NexaConvo -> fish.nexa.ui.nexa.NexaPage()
                    is Route.NexaCase -> fish.nexa.ui.nexa.NexaCasePage(route.caseId)
                    is Route.NexaBug -> fish.nexa.ui.nexa.NexaBugPage(route.reportId)
                    is Route.Notes -> fish.nexa.ui.notes.NotesPage(route)
                    is Route.Support -> fish.nexa.ui.support.SupportPage()
                    is Route.Discover -> fish.nexa.ui.discovery.DiscoverPage()
                    is Route.Admin -> fish.nexa.ui.moderation.AdminPanel(route)
                }
            }
        }

        if (roomy && Nav.hasMembers && Nav.membersPinned) {
            Box(Modifier.width(MEMBERS_W).fillMaxHeight().blurredBy { SCRIM_BLUR * Layers.dim }) { MemberDrawer() }
        }
        }
        // scrim behind the drawer
        if (drawer.value > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(drawer.value)
                    .background(c.bgOverlay)
                    .pressable(remember { MutableInteractionSource() }, role = Role.Button) { Nav.leftOpen = false },
            )
        }
        // the navigation drawer: rail, list, dock
        if (!wide) Box(
            Modifier
                .width(drawerW)
                .fillMaxHeight()
                .align(if (rtl) Alignment.CenterEnd else Alignment.CenterStart)
                .graphicsLayer {
                    translationX = -dir * (1f - drawer.value) * size.width
                    alpha = if (drawer.value <= 0.001f) 0f else 1f
                }
                .blurredBy { SCRIM_BLUR * Layers.dim },
        ) {
            NavDrawer(open = drawer.value > 0.5f)
        }
        // member drawer from the end side
        if (members.value > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(members.value)
                    .background(c.bgOverlay)
                    .pressable(remember { MutableInteractionSource() }, role = Role.Button) { Nav.rightOpen = false },
            )
            Box(
                Modifier
                    .width(min(screenW.value - 56f, 320f).dp)
                    .fillMaxHeight()
                    .align(if (rtl) Alignment.CenterStart else Alignment.CenterEnd)
                    .graphicsLayer { translationX = dir * (1f - members.value) * size.width }
                    .blurredBy { SCRIM_BLUR * Layers.dim },
            ) {
                MemberDrawer()
            }
        }
        }
        LayerHost()
        // a plan offered where the free one stopped something
        fish.nexa.ui.support.UpsellHost()
        // a notice from Nexa, over everything until it is dismissed
        fish.nexa.ui.nexa.NoticePopupHost()
    }
}

/** A full-width strip saying the connection is down (AppShell connection banner). */
@Composable
private fun ConnectionBanner() {
    val c = Nx.colors
    val online by Nexa.ui.online.collectAsState()
    val stateFlow = Nexa.session.gatewayState ?: return
    val state by stateFlow.collectAsState()
    // the server's reason for refusing names which limit it was (gateway/session.ts connectionNote)
    val note = if (state.closeCode == Gateway.CLOSE_TOO_MANY) {
        val reason = state.closeReason.orEmpty()
        when {
            "address" in reason -> t("gateway.tooManyFromThisNetwork")
            "limit" in reason -> t("gateway.serverIsFull")
            else -> t("gateway.tooManyForThisAccount")
        }
    } else null
    val (bg, fg, text) = when {
        !online -> Triple(c.dangerHover, c.onDanger, t("shell.appShell.offline"))
        state.status == Gateway.Status.CONNECTING -> Triple(c.warning, c.onWarning, note ?: t("shell.requireAuth.connecting"))
        state.status == Gateway.Status.CLOSED -> Triple(c.dangerHover, c.onDanger, note ?: t("shell.appShell.disconnectedReconnecting"))
        else -> return
    }
    if (online && state.status == Gateway.Status.CONNECTING && note == null && !Nexa.session.ready.value) return
    Box(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Txt(text, style = Nx.type.label.copy(fontSize = Nx.type.sp(13f)), color = fg, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
