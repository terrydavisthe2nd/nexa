package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.GameLists
import fish.nexa.data.ModEvent
import fish.nexa.data.Moderation
import fish.nexa.ui.profile.Widgets
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.describeError
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.profile.GameCover
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun cover(found: GameLists.Found?, name: String) =
    Widgets.Game(found?.id, found?.name ?: name, found?.cover, null, emptyList())

/** Names as a person would call them the same (web sameName). */
private fun sameName(a: String, b: String): Boolean {
    fun fold(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
    return fold(a) == fold(b)
}

/**
 * The web's GamesTab: games people shared from the desktop app. Each row leads
 * with the program, since that is what is being decided, with the game it was
 * said to be below. Only how many shared a program shows, never who.
 */
@Composable
fun GamesTab() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<GameLists.Page?>(null) }
    var failed by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf("waiting") }

    fun load() {
        failed = false
        scope.launch {
            try {
                page = GameLists.page()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    // someone shared one, or another member of staff decided
    LaunchedEffect(Unit) {
        Moderation.events.collect { e -> if (e is ModEvent.Games || (e is ModEvent.Log && e.entry.action.startsWith("game_"))) load() }
    }
    fun act(done: String, run: suspend () -> Unit) {
        scope.launch {
            try {
                run()
                Nexa.ui.toast(done, "success")
                load()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
            }
        }
    }
    fun turnDown(process: String, name: String) = act(t("moderation.games.turnedDownDone")) { GameLists.reject(process, name) }

    val p = page
    val listed = p?.decided?.filter { it.status == "listed" }.orEmpty()
    val rejected = p?.decided?.filter { it.status == "rejected" }.orEmpty()
    fun gameOf(id: String?, name: String) = id?.let { p?.games?.get(it) ?: GameLists.Found(it, name, null, null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("head") {
            Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(t("moderation.games.hint"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                ChipRow(t("moderation.tabs.games")) {
                    ModChip(t("moderation.games.waiting"), view == "waiting", { view = "waiting" }, count = p?.waiting?.size?.takeIf { it > 0 })
                    ModChip(t("moderation.games.onList"), view == "listed", { view = "listed" }, count = listed.size.takeIf { it > 0 })
                    ModChip(t("moderation.games.turnedDown"), view == "rejected", { view = "rejected" }, count = rejected.size.takeIf { it > 0 })
                }
            }
        }
        when {
            failed && p == null -> item("failed") { LoadFailed({ load() }) }
            p == null -> item("loading") { LoadingRow() }
            view == "waiting" -> {
                if (p.waiting.isEmpty()) item("none") { EmptyState("Gamepad2", null, t("moderation.games.waitingNone")) }
                items(p.waiting, key = { it.process }) { x ->
                    val game = gameOf(x.gameId, x.name)
                    GameRow(x.process, x.name, game, t("moderation.games.sharedBy", "count" to x.count)) {
                        NxButton(t("moderation.games.turnDown"), { turnDown(x.process, x.name) }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
                        NxButton(t("moderation.games.review"), { openReview(x.process, x.name, game, x.others, true, { load() }) { turnDown(x.process, x.name) } }, size = ButtonSize.SM)
                    }
                }
            }
            view == "listed" -> {
                if (listed.isEmpty()) item("none") { EmptyState("Gamepad2", null, t("moderation.games.listNone")) }
                items(listed, key = { it.process }) { x ->
                    val game = gameOf(x.gameId, x.name)
                    GameRow(x.process, x.name, game, Time.relative(x.decidedAt)) {
                        NxButton(t("moderation.games.remove"), { act(t("moderation.games.removedDone")) { GameLists.forget(x.process) } }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
                        NxButton(t("moderation.games.edit"), { openReview(x.process, x.name, game, emptyList(), false, { load() }) {} }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    }
                }
            }
            else -> {
                if (rejected.isEmpty()) item("none") { EmptyState("Gamepad2", null, t("moderation.games.rejectedNone")) }
                items(rejected, key = { it.process }) { x ->
                    GameRow(x.process, x.name, null, Time.relative(x.decidedAt)) {
                        NxButton(t("moderation.games.allow"), { act(t("moderation.games.allowedDone")) { GameLists.forget(x.process) } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(process: String, name: String, game: GameLists.Found?, aside: String, actions: @Composable () -> Unit) {
    val c = Nx.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.Md).background(c.bgSurface, Radii.Md).topEdge(Radii.Md, c.edgeLight).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GameCover(cover(game, name), 56, 52.dp, RoundedCornerShape(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Txt(process, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong)
                Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.text, maxLines = 1)
                val link = when {
                    game == null -> t("moderation.games.noMatch")
                    game.year != null -> t("moderation.games.matchedYear", "year" to game.year.toString())
                    else -> t("moderation.games.matched")
                }
                Txt(link, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(aside, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

/** Staff have the last word on the name and the game, whatever was shared (web ReviewDialog). */
private fun openReview(process: String, shared: String, sharedGame: GameLists.Found?, others: List<String>, waiting: Boolean, onDone: () -> Unit, onTurnDown: () -> Unit) {
    var name by mutableStateOf(shared)
    var chosen by mutableStateOf(sharedGame)
    var saving by mutableStateOf(false)
    openSheet("game-review", title = t("moderation.games.reviewTitle"), footer = { close ->
        val scope = rememberCoroutineScope()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (waiting) NxButton(t("moderation.games.turnDown"), { close(); onTurnDown() }, kind = ButtonKind.GHOST)
            Spacer(Modifier.weight(1f))
            NxButton(t("chat.editMessage.cancel"), { close() }, kind = ButtonKind.GHOST)
            NxButton(if (waiting) t("moderation.games.addToList") else t("settings.games.save"), {
                val clean = name.trim()
                saving = true
                scope.launch {
                    try {
                        GameLists.list(process, clean, chosen?.id)
                        Nexa.ui.toast(t("moderation.games.addedDone", "name" to clean), "success")
                        close()
                        onDone()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
                    } finally {
                        saving = false
                    }
                }
            }, loading = saving, enabled = name.isNotBlank())
        }
    }) {
        val c = Nx.colors
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().clip(Radii.Sm).background(c.bgSurface, Radii.Sm).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(t("moderation.games.program"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
                Txt(process, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong)
            }
            NxTextField(name, { name = it.take(128) }, label = t("settings.games.nameLabel"), hint = t("moderation.games.nameHint"))
            if (others.isNotEmpty()) OtherNames(others) { name = it }
            GameShelf(name, chosen, decidedAtStart = !waiting || sharedGame != null) { picked ->
                chosen = picked
                if (picked != null) name = picked.name
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherNames(others: List<String>, onPick: (String) -> Unit) {
    val c = Nx.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Txt(t("moderation.games.otherNames"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        for (n in others) ModChip(n, false, { onPick(n) })
    }
}

/** Box art to say which game it is, searched from the name, as the web's GameShelf is: three across on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameShelf(name: String, chosen: GameLists.Found?, decidedAtStart: Boolean, onChoose: (GameLists.Found?) -> Unit) {
    val c = Nx.colors
    var found by remember { mutableStateOf<List<GameLists.Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(true) }
    val query = name.trim()
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            found = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(280)
        try {
            val (r, list) = GameLists.search(query)
            ready = r
            found = list.take(6)
            // two games sharing the name are for the person to tell apart
            if (!decidedAtStart) list.filter { sameName(it.name, query) }.singleOrNull()?.let(onChoose)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            found = emptyList()
        }
        searching = false
    }
    if (!ready) return
    val shelf = if (chosen == null) found else (listOf(found.firstOrNull { it.id == chosen.id } ?: chosen) + found.filter { it.id != chosen.id }).take(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t("settings.games.whichGame"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.textSubtle)
        if (shelf.isEmpty()) {
            Txt(if (searching) t("widgets.game.searching") else t("settings.games.noMatchYet"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
        } else {
            for (rowGames in shelf.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (g in rowGames) Box(Modifier.weight(1f)) { ShelfBox(g, g.id == chosen?.id) { onChoose(if (g.id == chosen?.id) null else g) } }
                    repeat(3 - rowGames.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Txt(t("widgets.game.credit", "site" to "IGDB.com"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted)
    }
}

@Composable
private fun ShelfBox(g: GameLists.Found, on: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier.fillMaxWidth()
            .pressable(remember { MutableInteractionSource() }, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = g.name; selected = on },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // every cover keeps the ring's room, so choosing one moves nothing
        Box(Modifier.padding(bottom = 3.dp).then(if (on) Modifier.border(2.dp, c.brand, shape) else Modifier).padding(2.dp)) {
            GameCover(cover(g, g.name), 84, null, shape)
            if (on) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).background(c.brand, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon("Check", c.onBrand, size = 13.dp) }
            }
        }
        Txt(g.name, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 2)
        g.year?.let { Txt(it.toString(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted) }
    }
}
