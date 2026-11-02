package fish.nexa.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.input.ImeAction
import fish.nexa.ui.components.NxTextField
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.data.People
import fish.nexa.data.TypingStyle
import fish.nexa.media.Media
import fish.nexa.net.Member
import fish.nexa.net.wireJson
import fish.nexa.ui.Icon
import fish.nexa.ui.chat.RoleLooks
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.ServerIcon
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.TabStrip
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.profile.FullProfileCard
import fish.nexa.ui.profile.ProfileDesign
import fish.nexa.ui.profile.ProfilePart
import fish.nexa.ui.profile.ProfilePopoutCard
import fish.nexa.ui.profile.ProfileTab
import fish.nexa.ui.profile.ProfileTheme
import fish.nexa.ui.profile.WidgetDesign
import fish.nexa.ui.profile.Widgets
import fish.nexa.ui.server.CropResult
import fish.nexa.ui.server.ServerApi
import fish.nexa.ui.server.openImageCrop
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.rgbColor
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which designer Settings, Profiles is showing: the main profile's ("main") or a server's ("server"). */
object ProfileScope {
    var scope by mutableStateOf("main")
}

/**
 * The server designer's form (ServerProfileDesigner.tsx): the words, colours
 * and widgets for one server that wait for Save Changes. Pictures change at
 * once, as they do on the main profile. Whatever is left empty is the main
 * profile's.
 */
object ServerProfileForm {
    var serverId by mutableStateOf<String?>(null)
    var nick by mutableStateOf("")
    var pronouns by mutableStateOf("")
    val aboutState = TextFieldState()
    val about: String get() = aboutState.text.toString()
    var accent by mutableStateOf<Long?>(null)
    var accent2 by mutableStateOf<Long?>(null)
    /** This server's own widgets; null while the main profile's show here. */
    var widgets by mutableStateOf<List<Widgets.Widget>?>(null)
    var savedWidgets by mutableStateOf<List<Widgets.Widget>?>(null)
    /** The widgets (this server's and the main profile's) have come. */
    var widgetsLoaded by mutableStateOf(false)
    var mainWidgets by mutableStateOf<List<Widgets.Widget>>(emptyList())
    var widgetsFailed by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var uploading by mutableStateOf<String?>(null)

    fun take(m: Member) {
        nick = m.nickname
        pronouns = m.pronouns
        aboutState.setTextAndPlaceCursorAtEnd(AboutMe.toEdit(m.bio))
        accent = m.accentColor
        accent2 = m.accentColor2
    }

    /** Starts on a server: what was being edited for another never leaks into it. */
    fun begin(serverId: String, m: Member) {
        this.serverId = serverId
        take(m)
        widgets = null
        savedWidgets = null
        widgetsLoaded = false
        mainWidgets = emptyList()
        widgetsFailed = false
    }

    private fun wordsChanged(m: Member) = nick != m.nickname || pronouns != m.pronouns || about != AboutMe.toEdit(m.bio)
    fun themeChanged(m: Member) = accent != m.accentColor || (accent != null && accent2 != m.accentColor2)
    fun widgetsChanged() = widgetsLoaded && widgets != savedWidgets
    fun dirty(m: Member) = wordsChanged(m) || themeChanged(m) || widgetsChanged()

    fun reset(m: Member) {
        take(m)
        if (widgetsLoaded) widgets = savedWidgets
    }

    suspend fun loadWidgets(meId: String, serverId: String) {
        widgetsFailed = false
        try {
            val main = People.profile(meId)
            val here = People.profile(meId, serverId)
            if (this.serverId != serverId) return
            mainWidgets = Widgets.read(main.widgets)
            val own = if (here.serverWidgets) Widgets.read(here.widgets) else null
            widgets = own
            savedWidgets = own
            widgetsLoaded = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            widgetsFailed = true
        }
    }

