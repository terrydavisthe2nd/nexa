package fish.nexa.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.crypto.WeakKdfError
import fish.nexa.data.Accounts
import fish.nexa.data.AuthState
import fish.nexa.data.Passkeys
import fish.nexa.data.PasswordStep
import fish.nexa.data.SignedIn
import fish.nexa.net.ApiError
import fish.nexa.ui.Icon
import fish.nexa.ui.auth.ErrorBox
import fish.nexa.ui.auth.NewAccountFields
import fish.nexa.ui.auth.RecoveryKeyPanel
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Switch Accounts (web: the side menu of UserPanel's Switch Accounts): every
 * account signed in on this phone, the open one ticked, then Add Another Account.
 */
fun openAccountsSheet() {
    openSheet("accounts", title = t("accounts.switchAccounts")) { close ->
        val accounts by Accounts.list.collectAsState()
        val me = Nexa.users.meId
        Column {
            for (a in accounts) {
                AccountOption(a, current = a.id == me, onSwitch = {
                    close()
                    switchAccount(a.id)
                }, onRemove = {
                    confirm(t("accounts.removeTitle", "name" to a.name), t("accounts.removeBody"), t("accounts.remove"), titleWords = fish.nexa.ui.chat.PersonWords(t("accounts.removeTitle", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(a.id to a.name))) { Nexa.auth.removeAccount(a.id) }
                })
            }
            if (accounts.isNotEmpty()) MenuSep()
            StatusOption("Plus", t("accounts.addAnotherAccount"), null) {
                close()
                if (Accounts.canAdd()) openAddAccountSheet() else Nexa.ui.toast(t("accounts.tooMany", "count" to Accounts.MAX))
            }
        }
    }
}

/** Opens the app as another account, back on the screen it was last on. */
fun switchAccount(id: String) {
    Layers.clear()
    Nexa.scope.launch {
        Nexa.auth.switchTo(id)
        Nexa.auth.state.first { it is AuthState.Ready }
        Nexa.session.ready.first { it }
        Nav.restore(switched = true)
    }
}

/** Another account signed in here: switch to it, or sign it out of this phone; the open one is ticked. */
@Composable
private fun AccountOption(a: Accounts.Saved, current: Boolean, onSwitch: () -> Unit, onRemove: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radii.Sm)
            .background(if (pressed) c.brand.copy(alpha = 0.2f) else Color.Transparent)
            .pressable(interaction, enabled = !current, onClick = onSwitch)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = if (current) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(a.id, a.name, a.avatarId, 24.dp)
        Column(Modifier.weight(1f).padding(vertical = if (current) 10.dp else 0.dp)) {
            fish.nexa.ui.chat.PersonName(a.id, a.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(550)), color = if (pressed) c.textStrong else c.textSubtle, maxLines = 1)
            Txt("@${a.username}", style = Nx.type.hint, color = c.textMuted, maxLines = 1)
        }
        if (current) Icon("Check", c.statusOnline, size = 16.dp, label = t("accounts.current"))
        else IconButton("X", t("accounts.removeTitle", "name" to a.name), onRemove, tint = c.textMuted, size = 44.dp, iconSize = 14.dp)
    }
}

/** An account just made here: its key is shown once, so this stays until it has been saved, then the app switches. */
private fun openNewAccountKeySheet(id: String, key: String) {
    openSheet("add-account-key", title = t("register.saveYourRecoveryKey"), canClose = { false }, closeButton = false) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(t("register.yourMessagesAreEndToEnd"), style = Nx.type.bodySmall, color = Nx.colors.textMuted)
            RecoveryKeyPanel(key, t("register.iHaveSavedMyRecoveryKey"), t("register.continueToNexa")) { switchAccount(id) }
        }
    }
}

/**
 * Add Another Account, over the app (web: AddAccountModal): a password (and
 * its code) or a passkey signs another account in without leaving this one,
 * then the app switches to it. Create an Account makes a new one the same way,
 * once its recovery key has been saved.
 */
