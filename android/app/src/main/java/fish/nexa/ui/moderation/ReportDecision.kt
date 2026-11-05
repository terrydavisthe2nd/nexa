package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.*
import fish.nexa.ui.Icon
import fish.nexa.ui.components.*
import fish.nexa.ui.describeError
import fish.nexa.ui.nexa.formatSpan
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Something a report's Take Action can take down; `says` when the label already says what happens. */
private data class Removal(val id: String, val label: String, val says: Boolean = false)

private val PROFILE_FIELDS = listOf(
    "avatar" to "moderation.field.avatar",
    "banner" to "moderation.field.banner",
    "display_name" to "moderation.field.displayName",
    "username" to "moderation.field.username",
    "about_me" to "moderation.field.aboutMe",
    "pronouns" to "moderation.field.pronouns",
    "status" to "moderation.field.status",
    "widgets" to "moderation.field.widgets",
    "name_style" to "moderation.field.nameStyle",
    "typing_style" to "moderation.field.typingStyle",
    "server_profiles" to "moderation.field.serverProfiles",
)
private val SERVER_FIELDS = listOf(
    "icon" to "moderation.field.serverIcon",
    "banner" to "moderation.field.serverBanner",
    "name" to "moderation.field.serverName",
    "description" to "moderation.field.serverDescription",
    "tag" to "moderation.field.serverTag",
    "discovery" to "moderation.field.serverDiscovery",
)

private class Removals(val options: List<Removal>, val picked: List<String>, val single: Boolean = false)

/** What can be taken down for this report, the part people reported already picked (ReportDecision.tsx removalsFor). */
private fun removalsFor(d: SafetyReportDetail): Removals {
    val v = d.report
    return when (v.targetKind) {
        "message" -> Removals(listOf(Removal("message", t("reports.act.removeMessage"), true)), listOf("message"))
        "user" -> {
            val part = if (v.part == "server_profile") "server_profiles" else v.part
            Removals(PROFILE_FIELDS.map { Removal(it.first, t(it.second)) }, if (PROFILE_FIELDS.any { it.first == part }) listOf(part) else emptyList())
        }
        "server" -> Removals(SERVER_FIELDS.map { Removal(it.first, t(it.second)) }, if (SERVER_FIELDS.any { it.first == v.part }) listOf(v.part) else emptyList())
        "pack" -> Removals(listOf(Removal("delete", t("reports.act.removePack"), true), Removal("private", t("reports.act.packPrivate"), true)), emptyList(), single = true)
        "sticker" -> if (v.snapshot["pack_id"] != null) Removals(listOf(Removal("sticker", t("reports.act.removeSticker"), true)), listOf("sticker")) else Removals(emptyList(), emptyList())
        "emoji" -> if (v.serverId != "0") Removals(listOf(Removal("emoji", t("reports.act.removeEmoji"), true)), listOf("emoji")) else Removals(emptyList(), emptyList())
        else -> Removals(emptyList(), emptyList())
    }
}

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
private val MUTE_SPANS = listOf(HOUR, DAY, 3 * DAY, WEEK, 30 * DAY)
private val BAN_SPANS = listOf(DAY, 3 * DAY, WEEK, 30 * DAY, 0L)

