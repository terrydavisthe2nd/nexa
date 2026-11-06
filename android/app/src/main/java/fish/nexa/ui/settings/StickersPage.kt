package fish.nexa.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.net.ApiError
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.net.wireJson
import fish.nexa.ui.Icon
import fish.nexa.ui.auth.ErrorBox
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.stickers.StickerSettings
import fish.nexa.ui.stickers.StickerStill
import fish.nexa.ui.stickers.canRemovePack
import fish.nexa.ui.stickers.confirmRemovePack
import fish.nexa.ui.stickers.isEmojiPack
import fish.nexa.ui.stickers.openPackSheet
import fish.nexa.ui.stickers.packCount
import fish.nexa.ui.stickers.packCover
import fish.nexa.ui.stickers.packUrl
import fish.nexa.ui.stickers.rememberPackTint
import fish.nexa.ui.t
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

private val ACCEPT_IMAGE = setOf("image/png", "image/gif", "image/webp", "image/apng")
private const val MAX_STICKER = 5L * 1024 * 1024
private const val MAX_EMOJI = 2L * 1024 * 1024
/** How many a pack holds before its page offers a search of its own. */
private const val SEARCH_FROM = 24

private fun isEmoji(p: StickerPack) = isEmojiPack(p)
private fun canRemove(p: StickerPack, me: String?) = canRemovePack(p, me)

private suspend fun fetchPack(id: String): StickerPack = Nexa.api.get("/stickers/packs/$id", StickerPack.serializer()).also { Nexa.expressions.upsertPack(it) }

/** Emoji & Stickers: every pack this account has, and a page for each of its own. */
@Composable
fun StickersPage() {
    val host = LocalAnchors.current
    var tab by remember { mutableStateOf("sticker") }
    var editing by remember { mutableStateOf<String?>(null) }
    // a link to one pack's page from wherever it was seen
    val asked = StickerSettings.requested
    LaunchedEffect(asked) {
        if (asked != null) {
            editing = asked
            StickerSettings.requested = null
        }
    }
    // a pack opens as a level of its own; back comes to the list
    DisposableEffect(editing) {
        host?.inner = if (editing != null) ({ PackEditing.close(); editing = null }) else null
        onDispose { if (editing != null) host?.inner = null }
    }
    DisposableEffect(Unit) { onDispose { PackEditing.close() } }
    LaunchedEffect(editing) { host?.scroll?.scrollTo(0) }
    val open = editing
    if (open != null) {
        PackView(open, onBack = { Unsaved.unlessUnsaved { PackEditing.close(); editing = null } })
        return
    }
    PageTitle(t("settings.groups.emojiStickers"))
    Hint(t("settings.stickers.buildYourOwnEmojiAndSticker"), Modifier.padding(bottom = 14.dp).settingAnchor("packs"))
    Segmented(
        listOf(t("pickers.expressionPicker.stickers") to "sticker", t("modals.customStatus.emoji") to "emoji"), tab,
        { tab = it },
        label = t("settings.groups.emojiStickers"),
    )
    Column(Modifier.padding(top = 16.dp)) { Library(tab) { editing = it } }
}

