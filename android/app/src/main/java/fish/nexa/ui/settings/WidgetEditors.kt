package fish.nexa.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.media.Media
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.profile.GameCover
import fish.nexa.ui.profile.MusicArt
import fish.nexa.ui.profile.ProfileWidgets
import fish.nexa.ui.profile.Widgets
import fish.nexa.ui.server.CropResult
import fish.nexa.ui.server.ServerApi
import fish.nexa.ui.server.openImageCrop
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.random.Random

/** lib/widgets.ts: how much each kind holds, and how long its words may be. */
private object Limits {
    const val NOTE = 200
    const val TAGS = 3
    const val TAG = 20
    const val NAME = 100
    const val TITLE = 40
    const val TEXT = 1000
    const val STATS = 6
    const val LABEL = 24
    const val VALUE = 40
    const val LINK_LABEL = 32
    const val CARDS = 4
    val GAMES = mapOf("favorite" to 1, "playing" to 5, "wishlist" to 20, "played" to 20)
    val MUSIC = mapOf("artists" to 12, "albums" to 12, "songs" to 10)
}

private val KIND_ICONS = mapOf(
    "favorite" to "Heart", "playing" to "Gamepad2", "wishlist" to "Bookmark", "played" to "Library",
    "artists" to "MicVocal", "albums" to "Disc3", "songs" to "Music", "custom" to "PanelTop",
)

/** The kinds of widget, in the groups Add a Widget lists them in. */
private val KIND_GROUPS = listOf(
    "widgets.group.games" to listOf("favorite", "playing", "wishlist", "played"),
    "widgets.group.music" to listOf("artists", "albums", "songs"),
    "widgets.group.cards" to listOf("custom"),
)

private val TAG_SUGGESTIONS = listOf("casual", "competitive", "coop", "solo", "ranked", "withFriends")

private fun newWidgetId(): String = "w" + (1..8).map { "0123456789abcdefghijklmnopqrstuvwxyz"[Random.nextInt(36)] }.joinToString("")

private fun fresh(type: String): Widgets.Widget = when (type) {
    "custom" -> Widgets.CardWidget(newWidgetId(), "", null, null, null, emptyList(), emptyList())
    "artists", "albums", "songs" -> Widgets.MusicWidget(newWidgetId(), type, emptyList())
    else -> Widgets.GameWidget(newWidgetId(), type, emptyList(), null)
}

/** A label over a part of an editor (`.label`). */
@Composable
private fun EditLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text, style = Nx.type.label.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = Nx.colors.textSubtle, modifier = modifier.padding(bottom = 8.dp))
}

/** A small rounded button inside an editor (`.chip`). */
@Composable
private fun EditChip(text: String, icon: String? = null, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .height(32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .background(if (pressed) mixOklab(c.bgActive, c.text, 0.12f) else c.bgActive, shape)
            .clip(shape)
            .pressable(interaction, enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (busy) Spinner(c.textMuted, size = 14.dp) else if (icon != null) Icon(icon, c.textStrong, size = 14.dp)
        Txt(text, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1)
    }
}

/** Cancel and Done along an editor's foot. */
@Composable
private fun EditorFooter(close: () -> Unit, enabled: Boolean, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        NxButton(t("chat.editMessage.cancel"), close, kind = ButtonKind.GHOST)
        NxButton(t("pick.done"), onDone, enabled = enabled)
    }
}