/** The raised box a decision opens in, its top edge red for Take Action (`.decide`). */
@Composable
private fun DecideBox(danger: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val c = Nx.colors
    val shape = Radii.Lg
    Column(
        Modifier.fillMaxWidth().shadowMedium(shape, c).background(c.bgRaised, shape).topEdge(shape, c.edgeLight)
            .clip(shape)
            .drawBehind { if (danger) drawRect(c.danger, Offset.Zero, Size(size.width, 3.dp.toPx())) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun DecideLabel(text: String) =
    Txt(text, Modifier.semantics { heading() }, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(650)), color = Nx.colors.textStrong)

/** A choice as a pill (`.pick`): a tick when on, red-tinted when it takes something down. */
@Composable
private fun Pick(label: String, on: Boolean, danger: Boolean = false, radio: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = RoundedCornerShape(50)
    val tint = if (danger) c.danger else c.brand
    Row(
        Modifier.heightIn(min = 40.dp)
            .clip(shape)
            .background(if (on) mixSrgb(c.bgSurface, tint, if (danger) 0.18f else 0.2f) else c.bgSurface, shape)
            .border(1.dp, if (on) tint.copy(alpha = 0.58f) else c.borderSubtle, shape)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, role = if (radio) Role.RadioButton else Role.Checkbox, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on && !radio) Icon("Check", c.textStrong, size = 14.dp)
        Txt(label, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.text, maxLines = 1)
    }
}

/**
 * ReportDecision.tsx ActionPanel: Take Action, opened in place under the
 * reported content. What to take down, what to do to the account, the reason
 * they read and what the reporter is told; one press does all of it and closes
 * this report and the others about the same thing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionPanel(d: SafetyReportDetail, others: Int, onCancel: () -> Unit, onDone: () -> Unit, onChanged: () -> Unit) {
    val c = Nx.colors
    val v = d.report
    val meUser by Nexa.users.me.collectAsState()
    val rank = StaffRanks.of(meUser?.id, meUser?.flags ?: 0L)
    val admin = rank?.atLeast(StaffRank.ADMIN) == true
    val removals = remember(v.id) { removalsFor(d) }
    var picked by remember(v.id) { mutableStateOf(if (admin) removals.picked.toSet() else emptySet()) }
    val subject = d.subject
    val canRestrict = subject != null && subject.blocked.isEmpty()
    var restrict by remember(v.id) { mutableStateOf("none") }
    fun spansFor(r: String) = (if (r == "ban") BAN_SPANS else MUTE_SPANS).filter { admin || (it in 1..StaffRanks.MOD_MAX_MS) }
    var span by remember(v.id) { mutableLongStateOf(DAY) }
    var reason by remember(v.id) { mutableStateOf(t("reports.reason.${v.reason}")) }
    var telling by remember(v.id) { mutableStateOf(false) }
    var told by remember(v.id) { mutableStateOf("") }
    var alsoRelated by remember(v.id) { mutableStateOf(true) }
    var busy by remember(v.id) { mutableStateOf(false) }
    var error by remember(v.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val name = subject?.displayName?.ifEmpty { subject.username } ?: ""
    val doing = picked.isNotEmpty() || restrict != "none"
    val reasonOk = reason.trim().length >= 3
    val banned = subject?.ban != null && subject.ban !is JsonNull
    val muted = subject?.mute != null && subject.mute !is JsonNull

    val will = buildList {
        picked.forEach { id -> removals.options.firstOrNull { it.id == id }?.let { add(if (it.says) it.label else t("reports.act.willRemove", "what" to it.label)) } }
        if (restrict == "mute") add(t("reports.act.willMute", "name" to name, "span" to formatSpan(span)))
        if (restrict == "ban") add(if (span > 0) t("reports.act.willBan", "name" to name, "span" to formatSpan(span)) else t("reports.act.willBanForever", "name" to name))
        add(t("reports.act.willClose", "count" to 1 + if (alsoRelated || "message" in picked) others else 0))
    }

    fun submit() {
        if (busy || !reasonOk) return
        busy = true
        error = ""
        scope.launch {
            val why = reason.trim()
            val cases = mutableListOf<Long>()
            var steps = 0
            try {
                var closed = false
                if ("message" in picked) {
                    Reports.removeMessage(v, why, told.trim())
                    closed = true
                    steps++
                }
                if (v.targetKind == "user") {
                    val avatar = "avatar" in picked
                    val banner = "banner" in picked
                    if (avatar || banner) {
                        cases += Moderation.act(v.subjectId, buildJsonObject {
                            put("action", "avatar_remove"); put("reason", why)
                            putJsonObject("images") { put("avatar", avatar); put("banner", banner) }
                        }).number
                        steps++
                    }
                    val fields = picked.filter { it != "avatar" && it != "banner" }
                    if (fields.isNotEmpty()) {
                        cases += Moderation.act(v.subjectId, buildJsonObject {
                            put("action", "field_reset"); put("reason", why)
                            putJsonArray("fields") { fields.forEach { add(it) } }
                        }).number
                        steps++
                    }
                }
                if (v.targetKind == "server" && picked.isNotEmpty()) {
                    cases += Moderation.actOnServer(v.targetId, picked.toList(), why).number
                    steps++
                }
                if (v.targetKind == "pack" && picked.isNotEmpty()) {
                    cases += Moderation.actOnPack(v.targetId, emptyList(), if ("private" in picked) listOf("public") else emptyList(), "delete" in picked, false, why).number
                    steps++
                }
                if ("sticker" in picked) {
                    cases += Moderation.actOnPack(v.snapshot["pack_id"]!!.jsonPrimitive.content, listOf(v.targetId), emptyList(), false, false, why).number
                    steps++
                }
                if ("emoji" in picked) {
                    cases += Moderation.actOnEmojis(v.serverId, listOf(v.targetId), false, why).number
                    steps++
                }
                if (restrict != "none") {
                    cases += Moderation.act(v.subjectId, buildJsonObject {
                        put("action", restrict); put("reason", why)
                        if (span > 0) put("duration_ms", span) else put("permanent", true)
                    }).number
                    steps++
                }
                if (!closed) {
                    // the actions above leave the report as it was; it closes with what was done in its note
                    val fresh = Reports.detail(v.id).report
                    if (!Reports.closing(fresh.status)) {
                        val recorded = cases.joinToString("") { "\n" + t("reports.caseRecorded", "number" to it) }
                        Reports.review(fresh, "actioned", why + recorded, if (fresh.assignedTo == "0") Nexa.users.meId ?: "0" else fresh.assignedTo, told.trim(), others > 0 && alsoRelated)
                    }
                }
                Nexa.ui.toast(t("reports.act.done"), "success")
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val said = describeError(e, t("reports.failed"))
                error = if (steps > 0) t("reports.act.partly", "error" to said) else said
                if (steps > 0) onChanged()
            } finally {
                busy = false
            }
        }
    }

    DecideBox(danger = true) {
        Txt(t("reports.act.takeAction"), Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(17f)), color = c.textStrong)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DecideLabel(t("reports.act.takeDown"))
            when {
                removals.options.isEmpty() -> Txt(t("reports.act.nothingToRemove"), style = Nx.type.bodySmall, color = c.textMuted)
                !admin -> Txt(t("reports.act.adminOnly"), style = Nx.type.bodySmall, color = c.textMuted)
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    removals.options.forEach { o ->
                        Pick(o.label, o.id in picked, danger = true, enabled = !busy) {
                            picked = if (o.id in picked) picked - o.id else if (removals.single) setOf(o.id) else picked + o.id
                        }
                    }
                }
            }
        }
        if (canRestrict)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecideLabel(t("reports.act.account"))
                fish.nexa.ui.support.Segmented(
                    listOf("none" to t("reports.act.restrict.none"), "mute" to t("reports.act.restrict.mute"), "ban" to t("reports.act.restrict.ban")),
                    restrict,
                    { r ->
                        restrict = r
                        val list = spansFor(r)
                        if (span !in list) span = if (DAY in list) DAY else list.first()
                    },
                    t("reports.act.account"),
                )
                if (restrict != "none")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        spansFor(restrict).forEach { ms -> Pick(if (ms > 0) formatSpan(ms) else t("reports.act.permanent"), span == ms, radio = true, enabled = !busy) { span = ms } }
                    }
                if (banned || muted) Txt(t(if (banned) "reports.act.alreadyBanned" else "reports.act.alreadyMuted"), style = Nx.type.bodySmall, color = c.textMuted)
            }
        NxTextField(reason, { if (it.length <= 1000) reason = it }, label = t("moderation.form.reason"), hint = if (doing) t("moderation.form.reasonVisible") else t("reports.act.reasonNoteOnly"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
        if (telling)
            NxTextField(told, { if (it.length <= 1000) told = it }, label = t("reports.decision.told"), hint = t("reports.decision.toldHint"), placeholder = t("reports.outcome.actioned"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
        else NxButton(t("reports.act.tellReporter"), { telling = true }, kind = ButtonKind.LINK, inline = true)
        if (others > 0 && "message" !in picked) NxCheckbox(alsoRelated, { alsoRelated = it }, t("reports.decision.alsoRelated", "count" to others), enabled = !busy)
        Column(Modifier.fillMaxWidth().background(c.bgInput, Radii.Sm).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            will.forEach { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("•", style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                    Txt(w, style = Nx.type.body.copy(fontSize = Nx.type.sp(14f)), color = c.text)
                }
            }
        }
        if (error.isNotEmpty()) Txt(error, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            NxButton(t("reports.decision.cancel"), onCancel, kind = ButtonKind.GHOST, enabled = !busy)
            NxButton(if (doing) t("reports.act.takeAction") else t("reports.act.closeActioned"), { submit() }, kind = if (doing) ButtonKind.DANGER else ButtonKind.PRIMARY, enabled = reasonOk, loading = busy)
        }
    }
}

private val DISMISS_REASONS = listOf("notAgainstRules", "notEnough", "handled", "duplicate")

/** ReportDecision.tsx DismissPanel: a common reason (which is the note) and anything to add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DismissPanel(d: SafetyReportDetail, others: Int, onCancel: () -> Unit, onDone: () -> Unit) {
    val c = Nx.colors
    val v = d.report
    var why by remember(v.id) { mutableStateOf("notAgainstRules") }
    var extra by remember(v.id) { mutableStateOf("") }
    var telling by remember(v.id) { mutableStateOf(false) }
    var told by remember(v.id) { mutableStateOf("") }
    var alsoRelated by remember(v.id) { mutableStateOf(true) }
    var busy by remember(v.id) { mutableStateOf(false) }
    var error by remember(v.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val note = listOf(if (why.isNotEmpty()) t("reports.dismiss.$why") else "", extra.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    DecideBox(danger = false) {
        Txt(t("reports.act.dismiss"), Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(17f)), color = c.textStrong)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DISMISS_REASONS.forEach { r -> Pick(t("reports.dismiss.$r"), why == r, radio = true, enabled = !busy) { why = if (why == r) "" else r } }
        }
        NxTextField(extra, { if (it.length <= 1800) extra = it }, label = t("reports.staffNote"), placeholder = t("reports.dismiss.addHint"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
        if (telling)
            NxTextField(told, { if (it.length <= 1000) told = it }, label = t("reports.decision.told"), hint = t("reports.decision.toldHint"), placeholder = t("reports.outcome.dismissed"), singleLine = false, minLines = 2, enabled = !busy, capitalize = true, autoCorrect = true)
        else NxButton(t("reports.act.tellReporter"), { telling = true }, kind = ButtonKind.LINK, inline = true)
        if (others > 0) NxCheckbox(alsoRelated, { alsoRelated = it }, t("reports.decision.alsoRelated", "count" to others), enabled = !busy)
        if (error.isNotEmpty()) Txt(error, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            NxButton(t("reports.decision.cancel"), onCancel, kind = ButtonKind.GHOST, enabled = !busy)
            NxButton(t("reports.dismiss.confirm"), {
                if (busy || note.length < 3) return@NxButton
                busy = true
                error = ""
                scope.launch {
                    try {
                        Reports.review(v, "dismissed", note, if (v.assignedTo == "0") Nexa.users.meId ?: "0" else v.assignedTo, told.trim(), others > 0 && alsoRelated)
                        Nexa.ui.toast(t("reports.dismiss.done"), "success")
                        onDone()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = describeError(e, t("reports.failed"))
                    } finally {
                        busy = false
                    }
                }
            }, enabled = note.length >= 3, loading = busy)
        }
    }
}