/** The list: the pack's cover and what it is, in a panel to each group. */
@Composable
private fun Library(kind: String, onOpen: (String) -> Unit) {
    val c = Nx.colors
    val me = Nexa.users.me.collectAsState().value?.id
    val packs by Nexa.expressions.packs.collectAsState()
    var q by remember(kind) { mutableStateOf("") }
    val list = packs.values.filter { it.kind == kind }.sortedByDescending { it.updatedAt }
    val own = list.filter { it.serverId == null && it.ownerUserId == me }
    val installed = list.filter { it !in own && (canRemove(it, me) || (it.serverId == null && it.installed)) }
    val fromServers = list.filter { it !in own && it !in installed && it.serverId != null }
    val query = q.trim().lowercase()
    fun shown(of: List<StickerPack>) = if (query.isEmpty()) of else of.filter { it.name.lowercase().contains(query) }
    if (list.size > 3) Box(Modifier.padding(bottom = 10.dp)) { fish.nexa.ui.server.SearchBox(q, { q = it }, t("settings.stickers.searchYourPacks")) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NxButton(
            t(if (kind == "emoji") "settings.stickers.createEmojiPack" else "settings.stickers.createStickerPack"),
            { openCreatePack(kind, onOpen) }, icon = "Plus", size = ButtonSize.SM, modifier = Modifier.weight(1f), block = true,
        )
        NxButton(t("pickers.stickerPicker.findMorePacks"), {
            fish.nexa.ui.layers.Layers.close("settings")
            fish.nexa.ui.nav.Nav.go(fish.nexa.ui.nav.Route.Discover(if (kind == "emoji") "emoji" else "stickers"))
        }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Compass", modifier = Modifier.weight(1f), block = true)
    }
    if (list.isEmpty()) Box(Modifier.padding(top = 14.dp)) { EmptyBox(t(if (kind == "emoji") "settings.stickers.noEmojiPacksYet" else "settings.stickers.noStickerPacksYet")) }
    else if (shown(own).isEmpty() && shown(installed).isEmpty() && shown(fromServers).isEmpty()) Box(Modifier.padding(top = 14.dp)) { EmptyBox(t("settings.stickers.noPacksMatch")) }
    PackGroup(t("settings.stickers.yourPacks"), shown(own), manage = true, removable = { false }, note = { null }, onOpen)
    PackGroup(t("expressions.installed"), shown(installed), manage = false, removable = { canRemove(it, me) }, note = { null }, onOpen)
    PackGroup(t("settings.stickers.fromYourServers"), shown(fromServers), manage = false, removable = { false }, note = { p -> p.serverId?.let { Nexa.servers.get(it)?.server?.name } }, onOpen)
}

@Composable
internal fun PackGroup(title: String?, packs: List<StickerPack>, manage: Boolean, removable: (StickerPack) -> Boolean, note: (StickerPack) -> String?, onOpen: (String) -> Unit) {
    if (packs.isEmpty()) return
    val c = Nx.colors
    val shape = Radii.Lg
    if (title != null) Row(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(title, style = Nx.type.name.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong)
        Txt(packs.size.toString(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(500)), color = c.textMuted)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.panelEdge)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape),
    ) {
        packs.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            PackRow(p, manage, removable(p), note(p)) { if (manage) onOpen(p.id) else openPackSheet(p.id, p) }
        }
    }
}

/** A pack is public or private: the words and the icon to go with them. */
@Composable
private fun Visibility(public: Boolean, tint: androidx.compose.ui.graphics.Color, size: Float = 13f) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (public) "Globe" else "Lock", tint, size = 12.dp)
        Txt(t(if (public) "settings.stickers.publicBadge" else "settings.stickers.privateBadge"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(size)), color = tint, maxLines = 1)
    }
}

@Composable
private fun PackRow(p: StickerPack, manage: Boolean, removable: Boolean, note: String?, onClick: () -> Unit) {
    val c = Nx.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(start = 14.dp, end = if (removable) 2.dp else 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).sunken(Radii.Sm, c).ring(Radii.Sm, c.borderSubtle), contentAlignment = Alignment.Center) {
            val cover = packCover(p)
            if (cover != null) StickerStill(cover, isEmoji(p), 48.dp, Modifier.size(if (isEmoji(p)) 30.dp else 44.dp))
            else Txt(p.name.take(1).uppercase(), style = Nx.type.body.copy(fontWeight = FontWeight(700)), color = c.textMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(p.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(packCount(p), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
                if (manage) Visibility(p.public, if (p.public) c.textSubtle else c.textMuted)
                if (note != null) Txt(note, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1)
            }
        }
        if (manage) Icon(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 18.dp)
        if (removable) IconButton("X", t("pickers.stickerPicker.removeName", "name" to p.name), { confirmRemovePack(p) }, tint = c.icon, size = 48.dp, iconSize = 16.dp)
    }
}

// ---- one pack ----

/** The name and description being edited, against what the server last said. */
class PackForm(val packId: String, name: String, description: String) {
    var name by mutableStateOf(name)
    var description by mutableStateOf(description)
    var savedName by mutableStateOf(name)
    var savedDescription by mutableStateOf(description)
    var saving by mutableStateOf(false)
    val dirty get() = name != savedName || description != savedDescription
}

object PackEditing {
    var form by mutableStateOf<PackForm?>(null)
    fun close() {
        form = null
    }
}

