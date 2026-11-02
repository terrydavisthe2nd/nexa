package fish.nexa.ui.markdown

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.media.TwemojiImage
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sidewaysScroll
import fish.nexa.ui.layers.AnchorRef
import fish.nexa.ui.layers.anchor
import fish.nexa.ui.layers.openPopout
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.shadowLow
import kotlinx.coroutines.delay
import java.util.TimeZone

/**
 * How text is drawn where it appears: in a message (taps work), or in a
 * preview (inert: nothing taps, spoilers stay covered).
 */
data class RenderCtx(
    val serverId: String? = null,
    val inert: Boolean = false,
    /** The words' colour when it is not the usual (a message that failed to send). */
    val color: Color? = null,
    /** "(edited)", following the last word. */
    val edited: String? = null,
    /** What the words sit on, for coloured text to keep its contrast. */
    val surface: Color? = null,
    /** A note: files on lines of their own (drawn by `renderFile`), as much text as a note holds, and its longer-form type. */
    val files: Boolean = false,
    val max: Int = 0,
    val renderFile: (@Composable (id: String, name: String, width: Int?, align: String?) -> Unit)? = null,
    val bodyPx: Float = 16f,
    val bodyLine: Float = 22f,
    /** Space between the top-level blocks (a note's paragraphs stand a little apart). */
    val gap: Dp = 0.dp,
    /** The words' weight when it is not the usual (an embed's title). */
    val weight: FontWeight? = null,
    /** Emoji drawn as their :name: in words: another server's, in a message whose author may not use them there. */
    val plainEmoji: ((String) -> Boolean)? = null,
)

/**
 * A message's words as the web draws them (markdown/render.tsx with
 * styles/markdown.css): paragraphs, quotes, lists, code, tables, callouts and
 * collapsible sections, with mentions, emoji, spoilers, coloured words,
 * links and times inline.
 */
@Composable
fun MarkdownView(source: String, ctx: RenderCtx, modifier: Modifier = Modifier) {
    val nodes = remember(source, ctx.files, ctx.max) { Markdown.parse(source, files = ctx.files, max = ctx.max) }
    val jumbo = remember(nodes) { Markdown.isEmojiOnly(nodes) }
    Column(modifier.clipToBounds()) { Blocks(nodes, ctx, jumbo, top = true, edited = ctx.edited) }
}

/**
 * One line of a message (a reply's preview, the reply bar): its inline
 * formatting kept, every block flattened, nothing tappable.
 */
@Composable
fun MarkdownLine(source: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, serverId: String? = null) {
    val ctx = RenderCtx(serverId = serverId, inert = true, color = color)
    val nodes = remember(source) { flattenInline(Markdown.parse(source)) }
    InlineText(nodes, ctx, style.copy(color = color), jumbo = false, modifier = modifier, maxLines = 1)
}

private val SPACE = Node.Text(" ")

/** An inline node with any line break inside it turned into a space. */
private fun oneLine(n: Node): Node = when (n) {
    Node.Br -> SPACE
    is Node.Container -> n.withChildren(n.children.map(::oneLine))
    else -> n
}

/**
 * A message's markdown laid flat into one line (web markdown/flatten.ts), for a
 * preview of it such as the line over a reply. Formatting stays; blocks give up
 * their shape and keep their words: a code block reads as inline code, and
 * rules, footnotes and task boxes go.
 */
fun flattenInline(nodes: List<Node>): List<Node> {
    val out = ArrayList<Node>()
    fun gap() {
        val last = out.lastOrNull()
        if (last != null && !(last is Node.Text && last.text.lastOrNull()?.isWhitespace() == true)) out += SPACE
    }
    fun walk(list: List<Node>) {
        for (n in list) when (n) {
            is Node.Paragraph, is Node.Heading, is Node.Subtext, is Node.Quote, is Node.Alert -> {
                gap()
                walk((n as Node.Container).children)
            }
            is Node.ListBlock -> for (item in n.items) { gap(); walk(item) }
            is Node.Details -> { gap(); walk(n.summary); gap(); walk(n.children) }
            is Node.Table -> for (row in listOf(n.head) + n.rows) for (cell in row) { gap(); walk(cell) }
            is Node.CodeBlock -> {
                val text = n.text.replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) { gap(); out += Node.Code(text) }
            }
            Node.Br -> gap()
            Node.Hr, is Node.File, is Node.Footnotes, is Node.FootnoteRef, is Node.Task -> Unit
            else -> out += oneLine(n)
        }
    }
    walk(nodes)
    while (out.firstOrNull() === SPACE) out.removeAt(0)
    return out
}

private val BLOCK_TYPES = setOf("paragraph", "quote", "heading", "subtext", "list", "codeblock", "hr", "table", "alert", "details", "footnotes", "file")

