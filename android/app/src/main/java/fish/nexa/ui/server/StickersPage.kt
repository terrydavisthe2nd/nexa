package fish.nexa.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.media.Media
import fish.nexa.net.ApiError
import fish.nexa.net.Sticker
import fish.nexa.net.StickerPack
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.ToggleLine
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.markdown.AppLinks
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.settings.BackRowTo
import fish.nexa.ui.settings.PackBody
import fish.nexa.ui.settings.PackGroup
import fish.nexa.ui.stickers.openPackSheet
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.brandGradient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val IMAGE_TYPES = arrayOf("image/png", "image/gif", "image/webp", "image/apng")
private const val MAX_STICKER = 5 * 1024 * 1024

/** A sticker's picture: moving pictures move; a TGS or WebM shows its first frame. */
@Composable
fun StickerImage(pack: StickerPack, s: Sticker, css: Int, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    val url = if (pack.kind == "emoji") Media.emojiUrl(s.blobId, 48, density)
    else Media.stickerUrl(s.blobId, css, density) + if (s.format == "tgs" || s.format == "webm") "&still=1" else ""
    AsyncImage(url, s.name, Media.loader, modifier, contentScale = ContentScale.Fit)
}

/** Server Settings, Stickers: the packs that belong to this server, and a page for each to whoever may manage them. */
@Composable
fun ServerStickersPage(serverId: String) {
    val servers by Nexa.servers.byId.collectAsState()
    val packs by Nexa.expressions.packs.collectAsState()
    val st = servers[serverId]
    val manage = Nexa.servers.canI(st, P.MANAGE_EMOJIS)
    val list = packs.values.filter { it.serverId == serverId }.sortedByDescending { it.updatedAt }
    var selected by remember { mutableStateOf<String?>(null) }
    val pack = list.firstOrNull { it.id == selected }

    fun create() = promptText(
        t("server.serverStickers.createServerPack"),
        PromptField("name", t("server.packEditor.packName"), t("server.serverStickers.defaultPackName", "name" to (st?.server?.name ?: t("settings.settingsScreen.server"))), maxLength = 60, capitalize = true),
        t("settings.stickers.create"),
    ) { name ->
        if (name.isBlank()) return@promptText
        Nexa.scope.launch {
            try {
                val p = Nexa.api.request("POST", "/stickers/packs", StickerPack.serializer(), buildJsonObject {
                    put("name", name.trim())
                    put("server_id", serverId)
                })
                Nexa.expressions.upsertPack(p)
                selected = p.id
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("server.serverStickers.couldNotCreateThePack"), "error")
            }
        }
    }

    if (pack != null && manage) key(pack.id) { ServerPack(pack) { selected = null } }
    else SettingsPage {
        PageTitle(t("pickers.expressionPicker.stickers"), Modifier.padding(bottom = 0.dp))
        Hint(t("server.serverStickers.stickerPacksThatBelongToThis"), Modifier.padding(bottom = 16.dp))
        if (manage) NxButton(t("server.serverStickers.createServerPack"), { Holds.unlessUnsaved { create() } }, size = ButtonSize.SM, icon = "Plus", block = true, modifier = Modifier.padding(bottom = 16.dp))
        if (list.isEmpty()) EmptyNote(if (manage) t("server.serverStickers.thisServerHasNoStickerPacks") else t("server.serverStickers.thisServerHasNoStickerPacks2"))
        PackGroup(null, list, manage = manage, removable = { false }, note = { null }) { id ->
            if (manage) selected = id else list.firstOrNull { it.id == id }?.let { openPackSheet(id, it) }
        }
    }
}

/** One of the server's packs: the page a pack has in User Settings, with the save bar this frame keeps. */
@Composable
private fun ServerPack(pack: StickerPack, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(pack.name) }
    var description by remember { mutableStateOf(pack.description) }
    var busy by remember { mutableStateOf(false) }
    var installs by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pack.id) {
        runCatching {
            val fresh = Nexa.api.get("/stickers/packs/${pack.id}", StickerPack.serializer())
            Nexa.expressions.upsertPack(fresh)
            installs = fresh.installCount
        }
    }
    val dirty = name != pack.name || description != pack.description
    fun save() {
        busy = true
        scope.launch {
            try {
                val saved = Nexa.api.request("PATCH", "/stickers/packs/${pack.id}", StickerPack.serializer(), buildJsonObject {
                    if (name != pack.name) put("name", name)
                    if (description != pack.description) put("description", description)
                })
                Nexa.expressions.upsertPack(saved)
                // the server tidies whitespace: show what it kept, or the save bar never goes away
                name = saved.name
                description = saved.description
                Nexa.ui.toast(t("server.packEditor.packSaved"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast((e as? ApiError)?.message ?: t("server.packEditor.couldNotSaveThePack"), "error")
            } finally {
                busy = false
            }
        }
    }
    SettingsPage(SaveState(dirty, busy, { name = pack.name; description = pack.description }, ::save)) {
        BackRowTo(t("pickers.expressionPicker.stickers")) { Holds.unlessUnsaved(onBack) }
        PackBody(pack, installs, name, { name = it }, description, { description = it }, onBack)
    }
}