/** One pack you can change: its stickers first, since they are what you came for, then what it is called and who can have it. */
@Composable
private fun PackView(packId: String, onBack: () -> Unit) {
    val me = Nexa.users.me.collectAsState().value?.id
    val packs by Nexa.expressions.packs.collectAsState()
    val pack = packs[packId]
    var installs by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(packId) { runCatching { installs = fetchPack(packId).installCount } }
    BackRowTo(t("settings.groups.emojiStickers"), onBack)
    if (pack == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
        return
    }
    // only your own packs have a page; any other opened by a link is just looked at
    if (pack.ownerUserId != me) {
        LaunchedEffect(packId) {
            onBack()
            openPackSheet(packId, pack)
        }
        return
    }
    val form = PackEditing.form?.takeIf { it.packId == packId } ?: PackForm(packId, pack.name, pack.description).also { PackEditing.form = it }
    PackBody(pack, installs, form.name, { form.name = it }, form.description, { form.description = it }) {
        PackEditing.close()
        onBack()
    }
}

/**
 * What a pack you can change has on its page, in User Settings and Server
 * Settings alike: its colour, its stickers first, then what it is called and who
 * can have it. The frame around it keeps the name and description being edited.
 */
@Composable
internal fun PackBody(pack: StickerPack, installs: Int?, name: String, onName: (String) -> Unit, description: String, onDescription: (String) -> Unit, onDeleted: () -> Unit) {
    val c = Nx.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val emoji = isEmoji(pack)
    var total by remember { mutableIntStateOf(0) }
    var doneCount by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var done = 0
            total = uris.size
            doneCount = 0
            try {
                for (uri in uris) {
                    if (uploadItem(ctx, pack, uri)) done++
                    doneCount++
                }
                fetchPack(pack.id)
                if (done > 0) Nexa.ui.toast(t(if (emoji) "server.packEditor.emojiAdded" else "server.packEditor.stickersAdded", "count" to done), "success")
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("composer.composer.uploadFailed"), "error")
                runCatching { fetchPack(pack.id) }
            } finally {
                total = 0
            }
        }
    }
    val addFiles = {
        pick.launch(if (emoji) arrayOf("image/png", "image/gif", "image/webp", "image/apng") else arrayOf("image/png", "image/gif", "image/webp", "image/apng", "video/webm", "application/x-tgsticker", "application/gzip", "application/octet-stream"))
    }
    val addLabel = t(if (emoji) "android.settings.stickers.addEmoji" else "android.settings.stickers.addStickers")
    val tint = rememberPackTint(pack)
    val shape = Radii.Lg

    // the one bold thing on the page: the pack on its own colour
    Row(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.panelEdge)
            .background(mixOklab(c.bgRaised, tint, 0.26f), shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            val cover = packCover(pack)
            if (cover != null) fish.nexa.ui.chat.StickerMedia(cover.format, if (emoji) Media.emojiUrl(cover.blobId, 96, density) else Media.stickerUrl(cover.blobId, 160, density), "", if (emoji) 64.dp else 80.dp)
            else Txt(pack.name.take(1).uppercase(), style = Nx.type.title.copy(fontSize = Nx.type.sp(36f)), color = c.textStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(pack.name, style = Nx.type.title.copy(fontSize = Nx.type.sp(22f), lineHeight = Nx.type.sp(26f)), color = c.textStrong, maxLines = 2)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val fact = Nx.type.hint.copy(fontSize = Nx.type.sp(13.5f))
                Txt(packCount(pack), style = fact, color = c.textSubtle, maxLines = 1)
                Visibility(pack.public, c.textSubtle, 13.5f)
                if (pack.public && installs != null) Txt(t("stickerPack.installs", "count" to (installs ?: 0)), style = fact, color = c.textSubtle, maxLines = 1)
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NxButton(addLabel, addFiles, size = ButtonSize.SM, icon = "Upload")
                NxButton(t("pickers.stickerPicker.viewPack"), { openPackSheet(pack.id, pack) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Eye")
            }
        }
    }
    if (total > 0) Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).sunken(Radii.Pill, c)) {
            Box(Modifier.fillMaxWidth((doneCount.toFloat() / total).coerceIn(0f, 1f)).height(6.dp).background(fish.nexa.ui.theme.brandGradient(c), Radii.Pill))
        }
        Txt(t("server.packEditor.uploadingProgress", "done" to doneCount, "total" to total), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
    }

    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t(if (emoji) "modals.customStatus.emoji" else "pickers.expressionPicker.stickers"), style = Nx.type.name.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong, modifier = Modifier.weight(1f))
        if (pack.stickers.isNotEmpty()) NxButton(if (picking) t("chat.editMessage.cancel") else t("server.packEditor.select"), {
            picking = !picking
            chosen.clear()
        }, kind = if (picking) ButtonKind.SECONDARY else ButtonKind.GHOST, size = ButtonSize.SM, icon = if (picking) null else "SquareCheck")
    }
    if (pack.stickers.size >= SEARCH_FROM) Box(Modifier.padding(top = 8.dp)) { fish.nexa.ui.server.SearchBox(q, { q = it }, t("server.packEditor.searchThisPack")) }
    val query = q.trim().lowercase()
    val ordered = pack.stickers.sortedBy { it.position }
    val shown = if (query.isEmpty()) ordered else ordered.filter { it.name.lowercase().contains(query) || it.tags.lowercase().contains(query) }
    if (picking) {
        val barShape = Radii.Md
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .ring(barShape, c.brand.copy(alpha = 0.4f))
                .background(mixOklab(c.bgSurface, c.brand, 0.14f), barShape)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(t("server.packEditor.selectedCount", "count" to chosen.size), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textStrong, modifier = Modifier.weight(1f), maxLines = 1)
            NxButton(t("server.packEditor.selectAll"), {
                chosen.clear()
                chosen.addAll(shown.map { it.id })
            }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
            NxButton(t("chat.messageItem.delete"), {
                val list = chosen.toList()
                fish.nexa.ui.shell.confirm(t("server.packEditor.deleteChosen", "count" to list.size), t("server.packEditor.chosenAreRemovedForEveryone"), t("chat.messageItem.delete")) {
                    try {
                        for (id in list) Nexa.api.request("DELETE", "/stickers/packs/${pack.id}/stickers/$id")
                    } finally {
                        fetchPack(pack.id)
                        withContext(Dispatchers.Main) {
                            picking = false
                            chosen.clear()
                        }
                    }
                }
            }, kind = ButtonKind.DANGER, size = ButtonSize.SM, icon = "Trash2", enabled = chosen.isNotEmpty())
        }
    }
    ItemGrid(
        pack, emoji, shown,
        addLabel = if (!picking && query.isEmpty()) addLabel else null,
        onAdd = addFiles,
        chosen = if (picking) chosen else null,
        onItem = { s ->
            if (picking) {
                if (s.id in chosen) chosen.remove(s.id) else chosen.add(s.id)
            } else editItem(pack, s)
        },
    )
    if (pack.stickers.isEmpty()) Hint(t("server.packEditor.dropFilesHereOrClickTo", "size" to (if (emoji) 2 else 5)), Modifier.padding(top = 8.dp))
    else if (!picking && query.isEmpty()) Hint(t("android.settings.stickers.tapToEdit"), Modifier.padding(top = 8.dp))
    if (query.isNotEmpty() && shown.isEmpty()) Box(Modifier.padding(top = 8.dp)) { EmptyBox(t("pickers.stickerPicker.noStickersMatch")) }

    val panel = Radii.Lg
    Column(
        Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .ring(panel, c.panelEdge)
            .background(c.bgSurface, panel)
            .background(sheen(c), panel)
            .topEdge(panel, c.edgeLight)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(t("server.packEditor.details"), style = Nx.type.name.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong)
        NxTextField(name, { onName(it.take(100)) }, label = t("server.packEditor.packName"), capitalize = true)
        NxTextField(description, { onDescription(it.take(500)) }, label = t("server.packEditor.description"), hint = t("server.packEditor.shownWhenPeopleFindThePack"), singleLine = false, minLines = 2, capitalize = true)
        // one change at a time: pressed again while one is on its way (or waits on a captcha), it waits
        var publicBusy by remember(pack.id) { mutableStateOf(false) }
        ToggleRow(t("server.packEditor.publicPack"), pack.public, { v ->
            if (!publicBusy) {
                publicBusy = true
                scope.launch {
                    try {
                        Nexa.expressions.upsertPack(Nexa.api.request("PATCH", "/stickers/packs/${pack.id}", StickerPack.serializer(), buildJsonObject { put("public", v) }))
                    } catch (e: Exception) {
                        Nexa.ui.toast((e as? ApiError)?.message ?: t("server.packEditor.couldNotChangeVisibility"), "error")
                    } finally {
                        publicBusy = false
                    }
                }
            }
        }, t("server.packEditor.anyoneWithTheLinkCanInstall"), enabled = !publicBusy)
        if (pack.public) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).sunken(Radii.Sm, c).ring(Radii.Sm, c.borderSubtle).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Txt(packUrl(pack.id), style = Nx.type.code.copy(fontSize = Nx.type.sp(13f)), color = c.text, maxLines = 1)
            }
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(2000)
                    copied = false
                }
            }
            NxButton(if (copied) t("modals.groupInvite.copied") else t("server.packEditor.copyLink"), {
                copied = copyText(ctx, packUrl(pack.id))
            }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = if (copied) "Check" else "Copy")
        }
    }
    Box(Modifier.padding(top = 16.dp)) {
        DangerBox(null, t("server.packEditor.everyoneWhoInstalledThisPackLoses"), t("server.packEditor.deletePack"), {
            fish.nexa.ui.shell.confirm(t("server.packEditor.deleteNamed", "name" to pack.name), t("server.packEditor.everyoneWhoInstalledThisPackLoses"), t("server.packEditor.deletePack")) {
                Nexa.api.request("DELETE", "/stickers/packs/${pack.id}")
                Nexa.expressions.removePack(pack.id)
                withContext(Dispatchers.Main) { onDeleted() }
            }
        })
    }
}