/** A run of blocks; loose inline nodes between them read as one paragraph. */
@Composable
private fun Blocks(nodes: List<Node>, ctx: RenderCtx, jumbo: Boolean, top: Boolean, edited: String? = null) {
    // group stray inline nodes into paragraphs
    val blocks = remember(nodes) {
        val out = ArrayList<Node>()
        var run = ArrayList<Node>()
        for (n in nodes) {
            if (n.type in BLOCK_TYPES) {
                if (run.isNotEmpty()) { out += Node.Paragraph(run); run = ArrayList() }
                out += n
            } else run += n
        }
        if (run.isNotEmpty()) out += Node.Paragraph(run)
        out
    }
    val lastParagraph = blocks.lastOrNull() is Node.Paragraph
    blocks.forEachIndexed { i, b ->
        val first = top && i == 0
        val last = i == blocks.lastIndex
        if (top && i > 0 && ctx.gap > 0.dp) Spacer(Modifier.height(ctx.gap))
        Block(b, ctx, jumbo, first, if (last && lastParagraph) edited else null)
    }
    if (edited != null && !lastParagraph) Txt(edited, style = Nx.type.hint.copy(fontSize = Nx.type.sp(10f)), color = Nx.colors.textMuted)
}

@Composable
private fun Block(b: Node, ctx: RenderCtx, jumbo: Boolean, first: Boolean, edited: String?) {
    val c = Nx.colors
    val base = Nx.type.body.copy(color = ctx.color ?: c.text, fontSize = Nx.type.sp(ctx.bodyPx), lineHeight = Nx.type.sp(ctx.bodyLine)).let { if (ctx.weight != null) it.copy(fontWeight = ctx.weight) else it }
    when (b) {
        is Node.Paragraph -> InlineText(b.children, ctx, base, jumbo, edited = edited)
        is Node.Heading -> {
            val (size, top, bottom) = when (b.level) {
                1 -> Triple(1.5f, 16, 8)
                2 -> Triple(1.25f, 16, 8)
                3 -> Triple(1f, 16, 4)
                4 -> Triple(0.9375f, 12, 4)
                else -> Triple(0.875f, 12, 4)
            }
            val px = ctx.bodyPx * size
            val style = base.copy(
                fontSize = Nx.type.sp(px), lineHeight = Nx.type.sp(px * 1.25f), fontWeight = FontWeight(if (b.level == 6) 600 else 700),
                color = if (b.level == 6) c.textMuted else (ctx.color ?: c.textStrong),
            )
            InlineText(b.children, ctx, style, false, modifier = Modifier.padding(top = (if (first) 4 else top).dp, bottom = bottom.dp), edited = edited)
        }
        is Node.Subtext -> InlineText(b.children, ctx, base.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18f), color = c.textMuted), false, edited = edited)
        is Node.Quote -> {
            val bar = mixSrgb(c.borderStrong, c.brand, 0.55f)
            Row(Modifier.padding(vertical = 2.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(bar, RoundedCornerShape(2.dp)))
                Column(Modifier.padding(start = 12.dp, end = 8.dp)) { Blocks(b.children, ctx, false, top = false) }
            }
        }
        is Node.ListBlock -> ListView(b, ctx)
        is Node.CodeBlock -> CodeBlockView(b, ctx)
        Node.Hr -> Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(c.borderStrong))
        is Node.Table -> TableView(b, ctx)
        is Node.Alert -> AlertView(b, ctx)
        is Node.Details -> DetailsView(b, ctx)
        is Node.Footnotes -> FootnotesView(b, ctx)
        is Node.File -> ctx.renderFile?.invoke(b.id, b.name, b.width, b.align) ?: InlineText(listOf(Node.Text(b.name)), ctx, base, false)
        else -> InlineText(listOf(b), ctx, base, jumbo, edited = edited)
    }
}

// ---------------------------------------------------------------------------
// inline text

/** A background drawn behind a run of text: a mention, inline code, a highlight, a time, a spoiler. */
private class Chip(val start: Int, val end: Int, val fill: Color, val radius: Dp, val border: Color? = null, val inset: Dp = 1.5.dp, val spoiler: Int = -1)

private class Built(val text: AnnotatedString, val content: Map<String, InlineTextContent>, val chips: List<Chip>, val stamps: Boolean)

/** Space before and after a chip, as the web's inline padding gives it. */
private fun AnnotatedString.Builder.pad(content: MutableMap<String, InlineTextContent>, em: Float) {
    val key = "pad:$em"
    content.getOrPut(key) {
        InlineTextContent(Placeholder(em.em, 0.01.em, PlaceholderVerticalAlign.TextCenter)) {}
    }
    appendInlineContent(key, " ")
}

