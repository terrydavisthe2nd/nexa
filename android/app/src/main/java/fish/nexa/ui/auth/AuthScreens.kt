package fish.nexa.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.AuthState
import fish.nexa.data.LoginStep
import fish.nexa.net.ApiError
import fish.nexa.net.InstanceInfo
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NexaWordmark
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxCheckbox
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.describeError
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowHigh
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** Whether a screen of AuthLayout has been shown yet in this run of the app. */
private var authArrived = false

/**
 * Auth.module.css: the canvas lit from above in the accent, the wordmark
 * with its glow thrown down, and a frosted card that rises in after it.
 */
@Composable
fun AuthLayout(title: String, subtitle: String? = null, maxWidth: Int = 480, mark: (@Composable () -> Unit)? = null, subtitlePersonId: String? = null, subtitleName: String? = null, subtitleNameStyle: kotlinx.serialization.json.JsonElement? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val reduced = Nx.motion.reduced
    // The wordmark and card rise in on the first of these screens someone lands on;
    // going on between them (Log In, Register, Recover) keeps them still, as on the
    // web (AuthLayout's arrive): rising again from nothing read as a blink.
    val rise = remember { Animatable(if (reduced || authArrived) 1f else 0f).also { authArrived = true } }
    LaunchedEffect(Unit) { if (rise.value < 1f) rise.animateTo(1f, tween(520, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bgCanvas)
            .authLight(c.brand),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.graphicsLayer {
                    alpha = rise.value
                    translationY = (1f - rise.value) * 10.dp.toPx()
                },
            ) {
                NexaWordmark(height = 32.dp, modifier = Modifier.dropShadow(RoundedCornerShape(16.dp), Shadow(radius = 24.dp, color = c.brand.copy(alpha = 0.40f), offset = DpOffset(0.dp, 6.dp))))
            }
            val shape = Radii.Lg
            Column(
                Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = rise.value
                        translationY = (1f - rise.value) * 10.dp.toPx()
                    }
                    .dropShadow(shape, Shadow(radius = 100.dp, color = c.brand.copy(alpha = 0.55f), spread = (-40).dp, offset = DpOffset(0.dp, 40.dp)))
                    .shadowHigh(shape, c)
                    .ring(shape, c.panelEdge)
                    .background(c.bgRaised.copy(alpha = 0.92f), shape)
                    .topEdge(shape, c.edgeLightStrong)
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                mark?.invoke()
                Txt(title, style = Nx.type.title.copy(fontSize = Nx.type.sp(25f), lineHeight = Nx.type.sp(27.5f)), color = c.textStrong, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
                if (subtitle != null) {
                    val subtitleStyle = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(22.5f), textAlign = TextAlign.Center)
                    val subtitleModifier = Modifier.fillMaxWidth().padding(top = 0.dp).graphicsLayer { translationY = -10.dp.toPx() }
                    if (subtitleName != null) fish.nexa.ui.chat.PersonText(subtitlePersonId, subtitle, subtitleName, subtitleStyle, c.textMuted, subtitleModifier, nameStyle = subtitleNameStyle)
                    else Txt(subtitle, style = subtitleStyle, color = c.textMuted, modifier = subtitleModifier)
                }
                content()
            }
        }
    }
}

/**
 * `.page`'s two lights: an ellipse of the accent at 32% above the card
 * (70% by 55% of the page, centred 8% above it) and a fainter one low on the
 * right (45% by 40% at 88%, 108%).
 */
fun Modifier.authLight(brand: Color): Modifier = drawBehind {
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float, stop: Float) {
        val center = Offset(size.width * cx, size.height * cy)
        val radius = size.width * rx
        scale(1f, (size.height * ry) / radius, center) {
            drawCircle(Brush.radialGradient(0f to brand.copy(alpha = alpha), stop to Color.Transparent, center = center, radius = radius), radius = radius * 1.5f, center = center)
        }
    }
    ellipse(0.5f, -0.08f, 0.70f, 0.55f, 0.32f, 0.72f)
    ellipse(0.88f, 1.08f, 0.45f, 0.40f, 0.16f, 0.70f)
}

