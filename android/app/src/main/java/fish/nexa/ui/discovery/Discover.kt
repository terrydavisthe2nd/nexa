package fish.nexa.ui.discovery

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.net.Channel
import fish.nexa.net.PublicUser
import fish.nexa.net.ServerTag
import fish.nexa.net.ServerView
import fish.nexa.net.Snowflake
import fish.nexa.net.wireJson
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.components.AvatarColor
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.SelectField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.markdown.Markdown
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.NavButton
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A server as Discovery lists it (web DiscoveryCard). */
@Serializable
data class DiscoveryCard(
    val id: Snowflake,
    val name: String = "",
    val nsfw: Boolean = false,
    @SerialName("nsfw_content") val nsfwContent: Boolean = false,
    @SerialName("icon_id") val iconId: Snowflake? = null,
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    val description: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val language: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("online_count") val onlineCount: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("listed_at") val listedAt: Long? = null,
    val tag: ServerTag? = null,
    val member: Boolean = false,
    @SerialName("joins_paused") val joinsPaused: Boolean = false,
)

@Serializable
private data class SearchPage(val servers: List<DiscoveryCard> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false, @SerialName("next_offset") val nextOffset: Int? = null)

@Serializable
private data class Meta(val categories: List<String> = emptyList(), val counts: Map<String, Int> = emptyMap(), val total: Int = 0, val tags: List<String> = emptyList(), val languages: List<String> = emptyList())

@Serializable
private data class EmojiRef(val id: Snowflake, @SerialName("blob_id") val blobId: Snowflake = "", val name: String = "", val animated: Boolean = false)

@Serializable
private data class Detail(val server: DiscoveryCard, val emojis: List<EmojiRef> = emptyList(), val owner: PublicUser? = null)

@Serializable
private data class Joined(val server: ServerView, val users: List<PublicUser> = emptyList())

private val SIZES = listOf("any" to (null to null), "small" to (null to 50), "medium" to (50 to 500), "large" to (500 to null))

private fun count(n: Int): String = java.text.NumberFormat.getIntegerInstance().let { f ->
    if (n >= 10_000) android.icu.text.CompactDecimalFormat.getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT).format(n) else f.format(n)
}

private fun languageName(tag: String) = I18n.languages.firstOrNull { it.tag == tag }?.endonym ?: tag

/**
 * DiscoveryPage.tsx: every server whose owners listed it, searched by name,
 * words and tags, filed by category, and sorted by size or by how new it is
 * to the list. Anyone can list a server; nothing has to be earned first.
 */
