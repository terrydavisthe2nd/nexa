package fish.nexa.media

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.DecryptError
import fish.nexa.crypto.FileCrypto
import fish.nexa.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures and files. Public pictures (avatars, icons, banners, emoji,
 * stickers) are plain HTTPS through Coil with its caches. Attachments are
 * end-to-end encrypted: they are fetched with the session's token, checked
 * against the size the message gave, decrypted chunk by chunk into a cache
 * only this app can read (cleared on sign-out), and drawn from there.
 */
object Media {
    /** The smallest power of two at least css x density, 16..1024 (users.ts sized()). */
    fun sized(css: Int, density: Float): Int {
        val want = css * density.coerceIn(1f, 3f)
        var n = 16
        while (n < want && n < 1024) n *= 2
        return n
    }

    private fun base() = Nexa.api.baseUrl
    private fun still(key: String): String = if ((Nexa.settings.doc.value[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "false") "&still=1" else ""

    // &v=3 replaces cached still GIFs and incorrectly oriented photo variants
    // with copies made by the corrected server (api/users.ts MOVING).
    /** The avatar as uploaded, for the picture viewer (avatarUrl(id) with no size on the web). */
    fun avatarOriginalUrl(id: String?): String? = id?.let { "${base()}/media/avatar/$it?v=3" }

    fun avatarUrl(id: String?, css: Int, density: Float): String? = id?.let { "${base()}/media/avatar/$it?size=${sized(css, density)}&v=3" + still("animateAvatars") }
    /** The banner as uploaded, for the picture viewer. */
    fun bannerOriginalUrl(id: String?): String? = id?.let { "${base()}/media/banner/$it?v=3" }

    fun bannerUrl(id: String?, css: Int, density: Float): String? = id?.let { "${base()}/media/banner/$it?size=${sized(css, density)}&v=3" + still("animateBanners") }
    fun iconUrl(id: String?, css: Int, density: Float): String? = id?.let { "${base()}/media/icon/$it?size=${sized(css, density)}&v=3" + still("animateIcons") }
    fun emojiUrl(blobId: String, css: Int, density: Float, still: Boolean = false): String =
        "${base()}/media/emoji/$blobId?size=${sized(css, density)}" + if (still) "&still=1" else still("animateEmoji")
    fun stickerUrl(blobId: String, css: Int, density: Float): String = "${base()}/media/sticker/$blobId?size=${sized(css, density)}" + still("animateStickers")
    fun tagUrl(id: String, css: Int, density: Float): String = "${base()}/media/tag/$id?size=${sized(css, density).coerceIn(16, 128)}"
    fun typingUrl(id: String, css: Int, density: Float): String = "${base()}/media/typing/$id?size=${sized(css, density)}"
    fun widgetUrl(id: String, css: Int, density: Float): String = "${base()}/media/widget/$id?size=${sized(css, density)}"

    /** A link a message carries: absolute, or a path on this server. */
    fun absolute(url: String): String = if (url.startsWith("/")) base() + url else url

    lateinit var loader: ImageLoader
        private set

    private lateinit var cacheDir: File

    private val BLOB_ID = Regex("[0-9]{1,20}")

    /** Message metadata must never become a path outside the attachment cache. */
    private fun cacheFile(blobId: String, plain: Boolean = false): File {
        require(BLOB_ID.matches(blobId)) { "Invalid attachment id" }
        val owner = checkNotNull(Nexa.users.meId) { "No account is open" }
        require(BLOB_ID.matches(owner)) { "Invalid account id" }
        return File(cacheDir, "$owner-${if (plain) "plain-" else ""}$blobId")
    }

    @OptIn(ExperimentalCoilApi::class)
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "decrypted").apply { mkdirs() }
        loader = ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Nexa.api.http }))
                add(EncryptedFetcher.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.22).build() }
            .diskCache { DiskCache.Builder().directory(File(context.cacheDir, "pictures").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
            .build()
        // every AsyncImage gets these caches and the app's connection, whether or not it names the loader
        coil3.SingletonImageLoader.setSafe { loader }
    }

    // ---- encrypted attachments ----

    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The decrypted file for an attachment, fetched and opened once and then
     * kept in the private cache. [plainSize] is the size the message gave; the
     * download must be exactly its encrypted size, or it is refused unopened.
     */
    suspend fun decrypted(blobId: String, keyB64: String, plainSize: Long?, onProgress: ((Long, Long) -> Unit)? = null): File {
        val out = cacheFile(blobId)
        if (out.exists() && out.length() > 0 && (plainSize == null || out.length() == plainSize)) return out
        val lock = locks.getOrPut(blobId) { Mutex() }
        return lock.withLock {
            if (out.exists() && out.length() > 0 && (plainSize == null || out.length() == plainSize)) return@withLock out
            withContext(Dispatchers.IO) {
                val key = Bytes.unb64(keyB64)
                val req = Nexa.api.requestBuilder("/media/attachments/$blobId").get().build()
                val res = Nexa.api.await(Nexa.api.transfer.newCall(req))
                res.use { r ->
                    if (!r.isSuccessful) throw ApiError(r.code, "http_${r.code}", "The file could not be downloaded.")
                    val len = r.body.contentLength()
                    if (plainSize != null && len >= 0 && r.header("Content-Encoding") == null && len != FileCrypto.encryptedSize(plainSize)) {
                        throw DecryptError("file: the server sent a file of another size")
                    }
                    val tmp = File(cacheDir, "${out.name}.part")
                    try {
                        withReadAhead(r.body.byteStream()) { input ->
                            tmp.outputStream().buffered(1 shl 16).use { os ->
                                FileCrypto.decryptStream(input.buffered(1 shl 16), key, os, expectedSize = plainSize) { done -> onProgress?.invoke(done, plainSize ?: len) }
                            }
                        }
                        if (!tmp.renameTo(out)) throw java.io.IOException("could not keep the file")
                    } finally {
                        tmp.delete()
                    }
                }
                trim()
                out
            }
        }
    }

    /** A file already opened on this device, or null; touching it keeps it in the cache longer. */
    fun cached(blobId: String, plain: Boolean = false): File? {
        val f = cacheFile(blobId, plain)
        if (!f.exists() || f.length() == 0L) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /** How much opened media the cache keeps before the least recently shown goes. */
    private const val DISK_BUDGET = 1024L * 1024 * 1024

    /** Keeps the opened files within the budget, oldest first; a file being written is never touched. */
    fun trim() {
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BUDGET) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BUDGET) break
            val n = f.length()
            if (f.delete()) total -= n
        }
    }

    /** A webhook's plain file: fetched with the token and kept as it is. */
    suspend fun plain(blobId: String): File {
        val out = cacheFile(blobId, plain = true)
        if (out.exists() && out.length() > 0) return out
        return withContext(Dispatchers.IO) {
            val req = Nexa.api.requestBuilder("/media/attachments/$blobId").get().build()
            Nexa.api.await(Nexa.api.transfer.newCall(req)).use { r ->
                if (!r.isSuccessful) throw ApiError(r.code, "http_${r.code}", "The file could not be downloaded.")
                val tmp = File(cacheDir, "${out.name}.part")
                tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                tmp.renameTo(out)
            }
            out
        }
    }

    /** Seeds the cache with a file this device just sent, so it shows without downloading it back. */
    fun seed(blobId: String, plainFile: File) {
        val out = cacheFile(blobId)
        if (!out.exists()) plainFile.copyTo(out, overwrite = true)
    }

    /** The same, from bytes in memory (a picture's small copy). */
    fun seedBytes(blobId: String, bytes: ByteArray) {
        val out = cacheFile(blobId)
        if (!out.exists()) out.writeBytes(bytes)
    }

    fun clear() {
        if (::cacheDir.isInitialized) cacheDir.listFiles()?.forEach { it.delete() }
        try {
            loader.memoryCache?.clear()
        } catch (_: Exception) {
        }
    }
}

/** What Coil is asked to draw for an encrypted picture. */
data class EncryptedImage(val blobId: String, val key: String, val size: Long?, val plain: Boolean = false)

class EncryptedFetcher(private val data: EncryptedImage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = if (data.plain) Media.plain(data.blobId) else Media.decrypted(data.blobId, data.key, data.size)
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<EncryptedImage> {
        override fun create(data: EncryptedImage, options: Options, imageLoader: ImageLoader): Fetcher = EncryptedFetcher(data, options)
    }
}