/** The way back from a pack to the list. */
@Composable
internal fun BackRowTo(label: String, onBack: () -> Unit) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .heightIn(min = 44.dp)
            .clip(Radii.Sm)
            .pressable(remember { MutableInteractionSource() }, onClick = onBack)
            .semantics { contentDescription = t("server.settingsLayer.backTo", "name" to label) }
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (rtl) "ChevronRight" else "ChevronLeft", c.textMuted, size = 18.dp)
        Txt(label, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.textMuted)
    }
}

/** The pack's stickers or emoji, three a row, with the way to add more first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemGrid(pack: StickerPack, emoji: Boolean, items: List<Sticker>, addLabel: String?, onAdd: () -> Unit, chosen: List<String>?, onItem: (Sticker) -> Unit) {
    val c = Nx.colors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        // whole pixels, so three never round to a hair over the row
        val w = with(LocalDensity.current) { (((maxWidth - 16.dp).toPx() / 3).toInt()).toDp() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            val shape = Radii.Md
            if (addLabel != null) Column(
                Modifier
                    .width(w)
                    .ring(shape, c.borderStrong)
                    .clip(shape)
                    .pressable(remember { MutableInteractionSource() }, onClick = onAdd)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon("Plus", c.textMuted, size = 26.dp)
                        Txt(addLabel, style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                // as tall as the tiles beside it, which have a name under them
                Txt(" ", style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), maxLines = 1)
            }
            for (s in items) {
                val on = chosen?.contains(s.id) == true
                Column(
                    Modifier
                        .width(w)
                        .ring(shape, if (on) c.focusRing else c.borderSubtle, if (on) 2.dp else 1.dp)
                        .background(c.bgSurface, shape)
                        .background(sheen(c), shape)
                        .clip(shape)
                        .pressable(remember(s.id) { MutableInteractionSource() }) { onItem(s) }
                        .semantics { contentDescription = s.name }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        StickerStill(s, emoji, if (emoji) 48.dp else 96.dp, if (emoji) Modifier.size(48.dp) else Modifier.fillMaxSize())
                        if (s.id == pack.coverStickerId) Txt(
                            t("server.packEditor.cover"),
                            style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)),
                            color = c.onBrand,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.TopStart).background(fish.nexa.ui.theme.brandGradient(c), Radii.Pill).padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                        if (chosen != null) Box(
                            Modifier.align(Alignment.TopEnd).size(22.dp).then(if (on) Modifier.background(fish.nexa.ui.theme.brandGradient(c), Radii.Pill) else Modifier.background(c.bgBase.copy(alpha = 0.7f), Radii.Pill).ring(Radii.Pill, c.textMuted, 1.5.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon("Check", c.onBrand, size = 14.dp) }
                    }
                    Txt(s.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(550)), color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** One sticker of a pack you can change: drawn big and moving, its name and tags, and everything else that can be done with it. */
