package fish.nexa.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.util.Log
import fish.nexa.BuildConfig
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.net.wireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Over-the-air content packs: the files the build copies in from the web
 * (CopyWebAssets: translations, What's New, the Terms and Privacy Policy and
 * their version, sounds, seasonal pictures) updated without a new APK.
 *
 * tools/android_release.py publishes a pack (downloads/android/ota.json and
 * ota-<hash>.zip) when only those files changed since the published APK. A
 * pack names the one versionCode it was made for, so it never reaches a
 * build whose code expects other files. The app checks for one at start and
 * every fifteen minutes in front, downloads it, checks its SHA-256, unpacks it
 * into a new folder and takes it up on the next start (the catalogues are
 * read once). Every reader asks [open] first, which falls back to the APK's
 * own asset. Kotlin code cannot change this way: that still takes an APK.
 */
object Ota {
    @Serializable
    data class Pack(
        val id: String = "",
        @SerialName("version_code") val versionCode: Int = 0,
        val name: String = "",
        val size: Long = 0,
        val sha256: String = "",
    )

    private const val TAG = "Ota"
    private const val FIRST_CHECK_MS = 15_000L
    private const val EVERY_MS = 15 * 60_000L
    /** The folders a pack may write: what CopyWebAssets copies, minus twemoji (its tables change with Kotlin code). */
    private val ALLOWED = Regex("^(locales|sounds|seasonal|whatsnew|legal)/[A-Za-z0-9][A-Za-z0-9._-]{0,100}$")
    private const val MAX_PACK = 64L shl 20

    private lateinit var app: Context
    /** The pack in use this run, fixed at start so a screen never mixes two. */
    @Volatile private var active: File? = null
    private var loop: Job? = null
    private val busy = AtomicBoolean(false)

    private fun root() = File(app.noBackupFilesDir, "ota").apply { mkdirs() }
    private fun prefs() = app.getSharedPreferences("ota", Context.MODE_PRIVATE)

    /** At process start, before anything reads an asset: takes up a staged pack and drops what is stale. */
    fun init(context: Context) {
        app = context.applicationContext
        runCatching {
            val p = prefs()
            val staged = p.getString("staged", null)
            if (staged != null && p.getInt("staged_for", -1) == BuildConfig.VERSION_CODE && File(root(), staged).isDirectory) {
                p.edit().putString("active", staged).putInt("active_for", BuildConfig.VERSION_CODE).remove("staged").remove("staged_for").commit()
            }
            val id = p.getString("active", null)
            // a pack is for the APK it was made for: after an update the new APK's own files win
            active = if (id != null && p.getInt("active_for", -1) == BuildConfig.VERSION_CODE) File(root(), id).takeIf { it.isDirectory } else null
            if (active == null && id != null) p.edit().remove("active").remove("active_for").commit()
            val keep = setOfNotNull(active?.name, p.getString("staged", null))
            root().listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
        }.onFailure { Log.w(TAG, "could not read the content pack", it) }
    }

    /** The pack in use, for Settings. */
    val activeId: String? get() = active?.name

    private fun file(path: String): File? = active?.let { File(it, path) }?.takeIf { it.isFile }

    /** An asset as this phone has it: the pack's copy when there is one, else the APK's. */
    fun open(path: String): InputStream = file(path)?.inputStream() ?: app.assets.open(path)

    /** For WebView and Coil: a URL for the asset. */
    fun uri(path: String): String = file(path)?.let { "file://" + it.absolutePath } ?: "file:///android_asset/$path"

    /** Points a MediaPlayer at an asset. */
    fun setDataSource(mp: MediaPlayer, path: String) {
        val f = file(path)
        if (f != null) mp.setDataSource(f.absolutePath)
        else app.assets.openFd(path).use { fd: AssetFileDescriptor -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
    }

    fun start() {
        if (loop != null) return
        loop = Nexa.scope.launch {
            delay(FIRST_CHECK_MS)
            while (isActive) {
                if (Nexa.ui.visible.value) check()
                delay(EVERY_MS)
            }
        }
    }

    suspend fun check() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val el = Nexa.api.request("GET", "/android/ota?version_code=${BuildConfig.VERSION_CODE}", headers = mapOf("Cache-Control" to "no-cache"), auth = false)
            val pack = wireJson.decodeFromJsonElement(Pack.serializer(), el)
            if (pack.id.isEmpty() || pack.versionCode != BuildConfig.VERSION_CODE) return
            val p = prefs()
            if (pack.id == active?.name || pack.id == p.getString("staged", null)) return
            if (!Regex("^[0-9a-f]{16,64}$").matches(pack.id) || pack.size !in 1..MAX_PACK || !Regex("^[0-9a-f]{64}$").matches(pack.sha256)) return
            withContext(Dispatchers.IO) { stage(pack) }
            p.edit().putString("staged", pack.id).putInt("staged_for", pack.versionCode).commit()
            Log.i(TAG, "content pack ${pack.id} ready for the next start")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "content pack check failed", e)
        } finally {
            busy.set(false)
        }
    }

    private suspend fun stage(pack: Pack) {
        val zip = File(root(), "${pack.id}.zip.part")
        val url = Nexa.api.baseUrl + "/android/" + pack.name + "?v=" + pack.sha256.take(12)
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", Nexa.api.userAgent).get().build()
        Nexa.api.await(Nexa.api.transfer.newCall(req)).use { res ->
            if (!res.isSuccessful) throw java.io.IOException("the server answered ${res.code}")
            zip.outputStream().use { out -> res.body.byteStream().use { it.copyTo(out) } }
        }
        try {
            if (zip.length() != pack.size || !Bytes.hex(sha256(zip)).equals(pack.sha256, ignoreCase = true)) throw java.io.IOException("the pack does not match its fingerprint")
            val dir = File(root(), pack.id)
            val tmp = File(root(), "${pack.id}.tmp")
            tmp.deleteRecursively()
            tmp.mkdirs()
            var total = 0L
            ZipInputStream(zip.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (!ALLOWED.matches(e.name)) throw java.io.IOException("the pack holds ${e.name}")
                    val f = File(tmp, e.name)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_PACK * 4) throw java.io.IOException("the pack unpacks too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw java.io.IOException("could not keep the pack")
        } finally {
            zip.delete()
        }
    }

    private fun sha256(f: File): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest()
    }
}
