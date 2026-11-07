package fish.nexa.ui.components

import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import fish.nexa.Nexa
import fish.nexa.data.Power
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * What only decorates and goes round for ever: name effects, moving pictures,
 * stickers, snow. Any of it keeps the whole window drawing and the display at
 * full rate for as long as it shows, so it steps at [FRAME_MS] on one clock,
 * all in the same frame, and holds its frame once nobody has touched the phone
 * for [IDLE_MS], under Battery Saver, and under an open drawer. A touch sets it
 * going again.
 */
object Decor {
    private const val IDLE_MS = 20_000L
    const val FRAME_MS = 33L

    /** Decoration may move. */
    var running by mutableStateOf(true)
        private set

    private val idle = MutableStateFlow(false)
    private val covered = MutableStateFlow(false)
    @Volatile private var lastTouch = SystemClock.uptimeMillis()
    private val main = Handler(Looper.getMainLooper())

    /** Moving pictures on screen; they go when nothing holds them any more. */
    private val pictures = ArrayList<WeakReference<Animatable>>()
    /** Those in the page under an open sheet. What opens in the sheet itself (the GIF picker) is not among them. */
    private var underSheet: Set<Animatable> = emptySet()

    fun install() {
        // coming back to the app counts as a touch
        Nexa.scope.launch { Nexa.ui.visible.collect { if (it) touched() } }
        // only counted while the app shows, and looked at only when it may have run out
        Nexa.scope.launch {
            combine(Nexa.ui.visible, idle) { v, i -> v && !i }.collectLatest { counting ->
                if (!counting) return@collectLatest
                while (true) {
                    val left = lastTouch + IDLE_MS - SystemClock.uptimeMillis()
                    if (left <= 0) break
                    delay(left)
                }
                idle.value = true
            }
        }
        Nexa.scope.launch {
            combine(idle, Power.saver, covered) { i, s, c -> !i && !s && !c }.collect { on -> main.post { apply(on) } }
        }
    }

    /** Every touch. */
    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
        if (idle.value) idle.value = false
    }

    /** A drawer lies open over the page. */
    fun setCovered(on: Boolean) {
        covered.value = on
    }

    /** A sheet or dialog opened or closed over the page, which it blurs. */
    fun setSheetOpen(open: Boolean) {
        main.post {
            if (open == underSheet.isNotEmpty()) return@post
            if (open) {
                underSheet = pictures.mapNotNull { it.get() }.toSet()
                underSheet.forEach { it.stop() }
            } else {
                val held = underSheet
                underSheet = emptySet()
                if (running) held.forEach { it.start() }
            }
        }
    }

    private fun apply(on: Boolean) {
        if (running == on) return
        running = on
        pictures.removeAll { it.get() == null }
        for (p in pictures) p.get()?.let { if (!on) it.stop() else if (it !in underSheet) it.start() }
    }

    /** A moving picture just decoded: starts when it first draws, so one that arrives while all is still is stopped after that. */
    private fun track(picture: Animatable) {
        main.post {
            pictures.add(WeakReference(picture))
            if (pictures.size > 256) pictures.removeAll { it.get() == null }
            main.postDelayed({ if (!running) picture.stop() }, 300)
        }
    }

    /** Wraps Coil's decoder for moving pictures, so what it makes can be held. */
    class Tracked(private val of: Decoder.Factory) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val decoder = of.create(result, options, imageLoader) ?: return null
            return Decoder {
                decoder.decode()?.also { r ->
                    ((r.image as? coil3.DrawableImage)?.let { it.drawable } as? Animatable)?.let(::track)
                }
            }
        }
    }
}

/** True for a part of the screen that is covered and should hold still whatever else moves (the page under a sheet or drawer). */
val LocalDecorHeld = compositionLocalOf { false }

/**
 * Goes from 0 to 1 every [periodMs], on the shared clock, for anything that
 * loops. [informs] is for what says something (someone is typing), which goes
 * on through idle and Battery Saver.
 */
@Composable
fun decorClock(periodMs: Int, informs: Boolean = false): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val on = !LocalDecorHeld.current && (informs || Decor.running)
    LaunchedEffect(periodMs, on) {
        if (!on) return@LaunchedEffect
        while (true) {
            withFrameMillis { t -> phase.floatValue = (t % periodMs) / periodMs.toFloat() }
            delay(Decor.FRAME_MS - SystemClock.uptimeMillis() % Decor.FRAME_MS)
        }
    }
    return phase
}

/** The same, out and back: 0 to 1 over [periodMs], then back to 0. */
@Composable
fun decorSwing(periodMs: Int, ease: (Float) -> Float = { it }): Float {
    val p by decorClock(periodMs * 2)
    return ease(if (p < 0.5f) p * 2 else 2 - p * 2)
}