private fun editItem(pack: StickerPack, s: Sticker) {
    val emoji = isEmoji(pack)
    val ordered = pack.stickers.sortedBy { it.position }
    val at = ordered.indexOfFirst { it.id == s.id }
    fun move(by: Int) {
        val list = ordered.toMutableList()
        val to = (at + by).coerceIn(0, list.size - 1)
        if (to == at) return
        list.add(to, list.removeAt(at))
        Nexa.scope.launch {
            try {
                Nexa.api.request("PATCH", "/stickers/packs/${pack.id}/stickers", buildJsonArray {
                    list.forEachIndexed { i, x -> add(buildJsonObject { put("id", x.id); put("position", i) }) }
                })
                fetchPack(pack.id)
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("modals.confirm.somethingWentWrong"), "error")
            }
        }
    }
    val ctl = SheetCtl(t(if (emoji) "server.packEditor.editEmoji" else "server.packEditor.editSticker"))
    var name by mutableStateOf(s.name)
    var tags by mutableStateOf(s.tags)
    fun save() {
        ctl.close()
        Nexa.scope.launch {
            try {
                Nexa.api.request("PATCH", "/stickers/packs/${pack.id}/stickers/${s.id}", buildJsonObject {
                    put("name", name.trim().take(30).ifEmpty { s.name })
                    put("tags", tags.trim().take(200))
                })
                fetchPack(pack.id)
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("server.packEditor.couldNotRename"), "error")
            }
        }
    }
    openSettingsSheet("sticker-edit", ctl, footer = {
        NxButton(t("chat.editMessage.cancel"), { it.close() }, kind = ButtonKind.GHOST)
        NxButton(t("chat.editMessage.save"), { save() }, enabled = name != s.name || tags != s.tags)
    }) {
        val c = Nx.colors
        val density = LocalDensity.current.density
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(176.dp).sunken(Radii.Md, c).ring(Radii.Md, c.borderSubtle), contentAlignment = Alignment.Center) {
                fish.nexa.ui.chat.StickerMedia(s.format, if (emoji) Media.emojiUrl(s.blobId, 96, density) else Media.stickerUrl(s.blobId, 160, density), s.name, if (emoji) 64.dp else 160.dp)
            }
        }
        NxTextField(name, { name = it.take(30) }, label = t("modals.subchat.name"))
        NxTextField(tags, { tags = it.take(200) }, label = t("server.packEditor.tags"), hint = t("server.packEditor.spaceSeparatedUsedBySearch"), imeAction = ImeAction.Done, onIme = { save() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NxButton(t("server.packEditor.setAsCover"), {
                ctl.close()
                Nexa.scope.launch {
                    try {
                        Nexa.expressions.upsertPack(Nexa.api.request("PATCH", "/stickers/packs/${pack.id}", StickerPack.serializer(), buildJsonObject { put("cover_sticker_id", s.id) }))
                    } catch (e: Exception) {
                        Nexa.ui.toast((e as? ApiError)?.message ?: t("server.packEditor.couldNotSetTheCover"), "error")
                    }
                }
            }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Star", enabled = pack.coverStickerId != s.id, modifier = Modifier.weight(1f), block = true)
            NxButton(t("server.packEditor.deleteSticker"), {
                ctl.close()
                fish.nexa.ui.shell.confirm(t("server.packEditor.deleteNamed", "name" to s.name), t("server.packEditor.thisStickerIsRemovedFromThe"), t("server.packEditor.deleteSticker")) {
                    Nexa.api.request("DELETE", "/stickers/packs/${pack.id}/stickers/${s.id}")
                    fetchPack(pack.id)
                }
            }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Trash2", modifier = Modifier.weight(1f), block = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NxButton(t("reorder.moveEarlier"), { ctl.close(); move(-1) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, enabled = at > 0, modifier = Modifier.weight(1f), block = true)
            NxButton(t("reorder.moveLater"), { ctl.close(); move(1) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, enabled = at < ordered.size - 1, modifier = Modifier.weight(1f), block = true)
        }
    }
}

/** Uploads one picked file to a pack, named from its file name as the web names it; false when it was not a kind the pack takes. */
private suspend fun uploadItem(ctx: android.content.Context, pack: StickerPack, uri: Uri): Boolean {
    val emoji = isEmoji(pack)
    val cr = ctx.contentResolver
    val fileName = runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "sticker"
    val size = runCatching { cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } }.getOrNull() ?: -1L
    var mime = cr.getType(uri) ?: ""
    // a .tgs file usually comes with no useful type, so it is known by its name; video stickers are WebM
    val tgs = fileName.lowercase().endsWith(".tgs")
    if (tgs) mime = "application/x-tgsticker"
    val ok = mime in ACCEPT_IMAGE || (!emoji && (mime == "video/webm" || tgs))
    if (!ok) {
        Nexa.ui.toast(t(if (emoji) "server.packEditor.mustBeAnImage" else "server.packEditor.stickersMustBeImages", "name" to fileName), "error")
        return false
    }
    val max = if (emoji) MAX_EMOJI else MAX_STICKER
    if (size > max) {
        Nexa.ui.toast(t("server.packEditor.isLargerThan", "name" to fileName, "megabytes" to (max / (1024 * 1024)).toInt()), "error")
        return false
    }
    val base = fileName.replace(Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE), "")
    val clean = if (emoji) base.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').take(32) else base.take(30)
    val name = clean.ifEmpty { if (emoji) "emoji" else "sticker" }
    val bytes = withContext(Dispatchers.IO) { cr.openInputStream(uri)?.use { it.readBytes() } } ?: return false
    if (bytes.size > max) {
        Nexa.ui.toast(t("server.packEditor.isLargerThan", "name" to fileName, "megabytes" to (max / (1024 * 1024)).toInt()), "error")
        return false
    }
    val path = "/stickers/packs/${pack.id}/stickers?name=${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}&tags="
    val req = Nexa.api.requestBuilder(path).post(bytes.toRequestBody((mime.ifEmpty { "application/octet-stream" }).toMediaType())).build()
    Nexa.api.execute(Nexa.api.transfer, req, true)
    return true
}

