package fish.nexa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Nexa's palettes, value for value from web/src/styles/tokens.css: deep,
 * faintly violet ink in the dark themes, paper white in the light one, and
 * the depth tokens worked out from whichever theme is on, as the web works
 * them out with color-mix (in OKLab where the web mixes in OKLab).
 */
@Immutable
data class NexaColors(
    val name: String,
    val isLight: Boolean,
    val bgBase: Color,
    val bgRaised: Color,
    val bgSurface: Color,
    val bgFloating: Color,
    val bgInput: Color,
    val bgComposer: Color,
    val bgHover: Color,
    val bgSelected: Color,
    val bgActive: Color,
    val bgMessageHover: Color,
    val bgOverlay: Color,
    val text: Color,
    val textStrong: Color,
    val textSubtle: Color,
    val textMuted: Color,
    val textChannel: Color,
    val textPlaceholder: Color,
    val icon: Color,
    val brand: Color,
    val brandHover: Color,
    val brandActive: Color,
    val onBrand: Color,
    val onDanger: Color,
    val onPositive: Color,
    val onWarning: Color,
    val focusRing: Color,
    val link: Color,
    val textBrand: Color,
    val mentionBg: Color,
    val mentionBgHover: Color,
    val mentionFg: Color,
    val positive: Color,
    val positiveHover: Color,
    val warning: Color,
    val textWarning: Color,
    val danger: Color,
    val dangerHover: Color,
    val textDanger: Color,
    val statusOnline: Color,
    val statusIdle: Color,
    val statusDnd: Color,
    val statusOffline: Color,
    val borderSubtle: Color,
    val border: Color,
    val borderStrong: Color,
    val spoiler: Color,
    val spoilerRevealed: Color,
    val codeBg: Color,
    val codeBorder: Color,
    val codeKeyword: Color,
    val codeString: Color,
    val codeNumber: Color,
    val codeComment: Color,
    val codeTitle: Color,
    val codeType: Color,
    val codeVariable: Color,
    val embedBg: Color,
    val tooltipBg: Color,
    val tooltipText: Color,
    // depth, as the :root block at the end of tokens.css derives it
    val railShade: Float = if (isLight) 0.93f else 0.70f,
) {
    /** The canvas the rail sits on: the base mixed toward black. */
    val bgRail: Color = mixOklab(bgBase, Color.Black, 1f - railShade)
    val bgCanvas: Color get() = bgRail
    val canvasGlow: Color = brand.copy(alpha = if (isLight) 0.09f else 0.13f)
    val panelEdge: Color = textStrong.copy(alpha = if (isLight) 0.07f else 0.06f)
    val edgeLight: Color = Color.White.copy(alpha = if (isLight) 0.85f else 0.06f)
    val edgeLightStrong: Color = Color.White.copy(alpha = if (isLight) 0.95f else 0.11f)
    val shade: Color = if (isLight) mixOklab(textStrong, bgBase, 0.30f) else mixOklab(bgBase, Color.Black, 0.70f)
    val shadeAlpha: Float = if (isLight) 0.32f else 1f
    val selection: Color = focusRing.copy(alpha = if (isLight) 0.28f else 0.55f)
    val gradBrandTop: Color = mixOklab(brand, Color.White, 0.16f)
    val gradBrandBottom: Color = mixOklab(brand, Color.Black, 0.08f)
    val gradDangerTop: Color = mixOklab(danger, Color.White, 0.12f)
    val gradPositiveTop: Color = mixOklab(positive, Color.White, 0.12f)
    val sheenTop: Color = Color.White.copy(alpha = if (isLight) 0.5f else 0.035f)

    /** The plans' own lights (tokens.css --tier-*): Supporter rose, Pro the accent. */
    val tierSupporter: Color = if (isLight) Color(0xFFE8336F) else Color(0xFFFF5C93)
    val textSupporter: Color = mixOklab(tierSupporter, if (isLight) Color.Black else textStrong, 0.18f)
    val tierPro: Color get() = brand
    val textPro: Color get() = textBrand

    /** Profile badges' tones (tokens.css --badge-*); a badge's text is its tone mixed toward the strongest text, as the plans' are. */
    val badgeOg: Color = if (isLight) Color(0xFF138A75) else Color(0xFF4CC9B0)
    val badgeYears: Color = if (isLight) Color(0xFF3D6F9E) else Color(0xFF8FB4D9)
    val badgeBug: Color = if (isLight) Color(0xFF1F8F3E) else Color(0xFF5FD17A)
    val badgeDev: Color = if (isLight) Color(0xFF2A64D6) else Color(0xFF6AA2FF)
    val badgeContributor: Color = if (isLight) Color(0xFF7A45E0) else Color(0xFFB48CFF)
    val badgeBronze: Color = if (isLight) Color(0xFFA35F2C) else Color(0xFFD08A52)
    val badgeSilver: Color = if (isLight) Color(0xFF66717E) else Color(0xFFB9C3CF)
    val badgeGold: Color = if (isLight) Color(0xFFA9790F) else Color(0xFFF0C04A)
    /** A badge's words and icon in its [tone]. */
    fun badgeText(tone: Color): Color = mixOklab(tone, if (isLight) Color.Black else textStrong, if (isLight) 0.22f else 0.18f)

    fun shadeAt(a: Float): Color = shade.copy(alpha = (a * shadeAlpha).coerceIn(0f, 1f))

    fun statusColor(status: String?): Color = when (status) {
        "online" -> statusOnline
        "idle" -> statusIdle
        "dnd" -> statusDnd
        else -> statusOffline
    }
}

