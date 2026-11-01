package fish.nexa.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import fish.nexa.BuildConfig
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.net.wireJson
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.openDialog
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.brandGradient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own updates, kept the way the desktop app keeps its own
 * (desktop/src/main/update): a first look ten seconds after the app opens,
 * then one every minute while it is in front, and a longer wait after each
 * look that fails, up to fifteen minutes. The server publishes the newest
 * build at /api/v1/android/latest; when it is newer than this copy, the build
 * for this phone's processor downloads in the background straight away
 * (picking up where a broken download stopped) and is checked against its
 * published fingerprint. Only then is it offered, so Install is instant: it
 * hands the build to Android's package installer, which asks once and
 * replaces this copy, and says whether that worked. Settings, About shows
 * where it stands and can look again; the user panel carries a green button
 * while a build waits to be installed.
 */
object AppUpdate {
    /** One published build: for one processor, or "universal" for any. */
    @Serializable
    data class Apk(val abi: String, val name: String, val size: Long = 0, val sha256: String)

    @Serializable
    data class Release(
        val version: String = "",
        @SerialName("version_code") val versionCode: Int = 0,
        val files: List<Apk> = emptyList(),
        val notes: String? = null,
    )

    /** Where the updater stands, for Settings and the user panel. */
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val version: String, val progress: Float) : State
        data class Ready(val version: String) : State
        data class Installing(val version: String) : State
        /**
         * A look or a download that failed; the next one comes on its own, a
         * little later each time. `stopped` is a download cut off part way
         * (`reason` is then its version): the next try carries on from there.
         */
        data class Failed(val reason: String, val stopped: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    /** When the server was last asked, whatever it answered; 0 before the first time. */
    private val _lastCheck = MutableStateFlow(0L)
    val lastCheck: StateFlow<Long> = _lastCheck

    private const val FIRST_CHECK_MS = 10_000L
    private const val EVERY_MS = 60_000L
    /** The longest a run of failed looks waits before trying again. */
    private const val MAX_BACKOFF_MS = 15 * 60_000L

    /** A debug build is its own app (fish.nexa.debug) and is never replaced by a release. */
    val enabled: Boolean get() = !BuildConfig.DEBUG

    private var loop: Job? = null
    private val busy = AtomicBoolean(false)
    private var failures = 0
    private var nextAt = 0L
    /** The build downloaded and checked, waiting to be installed. */
    @Volatile private var ready: Pair<Release, File>? = null
    /** The version offered in a dialog this run, and one put off with Later: neither is offered again until the app starts anew. */
    private var offered = 0
    private var putOff = 0
    /** Install was pressed and Android asked first whether Nexa may install apps: install as soon as that is allowed. */
    @Volatile private var installWhenAllowed = false

    /** Starts looking: ten seconds from now, then every minute while the app is in front. */
    fun start() {
        if (!enabled || loop != null) return
        cleanOld()
        loop = Nexa.scope.launch {
            delay(FIRST_CHECK_MS)
            while (isActive) {
                // in front, and its turn: a look that failed waits longer before the next
                if (Nexa.ui.visible.value && System.currentTimeMillis() >= nextAt) check(quiet = true)
                delay(EVERY_MS)
            }
        }
    }

    /** The app came back to the front: install what waited on Android's permission, or look now if a minute has gone by. */
    fun resume() {
        if (!enabled) return
        // back from Android's screens: a call may float again when the app is left
        fish.nexa.voice.Pip.held = false
        start()
        if (installWhenAllowed && canInstall()) {
            installWhenAllowed = false
            install()
            return
        }
        val now = System.currentTimeMillis()
        if (now - _lastCheck.value >= EVERY_MS && now >= nextAt) Nexa.scope.launch { check(quiet = true) } else offerIfReady()
    }

    /**
     * Looks for a newer build and fetches it. The minute-by-minute looks are
     * quiet: they leave Settings' line alone unless something changes. One
     * asked for from Settings always runs and says it is looking.
     */
    suspend fun check(quiet: Boolean = false) {
        if (!enabled || !busy.compareAndSet(false, true)) return
        try {
            if (!quiet || _state.value is State.Failed) _state.value = State.Checking
            val rel = wireJson.decodeFromJsonElement(Release.serializer(), Nexa.api.request("GET", "/android/latest", headers = mapOf("Cache-Control" to "no-cache"), auth = false))
            _lastCheck.value = System.currentTimeMillis()
            val file = pick(rel)
            if (rel.versionCode <= BuildConfig.VERSION_CODE || file == null) {
                ready = null
                _state.value = State.UpToDate
            } else {
                val have = ready
                val apk = if (have != null && have.first.versionCode == rel.versionCode && have.second.exists()) have.second else {
                    // a build waiting to be installed is replaced: its file goes when this one downloads
                    ready = null
                    try {
                        download(rel, file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw Stopped(rel.version, e)
                    }
                }
                ready = rel to apk
                _state.value = State.Ready(rel.version)
                offerIfReady()
            }
            failures = 0
            nextAt = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
            nextAt = System.currentTimeMillis() + minOf(MAX_BACKOFF_MS, EVERY_MS shl minOf(failures, 8))
            _lastCheck.value = System.currentTimeMillis()
            _state.value = if (e is Stopped) State.Failed(e.version, stopped = true) else State.Failed(e.message?.takeIf { it.isNotBlank() } ?: t("settings.desktop.unknownError"))
        } finally {
            busy.set(false)
        }
    }

    /** A download that broke off, for the line that says so (the check before it went through). */
    private class Stopped(val version: String, cause: Throwable) : Exception(cause)

    /** The build for this phone: its processor's own, else the one that installs anywhere. */
    private fun pick(rel: Release): Apk? {
        for (abi in Build.SUPPORTED_ABIS) rel.files.firstOrNull { it.abi == abi }?.let { return it }
        return rel.files.firstOrNull { it.abi == "universal" }
    }

    private fun dir(): File = File(Nexa.app.cacheDir, "updates").apply { mkdirs() }

    /** Builds left from versions already installed, and anything half-written by an older copy of this code. */
    private fun cleanOld() {
        runCatching { dir().listFiles()?.forEach { if (!it.name.startsWith("nexa-")) it.delete() } }
    }

    /**
     * Downloads a build into the cache and checks it against its published
     * fingerprint. A build already here and whole is used as it is; a download
     * that was cut off goes on from where it stopped. Throws when the server
     * cannot be reached or the file does not match.
     */
    private suspend fun download(rel: Release, file: Apk): File = withContext(Dispatchers.IO) {
        val tag = file.sha256.take(16)
        val out = File(dir(), "nexa-$tag.apk")
        if (out.exists() && out.length() == file.size && sha256(out).equals(file.sha256, ignoreCase = true)) return@withContext out
        // whatever else is here is an older build, or one this release replaced
        dir().listFiles()?.forEach { if (it.name != "nexa-$tag.part") it.delete() }
        val part = File(dir(), "nexa-$tag.part")
        val from = if (part.exists() && part.length() in 1 until file.size) part.length() else 0L
        if (from == 0L) part.delete()
        val url = Nexa.api.baseUrl + "/android/" + java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20") + "?v=" + file.sha256.take(12)
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", Nexa.api.userAgent).apply {
            // the same bytes or none: an If-Range on the fingerprint the server tags them with
            if (from > 0) header("Range", "bytes=$from-").header("If-Range", "\"${file.sha256}\"")
        }.get().build()
        _state.value = State.Downloading(rel.version, from.toFloat() / file.size.coerceAtLeast(1))
        Nexa.api.await(Nexa.api.transfer.newCall(req)).use { res ->
            if (!res.isSuccessful) throw java.io.IOException("the server answered ${res.code}")
            val resumed = res.code == 206 && from > 0
            var done = if (resumed) from else 0L
            FileOutputStream(part, resumed).use { sink ->
                res.body.byteStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var shown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        // a few times a second is plenty for a progress bar
                        if (done - shown >= 256 * 1024 || done == file.size) {
                            shown = done
                            _state.value = State.Downloading(rel.version, (done.toFloat() / file.size.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        if (part.length() != file.size || !sha256(part).equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw java.io.IOException(t("android.update.failed"))
        }
        if (!part.renameTo(out)) throw java.io.IOException(t("android.update.failed"))
        out
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return Bytes.hex(digest.digest())
    }

    /** Offers a downloaded build once per run: when the Terms are agreed and nothing else is open over the app. */
    private fun offerIfReady() {
        val (rel, _) = ready ?: return
        if (rel.versionCode == putOff || rel.versionCode == offered) return
        offered = rel.versionCode
        Nexa.scope.launch(Dispatchers.Main) {
            while (!Layers.isEmpty || !Nexa.ui.visible.value || Nexa.users.me.value?.let { fish.nexa.ui.legal.Legal.accepted(it.id) } != true) delay(1000)
            if (ready?.first?.versionCode == rel.versionCode) offer(rel)
        }
    }

    private fun offer(rel: Release) {
        openDialog(
            "app-update",
            t("shell.update.title"),
            width = 440.dp,
            onClosed = { if (_state.value !is State.Installing) putOff = rel.versionCode },
            footer = { close ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                    NxButton(t("shell.update.later"), close, kind = ButtonKind.GHOST)
                    NxButton(t("android.update.install"), {
                        install()
                        if (canInstall()) close()
                    })
                }
            },
        ) {
            val c = Nx.colors
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(t("android.update.readyBody"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(22.5f)), color = c.textSubtle)
                Txt(t("shell.update.version", "version" to rel.version), style = Nx.type.bodySmall, color = c.textMuted)
                rel.notes?.takeIf { it.isNotBlank() }?.let { Txt(it, style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(20f)), color = c.text) }
                if (installWhenAllowed) Txt(t("android.update.allowInstalls"), style = Nx.type.bodySmall.copy(lineHeight = Nx.type.sp(20f)), color = c.text)
            }
        }
    }

    /**
     * Installs the build that is waiting: through Android's package installer,
     * which asks once and then replaces this copy. Where Android has not let
     * Nexa install apps yet, its own screen for that opens first, and the
     * install goes on by itself when the person comes back with it allowed.
     */
    fun install() {
        val (rel, apk) = ready ?: return
        // Android's screens for this open over the app: a call must not float over their buttons
        fish.nexa.voice.Pip.held = true
        if (!canInstall()) {
            installWhenAllowed = true
            runCatching {
                Nexa.app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + Nexa.app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Nexa.ui.toast(t("android.update.allowInstalls"))
            return
        }
        _state.value = State.Installing(rel.version)
        Nexa.scope.launch(Dispatchers.IO) {
            val committed = runCatching { commitSession(apk) }.isSuccess
            // a phone whose installer will not take a session still opens the file the way it always could
            if (!committed) withContext(Dispatchers.Main) { installByIntent(apk) }
        }
    }

    private fun commitSession(apk: File) {
        val ctx = Nexa.app
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+: once Nexa installed itself, its updates go in without Android's own prompt
            // (UPDATE_PACKAGES_WITHOUT_USER_ACTION), so a fix lands with one tap on Install
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            // Android 14+: claim update ownership, so other stores do not take updates over silently
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java).setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Hands the build to Android's installer as a file, for a phone whose installer would not take a session. */
    private fun installByIntent(apk: File) {
        val uri = FileProvider.getUriForFile(Nexa.app, Nexa.app.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { Nexa.app.startActivity(intent) }.isFailure) installFailed(null)
    }

    /** Android's installer said no, or the person backed out of it: the build stays ready for another try. */
    internal fun installFailed(message: String?, cancelled: Boolean = false) {
        fish.nexa.voice.Pip.held = false
        val (rel, _) = ready ?: return
        _state.value = State.Ready(rel.version)
        if (!cancelled) Nexa.ui.toast(t("android.update.installFailed", "reason" to (message?.takeIf { it.isNotBlank() } ?: t("settings.desktop.unknownError"))), "error")
    }

    /** Whether Android lets Nexa hand a build to its installer (asked once, on Android's own screen). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || Nexa.app.packageManager.canRequestPackageInstalls()

    /** Settings' line for where things stand, and its button. */
    @androidx.compose.runtime.Composable
    fun StatusLine(modifier: Modifier = Modifier) {
        val c = Nx.colors
        val st by state.collectAsState()
        val last by lastCheck.collectAsState()
        val visible by Nexa.ui.visible.collectAsState()
        LaunchedEffect(visible) { if (visible) resume() }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val line = when (val s = st) {
                State.Idle -> if (enabled) "" else t("android.update.debugBuild")
                State.Checking -> t("settings.desktop.checkingForUpdates")
                State.UpToDate -> t("android.update.upToDate")
                is State.Downloading -> t("settings.desktop.downloadingVersion", "version" to s.version)
                is State.Ready -> t("settings.desktop.versionIsReadyToInstall", "version" to s.version)
                is State.Installing -> t("android.update.installing", "version" to s.version)
                is State.Failed -> if (s.stopped) t("android.update.downloadStopped", "version" to s.reason) else t("settings.desktop.couldNotCheckForUpdates", "error" to s.reason)
            }
            val tail = if (last > 0 && st !is State.Checking) t("settings.desktop.lastChecked", "when" to Time.relative(last)) else ""
            if (line.isNotEmpty() || tail.isNotEmpty()) Txt((line + tail).trim(), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19f)), color = if (st is State.Failed) c.textDanger else c.textSubtle)
            (st as? State.Downloading)?.let { d ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(c.bgInput, RoundedCornerShape(3.dp))
                        .semantics {
                            contentDescription = t("shell.update.progress")
                            progressBarRangeInfo = ProgressBarRangeInfo(d.progress, 0f..1f)
                        },
                ) { Box(Modifier.fillMaxWidth(maxOf(0.04f, d.progress)).height(6.dp).background(brandGradient(c), RoundedCornerShape(3.dp))) }
            }
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st is State.Ready) NxButton(t("android.update.install"), { install() })
                    else NxButton(t("settings.desktop.checkForUpdates"), { Nexa.scope.launch { check(quiet = false) } }, kind = ButtonKind.SECONDARY, loading = st is State.Checking || st is State.Downloading || st is State.Installing)
                }
            }
        }
    }
}

/** What Android's package installer says of an update it was handed (AppUpdate.commitSession). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android asks the person first: its own screen, over the app they just pressed Install in
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null || runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) AppUpdate.installFailed(null)
            }
            // the new copy replaces this one, which Android closes; it opens again from the launcher
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> AppUpdate.installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED)
        }
    }
}
