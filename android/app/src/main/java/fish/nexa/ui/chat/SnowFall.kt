package fish.nexa.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.look.Seasonal
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Snow falling behind the messages, for a seasonal theme that has it (Winter
 * Night), unless turned off in Settings, Appearance (seasonalSnow): the web's
 * components/chat/SnowFall.tsx with the same numbers, in dp where it has CSS
 * pixels. Never drawn when motion is reduced, by the app's setting or the
 * system's.
 */
@Composable
fun SnowFall(modifier: Modifier) {
    val season = Seasonal.worn()
    val on = fish.nexa.ui.shell.settingOn("seasonalSnow")
    if (season?.snow != true || !on || Nx.motion.reduced) return
    Snow(modifier)
}

/**
 * How the snow moves, as the web's SnowFall.tsx has it. Nothing is a path
 * worked out in advance: the wind, each flake's drift, its fall and its spin
 * are random walks that keep being pulled back towards calm (an
 * Ornstein-Uhlenbeck process), so every flake is pushed about by fresh noise
 * every frame and never does the same thing twice. Each flake eases into what
 * the air does. A near flake ([z] towards 1) is bigger, brighter, faster and
 * blown further, and a little soft at the edge; most are far away.
 */
private class Flake(
    var x: Float, var y: Float, val z: Float, val r: Float, var vx: Float,
    /** Its own gusts and eddies, dp a second, a random walk. */
    var eddy: Float,
    val fall: Float,
    /** How much its fall wavers as it tumbles, a random walk around 0. */
    var waver: Float,
)

/** One flake for this many square dp, up to MAX. */
private const val AREA_PER_FLAKE = 6500f
private const val MAX = 160
/** Nearer than this, a flake has a soft edge. */
private const val SOFT_Z = 0.7f
private val FLAKE = Color(0xFFE8F1FF)
private val rng = java.util.Random()

private fun gauss(): Float = rng.nextGaussian().toFloat()

/** One step of a random walk pulled back towards [mean] at [rate], shaken by noise of strength [sigma]. */
private fun walk(v: Float, mean: Float, rate: Float, sigma: Float, dt: Float): Float = v + rate * (mean - v) * dt + sigma * sqrt(dt) * gauss()

private fun flake(w: Float, h: Float, anywhere: Boolean, wind: Float): Flake {
    val z = 0.3f + 0.7f * Random.nextFloat().pow(1.6f)
    val r = 0.6f + z * 2.2f
    return Flake(
        Random.nextFloat() * w, if (anywhere) Random.nextFloat() * h else -r * 6, z, r, wind * z,
        gauss() * 8 * z, (20 + 26 * z) * (0.85f + Random.nextFloat() * 0.3f), 0f,
    )
}

@Composable
private fun Snow(modifier: Modifier) {
    val flakes = remember { ArrayList<Flake>() }
    // the air everyone shares: a slow random walk around a light breeze
    val wind = remember { floatArrayOf(4f) }
    var tick by remember { mutableLongStateOf(0L) }
    val moving = fish.nexa.ui.components.Decor.running && !fish.nexa.ui.components.LocalDecorHeld.current
    LaunchedEffect(moving) {
        // still, a redraw for some other reason must not carry the flakes on
        tick = 0L
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // a long gap (the app in the background, a stall) moves the snow on by a twentieth of a second, not all at once, and never backwards
                tick = if (last == 0L) 0L else (now - last).coerceIn(0L, 50_000_000L)
                last = now
            }
            kotlinx.coroutines.delay(fish.nexa.ui.components.Decor.FRAME_MS - android.os.SystemClock.uptimeMillis() % fish.nexa.ui.components.Decor.FRAME_MS)
        }
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val dt = tick / 1e9f
        // sizes in dp, as the web's are in CSS pixels
        val w = size.width / density
        val h = size.height / density
        wind[0] = walk(wind[0], 4f, 0.15f, 4f, dt)
        if (!wind[0].isFinite()) wind[0] = 4f
        val want = min(MAX, (w * h / AREA_PER_FLAKE).roundToInt())
        while (flakes.size > want) flakes.removeAt(flakes.size - 1)
        while (flakes.size < want) flakes.add(flake(w, h, true, wind[0]))
        // how quickly a flake takes up a change in the air: light, so within half a second or so
        val ease = min(1f, 3 * dt)
        for (i in flakes.indices) {
            var f = flakes[i]
            f.eddy = walk(f.eddy, 0f, 1.2f, 18 * f.z, dt)
            f.waver = walk(f.waver, 0f, 1f, 0.25f, dt)
            f.vx += (wind[0] * f.z + f.eddy - f.vx) * ease
            f.x += f.vx * dt
            f.y += f.fall * max(0.6f, 1 + f.waver) * dt
            // past the bottom, or (should anything ever go wrong with the numbers) nowhere at all: a new flake at the top
            if (!(f.y <= h + f.r * 6) || !f.x.isFinite()) {
                f = flake(w, h, false, wind[0])
                flakes[i] = f
            }
            if (f.x < -10) f.x += w + 20 else if (f.x > w + 10) f.x -= w + 20
            val alpha = 0.18f + 0.5f * f.z
            val at = Offset(f.x * density, f.y * density)
            // the nearest are a little soft at the edge, as something close and out of focus is
            if (f.z > SOFT_Z) drawCircle(FLAKE, f.r * 1.9f * density, at, alpha = alpha * 0.22f)
            drawCircle(FLAKE, f.r * density, at, alpha = alpha)
        }
    }
}