/** The bar that saves a pack's name and description, over the bottom of the page while they differ. */
@Composable
fun BoxScope.PackSaveBar() {
    val form = PackEditing.form
    SaveBar(
        dirty = form?.dirty == true,
        saving = form?.saving == true,
        onReset = {
            form?.let {
                it.name = it.savedName
                it.description = it.savedDescription
            }
        },
        onSave = {
            val f = form ?: return@SaveBar
            f.saving = true
            Nexa.scope.launch(Dispatchers.Main) {
                try {
                    val saved = Nexa.api.request("PATCH", "/stickers/packs/${f.packId}", StickerPack.serializer(), buildJsonObject {
                        if (f.name != f.savedName) put("name", f.name)
                        if (f.description != f.savedDescription) put("description", f.description)
                    })
                    Nexa.expressions.upsertPack(saved)
                    // the server tidies spaces: what it kept is what shows, or the bar would never go
                    f.name = saved.name
                    f.description = saved.description
                    f.savedName = saved.name
                    f.savedDescription = saved.description
                    Nexa.ui.toast(t("server.packEditor.packSaved"), "success")
                } catch (e: Exception) {
                    Nexa.ui.toast((e as? ApiError)?.message ?: t("server.packEditor.couldNotSaveThePack"), "error")
                } finally {
                    f.saving = false
                }
            }
        },
    )
}