private class Builder(
    val ctx: RenderCtx,
    val revealed: Set<Int>,
    val reveal: (Int) -> Unit,
    val jumbo: Boolean,
    val now: Long,
    val colors: fish.nexa.ui.theme.NexaColors,
    val underlineLinks: Boolean,
    val openUser: (String) -> Unit,
    val openEmoji: (Node, Rect) -> Unit,
    val context: android.content.Context,
    val nameStyle: TextStyle,
    val measureName: (String, String?) -> Pair<TextUnit, TextUnit>,
) {
    val content = HashMap<String, InlineTextContent>()
    val chips = ArrayList<Chip>()
    var stamps = false
    var spoilerCount = 0
    private var emojiCount = 0

    fun build(nodes: List<Node>, edited: String?): Built {
        val text = buildAnnotatedString {
            nodes(nodes, inSpoiler = -1, inLink = false)
            // 10 px beside 16 px words, 4 px after the last of them
            if (edited != null) withStyle(SpanStyle(color = colors.textMuted, fontSize = 0.625.em)) { append(" $edited") }
        }
        return Built(text, content, chips, stamps)
    }

    private fun AnnotatedString.Builder.nodes(list: List<Node>, inSpoiler: Int, inLink: Boolean) {
        for (n in list) node(n, inSpoiler, inLink)
    }

    private fun AnnotatedString.Builder.node(n: Node, sp: Int, inLink: Boolean) {
        val covered = sp >= 0 && sp !in revealed
        when (n) {
            is Node.Text -> {
                val words = Zalgo.clamp(n.text)
                if (covered) withStyle(SpanStyle(color = Color.Transparent)) { append(words) } else append(words)
            }
            Node.Br -> append("\n")
            is Node.Strong -> withStyle(SpanStyle(fontWeight = FontWeight(700))) { nodes(n.children, sp, inLink) }
            is Node.Em -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { nodes(n.children, sp, inLink) }
            is Node.U -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { nodes(n.children, sp, inLink) }
            is Node.S -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { nodes(n.children, sp, inLink) }
            is Node.Sup -> withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift(0.5f))) { nodes(n.children, sp, inLink) }
            is Node.Sub -> withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift(-0.25f))) { nodes(n.children, sp, inLink) }
            is Node.Mark -> chip(colors.warning.copy(alpha = 0.32f), 3.dp, 2f / 16f) { nodes(n.children, sp, inLink) }
            is Node.Spoiler -> {
                val index = spoilerCount++
                val open = index in revealed
                val start = length
                pad(content, 2f / 16f)
                if (!open && !ctx.inert) {
                    withLink(LinkAnnotation.Clickable("spoiler-$index") { reveal(index) }) { nodes(n.children, index, inLink) }
                } else nodes(n.children, if (open) -1 else index, inLink)
                pad(content, 2f / 16f)
                chips += Chip(start, length, if (open) colors.spoilerRevealed else colors.spoiler, 4.dp, spoiler = if (open) -1 else index)
            }
            is Node.Code -> {
                val start = length
                pad(content, 0.38f * 0.875f)
                withStyle(SpanStyle(fontFamily = Nx_mono, fontSize = 0.875.em, color = if (covered) Color.Transparent else Color.Unspecified)) { append(n.text) }
                pad(content, 0.38f * 0.875f)
                chips += Chip(start, length, colors.codeBg, 5.dp, border = colors.codeBorder, inset = 0.5.dp)
            }
            is Node.Link -> link(n, sp, covered)
            is Node.Mention -> mention(n, covered)
            is Node.Emoji -> if (ctx.plainEmoji?.invoke(n.id) == true) append(":${n.name}:") else emoji(n, n.name, covered)
            is Node.UnicodeEmoji -> emoji(n, n.text, covered)
            is Node.Timestamp -> {
                stamps = true
                chip(colors.bgActive, 5.dp, 4f / 16f) {
                    val words = Timestamps.text(n, now, I18n.locale, TimeZone.getDefault())
                    if (covered) withStyle(SpanStyle(color = Color.Transparent)) { append(words) } else append(words)
                }
            }
            is Node.Color -> {
                val surface = ctx.surface ?: colors.bgRaised
                val s = MarkdownColors.Surface(Rgb.of(surface.toArgb()), Rgb.of((ctx.color ?: colors.text).toArgb()))
                val paint = MarkdownColors.paint(n.fg, n.bg, s)
                val fg = paint.color?.let { ColorMath.parseHex(it) }?.let { Color(0xFF000000.toInt() or it.toInt()) }
                val bg = paint.background?.let { ColorMath.parseHex(it) }?.let { Color(0xFF000000.toInt() or it.toInt()) }
                val style = SpanStyle(color = if (covered) Color.Transparent else (fg ?: Color.Unspecified), textDecoration = if (inLink) TextDecoration.Underline else null)
                if (bg != null) chip(bg, 3.dp, 2f / 16f) { withStyle(style) { nodes(n.children, sp, inLink) } }
                else withStyle(style) { nodes(n.children, sp, inLink) }
            }
            is Node.FootnoteRef -> withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift(0.5f), color = colors.link, fontWeight = FontWeight(600))) { append(n.n.toString()) }
            is Node.Task -> append(if (n.checked) "☑ " else "☐ ")
            is Node.Paragraph -> nodes(n.children, sp, inLink)
            is Node.Container -> nodes(n.children, sp, inLink)
            else -> Unit
        }
    }

    private inline fun AnnotatedString.Builder.chip(fill: Color, radius: Dp, padEm: Float, inner: AnnotatedString.Builder.() -> Unit) {
        val start = length
        pad(content, padEm)
        inner()
        pad(content, padEm)
        chips += Chip(start, length, fill, radius)
    }

    private fun AnnotatedString.Builder.link(n: Node.Link, sp: Int, covered: Boolean) {
        // a bare link to a channel or message this account can see reads as a pill, as a channel mention does
        val jump = if (n.masked) null else AppLinks.jumpTarget(n.url, Nexa.api.baseUrl)
        val jumpChannel = jump?.let { LinkedChannels.get(it.first) }
        if (jump != null && jumpChannel != null) return jumpLink(n.url, jumpChannel, jump.second != null, covered)
        val invite = if (n.masked) null else AppLinks.shownInvite(n.url, Nexa.api.baseUrl)
        val style = SpanStyle(color = if (covered) Color.Transparent else colors.link, textDecoration = if (underlineLinks) TextDecoration.Underline else null)
        val words: AnnotatedString.Builder.() -> Unit = {
            withStyle(style) { if (invite != null) append(invite) else nodes(n.children, sp, inLink = true) }
        }
        if (ctx.inert || covered) words() else withLink(LinkAnnotation.Clickable("link:${n.url}") { openLink(n.url) }) { words() }
        val host = Markdown.maskedHost(n)
        if (host != null) {
            append(" ")
            chip(colors.bgActive, 3.dp, 0.3f * 0.8f) {
                withStyle(SpanStyle(fontSize = 0.8.em, fontWeight = FontWeight(500), color = if (covered) Color.Transparent else colors.textMuted)) { append(host) }
            }
        }
    }

    /**
     * A link to a channel or message (the web's JumpLink): the channel's name,
     * a direct message's person or group, then for a message a small caret and
     * a speech bubble. Pressing it opens the link as any link to Nexa does.
     */
    private fun AnnotatedString.Builder.jumpLink(url: String, ch: fish.nexa.net.Channel, message: Boolean, covered: Boolean) {
        val fg = if (covered) Color.Transparent else colors.mentionFg
        val words = when (ch.type) {
            "dm" -> "@" + fish.nexa.ui.shell.Names.channel(ch)
            "group_dm" -> fish.nexa.ui.shell.Names.channel(ch)
            else -> "#" + ch.name
        }.replace(' ', '\u00A0')
        chip(colors.mentionBg, 5.dp, 3f / 16f) {
            val body: AnnotatedString.Builder.() -> Unit = {
                withStyle(SpanStyle(color = fg, fontWeight = FontWeight(600))) { append(words) }
                if (message) {
                    val key = "jump:${content.size}"
                    content[key] = InlineTextContent(Placeholder(1.6.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                        if (!covered) androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon("ChevronRight", colors.mentionFg.copy(alpha = 0.64f), size = 10.dp, stroke = 3f)
                            Icon("MessageCircle", colors.mentionFg, size = 13.dp, filled = true)
                        }
                    }
                    appendInlineContent(key, " › 💬")
                }
            }
            if (ctx.inert || covered) body() else withLink(LinkAnnotation.Clickable("jump:$url") { openLink(url) }) { body() }
        }
    }

    /** A link to Nexa itself opens here; anything else in the browser. */
    private fun openLink(url: String) {
        when (val app = AppLinks.appLink(url, Nexa.api.baseUrl)) {
            // a page of Nexa the app has no screen of its own for: the Terms and Privacy Policy in their sheet, the rest in the browser
            is AppLinks.AppLink.Route -> if (!Nav.openPath(app.path)) when (app.path.substringBefore('?').substringBefore('#').trimEnd('/')) {
                "/terms" -> fish.nexa.ui.legal.Legal.openDocument("terms")
                "/privacy" -> fish.nexa.ui.legal.Legal.openDocument("privacy")
                else -> browse(if (url.startsWith("/")) Nexa.api.baseUrl + url else url)
            }
            // the key after the # comes along: without it a new member could not read the history
            is AppLinks.AppLink.Invite -> fish.nexa.ui.dialogs.Dialogs.openInvite(app.code, app.hash)
            is AppLinks.AppLink.Profile -> fish.nexa.ui.profile.Profiles.openByUsername(app.username)
            is AppLinks.AppLink.Note -> fish.nexa.ui.notes.openNoteLink(app.token, app.hash)
            null -> browse(url)
        }
    }

    private fun browse(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun AnnotatedString.Builder.mention(n: Node.Mention, covered: Boolean) {
        val color = if (covered) Color.Transparent else colors.mentionFg
        val words: String
        var fill = colors.mentionBg
        var fg = color
        var action: (() -> Unit)? = null
        when (n.kind) {
            MentionKind.EVERYONE -> words = t("render.everyone")
            MentionKind.HERE -> words = t("render.here")
            MentionKind.USER -> {
                val u = Nexa.users.get(n.id) ?: Nexa.users.me.value?.takeIf { it.id == n.id }?.let { fish.nexa.net.PublicUser(it.id, it.username, it.displayName) }
                words = "@" + (u?.displayName?.ifBlank { null } ?: u?.username?.ifBlank { null } ?: t("render.unknownUser"))
                val id = n.id
                if (id != null) action = { openUser(id) }
            }
            MentionKind.CHANNEL -> {
                val ch = Nexa.channels.get(n.id)
                if (ch == null || ch.name.isEmpty()) {
                    words = "#" + t("render.unknownChannel")
                    fill = colors.textMuted.copy(alpha = 0.14f)
                    fg = if (covered) Color.Transparent else colors.textMuted
                } else {
                    words = "#" + ch.name
                    action = { if (ch.serverId != null) Nav.go(Route.Server(ch.serverId, ch.id)) else Nav.go(Route.Dm(ch.id)) }
                }
            }
            MentionKind.ROLE -> {
                val role = ctx.serverId?.let { Nexa.servers.get(it) }?.roles?.get(n.id)
                    ?: Nexa.servers.byId.value.values.firstNotNullOfOrNull { it.roles[n.id] }
                if (role == null) {
                    words = "@" + t("render.unknownRole")
                    fill = colors.textMuted.copy(alpha = 0.14f)
                    fg = if (covered) Color.Transparent else colors.textMuted
                } else {
                    words = "@" + role.name.ifEmpty { t("render.unknownRole") }
                    if (role.color != 0L) {
                        val rc = Color(0xFF000000 or role.color)
                        fill = rc.copy(alpha = 0.16f)
                        fg = if (covered) Color.Transparent else mixSrgb(colors.textStrong, rc, 0.72f)
                    }
                }
            }
        }
        // a mention stays on one line, as the web's white-space: nowrap keeps it
        val unbroken = words.replace(' ', '\u00A0')
        chip(fill, 5.dp, 3f / 16f) {
            val style = SpanStyle(color = fg, fontWeight = FontWeight(600))
            val a = action
            if (n.kind == MentionKind.USER && n.id != null && !covered) {
                val key = "person:${content.size}"
                val (width, height) = measureName(unbroken, n.id)
                content[key] = InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
                    val modifier = if (ctx.inert || a == null) Modifier else Modifier.pressable(remember { MutableInteractionSource() }, onClick = a)
                    fish.nexa.ui.chat.PersonName(n.id, unbroken, nameStyle, fg, modifier, serverId = ctx.serverId, showNameStyle = false)
                }
                appendInlineContent(key, unbroken)
            } else if (a != null && !ctx.inert && !covered) withLink(LinkAnnotation.Clickable("mention:${n.kind}:${n.id}") { a() }) { withStyle(style) { append(unbroken) } }
            else withStyle(style) { append(unbroken) }
        }
    }

    private fun AnnotatedString.Builder.emoji(n: Node, alt: String, covered: Boolean) {
        val key = "emoji:${emojiCount++}"
        val size: TextUnit = if (jumbo) 3.em else 1.375.em
        val custom = n as? Node.Emoji
        content[key] = InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.Center)) {
            if (!covered) {
                val ref = remember { AnchorRef() }
                val mod = if (ctx.inert) Modifier else Modifier.anchor(ref).pressable(remember { MutableInteractionSource() }) { openEmoji(n, ref.rect) }
                Box(mod) {
                    if (custom != null) {
                        val density = LocalDensity.current.density
                        @Suppress("UNUSED_VARIABLE") val animate = fish.nexa.ui.shell.settingOn("animateEmoji")
                        AsyncImage(Media.emojiUrl(custom.id, if (jumbo) 48 else 48, density), custom.name, Modifier.size(if (jumbo) 48.dp else 22.dp))
                    } else TwemojiImage(alt, if (jumbo) 48.dp else 22.dp)
                }
            }
        }
        appendInlineContent(key, if (custom != null) ":${custom.name}:" else alt)
    }
}

