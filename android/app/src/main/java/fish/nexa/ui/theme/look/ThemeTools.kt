package fish.nexa.ui.theme.look

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * lib/look/tools.ts: the theme builder's tools that work on a whole palette
 * at once (Tune's sliders, the colour-vision previews, colour ideas and
 * Surprise Me). Nothing they do is saved as such: what they make is a
 * palette, kept as colours set by hand, so a theme made with them reads the
 * same everywhere.
 */
object ThemeTools {
    /** Tune's sliders, each -100 to 100 except hue, which turns -180 to 180 degrees. */
    data class Tune(val hue: Int = 0, val saturation: Int = 0, val brightness: Int = 0, val contrast: Int = 0) {
        val neutral get() = hue == 0 && saturation == 0 && brightness == 0 && contrast == 0
    }

    /** The large areas: what Brightness moves, and what Contrast moves text away from. */
    val SURFACES = setOf("bg-base", "bg-raised", "bg-surface", "bg-floating", "bg-input", "bg-composer", "embed-bg", "code-bg", "tooltip-bg", "spoiler-revealed")

    /** The colours that mean something, left alone unless asked, so green still means online. */
    val STATUS = setOf("positive", "positive-hover", "on-positive", "warning", "text-warning", "on-warning", "danger", "danger-hover", "on-danger", "text-danger", "status-online", "status-idle", "status-dnd", "status-offline")

    private val READS = setOf("text-strong", "text", "text-subtle", "text-muted", "text-channel", "text-placeholder", "icon", "link", "text-brand", "mention-fg", "text-warning", "text-danger", "code-keyword", "code-string", "code-number", "code-comment", "code-title", "code-type", "code-variable", "status-online", "status-idle", "status-dnd", "status-offline")

    private fun clamp01(x: Double) = min(1.0, max(0.0, x))

    private fun through(value: String, f: (Colour.Oklch) -> Colour.Oklch): String {
        val p = Colour.parseColour(value) ?: return value
        val o = Colour.rgbToOklch(p.rgb)
        val out = Colour.parseColour(Colour.oklchToHex(f(o))) ?: return value
        return Colour.toThemeHex(out.rgb, p.alpha)
    }

    /** The palette with Tune's sliders applied; status colours change only with [status]. */
    fun tunePalette(tokens: TokenMap, tune: Tune, status: Boolean): TokenMap {
        if (tune.neutral) return tokens
        val page = Colour.parseColour(tokens.getValue("bg-raised"))?.let { Colour.rgbToOklch(it.rgb).l } ?: 0.0
        val lift = tune.brightness / 100.0 * 0.3
        val out = LinkedHashMap(tokens)
        for (tk in Tokens.ALL) {
            if (!status && tk in STATUS) continue
            out[tk] = through(tokens.getValue(tk)) { o ->
                var l = o.l
                val h = ((o.h + tune.hue) % 360 + 360) % 360
                val c = max(0.0, o.c * (1 + tune.saturation / 100.0))
                if (tk in SURFACES) l = clamp01(l + lift)
                else if (tk in READS) l = clamp01(page + lift + (l - page) * (1 + tune.contrast / 100.0))
                Colour.Oklch(l, c, h)
            }
        }
        return out
    }

    /* Seeing the theme as people with a colour-vision deficiency do. */

    val VISIONS = listOf("typical", "protanopia", "deuteranopia", "tritanopia", "greyscale")