/** Puts up a picture for a widget, sent whole with the part framed; the server cuts it in [shape]. */
private suspend fun uploadWidgetImage(r: CropResult, shape: String): String {
    val q = StringBuilder("?shape=$shape")
    r.crop?.let { q.append("&x=${it.x}&y=${it.y}&w=${it.w}&h=${it.h}") }
    val res = ServerApi.upload("POST", "/users/@me/widgets/image$q", r.bytes, r.type) as JsonObject
    return (res["id"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalStateException()
}

/** Add a Widget: every kind, in groups, each with what it shows, and why one cannot be added. */
fun openAddWidget(widgets: List<Widgets.Widget>, onPick: (Widgets.Widget) -> Unit) {
    openSheet("widget-add", title = t("widgets.addOne")) { close ->
        val c = Nx.colors
        val cards = widgets.count { it.type == "custom" }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for ((title, types) in KIND_GROUPS) Column {
                Txt(t(title), style = Nx.type.label.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.textSubtle, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (type in types) {
                        val taken = if (type == "custom") cards >= Limits.CARDS else widgets.any { it.type == type }
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = if (taken) 0.55f else 1f }
                                .background(c.bgSurface, Radii.Md)
                                .background(sheen(c), Radii.Md)
                                .ring(Radii.Md, if (pressed) c.brand.copy(alpha = 0.55f) else c.borderSubtle)
                                .topEdge(Radii.Md, c.edgeLight)
                                .clip(Radii.Md)
                                .pressable(interaction, enabled = !taken) {
                                    close()
                                    onPick(fresh(type))
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(Modifier.size(40.dp).background(mixOklab(c.bgActive, c.brand, 0.16f), RoundedCornerShape(12.dp)).topEdge(RoundedCornerShape(12.dp), c.edgeLight), contentAlignment = Alignment.Center) {
                                Icon(KIND_ICONS[type] ?: "PanelTop", c.textBrand, size = 20.dp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Txt(Widgets.title(type), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(650)), color = c.textStrong)
                                Txt(
                                    if (taken) (if (type == "custom") t("widgets.cardsFull") else t("widgets.onProfile")) else t("widgets.about.$type"),
                                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(18.2f)),
                                    color = c.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Opens the editor for one widget; [onDone] gets it as it should be shown. */
fun openWidgetEditor(w: Widgets.Widget, isNew: Boolean, onDone: (Widgets.Widget) -> Unit) {
    when (w) {
        is Widgets.GameWidget -> openGameEditor(w, onDone)
        is Widgets.MusicWidget -> openMusicEditor(w, onDone)
        is Widgets.CardWidget -> openCardEditor(w, isNew, onDone)
    }
}

/** The search box at the top of a games or music editor, with the magnifier inside it. */
@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, label: String, onEnter: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Sm
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(c.bgInput, shape)
            .ring(shape, c.borderSubtle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon("Search", c.textMuted, size = 17.dp)
        val style = Nx.type.input.copy(fontSize = Nx.type.sp(15f), color = c.text)
        BasicTextField(
            value,
            { onChange(it.take(Limits.NAME)) },
            Modifier.weight(1f).semantics { contentDescription = label },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(c.focusRing),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onEnter() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Txt(placeholder, style = style, color = c.textPlaceholder, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** What was found, under the search box: a sunken list of rows, each added or taken off with a tap. */
@Composable
private fun ResultsBox(content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val scroll = rememberScrollState()
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .heightIn(max = 264.dp)
            .background(mixOklab(c.bgInput, c.bgFloating, 0.3f), Radii.Md)
            .ring(Radii.Md, c.borderSubtle)
            .clip(Radii.Md)
            .verticalScroll(scroll)
            .padding(4.dp),
        content = content,
    )
}

/** One found game, song, album or artist: tap to add it, tap again to take it off. */
@Composable
private fun ResultRow(on: Boolean, enabled: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit, name: String, about: String?, trailing: String?) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled || on) 1f else 0.6f }
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) c.bgHover else Color.Transparent)
            .pressable(interaction, enabled = enabled || on, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        picture()
        Column(Modifier.weight(1f)) {
            Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
            if (!about.isNullOrEmpty()) Txt(about, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), lineHeight = Nx.type.sp(16f)), color = c.textMuted, maxLines = 1)
        }
        if (trailing != null) Txt(trailing, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontFeatureSettings = "tnum"), color = c.textMuted)
        Box(Modifier.size(28.dp).background(if (on) c.brand else c.bgActive, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (on) "Check" else "Plus", if (on) Color.White else c.textStrong, size = 15.dp)
        }
    }
}

/** A line inside the results (searching, nothing found, search not answering). */
@Composable
private fun ResultStatus(text: String) {
    Txt(text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f)), color = Nx.colors.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp))
}

/** "Add “name” by Name": what the search did not find exactly can go in as it is. */
@Composable
private fun ByNameRow(name: String, enabled: Boolean, square: Boolean, round: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = if (round) CircleShape else RoundedCornerShape(4.dp)
    ResultRowPlain(enabled, onClick) {
        Box(
            Modifier.width(if (square) 36.dp else 30.dp).aspectRatio(if (square) 1f else 3f / 4f).border(1.5.dp, c.border, shape),
            contentAlignment = Alignment.Center,
        ) { Icon("Plus", c.textMuted, size = 14.dp) }
        Txt(t("widgets.game.addByName", "name" to name), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ResultRowPlain(enabled: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) c.bgHover else Color.Transparent)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The heading over what has been chosen, with how many of how many. */
@Composable
private fun ChosenHeading(label: String, count: String?) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Txt(label, style = Nx.type.label.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.textSubtle)
        if (count != null) Txt(count, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontFeatureSettings = "tnum"), color = c.textMuted)
    }
}

/** Nothing chosen yet: a dashed box saying how to choose. */
@Composable
private fun ChosenEmpty(text: String) {
    val c = Nx.colors
    Txt(
        text, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().border(1.5.dp, c.border, Radii.Md).padding(horizontal = 12.dp, vertical = 16.dp),
    )
}

/** A row of what has been chosen; pressed and held, it moves earlier or later. */
@Composable
private fun ChosenRow(single: Boolean, index: Int, count: Int, onMove: (Int) -> Unit, removeLabel: String, onRemove: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Nx.colors
    val canMove = !single && count > 1
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.bgSurface, Radii.Md)
            .background(sheen(c), Radii.Md)
            .ring(Radii.Md, c.borderSubtle)
            .topEdge(Radii.Md, c.edgeLight)
            .clip(Radii.Md)
            .then(
                if (canMove) Modifier.pressable(remember { MutableInteractionSource() }, onLongClick = {
                    openMenu(buildList {
                        if (index > 0) add(MenuItem.Action(t("reorder.moveEarlier")) { onMove(-1) })
                        if (index < count - 1) add(MenuItem.Action(t("reorder.moveLater")) { onMove(1) })
                    })
                }) {} else Modifier,
            )
            .padding(if (single) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (single) 14.dp else 10.dp),
    ) {
        content()
        IconButton("X", removeLabel, onRemove, tint = c.textMuted, size = 36.dp, iconSize = 16.dp)
    }
}