/** A failure above the form, shaken once as it arrives. */
@Composable
fun ErrorBox(text: String) {
    val c = Nx.colors
    val shake = remember { Animatable(0f) }
    LaunchedEffect(text) {
        if (!Nexa.settings.reducedMotion) for (x in listOf(-5f, 5f, -5f, 5f, 0f)) shake.animateTo(x, tween(76))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value * density }
            .background(c.danger.copy(alpha = 0.14f), Radii.Sm)
            .border(1.dp, c.danger.copy(alpha = 0.5f), Radii.Sm)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) { Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) }
}

@Composable
private fun Note(text: String, center: Boolean = false) =
    Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = Nx.colors.textMuted, align = if (center) TextAlign.Center else null, modifier = if (center) Modifier.fillMaxWidth() else Modifier)

@Composable
private fun Foot(lead: String?, link: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) Txt("$lead ", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = Nx.colors.textMuted)
        NxButton(link, onClick, kind = ButtonKind.LINK, inline = true)
    }
}

/** The four bars under a new password (AuthLayout passwordStrength). */
fun passwordStrength(pw: String): Pair<Int, String> {
    if (pw.length < 8) return 0 to t("authLayout.tooShort")
    var score = 1
    if (pw.length >= 12) score++
    if (pw.any { it.isLowerCase() } && pw.any { it.isUpperCase() }) score++
    if (pw.any { it.isDigit() }) score++
    if (pw.any { !it.isLetterOrDigit() && !it.isWhitespace() && it != '_' }) score++
    val capped = minOf(4, score)
    val labels = listOf(t("authLayout.tooShort"), t("authLayout.weak"), t("authLayout.okay"), t("authLayout.good"), t("authLayout.strong"))
    return capped to labels[capped]
}

private val USERNAME_RE = Regex("^[a-z0-9_.]{2,32}$")

fun usernameProblem(u: String): String? = when {
    u.length < 2 -> t("authLayout.atLeast2Characters")
    u.length > 32 -> t("authLayout.atMost32Characters")
    !USERNAME_RE.matches(u) -> t("authLayout.onlyAZ09And")
    u.startsWith(".") || u.endsWith(".") || u.contains("..") -> t("authLayout.dotsCanTLeadTrailOr")
    else -> null
}

@Composable
private fun StrengthMeter(password: String) {
    val c = Nx.colors
    val (score, label) = passwordStrength(password)
    val on = when {
        password.isEmpty() -> c.bgActive
        score <= 1 -> c.danger
        score == 2 -> c.warning
        else -> c.positive
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in 1..4) {
            val lit = password.isNotEmpty() && score >= n
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .then(if (lit) Modifier.dropShadow(RoundedCornerShape(3.dp), Shadow(radius = 8.dp, color = on, spread = (-2).dp)) else Modifier)
                    .background(if (lit) on else c.bgActive, RoundedCornerShape(3.dp)),
            )
        }
        Txt(if (password.isEmpty()) t("settings.account.atLeast8Characters") else label, style = Nx.type.hint, color = c.textMuted, modifier = Modifier.padding(start = 8.dp))
    }
}

private enum class AuthPage { LOGIN, REGISTER, RECOVER }

/** The signed-out app: log in, register or recover, one page at a time. */
@Composable
fun AuthFlow() {
    var page by remember { mutableStateOf(AuthPage.LOGIN) }
    // Back from Register or Recover returns to Log In, as the browser's back does on the web
    androidx.activity.compose.BackHandler(enabled = page != AuthPage.LOGIN) { page = AuthPage.LOGIN }
    when (page) {
        AuthPage.LOGIN -> LoginScreen(onRegister = { page = AuthPage.REGISTER }, onRecover = { page = AuthPage.RECOVER })
        AuthPage.REGISTER -> RegisterScreen(onLogin = { page = AuthPage.LOGIN })
        AuthPage.RECOVER -> RecoverScreen(onBack = { page = AuthPage.LOGIN })
    }
}