// ---- making a pack, and Telegram's ----

private fun openCreatePack(kind: String, onCreated: (String) -> Unit) {
    val emoji = kind == "emoji"
    val ctl = SheetCtl(t(if (emoji) "settings.stickers.createEmojiPack" else "settings.stickers.createStickerPack"))
    var name by mutableStateOf(t(if (emoji) "settings.stickers.myEmoji" else "settings.stickers.myStickers"))
    var busy by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var link by mutableStateOf("")
    var progress by mutableStateOf<String?>(null)
    var fraction by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var job: kotlinx.coroutines.Job? = null
    fun create() {
        if (busy || name.isBlank()) return
        busy = true
        Nexa.scope.launch(Dispatchers.Main) {
            try {
                val p = Nexa.api.request("POST", "/stickers/packs", StickerPack.serializer(), buildJsonObject {
                    put("name", name.trim())
                    put("kind", kind)
                })
                Nexa.expressions.upsertPack(p)
                ctl.close()
                onCreated(p.id)
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("server.serverStickers.couldNotCreateThePack"), "error")
            } finally {
                busy = false
            }
        }
    }
    fun import() {
        if (busy || link.isBlank()) return
        busy = true
        error = null
        progress = t("settings.stickers.importing")
        fraction = 0f
        job = Nexa.scope.launch(Dispatchers.Main) {
            try {
                val done = importTelegram(link.trim()) { phase, d, total ->
                    progress = t(if (phase == "store") "settings.stickers.savingStickers" else "settings.stickers.fetchingStickers", "done" to d, "total" to total)
                    fraction = if (total > 0) d.toFloat() / total else 0f
                }
                val pack = wireJson.decodeFromJsonElement(StickerPack.serializer(), done["pack"]!!)
                Nexa.expressions.upsertPack(pack)
                fun n(k: String) = (done[k] as? JsonPrimitive)?.intOrNull ?: 0
                var text = t("settings.stickers.importedToast", "imported" to n("imported"))
                if (n("skipped") > 0) text += " " + t("settings.stickers.importedSkipped", "skipped" to n("skipped"))
                if (n("failed") > 0) text += " " + t("settings.stickers.importedFailed", "failed" to n("failed"))
                Nexa.ui.toast(text, "success")
                ctl.close()
                onCreated(pack.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? ApiError)?.message ?: t("settings.stickers.importFailed")
            } finally {
                busy = false
                progress = null
            }
        }
    }
    openSettingsSheet("pack-create", ctl, footer = { sheet ->
        if (importing) NxButton(t("settings.stickers.back"), {
            job?.cancel()
            importing = false
            ctl.title = t("settings.stickers.createStickerPack")
        }, kind = ButtonKind.GHOST, enabled = !busy)
        NxButton(t("chat.editMessage.cancel"), {
            job?.cancel()
            sheet.close()
        }, kind = ButtonKind.GHOST)
        if (importing) NxButton(t("settings.stickers.importButton"), { import() }, loading = busy, enabled = link.isNotBlank())
        else NxButton(t("settings.stickers.create"), { create() }, loading = busy, enabled = name.isNotBlank())
    }) {
        val c = Nx.colors
        if (!importing) {
            NxTextField(name, { name = it.take(60) }, label = t("server.packEditor.packName"), imeAction = ImeAction.Done, onIme = { create() }, capitalize = true)
            if (!emoji) NxButton(t("settings.stickers.importTelegram"), {
                importing = true
                ctl.title = t("settings.stickers.importTitle")
            }, kind = ButtonKind.LINK)
        } else {
            Hint(t("settings.stickers.importHint"))
            NxTextField(link, { link = it }, label = t("settings.stickers.importLinkLabel"), placeholder = t("settings.stickers.importPlaceholder"), keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = ImeAction.Go, onIme = { import() })
            progress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().size(6.dp).sunken(Radii.Pill, c)) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).size(6.dp).background(fish.nexa.ui.theme.brandGradient(c), Radii.Pill))
                    }
                    Txt(p, style = Nx.type.hint, color = c.textMuted)
                }
            }
            error?.let { ErrorBox(it) }
        }
    }
}

