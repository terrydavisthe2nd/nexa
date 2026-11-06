package fish.nexa.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.net.ApiError
import fish.nexa.net.DiscoverPacksPage
import fish.nexa.net.StickerPack
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.stickers.StickerStill
import fish.nexa.ui.stickers.openPackSheet
import fish.nexa.ui.stickers.packCount
import fish.nexa.ui.stickers.rememberPackTint
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Tilt(val degrees: Float, val y: Int)

/** Each sticker's slight tilt on its sheet, as PackDiscovery.module.css has them. */
private val TILTS = listOf(Tilt(-5f, 0), Tilt(3f, 5), Tilt(-2f, -4), Tilt(6f, 3))

/** Discovery's sticker or emoji part (PackDiscovery.tsx): every public pack of that kind, searched by its words and its stickers' names and tags. */
@Composable
internal fun PackDiscovery(kind: String) {
    val c = Nx.colors
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("popular") }
    var list by remember { mutableStateOf<List<StickerPack>?>(null) }
    var next by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val emoji = kind == "emoji"
    suspend fun load(offset: Int) {
        val mine = ++generation
        failed = false
        try {
            val parts = buildList {
                add("kind=$kind")
                add("sort=$sort")
                add("limit=24")
                if (q.isNotBlank()) add("q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"))
                if (offset > 0) add("offset=$offset")
            }
            val page = Nexa.api.get("/stickers/discover?" + parts.joinToString("&"), DiscoverPacksPage.serializer())
            if (mine != generation) return
            list = if (offset > 0 && list != null) list!! + page.packs else page.packs
            next = if (page.hasMore) page.nextOffset else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (mine == generation) failed = true
        }
    }
    LaunchedEffect(q, sort, reload) {
        ++generation
        if (q.isNotEmpty()) delay(300)
        load(0)
    }
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("lead") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(t(if (emoji) "discovery.packs.emojiHeading" else "discovery.packs.stickerHeading"), style = Nx.type.title.copy(fontSize = Nx.type.sp(26f), lineHeight = Nx.type.sp(29f)), color = c.textStrong)
                Txt(t(if (emoji) "discovery.packs.emojiLead" else "discovery.packs.stickerLead"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textMuted)
            }
        }
        item("search") { fish.nexa.ui.server.SearchBox(q, { q = it }, t(if (emoji) "discovery.packs.searchEmoji" else "discovery.packs.searchStickers")) }
        item("sorts") { SortStrip(sort, listOf("popular", "newest", "updated"), { t("discovery.packs.sort.$it") }, t("discovery.packs.sortLabel")) { sort = it } }
        val l = list
        when {
            failed && l == null -> item("failed") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(t("discovery.packs.failed"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center)
                    NxButton(t("chat.messageList.tryAgain"), { reload += 1 }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                }
            }
            l == null -> item("loading") { Txt(t("discovery.packs.loading"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
            l.isEmpty() -> item("empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon("Sticker", c.textMuted, size = 36.dp)
                    Txt(t(if (q.isNotBlank()) "discovery.packs.noMatches" else "discovery.packs.nothingYet"), style = Nx.type.heading, color = c.textStrong, align = TextAlign.Center)
                    Txt(t(if (q.isNotBlank()) "discovery.packs.noMatchesHint" else "discovery.packs.nothingYetHint"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
                }
            }
            else -> items(l, key = { it.id }) { p -> PackCard(p) { changed -> list = list?.map { if (it.id == changed.id) changed else it } } }
        }
        next?.let { offset ->
            item("more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NxButton(t("discovery.packs.more"), { scope.launch { load(offset) } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                }
            }
        }
    }
}

/** A pack on Discovery: a sheet of its stickers on the pack's own colour, what it is, and the button to have it. */
@Composable
private fun PackCard(pack: StickerPack, onChange: (StickerPack) -> Unit) {
    val c = Nx.colors
    val shape = Radii.Lg
    val scope = rememberCoroutineScope()
    val me = Nexa.users.me.collectAsState().value?.id
    val packs by Nexa.expressions.packs.collectAsState()
    val installed = packs[pack.id]?.installed ?: pack.installed
    val emoji = pack.kind == "emoji"
    var busy by remember { mutableStateOf(false) }
    val tint = rememberPackTint(pack)
    val shown = pack.stickers.take(if (emoji) 6 else 4)
    val left = (pack.stickerCount ?: pack.stickers.size) - shown.size
    Column(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .background(c.bgRaised, shape)
            .topEdge(shape, c.edgeLight)
            .pressable(remember { MutableInteractionSource() }) { openPackSheet(pack.id, pack) }
            .semantics { contentDescription = t("stickerPack.viewNamed", "name" to pack.name) },
    ) {
        Box(Modifier.fillMaxWidth().height(112.dp).background(mixOklab(c.bgRaised, tint, 0.34f)).background(sheen(c)).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (emoji) 10.dp else 0.dp, Alignment.CenterHorizontally)) {
                shown.forEachIndexed { i, s ->
                    if (emoji) StickerStill(s, true, 38.dp, Modifier.size(38.dp))
                    else {
                        val tilt = TILTS[i % TILTS.size]
                        Box(Modifier.weight(1f, fill = false).widthIn(max = 84.dp).aspectRatio(1f).offset(y = tilt.y.dp).rotate(tilt.degrees)) { StickerStill(s, false, 84.dp, Modifier.fillMaxSize()) }
                    }
                }
            }
            if (left > 0) Txt(
                "+$left",
                style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(650)),
                color = c.textStrong,
                modifier = Modifier.align(Alignment.BottomEnd).background(c.shadeAt(0.72f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(pack.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val fact = Nx.type.hint.copy(fontSize = Nx.type.sp(13f))
                Txt(packCount(pack), style = fact, color = c.textMuted, maxLines = 1)
                if ((pack.installCount ?: 0) > 0) Txt(t("stickerPack.installs", "count" to (pack.installCount ?: 0)), style = fact, color = c.textMuted, maxLines = 1)
            }
            pack.owner?.let { Txt(t("stickerPack.madeBy", "name" to it.name), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1) }
            if (pack.description.isNotBlank()) Txt(pack.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(20f)), color = c.textSubtle, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 36.dp)) {
                when {
                    pack.ownerUserId == me -> NxButton(t("pickers.stickerPicker.viewPack"), { openPackSheet(pack.id, pack) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, block = true)
                    installed -> NxButton(t("settings.stickers.installed"), {}, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, block = true, enabled = false, icon = "Check")
                    else -> NxButton(t("settings.stickers.install"), {
                        busy = true
                        scope.launch {
                            try {
                                Nexa.expressions.upsertPack(Nexa.api.request("PUT", "/stickers/packs/${pack.id}/install", StickerPack.serializer()))
                                onChange(pack.copy(installed = true, installCount = (pack.installCount ?: 0) + 1))
                            } catch (e: Exception) {
                                Nexa.ui.toast((e as? ApiError)?.message ?: t("modals.stickerPack.couldNotInstallThePack"), "error")
                            } finally {
                                busy = false
                            }
                        }
                    }, size = ButtonSize.SM, block = true, loading = busy, icon = "PackagePlus")
                }
            }
        }
    }
}