fun openAddAccountSheet() {
    var creating by mutableStateOf(false)
    // closing while the account is being made would lose its recovery key
    var making by mutableStateOf(false)
    openSheet(
        "add-account",
        canClose = { !making },
        heading = { fish.nexa.ui.layers.SheetTitle(if (creating) t("register.createAnAccount") else t("accounts.addAnotherAccount")) },
    ) { close ->
        val c = Nx.colors
        val activity = androidx.activity.compose.LocalActivity.current
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var useBackup by remember { mutableStateOf(false) }
        var needs by remember { mutableStateOf<PasswordStep.NeedsCode?>(null) }
        var busy by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        fun describe(e: Throwable, fallback: String): String = when {
            e is WeakKdfError -> e.message ?: fallback
            e is ApiError && e.code == "invalid_credentials" -> t("login.usernameOrPasswordIsWrong")
            e is ApiError && e.code == "invalid_code" -> t("login.thatCodeDidNotWork")
            e is ApiError && e.code == "rate_limited" -> t("login.tooManyAttempts")
            else -> describeError(e, fallback)
        }
        // on the app's scope: this sheet closes as the switch begins
        fun run(kind: String, fallback: String, work: suspend () -> SignedIn?) {
            if (busy != null) return
            busy = kind
            error = null
            Nexa.scope.launch {
                val out = try {
                    work() ?: return@launch
                } catch (e: Exception) {
                    if (!Passkeys.cancelled(e)) error = describe(e, fallback)
                    return@launch
                } finally {
                    busy = null
                }
                // every dialog goes before switching: they were the last account's, and the
                // screen they hang from leaves while the app reopens, taking their close animation with it
                Layers.clear()
                try {
                    Nexa.auth.addSignedIn(out)
                    Nexa.auth.state.first { it is AuthState.Ready }
                    Nexa.session.ready.first { it }
                    Nav.restore(switched = true)
                } catch (e: Exception) {
                    Nexa.ui.toast(describe(e, fallback), "error")
                }
            }
        }
        fun submitPassword() {
            if (username.isBlank() || password.isEmpty()) return
            run("password", t("login.couldNotDecryptYourKeysWith")) {
                when (val step = Nexa.auth.passwordSignIn(username, password)) {
                    is PasswordStep.Done -> step.out
                    is PasswordStep.NeedsCode -> { needs = step; null }
                }
            }
        }
        fun submitCode() {
            val n = needs ?: return
            if (code.isBlank()) return
            run("code", t("login.thatCodeDidNotWork")) { n.finish(if (useBackup) null else code, if (useBackup) code else null) }
        }

        val instance by Nexa.auth.instance.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) { Nexa.auth.loadInstance() }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            fish.nexa.ui.chat.PersonText(Nexa.users.meId, t("accounts.addHint", "name" to fish.nexa.ui.chat.PERSON_SLOT), (Nexa.users.me.value?.name ?: ""), style = Nx.type.bodySmall, color = c.textMuted)
            error?.takeIf { !creating }?.let { ErrorBox(it) }
            val n = needs
            if (creating) {
                NewAccountFields(instance, { name, shown, pass, key ->
                    making = true
                    try {
                        val (out, recovery) = Nexa.auth.createAccount(name, shown, pass, key, key)
                        Nexa.auth.keepCreated(out)
                        making = false
                        close()
                        openNewAccountKeySheet(out.me.id, recovery)
                    } finally {
                        making = false
                    }
                }, { creating = false })
            } else if (n != null) {
                Txt(if (useBackup) t("login.enterOneOfTheBackupCodes") else t("login.enterTheSixDigitCodeFrom"), style = Nx.type.bodySmall, color = c.textMuted)
                NxTextField(
                    code, { v -> code = if (useBackup) v.uppercase().take(20) else v.filter { it.isDigit() || it == ' ' }.take(7) },
                    label = if (useBackup) t("login.backupCode") else t("login.authenticationCode"), required = true,
                    placeholder = if (useBackup) "XXXX-XXXX-XXXX" else "123456",
                    keyboardType = if (useBackup) KeyboardType.Ascii else KeyboardType.NumberPassword, imeAction = ImeAction.Done, onIme = { submitCode() },
                )
                NxButton(t("login.logIn"), { submitCode() }, size = ButtonSize.LG, block = true, loading = busy == "code", enabled = code.isNotBlank())
                NxButton(
                    if (useBackup) t("login.useYourAuthenticatorInstead") else if (n.backupCodesLeft > 0) t("login.useABackupCodeLeft", "count" to n.backupCodesLeft) else t("login.useABackupCode"),
                    { useBackup = !useBackup; code = ""; error = null }, kind = ButtonKind.LINK, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                NxButton(t("modals.createServer.back"), { needs = null; code = ""; password = ""; error = null }, kind = ButtonKind.LINK, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                NxTextField(username, { username = it }, label = t("settings.account.username"), required = true, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
                NxTextField(password, { password = it }, label = t("chat.lockedChannel.password"), required = true, password = true, imeAction = ImeAction.Go, onIme = { submitPassword() })
                NxButton(t("login.logIn"), { submitPassword() }, size = ButtonSize.LG, block = true, loading = busy == "password", enabled = username.isNotBlank() && password.isNotEmpty())
                if (Passkeys.supported && activity != null) {
                    NxButton(t("passkeys.logInWithAPasskey"), { run("passkey", t("passkeys.couldNotSignIn")) { Passkeys.signIn(activity).let { (me, token, id) -> SignedIn(me, token, id) } } },
                        kind = ButtonKind.SECONDARY, size = ButtonSize.LG, block = true, icon = "KeyRound", loading = busy == "passkey")
                }
                if (instance?.registration != "closed") {
                    NxButton(t("register.createAnAccount"), { creating = true; error = null }, kind = ButtonKind.SECONDARY, size = ButtonSize.LG, block = true)
                }
            }
        }
    }
}
