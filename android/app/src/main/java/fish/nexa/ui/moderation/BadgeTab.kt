package fish.nexa.ui.moderation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Badges
import fish.nexa.data.ModEvent
import fish.nexa.data.ModUser
import fish.nexa.data.Moderation
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.TickBox
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.profile.BadgePill
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch

private const val MAX_NAME = 32

private fun colourHex(c: Int) = "#%06x".format(c and 0xFFFFFF)

/**
 * The web's BadgesTab: Contributor and the badges staff make for an event,
 * each a name, a Lucide icon and a colour, made and changed in the card at
 * the top. They are given on a person's page (openGiveBadgesSheet);
 * deleting one takes it from everyone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgesTab() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Badges.Def>() }
    var contributors by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Badges.Def?>(null) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(Badges.EVENT_ICONS[0]) }
    var colour by remember { mutableIntStateOf(Badges.EVENT_COLOURS[0]) }
    var busy by remember { mutableStateOf(false) }

    fun load() {
        failed = false
        scope.launch {
            try {
                val (defs, n) = Badges.defs()
                list.clear()
                list.addAll(defs)
                contributors = n
                loaded = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    // another member of staff gave, made or deleted one
    LaunchedEffect(Unit) {
        Moderation.events.collect { e -> if (e is ModEvent.Log && e.entry.action.startsWith("badge_")) load() }
    }
    fun startEdit(d: Badges.Def?) {
        editing = d
        name = d?.name ?: ""
        icon = d?.icon ?: Badges.EVENT_ICONS[0]
        colour = d?.color ?: Badges.EVENT_COLOURS[0]
    }
    val nameLength = name.trim().let { it.codePointCount(0, it.length) }
    val ready = nameLength in 1..MAX_NAME
    fun submit() {
        busy = true
        scope.launch {
            try {
                val was = editing
                if (was != null) {
                    Badges.editDef(was.id, name.trim(), icon, colour)?.let { d -> list.replaceAll { if (it.id == d.id) d.copy(holders = it.holders) else it } }
                    Nexa.ui.toast(t("badges.staff.saved"), "success")
                } else {
                    Badges.createDef(name.trim(), icon, colour)?.let { list.add(0, it) }
                    Nexa.ui.toast(t("badges.staff.created"), "success")
                }
                startEdit(null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
            } finally {
                busy = false
            }
        }
    }
    fun remove(d: Badges.Def) = confirm(t("badges.staff.deleteTitle"), t("badges.staff.deleteBody", "name" to d.name), t("badges.staff.delete")) {
        Badges.deleteDef(d.id)
        list.removeAll { it.id == d.id }
        if (editing?.id == d.id) startEdit(null)
        Nexa.ui.toast(t("badges.staff.deleted"), "success")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("form") {
            val shape = Radii.Lg
            Column(Modifier.fillMaxWidth().shadowMedium(shape, c).ring(shape, c.panelEdge).background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon("Award", c.textStrong, size = 18.dp)
                    Txt(if (editing != null) t("badges.staff.edit") else t("badges.staff.new"), style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(680)), color = c.textStrong, modifier = Modifier.semantics { heading() })
                }
                Txt(t("badges.staff.hint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), lineHeight = Nx.type.sp(19.6f)), color = c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // the badge being made, as it will look
                    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radii.Md).background(c.bgBase.copy(alpha = 0.6f)).ring(Radii.Md, c.borderSubtle), contentAlignment = Alignment.Center) {
                        Box(Modifier.scale(1.35f)) {
                            BadgePill(Badges.Badge("${Badges.EVENT_PREFIX}0", name = name.trim().ifEmpty { t("badges.staff.namePlaceholder") }, icon = icon, color = colour), "0", 0L, "", still = true, named = true)
                        }
                    }
                    NxTextField(name, { name = it.take(MAX_NAME + 8) }, label = t("badges.staff.name"), placeholder = t("badges.staff.namePlaceholder"), hint = if (nameLength > MAX_NAME - 8) "$nameLength/$MAX_NAME" else null, capitalize = true)
                    Txt(t("badges.staff.icon"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (name in Badges.EVENT_ICONS) {
                            val on = icon == name
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(if (on) Modifier.background(c.brand.copy(alpha = 0.16f)).ring(RoundedCornerShape(8.dp), c.brand.copy(alpha = 0.4f)) else Modifier)
                                    .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton) { icon = name }
                                    .semantics { contentDescription = name; selected = on },
                                contentAlignment = Alignment.Center,
                            ) { Icon(name, if (on) c.textBrand else c.textMuted, size = 18.dp) }
                        }
                    }
                    Txt(t("badges.staff.colour"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        for (sw in Badges.EVENT_COLOURS) {
                            val on = colour == sw
                            val col = Color(0xFF000000 or sw.toLong())
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .then(if (on) Modifier.ring(CircleShape, col).padding(3.dp) else Modifier)
                                    .clip(CircleShape)
                                    .background(col)
                                    .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton) { colour = sw }
                                    .semantics { contentDescription = colourHex(sw); selected = on },
                            )
                        }
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).ring(CircleShape, c.textMuted.copy(alpha = 0.45f))
                                .pressable(remember { MutableInteractionSource() }, role = Role.Button) {
                                    fish.nexa.ui.settings.openColourPicker(t("badges.staff.customColour"), colourHex(colour), alpha = false) { v -> v.removePrefix("#").take(6).toIntOrNull(16)?.let { colour = it } }
                                }
                                .semantics { contentDescription = t("badges.staff.customColour") },
                            contentAlignment = Alignment.Center,
                        ) { Icon("Pipette", c.textMuted, size = 15.dp) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (editing != null) NxButton(t("chat.editMessage.cancel"), { startEdit(null) }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH)
                        Spacer(Modifier.weight(1f))
                        NxButton(if (editing != null) t("badges.staff.save") else t("badges.staff.create"), ::submit, size = ButtonSize.TOUCH, icon = if (editing != null) null else "Plus", enabled = ready, loading = busy)
                    }
                }
            }
        }
        item("listTitle") {
            Txt(t("badges.staff.list"), style = Nx.type.name.copy(fontSize = Nx.type.sp(17f), fontWeight = FontWeight(680)), color = c.textStrong, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        }
        when {
            failed && !loaded -> item("failed") { LoadFailed({ load() }) }
            !loaded -> item("loading") { LoadingRow() }
            else -> {
                item("contributor") {
                    DefRow(Badges.Badge(Badges.CONTRIBUTOR), t("badges.staff.holders", "count" to contributors), note = t("badges.staff.contributorNote"))
                }
                items(list, key = { it.id }) { d ->
                    DefRow(Badges.Badge("${Badges.EVENT_PREFIX}${d.id}", name = d.name, icon = d.icon, color = d.color), t("badges.staff.holders", "count" to d.holders), editing = editing?.id == d.id) {
                        ModIconButton("PenLine", t("badges.staff.editNamed", "name" to d.name), { startEdit(d) })
                        ModIconButton("Trash2", t("badges.staff.deleteNamed", "name" to d.name), { remove(d) })
                    }
                }
                if (list.isEmpty()) item("none") { Txt(t("badges.staff.none"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted, modifier = Modifier.padding(14.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefRow(badge: Badges.Badge, meta: String, note: String? = null, editing: Boolean = false, tools: (@Composable () -> Unit)? = null) {
    val c = Nx.colors
    val shape = Radii.Md
    Column(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).ring(shape, if (editing) c.focusRing else c.panelEdge, if (editing) 2.dp else 1.dp).background(cardFill(c), shape).background(sheen(c), shape).topEdge(shape, c.edgeLight).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BadgePill(badge, "0", 0L, "", still = true, named = true)
            Txt(meta, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            if (tools != null) Row { tools() }
        }
        if (note != null) Txt(note, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted)
    }
}

/**
 * The web's GrantBadgesModal: the badges staff give someone by hand,
 * Contributor and every event badge, each ticked when they hold it. Saving
 * gives and takes the difference.
 */