/** JetBrains Mono, which code is set in. */
private val Nx_mono get() = fish.nexa.ui.theme.NexaFonts.mono

@Composable
private fun InlineText(
    nodes: List<Node>,
    ctx: RenderCtx,
    style: TextStyle,
    jumbo: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    edited: String? = null,
) {
    val inlineWidth = fish.nexa.ui.chat.rememberInlineWidth()
    val c = Nx.colors
    var revealed by remember(nodes) { mutableStateOf(emptySet<Int>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val underline = Nexa.settings.underlineLinks
    val users by Nexa.users.byId.collectAsState()
    val me by Nexa.users.me.collectAsState()
    val linked by LinkedChannels.found.collectAsState()
    val measurer = rememberTextMeasurer()
    val nameDensity = LocalDensity.current
    val mentionStyle = style.copy(fontWeight = FontWeight(600))
    val built = remember(nodes, revealed, now, c, edited, ctx, underline, users, me, linked, mentionStyle, measurer, nameDensity, inlineWidth.pixels) {
        Builder(
            ctx, revealed, { revealed = revealed + it }, jumbo, now, c, underline,
            openUser = { id -> fish.nexa.ui.shell.openProfile(id) },
            openEmoji = { n, at -> openEmojiDetails(n, at) },
            context = context,
            nameStyle = mentionStyle,
            measureName = { text, id ->
                val own = fish.nexa.data.NameStyle.read(if (id == me?.id) me?.nameStyle else users[id]?.nameStyle)
                val measured = measurer.measure(text, fish.nexa.ui.chat.nameTextStyle(mentionStyle, own), maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (inlineWidth.pixels - with(nameDensity) { mentionStyle.fontSize.toPx() * (6f / 16f) }.toInt()).coerceAtLeast(1)))
                with(nameDensity) { measured.size.width.toSp() to measured.size.height.toSp() }
            },
        ).build(nodes, edited)
    }
    // relative times keep counting
    if (built.stamps) LaunchedEffect(built) {
        delay(10_000)
        now = System.currentTimeMillis()
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    BasicText(
        built.text,
        modifier = modifier.then(inlineWidth.modifier).clipToBounds().drawBehind {
            val l = layout ?: return@drawBehind
            for (chip in built.chips) for (r in boxes(l, chip.start, chip.end)) {
                val inset = with(density) { chip.inset.toPx() }
                val rr = Rect(r.left, r.top + inset, r.right, r.bottom - inset)
                val radius = CornerRadius(with(density) { chip.radius.toPx() })
                drawRoundRect(chip.fill, rr.topLeft, rr.size, radius)
                chip.border?.let { drawRoundRect(it, rr.topLeft, rr.size, radius, style = Stroke(with(density) { 1.dp.toPx() })) }
            }
        },
        style = style.copy(lineHeight = if (jumbo) style.fontSize * 3.6f else style.lineHeight),
        inlineContent = built.content,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
    )
}

/** The boxes a run of text takes, one per line it spans. */
private fun boxes(l: TextLayoutResult, start: Int, end: Int): List<Rect> {
    val len = l.layoutInput.text.length
    if (start >= end || start >= len) return emptyList()
    val e = minOf(end, len)
    val out = ArrayList<Rect>()
    val first = l.getLineForOffset(start)
    val last = l.getLineForOffset(e - 1)
    for (line in first..last) {
        val s = maxOf(start, l.getLineStart(line))
        val ee = minOf(e, l.getLineEnd(line, visibleEnd = true))
        if (s >= ee) continue
        val a = l.getBoundingBox(s)
        val b = l.getBoundingBox(ee - 1)
        out += Rect(minOf(a.left, b.left), l.getLineTop(line), maxOf(a.right, b.right), l.getLineBottom(line))
    }
    return out
}

/** EmojiDetails: the emoji big, its :name:, and where it comes from, held to the emoji tapped. */
private fun openEmojiDetails(n: Node, at: Rect) {
    openPopout("emoji-details", at, 300.dp) { _ ->
        val c = Nx.colors
        Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (name, from) = when (n) {
                is Node.Emoji -> {
                    val density = LocalDensity.current.density
                    @Suppress("UNUSED_VARIABLE") val animate = fish.nexa.ui.shell.settingOn("animateEmoji")
                    AsyncImage(Media.emojiUrl(n.id, 48, density), n.name, Modifier.size(48.dp))
                    val server = Nexa.expressions.emojis.value.entries.firstOrNull { (_, list) -> list.any { it.blobId == n.id } }?.key?.let { Nexa.servers.get(it)?.server?.name }
                    val pack = if (server == null) Nexa.expressions.packs.value.values.firstOrNull { p -> p.stickers.any { it.blobId == n.id } }?.name else null
                    ":${n.name}:" to when {
                        server != null -> t("chat.emojiDetails.fromServer", "name" to server)
                        pack != null -> t("chat.emojiDetails.fromPack", "name" to pack)
                        else -> t("chat.emojiDetails.custom")
                    }
                }
                is Node.UnicodeEmoji -> {
                    TwemojiImage(n.text, 48.dp)
                    (Shortcodes.nameOf(n.text)?.let { ":$it:" } ?: n.text) to t("chat.emojiDetails.standard")
                }
                else -> "" to ""
            }
            Column {
                Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                Txt(from, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// blocks

@Composable
private fun ListView(b: Node.ListBlock, ctx: RenderCtx) {
    val c = Nx.colors
    val base = Nx.type.body.copy(color = ctx.color ?: c.text)
    Column(Modifier.padding(vertical = 4.dp)) {
        b.items.forEachIndexed { i, item ->
            val task = item.firstOrNull() as? Node.Task
            val rest = if (task != null) item.drop(1) else item
            Row(Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.TopEnd) {
                    when {
                        task != null -> TaskBox(task.checked)
                        b.ordered -> Txt("${b.start + i}.", style = base, color = c.text, modifier = Modifier.padding(end = 6.dp))
                        else -> Txt("•", style = base, color = c.textMuted, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    // the item's words, then any list nested under them
                    val inline = rest.takeWhile { it !is Node.ListBlock }
                    if (inline.isNotEmpty()) InlineText(inline, ctx, base, false)
                    for (nested in rest.dropWhile { it !is Node.ListBlock }) Block(nested, ctx, false, false, null)
                }
            }
        }
    }
}

@Composable
private fun TaskBox(checked: Boolean) {
    val c = Nx.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(top = 4.dp, end = 6.dp)
            .size(15.dp)
            .then(if (checked) Modifier.background(c.brand, shape) else Modifier.border(1.5.dp, c.textMuted, shape))
            .semantics { contentDescription = if (checked) t("render.taskDone") else t("render.taskOpen") },
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon("Check", c.onBrand, size = 11.dp) }
}

/** Code in a language, coloured as the chat's code blocks are (plain for a language it does not know). */
@Composable
fun highlightedCode(code: String, lang: String?): androidx.compose.ui.text.AnnotatedString {
    val c = Nx.colors
    return remember(code, lang, c) {
        buildAnnotatedString {
            append(code)
            for (s in Highlight.spans(code, lang)) {
                val color = when (s.kind) {
                    Highlight.Kind.KEYWORD -> c.codeKeyword
                    Highlight.Kind.STRING -> c.codeString
                    Highlight.Kind.NUMBER -> c.codeNumber
                    Highlight.Kind.COMMENT -> c.codeComment
                    Highlight.Kind.TITLE -> c.codeTitle
                    Highlight.Kind.TYPE -> c.codeType
                    Highlight.Kind.VARIABLE -> c.codeVariable
                }
                addStyle(SpanStyle(color = color, fontStyle = if (s.kind == Highlight.Kind.COMMENT) FontStyle.Italic else null), s.start, s.end)
            }
        }
    }
}

@Composable
private fun CodeBlockView(b: Node.CodeBlock, ctx: RenderCtx) {
    val c = Nx.colors
    val text = highlightedCode(b.text, b.lang)
    val shape = Radii.Sm
    Box(Modifier.padding(top = 6.dp).fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(c.codeBg, shape)
                .border(1.dp, c.codeBorder, shape)
                .sidewaysScroll()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicText(text, style = Nx.type.code.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20.3f), color = ctx.color ?: c.text), softWrap = false)
        }
        if (!ctx.inert) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(36.dp)
                .shadowLow(Radii.Sm, c)
                .background(c.bgFloating, Radii.Sm)
                .border(1.dp, c.codeBorder, Radii.Sm)
                .pressable(remember { MutableInteractionSource() }) {
                    val cm = Nexa.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", b.text))
                    if (android.os.Build.VERSION.SDK_INT < 33) Nexa.ui.toast(t("clipboard.codeCopied"))
                }
                .semantics { contentDescription = t("render.copyCode") },
            contentAlignment = Alignment.Center,
        ) { Icon("Copy", c.textMuted.copy(alpha = 0.9f), size = 15.dp) }
    }
}

@Composable
private fun AlertView(b: Node.Alert, ctx: RenderCtx) {
    val c = Nx.colors
    val (accent, icon) = when (b.kind) {
        AlertKind.NOTE -> c.link to "Info"
        AlertKind.TIP -> c.positive to "Lightbulb"
        AlertKind.IMPORTANT -> c.textBrand to "MessageSquareWarning"
        AlertKind.WARNING -> c.textWarning to "TriangleAlert"
        AlertKind.CAUTION -> c.textDanger to "OctagonAlert"
    }
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(IntrinsicSize.Min).background(accent.copy(alpha = 0.07f), shape)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                val title = mixSrgb(c.textStrong, accent, 0.8f)
                Icon(icon, title, size = 16.dp)
                Txt(Markdown.alertTitle(b.kind) { t(it) }, style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = title)
            }
            Blocks(b.children, ctx, false, top = false)
        }
    }
}

