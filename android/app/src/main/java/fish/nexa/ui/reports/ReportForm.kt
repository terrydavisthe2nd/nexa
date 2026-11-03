package fish.nexa.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.*
import fish.nexa.net.PublicUser
import fish.nexa.ui.Icon
import fish.nexa.ui.collectEntryAsState
import fish.nexa.ui.components.*
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.SheetTitle
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

fun reportItem(context: ReportContext) =
    MenuItem.Action(
        if (context.kind == "application") t("reports.report") + ": " + t("reports.kind.application")
        else t("reports.report")
    ) {
        openReport(context)
    }

/** The form's step: what to report (from Settings), why, what happened, and the receipt. */
private enum class Step { START, REASON, DETAILS, SENT }

/** What the sheet's frame (title and footer) and its content share. */
private class ReportFlow(context: ReportContext) {
    var step by mutableStateOf(if (context.kind == null) Step.START else Step.REASON)
    var busy by mutableStateOf(false)
    var canSend by mutableStateOf(false)
    var send: () -> Unit by mutableStateOf({})
}

/**
 * ReportModal.tsx: Report Content as a sheet. Opened from an item's menu it
 * starts at the reasons; from Settings it asks what to report first.
 */
fun openReport(context: ReportContext = ReportContext()) {
    val flow = ReportFlow(context)
    openSheet(
        "reportContent",
        canClose = { !flow.busy },
        heading = { if (flow.step != Step.SENT) SheetTitle(t("reports.report")) },
        footer = { close ->
            when (flow.step) {
                Step.DETAILS ->
                    NxButton(t("reports.send"), { flow.send() }, enabled = flow.canSend && !flow.busy, loading = flow.busy, block = true, size = ButtonSize.LG)
                Step.SENT ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NxButton(t("reports.done"), close, block = true, size = ButtonSize.LG)
                        NxButton(
                            t("reports.yours.view"),
                            {
                                close()
                                fish.nexa.ui.settings.Settings.open("reports")
                            },
                            kind = ButtonKind.SECONDARY,
                            block = true,
                            size = ButtonSize.LG,
                        )
                    }
                else -> {}
            }
        },
    ) { _ ->
        ReportBody(context, flow)
    }
}

