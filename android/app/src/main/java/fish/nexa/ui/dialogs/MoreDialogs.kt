package fish.nexa.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.Kdf
import fish.nexa.data.Invites
import fish.nexa.net.Channel
import fish.nexa.net.Presence
import fish.nexa.net.Server
import fish.nexa.net.ServerView
import fish.nexa.net.wireJson
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.AvatarColor
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.SelectField
import fish.nexa.ui.components.ToggleRow
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.profile.ProfileLinks
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.sunken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Calendar

// ---------------------------------------------------------------------------
// CreateServerModal: make a server, join one with an invite, or import a template

private fun templateChannelIcon(type: String): String = when (type) {
    "voice" -> "Volume2"
    "announcement" -> "Megaphone"
    "forum" -> "MessagesSquare"
    "media" -> "Images"
    else -> "Hash"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateServerSheet(layer: Layer, close: () -> Unit) {
    val c = Nx.colors
    val me = Nexa.users.me.value
    var mode by remember { mutableStateOf("create") }
    var name by remember { mutableStateOf(me?.let { t("modals.createServer.namedServer", "name" to it.displayName.ifBlank { it.username }) } ?: t("modals.createServer.myServer")) }
    var code by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }

    var templateUrl by remember { mutableStateOf("") }
    var templateName by remember { mutableStateOf("") }
    var templateNameEdited by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Invites.TemplatePreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var templateErr by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mode, templateUrl) {
        if (mode != "template") return@LaunchedEffect
        val raw = templateUrl.trim()
        if (raw.isEmpty()) {
            preview = null
            previewLoading = false
            templateErr = null
            return@LaunchedEffect
        }
        when (val parsed = Invites.parseDiscordTemplate(raw)) {
            is Invites.ParsedTemplate.Err -> {
                preview = null
                previewLoading = false
                templateErr = if (parsed.reason == "invite") t("modals.createServer.inviteNotTemplate") else null
            }
            is Invites.ParsedTemplate.Ok -> {
                templateErr = null
                previewLoading = true
                delay(250)
                try {
                    val res = Invites.previewDiscordTemplate(parsed.code)
                    preview = res
                    if (!templateNameEdited || templateName.isBlank()) templateName = res.serverName
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    preview = null
                    templateErr = describeError(e, t("modals.createServer.couldNotCreateTheServer"))
                } finally {
                    previewLoading = false
                }
            }
        }
    }

    suspend fun finishCreatedServer(view: ServerView) {
        Nexa.channels.upsert(view.channels)
        Nexa.servers.put(view)
        for (ch in view.channels) {
            if (ch.type != "category" && ch.keyConvId == ch.id) {
                runCatching { Nexa.keys.ensureConvKey(ch.id) }
            }
        }
        // the icon goes up once the server exists; a failure there still leaves the server made
        icon?.let { picked ->
            try {
                Nexa.servers.setServer(wireJson.decodeFromJsonElement(Server.serializer(), uploadPicture("PUT", "/servers/${view.id}/icon", picked)))
            } catch (e: Exception) {
                Nexa.ui.toast(
                    if (e is fish.nexa.net.ApiError) t("modals.createServer.theServerWasMadeButItsIcon", "error" to (e.message ?: "")) else t("modals.createServer.theServerWasMadeButIts"),
                    "error",
                )
            }
        }
        close()
        val first = (view.systemChannelId?.let { sys -> view.channels.firstOrNull { it.id == sys } })
            ?: view.channels.firstOrNull { it.type == "text" || it.type == "announcement" || it.type == "forum" || it.type == "media" }
        Nav.go(Route.Server(view.id, first?.id))
        Nav.leftOpen = false
    }

    fun create() {
        val clean = name.trim()
        if (clean.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val res = Nexa.auth.guarded("create_server") { captcha ->
                    Nexa.api.request("POST", "/servers", buildJsonObject {
                        put("name", clean)
                        putJsonObject("defaults") {
                            put("text_category", t("servers.defaults.textChannels"))
                            put("voice_category", t("servers.defaults.voiceChannels"))
                            put("text_channel", t("servers.defaults.general"))
                            put("voice_channel", t("servers.defaults.generalVoice"))
                        }
                    }, captcha = captcha)
                }
                val view = wireJson.decodeFromJsonElement(ServerView.serializer(), res)
                finishCreatedServer(view)
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("modals.createServer.couldNotCreateTheServer")), "error")
            } finally {
                busy = false
            }
        }
    }

    fun importTemplate() {
        val raw = templateUrl.trim()
        if (raw.isEmpty() || busy) return
        val parsed = Invites.parseDiscordTemplate(raw)
        if (parsed is Invites.ParsedTemplate.Err) {
            templateErr = if (parsed.reason == "invite") t("modals.createServer.inviteNotTemplate") else t("modals.createServer.invalidTemplateLink")
            return
        }
        busy = true
        templateErr = null
        scope.launch {
            try {
                val custom = templateName.trim()
                val res = Nexa.auth.guarded("create_server") { captcha ->
                    Nexa.api.request("POST", "/servers/import/discord", buildJsonObject {
                        put("url", raw)
                        if (custom.isNotEmpty()) put("name", custom)
                    }, captcha = captcha)
                }
                val view = wireJson.decodeFromJsonElement(ServerView.serializer(), res)
                finishCreatedServer(view)
            } catch (e: Exception) {
                templateErr = describeError(e, t("modals.createServer.couldNotCreateTheServer"))
            } finally {
                busy = false
            }
        }
    }

    fun joinIt() {
        val trimmed = code.trim()
        if (Invites.isDiscordTemplateUrl(trimmed)) {
            templateUrl = trimmed
            mode = "template"
            return
        }
        val parsed = Invites.parseCode(code)
        if (parsed == null) {
            Nexa.ui.toast(t("modals.createServer.thatDoesnTLookLikeAn"), "error")
            return
        }
        close()
        // the same sheet an invite link opens
        Dialogs.openInvite(parsed.first, parsed.second)
    }

    if (mode == "join") {
        val example = "${ProfileLinks.origin()}/invite/hTKzmak"
        Sheet(layer, title = t("modals.createServer.joinAServer"), maxWidth = 440.dp, footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                NxButton(t("modals.createServer.back"), { mode = "create" }, kind = ButtonKind.GHOST)
                NxButton(t("modals.createServer.joinServer"), ::joinIt, enabled = code.isNotBlank())
            }
        }) {
            DialogHint(t("modals.createServer.enterAnInviteBelowToJoin"))
            NxTextField(code, { code = it }, label = t("modals.createServer.inviteLink"), required = true, placeholder = example, imeAction = ImeAction.Go, onIme = ::joinIt)
            Txt(t("modals.createServer.invitesLookLike", "example" to example), style = Nx.type.hint, color = c.textMuted)
        }
        return
    }

    if (mode == "template") {
        val prev = preview
        val effectiveName = templateName.trim().ifEmpty { prev?.serverName?.ifEmpty { null } ?: name.ifBlank { "S" } }
        Sheet(layer, title = t("modals.createServer.importTemplate"), maxWidth = 460.dp, footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                NxButton(t("modals.createServer.back"), { mode = "create" }, kind = ButtonKind.GHOST)
                NxButton(t("modals.createServer.create"), ::importTemplate, enabled = templateUrl.isNotBlank(), loading = busy)
            }
        }) {
            DialogHint(t("modals.createServer.importTemplateHint"))
            NxTextField(
                templateUrl, { templateUrl = it },
                label = t("modals.createServer.templateLink"),
                required = true,
                placeholder = t("modals.createServer.templatePlaceholder"),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onIme = ::importTemplate,
            )
            templateErr?.let { err ->
                Txt(err, style = Nx.type.bodySmall, color = c.danger)
            }
            if (previewLoading && prev == null) {
                Txt(t("modals.createServer.readingTemplate"), style = Nx.type.hint, color = c.textMuted)
            } else if (prev == null && templateErr == null) {
                Txt(t("modals.createServer.templatesLookLike"), style = Nx.type.hint, color = c.textMuted)
            }
            if (prev != null) {
                val cats = prev.channels.filter { it.type == "category" }
                val loose = prev.channels.filter { it.type != "category" && it.parentIndex < 0 }
                val chanCount = prev.channels.count { it.type != "category" }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .ring(Radii.Md, c.borderSubtle)
                        .background(mixOklab(c.bgFloating, c.bgBase, 0.6f), Radii.Md)
                        .clip(Radii.Md)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(prev.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight.SemiBold), color = c.textStrong, maxLines = 2)
                        Txt(
                            t("modals.createServer.templateSummary", "channels" to chanCount, "categories" to cats.size, "roles" to prev.roles.size),
                            style = Nx.type.hint,
                            color = c.textMuted,
                        )
                        if (prev.description.isNotEmpty()) {
                            Txt(prev.description, style = Nx.type.bodySmall, color = c.textSubtle, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (prev.roles.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (r in prev.roles) {
                                val dotColor = if (r.color > 0) Color(0xFF000000 or r.color.toLong()) else c.textMuted
                                Row(
                                    Modifier.background(c.bgSurface, Radii.Pill).padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                                    Txt(r.name, style = Nx.type.hint, color = c.textSubtle, maxLines = 2)
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (loose.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (ch in loose) {
                                    Row(
                                        Modifier.background(c.bgSurface, Radii.Xs).padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(templateChannelIcon(ch.type), c.textMuted, size = 13.dp)
                                        Txt(ch.name, style = Nx.type.hint, color = c.text, maxLines = 2)
                                        if (ch.isPrivate) Icon("Lock", c.textMuted, size = 11.dp)
                                    }
                                }
                            }
                        }
                        for (cat in cats) {
                            val children = prev.channels.filter { it.type != "category" && it.parentIndex == cat.id }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Txt(cat.name, style = Nx.type.hint.copy(fontWeight = FontWeight.SemiBold), color = c.textMuted, maxLines = 2)
                                if (children.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (ch in children) {
                                            Row(
                                                Modifier.background(c.bgSurface, Radii.Xs).padding(horizontal = 8.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Icon(templateChannelIcon(ch.type), c.textMuted, size = 13.dp)
                                                Txt(ch.name, style = Nx.type.hint, color = c.text, maxLines = 2)
                                                if (ch.isPrivate) Icon("Lock", c.textMuted, size = 11.dp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IconPicker(null, icon, { icon = it }, { icon = null }, AvatarColor.initials(effectiveName), t("modals.createServer.serverIcon"))
                }
                NxTextField(
                    templateName,
                    {
                        templateNameEdited = true
                        templateName = it.take(100)
                    },
                    label = t("modals.createServer.serverName"),
                    required = true,
                    imeAction = ImeAction.Done,
                    onIme = ::importTemplate,
                    capitalize = true,
                )
            }
        }
        return
    }

    val tplInteraction = remember { MutableInteractionSource() }
    val tplPressed by tplInteraction.collectIsPressedAsState()
    Sheet(layer, title = t("modals.createServer.createYourServer"), maxWidth = 440.dp, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            NxButton(t("modals.createServer.haveAnInviteAlready"), { mode = "join" }, kind = ButtonKind.GHOST, modifier = Modifier.weight(1f, fill = false))
            NxButton(t("modals.createServer.create"), ::create, enabled = name.isNotBlank(), loading = busy)
        }
    }) {
        DialogHint(t("modals.createServer.yourServerIsWhereYouAnd"))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconPicker(null, icon, { icon = it }, { icon = null }, AvatarColor.initials(name.ifBlank { "S" }), t("modals.createServer.serverIcon"))
        }
        NxTextField(name, { name = it.take(100) }, label = t("modals.createServer.serverName"), required = true, imeAction = ImeAction.Done, onIme = ::create, capitalize = true)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .ring(Radii.Md, if (tplPressed) c.borderStrong else c.borderSubtle)
                .background(mixOklab(c.bgFloating, c.bgBase, 0.6f), Radii.Md)
                .clip(Radii.Md)
                .pressable(tplInteraction, role = Role.Button) { mode = "template" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon("PanelsTopLeft", c.textMuted, size = 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(t("modals.createServer.importTemplate"), style = Nx.type.body.copy(fontWeight = FontWeight(500)), color = c.text)
                Txt(t("modals.createServer.importTemplateDesc"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)), color = c.textMuted)
            }
            Icon("ChevronRight", c.textMuted, size = 18.dp)
        }
        Txt(t("modals.createServer.byCreatingAServerYouAgree"), style = Nx.type.hint, color = c.textMuted)
    }
}

// ---------------------------------------------------------------------------
// CustomStatusModal

private data class ClearChoice(val label: String, val ms: Long?)

@Composable
internal fun CustomStatusSheet(layer: Layer, close: () -> Unit) {
    val c = Nx.colors
    val me = Nexa.users.me.value
    val presence = me?.let { Nexa.presence.byId.value[it.id] }
    var text by remember { mutableStateOf(presence?.statusText ?: "") }
    var emoji by remember { mutableStateOf(presence?.statusEmoji ?: "") }
    // a status that clears itself keeps doing so unless told otherwise
    val until = remember { presence?.statusExpiresAt?.takeIf { it > System.currentTimeMillis() } }
    val choices = buildList {
        if (until != null) add(ClearChoice(t("modals.customStatus.keepUntil", "time" to Time.timestamp(until)), -2))
        add(ClearChoice(t("modals.customStatus.donTClear"), null))
        add(ClearChoice(t("modals.customStatus.30Minutes"), 30 * 60_000L))
        add(ClearChoice(t("modals.customStatus.1Hour"), 60 * 60_000L))
        add(ClearChoice(t("modals.customStatus.4Hours"), 4 * 60 * 60_000L))
        add(ClearChoice(t("modals.customStatus.today"), -1))
    }
    var clear by remember { mutableIntStateOf(0) }
    fun save() {
        val m = me ?: return
        val opt = choices.getOrNull(clear)
        val expires: Long? = when (opt?.ms) {
            -2L -> until
            -1L -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
            null -> null
            else -> System.currentTimeMillis() + opt.ms
        }
        val words = text.trim().take(128)
        val em = emoji.trim().take(64)
        Nexa.session.send("presence", if (words.isNotEmpty() || em.isNotEmpty()) buildJsonObject {
            put("status_text", words)
            put("status_emoji", em)
            if (expires != null) put("status_expires_at", expires) else put("status_expires_at", JsonNull)
        } else buildJsonObject { put("clear_status", true) })
        Nexa.presence.set(Presence(m.id, presence?.status ?: "online", words, em, expires, presence?.activities))
        close()
    }
    Sheet(layer, title = t("modals.customStatus.setACustomStatus"), maxWidth = 440.dp, footer = {
        FooterButtons(t("chat.editMessage.save"), ::save, close)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(t("modals.customStatus.emoji"), style = Nx.type.label, color = c.textSubtle)
                Box {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(44.dp)
                            .sunken(Radii.Xs, c)
                            .clip(Radii.Xs)
                            .pressable(remember { MutableInteractionSource() }) {
                                // a server's or a pack's own emoji goes as its token, which every app draws as its picture
                                fish.nexa.ui.pickers.Pickers.open(fish.nexa.ui.pickers.Pickers.Opener(listOf(fish.nexa.ui.pickers.Pickers.Tab.EMOJI), null, closeOnEmoji = true, onEmoji = { p -> emoji = p.text.trim() }))
                            }
                            .semantics { contentDescription = if (emoji.isNotEmpty()) t("modals.customStatus.changeTheEmoji") else t("pickers.emojiPicker.pickAnEmoji") },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (emoji.isNotEmpty()) fish.nexa.ui.profile.StatusEmojiImage(emoji, 24.dp) else Icon("SmilePlus", c.textMuted, size = 20.dp)
                    }
                    if (emoji.isNotEmpty()) Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(8.dp, (-8).dp)
                            .size(24.dp)
                            .shadowLow(CircleShape, c)
                            .background(c.bgRaised, CircleShape)
                            .clip(CircleShape)
                            .pressable(remember { MutableInteractionSource() }) { emoji = "" }
                            .semantics { contentDescription = t("modals.customStatus.removeTheEmoji") },
                        contentAlignment = Alignment.Center,
                    ) { Icon("X", c.textMuted, size = 12.dp) }
                }
            }
            NxTextField(
                text, { text = it.take(128) },
                modifier = Modifier.weight(1f),
                label = t("modals.customStatus.whatSCookin", "name" to (me?.displayName ?: "")),
                labelContent = { fish.nexa.ui.chat.PersonText(me?.id, t("modals.customStatus.whatSCookin", "name" to fish.nexa.ui.chat.PERSON_SLOT), me?.displayName ?: "", Nx.type.label, c.textSubtle) },
                placeholder = t("modals.customStatus.supportHasArrived"),
                imeAction = ImeAction.Done,
                onIme = ::save,
                capitalize = true,
            )
        }
        SelectField(t("modals.customStatus.clearAfter"), choices.mapIndexed { i, ch -> i to ch.label }, clear) { clear = it }
        if (!presence?.statusText.isNullOrEmpty() || !presence?.statusEmoji.isNullOrEmpty()) NxButton(t("modals.customStatus.clearStatus"), {
            text = ""
            emoji = ""
        }, kind = ButtonKind.LINK, inline = true)
    }
}