@Composable
private fun DetailsView(b: Node.Details, ctx: RenderCtx) {
    val c = Nx.colors
    var open by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(if (Nx.motion.reduced) 0 else 180), label = "chevron")
    val shape = Radii.Sm
    Column(
        Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .border(1.dp, c.border, shape)
            .background(c.text.copy(alpha = 0.03f), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .pressable(remember { MutableInteractionSource() }, enabled = !ctx.inert) { open = !open }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon("ChevronRight", c.textMuted, size = 16.dp, modifier = Modifier.rotate(if (rtl) 180f - turn else turn))
            if (b.summary.isEmpty()) Txt(t("render.details"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = ctx.color ?: c.text)
            else InlineText(b.summary, ctx, Nx.type.body.copy(fontWeight = FontWeight(600), color = ctx.color ?: c.text), false)
        }
        if (open) Column(Modifier.padding(start = 32.dp, end = 10.dp, top = 2.dp, bottom = 8.dp)) { Blocks(b.children, ctx, false, top = false) }
    }
}

@Composable
private fun FootnotesView(b: Node.Footnotes, ctx: RenderCtx) {
    val c = Nx.colors
    val style = Nx.type.body.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.85f), color = c.textMuted)
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Column(Modifier.padding(top = 6.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (note in b.notes) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("${note.n}.", style = style, color = c.textMuted)
                InlineText(note.children, ctx, style, false, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/**
 * A table as wide as it needs, scrolling sideways in its own box: every
 * column as wide as its widest cell (to a limit), every row as tall as its
 * tallest.
 */
@Composable
private fun TableView(b: Node.Table, ctx: RenderCtx) {
    val c = Nx.colors
    val shape = Radii.Sm
    val base = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(21f), color = ctx.color ?: c.text)
    val cols = b.align.size
    val rows = listOf(b.head) + b.rows
    Box(
        Modifier
            .padding(vertical = 6.dp)
            .border(1.dp, c.border, shape)
            .sidewaysScroll()
            .semantics { contentDescription = t("render.table") },
    ) {
        Layout(content = {
            rows.forEachIndexed { r, row ->
                for (col in 0 until cols) {
                    val cell = row.getOrNull(col) ?: emptyList()
                    val head = r == 0
                    val fill = when {
                        head -> c.text.copy(alpha = 0.06f)
                        r % 2 == 0 -> c.text.copy(alpha = 0.03f)
                        else -> Color.Transparent
                    }
                    Box(
                        Modifier
                            .background(fill)
                            .drawBehind {
                                val w = 1.dp.toPx()
                                if (col > 0) drawLine(c.border, Offset(0f, 0f), Offset(0f, size.height), w)
                                if (r > 0) drawLine(c.border, Offset(0f, 0f), Offset(size.width, 0f), w)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = when (b.align[col]) {
                            Align.CENTER -> Alignment.TopCenter
                            Align.RIGHT -> Alignment.TopEnd
                            else -> Alignment.TopStart
                        },
                    ) { InlineText(cell, ctx, if (head) base.copy(fontWeight = FontWeight(600)) else base, false) }
                }
            }
        }) { measurables, _ ->
            val maxCell = (280 * density).toInt()
            // each cell is measured once: a column as wide as its widest cell would like (to a limit),
            // a row as tall as its tallest cell is at that width
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m -> widths[i % cols] = maxOf(widths[i % cols], minOf(maxCell, m.maxIntrinsicWidth(Constraints.Infinity))) }
            val heights = rows.indices.map { r -> (0 until cols).maxOf { col -> measurables[r * cols + col].maxIntrinsicHeight(widths[col]) } }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (col in 0 until cols) {
                        placeables[r * cols + col].place(x, y)
                        x += widths[col]
                    }
                    y += heights[r]
                }
            }
        }
    }
}