    /** Machado, Oliveira and Fernandes (2009) at full severity, applied to linear RGB. */
    private val MATRIX = mapOf(
        "protanopia" to doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
        "deuteranopia" to doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.01182, 0.04294, 0.968881),
        "tritanopia" to doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.3039),
    )

    private fun lin(v: Double): Double {
        val c = v / 255
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun unlin(v: Double): Double = 255 * if (v <= 0.0031308) 12.92 * max(0.0, v) else 1.055 * min(1.0, v).pow(1 / 2.4) - 0.055

    fun seeAs(rgb: Colour.Rgb, vision: String): Colour.Rgb {
        if (vision == "typical") return rgb
        val r = lin(rgb.r)
        val g = lin(rgb.g)
        val b = lin(rgb.b)
        if (vision == "greyscale") {
            val y = unlin(0.2126 * r + 0.7152 * g + 0.0722 * b)
            return Colour.Rgb(y, y, y)
        }
        val m = MATRIX[vision] ?: return rgb
        return Colour.Rgb(unlin(m[0] * r + m[1] * g + m[2] * b), unlin(m[3] * r + m[4] * g + m[5] * b), unlin(m[6] * r + m[7] * g + m[8] * b))
    }

    /** A palette as someone with [vision] sees it, for the preview only. */
    fun visionPalette(tokens: TokenMap, vision: String): TokenMap {
        if (vision == "typical") return tokens
        return tokens.mapValues { (_, v) -> Colour.parseColour(v)?.let { Colour.toThemeHex(seeAs(it.rgb, vision), it.alpha) } ?: v }
    }

    /* Ideas: colours offered to try with one press. */

    private val SPECTRUM = listOf(25.0, 55.0, 95.0, 150.0, 190.0, 230.0, 265.0, 300.0, 335.0)

    /** Accents to try: the current one turned round the wheel, then a spread of hues that read on this kind of background. */
    fun accentIdeas(accent: String, scheme: String): List<String> {
        val o = Colour.hexToOklch(accent) ?: Colour.Oklch(0.6, 0.2, 290.0)
        val c = max(0.12, min(0.22, o.c))
        val l = if (scheme == "dark") 0.64 else 0.55
        val turned = listOf(-30, 30, 120, -120, 180).map { d -> Colour.oklchToHex(Colour.Oklch(o.l, o.c, (o.h + d + 360) % 360)) }
        val spread = SPECTRUM.map { h -> Colour.oklchToHex(Colour.Oklch(l, c, h)) }
        return (turned + spread).distinct().filter { it != accent }.take(14)
    }

    /** Backgrounds to try: plain, tinted with the accent's hue at two strengths, its opposite, warm, cool and green. */
    fun backgroundIdeas(accent: String, scheme: String): List<String> {
        val h = Colour.hexToOklch(accent)?.h ?: 290.0
        val l = if (scheme == "dark") 0.2 else 0.975
        val tints = listOf(0.0 to 0.0, h to 0.012, h to 0.035, (h + 180) % 360 to 0.025, 60.0 to 0.02, 235.0 to 0.025, 150.0 to 0.02)
        return tints.map { (hue, c) -> Derive.fitBackground(Colour.oklchToHex(Colour.Oklch(l, c, hue)), scheme) }.distinct()
    }

    /** A theme to start from at random: dark more often than light, a background barely tinted, an accent that goes with it. */
    fun surprise(random: Random = Random.Default): SimpleColours {
        val scheme = if (random.nextDouble() < 0.72) "dark" else "light"
        val bgHue = random.nextDouble() * 360
        val turns = listOf(0, 30, -30, 150, 180, 210, 120)
        val accentHue = (bgHue + turns[floor(random.nextDouble() * turns.size).toInt()] + 360) % 360
        val background = Derive.fitBackground(Colour.oklchToHex(Colour.Oklch(if (scheme == "dark") 0.19 else 0.975, 0.005 + random.nextDouble() * 0.04, bgHue)), scheme)
        val accent = Colour.oklchToHex(Colour.Oklch(if (scheme == "dark") 0.6 + random.nextDouble() * 0.12 else 0.5 + random.nextDouble() * 0.1, 0.13 + random.nextDouble() * 0.1, accentHue))
        return SimpleColours(background, accent, null, scheme)
    }

    /**
     * [theme] changed so that it draws [next] where it drew [current]: each
     * colour that moved becomes a colour set by hand, unless it moved back to
     * what the source gives.
     */
    fun withPalette(theme: CustomTheme, next: TokenMap, current: TokenMap): CustomTheme {
        val base = CustomThemes.sourceTokens(theme.source)
        val o = LinkedHashMap(theme.overrides)
        for (tk in Tokens.ALL) {
            val v = next[tk] ?: continue
            if (v == current[tk]) continue
            if (base[tk] == v) o.remove(tk) else o[tk] = v
        }
        return theme.copy(overrides = o)
    }

    /** A slider's value as it reads beside it: signed, so which way it moved shows. */
    fun signed(v: Int, unit: String = ""): String = (if (v > 0) "+" else if (v < 0) "−" else "") + abs(v) + unit
}