@Composable
fun LoginScreen(onRegister: () -> Unit, onRecover: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableStateOf<String?>(null) }
    var ticket by remember { mutableStateOf<String?>(null) }
    var codesLeft by remember { mutableIntStateOf(0) }
    var useBackup by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }

    fun describe(e: Throwable, fallback: String): String = when {
        e is ApiError && e.code == "invalid_credentials" -> t("login.usernameOrPasswordIsWrong")
        e is ApiError && e.code == "rate_limited" -> t("login.tooManyAttempts")
        e is ApiError && e.code == "account_disabled" -> t("login.accountDisabled")
        e is ApiError && e.isNetwork -> t("login.canTReachTheServerCheck")
        else -> describeError(e, fallback)
    }

    fun submitPassword() {
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        wrong = null
        scope.launch {
            try {
                when (val step = Nexa.auth.login(username, password)) {
                    is LoginStep.NeedsCode -> {
                        ticket = step.ticket
                        codesLeft = step.backupCodesLeft
                    }
                    LoginStep.Done -> Unit
                }
            } catch (e: Exception) {
                if (e is ApiError && e.code == "invalid_credentials") wrong = t("login.usernameOrPasswordIsWrong")
                else error = describe(e, t("login.couldNotDecryptYourKeysWith"))
            } finally {
                busy = false
            }
        }
    }

    fun submitCode() {
        val tk = ticket ?: return
        if (busy || code.isBlank()) return
        busy = true
        error = null
        wrong = null
        scope.launch {
            try {
                Nexa.auth.completeMfa(tk, if (useBackup) null else code, if (useBackup) code else null)
            } catch (e: Exception) {
                code = ""
                if (e is ApiError && (e.code == "mfa_expired" || e.code == "mfa_disabled")) {
                    ticket = null
                    password = ""
                    error = describeError(e, t("login.thatCodeDidNotWork"))
                } else if (e is ApiError && e.code == "invalid_code") wrong = t("login.thatCodeDidNotWork")
                else error = describe(e, t("login.thatCodeDidNotWork"))
            } finally {
                busy = false
            }
        }
    }

    if (ticket != null) {
        AuthLayout(t("login.oneMoreStep"), if (useBackup) t("login.enterOneOfTheBackupCodes") else t("login.enterTheSixDigitCodeFrom"), maxWidth = 420) {
            error?.let { ErrorBox(it) }
            NxTextField(
                value = code,
                onValueChange = { v ->
                    wrong = null
                    code = if (useBackup) v.uppercase().take(20) else v.filter { it.isDigit() || it == ' ' }.take(7)
                },
                label = if (useBackup) t("login.backupCode") else t("login.authenticationCode"),
                required = true,
                error = wrong,
                placeholder = if (useBackup) "XXXX-XXXX-XXXX" else "123456",
                keyboardType = if (useBackup) KeyboardType.Ascii else KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                onIme = { submitCode() },
            )
            NxButton(t("login.logIn"), { submitCode() }, size = ButtonSize.LG, block = true, loading = busy)
            NxButton(
                if (useBackup) t("login.useYourAuthenticatorInstead") else if (codesLeft > 0) t("login.useABackupCodeLeft", "count" to codesLeft) else t("login.useABackupCode"),
                { useBackup = !useBackup; code = ""; error = null; wrong = null },
                kind = ButtonKind.LINK, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            NxButton(t("modals.createServer.back"), { ticket = null; code = ""; password = ""; error = null; wrong = null; useBackup = false }, kind = ButtonKind.LINK, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        return
    }

    val activity = androidx.activity.compose.LocalActivity.current
    var passkeyBusy by remember { mutableStateOf(false) }
    fun submitPasskey() {
        val act = activity ?: return
        if (passkeyBusy) return
        passkeyBusy = true
        error = null
        wrong = null
        scope.launch {
            try {
                val (me, token, identity) = fish.nexa.data.Passkeys.signIn(act)
                Nexa.auth.loginWith(me, token, identity)
            } catch (e: Exception) {
                if (!fish.nexa.data.Passkeys.cancelled(e)) error = describe(e, t("passkeys.couldNotSignIn"))
            } finally {
                passkeyBusy = false
            }
        }
    }
    // a phone cannot scan its own screen, so the code is for wider ones (as the web's 768 px)
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 768

    val form: @Composable ColumnScope.() -> Unit = {
        error?.let { ErrorBox(it) }
        NxTextField(username, { username = it; wrong = null }, label = t("settings.account.username"), required = true, keyboardType = KeyboardType.Email)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NxTextField(password, { password = it; wrong = null }, label = t("chat.lockedChannel.password"), required = true, password = true, error = wrong, imeAction = ImeAction.Go, onIme = { submitPassword() })
            NxButton(t("login.forgotYourPassword"), onRecover, kind = ButtonKind.LINK, inline = true, modifier = Modifier.padding(top = 0.dp).graphicsLayer { translationY = -8.dp.toPx() })
        }
        NxButton(t("login.logIn"), { submitPassword() }, size = ButtonSize.LG, block = true, loading = busy)
        if (fish.nexa.data.Passkeys.supported) {
            NxButton(t("passkeys.logInWithAPasskey"), { submitPasskey() }, kind = ButtonKind.SECONDARY, size = ButtonSize.LG, block = true, icon = "KeyRound", loading = passkeyBusy)
        }
        Note(t("login.staySignedIn"))
        Foot(t("login.needAnAccount"), t("login.register"), onRegister)
    }

    AuthLayout(t("login.welcomeBack"), t("login.weReSoExcitedToSee"), maxWidth = if (wide) 820 else 480) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { form() }
                Box(Modifier.width(1.dp).height(320.dp).background(Nx.colors.borderSubtle))
                QrSignInPanel(Modifier.width(232.dp)) { error = it }
            }
        } else {
            form()
        }
    }
}