@Composable
private fun ServerDiscovery() {
    val c = Nx.colors
    var q by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("any") }
    var sort by remember { mutableStateOf("members") }
    var showNSFW by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<DiscoveryCard>?>(null) }
    var next by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var requestGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val meta by produceState<Meta?>(null, showNSFW) { value = null; value = runCatching { Nexa.api.get("/discovery/categories" + if (showNSFW) "?nsfw=1" else "", Meta.serializer()) }.getOrNull() }

    fun path(offset: Int): String {
        val (min, max) = SIZES.first { it.first == size }.second
        val parts = buildList {
            if (showNSFW) add("nsfw=1")
            if (q.isNotBlank()) add("q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"))
            if (category.isNotEmpty()) add("category=$category")
            if (tag.isNotEmpty()) add("tag=" + java.net.URLEncoder.encode(tag, "UTF-8"))
            if (language.isNotEmpty()) add("language=$language")
            if (min != null) add("min_members=$min")
            if (max != null) add("max_members=$max")
            add("sort=$sort")
            add("limit=24")
            if (offset > 0) add("offset=$offset")
        }
        return "/discovery?" + parts.joinToString("&")
    }

    suspend fun load(offset: Int) {
        val generation = ++requestGeneration
        failed = false
        try {
            val page = Nexa.api.get(path(offset), SearchPage.serializer())
            if (generation != requestGeneration) return
            cards = if (offset > 0 && cards != null) cards!! + page.servers else page.servers
            next = if (page.hasMore) page.nextOffset else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (generation == requestGeneration) failed = true
        }
    }
    LaunchedEffect(q, category, tag, language, size, sort, reload, showNSFW) {
        ++requestGeneration
        if (q.isNotEmpty()) delay(300)
        load(0)
    }
    val filtered = q.isNotBlank() || category.isNotEmpty() || tag.isNotEmpty() || language.isNotEmpty() || size != "any"

        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("lead") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(t("discovery.heading"), style = Nx.type.title.copy(fontSize = Nx.type.sp(26f), lineHeight = Nx.type.sp(29f)), color = c.textStrong)
                    Txt(t("discovery.lead"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textMuted)
                }
            }
            item("search") {
                // the magnifier and the way to clear it, as the web's search box has them
                fish.nexa.ui.server.SearchBox(q, { q = it }, t("discovery.search"))
            }
            item("cats") {
                // one row that scrolls sideways, edge to edge, so the servers start higher up
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = t("discovery.categories") },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CategoryChip(t("discovery.all"), meta?.total, category.isEmpty()) { category = "" }
                    for (cat in meta?.categories ?: emptyList()) CategoryChip(t("discovery.cat.$cat"), meta?.counts?.get(cat), category == cat) { category = if (category == cat) "" else cat }
                }
            }
            item("sorts") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SortStrip(sort) { sort = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            SelectField(t("discovery.size"), SIZES.map { it.first to t("discovery.sizes.${it.first}") }, size) { size = it }
                        }
                        Box(Modifier.weight(1f)) {
                            val langs = meta?.languages?.takeIf { it.isNotEmpty() } ?: I18n.languages.map { it.tag }
                            SelectField(t("discovery.language"), listOf("" to t("discovery.anyLanguage")) + langs.map { it to languageName(it) }, language) { language = it }
                        }
                    }
                    if (tag.isNotEmpty()) Row(
                        Modifier.background(mixOklab(c.bgSurface, c.brand, 0.22f), RoundedCornerShape(999.dp)).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(t("discovery.taggedWith", "tag" to tag), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textStrong)
                        Box(Modifier.size(40.dp).pressable(remember { MutableInteractionSource() }) { tag = "" }.semantics { contentDescription = t("discovery.clearTag") }, contentAlignment = Alignment.Center) {
                            Icon("X", c.textStrong, size = 14.dp)
                        }
                    }
                }
            }
            item("nsfw") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon("TriangleAlert", c.warning, size = 16.dp)
                    Txt(t("nsfw.label"), style = Nx.type.bodySmall, color = c.textMuted)
                    fish.nexa.ui.components.NxToggle(showNSFW, { on ->
                        if (!on) { ++requestGeneration; showNSFW = false; cards = null; next = null }
                        else fish.nexa.ui.shell.confirm(t("nsfw.warningTitle"), t("nsfw.discoveryWarning"), t("nsfw.confirm"), danger = false) { ++requestGeneration; showNSFW = true; cards = null; next = null }
                    }, label = t("nsfw.label"))
                }
            }
            val popular = meta?.tags?.filter { !it.equals("nsfw", ignoreCase = true) }?.take(12) ?: emptyList()
            if (tag.isEmpty() && popular.isNotEmpty()) item("popular") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Txt(t("discovery.popularTags"), style = Nx.type.bodySmall, color = c.textMuted)
                    for (tg in popular) TagButton(tg) { tag = tg }
                }
            }
            val list = cards
            when {
                failed && list == null -> item("failed") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(t("discovery.failed"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center)
                        NxButton(t("chat.messageList.tryAgain"), { reload += 1 }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    }
                }
                list == null -> item("loading") { Txt(t("discovery.loading"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
                list.isEmpty() -> item("empty") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon("Compass", c.textMuted, size = 36.dp)
                        Txt(if (filtered) t("discovery.noMatches") else t("discovery.nothingYet"), style = Nx.type.heading, color = c.textStrong, align = TextAlign.Center)
                        Txt(if (filtered) t("discovery.noMatchesHint") else t("discovery.nothingYetHint"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
                    }
                }
                else -> items(list, key = { it.id }) { card -> ServerCard(card, onTag = { tag = it }) { openServerSheet(card) } }
            }
            next?.let { offset ->
                item("more") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NxButton(t("discovery.more"), { scope.launch { load(offset) } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    }
                }
            }
        }
}

/**
 * The Discover tab (DiscoveryPage.tsx): servers to join, and sticker and emoji
 * packs to install. A part once opened stays composed, so coming back keeps its
 * search and where it was scrolled to.
 */
@Composable
fun DiscoverPage(section: String = "servers") {
    val c = Nx.colors
    var open by remember(section) { mutableStateOf(section) }
    var seen by remember { mutableStateOf(setOf(section)) }
    if (open !in seen) seen = seen + open
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .boxShadow(RoundedCornerShape(0.dp), 8f, 16f, c.shadeAt(0.9f), spread = -14f)
                .background(c.bgRaised)
                .background(sheen(c))
                .padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NavButton()
            Icon("Compass", c.textMuted, size = 22.dp)
            Txt(t("discovery.title"), style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(660)), color = c.textStrong, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            fish.nexa.ui.settings.Segmented(
                listOf(t("discovery.tab.servers") to "servers", t("pickers.expressionPicker.stickers") to "stickers", t("modals.customStatus.emoji") to "emoji"),
                open,
                { open = it },
                label = t("discovery.title"),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            for (id in listOf("servers", "stickers", "emoji")) if (id in seen) {
                // what is not open keeps its place and draws nothing
                Box(if (open == id) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) {
                    when (id) {
                        "servers" -> ServerDiscovery()
                        "stickers" -> PackDiscovery("sticker")
                        else -> PackDiscovery("emoji")
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, n: Int?, on: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .then(if (on) Modifier else Modifier.ring(shape, c.borderSubtle))
            .clip(shape)
            .then(if (on) Modifier.background(brandGradient(c)) else Modifier.background(c.bgSurface))
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(label, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (on) c.onBrand else c.text, maxLines = 1)
        if (n != null && n > 0) Txt(n.toString(), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(500)), color = (if (on) c.onBrand else c.text).copy(alpha = 0.7f), maxLines = 1)
    }
}

@Composable
private fun SortStrip(sort: String, onSort: (String) -> Unit) = SortStrip(sort, listOf("members", "newest", "created"), { t("discovery.sort.$it") }, onSort = onSort)

@Composable
internal fun SortStrip(sort: String, options: List<String>, label: (String) -> String, name: String = t("discovery.sortLabel"), onSort: (String) -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().sunken(shape, c).ring(shape, c.borderSubtle).padding(3.dp).semantics { contentDescription = name },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (x in options) {
            val on = sort == x
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .then(if (on) Modifier.background(c.bgSurface).background(sheen(c)).topEdge(RoundedCornerShape(9.dp), c.edgeLight) else Modifier)
                    .pressable(remember { MutableInteractionSource() }) { onSort(x) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) { Txt(label(x), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.textMuted, maxLines = 1, align = TextAlign.Center) }
        }
    }
}

@Composable
private fun TagButton(tag: String, onClick: () -> Unit) {
    val c = Nx.colors
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.bgActive)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(if (tag.equals("nsfw", ignoreCase = true)) t("nsfw.label") else tag, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600)), color = c.text, maxLines = 1) }
}

