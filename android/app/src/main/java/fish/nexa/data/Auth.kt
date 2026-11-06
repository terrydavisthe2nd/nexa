package fish.nexa.data

import android.util.Log
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.DecryptError
import fish.nexa.crypto.Identity
import fish.nexa.crypto.Kdf
import fish.nexa.crypto.KdfParams
import fish.nexa.crypto.Keys
import fish.nexa.crypto.WeakKdfError
import fish.nexa.net.ApiError
import fish.nexa.net.InstanceInfo
import fish.nexa.net.KdfInfo
import fish.nexa.net.KdfParamsJson
import fish.nexa.net.KeyBundle
import fish.nexa.net.LoginResult
import fish.nexa.net.MeUser
import fish.nexa.net.RecoverBegin
import fish.nexa.net.RegisterRequest
import fish.nexa.net.wireJson
import fish.nexa.ui.ReadableError
import fish.nexa.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** Where signing in stands (web/src/stores/auth.ts). */
sealed interface AuthState {
    data object Loading : AuthState
    data object Anonymous : AuthState
    /** Signed in on this device, but the identity key is not here: the password opens it. */
    data class Locked(val me: MeUser) : AuthState
    /**
     * The passcode is needed: this device has one, or (adopting) the account
     * has one this device has not taken on yet.
     */
    data class PinLocked(val me: MeUser, val adopting: Boolean) : AuthState
    /** The server could not be reached; the token is kept and the check retried. */
    data class Offline(val message: String) : AuthState
    data class Ready(val me: MeUser) : AuthState
}

/** The UI's captcha sheet: a fresh Turnstile token made for `action` on the instance's own page. */
fun interface CaptchaProvider {
    suspend fun token(siteKey: String, action: String): String
}

/** What a login asked for next. */
sealed interface LoginStep {
    data object Done : LoginStep
    data class NeedsCode(val ticket: String, val backupCodesLeft: Int) : LoginStep
}

/** What a sign-in got, before anything is kept on this phone (web stores/auth.ts SignedIn). */
data class SignedIn(val me: MeUser, val token: String, val identity: Identity)

/** A password's answer without touching the account open here: signed in, or a code to send with [finish]. */
sealed interface PasswordStep {
    data class Done(val out: SignedIn) : PasswordStep
    data class NeedsCode(val backupCodesLeft: Int, val finish: suspend (code: String?, backupCode: String?) -> SignedIn) : PasswordStep
}