// ---------------------------------------------------------------------------
// SubchatModal: a subchat's name, and the password that keeps it shut

private const val SUBCHAT_NAME_MAX = 48

private fun passwordProblem(pw: String): String? = when {
    pw.length < 4 -> t("appLock.useAtLeastCharacters", "count" to 4)
    pw.length > 128 -> t("appLock.thatIsTooLong")
    else -> null
}

/** Stretches a new password on this device; only what comes out of it is sent. */
private suspend fun newLock(password: String): JsonObject = withContext(Dispatchers.Default) {
    val salt = Bytes.random(16)
    val p = Kdf.DEFAULT
    val master = Kdf.deriveMaster(password, salt, p)
    buildJsonObject {
        put("password_key", Bytes.b64(Kdf.authKey(master)))
        put("password_salt", Bytes.b64(salt))
        putJsonObject("password_params") {
            put("m", p.m)
            put("t", p.t)
            put("p", p.p)
            put("v", p.v)
        }
    }
}

@Composable
internal fun SubchatSheet(layer: Layer, parentId: String, channelId: String?, close: () -> Unit) {
    val c = Nx.colors
    val channel = channelId?.let { Nexa.channels.get(it) }
    val editing = channel != null
    var name by remember { mutableStateOf(channel?.name ?: "") }
    var wantsPassword by remember { mutableStateOf(channel?.locked == true) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val changingPassword = wantsPassword && (channel?.locked != true || password.isNotEmpty())
    val removingPassword = editing && channel?.locked == true && !wantsPassword
    fun save() {
        if (busy) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = t("modals.subchat.giveTheSubchatAName")
            return
        }
        if (changingPassword) {
            passwordProblem(password)?.let {
                error = it
                return
            }
            if (password != confirm) {
                error = t("modals.subchat.thoseTwoPasswordsAreDifferent")
                return
            }
        }
        busy = true
        error = null
        scope.launch {
            try {
                val lock: JsonObject? = when {
                    changingPassword -> newLock(password)
                    removingPassword -> buildJsonObject { put("password_remove", true) }
                    else -> null
                }
                val body = JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(trimmed)) + (lock ?: JsonObject(emptyMap())))
                val next = if (channel != null) Nexa.api.request("PATCH", "/channels/${channel.id}", Channel.serializer(), body)
                else Nexa.api.request("POST", "/channels/$parentId/subchats", Channel.serializer(), body)
                Nexa.channels.upsert(listOf(next))
                close()
            } catch (e: Exception) {
                error = describeError(e, t("modals.subchat.thatCouldNotBeSaved"))
            } finally {
                busy = false
            }
        }
    }
    Sheet(layer, title = if (editing) t("chat.subchat.editSubchat") else t("modals.subchat.newSubchat"), maxWidth = 440.dp, footer = {
        FooterButtons(if (editing) t("chat.editMessage.save") else t("modals.subchat.createSubchat"), ::save, close, enabled = name.isNotBlank(), busy = busy)
    }) {
        NxTextField(name, { name = it.take(SUBCHAT_NAME_MAX) }, label = t("modals.subchat.name"), required = true, imeAction = ImeAction.Done, onIme = ::save, capitalize = true)
        ToggleRow(wantsPassword, {
            wantsPassword = it
            password = ""
            confirm = ""
        }, t("modals.subchat.askForAPassword"), t("modals.subchat.everyoneInTheSubchatTypesIt"))
        if (wantsPassword) {
            NxTextField(
                password, { password = it },
                label = if (channel?.locked == true) t("settings.account.newPassword") else t("chat.lockedChannel.password"),
                placeholder = if (channel?.locked == true) t("modals.subchat.leaveEmptyToKeepTheCurrent") else null,
                password = true,
                imeAction = if (password.isNotEmpty()) ImeAction.Next else ImeAction.Done,
                onIme = if (password.isEmpty()) ({ save() }) else null,
            )
            if (password.isNotEmpty()) NxTextField(confirm, { confirm = it }, label = t("modals.subchat.typeItAgain"), password = true, imeAction = ImeAction.Done, onIme = ::save)
        }
        error?.let { Txt(it, style = Nx.type.bodySmall, color = c.textDanger) }
    }
}