/** RecoveryKeyPanel: the key, Copy, what it is, and a box to tick before going on. */
@Composable
fun RecoveryKeyPanel(value: String, confirm: String, doneLabel: String, onDone: () -> Unit) {
    val c = Nx.colors
    val ctx = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .sunken(Radii.Md, c)
            .border(1.dp, c.borderSubtle, Radii.Md)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(value.replace("-", "-​"), style = Nx.type.code.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(25.5f), letterSpacing = androidx.compose.ui.unit.TextUnit(0.04f, androidx.compose.ui.unit.TextUnitType.Em)), color = c.textStrong, align = TextAlign.Center)
    }
    NxButton(if (copied) t("modals.groupInvite.copied") else t("modals.groupInvite.copy"), {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Nexa", value))
        copied = true
    }, kind = ButtonKind.SECONDARY, block = true, icon = if (copied) "Check" else null)
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.warning.copy(alpha = 0.12f), Radii.Sm)
            .border(1.dp, c.warning.copy(alpha = 0.5f), Radii.Sm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { Txt(t("register.weCannotShowThisKeyAgain"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f)), color = c.text) }
    NxCheckbox(saved, { saved = it }, confirm)
    NxButton(doneLabel, onDone, size = ButtonSize.LG, block = true, enabled = saved)
}