/** A server's colour where it has no banner: its own steady colour, deepened. */
@Composable
private fun Band(card: DiscoveryCard, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    val tint = AvatarColor.of(card.id)
    Box(modifier.background(Brush.linearGradient(listOf(tint, mixOklab(tint, Color.Black, 0.45f))))) {
        if (card.bannerId != null) AsyncImage(Media.bannerUrl(card.bannerId, 600, density), null, Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ServerIconTile(card: DiscoveryCard, size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp, ring: Color) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier.size(size).ring(shape, ring, 5.dp).clip(shape).background(AvatarColor.of(card.id)),
        contentAlignment = Alignment.Center,
    ) {
        if (card.iconId != null) AsyncImage(Media.iconUrl(card.iconId, size.value.toInt(), density), null, Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Txt(AvatarColor.initials(card.name), style = Nx.type.name.copy(fontSize = Nx.type.sp(size.value * 0.36f), fontWeight = FontWeight(700)), color = Color.White)
    }
}

@Composable
private fun Stats(card: DiscoveryCard) {
    val c = Nx.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(c.statusOnline, CircleShape))
            Txt(t("discovery.online", "count" to card.onlineCount, "shown" to count(card.onlineCount)), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(c.textMuted, CircleShape))
            Txt(t("discovery.members", "count" to card.memberCount, "shown" to count(card.memberCount)), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
        }
    }
}

