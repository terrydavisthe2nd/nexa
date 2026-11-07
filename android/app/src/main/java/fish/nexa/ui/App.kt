package fish.nexa.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import fish.nexa.Nexa
import fish.nexa.data.AuthState
import fish.nexa.ui.auth.AuthFlow
import fish.nexa.ui.auth.CaptchaHost
import fish.nexa.ui.auth.LockScreen
import fish.nexa.ui.auth.OfflineScreen
import fish.nexa.ui.auth.Splash
import fish.nexa.ui.components.ToastHost
import fish.nexa.ui.shell.MainShell
import fish.nexa.ui.theme.NexaTheme
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.look.CustomTheme
import fish.nexa.ui.theme.look.CustomThemes
import fish.nexa.ui.theme.look.LookDraft
import fish.nexa.ui.theme.look.Looks
import fish.nexa.ui.theme.look.ResolvedTheme
import fish.nexa.ui.theme.look.Seasonal
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The account's look, kept on this device too (the web's ec:appearance), so
 * the sign-in screen, the passcode screen and the first frame are drawn in
 * the right theme before the encrypted settings arrive. A custom theme in use
 * is kept with it, since without the settings there is nothing to draw it from.
 */
object Appearance {
    private fun prefs() = Nexa.app.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    data class Look(
        val theme: String,
        val accent: String?,
        val fontScale: Float,
        val reducedMotion: Boolean,
        val classic: Boolean,
        val customs: List<CustomTheme> = emptyList(),
        val pairDark: String? = null,
        val pairLight: String? = null,
    )