class Auth(private val nx: Nexa) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state
    private val _instance = MutableStateFlow<InstanceInfo?>(null)
    val instance: StateFlow<InstanceInfo?> = _instance

    var captcha: CaptchaProvider? = null
    /** The enc key between a password and its second factor, never stored. */
    private var pendingEncKey: ByteArray? = null

    init {
        nx.api.onUnauthorized = {
            if (_state.value is AuthState.Ready) nx.scope.launch { signOutLocally("expired") }
        }
        nx.session.onSignedOut = { reason -> nx.scope.launch { signOutLocally(reason) } }
        nx.api.captchaSolver = { action -> solveCaptcha(action) }
        LockTimers.install()
    }

    suspend fun loadInstance(): InstanceInfo? = try {
        nx.api.request("GET", "/instance", InstanceInfo.serializer(), auth = false).also {
            _instance.value = it
            // links to Nexa's public address open in the app as well as links to this one
            fish.nexa.ui.markdown.AppLinks.setPublicUrl(it.publicUrl.ifEmpty { null })
        }
    } catch (_: Exception) {
        null
    }

    /**
     * A captcha for `action`, for a request the server turned down for want of
     * one (lib/captcha.ts solveFor): null when this instance has none set up.
     */
    /** Turnstile's site key, from its own field or from `captcha` when that names Turnstile. */
    private fun turnstileKey(info: InstanceInfo?): String? =
        info?.turnstile?.siteKey?.takeIf { it.isNotEmpty() }
            ?: info?.captcha?.takeIf { it.provider == "turnstile" && it.siteKey.isNotEmpty() }?.siteKey

    suspend fun solveCaptcha(action: String): String? {
        val info = _instance.value?.takeIf { turnstileKey(it) != null } ?: loadInstance()
        val key = turnstileKey(info) ?: return null
        val p = captcha ?: return null
        return p.token(key, action)
    }

    /** For requests outside signing in that may need a captcha (joining through an invite, friend requests). */
    suspend fun <T> withCaptcha(action: String, send: suspend (String?) -> T): T = guarded(action, send)

    /** Sends a guarded request with a captcha token when the server asks for them. */
    suspend fun <T> guarded(action: String, send: suspend (String?) -> T): T {
        val inst = _instance.value ?: loadInstance()
        suspend fun tokenFor(info: InstanceInfo?): String? {
            val key = turnstileKey(info) ?: return null
            val p = captcha ?: throw ApiError(0, "captcha_unavailable", "The captcha could not start.")
            return p.token(key, action)
        }
        try {
            return send(tokenFor(inst))
        } catch (e: ApiError) {
            if (e.code != "captcha_required" || turnstileKey(inst) != null) throw e
            val fresh = loadInstance()
            if (turnstileKey(fresh) == null) throw e
            return send(tokenFor(fresh))
        }
    }

    private val booting = kotlinx.coroutines.sync.Mutex()

    /**
     * Opening the app (or its background connection starting): a stored token
     * is checked, the identity looked for. Runs once at a time, and not again
     * once signed in.
     */
    suspend fun bootstrap() {
        if (_state.value is AuthState.Ready || _state.value is AuthState.PinLocked || !booting.tryLock()) return
        try {
            bootstrapOnce()
        } finally {
            booting.unlock()
        }
    }

    private suspend fun bootstrapOnce() {
        loadInstance()
        val token = nx.secure.getString("token")
        if (token == null) {
            _state.value = AuthState.Anonymous
            return
        }
        nx.api.token = token
        val me = try {
            nx.api.get("/users/@me", MeUser.serializer())
        } catch (e: ApiError) {
            if (e.status == 401) {
                Accounts.forgetToken(token)
                clearLocal(null)
                _state.value = AuthState.Anonymous
            } else if (e.isTransient) {
                _state.value = AuthState.Offline(e.message ?: "")
            } else {
                _state.value = AuthState.Anonymous
            }
            return
        }
        Accounts.remember(me, token)
        // with a passcode, every start opens on the passcode screen
        if (AppLock.has(me.id)) {
            _state.value = AuthState.PinLocked(me, adopting = false)
            return
        }
        val identity = loadIdentity(me.id)
        if (identity == null) {
            _state.value = AuthState.Locked(me)
            return
        }
        begin(me, identity)
    }

    private fun loadIdentity(userId: String): Identity? {
        val raw = nx.secure.get("identity-$userId") ?: return null
        if (raw.size != 64) return null
        val pub = raw.copyOfRange(0, 32)
        val priv = raw.copyOfRange(32, 64)
        if (!Identity.publicKeyOf(priv).contentEquals(pub)) return null
        return Identity(pub, priv)
    }

    /** Keeps the identity on the device, never while a passcode guards it. */
    private fun saveIdentity(userId: String, id: Identity) {
        if (AppLock.has(userId)) return
        nx.secure.put("identity-$userId", Bytes.concat(id.publicKey, id.privateKey))
    }

    private fun begin(me: MeUser, identity: Identity) {
        nx.api.token?.let { Accounts.remember(me, it) }
        nx.users.setMe(me)
        nx.misc.nexa.value = me.nexa
        NexaInbox.setState(me.nexa)
        nx.keys.init(me.id, identity, persist = !AppLock.has(me.id))
        nx.settings.key = Kdf.settingsKey(identity.privateKey)
        _state.value = AuthState.Ready(me)
        nx.session.start()
    }

    private fun params(p: KdfParamsJson) = KdfParams(p.m, p.t, p.p, p.v)

    private suspend fun stretch(password: String, salt: ByteArray, p: KdfParams): Pair<ByteArray, ByteArray> = withContext(Dispatchers.Default) {
        val master = Kdf.deriveMaster(password, salt, p)
        val pair = Kdf.authKey(master) to Kdf.encKey(master)
        master.fill(0)
        pair
    }

    /**
     * Signs in with a password without touching the account open here: Add
     * Another Account keeps the result beside it ([addSignedIn]).
     */
    suspend fun passwordSignIn(username: String, password: String): PasswordStep {
        val name = username.trim().lowercase()
        val kdf = nx.api.request("GET", "/auth/kdf?username=" + URLEncoder.encode(name, "UTF-8"), KdfInfo.serializer(), auth = false)
        val p = params(kdf.params)
        if (!Kdf.acceptable(p)) throw WeakKdfError("server asked for weak parameters")
        val (authKey, encKey) = stretch(password, Bytes.unb64(kdf.salt), p)
        val res = guarded("login") { token ->
            nx.api.request("POST", "/auth/login", LoginResult.serializer(), buildJsonObject {
                put("username", name)
                put("auth_key", Bytes.b64(authKey))
            }, captcha = token, auth = false)
        }
        if (!res.mfaRequired) return PasswordStep.Done(opened(res, encKey))
        val ticket = res.ticket ?: ""
        return PasswordStep.NeedsCode(res.backupCodesLeft) { code, backupCode ->
            opened(nx.api.request("POST", "/auth/login/mfa", LoginResult.serializer(), buildJsonObject {
                put("ticket", ticket)
                if (!backupCode.isNullOrBlank()) put("backup_code", backupCode.trim()) else put("code", (code ?: "").replace(Regex("\\s"), ""))
            }, auth = false), encKey)
        }
    }

    private fun opened(res: LoginResult, encKey: ByteArray): SignedIn {
        val me = res.user ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        val token = res.token ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        val priv = try {
            Keys.unwrapPrivateKey(Bytes.unb64(res.encPrivateKey ?: ""), encKey)
        } catch (_: Exception) {
            throw DecryptError("Your keys could not be opened with that password.")
        }
        return SignedIn(me, token, Identity(Identity.publicKeyOf(priv), priv))
    }

    /**
     * Keeps an account just signed in to (Add Another Account) beside the one
     * open here, then switches to it (web lib/accounts.ts addSignedIn).
     */
    suspend fun addSignedIn(out: SignedIn) {
        suspend fun logOut(token: String) = try {
            nx.api.request("POST", "/auth/logout", headers = mapOf("Authorization" to "Bearer $token"), auth = false)
        } catch (_: Exception) {
        }
        if (out.me.id == nx.users.meId) {
            // the account already open: the second session is not wanted
            nx.scope.launch { logOut(out.token) }
            throw ReadableError(t("accounts.alreadyOpen"))
        }
        val before = Accounts.find(out.me.id)
        saveIdentity(out.me.id, out.identity)
        Accounts.remember(out.me, out.token)
        // signed in to again: the session it had here goes
        if (before != null && before.token != out.token) nx.scope.launch { logOut(before.token) }
        switchTo(out.me.id)
    }

    suspend fun login(username: String, password: String): LoginStep {
        val name = username.trim().lowercase()
        val kdf = nx.api.request("GET", "/auth/kdf?username=" + URLEncoder.encode(name, "UTF-8"), KdfInfo.serializer(), auth = false)
        val p = params(kdf.params)
        if (!Kdf.acceptable(p)) throw WeakKdfError("server asked for weak parameters")
        val (authKey, encKey) = stretch(password, Bytes.unb64(kdf.salt), p)
        val res = guarded("login") { token ->
            nx.api.request("POST", "/auth/login", LoginResult.serializer(), buildJsonObject {
                put("username", name)
                put("auth_key", Bytes.b64(authKey))
            }, captcha = token, auth = false)
        }
        if (res.mfaRequired) {
            pendingEncKey = encKey
            return LoginStep.NeedsCode(res.ticket ?: "", res.backupCodesLeft)
        }
        finish(res, encKey)
        return LoginStep.Done
    }

    suspend fun completeMfa(ticket: String, code: String?, backupCode: String?) {
        val enc = pendingEncKey ?: throw ApiError(401, "mfa_expired", "That sign-in timed out. Enter your password again.")
        val res = nx.api.request("POST", "/auth/login/mfa", LoginResult.serializer(), buildJsonObject {
            put("ticket", ticket)
            if (!backupCode.isNullOrBlank()) put("backup_code", backupCode.trim()) else put("code", (code ?: "").replace(Regex("\\s"), ""))
        }, auth = false)
        finish(res, enc)
        pendingEncKey = null
    }

    /** Signs in with what a passkey or a phone (a QR code) handed over: the session and the identity key. */
    fun loginWith(me: MeUser, token: String, identity: Identity) {
        pendingEncKey = null
        nx.api.token = token
        nx.secure.putString("token", token)
        nx.secure.putString("account", me.id)
        saveIdentity(me.id, identity)
        begin(me, identity)
    }

    private fun finish(res: LoginResult, encKey: ByteArray) {
        val me = res.user ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        val token = res.token ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        val priv = try {
            Keys.unwrapPrivateKey(Bytes.unb64(res.encPrivateKey ?: ""), encKey)
        } catch (_: Exception) {
            throw DecryptError("Your keys could not be opened with that password.")
        }
        val pub = Identity.publicKeyOf(priv)
        val identity = Identity(pub, priv)
        nx.api.token = token
        nx.secure.putString("token", token)
        nx.secure.putString("account", me.id)
        saveIdentity(me.id, identity)
        begin(me, identity)
    }

    /**
     * A new account, without touching the one open here: every key is made here and
     * only sealed copies leave the device. Comes with the recovery key, shown once.
     */
    suspend fun createAccount(username: String, displayName: String, password: String, inviteCode: String?, registrationKey: String?): Pair<SignedIn, String> {
        val salt = Bytes.random(16)
        val p = Kdf.DEFAULT
        val (authKey, encKey) = stretch(password, salt, p)
        val identity = Identity.generate()
        val (recBytes, recDisplay) = Kdf.newRecoveryKey()
        val body = RegisterRequest(
            username = username.trim().lowercase(),
            displayName = displayName.trim(),
            authKey = Bytes.b64(authKey),
            kdfSalt = Bytes.b64(salt),
            kdfParams = KdfParamsJson(p.m, p.t, p.p, 1),
            publicKey = Bytes.b64(identity.publicKey),
            encPrivateKey = Bytes.b64(Keys.wrapPrivateKey(identity.privateKey, encKey)),
            encPrivateKeyRecovery = Bytes.b64(Keys.wrapPrivateKey(identity.privateKey, Kdf.recoveryEncKey(recBytes))),
            recoveryAuthKey = Bytes.b64(Kdf.recoveryAuthKey(recBytes)),
            inviteCode = inviteCode?.trim()?.takeIf { it.isNotEmpty() },
            registrationKey = registrationKey?.trim()?.takeIf { it.isNotEmpty() },
        )
        val res = guarded("register") { token ->
            nx.api.request("POST", "/auth/register", LoginResult.serializer(), wireJson.encodeToJsonElement(RegisterRequest.serializer(), body), captcha = token, auth = false)
        }
        val me = res.user ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        val token = res.token ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return SignedIn(me, token, identity) to recDisplay
    }

    /** The signed-out app's sign-up: the new account becomes the one open here once its key is saved. */
    suspend fun register(username: String, displayName: String, password: String, inviteCode: String?, registrationKey: String?): String {
        val (out, recovery) = createAccount(username, displayName, password, inviteCode, registrationKey)
        nx.api.token = out.token
        nx.secure.putString("token", out.token)
        nx.secure.putString("account", out.me.id)
        saveIdentity(out.me.id, out.identity)
        pendingRegistration = out.me to out.identity
        return recovery
    }

    /**
     * Keeps an account just created beside the one open here, without switching yet:
     * its recovery key still has to be saved, and losing the app meanwhile must not lose it.
     */
    fun keepCreated(out: SignedIn) {
        saveIdentity(out.me.id, out.identity)
        Accounts.remember(out.me, out.token)
    }

    /** Held until the person has saved their recovery key, then the app opens. */
    private var pendingRegistration: Pair<MeUser, Identity>? = null

    fun finishRegistration() {
        val (me, identity) = pendingRegistration ?: return
        pendingRegistration = null
        begin(me, identity)
    }

    /** Forgot the password: the recovery key opens the identity, a new password and recovery key replace the old. */
    suspend fun recover(username: String, recoveryKey: String, newPassword: String): String {
        val name = username.trim().lowercase()
        val rec = Kdf.recoveryKeyBytes(recoveryKey)
        val recAuth = Kdf.recoveryAuthKey(rec)
        val begin = guarded("recover") { token ->
            nx.api.request("POST", "/auth/recover/begin", RecoverBegin.serializer(), buildJsonObject {
                put("username", name)
                put("recovery_auth_key", Bytes.b64(recAuth))
            }, captcha = token, auth = false)
        }
        val priv = Keys.unwrapPrivateKey(Bytes.unb64(begin.encPrivateKeyRecovery), Kdf.recoveryEncKey(rec))
        val identity = Identity(Identity.publicKeyOf(priv), priv)
        val salt = Bytes.random(16)
        val p = Kdf.DEFAULT
        val (authKey, encKey) = stretch(newPassword, salt, p)
        val (newRec, newRecDisplay) = Kdf.newRecoveryKey()
        val res = nx.api.request("POST", "/auth/recover/finish", LoginResult.serializer(), buildJsonObject {
            put("username", name)
            put("recovery_auth_key", Bytes.b64(recAuth))
            put("new_auth_key", Bytes.b64(authKey))
            put("new_kdf_salt", Bytes.b64(salt))
            put("new_kdf_params", buildJsonObject { put("m", p.m); put("t", p.t); put("p", p.p); put("v", 1) })
            put("new_enc_private_key", Bytes.b64(Keys.wrapPrivateKey(priv, encKey)))
            put("new_recovery_auth_key", Bytes.b64(Kdf.recoveryAuthKey(newRec)))
            put("new_enc_private_key_recovery", Bytes.b64(Keys.wrapPrivateKey(priv, Kdf.recoveryEncKey(newRec))))
            put("ticket", begin.ticket)
        }, auth = false)
        val me = res.user ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        val token = res.token ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        nx.api.token = token
        nx.secure.putString("token", token)
        nx.secure.putString("account", me.id)
        saveIdentity(me.id, identity)
        pendingRegistration = me to identity
        return newRecDisplay
    }

    /** Signed in here but without the identity: the password opens it from the server's sealed copy. */
    suspend fun unlock(password: String) {
        val me = (_state.value as? AuthState.Locked)?.me ?: return
        val bundle = nx.api.get("/users/@me/keys", KeyBundle.serializer())
        val p = params(bundle.kdfParams)
        if (!Kdf.acceptable(p)) throw WeakKdfError("server asked for weak parameters")
        val (_, encKey) = stretch(password, Bytes.unb64(bundle.kdfSalt), p)
        val priv = try {
            Keys.unwrapPrivateKey(Bytes.unb64(bundle.encPrivateKey), encKey)
        } catch (_: Exception) {
            throw DecryptError("That password is wrong.")
        }
        val identity = Identity(Identity.publicKeyOf(priv), priv)
        saveIdentity(me.id, identity)
        begin(me, identity)
    }

    /** The current password's auth key, for the screens that re-check it (2FA, password change, delete). */
    suspend fun currentAuthKey(password: String): ByteArray {
        val bundle = nx.api.get("/users/@me/keys", KeyBundle.serializer())
        val p = params(bundle.kdfParams)
        if (!Kdf.acceptable(p)) throw WeakKdfError("server asked for weak parameters")
        return stretch(password, Bytes.unb64(bundle.kdfSalt), p).first
    }

    suspend fun changePassword(current: String, next: String) {
        val me = (_state.value as? AuthState.Ready)?.me ?: return
        val identity = nx.keys.identity ?: return
        val authKey = currentAuthKey(current)
        val salt = Bytes.random(16)
        val p = Kdf.DEFAULT
        val (newAuth, newEnc) = stretch(next, salt, p)
        nx.api.request("POST", "/auth/password", buildJsonObject {
            put("auth_key", Bytes.b64(authKey))
            put("new_auth_key", Bytes.b64(newAuth))
            put("new_kdf_salt", Bytes.b64(salt))
            put("new_kdf_params", buildJsonObject { put("m", p.m); put("t", p.t); put("p", p.p); put("v", 1) })
            put("new_enc_private_key", Bytes.b64(Keys.wrapPrivateKey(identity.privateKey, newEnc)))
        })
        Log.i(TAG, "password changed for ${me.id}")
    }

    suspend fun logout() {
        nx.session.stop()
        try {
            nx.api.request("POST", "/auth/logout")
        } catch (_: Exception) {
        }
        signOutLocally("logout")
    }

    private suspend fun signOutLocally(reason: String) {
        val uid = nx.users.meId ?: nx.secure.getString("account")
        nx.session.reset()
        nx.settings.reset()
        clearLocal(uid)
        Log.i(TAG, "signed out: $reason")
        // another account signed in on this phone carries on in its place
        if (uid != null) Accounts.forget(uid)
        val next = Accounts.next()
        if (next != null) open(next) else _state.value = AuthState.Anonymous
    }

    // ---- more than one account (web/src/lib/accounts.ts) ----

    /**
     * Puts the account open here away without signing it out: its call and
     * connection end and what it showed leaves memory, but its token stays on
     * the list and its keys where they are.
     */
    private suspend fun putAway() {
        fish.nexa.voice.Calls.leave()
        nx.settings.flush()
        nx.session.reset()
        nx.settings.reset()
        nx.keys.reset()
        nx.users.setMe(null)
        NexaInbox.reset()
        PrivateFiles.clear()
        fish.nexa.notes.Notes.clear()
        fish.nexa.media.Media.clear()
        nx.api.token = null
        nx.secure.delete("token")
    }

    private suspend fun open(account: Accounts.Saved) {
        nx.api.token = account.token
        nx.secure.putString("token", account.token)
        nx.secure.putString("account", account.id)
        _state.value = AuthState.Loading
        // waits for a check already running (the app starting) rather than
        // skipping, which left the splash up for good
        booting.withLock { bootstrapOnce() }
    }

    /** Opens the app as another account signed in on this phone. */
    suspend fun switchTo(id: String) {
        val to = Accounts.find(id) ?: return
        if (to.id == nx.users.meId) return
        putAway()
        open(to)
    }

    /** Signs out an account that is not the one open: its session on the server, then what this phone kept for it. */
    suspend fun removeAccount(id: String) {
        val acct = Accounts.find(id) ?: return
        if (id == nx.users.meId) return
        Accounts.forget(id)
        try {
            nx.api.request("POST", "/auth/logout", headers = mapOf("Authorization" to "Bearer ${acct.token}"), auth = false)
        } catch (_: Exception) {
        }
        nx.secure.delete("identity-$id")
        nx.secure.delete("roots-$id")
        AppLock.clear(id)
    }

    private fun clearLocal(uid: String?) {
        nx.api.token = null
        nx.secure.delete("token")
        if (uid != null) {
            nx.secure.delete("identity-$uid")
            nx.secure.delete("roots-$uid")
            AppLock.clear(uid)
        }
        adoptIdentity = null
        pendingPolicy = null
        nx.keys.reset()
        nx.users.setMe(null)
        NexaInbox.reset()
        PrivateFiles.clear()
        fish.nexa.notes.Notes.clear()
    }

    fun retryOffline() = nx.scope.launch { bootstrap() }

    // ---- account changes that need the password again (Settings → My Account) ----

    /** A new recovery key; the old one stops working at once. Returns the new one, to be shown once. */
    suspend fun newRecoveryKey(password: String): String {
        val identity = nx.keys.identity ?: throw IllegalStateException("locked")
        val authKey = currentAuthKey(password)
        val (rec, display) = Kdf.newRecoveryKey()
        nx.api.request("POST", "/auth/recovery", buildJsonObject {
            put("auth_key", Bytes.b64(authKey))
            put("recovery_auth_key", Bytes.b64(Kdf.recoveryAuthKey(rec)))
            put("enc_private_key_recovery", Bytes.b64(Keys.wrapPrivateKey(identity.privateKey, Kdf.recoveryEncKey(rec))))
        })
        nx.users.me.value?.let { nx.users.setMe(it.copy(hasRecovery = true)) }
        return display
    }

    /** Deletes the account for good, then signs out here. A purge that finishes later still signs out. */
    suspend fun deleteAccount(password: String) {
        val authKey = currentAuthKey(password)
        try {
            nx.api.request("POST", "/users/@me/delete", buildJsonObject { put("auth_key", Bytes.b64(authKey)) })
        } catch (e: ApiError) {
            if (e.code != "delete_incomplete") throw e
            nx.ui.toast(e.message ?: "", "info")
        }
        logout()
    }

    // ---- the passcode (stores/auth.ts: setPasscode, removePasscode, lockApp, requirePasscode, unlockWithPasscode) ----

    /** While a device takes on the account's passcode: the identity it keeps meanwhile, and the policy. */
    private var adoptIdentity: Identity? = null
    private var pendingPolicy: LockPolicy? = null

    /** Locks now, if this device has a passcode: everything readable leaves memory until it is typed. */
    fun lockApp() {
        val s = _state.value as? AuthState.Ready ?: return
        if (!AppLock.has(s.me.id)) return
        _state.value = AuthState.PinLocked(s.me, adopting = false)
        lockSession()
    }

    /**
     * What locking forgets: keys, messages, decrypted pictures and the
     * settings (saved sealed first). The connection closes too, unless a call
     * is on: then it stays, for the call's own events only.
     */
    private fun lockSession() {
        if (AppLock.inCall()) nx.session.lockedInCall = true else nx.session.stop()
        nx.keys.reset()
        nx.messages.clear()
        // notes are opened with keys of their own, which go with the rest
        fish.nexa.notes.Notes.clear()
        fish.nexa.media.Media.clear()
        nx.scope.launch {
            nx.settings.flush()
            if (_state.value is AuthState.PinLocked) nx.settings.reset()
        }
    }

    /** The passcode screen: opens this device, or (adopting) takes the account's passcode on here. */
    suspend fun unlockWithPasscode(passcode: String) {
        val s = _state.value as? AuthState.PinLocked ?: return
        if (s.adopting) {
            val identity = adoptIdentity ?: throw ReadableError(t("appLock.thisDeviceHasNoPasscode"))
            val policy = pendingPolicy ?: throw ReadableError(t("appLock.thisDeviceHasNoPasscode"))
            withContext(Dispatchers.Default) { AppLock.adopt(s.me.id, identity, passcode, policy) }
            forgetStored(s.me.id)
            adoptIdentity = null
            pendingPolicy = null
            resume(s.me, identity)
            return
        }
        val identity = withContext(Dispatchers.Default) { AppLock.unlock(s.me.id, passcode) }
        resume(s.me, identity)
    }

    /** A fingerprint or face opened the identity: as good as the passcode. */
    fun unlockWithBiometric(identity: Identity) {
        val s = _state.value as? AuthState.PinLocked ?: return
        if (s.adopting) return
        AppLock.succeeded(s.me.id)
        resume(s.me, identity)
    }

    /** Unlocked: the keys come back, and a fresh ready brings the rest. */
    private fun resume(me: MeUser, identity: Identity) {
        if (_state.value !is AuthState.PinLocked) return
        val inCall = nx.session.lockedInCall
        nx.session.lockedInCall = false
        begin(me, identity)
        if (inCall) nx.session.restart()
        LockTimers.activity()
        LockTimers.rearm()
    }

    /** The plain identity and the cached conversation keys go from the device: the passcode guards them now. */
    private fun forgetStored(userId: String) {
        nx.secure.delete("identity-$userId")
        nx.secure.delete("roots-$userId")
        nx.keys.persistRoots = false
    }

    /** Saves the settings now, once more after half a second; false when that failed both times. */
    private suspend fun saveSettingsNow(): Boolean {
        if (nx.settings.flush()) return true
        delay(500)
        return nx.settings.flush()
    }

    /** A passcode for this account, set here. Every device asks for it; a change needs no old one. */
    suspend fun setPasscode(passcode: String) {
        val me = (_state.value as? AuthState.Ready)?.me ?: throw ReadableError(t("auth.unlockYourKeysFirst"))
        val identity = nx.keys.identity ?: throw ReadableError(t("auth.unlockYourKeysFirst"))
        val previous = AppLock.load(me.id)
        val previousPolicy = nx.settings.doc.value["appLock"] ?: JsonNull
        val policy = withContext(Dispatchers.Default) { AppLock.enable(me.id, identity, passcode, previous?.timings ?: LockTimings()) }
        forgetStored(me.id)
        LockTimers.rearm()
        nx.settings.set("appLock", AppLock.policyJson(policy))
        if (saveSettingsNow()) return
        if (previous != null) AppLock.save(me.id, previous) else {
            AppLock.clear(me.id)
            saveIdentity(me.id, identity)
            nx.keys.persistRoots = true
        }
        nx.settings.set("appLock", previousPolicy)
        LockTimers.rearm()
        throw ReadableError(t("auth.couldNotSaveThatToYour"))
    }

    /** No passcode any more, on this device or the account's others. */
    suspend fun removePasscode() {
        val me = (_state.value as? AuthState.Ready)?.me ?: return
        val identity = nx.keys.identity ?: throw ReadableError(t("auth.unlockYourKeysFirst"))
        val previous = AppLock.load(me.id)
        val previousPolicy = nx.settings.doc.value["appLock"] ?: JsonNull
        AppLock.clear(me.id, biometric = false)
        LockTimers.rearm()
        saveIdentity(me.id, identity)
        nx.keys.persistRoots = true
        nx.settings.set("appLock", JsonNull)
        if (saveSettingsNow()) {
            BiometricLock.forget(me.id)
            return
        }
        previous?.let { AppLock.save(me.id, it) }
        nx.settings.set("appLock", previousPolicy)
        forgetStored(me.id)
        LockTimers.rearm()
        throw ReadableError(t("auth.couldNotSaveThatToYour"))
    }

    /** This device's lock timings; the account's policy takes them too, for devices that adopt it later. */
    fun setLockTimings(timings: LockTimings) {
        val me = nx.users.meId ?: return
        AppLock.patch(me, timings) ?: return
        LockTimers.rearm()
        (nx.settings.doc.value["appLock"] as? JsonObject)?.let { nx.settings.set("appLock", AppLock.policyWithTimings(it, timings)) }
    }

    /**
     * Run at every ready once the settings are in (applyLockPolicy): an
     * account passcode this device lacks is asked for before going on; a
     * passcode removed elsewhere is removed here.
     */
    fun applyLockPolicy() {
        val s = _state.value as? AuthState.Ready ?: return
        if (!nx.settings.loaded.value) return
        val doc = nx.settings.doc.value
        val has = AppLock.has(s.me.id)
        if (AppLock.policySet(doc) && !has) {
            val policy = AppLock.policyOf(doc) ?: return
            adoptIdentity = nx.keys.identity ?: return
            pendingPolicy = policy
            _state.value = AuthState.PinLocked(s.me, adopting = true)
            lockSession()
        } else if (!AppLock.policySet(doc) && has) {
            val identity = nx.keys.identity ?: return
            AppLock.clear(s.me.id)
            saveIdentity(s.me.id, identity)
            nx.keys.persistRoots = true
            LockTimers.rearm()
        }
    }

    companion object {
        private const val TAG = "NexaAuth"
    }
}