@Composable
fun RegisterScreen(onLogin: () -> Unit) {
    var recovery by remember { mutableStateOf<String?>(null) }
    var instance by remember { mutableStateOf<InstanceInfo?>(Nexa.auth.instance.value) }
    LaunchedEffect(Unit) { Nexa.auth.loadInstance()?.let { instance = it } }

    recovery?.let { key ->
        AuthLayout(t("register.saveYourRecoveryKey"), t("register.yourMessagesAreEndToEnd")) {
            RecoveryKeyPanel(key, t("register.iHaveSavedMyRecoveryKey"), t("register.continueToNexa")) { Nexa.auth.finishRegistration() }
        }
        return
    }
    if (instance?.registration == "closed") {
        AuthLayout(t("register.createAnAccount")) {
            Note(t("register.thisServerIsNotAcceptingNew"), center = true)
            NxButton(t("register.alreadyHaveAnAccount"), onLogin, kind = ButtonKind.LINK, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        return
    }

    AuthLayout(t("register.createAnAccount")) {
        NewAccountFields(instance, { name, shown, pass, key -> recovery = Nexa.auth.register(name, shown, pass, key, key) }, onLogin)
    }
}

/** The new account form (web NewAccountForm), on the sign-up screen and in Add Another Account. */
@Composable
fun NewAccountFields(instance: InstanceInfo?, create: suspend (username: String, displayName: String, password: String, key: String?) -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var inviteKey by remember { mutableStateOf("") }
    var inviteOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var nameTaken by remember { mutableStateOf<String?>(null) }
    var displayTaken by remember { mutableStateOf<String?>(null) }
    val uname = username.trim().lowercase()
    val uProblem = if (uname.isNotEmpty()) usernameProblem(uname) else null
    val mismatch = confirm.isNotEmpty() && confirm != password
    val ready = uProblem == null && uname.length >= 2 && display.isNotBlank() && password.length >= 8 && password == confirm

    fun submit() {
        if (busy || !ready) return
        busy = true
        error = null
        progress = t("register.securingYourAccountThisTakesA")
        scope.launch {
            try {
                val key = inviteKey.trim().takeIf { it.isNotEmpty() }
                create(uname, display.trim(), password, key)
            } catch (e: Exception) {
                val code = (e as? ApiError)?.code ?: ""
                when (code) {
                    "username_taken" -> nameTaken = t("register.thatUsernameIsTaken")
                    "invalid_username" -> nameTaken = t("register.thatUsernameIsNotAllowed")
                    "reserved_username" -> nameTaken = t("register.thatNameIsReserved")
                    "reserved_display_name" -> displayTaken = t("register.thatNameIsReserved")
                    "registration_closed" -> error = t("register.thisServerIsNotAcceptingNew")
                    "invite_required" -> error = t("register.youNeedAnInviteOrRegistration")
                    "rate_limited" -> error = t("register.tooManySignUpsFromYour")
                    "too_many_signups" -> error = t("register.twoAccountsADay")
                    else -> error = describeError(e, t("register.somethingWentWrongPleaseTryAgain"))
                }
            } finally {
                busy = false
                progress = ""
            }
        }
    }

    error?.let { ErrorBox(it) }
    NxTextField(username, { username = it.take(32); nameTaken = null }, label = t("settings.account.username"), required = true, error = uProblem ?: nameTaken, hint = t("register.lowercaseLettersNumbersUnderscoresAndDots"))
    NxTextField(display, { display = it.take(32); displayTaken = null }, label = t("register.displayName"), required = true, error = displayTaken, hint = t("register.howYourNameAppearsToOthers"), capitalize = true)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NxTextField(password, { password = it }, label = t("chat.lockedChannel.password"), required = true, password = true)
        StrengthMeter(password)
    }
    NxTextField(confirm, { confirm = it }, label = t("register.confirmPassword"), required = true, password = true, error = if (mismatch) t("settings.account.passwordsDoNotMatch") else null, imeAction = if (instance?.registration == "invite") ImeAction.Next else ImeAction.Done, onIme = { submit() })
    if (instance?.registration == "invite") {
        NxTextField(inviteKey, { inviteKey = it }, label = t("register.inviteRegistrationKey"), required = true, hint = t("register.youNeedAnInviteOrRegistration"))
    } else {
        NxButton(t("register.haveAnInviteOrRegistrationKey"), { inviteOpen = !inviteOpen }, kind = ButtonKind.LINK, icon = if (inviteOpen) "ChevronDown" else "ChevronRight")
        if (inviteOpen) NxTextField(inviteKey, { inviteKey = it }, label = t("register.inviteRegistrationKey"))
    }
    NxButton(t("settings.security.continue"), { submit() }, size = ButtonSize.LG, block = true, loading = busy, enabled = ready || busy)
    if (progress.isNotEmpty()) Note(progress, center = true)
    // the agreement, with the two documents a tap away
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Note(t("legal.gatePrivacy"))
        NxButton(t("legal.termsTitle"), { fish.nexa.ui.legal.Legal.openDocument("terms") }, kind = ButtonKind.LINK, inline = true)
        Txt("·", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = Nx.colors.textMuted)
        NxButton(t("legal.privacyTitle"), { fish.nexa.ui.legal.Legal.openDocument("privacy") }, kind = ButtonKind.LINK, inline = true)
    }
    Foot(null, t("register.alreadyHaveAnAccount"), onLogin)
}