@Composable
private fun ReportBody(context: ReportContext, flow: ReportFlow) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(context.kind) }
    var id by remember { mutableStateOf(context.id ?: "") }
    var target by remember { mutableStateOf<SafetyReportTarget?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ChatMessage?>(null) }
    var message by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val shots = remember { mutableStateListOf<Shot>() }
    val requestId = remember { java.util.UUID.randomUUID().toString() }
    val me = Nexa.users.meId

    LaunchedEffect(kind, id, context.serverId, retry) {
        target = null
        message = null
        preview = null
        error = ""
        val k = kind ?: return@LaunchedEffect
        if (k != "other" && !id.matches(Regex("[0-9]{1,20}"))) return@LaunchedEffect
        loading = true
        try {
            val r = Reports.target(k, id.ifEmpty { "0" }, context.serverId)
            if (k == "message") message = Reports.fullMessage(r) { preview = it }
            target = r
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = describeError(e, t("reports.failed"))
        } finally {
            loading = false
        }
    }
    val reasons = target?.reasons ?: Reports.reasons.filter { it != "sexual" }
    val evidence = if (kind == "message") "" else context.evidence.joinToString("\n\n") { it.text }
    val valid = target != null && reason != null && reason in reasons && (kind != "message" || message != null) && description.trim().length >= 3 && evidence.length <= 16000 && shots.none { it.id == null }
    val subjectId = target?.takeIf { (kind == "user" || kind == "message") && it.subjectId != "0" && it.subjectId != me }?.subjectId
    val subject by Nexa.users.byId.collectEntryAsState(subjectId)
    val blocked = subjectId != null && Nexa.relationships.isBlocked(subjectId)

    val send: () -> Unit = send@{
        val k = kind ?: return@send
        val tg = target ?: return@send
        if (!valid || flow.busy) return@send
        flow.busy = true
        error = ""
        scope.launch {
            try {
                Reports.send(
                    buildJsonObject {
                        put("request_id", requestId)
                        put("target_kind", k)
                        if (k == "message") put("message", message!!)
                        put("target_id", tg.id)
                        context.serverId?.let { put("server_id", it) }
                        put("part", if (k == "other") "custom" else "whole")
                        put("custom_part", if (k == "other") description.trim().take(120) else "")
                        put("reason", reason!!)
                        put("description", description)
                        put("excerpt", evidence)
                        put("files", JsonArray(shots.mapNotNull { sh -> sh.id?.let { buildJsonObject { put("id", it); put("name", sh.name) } } }))
                    }
                )
                flow.step = Step.SENT
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = describeError(e, t("reports.failed"))
            } finally {
                flow.busy = false
            }
        }
    }
    SideEffect {
        flow.send = send
        flow.canSend = valid
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        when (flow.step) {
            Step.START ->
                StartStep { k, value ->
                    kind = k
                    id = value
                    reason = null
                    flow.step = Step.REASON
                }
            Step.REASON, Step.DETAILS -> {
                if (context.kind == null)
                    BackLink { flow.step = if (flow.step == Step.DETAILS) Step.REASON else Step.START }
                if (kind != "other" && kind != "message") target?.let { TargetCard(kind ?: "other", it.snapshot) }
                if (kind == "message" && flow.step == Step.REASON) preview?.let { MessagePreview(it, compact = true) }
                if (target?.alreadyReported != null)
                    Txt(
                        t("reports.alreadyReported"),
                        Modifier.fillMaxWidth().background(mixSrgb(c.bgInput, c.brand, 0.10f), Radii.Sm).padding(horizontal = 14.dp, vertical = 12.dp),
                        style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)),
                        color = c.text,
                    )
                if (flow.step == Step.REASON) {
                    Txt(t("reports.whatsWrong"), Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(19f)), color = c.textStrong)
                    Column(Modifier.fillMaxWidth().background(c.bgInput, Radii.Md).padding(4.dp)) {
                        reasons.forEachIndexed { i, r ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(c.borderSubtle))
                            ReasonRow(r, chosen = reason == r, enabled = target != null) {
                                reason = r
                                flow.step = Step.DETAILS
                            }
                        }
                    }
                } else reason?.let { r ->
                    Row(
                        Modifier.fillMaxWidth().background(c.bgInput, Radii.Md).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ReasonGlyph(r)
                        Txt(t("reports.reason.$r"), Modifier.weight(1f), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong)
                        NxButton(t("reports.change"), { flow.step = Step.REASON }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH, enabled = !flow.busy)
                    }
                    if (Reports.tone(r) == ReasonTone.URGENT)
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(Radii.Sm).background(mixSrgb(c.bgInput, c.danger, 0.12f))) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(c.danger))
                            Txt(
                                t(if (r == "self_harm") "reports.urgent.selfHarm" else "reports.urgent.danger"),
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)),
                                color = c.textStrong,
                            )
                        }
                    if (kind == "message") preview?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt(t("reports.sharedWithStaff"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong)
                            MessagePreview(it)
                        }
                    }
                    if (kind != "message" && context.evidence.isNotEmpty())
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt(t("reports.sharedWithStaff"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong)
                            context.evidence.forEach { fish.nexa.ui.markdown.MarkdownView(it.text, fish.nexa.ui.markdown.RenderCtx()) }
                        }
                    NxTextField(
                        description,
                        { if (it.length <= 4000) description = it },
                        label = t("reports.description"),
                        hint = t("reports.descriptionHint"),
                        required = true,
                        singleLine = false,
                        minLines = 5,
                        enabled = !flow.busy,
                        capitalize = true,
                        autoCorrect = true,
                    )
                    if (kind == "other") Screenshots(shots, enabled = !flow.busy) { error = it }
                    Txt(t(if (kind == "message") "reports.fullMessageNotice" else "reports.privacy"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle)
                }
            }
            Step.SENT ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon("CircleCheck", c.positive, size = 44.dp)
                    Txt(t("reports.sent"), Modifier.semantics { heading() }, style = Nx.type.heading.copy(fontSize = Nx.type.sp(22f)), color = c.textStrong, align = TextAlign.Center)
                    Txt(t("reports.sentBody"), style = Nx.type.body, color = c.text, align = TextAlign.Center)
                    Txt(t("reports.sentPrivate"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle, align = TextAlign.Center)
                    if (subjectId != null && !blocked) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.borderSubtle))
                        Txt(t("reports.blockHint"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle, align = TextAlign.Center)
                        NxButton(
                            t("reports.blockPerson", "name" to (subject?.let { it.displayName.ifEmpty { it.username } } ?: "")),
                            { fish.nexa.ui.profile.openBlockDialog(subjectId) },
                            kind = ButtonKind.DANGER,
                            size = ButtonSize.LG,
                        )
                    }
                }
        }
        if (loading && flow.step != Step.START) Txt(t("reports.loading"), style = Nx.type.bodySmall, color = c.textMuted)
        if (evidence.length > 16000) Txt(t("reports.evidenceTooLong"), style = Nx.type.bodySmall, color = c.textDanger)
        if (error.isNotEmpty())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(error, Modifier.weight(1f), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger)
                if (target == null && kind != null) NxButton(t("reports.retry"), { retry++ }, kind = ButtonKind.LINK)
            }
    }
}