    suspend fun save(serverId: String, m: Member) {
        saving = true
        try {
            val widgetsNow = widgetsChanged()
            val body = buildJsonObject {
                if (nick != m.nickname) nick.trim().let { if (it.isEmpty()) put("nick", JsonNull) else put("nick", it) }
                if (pronouns != m.pronouns) put("pronouns", pronouns.trim())
                if (about != AboutMe.toEdit(m.bio)) put("bio", AboutMe.toSave(about).trim())
                if (themeChanged(m)) {
                    put("accent_color", accent?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("accent_color_2", if (accent != null) accent2?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
                }
                if (widgetsNow) put("widgets", widgets?.let { list -> JsonArray(list.map { Widgets.toJson(it) }) } ?: JsonNull)
            }
            val res = Nexa.api.request("PATCH", "/servers/$serverId/members/@me", Member.serializer(), body)
            Nexa.servers.upsertMember(res)
            // the server cleans what it keeps, so its copy is the one shown from now on
            take(res)
            if (widgetsNow) Nexa.users.meId?.let { loadWidgets(it, serverId) }
            Nexa.ui.toast(t("serverProfile.saved"), "success")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Nexa.ui.toast(describeError(e, t("serverProfile.uploadFailed")), "error")
        } finally {
            saving = false
        }
    }

    /** Puts up a picture or banner for this server only, cut on the server to the part framed. */
    suspend fun upload(serverId: String, kind: String, r: CropResult) {
        uploading = kind
        try {
            val area = r.crop?.let { "?x=${it.x}&y=${it.y}&w=${it.w}&h=${it.h}" } ?: ""
            val res = ServerApi.upload("PUT", "/servers/$serverId/members/@me/$kind$area", r.bytes, r.type)
            Nexa.servers.upsertMember(wireJson.decodeFromJsonElement(Member.serializer(), res))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Nexa.ui.toast(describeError(e, t("serverProfile.uploadFailed")), "error")
        } finally {
            uploading = null
        }
    }

    /** Takes this server's picture or banner away; the main profile's shows there again. */
    suspend fun remove(serverId: String, kind: String) {
        uploading = kind
        try {
            Nexa.servers.upsertMember(Nexa.api.request("DELETE", "/servers/$serverId/members/@me/$kind", Member.serializer()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Nexa.ui.toast(describeError(e, t("serverProfile.uploadFailed")), "error")
        } finally {
            uploading = null
        }
    }
}

/**
 * Settings, Profiles, Server Profiles: the profile designer for one server.
 * The same stage as the main profile's, drawing the profile as people in
 * that server see it, with every part that can differ there: the nickname,
 * pronouns, picture, banner, theme, About Me and widgets.
 */
@Composable
internal fun ServerProfileDesigner(serverId: String?, onServer: (String) -> Unit) {
    val c = Nx.colors
    val order by Nexa.servers.order.collectAsState()
    val byId by Nexa.servers.byId.collectAsState()
    val servers = order.filter { byId[it] != null }
    val current = serverId?.takeIf { byId[it] != null } ?: servers.firstOrNull()
    if (current == null) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon("Server", c.textMuted, size = 28.dp)
            Txt(t("serverProfile.designer.noServers"), style = Nx.type.title.copy(fontSize = Nx.type.sp(18f), fontWeight = FontWeight(700)), color = c.textStrong)
            Txt(t("serverProfile.designer.noServersHint"), style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    // a server of its own each: what was being edited for one never leaks into the next
    key(current) { Designer(current, servers, onServer) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Designer(serverId: String, servers: List<String>, onServer: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meState by Nexa.users.me.collectAsState()
    val byId by Nexa.servers.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val me = meState ?: return
    val st = byId[serverId] ?: return
    val member = st.me
    val f = ServerProfileForm
    LaunchedEffect(serverId) {
        if (f.serverId != serverId) f.begin(serverId, member)
        f.loadWidgets(me.id, serverId)
    }
    var view by remember { mutableStateOf("card") }
    var tab by remember { mutableStateOf<ProfileTab?>(null) }
    var tabNonce by remember { mutableIntStateOf(0) }
    val host = LocalAnchors.current
    val reduced = Nx.motion.reduced
    // the first frame, before the form has been moved to this server
    if (f.serverId != serverId) return
    fun showTab(next: ProfileTab) {
        tab = next
        tabNonce += 1
    }
    val s = Nexa.servers
    val canNick = s.canI(st, P.CHANGE_NICKNAME) || s.canI(st, P.MANAGE_NICKNAMES)
    val server = st.server

    var picking by remember { mutableStateOf("avatar") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kind = picking
        scope.launch {
            val file = runCatching { ServerApi.read(context, uri) }.getOrNull() ?: return@launch
            if (file.bytes.size > 10 * 1024 * 1024) {
                Nexa.ui.toast(t("settings.profile.imagesAreLimitedTo10Mb"), "error")
                return@launch
            }
            if (kind == "avatar") openImageCrop(file, 1f, round = true, title = t("settings.profile.cropYourAvatar"), maxSize = 1024, serverCrop = true) { r -> scope.launch { f.upload(serverId, "avatar", r) } }
            else openImageCrop(file, 600f / 240f, round = false, title = t("settings.profile.cropYourBanner"), maxSize = 1920, serverCrop = true) { r -> scope.launch { f.upload(serverId, "banner", r) } }
        }
    }
    fun choose(kind: String) {
        picking = kind
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // pressing a part of the preview: the form goes to what changes it
    fun open(part: String) {
        if (part == "name" && !canNick) return Nexa.ui.toast(t("serverProfile.designer.nickLocked"), "info")
        scope.launch {
            delay(40)
            jumpToField(host, part, scope, reduced)
        }
    }

    // widgets changed here become this server's own, starting from the ones shown
    fun changeWidgets(fn: (List<Widgets.Widget>) -> List<Widgets.Widget>) {
        f.widgets = fn(f.widgets ?: f.mainWidgets)
    }
    val shownWidgets = f.widgets ?: f.mainWidgets
    val widgetTools = if (!f.widgetsLoaded) null else WidgetDesign(
        edit = { id -> shownWidgets.firstOrNull { it.id == id }?.let { w -> openWidgetEditor(w, isNew = false) { done -> changeWidgets { list -> list.map { if (it.id == done.id) done else it } } } } },
        move = { id, delta ->
            changeWidgets { list ->
                val next = list.toMutableList()
                val at = next.indexOfFirst { it.id == id }
                val to = at + delta
                if (at >= 0 && to in next.indices) next.add(to, next.removeAt(at))
                next
            }
        },
        remove = { id -> changeWidgets { list -> list.filter { it.id != id } } },
        feature = { id -> changeWidgets { list -> list.firstOrNull { it.id == id }?.let { w -> listOf(w) + list.filter { it.id != id } } ?: list } },
        add = {
            openAddWidget(shownWidgets) { fresh ->
                openWidgetEditor(fresh, isNew = true) { done ->
                    changeWidgets { it + done }
                    if (view == "full") showTab(ProfileTab.WIDGETS)
                }
            }
        },
    )
    val design = ProfileDesign(
        edit = { part ->
            when (part) {
                ProfilePart.BANNER -> open("banner")
                ProfilePart.AVATAR -> open("avatar")
                ProfilePart.NAME -> open("name")
                ProfilePart.PRONOUNS -> open("pronouns")
                ProfilePart.ABOUT -> open("about")
                // a status and a server tag are the account's, the same in every server
                ProfilePart.STATUS, ProfilePart.TAG -> Unit
            }
        },
        fixed = if (canNick) setOf(ProfilePart.STATUS, ProfilePart.TAG) else setOf(ProfilePart.STATUS, ProfilePart.TAG, ProfilePart.NAME),
    )
    // whatever is left empty here is the main profile's, and is drawn so
    val aboutOut = AboutMe.toSave(f.about).trim()
    val shownName = f.nick.trim().ifEmpty { me.displayName }
    val preview = me.asPublic().copy(
        displayName = shownName,
        avatarId = member.avatarId ?: me.avatarId,
        bannerId = member.bannerId ?: me.bannerId,
        pronouns = f.pronouns.trim().ifEmpty { me.pronouns },
        aboutMe = aboutOut.ifEmpty { me.aboutMe },
        accentColor = if (f.accent != null) f.accent else me.accentColor,
        accentColor2 = if (f.accent != null) f.accent2 else me.accentColor2,
    )
    val theme = ProfileTheme.of(preview.accentColor, preview.accentColor2)
    val tint = ProfileTheme.rememberDominant(if (theme != null) null else Media.bannerUrl(preview.bannerId, 64, 1f) ?: Media.avatarUrl(preview.avatarId, 64, 1f))
    val glow = theme?.top ?: tint ?: c.brand
    val hasOwn = member.nickname.isNotEmpty() || member.avatarId != null || member.bannerId != null || member.bio.isNotEmpty() ||
        member.pronouns.isNotEmpty() || member.accentColor != null || f.savedWidgets != null

    // everything set for this server goes, and the main profile shows there again
    fun useMain() = confirm(
        t("serverProfile.designer.useMainTitle", "name" to server.name),
        t("serverProfile.designer.useMainBody"),
        t("serverProfile.designer.useMain"),
        danger = false,
    ) {
        var m = Nexa.api.request("PATCH", "/servers/$serverId/members/@me", Member.serializer(), buildJsonObject {
            if (canNick) put("nick", JsonNull)
            put("pronouns", "")
            put("bio", "")
            put("accent_color", JsonNull)
            put("accent_color_2", JsonNull)
            put("widgets", JsonNull)
        })
        if (m.avatarId != null) m = Nexa.api.request("DELETE", "/servers/$serverId/members/@me/avatar", Member.serializer())
        if (m.bannerId != null) m = Nexa.api.request("DELETE", "/servers/$serverId/members/@me/banner", Member.serializer())
        Nexa.servers.upsertMember(m)
        f.take(m)
        f.widgets = null
        f.savedWidgets = null
        Nexa.ui.toast(t("serverProfile.designer.useMainDone", "name" to server.name), "success")
    }

    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ServerPicker(serverId, servers, onServer, Modifier.weight(1f, fill = false))
        if (hasOwn) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            NxButton(t("serverProfile.designer.useMain"), ::useMain, kind = ButtonKind.GHOST, size = ButtonSize.SM)
        }
    }
    Txt(
        t("serverProfile.designer.introForm", "name" to server.name),
        style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f), lineHeight = Nx.type.sp(21f)),
        color = c.textMuted,
        modifier = Modifier.padding(bottom = 18.dp),
    )
    val density = LocalDensity.current.density
    val summaryStyle = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(650))
    androidx.compose.runtime.SideEffect {
        DesignerState.summary = {
            Avatar(me.id, shownName, preview.avatarId, 28.dp)
            Txt(shownName, style = summaryStyle, color = c.textStrong, maxLines = 1)
        }
    }

    DesignerPreview {
        TabStrip(
            listOf("card" to t("designer.view.card"), "full" to t("designer.view.full"), "chat" to t("designer.view.chat")),
            view, t("designer.view.label"), tabHeight = 32.dp,
        ) { view = it }
        Stage(glow) {
            when (view) {
                "full" -> FullProfileCard(preview, tab, null, close = {}, design = design, previewWidgets = shownWidgets, embedded = true, tabNonce = tabNonce)
                "card" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .widthIn(max = 320.dp)
                            .fillMaxWidth()
                            .ring(Radii.Lg, c.panelEdge)
                            .clip(Radii.Lg),
                    ) { ProfilePopoutCard(preview, null, close = {}, design = design, previewWidgets = shownWidgets) }
                }
                else -> {
                    // the first text channel the server has, for the conversation's header
                    val channel = channels.values.filter { it.serverId == serverId && it.isText }.minByOrNull { it.position }?.name ?: "general"
                    ChatPreview(me, shownName, TypingStyle.read(me.typingStyle), avatarId = preview.avatarId, channel = channel, look = RoleLooks.of(st, me.id))
                }
            }
        }
        Txt(
            t("serverProfile.caption.$view", "name" to server.name),
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(18.2f)),
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    Spacer(Modifier.height(28.dp))

    Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
        FormSection(t("designer.group.look")) {
            FormField("avatar", null, t("serverProfile.picture"), hint = if (member.avatarId != null) t("designer.imageHint") else t("serverProfile.designer.pictureMainHint")) {
                PictureRow({ Avatar(me.id, shownName, preview.avatarId, 56.dp) }) {
                    NxButton(t("serverProfile.changePicture"), { choose("avatar") }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "ImagePlus", loading = f.uploading == "avatar")
                    if (member.avatarId != null) NxButton(t("serverProfile.useOwnPicture"), { scope.launch { f.remove(serverId, "avatar") } }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
                }
            }
            FormField("banner", null, t("serverProfile.banner"), hint = if (member.bannerId != null) t("designer.imageHint") else t("serverProfile.designer.bannerMainHint")) {
                PictureRow({ BannerThumb(Media.bannerUrl(preview.bannerId, 340, density), theme, preview.accentColor, me.id) }) {
                    NxButton(if (member.bannerId != null) t("settings.profile.changeBanner") else t("designer.addBanner"), { choose("banner") }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "ImagePlus", loading = f.uploading == "banner")
                    if (member.bannerId != null) NxButton(t("serverProfile.useOwnBanner"), { scope.launch { f.remove(serverId, "banner") } }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
                }
            }
            FormField("colours", null, t("settings.profile.profileTheme"), hint = t("serverProfile.designer.themeHint")) { ColoursFields(f.accent, f.accent2, { f.accent = it }, { f.accent2 = it }) }
        }
        FormSection(t("designer.group.name")) {
            FormField("name", null, t("modals.serverProfile.nickname"), hint = if (canNick) null else t("serverProfile.designer.nickLocked")) {
                NxTextField(f.nick, { f.nick = it.take(32) }, Modifier.fillMaxWidth().semantics { contentDescription = t("modals.serverProfile.nickname") }, placeholder = me.displayName, enabled = canNick, imeAction = ImeAction.Next)
            }
            FormField("pronouns", null, t("settings.profile.pronouns")) {
                NxTextField(f.pronouns, { f.pronouns = it.take(40) }, Modifier.fillMaxWidth().semantics { contentDescription = t("settings.profile.pronouns") }, placeholder = me.pronouns.ifEmpty { t("settings.profile.addYourPronouns") }, imeAction = ImeAction.Done)
            }
        }
        FormSection(t("designer.group.about")) {
            FormField("about", null, t("settings.profile.aboutMe")) {
                AboutFields(f.aboutState, placeholder = me.aboutMe.trim().lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() } ?: t("settings.profile.tellPeopleAboutYourself"))
            }
            FormField(
                "widgets", null, t("widgets.title"),
                hint = if (f.widgets == null) t("serverProfile.designer.widgetsMainHint") else null,
                aside = if (f.widgets != null && f.widgetsLoaded) ({
                    Txt(
                        t("serverProfile.designer.mainWidgets"),
                        style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)),
                        color = c.textBrand,
                        modifier = Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { f.widgets = null }.padding(vertical = 4.dp),
                    )
                }) else null,
            ) {
                if (f.widgetsFailed) WidgetsFailed { scope.launch { f.loadWidgets(me.id, serverId) } }
                else WidgetList(shownWidgets, widgetTools)
            }
        }
    }
    if (f.uploading != null) Row(
        Modifier.padding(start = 4.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spinner(c.textMuted, size = 14.dp)
        Txt(t("common.working"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
    }
}

/** The server being designed: its icon and name, pressed to pick another. */
@Composable
private fun ServerPicker(serverId: String, servers: List<String>, onServer: (String) -> Unit, modifier: Modifier) {
    val c = Nx.colors
    val byId by Nexa.servers.byId.collectAsState()
    val server = byId[serverId]?.server ?: return
    val shape = Radii.Sm
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = 44.dp)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .ring(shape, if (pressed) c.borderStrong else c.borderSubtle)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(interaction) {
                openMenu(
                    servers.map { id ->
                        MenuItem.Action(byId[id]?.server?.name ?: id, checked = id == serverId) { Unsaved.unlessUnsaved { onServer(id) } }
                    },
                    title = t("serverProfile.designer.choose"),
                )
            }
            .semantics { contentDescription = t("serverProfile.designer.chooseNamed", "name" to server.name) }
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ServerIcon(server.id, server.name, server.iconId, 28.dp, radius = 8.dp)
        Txt(server.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), fontWeight = FontWeight(650)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Icon("ChevronDown", c.textMuted, size = 16.dp)
    }
}

/** The server designer's save bar: words, colours and widgets wait for it. */
@Composable
fun BoxScope.ServerProfileSaveBar() {
    val byId by Nexa.servers.byId.collectAsState()
    val f = ServerProfileForm
    val serverId = f.serverId ?: return
    val member = byId[serverId]?.me ?: return
    val scope = rememberCoroutineScope()
    SaveBar(
        dirty = f.dirty(member),
        saving = f.saving,
        onReset = { f.reset(member) },
        onSave = { scope.launch { f.save(serverId, member) } },
        saveLabel = t("settings.profile.saveChanges"),
    )
}