    /** The look the settings document asks for. */
    fun of(doc: JsonObject): Look {
        val pair = doc["systemThemes"] as? JsonObject
        fun side(k: String) = (pair?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return Look(
            theme = (doc["theme"] as? JsonPrimitive)?.contentOrNull ?: "dark",
            accent = (doc["accent"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
            fontScale = ((doc["fontScale"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: 1.0).toFloat().coerceIn(0.75f, 1.5f),
            reducedMotion = (doc["reducedMotion"] as? JsonPrimitive)?.booleanOrNull ?: false,
            classic = (doc["fontFace"] as? JsonPrimitive)?.contentOrNull == "classic",
            customs = CustomThemes.sanitizeAll(doc["customThemes"]),
            pairDark = side("dark"),
            pairLight = side("light"),
        )
    }

    fun remembered(): Look = prefs().let { p ->
        val customs = p.getString("customThemes", null)?.let { runCatching { CustomThemes.sanitizeAll(Json.parseToJsonElement(it)) }.getOrNull() } ?: emptyList()
        Look(
            p.getString("theme", "dark") ?: "dark", p.getString("accent", null), p.getFloat("fontScale", 1f), p.getBoolean("reducedMotion", false), p.getBoolean("classic", false),
            customs, p.getString("pairDark", null), p.getString("pairLight", null),
        )
    }

    fun remember(l: Look) {
        // only the custom themes that could be drawn: the one chosen, or the pair Sync with System moves between
        val wanted = setOfNotNull(l.theme, l.pairDark.takeIf { l.theme == "system" }, l.pairLight.takeIf { l.theme == "system" })
        val keep = l.customs.filter { it.id in wanted }
        prefs().edit()
            .putString("theme", l.theme).putString("accent", l.accent).putFloat("fontScale", l.fontScale)
            .putBoolean("reducedMotion", l.reducedMotion).putBoolean("classic", l.classic)
            .putString("customThemes", if (keep.isEmpty()) null else JsonArray(keep.map { CustomThemes.toJson(it) }).toString())
            .putString("pairDark", l.pairDark).putString("pairLight", l.pairLight)
            .apply()
    }
}

@Composable
fun App() {
    val auth by Nexa.auth.state.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val loaded by Nexa.settings.loaded.collectAsState()
    val look = if (loaded && auth is AuthState.Ready) Appearance.of(doc) else Appearance.remembered()
    LaunchedEffect(look, loaded) { if (loaded) Appearance.remember(look) }
    // going idle when away, and Discord showing activity only while sharing is on
    LaunchedEffect(Unit) { fish.nexa.ui.settings.ActivitySync.start() }
    val locale = (doc["locale"] as? JsonPrimitive)?.contentOrNull
    LaunchedEffect(locale, loaded) {
        if (!loaded) return@LaunchedEffect
        I18n.use(locale)
        // the notification channels are named in the app's language
        fish.nexa.notify.Notifier.installChannels()
    }
    @Suppress("UNUSED_VARIABLE") val lang = I18n.tag
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val draft = LookDraft.theme
    // a season ending while the app is open is drawn as over at once
    val today by Seasonal.today.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            Nexa.ui.visible.first { it }
            Seasonal.tick()
        }
    }
    // A seasonal theme is worn only while its season lasts. Once it is over the
    // account goes back to the default theme, rather than drawing as Dark and
    // coming back by itself the next year (ThemeEffect.tsx).
    LaunchedEffect(look.theme, loaded, today) {
        if (!loaded || auth !is AuthState.Ready) return@LaunchedEffect
        val now = Seasonal.orDefault(look.theme, today)
        if (now != look.theme) Nexa.settings.set("theme", JsonPrimitive(now))
    }
    val colors = remember(look, systemDark, draft, today) { Themes.resolve(look, systemDark, draft) }
    // the status and navigation bars' icons in the theme's own scheme
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = colors.isLight
            isAppearanceLightNavigationBars = colors.isLight
        }
    }

    NexaTheme(
        colors = colors,
        fontScale = look.fontScale,
        classic = look.classic,
        reducedMotion = look.reducedMotion || Motion.systemOff(),
        rtl = I18n.rtl,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Nx.colors.bgCanvas)
                // every touch and key holds off the passcode's idle lock (watched, never taken)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            fish.nexa.data.LockTimers.activity()
                            fish.nexa.ui.settings.ActivitySync.touch()
                            fish.nexa.ui.components.Decor.touched()
                        }
                    }
                }
                .onPreviewKeyEvent {
                    fish.nexa.data.LockTimers.activity()
                    fish.nexa.ui.settings.ActivitySync.touch()
                    fish.nexa.ui.components.Decor.touched()
                    // the keybinds answer first, even inside a text box, as they do on the web
                    auth is AuthState.Ready && fish.nexa.ui.settings.Keybinds.handle(it)
                },
        ) {
            when (val s = auth) {
                AuthState.Loading -> Splash()
                AuthState.Anonymous -> AuthFlow()
                is AuthState.Locked -> LockScreen(s)
                is AuthState.PinLocked -> fish.nexa.ui.auth.PasscodeScreen(s)
                is AuthState.Offline -> OfflineScreen(s)
                // while banned, the ban screen stands in place of the whole app
                is AuthState.Ready -> {
                    // the app's own updates: looked for soon after it opens, now and then while it is in front, and on coming back
                    val visible by Nexa.ui.visible.collectAsState()
                    LaunchedEffect(visible) { if (visible) AppUpdate.resume() }
                    val isBanned = banned()
                    // back from a ban: the connection the server shut is opened again
                    var wasBanned by remember { mutableStateOf(false) }
                    LaunchedEffect(isBanned) {
                        if (isBanned) wasBanned = true
                        else if (wasBanned) {
                            wasBanned = false
                            Nexa.session.restart()
                        }
                    }
                    if (isBanned) fish.nexa.ui.nexa.BanScreen() else MainShell()
                }
            }
            fish.nexa.ui.nexa.DeletedNoticeHost()
            CaptchaHost.Sheet()
            ToastHost()
        }
    }
}

/** The system's own "remove animations" switch counts as Reduce Motion. */
object Motion {
    fun systemOff(): Boolean = try {
        android.provider.Settings.Global.getFloat(Nexa.app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }
}

/**
 * A theme as the settings hold it ("dark", "system", a preset or a custom
 * id) to the palette drawn, the accent over it: lib/theme.ts resolveLook and
 * accentVarsOn. A theme being built is drawn as it is, with its own accent.
 */
object Themes {
    fun resolve(look: Appearance.Look, systemDark: Boolean, draft: ResolvedTheme? = null): fish.nexa.ui.theme.NexaColors {
        if (draft != null) {
            Nexa.settings.lookUnderlines = false
            return Looks.fromTokens("draft", draft.scheme == "light", draft.tokens)
        }
        val resolved = Looks.resolve(look.theme, !systemDark, look.pairDark, look.pairLight, look.customs)
        Nexa.settings.lookUnderlines = resolved.underlineLinks
        return Looks.colors(resolved, look.accent)
    }
}

/** useBan: whether a ban is in force, read again whenever the standing changes. */
@Composable
private fun banned(): Boolean {
    val standing by fish.nexa.data.NexaInbox.standing.collectAsState()
    return standing?.ban?.inForce() == true
}