private fun hex(v: Long): Color = Color(0xFF000000L or v)
private fun rgba(r: Int, g: Int, b: Int, a: Float): Color = Color(r, g, b, (a * 255).toInt().coerceIn(0, 255))

object Palettes {
    val dark = NexaColors(
        name = "dark", isLight = false,
        bgBase = hex(0x1c1825), bgRaised = hex(0x221d2b), bgSurface = hex(0x2b2636), bgFloating = hex(0x282331),
        bgInput = hex(0x15121c), bgComposer = hex(0x2b2636),
        bgHover = rgba(166, 156, 196, 0.08f), bgSelected = rgba(166, 156, 196, 0.13f), bgActive = rgba(166, 156, 196, 0.17f),
        bgMessageHover = rgba(166, 156, 196, 0.05f), bgOverlay = rgba(6, 3, 14, 0.74f),
        text = hex(0xf0edf7), textStrong = hex(0xffffff), textSubtle = hex(0xd0cadb), textMuted = hex(0xb0a8bf),
        textChannel = hex(0xa39bb3), textPlaceholder = hex(0x928ba5), icon = hex(0xbcb4cb),
        brand = hex(0x8000ff), brandHover = hex(0x6a00d6), brandActive = hex(0x5600ac), onBrand = hex(0xffffff),
        onDanger = hex(0xffffff), onPositive = hex(0xffffff), onWarning = hex(0x111214),
        focusRing = hex(0xb77aff), link = hex(0x86b8f7), textBrand = hex(0xc6a4ff),
        mentionBg = rgba(128, 0, 255, 0.24f), mentionBgHover = rgba(128, 0, 255, 0.38f), mentionFg = hex(0xe0d0ff),
        positive = hex(0x2d8550), positiveHover = hex(0x256e42), warning = hex(0xfdb833), textWarning = hex(0xfdb833),
        danger = hex(0xd1343b), dangerHover = hex(0xb02a31), textDanger = hex(0xff8a8e),
        statusOnline = hex(0x46b56e), statusIdle = hex(0xf0b232), statusDnd = hex(0xff7075), statusOffline = hex(0xa39cb1),
        borderSubtle = rgba(166, 156, 196, 0.11f), border = rgba(166, 156, 196, 0.19f), borderStrong = rgba(166, 156, 196, 0.4f),
        spoiler = hex(0x7d768a), spoilerRevealed = hex(0x15121c), codeBg = hex(0x15121c), codeBorder = rgba(166, 156, 196, 0.12f),
        codeKeyword = hex(0xc678dd), codeString = hex(0x98c379), codeNumber = hex(0xd19a66), codeComment = hex(0x8d939e),
        codeTitle = hex(0x61afef), codeType = hex(0xe5c07b), codeVariable = hex(0xe06c75),
        embedBg = hex(0x272231), tooltipBg = hex(0x0e0b14), tooltipText = hex(0xf5f3fa),
    )