fun openGiveBadgesSheet(u: ModUser) {
    var defs by mutableStateOf<List<Badges.Def>?>(null)
    var held by mutableStateOf<List<String>?>(null)
    var pick by mutableStateOf<Set<String>>(emptySet())
    var failed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    suspend fun load() {
        failed = false
        try {
            val all = Badges.defs().first
            val mine = Badges.granted(u.id)
            defs = all
            held = mine
            pick = mine.toSet()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed = true
        }
    }
    openSheet("give-badges", title = t("badges.staff.giveTitle", "name" to u.displayName), heading = { fish.nexa.ui.chat.PersonHeading(fish.nexa.ui.chat.PersonWords(t("badges.staff.giveTitle", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(u.id to u.displayName))) }, footer = { close ->
        val scope = rememberCoroutineScope()
        val h = held
        val changed = h != null && (h.size != pick.size || h.any { it !in pick })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            NxButton(t("chat.editMessage.cancel"), { close() }, kind = ButtonKind.SECONDARY)
            NxButton(t("badges.staff.save"), {
                busy = true
                scope.launch {
                    try {
                        Badges.setGranted(u.id, pick.toList())
                        Nexa.ui.toast(t("badges.staff.givenDone", "name" to u.displayName), "success")
                        close()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
                    } finally {
                        busy = false
                    }
                }
            }, loading = busy, enabled = changed)
        }
    }) {
        val c = Nx.colors
        LaunchedEffect(Unit) { load() }
        val scope = rememberCoroutineScope()
        Txt(t("badges.staff.giveBody"), style = Nx.type.bodySmall, color = c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
        val h = held
        when {
            failed -> LoadFailed({ scope.launch { load() } })
            h == null -> LoadingRow()
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val options = listOf(Badges.Badge(Badges.CONTRIBUTOR)) + (defs ?: emptyList()).map { Badges.Badge("${Badges.EVENT_PREFIX}${it.id}", name = it.name, icon = it.icon, color = it.color) }
                for (o in options) {
                    val on = o.id in pick
                    val shape = Radii.Md
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (on) c.brand.copy(alpha = 0.12f) else c.bgInput)
                            .ring(shape, if (on) c.brand else c.borderSubtle, if (on) 2.dp else 1.dp)
                            .pressable(remember { MutableInteractionSource() }, role = Role.Checkbox) { pick = if (on) pick - o.id else pick + o.id }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TickBox(on)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BadgePill(o, "0", 0L, "", still = true, named = true)
                                if (o.id in h) Txt(t("badges.staff.held"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = c.textMuted)
                            }
                            if (o.id == Badges.CONTRIBUTOR) Txt(t("badges.contributorTip"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                        }
                    }
                }
                if (defs?.isEmpty() == true) Txt(t("badges.staff.noneYet"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
            }
        }
    }
}