@Composable
private fun BackLink(onBack: () -> Unit) {
    val c = Nx.colors
    Row(
        Modifier.clip(Radii.Sm).pressable(remember { MutableInteractionSource() }, onClick = onBack).heightIn(min = 40.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon("ArrowLeft", c.textMuted, size = 18.dp)
        Txt(t("reports.stepBack"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textMuted)
    }
}

/** One reason in the panel: its glyph, its name and what it covers. Pressing it goes on to the details. */
@Composable
private fun ReasonRow(reason: String, chosen: Boolean, enabled: Boolean, onPick: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Sm
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .then(if (chosen) Modifier.background(mixSrgb(c.bgInput, c.brand, 0.12f), shape).border(1.5.dp, c.brand.copy(alpha = 0.7f), shape) else Modifier)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onPick)
            .semantics {
                role = Role.Button
                selected = chosen
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ReasonGlyph(reason)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Txt(t("reports.reason.$reason"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong)
            Txt(t("reports.reasonHint.$reason"), style = Nx.type.bodySmall, color = c.textMuted)
        }
        if (chosen) Icon("Check", c.textBrand, size = 18.dp)
    }
}

/** The chat row as the reader saw it. */
@Composable
private fun MessagePreview(cm: ChatMessage, compact: Boolean = false) {
    val c = Nx.colors
    val channel = Nexa.channels.get(cm.channelId) ?: fish.nexa.net.Channel(id = cm.channelId)
    Box(
        Modifier.fillMaxWidth()
            .then(if (compact) Modifier.heightIn(max = 180.dp) else Modifier)
            .clip(Radii.Sm)
            .background(c.bgRaised, Radii.Sm)
            .border(1.dp, c.borderSubtle, Radii.Sm)
            .padding(bottom = 12.dp)
    ) {
        fish.nexa.ui.chat.MessageRow(
            fish.nexa.ui.chat.MsgItem(cm.id, cm = cm, groupStart = true),
            fish.nexa.ui.chat.ListCtx(channel, Nexa.servers.byId.value[channel.serverId], false, false, Nexa.users.meId ?: ""),
            null,
            false,
            false,
            preview = true,
        )
    }
}

/**
 * From Settings: what to report. A link is the easy way (a message, channel,
 * profile or pack); something with neither link nor ID can still be described;
 * an ID works for the rest.
 */
@Composable
private fun StartStep(onPick: (String, String) -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var byId by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("message") }
    var id by remember { mutableStateOf("") }
    fun useLink() {
        linkError = ""
        val found = parseLink(link.trim())
        when {
            found == null -> linkError = t("reports.start.badLink")
            found.first == "profile" -> {
                checking = true
                scope.launch {
                    try {
                        val u = Nexa.api.request("GET", "/profiles/${java.net.URLEncoder.encode(found.second, "UTF-8")}", PublicUser.serializer())
                        onPick("user", u.id)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        linkError = describeError(e, t("reports.start.badLink"))
                    } finally {
                        checking = false
                    }
                }
            }
            else -> onPick(found.first, found.second)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(t("reports.start.intro"), style = Nx.type.body, color = c.text)
        NxTextField(link, { link = it.take(500); linkError = "" }, label = t("reports.start.link"), placeholder = t("reports.start.linkPlaceholder"), error = linkError.ifEmpty { null }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Go, onIme = { useLink() })
        NxButton(t("reports.start.continue"), { useLink() }, kind = ButtonKind.SECONDARY, icon = "Link2", enabled = link.isNotBlank(), loading = checking, block = true, size = ButtonSize.LG)
        Column(
            Modifier.fillMaxWidth().clip(Radii.Md).background(c.bgInput).pressable(remember { MutableInteractionSource() }) { onPick("other", "") }.semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Txt(t("reports.start.somethingElse"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong)
            Txt(t("reports.start.somethingElseHint"), style = Nx.type.bodySmall, color = c.textMuted)
        }
        NxButton(t("reports.start.byId"), { byId = !byId }, kind = ButtonKind.GHOST, icon = if (byId) "ChevronDown" else "ChevronRight", size = ButtonSize.TOUCH)
        if (byId) {
            SelectField(t("reports.target"), Reports.kinds.filter { it != "other" }.map { it to t("reports.kind.$it") }, kind) { kind = it }
            NxTextField(id, { v -> id = v.filter { it.isDigit() }.take(20) }, label = t("reports.targetId"), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            NxButton(t("reports.start.continue"), { onPick(kind, id) }, kind = ButtonKind.SECONDARY, enabled = id.matches(Regex("[0-9]{1,20}")))
        }
    }
}

/** A link to something on this Nexa: ("message"|"channel"|"pack", id) or ("profile", username). */
private fun parseLink(href: String): Pair<String, String>? = runCatching {
    val u = java.net.URI(href)
    if (u.scheme != "https" && u.scheme != "http") return null
    val hosts = listOfNotNull(
        runCatching { java.net.URI(Nexa.api.baseUrl).authority?.lowercase() }.getOrNull(),
        runCatching { java.net.URI(Nexa.auth.instance.value?.publicUrl ?: "").authority?.lowercase() }.getOrNull(),
    )
    if ((u.authority ?: "").lowercase() !in hosts) return null
    val path = u.path ?: ""
    Regex("^/channels/(?:@me|\\d{1,20})/(\\d{1,20})/(\\d{1,20})/?$").find(path)?.let { return "message" to it.groupValues[2] }
    Regex("^/channels/(?:@me|\\d{1,20})/(\\d{1,20})/?$").find(path)?.let { return "channel" to it.groupValues[1] }
    Regex("^/stickers/pack/(\\d{1,20})/?$").find(path)?.let { return "pack" to it.groupValues[1] }
    Regex("^/@([A-Za-z0-9_.]{2,32})/?$").find(path)?.let { return "profile" to it.groupValues[1].lowercase() }
    null
}.getOrNull()

/** A screenshot going up with a "something else" report; [id] once the server has it. */
private class Shot(val uri: android.net.Uri, val name: String) {
    var id by mutableStateOf<String?>(null)
}

private const val MAX_SHOTS = 5
private const val MAX_SHOT_BYTES = 100L shl 20

/** Screenshots, only for "something else" reports, where nothing else is captured (the web's .screenshots). */
@Composable
private fun Screenshots(shots: MutableList<Shot>, enabled: Boolean, onError: (String) -> Unit) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    fun add(uris: List<android.net.Uri>) {
        val resolver = Nexa.app.contentResolver
        var bytes = 0L
        for (uri in uris.take(MAX_SHOTS - shots.size)) {
            val type = (resolver.getType(uri) ?: "application/octet-stream").lowercase()
            var name = "screenshot"
            var size = 0L
            runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        name = cur.getString(0) ?: name
                        size = cur.getLong(1)
                    }
                }
            }
            bytes += size
            if (bytes > MAX_SHOT_BYTES) {
                onError(t("reports.screenshotsTooBig"))
                break
            }
            val shot = Shot(uri, name)
            shots += shot
            scope.launch {
                try {
                    val body = fish.nexa.net.UriBody(uri, type, size) {}
                    val req = Nexa.api.requestBuilder("/reports/attachments?name=" + java.net.URLEncoder.encode(name.take(120), "UTF-8")).post(body).header("Content-Type", type).build()
                    val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Nexa.api.execute(Nexa.api.http, req, true) } as? JsonObject
                    shot.id = (res?.get("id") as? JsonPrimitive)?.contentOrNull ?: error("no id")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    shots.remove(shot)
                    onError(describeError(e, t("reports.failed")))
                }
            }
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(MAX_SHOTS)) { uris -> if (uris.isNotEmpty()) add(uris) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(t("reports.screenshots"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong)
        Txt(t("reports.screenshotsHint"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shots.forEach { sh ->
                Box(Modifier.size(76.dp).clip(Radii.Sm).background(c.bgInput)) {
                    coil3.compose.AsyncImage(sh.uri, contentDescription = sh.name, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = if (sh.id == null) 0.5f else 1f)
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f))
                            .pressable(remember { MutableInteractionSource() }, enabled = enabled) { shots.remove(sh) }
                            .semantics { contentDescription = t("reports.removeFile", "name" to sh.name) },
                        contentAlignment = Alignment.Center,
                    ) { Icon("X", androidx.compose.ui.graphics.Color.White, size = 14.dp) }
                }
            }
            if (shots.size < MAX_SHOTS)
                Column(
                    Modifier.size(76.dp).clip(Radii.Sm).border(1.5.dp, c.borderStrong, Radii.Sm)
                        .pressable(remember { MutableInteractionSource() }, enabled = enabled && shots.all { it.id != null }) {
                            pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon("ImagePlus", c.textMuted, size = 20.dp)
                    Txt(t(if (shots.any { it.id == null }) "reports.uploading" else "reports.addScreenshot"), style = Nx.type.hint, color = c.textMuted, align = TextAlign.Center)
                }
        }
    }
}