    val light = dark.copy(
        name = "light", isLight = true,
        bgBase = hex(0xf4f2fb), bgRaised = hex(0xffffff), bgSurface = hex(0xeae8f3), bgFloating = hex(0xffffff),
        bgInput = hex(0xebe9f4), bgComposer = hex(0xedebf5),
        bgHover = rgba(101, 94, 125, 0.09f), bgSelected = rgba(101, 94, 125, 0.14f), bgActive = rgba(101, 94, 125, 0.2f),
        bgMessageHover = rgba(101, 94, 125, 0.05f), bgOverlay = rgba(28, 18, 50, 0.46f),
        text = hex(0x2e2c37), textStrong = hex(0x1f1c28), textSubtle = hex(0x595565), textMuted = hex(0x595667),
        textChannel = hex(0x5f5b6c), textPlaceholder = hex(0x6c6877), icon = hex(0x514d5f),
        link = hex(0x0062c0), textBrand = hex(0x6a00d6), focusRing = hex(0x8000ff),
        mentionBg = rgba(128, 0, 255, 0.16f), mentionBgHover = rgba(128, 0, 255, 0.32f), mentionFg = hex(0x4a1a8f),
        positive = hex(0x1f7d47), positiveHover = hex(0x196a3c), warning = hex(0xbb7300), textWarning = hex(0x7a4b00),
        danger = hex(0xd1343b), dangerHover = hex(0xb02a31), textDanger = hex(0xab1e29),
        statusOnline = hex(0x2a7044), statusIdle = hex(0x845a06), statusDnd = hex(0xc0222c), statusOffline = hex(0x6b6776),
        borderSubtle = rgba(141, 136, 162, 0.26f), border = rgba(141, 136, 162, 0.38f), borderStrong = rgba(141, 136, 162, 0.52f),
        spoiler = hex(0x93909e), spoilerRevealed = hex(0xeae8f3), codeBg = hex(0xf5f3fb), codeBorder = rgba(141, 136, 162, 0.26f),
        codeKeyword = hex(0xcf222e), codeString = hex(0x0a3069), codeNumber = hex(0x0550ae), codeComment = hex(0x636b75),
        codeTitle = hex(0x7a3fd4), codeType = hex(0x116329), codeVariable = hex(0x953800),
        embedBg = hex(0xf5f3fb), tooltipBg = hex(0x282336), tooltipText = hex(0xf7f6fa), railShade = 0.93f,
    )

    /** Ash: graphite, with no violet in it, for anyone who wants the app quiet. */
    val ash = dark.copy(
        name = "ash",
        bgBase = hex(0x18191d), bgRaised = hex(0x1d1e23), bgSurface = hex(0x27282e), bgFloating = hex(0x24252b),
        bgInput = hex(0x111215), bgComposer = hex(0x27282e),
        bgHover = rgba(200, 202, 215, 0.07f), bgSelected = rgba(200, 202, 215, 0.12f), bgActive = rgba(200, 202, 215, 0.16f),
        bgMessageHover = rgba(200, 202, 215, 0.045f), bgOverlay = rgba(0, 0, 0, 0.72f),
        text = hex(0xeeeef1), textSubtle = hex(0xcacbd2), textMuted = hex(0xaeafb8), textChannel = hex(0x9fa0aa),
        textPlaceholder = hex(0x8c8e98), icon = hex(0xb6b7c0), textDanger = hex(0xff8d90), statusOffline = hex(0xa0a1ab),
        borderSubtle = rgba(200, 202, 215, 0.1f), border = rgba(200, 202, 215, 0.17f), borderStrong = rgba(200, 202, 215, 0.36f),
        spoiler = hex(0x7c7d86), spoilerRevealed = hex(0x111215), codeBg = hex(0x111215), codeBorder = rgba(200, 202, 215, 0.1f),
        embedBg = hex(0x24252b), tooltipBg = hex(0x0b0b0d),
    )

