package fish.nexa.ui.stickers

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ReportContext
import fish.nexa.media.Media
import fish.nexa.net.ApiError
import fish.nexa.net.StickerPack
import fish.nexa.ui.Icon
import fish.nexa.ui.chat.StickerMedia
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.reports.openReport
import fish.nexa.ui.settings.copyText
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A whole pack (StickerPackModal.tsx): the one chosen drawn big and moving, every
 * sticker in it by name, who made it and the one thing to do with it. Whatever
 * opened it passes what it already knows, and the rest arrives from the server.
 */
fun openPackSheet(packId: String, pack: StickerPack? = null, stickerId: String? = null) =
    fish.nexa.ui.dialogs.open("stickerPack") { layer, close -> PackSheet(layer, packId, pack, stickerId, close) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackSheet(layer: Layer, packId: String, given: StickerPack?, stickerId: String?, close: () -> Unit) {
    val c = Nx.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val me = Nexa.users.me.collectAsState().value?.id
    val packs by Nexa.expressions.packs.collectAsState()
    val doc by Nexa.settings.doc.collectAsState()
    val stored = packs[packId]
    var loaded by remember { mutableStateOf<StickerPack?>(null) }
    var failed by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(stickerId) }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(packId) {
        try {
            loaded = Nexa.api.get("/stickers/packs/$packId", StickerPack.serializer())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    val base = loaded ?: stored ?: given
    if (base == null) {
        Sheet(layer, title = if (failed) t("stickerPack.packUnavailable") else t("stickerPack.loading"), maxWidth = 480.dp) {
            if (failed) Txt(t("stickerPack.thisPackIsPrivate"), style = Nx.type.bodySmall, color = c.textMuted)
            else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
        }
        return
    }
    val pack = base.copy(installed = stored?.installed ?: base.installed)
    val items = remember(pack.stickers) { pack.stickers.sortedBy { it.position } }
    val current = items.firstOrNull { it.id == picked } ?: packCover(pack)
    val emoji = isEmojiPack(pack)
    val mine = me != null && pack.ownerUserId == me
    val removable = canRemovePack(pack, me)
    // a favourite only comes back from a pack the picker has
    val usable = !emoji && stored != null
    val favorite = current != null && current.id in FavoriteStickers.ids(doc)
    val tint = rememberPackTint(pack.copy(coverStickerId = current?.id ?: pack.coverStickerId))
    fun install() {
        busy = true
        scope.launch {
            try {
                val res = Nexa.api.request("PUT", "/stickers/packs/${pack.id}/install", StickerPack.serializer())
                Nexa.expressions.upsertPack(res)
                loaded = res
                Nexa.ui.toast(t(if (emoji) "modals.stickerPack.isInstalledEmoji" else "modals.stickerPack.isInstalled", "name" to pack.name), "success")
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("modals.stickerPack.couldNotInstallThePack"), "error")
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, title = pack.name, maxWidth = 640.dp, footer = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxButton(t("reports.report"), { openReport(ReportContext(kind = "pack", id = pack.id)) }, kind = ButtonKind.GHOST)
            Box(Modifier.weight(1f))
            if (pack.public) fish.nexa.ui.components.IconButton(if (copied) "Check" else "Copy", t("server.packEditor.copyLink"), { copied = copyText(ctx, packUrl(pack.id)) }, tint = c.icon)
            when {
                mine -> NxButton(t("stickerPack.editPack"), { close(); StickerSettings.openPack(pack.id) }, kind = ButtonKind.SECONDARY)
                pack.installed && removable -> NxButton(t("server.packEditor.removePack"), { confirmRemovePack(pack, close) }, kind = ButtonKind.SECONDARY)
                pack.installed -> NxButton(t("settings.stickers.installed"), {}, kind = ButtonKind.SECONDARY, enabled = false, icon = "Check")
                pack.public -> NxButton(t("stickerPack.installPack"), { install() }, loading = busy)
            }
        }
    }) {
        // who made it and how many have it, then what it says about itself
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val fact = Nx.type.hint.copy(fontSize = Nx.type.sp(13.5f))
            Txt(packCount(pack), style = fact, color = c.textMuted)
            if ((pack.installCount ?: 0) > 0) Txt(t("stickerPack.installs", "count" to (pack.installCount ?: 0)), style = fact, color = c.textMuted)
            pack.owner?.let { owner ->
                Box(Modifier.pressable(remember { MutableInteractionSource() }) { fish.nexa.ui.profile.Profiles.open(owner.id) }) {
                    Txt(t("stickerPack.madeBy", "name" to owner.name), style = fact, color = c.textMuted)
                }
            }
        }
        if (pack.description.isNotBlank()) Txt(pack.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textSubtle, modifier = Modifier.padding(top = 4.dp))
        if (current != null) {
            val shape = Radii.Lg
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .ring(shape, c.borderSubtle)
                    .background(mixOklab(c.bgRaised, tint, 0.26f), shape)
                    .background(sheen(c), shape)
                    .topEdge(shape, c.edgeLight)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val art = if (emoji) 64.dp else 96.dp
                    val url = if (emoji) Media.emojiUrl(current.blobId, 96, density) else Media.stickerUrl(current.blobId, 160, density)
                    androidx.compose.runtime.key(current.id) { StickerMedia(current.format, url, current.name, art) }
                    Column(Modifier.weight(1f)) {
                        Txt(if (emoji) ":${current.name}:" else current.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(18f), fontWeight = FontWeight(690)), color = c.textStrong, maxLines = 2)
                        if (current.tags.isNotBlank()) Txt(current.tags, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textSubtle, maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (usable) StageButton("Star", t(if (favorite) "pickers.gifPicker.removeFromFavorites" else "pickers.gifPicker.addToFavorites"), Modifier.weight(1f), on = favorite) { FavoriteStickers.toggle(current.id) }
                    StageButton("Flag", t("pickers.stickerPicker.reportSticker"), Modifier.weight(1f)) { openReport(ReportContext(kind = "sticker", id = current.id)) }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            val minWide = if (emoji) 64.dp else 76.dp
            val across = maxOf(2, ((maxWidth + 8.dp) / (minWide + 8.dp)).toInt())
            val w = with(LocalDensity.current) { (((maxWidth - 8.dp * (across - 1)).toPx() / across).toInt()).toDp() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = across) {
                for (s in items) {
                    val shape = Radii.Md
                    val on = s.id == current?.id
                    Column(
                        Modifier
                            .width(w)
                            .ring(shape, if (on) c.focusRing else c.borderSubtle, if (on) 2.dp else 1.dp)
                            .background(c.bgSurface, shape)
                            .background(sheen(c), shape)
                            .clip(shape)
                            .pressable(remember(s.id) { MutableInteractionSource() }) { picked = s.id }
                            .semantics { contentDescription = s.name }
                            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StickerStill(s, emoji, if (emoji) 48.dp else 96.dp, if (emoji) Modifier.size(48.dp) else Modifier.fillMaxWidth().aspectRatio(1f))
                        Txt(s.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(550)), color = c.textMuted, maxLines = 1, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (loaded == null && !failed) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
    }
}

@Composable
private fun StageButton(icon: String, label: String, modifier: Modifier, on: Boolean = false, onClick: () -> Unit) {
    val c = Nx.colors
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(Radii.Sm)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, if (on) c.warning else c.textSubtle, size = 15.dp, filled = on)
        Txt(label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1)
    }
}

/** packRemoval.ts: what removing a pack takes out of the picker, then out it goes. */
fun confirmRemovePack(p: StickerPack, then: (() -> Unit)? = null) {
    val count = p.stickers.size
    val body = when {
        count == 0 -> t("packRemoval.itLeavesYourPicker")
        isEmojiPack(p) -> t("packRemoval.itsEmojiLeaveYourPicker", "count" to count)
        else -> t("packRemoval.itsStickersLeaveYourPicker", "count" to count)
    } + " " + t("packRemoval.youCanInstallItAgain")
    fish.nexa.ui.shell.confirm(t("packRemoval.removePackNamed", "name" to p.name), body, t("pickers.stickerPicker.removePack")) {
        try {
            Nexa.api.request("DELETE", "/stickers/packs/${p.id}/install")
        } catch (e: ApiError) {
            throw fish.nexa.ui.ReadableError(e.message ?: t("packRemoval.couldNotRemoveThePack"))
        }
        // a pack of a server I am in stays, just not installed
        if (p.serverId != null && Nexa.servers.get(p.serverId) != null) Nexa.expressions.upsertPack(p.copy(installed = false))
        else Nexa.expressions.removePack(p.id)
        Nexa.ui.toast(t("pickers.stickerPicker.removedNamed", "name" to p.name), "success")
        then?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { it() } }
    }
}

/** Opening one pack's page in Settings, from wherever a pack is seen. */
object StickerSettings {
    /** The pack the page opens on next, used up when it does. */
    var requested by mutableStateOf<String?>(null)

    fun openPack(id: String) {
        requested = id
        fish.nexa.ui.settings.Settings.open("stickers")
    }
}
