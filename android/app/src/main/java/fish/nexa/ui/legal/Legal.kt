package fish.nexa.ui.legal

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxCheckbox
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sidewaysScroll
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch

/**
 * lib/legal.ts: which version of the Terms and Privacy Policy is current (read
 * from the web's own file, copied in at build time) and whether this account
 * agreed to it on this device, which is where the web keeps it too.
 */
object Legal {
    private val src: String by lazy { runCatching { fish.nexa.data.Ota.open("legal/legal.ts").bufferedReader().readText() }.getOrDefault("") }

    val effective: String by lazy { Regex("""LEGAL_EFFECTIVE\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1) ?: "" }

    /** `${LEGAL_EFFECTIVE}.2` and the like: what an agreement is recorded against. */
    val version: String by lazy {
        val m = Regex("""LEGAL_VERSION\s*=\s*`\$\{LEGAL_EFFECTIVE\}([^`]*)`""").find(src)
        if (m != null) effective + m.groupValues[1] else Regex("""LEGAL_VERSION\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1) ?: effective
    }

    val contactEmail: String by lazy { Regex("""CONTACT_EMAIL\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1) ?: "" }
    val ownerUsername: String by lazy { Regex("""OWNER_USERNAME\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1) ?: "" }
    val githubUrl: String by lazy { Regex("""GITHUB_URL\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1) ?: "" }

    fun openGitHub(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))) }
    }

    private fun prefs() = Nexa.app.getSharedPreferences("legal", Context.MODE_PRIVATE)
    private fun key(userId: String) = "nexa.terms-accepted.$userId"

    fun accepted(userId: String): Boolean = version.isNotEmpty() && prefs().getString(key(userId), null) == version

    /** The older version agreed to before, when the documents have changed since. */
    fun previously(userId: String): String? = prefs().getString(key(userId), null)?.takeIf { it != version }

    fun accept(userId: String) = prefs().edit().putString(key(userId), version).apply()

    // ---- the documents (lib/legalDocs.ts parseLegal) ----

    sealed interface Block {
        data class P(val text: String) : Block
        data class Ul(val items: List<String>) : Block
        data class Ol(val items: List<String>) : Block
        data class Item(val label: String, val lead: String, val text: String, val sub: List<String>) : Block
        data class Lines(val lines: List<String>) : Block
    }

    data class Section(val id: String, val number: String, val title: String, val blocks: MutableList<Block>)
    data class Doc(val title: String, val effective: String, val meta: List<Pair<String, String>>, val intro: MutableList<Block>, val sections: MutableList<Section>)

    private val HEADING = Regex("""^(\d+)\.\s+([A-Z0-9][A-Z0-9 ,()'’&/-]*)$""")
    private val LETTERED = Regex("""^([a-z])\)\s+(.*)$""")

    private fun unwrap(lines: List<String>) = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    fun headingCase(s: String): String {
        val lower = s.lowercase()
            .replace(Regex("\\bnexa\\b"), "Nexa").replace(Regex("\\bip\\b"), "IP").replace(Regex("\\bdm\\b"), "DM")
        return lower.replaceFirstChar { it.uppercase() }
    }

    private fun items(lines: List<String>, marker: Regex): List<String> {
        val out = ArrayList<MutableList<String>>()
        for (line in lines) {
            val m = marker.find(line)
            if (m != null && m.range.first == 0) out += mutableListOf(line.substring(m.value.length))
            else if (out.isNotEmpty()) out.last() += line
        }
        return out.map { unwrap(it) }
    }

    private fun block(lines: List<String>): Block {
        val first = lines[0]
        if (Regex("^- ").containsMatchIn(first)) return Block.Ul(items(lines, Regex("^- ")))
        if (Regex("^\\s+\\d+\\.\\s").containsMatchIn(first)) return Block.Ol(items(lines, Regex("^\\s+\\d+\\.\\s+")))
        val lettered = LETTERED.find(first)
        if (lettered != null) {
            val body = ArrayList<String>()
            val subLines = ArrayList<String>()
            var inSub = false
            for ((i, line) in lines.withIndex()) {
                val text = if (i == 0) lettered.groupValues[2] else line
                if (Regex("^\\s+- ").containsMatchIn(line)) inSub = true
                (if (inSub) subLines else body) += text
            }
            val whole = unwrap(body)
            val colon = whole.indexOf(':')
            val lead = if (colon in 1..120) whole.substring(0, colon) else ""
            return Block.Item(lettered.groupValues[1], lead, if (lead.isNotEmpty()) whole.substring(colon + 1).trim() else whole, items(subLines, Regex("^\\s+- ")))
        }
        if (lines.all { Regex("^\\s{2,}\\S").containsMatchIn(it) }) return Block.Lines(lines.map { it.trim() })
        return Block.P(unwrap(lines))
    }

    fun parse(src: String, idPrefix: String): Doc {
        val text = src.replace("\r\n", "\n")
        val chunks = text.split(Regex("\\n\\s*\\n")).map { c -> c.split("\n").filter { it.isNotBlank() } }.toMutableList()
        val head = if (chunks.isNotEmpty()) chunks.removeAt(0) else emptyList()
        val title = headingCase((head.firstOrNull() ?: "").replace(Regex("^NEXA\\s*-\\s*", RegexOption.IGNORE_CASE), ""))
        val meta = head.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { at -> line.substring(0, at).trim() to line.substring(at + 1).trim() } }
        val eff = meta.firstOrNull { it.first.lowercase() == "effective" }?.second ?: ""
        val doc = Doc(title, eff, meta, ArrayList(), ArrayList())
        for (lines in chunks) {
            if (lines.isEmpty()) continue
            if (Regex("^END OF ").containsMatchIn(lines[0].trim())) break
            val h = if (lines.size == 1) HEADING.find(lines[0].trim()) else null
            if (h != null) {
                doc.sections += Section("$idPrefix-${h.groupValues[1]}", h.groupValues[1], headingCase(h.groupValues[2]), ArrayList())
                continue
            }
            val b = block(lines)
            doc.sections.lastOrNull()?.blocks?.add(b) ?: doc.intro.add(b)
        }
        return doc
    }

    val terms: Doc by lazy { parse(runCatching { fish.nexa.data.Ota.open("legal/TERMS_OF_SERVICE.txt").bufferedReader().readText() }.getOrDefault(""), "terms") }
    val privacy: Doc by lazy { parse(runCatching { fish.nexa.data.Ota.open("legal/PRIVACY_POLICY.txt").bufferedReader().readText() }.getOrDefault(""), "privacy") }

    /**
     * TermsGate.tsx: after signing in, until this account agrees to the
     * current Terms on this device. It cannot be closed; Log Out is the
     * other way out.
     */
    fun gateIfNeeded() {
        val me = Nexa.users.me.value ?: return
        if (accepted(me.id) || Layers.stack.any { it.key == "terms" }) return
        lateinit var layer: Layer
        layer = Layer("terms", canClose = { false }) { _ ->
            val previous = previously(me.id)
            var checked by remember { mutableStateOf(false) }
            Sheet(
                layer,
                title = if (previous != null) t("legal.gateTitleUpdated") else t("legal.gateTitle"),
                closeButton = false,
                footer = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NxCheckbox(checked, { checked = it }, t("legal.gateCheck"))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NxButton(t("settings.settings.logOut"), {
                                Layers.remove(layer)
                                Nexa.scope.launch {
                                    Nexa.auth.logout()
                                    Nav.reset()
                                }
                            }, kind = ButtonKind.GHOST)
                            Spacer(Modifier.weight(1f))
                            NxButton(t("legal.agreeAndContinue"), {
                                accept(me.id)
                                Layers.remove(layer)
                            }, enabled = checked)
                        }
                    }
                },
            ) {
                Gate(previous)
            }
        }
        Layers.open(layer)
    }

    @Composable
    private fun Gate(previous: String?) {
        val c = Nx.colors
        var tab by remember { mutableStateOf("summary") }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(
                if (previous != null) t("legal.gateUpdatedIntro", "version" to effective) else t("legal.gateIntro", "version" to effective),
                style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(23.25f)), color = c.text,
            )
            if (previous != null) Changes(previous)
            Row(Modifier.sidewaysScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, label) in listOf("summary" to t("legal.readSummary"), "terms" to t("legal.termsTitle"), "privacy" to t("legal.privacyTitle"))) {
                    val on = tab == id
                    val shape = RoundedCornerShape(20.dp)
                    Box(
                        Modifier
                            .height(40.dp)
                            .then(if (on) Modifier.background(c.brand, shape) else Modifier.border(1.dp, c.border, shape))
                            .pressable(remember { MutableInteractionSource() }, role = Role.Tab) { tab = id }
                            .semantics { selected = on }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(500)), color = if (on) c.onBrand else c.textSubtle) }
                }
            }
            when (tab) {
                "summary" -> Highlights()
                else -> Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .border(1.dp, c.border, Radii.Sm)
                        .background(c.bgBase, Radii.Sm)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) { Document(if (tab == "terms") terms else privacy, small = true) }
            }
        }
    }

    @Composable
    private fun Highlights() {
        val c = Nx.colors
        val cards = listOf(
            Triple("Lock", t("legal.hlPrivateTitle"), t("legal.hlPrivateBody")),
            Triple("Eye", t("legal.hlStaffTitle"), t("legal.hlStaffBody")),
            Triple("Gavel", t("legal.hlModerationTitle"), t("legal.hlModerationBody")),
            Triple("ScrollText", t("legal.hlRulesTitle"), t("legal.hlRulesBody")),
            Triple("Database", t("legal.hlDataTitle"), t("legal.hlDataBody")),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((icon, title, body) in cards) {
                val shape = Radii.Md
                Row(
                    Modifier
                        .fillMaxWidth()
                        .shadowLow(shape, c)
                        .ring(shape, c.borderSubtle)
                        .background(c.bgRaised, shape)
                        .topEdge(shape, c.edgeLight)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(32.dp).background(c.brand.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, c.textBrand, size = 16.dp) }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt(title, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(700)), color = c.textStrong)
                        Txt(body, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19.6f)), color = c.textSubtle)
                    }
                }
            }
        }
    }

    @Composable
    /** LegalContent.tsx LegalChanges: what changed since the version agreed to before. */
    private fun Changes(previous: String) {
        val c = Nx.colors
        val shape = Radii.Sm
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, c.brand.copy(alpha = 0.3f), shape)
                .background(mixSrgb(Color.Transparent, c.brand, 0.09f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Icon("Sparkles", c.textBrand, size = 14.dp)
                Txt(t("legal.newTitle"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(700)), color = c.textBrand)
            }
            val items = listOf(t("legal.newCallUsage")) + (if (previous < "2026-10-28.1") listOf(t("legal.newNSFW")) else emptyList()) + (if (previous < "2026-10-26.1") listOf(t("legal.newPlans")) else emptyList()) + (if (previous < "2026-10-25.1") listOf(t("legal.newPasskeys")) else emptyList()) + (if (previous < "2026-10-20.2") listOf(t("legal.newAddresses")) else emptyList()) + (if (previous < "2026-10-20.1") listOf(t("legal.newCaptcha")) else emptyList()) + if (previous < "2026-10-19") listOf(t("legal.new1"), t("legal.new2"), t("legal.new3"), t("legal.new4"), t("legal.new5")) else emptyList()
            Bullets(items, ordered = false, size = 14f)
        }
    }

    private val LINKABLE = Regex("""([\w.+-]+@[\w-]+\.[\w.]+)|((?:https?://)?(?:nexa\.fish|mathtest\.lol|chat\.evilion\.xyz)(?:/[\w/-]*)?)""")

    /** Email addresses and Nexa's own addresses in the documents, tappable. */
    @Composable
    private fun linkify(text: String): AnnotatedString {
        val c = Nx.colors
        val ctx = androidx.compose.ui.platform.LocalContext.current
        return buildAnnotatedString {
            var last = 0
            for (m in LINKABLE.findAll(text)) {
                val raw = m.value.removeSuffix(".")
                append(text.substring(last, m.range.first))
                val target = if (m.groups[1] != null) "mailto:$raw" else if (raw.startsWith("http")) raw else "https://$raw"
                withLink(LinkAnnotation.Clickable(target, TextLinkStyles(SpanStyle(color = c.link))) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) } }) { append(raw) }
                last = m.range.first + raw.length
            }
            if (last < text.length) append(text.substring(last))
        }
    }

    @Composable
    private fun Bullets(items: List<String>, ordered: Boolean, size: Float = 15f) {
        val c = Nx.colors
        Column(verticalArrangement = Arrangement.spacedBy(if (size < 15f) 5.dp else 8.dp), modifier = Modifier.padding(start = 4.dp)) {
            items.forEachIndexed { i, it ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(if (ordered) "${i + 1}." else "•", style = Nx.type.body.copy(fontSize = Nx.type.sp(size), fontWeight = if (ordered) FontWeight(700) else FontWeight(400)), color = if (ordered) c.textBrand else c.textMuted)
                    BasicText(linkify(it), style = Nx.type.body.copy(fontSize = Nx.type.sp(size), lineHeight = Nx.type.sp(size * 1.6f), color = c.text))
                }
            }
        }
    }

    /** The Terms or the Privacy Policy, whole, in a sheet (the web's /terms and /privacy pages). */
    fun openDocument(which: String) {
        fish.nexa.ui.layers.openSheet("legal-$which", title = null) { _ ->
            Document(if (which == "privacy") privacy else terms)
            Spacer(Modifier.height(8.dp))
            ContactBlock()
        }
    }

    /** Contact the owner (nora) here: the address, and that a message in the app works too. */
    @Composable
    fun ContactBlock() {
        val c = Nx.colors
        Column(Modifier.padding(top = 16.dp)) {
            BasicText(linkify(t("legal.contactHere", "user" to ownerUsername) + " " + contactEmail), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(24.75f), color = c.text))
            Txt(t("legal.inApp", "user" to "@$ownerUsername"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(24.75f)), color = c.text)
        }
    }

    /** LegalDocument.tsx: the title, its dates, then numbered sections. */
    @Composable
    fun Document(doc: Doc, small: Boolean = false) {
        val c = Nx.colors
        Column {
            Txt(doc.title, style = Nx.type.heading.copy(fontSize = Nx.type.sp(if (small) 20f else 24f), fontWeight = FontWeight(800)), color = c.textStrong, modifier = Modifier.padding(bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                for ((label, value) in doc.meta) BasicText(linkify("$label: $value"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), color = c.textMuted))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Spacer(Modifier.height(18.dp))
            for (b in doc.intro) BlockView(b)
            for ((i, s) in doc.sections.withIndex()) {
                Row(Modifier.padding(top = if (i == 0) 0.dp else 28.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(s.number, style = Nx.type.body.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(700), fontFeatureSettings = "tnum"), color = c.textBrand)
                    Txt(s.title, style = Nx.type.body.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(700)), color = c.textStrong)
                }
                for (b in s.blocks) BlockView(b)
            }
        }
    }

    @Composable
    private fun BlockView(b: Block) {
        val c = Nx.colors
        val bodyStyle = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(24.75f), color = c.text)
        when (b) {
            is Block.P -> BasicText(linkify(b.text), Modifier.padding(bottom = 12.dp), style = bodyStyle)
            is Block.Ul -> Box(Modifier.padding(bottom = 12.dp)) { Bullets(b.items, ordered = false) }
            is Block.Ol -> Box(Modifier.padding(bottom = 12.dp)) { Bullets(b.items, ordered = true) }
            is Block.Item -> {
                val shape = Radii.Md
                Column(
                    Modifier
                        .padding(bottom = 10.dp)
                        .fillMaxWidth()
                        .shadowLow(shape, c)
                        .ring(shape, c.borderSubtle)
                        .background(c.bgRaised, shape)
                        .topEdge(shape, c.edgeLight)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    val text = linkify(b.text)
                    BasicText(
                        buildAnnotatedString {
                            if (b.lead.isNotEmpty()) withStyle(SpanStyle(color = c.textStrong, fontWeight = FontWeight(700))) { append(b.lead + ": ") }
                            append(text)
                        },
                        style = bodyStyle,
                    )
                    if (b.sub.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { Bullets(b.sub, ordered = false) }
                }
            }
            is Block.Lines -> {
                val shape = Radii.Md
                Column(
                    Modifier
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                        .ring(shape, c.borderSubtle)
                        .background(c.bgRaised, shape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    for (l in b.lines) BasicText(linkify(l), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(24.65f), color = c.text))
                }
            }
        }
    }
}