    val onyx = dark.copy(
        name = "onyx",
        bgBase = hex(0x000000), bgRaised = hex(0x000000), bgSurface = hex(0x141218), bgFloating = hex(0x16141b),
        bgInput = hex(0x0c0b10), bgComposer = hex(0x141218),
        bgHover = rgba(255, 255, 255, 0.06f), bgSelected = rgba(255, 255, 255, 0.1f), bgActive = rgba(255, 255, 255, 0.14f),
        bgMessageHover = rgba(255, 255, 255, 0.04f),
        text = hex(0xdcdae0), textStrong = hex(0xffffff), textSubtle = hex(0xc4c1ca), textMuted = hex(0xa19eaa),
        textChannel = hex(0x96939f), textPlaceholder = hex(0x86838f),
        borderSubtle = rgba(255, 255, 255, 0.1f), border = rgba(255, 255, 255, 0.16f), borderStrong = rgba(255, 255, 255, 0.34f),
        spoilerRevealed = hex(0x141218), codeBg = hex(0x0c0b10), embedBg = hex(0x121016),
    )

    val builtIn = listOf(dark, light, ash, onyx)

    fun byName(name: String?): NexaColors = builtIn.firstOrNull { it.name == name } ?: dark
}

// ---- OKLab mixing, as CSS color-mix(in oklab, a (1-t), b t) ----

private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
private fun toSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else (1.055f * c.toDouble().pow(1 / 2.4).toFloat() - 0.055f)

private fun oklab(c: Color): FloatArray {
    val r = toLinear(c.red)
    val g = toLinear(c.green)
    val b = toLinear(c.blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun fromOklab(lab: FloatArray, alpha: Float): Color {
    val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
    val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
    val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
    val l3 = l * l * l
    val m3 = m * m * m
    val s3 = s * s * s
    val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
    val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
    val b = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    return Color(toSrgb(r).coerceIn(0f, 1f), toSrgb(g).coerceIn(0f, 1f), toSrgb(b).coerceIn(0f, 1f), alpha)
}

/** `oklch(from c calc(l + dl) calc(c + dc) h)`: lighter or darker, more or less vivid, the same hue. */
fun adjustOklch(c: Color, dl: Float, dc: Float): Color {
    val lab = oklab(c)
    val chroma = kotlin.math.sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    val hue = kotlin.math.atan2(lab[2], lab[1])
    val l = (lab[0] + dl).coerceIn(0f, 1f)
    val ch = (chroma + dc).coerceAtLeast(0f)
    return fromOklab(floatArrayOf(l, ch * kotlin.math.cos(hue), ch * kotlin.math.sin(hue)), c.alpha)
}

/** `color-mix(in oklab, a, b t)`: t is the share of b. */
fun mixOklab(a: Color, b: Color, t: Float): Color {
    val x = oklab(a)
    val y = oklab(b)
    return fromOklab(floatArrayOf(x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t), a.alpha + (b.alpha - a.alpha) * t)
}

/** `color-mix(in srgb, a, b t)`: t is the share of b, mixed in gamma-encoded sRGB as CSS does. */
fun mixSrgb(a: Color, b: Color, t: Float): Color =
    Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

/** Reads "#rrggbb" (or a 0xRRGGBB number) as a colour. */
fun parseHex(s: String?): Color? {
    val h = s?.trim()?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
}

fun rgbColor(v: Long): Color = Color(0xFF000000L or (v and 0xFFFFFF))