/** POST …/import/telegram, read as it streams (NDJSON): progress lines, then the pack, or an error. */
private suspend fun importTelegram(url: String, onProgress: (phase: String, done: Int, total: Int) -> Unit): JsonObject = withContext(Dispatchers.IO) {
    val body = buildJsonObject { put("url", url) }.toString().toRequestBody("application/json".toMediaType())
    val req = Nexa.api.requestBuilder("/stickers/packs/import/telegram").header("Accept", "application/x-ndjson").post(body).build()
    val client = Nexa.api.transfer.newBuilder().callTimeout(150, java.util.concurrent.TimeUnit.SECONDS).build()
    Nexa.api.await(client.newCall(req)).use { res ->
        if (!res.isSuccessful) throw Nexa.api.errorOf(res.code, res.body.string(), res.header("Retry-After"), true)
        val reader = res.body.byteStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val o = runCatching { wireJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            (o["error"] as? JsonObject)?.let { err ->
                throw ApiError(400, (err["code"] as? JsonPrimitive)?.contentOrNull ?: "import_failed", (err["message"] as? JsonPrimitive)?.contentOrNull ?: t("settings.stickers.importFailed"))
            }
            if ((o["done"] as? JsonPrimitive)?.contentOrNull == "true") return@withContext o
            val phase = (o["phase"] as? JsonPrimitive)?.contentOrNull ?: continue
            val d = (o["done"] as? JsonPrimitive)?.intOrNull ?: 0
            val total = (o["total"] as? JsonPrimitive)?.intOrNull ?: 0
            withContext(Dispatchers.Main) { onProgress(phase, d, total) }
        }
        throw ApiError(0, "import_failed", t("settings.stickers.importFailed"))
    }
}