private fun <T> moved(list: List<T>, at: Int, delta: Int): List<T> {
    val to = at + delta
    if (at !in list.indices || to !in list.indices) return list
    return list.toMutableList().apply { add(to, removeAt(at)) }
}

private fun gameKey(g: Widgets.Game) = g.id?.let { "g:$it" } ?: "n:${g.name.lowercase()}"
private fun musicKey(m: Widgets.Music) = m.id?.let { "d:$it" } ?: "n:${m.name.lowercase()}\u0000${(m.by ?: "").lowercase()}"

private class FoundGame(val id: String, val name: String, val cover: String?, val year: Int?)

/**
 * A games widget: a search of IGDB's games, through this server, that adds
 * what it finds, or a game typed in by name with a picture of its own; and the
 * games chosen, in order, with how each is played (Playing Now) or why it is
 * the one (Favourite Game).
 */
private fun openGameEditor(initial: Widgets.GameWidget, onDone: (Widgets.Widget) -> Unit) {
    val most = Limits.GAMES[initial.type] ?: 1
    val single = most == 1
    var games by mutableStateOf(initial.games)
    var note by mutableStateOf(initial.note ?: "")
    var uploading by mutableStateOf<String?>(null)
    openSheet(
        "widget-games", title = Widgets.title(initial.type),
        footer = { close ->
            EditorFooter(close, enabled = games.isNotEmpty() && uploading == null) {
                close()
                onDone(initial.copy(games = games, note = if (single) note.trim().ifEmpty { null } else null))
            }
        },
    ) {
        val c = Nx.colors
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var q by remember { mutableStateOf("") }
        var found by remember { mutableStateOf<Pair<String, List<FoundGame>>?>(null) }
        var searching by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        // whether this server can search IGDB at all, asked once as the editor opens
        var ready by remember { mutableStateOf<Boolean?>(null) }
        var pictureFor by remember { mutableStateOf<String?>(null) }
        var tagFocus by remember { mutableStateOf<String?>(null) }
        val query = q.trim()
        suspend fun search(text: String): Pair<Boolean, List<FoundGame>> {
            val res = Nexa.api.get("/games/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")) as JsonObject
            val list = (res["games"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { o ->
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                FoundGame(id, name, (o["cover"] as? JsonPrimitive)?.contentOrNull, (o["year"] as? JsonPrimitive)?.intOrNull)
            } ?: emptyList()
            return ((res["ready"] as? JsonPrimitive)?.contentOrNull != "false") to list
        }
        LaunchedEffect(Unit) { ready = runCatching { search("").first }.getOrDefault(true) }
        LaunchedEffect(query, ready) {
            failed = false
            if (query.isEmpty() || ready == false) {
                found = null
                searching = false
                return@LaunchedEffect
            }
            searching = true
            delay(280)
            try {
                val (r, list) = search(query)
                ready = r
                found = query to list
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
            searching = false
        }
        fun has(g: Widgets.Game) = games.any { gameKey(it) == gameKey(g) }
        val isFull = !single && games.size >= most
        fun add(g: Widgets.Game) {
            games = when {
                single -> listOf(g)
                has(g) -> games.filter { gameKey(it) != gameKey(g) }
                isFull -> games
                else -> games + g
            }
        }
        fun addByName() {
            val name = query.take(Limits.NAME)
            if (name.isEmpty()) return
            add(Widgets.Game(null, name, null, null, emptyList()))
            q = ""
        }
        fun patch(key: String, change: (Widgets.Game) -> Widgets.Game) {
            games = games.map { if (gameKey(it) == key) change(it) else it }
        }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val key = pictureFor
            if (uri == null || key == null) return@rememberLauncherForActivityResult
            scope.launch {
                val f = runCatching { ServerApi.read(context, uri) }.getOrNull() ?: return@launch
                openImageCrop(f, 3f / 4f, round = false, title = t("widgets.game.cropTitle"), maxSize = 400, serverCrop = true) { r ->
                    uploading = key
                    scope.launch {
                        try {
                            val id = uploadWidgetImage(r, "cover")
                            patch(key) { it.copy(image = id) }
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("composer.composer.uploadFailed")), "error")
                        } finally {
                            uploading = null
                        }
                    }
                }
            }
        }
        val results = found?.takeIf { it.first == query }?.second ?: emptyList()
        val exact = results.any { it.name.equals(query, ignoreCase = true) }
        val byName = query.isNotEmpty() && !exact && games.none { it.id == null && it.name.equals(query, ignoreCase = true) }

        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            EditLabel(if (single) t("widgets.game.searchOne") else t("widgets.game.searchMany"))
            SearchBox(q, { q = it }, if (ready == false) t("widgets.game.namePlaceholder") else t("widgets.game.searchPlaceholder"), if (single) t("widgets.game.searchOne") else t("widgets.game.searchMany")) {
                val first = results.firstOrNull()
                if (first != null && !has(Widgets.Game(first.id, first.name, first.cover, null, emptyList()))) add(Widgets.Game(first.id, first.name, first.cover, null, emptyList()))
                else if (first == null && byName && ready == false) addByName()
            }
            if (ready == false) Txt(t("widgets.game.notSetUp"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
            if (query.isNotEmpty()) ResultsBox {
                for (g in results) {
                    val game = Widgets.Game(g.id, g.name, g.cover, null, emptyList())
                    val on = has(game)
                    ResultRow(on, !isFull, { add(game) }, { GameCover(game, 30, 30.dp, RoundedCornerShape(4.dp)) }, g.name, null, g.year?.toString())
                }
                if (searching && results.isEmpty()) ResultStatus(t("widgets.game.searching"))
                if (!searching && failed) ResultStatus(t("widgets.game.unavailable"))
                if (!searching && !failed && ready != false && found?.first == query && results.isEmpty()) ResultStatus(t("widgets.game.noResults"))
                if (byName && !searching) ByNameRow(query, !isFull, square = false, round = false) { addByName() }
            }
            if (ready == true) Txt(t("widgets.game.credit", "site" to "IGDB.com"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, modifier = Modifier.padding(start = 2.dp, top = 6.dp))

            ChosenHeading(if (single) t("widgets.game.yourFavorite") else t("widgets.game.yours"), if (single) null else t("widgets.used", "count" to games.size, "max" to most))
            if (games.isEmpty()) ChosenEmpty(if (single) t("widgets.game.noneYetOne") else t("widgets.game.noneYet"))
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                games.forEachIndexed { i, g ->
                    val key = gameKey(g)
                    ChosenRow(single, i, games.size, { d -> games = moved(games, i, d) }, t("widgets.game.remove", "name" to g.name), { games = games.filter { gameKey(it) != key } }) {
                        GameCover(g, if (single) 56 else 36, if (single) 56.dp else 36.dp, RoundedCornerShape(if (single) 7.dp else 5.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt(
                                g.name,
                                style = if (single) Nx.type.name.copy(fontSize = Nx.type.sp(17f), lineHeight = Nx.type.sp(20.4f), fontWeight = FontWeight(650)) else Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(18f), fontWeight = FontWeight(650)),
                                color = c.textStrong,
                            )
                            if (g.cover == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                EditChip(if (g.image != null) t("widgets.changePicture") else t("widgets.addPicture"), "ImagePlus", busy = uploading == key, enabled = uploading == null) {
                                    pictureFor = key
                                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                if (g.image != null) EditChip(t("widgets.removePicture")) { patch(key) { it.copy(image = null) } }
                            }
                            if (initial.type == "playing") TagEditor(g.tags, { tags -> patch(key) { it.copy(tags = tags) } }, g.name, tagFocus == key) { on -> tagFocus = if (on) key else null }
                        }
                    }
                }
            }
            if (!single && games.size > 1) Txt(t("android.server.reorderGridHint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
            if (single) Column(Modifier.padding(top = 20.dp)) {
                NxTextField(
                    note, { note = it.take(Limits.NOTE) },
                    label = t("widgets.game.noteLabel"),
                    placeholder = t("widgets.game.notePlaceholder"),
                    hint = t("composer.limit.left", "count" to Limits.NOTE - note.length),
                    singleLine = false, minLines = 3, capitalize = true, imeAction = ImeAction.Default,
                )
            }
        }
    }
}

/** How someone plays a game they are playing now: up to three short tags, with a few to start from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(tags: List<String>, onChange: (List<String>) -> Unit, game: String, suggest: Boolean, onFocus: (Boolean) -> Unit) {
    val c = Nx.colors
    var draft by remember { mutableStateOf("") }
    fun add(raw: String) {
        val tag = raw.trim().replace(Regex("\\s+"), " ").take(Limits.TAG)
        draft = ""
        if (tag.isEmpty() || tags.size >= Limits.TAGS || tags.any { it.equals(tag, ignoreCase = true) }) return
        onChange(tags + tag)
    }
    val room = tags.size < Limits.TAGS
    val ideas = TAG_SUGGESTIONS.map { t("widgets.tag.$it") }.filter { idea -> tags.none { it.equals(idea, ignoreCase = true) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (tag in tags) Row(
            Modifier.height(28.dp).background(c.bgActive, RoundedCornerShape(999.dp)).padding(start = 9.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Txt(tag, style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600)), color = c.textSubtle, maxLines = 1)
            IconButton("X", t("widgets.game.removeTag", "tag" to tag), { onChange(tags.filter { it != tag }) }, tint = c.textMuted, size = 24.dp, iconSize = 12.dp)
        }
        if (room) {
            val style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), color = c.text)
            BasicTextField(
                draft,
                { v ->
                    // a comma ends a tag, as Enter does
                    if (v.endsWith(",")) add(v.dropLast(1)) else draft = v.replace(",", "").take(Limits.TAG)
                },
                Modifier
                    .height(28.dp)
                    .width(140.dp)
                    .border(1.dp, if (suggest) c.focusRing.copy(alpha = 0.75f) else c.border, RoundedCornerShape(999.dp))
                    .onFocusChanged {
                        if (!it.isFocused && draft.isNotBlank()) add(draft)
                        onFocus(it.isFocused)
                    }
                    .padding(horizontal = 9.dp)
                    .semantics { contentDescription = t("widgets.game.tagLabel", "name" to game) },
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(c.focusRing),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(draft) }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Txt(t("widgets.game.tagPlaceholder"), style = style, color = c.textPlaceholder, maxLines = 1)
                        inner()
                    }
                },
            )
        }
    }
    if (suggest && room && ideas.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (idea in ideas) Box(
            Modifier
                .height(28.dp)
                .ring(RoundedCornerShape(999.dp), c.borderSubtle)
                .clip(RoundedCornerShape(999.dp))
                .pressable(remember { MutableInteractionSource() }) { add(idea) }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(idea, style = Nx.type.label.copy(fontSize = Nx.type.sp(12f), fontWeight = FontWeight(600)), color = c.textMuted, maxLines = 1) }
    }
}

private class FoundMusic(val id: String, val name: String, val by: String?, val art: String?, val duration: Int?, val album: String?, val record: String?, val fans: Long?, val via: Pair<String, String>?)

/** What tells one found apart from others of its name: whose it is, what it is, how it was found. */
private fun foundAbout(type: String, r: FoundMusic): String {
    if (type == "artists") {
        r.via?.let { (kind, name) -> return t(if (kind == "song") "widgets.music.viaSong" else "widgets.music.viaAlbum", "name" to name) }
        return r.fans?.takeIf { it > 0 }?.let { t("widgets.music.fans", "count" to it, "fans" to compactNumber(it)) } ?: ""
    }
    val what = when (r.record) {
        "single" -> t("widgets.music.single")
        "ep" -> t("widgets.music.ep")
        "compile" -> t("widgets.music.compilation")
        else -> ""
    }
    return listOfNotNull(r.by, if (type == "songs") r.album else what).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun compactNumber(n: Long): String = try {
    android.icu.text.CompactDecimalFormat.getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT).apply { maximumFractionDigits = 1 }.format(n)
} catch (_: Exception) {
    n.toString()
}

/**
 * A music widget: a search of Deezer through this server that finds what was
 * meant from a name, a song or an album, or one typed in by name with a
 * picture of its own; and the ones chosen, in order.
 */
private fun openMusicEditor(initial: Widgets.MusicWidget, onDone: (Widgets.Widget) -> Unit) {
    val type = initial.type
    val most = Limits.MUSIC[type] ?: 10
    var items by mutableStateOf(initial.music)
    var uploading by mutableStateOf<String?>(null)
    openSheet(
        "widget-music", title = Widgets.title(type),
        footer = { close ->
            EditorFooter(close, enabled = items.isNotEmpty() && uploading == null) {
                close()
                onDone(initial.copy(music = items))
            }
        },
    ) {
        val c = Nx.colors
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var q by remember { mutableStateOf("") }
        var found by remember { mutableStateOf<Pair<String, List<FoundMusic>>?>(null) }
        var searching by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf<Boolean?>(null) }
        var pictureFor by remember { mutableStateOf<String?>(null) }
        val query = q.trim()
        suspend fun search(text: String): Pair<Boolean, List<FoundMusic>> {
            val res = Nexa.api.get("/music/search?kind=$type&q=" + java.net.URLEncoder.encode(text, "UTF-8")) as JsonObject
            val list = (res["results"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { o ->
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val id = s("id") ?: return@mapNotNull null
                val name = s("name") ?: return@mapNotNull null
                val via = (o["via"] as? JsonObject)?.let { v -> ((v["kind"] as? JsonPrimitive)?.contentOrNull ?: "song") to ((v["name"] as? JsonPrimitive)?.contentOrNull ?: "") }
                FoundMusic(id, name, s("by"), s("art"), (o["duration"] as? JsonPrimitive)?.intOrNull, s("album"), s("record"), s("fans")?.toLongOrNull(), via)
            } ?: emptyList()
            return ((res["ready"] as? JsonPrimitive)?.contentOrNull != "false") to list
        }
        LaunchedEffect(Unit) { ready = runCatching { search("").first }.getOrDefault(true) }
        // a search a key press made stale is called off, not just ignored
        LaunchedEffect(query, ready) {
            failed = false
            if (query.isEmpty() || ready == false) {
                found = null
                searching = false
                return@LaunchedEffect
            }
            searching = true
            delay(280)
            try {
                val (r, list) = search(query)
                ready = r
                found = query to list
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
            searching = false
        }
        fun has(m: Widgets.Music) = items.any { musicKey(it) == musicKey(m) }
        val isFull = items.size >= most
        fun add(m: Widgets.Music) {
            items = when {
                has(m) -> items.filter { musicKey(it) != musicKey(m) }
                isFull -> items
                else -> items + m
            }
        }
        fun fromFound(r: FoundMusic) = Widgets.Music(r.id, r.name, if (type != "artists") r.by else null, r.art, null, if (type == "songs") r.duration else null)
        fun addByName() {
            val name = query.take(Limits.NAME)
            if (name.isEmpty()) return
            add(Widgets.Music(null, name, null, null, null, null))
            q = ""
        }
        fun patch(key: String, change: (Widgets.Music) -> Widgets.Music) {
            items = items.map { if (musicKey(it) == key) change(it) else it }
        }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val key = pictureFor
            if (uri == null || key == null) return@rememberLauncherForActivityResult
            scope.launch {
                val f = runCatching { ServerApi.read(context, uri) }.getOrNull() ?: return@launch
                openImageCrop(f, 1f, round = type == "artists", title = t("widgets.card.cropTitle"), maxSize = 512, serverCrop = true) { r ->
                    uploading = key
                    scope.launch {
                        try {
                            val id = uploadWidgetImage(r, "square")
                            patch(key) { it.copy(image = id) }
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("composer.composer.uploadFailed")), "error")
                        } finally {
                            uploading = null
                        }
                    }
                }
            }
        }
        val results = found?.takeIf { it.first == query }?.second ?: emptyList()
        val exact = results.any { it.name.equals(query, ignoreCase = true) }
        val byName = query.isNotEmpty() && !exact && items.none { it.id == null && it.name.equals(query, ignoreCase = true) }

        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            EditLabel(t("widgets.music.search.$type"))
            SearchBox(q, { q = it }, if (ready == false) t("widgets.music.namePlaceholder") else t("widgets.music.placeholder.$type"), t("widgets.music.search.$type")) {
                val first = results.firstOrNull()
                if (first != null && !has(fromFound(first))) add(fromFound(first))
                else if (first == null && byName && ready == false) addByName()
            }
            if (ready == false) Txt(t("widgets.music.notSetUp"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
            if (query.isNotEmpty()) ResultsBox {
                for (r in results) {
                    val item = fromFound(r)
                    val on = has(item)
                    ResultRow(on, !isFull, { add(item) }, { MusicArt(type, item, 36, 36.dp) }, r.name, foundAbout(type, r), if (type == "songs") r.duration?.let { Widgets.songLength(it) } else null)
                }
                if (searching && results.isEmpty()) ResultStatus(t("widgets.game.searching"))
                if (!searching && failed) ResultStatus(t("widgets.music.unavailable"))
                if (!searching && !failed && ready != false && found?.first == query && results.isEmpty()) ResultStatus(t("widgets.music.noResults"))
                if (byName && !searching) ByNameRow(query, !isFull, square = true, round = type == "artists") { addByName() }
            }
            if (ready == true) Txt(t("widgets.music.credit", "site" to "Deezer"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, modifier = Modifier.padding(start = 2.dp, top = 6.dp))

            ChosenHeading(t("widgets.music.yours.$type"), t("widgets.used", "count" to items.size, "max" to most))
            if (items.isEmpty()) ChosenEmpty(t("widgets.music.noneYet"))
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { i, m ->
                    val key = musicKey(m)
                    ChosenRow(false, i, items.size, { d -> items = moved(items, i, d) }, t("widgets.game.remove", "name" to m.name), { items = items.filter { musicKey(it) != key } }) {
                        MusicArt(type, m, 36, 36.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column {
                                Txt(m.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(18f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 2)
                                if (!m.by.isNullOrEmpty()) Txt(m.by, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f), lineHeight = Nx.type.sp(16f)), color = c.textMuted)
                            }
                            if (m.art == null && m.id == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                EditChip(if (m.image != null) t("widgets.changePicture") else t("widgets.addPicture"), "ImagePlus", busy = uploading == key, enabled = uploading == null) {
                                    pictureFor = key
                                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                if (m.image != null) EditChip(t("widgets.removePicture")) { patch(key) { it.copy(image = null) } }
                            }
                        }
                    }
                }
            }
            if (items.size > 1) Txt(t("android.server.reorderGridHint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A link as it was typed, with the https:// it was most likely meant to have. */
private fun withScheme(raw: String): String {
    val v = raw.trim()
    if (v.isEmpty() || Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(v)) return v
    return "https://" + v.trimStart('/')
}

private fun linkProblem(raw: String): String? {
    if (raw.isEmpty()) return null
    return try {
        val u = java.net.URI(raw)
        if ((u.scheme != "https" && u.scheme != "http") || u.host.isNullOrEmpty() || u.userInfo != null) t("widgets.card.badLink") else null
    } catch (_: Exception) {
        t("widgets.card.badLink")
    }
}

/** One of a card's colours: a round swatch that opens the colour picker. */
@Composable
private fun CardSwatch(value: Int, label: String, onChange: (Int) -> Unit) {
    val hex = "#%06x".format(value)
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF000000L or value.toLong())).ring(CircleShape, Nx.colors.borderSubtle).topEdge(CircleShape, Color.White.copy(alpha = 0.2f))
            .pressable(remember { MutableInteractionSource() }) { openColourPicker(label, hex, false) { v -> v.removePrefix("#").take(6).toIntOrNull(16)?.let(onChange) } }
            .semantics { contentDescription = label },
    )
}

/**
 * A card of your own: a title, a picture across its top or beside the title,
 * a few words, up to six stats and a link, and the card as the profile will
 * show it.
 */
private fun openCardEditor(initial: Widgets.CardWidget, isNew: Boolean, onDone: (Widgets.Widget) -> Unit) {
    var title by mutableStateOf(initial.title)
    var text by mutableStateOf(initial.text ?: "")
    var image by mutableStateOf(initial.image?.let { it to (initial.shape ?: "wide") })
    var stats by mutableStateOf(initial.stats)
    var links by mutableStateOf(initial.links.map { it.url to (it.label ?: "") })
    var color by mutableStateOf(initial.color)
    var color2 by mutableStateOf(initial.color2)
    var uploading by mutableStateOf<String?>(null)
    var tried by mutableStateOf(false)
    fun card(): Widgets.CardWidget {
        val filled = stats
            .map { if (it.bar != null) Widgets.Stat(it.label.trim(), "${it.bar}%", it.bar) else Widgets.Stat(it.label.trim(), it.value.trim()) }
            .filter { it.label.isNotEmpty() || (it.bar == null && it.value.isNotEmpty()) }
        val typed = links.map { (u, l) -> Widgets.Link(withScheme(u), l.trim().ifEmpty { null }) }.filter { it.url.isNotEmpty() }
        return Widgets.CardWidget(
            initial.id, title.trim(), image?.first, image?.second, text.trim().ifEmpty { null }, filled,
            typed, color, if (color != null) color2 else null,
        )
    }
    fun problem(): String? {
        val cw = card()
        val something = cw.text != null || cw.image != null || cw.stats.isNotEmpty() || cw.links.isNotEmpty()
        return when {
            cw.title.isEmpty() -> t("widgets.card.needsTitle")
            !something -> t("widgets.card.needsMore")
            cw.stats.any { it.label.isEmpty() || it.value.isEmpty() } -> t("widgets.card.halfStat")
            else -> cw.links.firstNotNullOfOrNull { linkProblem(it.url) }
        }
    }
    openSheet(
        "widget-card", title = if (isNew) t("widgets.card.titleNew") else t("widgets.card.titleEdit"),
        footer = { close ->
            EditorFooter(close, enabled = uploading == null && !(tried && problem() != null)) {
                tried = true
                if (problem() != null || uploading != null) return@EditorFooter
                close()
                onDone(card())
            }
        },
    ) {
        val c = Nx.colors
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        var pickShape by remember { mutableStateOf("wide") }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val shape = pickShape
            scope.launch {
                val f = runCatching { ServerApi.read(context, uri) }.getOrNull() ?: return@launch
                openImageCrop(f, if (shape == "wide") 12f / 5f else 1f, round = false, title = t("widgets.card.cropTitle"), maxSize = 720, serverCrop = true) { r ->
                    uploading = shape
                    scope.launch {
                        try {
                            image = uploadWidgetImage(r, shape) to shape
                        } catch (e: Exception) {
                            Nexa.ui.toast(describeError(e, t("composer.composer.uploadFailed")), "error")
                        } finally {
                            uploading = null
                        }
                    }
                }
            }
        }
        fun choose(shape: String) {
            pickShape = shape
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            NxTextField(
                title, { title = it.take(Limits.TITLE) },
                label = t("widgets.card.title"),
                placeholder = t("widgets.card.titlePlaceholder"),
                error = if (tried && title.isBlank()) t("widgets.card.needsTitle") else null,
                capitalize = true,
            )
            Column {
                EditLabel(t("widgets.card.picture"))
                val img = image
                if (img != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val (id, shape) = img
                    AsyncImage(
                        Media.widgetUrl(id, if (shape == "wide") 300 else 104, density), null, Media.loader,
                        (if (shape == "wide") Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(12f / 5f).ring(Radii.Sm, c.borderSubtle).clip(Radii.Sm) else Modifier.size(104.dp).ring(RoundedCornerShape(16.dp), c.borderSubtle).clip(RoundedCornerShape(16.dp)))
                            .background(c.bgActive),
                        contentScale = ContentScale.Crop,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditChip(t("widgets.changePicture"), "ImagePlus", busy = uploading != null) { choose(shape) }
                        EditChip(t("widgets.removePicture")) { image = null }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickTile(t("widgets.card.wide"), uploading == "wide", uploading == null, Modifier.weight(1f)) { choose("wide") }
                    PickTile(t("widgets.card.square"), uploading == "square", uploading == null, Modifier.width(104.dp)) { choose("square") }
                }
            }
            Column {
                EditLabel(t("widgets.card.text"))
                fish.nexa.ui.format.FormattedTextField(text, { text = it.take(Limits.TEXT) }, Limits.TEXT, t("widgets.card.textPlaceholder"), t("widgets.card.text"), null, minLines = 3)
            }
            Column {
                EditLabel(t("widgets.card.stats"), Modifier.padding(bottom = 0.dp))
                Txt(t("widgets.card.statsHint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted, modifier = Modifier.padding(bottom = 8.dp))
                if (stats.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    stats.forEachIndexed { i, st ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NxTextField(st.label, { v -> stats = stats.mapIndexed { j, s -> if (j == i) s.copy(label = v.take(Limits.LABEL)) else s } }, Modifier.weight(1f).semantics { contentDescription = t("widgets.card.statLabelN", "n" to i + 1) }, placeholder = t("widgets.card.statLabel"), capitalize = true)
                            if (st.bar != null) NxTextField(
                                st.bar.toString(), { v -> val n = v.filter { it.isDigit() }.take(3).toIntOrNull()?.coerceIn(0, 100) ?: 0; stats = stats.mapIndexed { j, s -> if (j == i) s.copy(bar = n) else s } },
                                Modifier.weight(1.3f).semantics { contentDescription = t("widgets.card.statPercentN", "n" to i + 1) }, placeholder = t("widgets.card.statPercent"), keyboardType = KeyboardType.Number,
                            ) else NxTextField(st.value, { v -> stats = stats.mapIndexed { j, s -> if (j == i) s.copy(value = v.take(Limits.VALUE)) else s } }, Modifier.weight(1.3f).semantics { contentDescription = t("widgets.card.statValueN", "n" to i + 1) }, placeholder = t("widgets.card.statValue"), capitalize = true)
                            IconButton(
                                "ChartNoAxesColumn", t("widgets.card.statBarN", "n" to i + 1),
                                { stats = stats.mapIndexed { j, s -> if (j != i) s else if (s.bar != null) Widgets.Stat(s.label, "") else Widgets.Stat(s.label, "", s.value.trim().removeSuffix("%").toIntOrNull()?.coerceIn(0, 100) ?: 50) } },
                                tint = if (st.bar != null) c.textBrand else c.textMuted, size = 36.dp, iconSize = 16.dp,
                            )
                            IconButton("X", t("widgets.card.removeStat", "n" to i + 1), { stats = stats.filterIndexed { j, _ -> j != i } }, tint = c.textMuted, size = 36.dp, iconSize = 16.dp)
                        }
                    }
                }
                if (stats.size < Limits.STATS) Box(Modifier.padding(top = if (stats.isNotEmpty()) 8.dp else 0.dp)) {
                    EditChip(t("widgets.card.addStat"), "Plus") { stats = stats + Widgets.Stat("", "") }
                }
            }
            Column {
                EditLabel(t("widgets.card.links"))
                if (links.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    links.forEachIndexed { i, (u, l) ->
                        val bad = if (u.isNotBlank()) linkProblem(withScheme(u)) else null
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NxTextField(
                                u, { v -> links = links.mapIndexed { j, x -> if (j == i) v.take(512) to x.second else x } },
                                Modifier.onFocusChanged { f -> if (!f.isFocused && u.isNotBlank()) links = links.mapIndexed { j, x -> if (j == i) withScheme(x.first) to x.second else x } }.semantics { contentDescription = t("widgets.card.linkUrlN", "n" to i + 1) },
                                placeholder = "https://",
                                keyboardType = KeyboardType.Uri,
                                error = bad,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NxTextField(
                                    l, { v -> links = links.mapIndexed { j, x -> if (j == i) x.first to v.take(Limits.LINK_LABEL) else x } },
                                    Modifier.weight(1f).semantics { contentDescription = t("widgets.card.linkLabelN", "n" to i + 1) },
                                    placeholder = t("widgets.card.linkLabel"),
                                    enabled = u.isNotBlank(),
                                    capitalize = true,
                                )
                                IconButton("X", t("widgets.card.removeLink", "n" to i + 1), { links = links.filterIndexed { j, _ -> j != i } }, tint = c.textMuted, size = 36.dp, iconSize = 16.dp)
                            }
                        }
                    }
                }
                if (links.size < Widgets.MAX_LINKS) Box(Modifier.padding(top = if (links.isNotEmpty()) 8.dp else 0.dp)) {
                    EditChip(t("widgets.card.addLink"), "Plus") { links = links + ("" to "") }
                }
            }
            Column {
                EditLabel(t("widgets.card.colour"), Modifier.padding(bottom = 0.dp))
                Txt(t("widgets.card.colourHint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)), color = c.textMuted, modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val first = color
                    if (first == null) EditChip(t("widgets.card.addColour"), "Palette") { color = 0x8000FF }
                    else {
                        CardSwatch(first, t("widgets.card.firstColour")) { color = it }
                        val second = color2
                        if (second != null) CardSwatch(second, t("widgets.card.secondColour")) { color2 = it }
                        else EditChip(t("widgets.card.addSecondColour"), "Plus") { color2 = 0x00B0F4 }
                        EditChip(t("widgets.card.noColour")) { color = null; color2 = null }
                    }
                }
            }
            Column {
                EditLabel(t("settings.profile.preview"))
                val shown = card().let { it.copy(title = it.title.ifEmpty { t("widgets.card.titlePlaceholder") }) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(mixOklab(c.bgFloating, c.bgBase, 0.45f), Radii.Md)
                        .ring(Radii.Md, c.borderSubtle)
                        .topEdge(Radii.Md, c.edgeLight)
                        .padding(14.dp),
                ) { ProfileWidgets(listOf(shown)) }
            }
            val p = problem()
            if (tried && p != null) Txt(p, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textDanger, modifier = Modifier.semantics { contentDescription = p })
        }
    }
}

/** A shape to pick a card's picture in, before there is one (`.pick`). */
@Composable
private fun PickTile(text: String, busy: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier
            .height(104.dp)
            .graphicsLayer { alpha = if (enabled || busy) 1f else 0.6f }
            .background(if (pressed) c.brand.copy(alpha = 0.06f) else Color.Transparent, Radii.Md)
            .border(1.5.dp, if (pressed) mixOklab(c.border, c.brand, 0.6f) else c.border, Radii.Md)
            .clip(Radii.Md)
            .pressable(interaction, enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) Spinner(c.textMuted, size = 20.dp) else Icon("ImagePlus", if (pressed) c.textStrong else c.textMuted, size = 20.dp)
        Txt(text, style = Nx.type.label.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = if (pressed) c.textStrong else c.textMuted, maxLines = 1)
    }
}