@Composable
fun RecoverScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrongKey by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf<String?>(null) }

    fresh?.let { k ->
        AuthLayout(t("recover.passwordReset"), t("recover.hereIsYourNewRecoveryKey")) {
            Note(t("recover.twoFactorIsOffNow"))
            RecoveryKeyPanel(k, t("recover.iHaveSavedMyNewRecovery"), t("settings.security.continue")) { Nexa.auth.finishRegistration() }
        }
        return
    }

    fun submit() {
        if (busy) return
        if (password.length < 8 || password != confirm) return
        busy = true
        error = null
        wrongKey = null
        scope.launch {
            try {
                fresh = Nexa.auth.recover(username, key, password)
            } catch (e: Exception) {
                when {
                    e is ApiError && e.code == "account_disabled" -> error = t("login.accountDisabled")
                    e is ApiError && e.code == "invalid_credentials" -> wrongKey = t("recover.usernameOrRecoveryKeyIsWrong")
                    e is IllegalArgumentException -> wrongKey = t("recover.thatDoesNotLookLikeARecoveryKey")
                    e is ApiError -> error = describeError(e, t("recover.recoveryFailedCheckTheKeyAnd"))
                    else -> error = t("recover.recoveryFailedCheckTheKeyAnd")
                }
            } finally {
                busy = false
            }
        }
    }

    AuthLayout(t("recover.recoverYourAccount"), t("recover.useTheRecoveryKeyYouSaved")) {
        error?.let { ErrorBox(it) }
        NxTextField(username, { username = it; wrongKey = null }, label = t("settings.account.username"), required = true)
        NxTextField(key, { key = it; wrongKey = null }, label = t("settings.account.recoveryKey"), required = true, error = wrongKey, hint = t("recover.eightGroupsOfEightCharactersDashes"), mono = true)
        NxTextField(password, { password = it }, label = t("settings.account.newPassword"), required = true, password = true)
        NxTextField(confirm, { confirm = it }, label = t("settings.account.confirmNewPassword"), required = true, password = true, error = if (confirm.isNotEmpty() && confirm != password) t("settings.account.passwordsDoNotMatch") else null, imeAction = ImeAction.Done, onIme = { submit() })
        Note(t("recover.whatAResetDoes"))
        NxButton(t("recover.resetPassword"), { submit() }, size = ButtonSize.LG, block = true, loading = busy)
        Foot(null, t("recover.backToLogIn"), onBack)
    }
}

/** Signed in here, but the keys are not: the password opens them again. */
@Composable
fun LockScreen(state: AuthState.Locked) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy || password.isEmpty()) return
        busy = true
        error = null
        wrong = null
        scope.launch {
            try {
                Nexa.auth.unlock(password)
            } catch (e: Exception) {
                if (e is ApiError || e is fish.nexa.crypto.WeakKdfError) error = describeError(e, t("lock.thatPasswordCouldNotUnlockYour"))
                else wrong = t("lock.thatPasswordCouldNotUnlockYour")
            } finally {
                busy = false
            }
        }
    }
    AuthLayout(t("lock.unlockYourMessages"), t("lock.welcomeBackEnterYourPassword", "name" to fish.nexa.ui.chat.PERSON_SLOT), subtitlePersonId = state.me.id, subtitleName = state.me.name, subtitleNameStyle = state.me.nameStyle) {
        error?.let { ErrorBox(it) }
        NxTextField(password, { password = it; wrong = null }, label = t("chat.lockedChannel.password"), required = true, password = true, error = wrong, imeAction = ImeAction.Go, onIme = { submit() })
        NxButton(t("lock.unlock"), { submit() }, size = ButtonSize.LG, block = true, loading = busy)
        NxButton(t("lock.logOutInstead"), { scope.launch { Nexa.auth.logout() } }, kind = ButtonKind.LINK, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** The session could not be checked: keeps trying, sooner when asked. */
@Composable
fun OfflineScreen(state: AuthState.Offline) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        var tries = 0
        while (true) {
            kotlinx.coroutines.delay(minOf(30_000L, 2_000L shl tries))
            busy = true
            Nexa.auth.bootstrap()
            busy = false
            tries++
        }
    }
    AuthLayout(t("offline.canTConnect"), state.message.ifBlank { t("offline.couldNotReachTheServer") }, maxWidth = 420) {
        Note(t("offline.youAreStillLoggedInCheck"), center = true)
        NxButton(t("chat.messageItem.retry"), { scope.launch { busy = true; Nexa.auth.bootstrap(); busy = false } }, size = ButtonSize.LG, block = true, loading = busy)
    }
}

@Composable
fun Splash() {
    val c = Nx.colors
    Box(Modifier.fillMaxSize().background(c.bgCanvas), contentAlignment = Alignment.Center) {
        NexaWordmark(height = 32.dp, modifier = Modifier.alpha(0.9f))
    }
}