/** A server: its banner (or its colour), its icon set into it, then what it is. */
@Composable
internal fun ServerCard(card: DiscoveryCard, onTag: (String) -> Unit, onOpen: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Lg
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val words = remember(card.description) { Markdown.plainText(Markdown.parse(card.description, inline = true), fish.nexa.ui.chat.AppNames).replace(Regex("\\s+"), " ").trim() }
    Column(
        Modifier
            .fillMaxWidth()
            .ring(shape, if (pressed) c.border else c.borderSubtle)
            .clip(shape)
            .background(c.bgRaised, shape)
            .topEdge(shape, c.edgeLight)
            .pressable(interaction, onClick = onOpen)
            .semantics { contentDescription = card.name },
    ) {
        Band(card, Modifier.fillMaxWidth().height(96.dp))
        Box(Modifier.padding(start = 16.dp).offset(y = (-30).dp)) { ServerIconTile(card, 60.dp, 18.dp, c.bgRaised) }
        Column(Modifier.fillMaxWidth().offset(y = (-22).dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(card.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (card.nsfw) Badge(t("nsfw.label"), c.warning)
                when {
                    card.member -> Badge(t("discovery.joined"), c.positive)
                    card.joinsPaused -> Badge(t("discovery.paused"), c.warning)
                }
            }
            if (words.isNotEmpty()) Txt(words, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20f)), color = c.textMuted, maxLines = 3)
            if (card.tags.any { !it.equals("nsfw", ignoreCase = true) }) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (tg in card.tags.filter { !it.equals("nsfw", ignoreCase = true) }) TagButton(tg) { onTag(tg) }
            }
            Stats(card)
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun Badge(text: String, tone: Color) {
    Txt(
        text,
        style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(650)),
        color = Nx.colors.textStrong,
        modifier = Modifier.background(tone.copy(alpha = 0.22f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** One server, opened from Discovery: everything its listing says, and the way in. */
private fun openServerSheet(card: DiscoveryCard) = fish.nexa.ui.dialogs.open("discoveryServer") { layer, close -> ServerSheet(layer, card, close) }

@Composable
private fun ServerSheet(layer: Layer, card: DiscoveryCard, close: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val density = LocalDensity.current.density
    val detail by produceState<Detail?>(null, card.id) { value = runCatching { Nexa.api.get("/discovery/${card.id}" + if (card.nsfw) "?nsfw=1" else "", Detail.serializer()) }.getOrNull() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun firstChannel(list: Collection<Channel>) = list.filter { it.serverId == card.id && (it.isText || it.isAnnouncement) }.minByOrNull { it.position }
    fun join() {
        if (card.member) {
            close()
            val first = firstChannel(Nexa.channels.byId.value.values)
            Nav.go(Route.Server(card.id, first?.id))
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val res = Nexa.auth.guarded("join") { captcha -> Nexa.api.request("POST", "/discovery/${card.id}/join" + if (card.nsfw) "?nsfw=1" else "", captcha = captcha) }
                val joined = wireJson.decodeFromJsonElement(Joined.serializer(), res)
                Nexa.session.putServer(joined.server, joined.users)
                close()
                Nav.go(Route.Server(card.id, firstChannel(joined.server.channels)?.id))
                Nexa.ui.toast(t("discovery.welcome", "name" to card.name), "success")
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("discovery.joinFailed")), "error")
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, bare = true, padding = 0.dp, maxWidth = 560.dp, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            NxButton(t("reports.report"), { fish.nexa.ui.reports.openReport(fish.nexa.data.ReportContext(kind = "server", id = card.id)) }, kind = ButtonKind.GHOST)
            NxButton(t("discovery.close"), close, kind = ButtonKind.GHOST)
            NxButton(if (card.member) t("discovery.open") else t("discovery.join"), ::join, loading = busy, enabled = card.member || !card.joinsPaused)
        }
    }) {
        Band(card, Modifier.fillMaxWidth().height(140.dp))
        Box(Modifier.padding(start = 20.dp).offset(y = (-36).dp)) { ServerIconTile(card, 80.dp, 24.dp, c.bgFloating) }
        Column(Modifier.fillMaxWidth().offset(y = (-24).dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card.nsfw) Badge(t("nsfw.label"), c.warning)
            Txt(card.name, style = Nx.type.title.copy(fontSize = Nx.type.sp(24f), lineHeight = Nx.type.sp(28f)), color = c.textStrong, maxLines = 2)
            // `.facts`: who is about, how many belong, and what it is
            val fact = Nx.type.hint.copy(fontSize = Nx.type.sp(13.5f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(c.statusOnline, CircleShape))
                    Txt(t("discovery.online", "count" to card.onlineCount, "shown" to count(card.onlineCount)), style = fact, color = c.textMuted, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon("Users", c.textMuted, size = 14.dp)
                    Txt(t("discovery.members", "count" to card.memberCount, "shown" to count(card.memberCount)), style = fact, color = c.textMuted, maxLines = 1)
                }
                if (card.category.isNotEmpty()) Txt(t("discovery.cat.${card.category}"), style = fact, color = c.textMuted)
                if (card.language.isNotEmpty()) Txt(languageName(card.language), style = fact, color = c.textMuted)
            }
            if (card.description.isNotBlank()) fish.nexa.ui.markdown.MarkdownView(card.description, fish.nexa.ui.markdown.RenderCtx(surface = c.bgFloating))
            if (card.tags.any { !it.equals("nsfw", ignoreCase = true) }) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (tg in card.tags.filter { !it.equals("nsfw", ignoreCase = true) }) Txt(if (tg.equals("nsfw", ignoreCase = true)) t("nsfw.label") else tg, style = Nx.type.hint, color = c.text, modifier = Modifier.background(c.bgActive, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            val emojis = detail?.emojis ?: emptyList()
            if (emojis.isNotEmpty()) FlowRow(Modifier.semantics { contentDescription = t("discovery.emojis") }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in emojis.filter { it.blobId.isNotEmpty() }) AsyncImage(Media.emojiUrl(e.blobId, 32, density), ":${e.name}:", Media.loader, modifier = Modifier.size(32.dp))
            }
            detail?.owner?.let { Txt(t("discovery.ownedBy", "name" to it.name), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13.5f)), color = c.textMuted) }
            if (card.joinsPaused && !card.member) Txt(t("discovery.pausedLong"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13.5f)), color = c.textMuted)
        }
    }
}
