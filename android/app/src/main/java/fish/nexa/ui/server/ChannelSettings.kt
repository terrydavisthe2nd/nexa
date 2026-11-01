package fish.nexa.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.data.Perms
import fish.nexa.net.Channel
import fish.nexa.net.Overwrite
import fish.nexa.net.wireJson
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxSlider
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.ToggleLine
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.pickers.Pickers
import fish.nexa.ui.shell.CHANNEL_ICONS
import fish.nexa.ui.shell.ChannelGlyph
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.shadowMedium
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.sunken
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val SLOWMODE = listOf(0, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600)

private fun slowLabel(v: Int): String = if (v == 0) t("server.channelSettings.slowmodeOff") else Time.shortDuration(v.toDouble())

/** CreateChannelModal typedChannelName: a text channel's name as it is typed, lowercase with dashes. */
fun typedChannelName(v: String): String = v.lowercase().replace(Regex("\\s+"), "-").replace(Regex("-{2,}"), "-")

/** slug: the finished name, without dashes left hanging at either end. */
fun slugChannelName(v: String): String = typedChannelName(v.trim()).trim('-')

/**
 * ChannelSettingsModal: a channel's or category's Overview, Permissions,
 * Integrations (text channels whose webhooks you manage) and Delete. It
 * closes by itself when the channel goes.
 */
object ChannelSettings {
    fun open(channelId: String, page: String? = null) {
        openSettingsLayer("channelSettings") { layer, close -> ChannelSettingsLayer(layer, channelId, page, close) }
    }
}

@Composable
private fun ChannelSettingsLayer(layer: Layer, channelId: String, initial: String?, close: () -> Unit) {
    val channels by Nexa.channels.byId.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    val ch = channels[channelId]
    LaunchedEffect(ch == null) { if (ch == null) close() }
    val serverId = ch?.serverId ?: return
    val st = servers[serverId] ?: return
    val isCat = ch.isCategory
    val isThread = ch.isThread
    val hooks = (ch.isText || ch.isAnnouncement) && Nexa.servers.canI(st, P.MANAGE_WEBHOOKS, ch.id)
    var page by remember { mutableStateOf(initial ?: "overview") }
    val sections = listOf(
        NavSection(
            (if (isCat || isThread) "" else "#") + ch.name,
            listOfNotNull(
                NavPage("overview", t("server.channelSettings.overview"), "Settings2"),
                // a thread answers to its channel's permissions
                if (isThread) null else NavPage("permissions", t("server.channelSettings.permissions"), "SlidersHorizontal"),
                if (hooks) NavPage("integrations", t("integrations.title"), "Webhook") else null,
            ),
        ),
        NavSection(null, listOf(NavPage("delete", if (isCat) t("server.channelSettings.deleteCategory") else if (isThread) t("threads.bar.delete") else t("server.channelSettings.deleteChannel"), "Trash2", danger = true))),
    )
    SettingsFrame(layer, t("server.channelSettings.channelSettings"), sections, page, { page = it }, startOnList = initial == null, close = close) { id ->
        when (id) {
            "permissions" -> if (isThread) ChannelOverviewPage(ch) else ChannelPermissionsPage(ch, serverId)
            "integrations" -> if (hooks) IntegrationsPage(serverId, ch.id) else ChannelOverviewPage(ch)
            "delete" -> DeleteChannelPage(ch) {
                close()
                Nav.go(Route.Server(serverId), replace = true)
            }
            else -> ChannelOverviewPage(ch)
        }
    }
}

/** A forum tag as it is being edited: one not saved yet has no id. */
private data class TagEdit(val id: String?, val name: String, val emoji: String, val moderated: Boolean, val key: String = id ?: java.util.UUID.randomUUID().toString())

private const val MAX_FORUM_TAGS = 20

private fun sameTags(a: List<TagEdit>, b: List<TagEdit>) =
    a.size == b.size && a.zip(b).all { (x, y) -> x.id == y.id && x.name == y.name && x.emoji == y.emoji && x.moderated == y.moderated }

/**
 * The Overview: only what was typed here is a draft; everything else
 * follows the channel, so saving never sends back values from before
 * somebody else's change. A text channel can be made an announcement
 * channel; a forum has its guidelines, tags, layout and order; a thread its
 * name, when it closes, whether it is locked and its tags.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChannelOverviewPage(ch: Channel) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val channels by Nexa.channels.byId.collectAsState()
    val thread = ch.isThread
    // a media channel is a forum of pictures and videos, always shown as a gallery
    val forum = ch.isForum
    val media = ch.isMedia
    val link = ch.isLink
    val textLike = ch.isText || ch.isAnnouncement
    // names are written as text channels' are: lower case, dashes for spaces
    val slugged = textLike || forum
    val parent = if (thread) ch.parentId?.let { channels[it] } else null
    val postTags = if (parent?.isForum == true) parent.forum?.tags.orEmpty() else emptyList()
    val mayManageThreads = thread && fish.nexa.ui.threads.ThreadsUi.canManage(ch)
    var nameEdit by remember { mutableStateOf<String?>(null) }
    var topicEdit by remember { mutableStateOf<String?>(null) }
    var slowEdit by remember { mutableStateOf<Int?>(null) }
    var nsfwEdit by remember { mutableStateOf<Boolean?>(null) }
    var limitEdit by remember { mutableStateOf<Int?>(null) }
    var glyphEdit by remember { mutableStateOf<String?>(null) }
    var announceEdit by remember { mutableStateOf<Boolean?>(null) }
    var defaultArchiveEdit by remember { mutableStateOf<Int?>(null) }
    var archiveEdit by remember { mutableStateOf<Int?>(null) }
    var lockedEdit by remember { mutableStateOf<Boolean?>(null) }
    var appliedEdit by remember { mutableStateOf<List<String>?>(null) }
    var tagsEdit by remember { mutableStateOf<List<TagEdit>?>(null) }
    var layoutEdit by remember { mutableStateOf<String?>(null) }
    var sortEdit by remember { mutableStateOf<String?>(null) }
    var requireTagEdit by remember { mutableStateOf<Boolean?>(null) }
    var hideDownloadsEdit by remember { mutableStateOf<Boolean?>(null) }
    var urlEdit by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val savedTags = remember(ch.forum?.tags) { ch.forum?.tags.orEmpty().map { TagEdit(it.id, it.name, it.emoji, it.moderated) } }
    val name = nameEdit ?: ch.name
    val topic = topicEdit ?: ch.topic
    val slow = slowEdit ?: ch.slowmodeSeconds
    val nsfw = nsfwEdit ?: ch.nsfw
    val limit = limitEdit ?: ch.userLimit
    // an uploaded picture is edited as PICTURE: picking anything else and saving puts it away
    val savedGlyph = if (ch.iconId != null) PICTURE else ch.glyph ?: ""
    val glyph = glyphEdit ?: savedGlyph
    val announce = announceEdit ?: ch.isAnnouncement
    val defaultArchive = defaultArchiveEdit ?: ch.defaultAutoArchive ?: 4320
    val archive = archiveEdit ?: ch.thread?.autoArchive ?: 4320
    val locked = lockedEdit ?: (ch.thread?.locked == true)
    val applied = appliedEdit ?: ch.thread?.appliedTags.orEmpty()
    val tags = tagsEdit ?: savedTags
    val layout = layoutEdit ?: ch.forum?.layout ?: "list"
    val sort = sortEdit ?: ch.forum?.sort ?: "activity"
    val requireTag = requireTagEdit ?: (ch.forum?.requireTag == true)
    val hideDownloads = hideDownloadsEdit ?: (ch.forum?.hideDownloads == true)
    val url = urlEdit ?: ch.url ?: ""
    val forumDirty = forum && (!sameTags(tags, savedTags) || layout != (ch.forum?.layout ?: "list") || sort != (ch.forum?.sort ?: "activity") || requireTag != (ch.forum?.requireTag == true) ||
        (media && hideDownloads != (ch.forum?.hideDownloads == true)))
    val dirty = name != ch.name || topic != ch.topic || slow != ch.slowmodeSeconds || nsfw != ch.nsfw || limit != ch.userLimit || glyph != savedGlyph ||
        (textLike && announce != ch.isAnnouncement) ||
        ((textLike || forum) && defaultArchive != (ch.defaultAutoArchive ?: 4320)) ||
        (thread && (archive != (ch.thread?.autoArchive ?: 4320) || locked != (ch.thread?.locked == true) || applied != ch.thread?.appliedTags.orEmpty())) ||
        (link && url != (ch.url ?: "")) ||
        forumDirty
    fun reset() {
        nameEdit = null; topicEdit = null; slowEdit = null; nsfwEdit = null; limitEdit = null; glyphEdit = null
        announceEdit = null; defaultArchiveEdit = null; archiveEdit = null; lockedEdit = null; appliedEdit = null
        tagsEdit = null; layoutEdit = null; sortEdit = null; requireTagEdit = null; hideDownloadsEdit = null; urlEdit = null
    }
    fun save() {
        val urlChanged = link && url != (ch.url ?: "")
        val dest = if (urlChanged) fish.nexa.ui.shell.LinkChannels.destination(url) else null
        if (urlChanged && dest == null) {
            Nexa.ui.toast(t("channelLink.badAddress"), "error")
            return
        }
        busy = true
        scope.launch {
            try {
                val body = buildJsonObject {
                    if (dest != null) put("url", dest.href)
                    if (name != ch.name) put("name", if (slugged) slugChannelName(name) else name.trim())
                    if (topic != ch.topic) put("topic", topic)
                    if (slow != ch.slowmodeSeconds) put("slowmode_seconds", slow)
                    if (nsfw != ch.nsfw) put("nsfw", nsfw)
                    if (limit != ch.userLimit) put("user_limit", limit)
                    if (glyph != savedGlyph && glyph != PICTURE) put("glyph", glyph)
                    if (textLike && announce != ch.isAnnouncement) put("type", if (announce) "announcement" else "text")
                    if ((textLike || forum) && defaultArchive != (ch.defaultAutoArchive ?: 4320)) put("default_auto_archive", defaultArchive)
                    if (thread) {
                        if (archive != (ch.thread?.autoArchive ?: 4320)) put("auto_archive", archive)
                        if (locked != (ch.thread?.locked == true)) put("locked", locked)
                        if (applied != ch.thread?.appliedTags.orEmpty()) put("applied_tags", kotlinx.serialization.json.JsonArray(applied.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    }
                    if (forumDirty) put("forum", buildJsonObject {
                        put("tags", kotlinx.serialization.json.JsonArray(tags.filter { it.name.isNotBlank() }.map { tg ->
                            buildJsonObject {
                                tg.id?.let { put("id", it) }
                                put("name", tg.name.trim())
                                put("emoji", tg.emoji)
                                put("moderated", tg.moderated)
                            }
                        }))
                        put("layout", if (media) "gallery" else layout)
                        put("sort", sort)
                        put("require_tag", requireTag)
                        if (media) put("hide_downloads", hideDownloads)
                    })
                }
                val saved = Nexa.api.request("PATCH", "/channels/${ch.id}", Channel.serializer(), body)
                Nexa.channels.upsert(listOf(saved))
                // the server tidies names and topics, so what it kept is shown
                reset()
                Nexa.ui.toast(t("server.channelSettings.channelSaved"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("server.channelSettings.couldNotSaveTheChannel"), "error")
            } finally {
                busy = false
            }
        }
    }
    val nameLabel = if (ch.isCategory) t("server.channelSettings.categoryName") else if (thread) t("threads.settings.name") else t("server.channelSettings.channelName")
    SettingsPage(SaveState(dirty, busy, ::reset, ::save)) {
        PageTitle(t("server.channelSettings.overview"))
        Form {
            NxTextField(
                name, { v -> nameEdit = (if (slugged) typedChannelName(v) else v).take(100) },
                label = nameLabel,
                required = true,
                capitalize = !slugged,
                leading = {
                    // what the channel list will show, as it is being picked below
                    ChannelGlyph(ch.copy(type = if (textLike) (if (announce) "announcement" else "text") else ch.type, glyph = glyph.takeIf { it != PICTURE }, iconId = ch.iconId.takeIf { glyph == PICTURE }), 16.dp, Nx.colors.textMuted, cut = Nx.colors.bgInput)
                },
            )
            if (link) NxTextField(
                url, { urlEdit = it.take(1024) },
                label = t("channelLink.address"),
                required = true,
                placeholder = "https://",
                hint = t("channelLink.addressHint"),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            )
            if (!thread) Field(
                if (ch.isCategory) t("server.channelIcon.categoryTitle") else t("server.channelIcon.title"),
                hint = if (ch.isCategory) t("server.channelIcon.categoryHint") else t("server.channelIcon.hint"),
            ) { ChannelIconPicker(ch, glyph, onUploaded = { glyphEdit = null }) { glyphEdit = it } }
            if (textLike || forum) Field(
                if (forum) t("forum.settings.guidelines") else t("server.channelSettings.channelTopic"),
                hint = if (forum) t("forum.settings.guidelinesHint") else null,
            ) {
                fish.nexa.ui.format.FormattedTextField(
                    topic, { topicEdit = it }, 1024,
                    placeholder = if (forum) t("forum.settings.guidelinesPlaceholder") else t("server.channelSettings.letEveryoneKnowHowToUse"),
                    title = if (forum) t("forum.settings.guidelines") else t("server.channelSettings.channelTopic"),
                    serverId = ch.serverId,
                )
            }
            if (textLike) ToggleLine(t("follows.settings.announcement"), announce, { announceEdit = it }, description = t("follows.settings.announcementDesc"))
            if (forum) ForumTagsEditor(tags) { tagsEdit = it }
            if (forum) ToggleLine(t("forum.settings.requireTag"), requireTag, { requireTagEdit = it }, description = t("forum.settings.requireTagDesc"))
            if (media) ToggleLine(t("media.settings.hideDownloads"), hideDownloads, { hideDownloadsEdit = it }, description = t("media.settings.hideDownloadsDesc"))
            if (forum) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!media) Box(Modifier.weight(1f)) {
                    Field(t("forum.settings.layout")) {
                        SelectField(listOf("list" to t("forum.layout.list"), "gallery" to t("forum.layout.gallery")), layout, { layoutEdit = it }, title = t("forum.settings.layout"))
                    }
                }
                Box(Modifier.weight(1f)) {
                    Field(t("forum.settings.sort")) {
                        SelectField(listOf("activity" to t("forum.sort.activity"), "created" to t("forum.sort.created")), sort, { sortEdit = it }, title = t("forum.settings.sort"))
                    }
                }
            }
            if (thread && postTags.isNotEmpty()) Field(t("forum.post.tags")) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tg in postTags) {
                        val on = tg.id in applied
                        val allowed = !tg.moderated || mayManageThreads
                        TagChip(tg.emoji, tg.name, on, allowed) { appliedEdit = if (on) applied - tg.id else (applied + tg.id).take(5) }
                    }
                }
            }
            if (textLike || forum || thread) Field(
                if (forum) t("forum.settings.slowmode") else t("server.channelSettings.slowmode"),
                hint = if (forum) t("forum.settings.slowmodeHint") else t("server.channelSettings.membersWillBeRestrictedToSending"),
            ) {
                SelectField(SLOWMODE.map { it to slowLabel(it) }, slow, { slowEdit = it }, title = t("server.channelSettings.slowmode"))
            }
            if (textLike || forum) Field(t("threads.settings.defaultArchive"), hint = t("threads.settings.defaultArchiveHint")) {
                SelectField(fish.nexa.ui.threads.ThreadsUi.AUTO_ARCHIVE.map { it to fish.nexa.ui.threads.ThreadsUi.archiveLabel(it) }, defaultArchive, { defaultArchiveEdit = it }, title = t("threads.settings.defaultArchive"))
            }
            if (thread) Field(t("threads.create.closeAfter"), hint = t("threads.create.closeHint")) {
                SelectField(fish.nexa.ui.threads.ThreadsUi.AUTO_ARCHIVE.map { it to fish.nexa.ui.threads.ThreadsUi.archiveLabel(it) }, archive, { archiveEdit = it }, title = t("threads.create.closeAfter"))
            }
            if (thread && mayManageThreads) ToggleLine(t("threads.settings.locked"), locked, { lockedEdit = it }, description = t("threads.settings.lockedDesc"))
            if (textLike || forum) ToggleLine(t("server.channelSettings.ageRestrictedChannel"), nsfw, { nsfwEdit = it }, description = if (nsfw) t("nsfw.channelHint") else null)
            if (ch.isVoice) Field(
                t("server.channelSettings.userLimitValue", "limit" to if (limit == 0) t("server.channelSettings.noLimit") else t("server.channelSettings.usersCount", "count" to limit)),
                hint = t("server.channelSettings.limitHowManyPeopleCanJoin"),
            ) {
                Box(Modifier.noSwipeWhileTouched()) {
                    NxSlider(limit.toFloat(), { limitEdit = it.toInt().coerceIn(0, 99) }, 0f..99f, label = t("server.channelSettings.userLimitValue", "limit" to limit))
                }
            }
        }
    }
}

/** A forum tag to put on a post, or take off: a pill that fills with the accent while it is on. */
@Composable
private fun TagChip(emoji: String, name: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (on) fish.nexa.ui.theme.mixSrgb(c.brand, c.bgSurface, 0.78f) else c.bgSurface, shape)
            .border(1.dp, if (on) c.brand.copy(alpha = 0.7f) else c.borderSubtle, shape)
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, role = Role.Switch, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (emoji.isNotEmpty()) Txt(emoji, style = Nx.type.bodySmall)
        Txt(name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = if (on) c.textStrong else c.text, maxLines = 1)
    }
}

/**
 * A forum's tags: what posts can be filed under, each with an emoji if it
 * likes, and whether only people who manage threads may put it on a post.
 */
@Composable
private fun ForumTagsEditor(tags: List<TagEdit>, onChange: (List<TagEdit>) -> Unit) {
    val c = Nx.colors
    fun update(i: Int, fn: (TagEdit) -> TagEdit) = onChange(tags.mapIndexed { j, tg -> if (j == i) fn(tg) else tg })
    Field(t("forum.settings.tags"), hint = t("forum.settings.tagsHint", "count" to MAX_FORUM_TAGS)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEachIndexed { i, tg ->
                key(tg.key) {
                    Column(
                        Modifier.fillMaxWidth().surfaceCard(c, Radii.Sm).padding(start = 8.dp, end = 4.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(width = 64.dp, height = 44.dp)) {
                                NxTextField(
                                    tg.emoji,
                                    { v -> update(i) { it.copy(emoji = v.trim().let { s -> s.codePointCount(0, s.length).let { n -> if (n > 2) s.substring(s.offsetByCodePoints(0, n - 2)) else s } }) } },
                                    Modifier.semantics { contentDescription = t("forum.settings.tagEmoji") },
                                    placeholder = "🙂",
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                NxTextField(
                                    tg.name, { v -> update(i) { it.copy(name = v.take(20)) } },
                                    Modifier.semantics { contentDescription = t("forum.settings.tagName") },
                                    placeholder = t("forum.settings.tagName"),
                                    capitalize = true,
                                )
                            }
                            Box(
                                Modifier.size(44.dp).clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { onChange(tags.filterIndexed { j, _ -> j != i }) }
                                    .semantics { contentDescription = t("forum.settings.removeTag", "name" to tg.name.ifBlank { "…" }) },
                                contentAlignment = Alignment.Center,
                            ) { Icon("Trash2", c.icon, size = 18.dp) }
                        }
                        fish.nexa.ui.components.NxCheckbox(tg.moderated, { on -> update(i) { it.copy(moderated = on) } }, t("forum.settings.moderated"), Modifier.padding(start = 2.dp))
                    }
                }
            }
            if (tags.size < MAX_FORUM_TAGS) NxButton(
                t("forum.settings.addTag"), { onChange(tags + TagEdit(null, "", "", false)) },
                kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Plus",
            )
        }
    }
}

/** The value that stands for the channel's uploaded picture while it is being edited. */
private const val PICTURE = "picture"

/**
 * ChannelIconPicker: an uploaded picture, the default, an emoji (this server's own included) and
 * the app's 48 line icons, as tiles in a well; Choose Emoji opens the picker.
 */
@Composable
private fun ChannelIconPicker(ch: Channel, value: String, onUploaded: () -> Unit, onChange: (String) -> Unit) {
    val c = Nx.colors
    val isCategory = ch.isCategory
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    // a picture is saved at once, as a role's icon is
    fun upload(r: CropResult) {
        uploading = true
        scope.launch {
            try {
                val area = r.crop?.let { "?x=${it.x}&y=${it.y}&w=${it.w}&h=${it.h}" } ?: ""
                val el = ServerApi.upload("PUT", "/channels/${ch.id}/icon$area", r.bytes, r.type)
                Nexa.channels.upsert(listOf(fish.nexa.net.wireJson.decodeFromJsonElement(Channel.serializer(), el)))
                onUploaded()
                Nexa.ui.toast(t("server.channelIcon.pictureSaved"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast(describeError(e, t("server.channelIcon.pictureFailed")), "error")
            } finally {
                uploading = false
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = runCatching { ServerApi.read(context, uri) }.getOrNull() ?: return@launch
            openImageCrop(f, 1f, round = false, title = t("server.channelIcon.cropPicture"), maxSize = 256, serverCrop = true) { upload(it) }
        }
    }
    val iconName = value.removePrefix("icon:").takeIf { value.startsWith("icon:") }
    var emoji by remember { mutableStateOf(if (value.startsWith("emoji:") || value.startsWith("custom:")) value else null) }
    val emojiShown = if (value.startsWith("emoji:") || value.startsWith("custom:")) value else emoji
    val noneChecked = value != PICTURE && (value.isEmpty() || (iconName != null && iconName !in CHANNEL_ICONS))
    fun emojiLabel(raw: String): String = when {
        raw.startsWith("emoji:") -> raw.removePrefix("emoji:")
        else -> Nexa.expressions.emoji(raw.removePrefix("custom:"))?.let { ":${it.name}:" } ?: t("server.channelIcon.chooseEmoji")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileGrid(Modifier.fillMaxWidth().sunken(Radii.Sm, c).padding(8.dp)) {
            IconTile(noneChecked, if (isCategory) t("server.channelIcon.none") else t("server.channelIcon.default"), { onChange("") }) {
                when {
                    ch.isVoice -> Icon("Volume2", it, size = 20.dp)
                    isCategory -> Box(Modifier.size(16.dp).border(2.dp, it.copy(alpha = 0.8f), CircleShape))
                    else -> Icon("Hash", it, size = 20.dp)
                }
            }
            ch.iconId?.let {
                IconTile(value == PICTURE, t("server.channelIcon.picture"), { onChange(PICTURE) }) { tint -> ChannelGlyph(ch.copy(type = "text", glyph = null), 22.dp, tint) }
            }
            emojiShown?.let { e ->
                IconTile(value == e, emojiLabel(e), { onChange(e) }) { ChannelGlyph(ch.copy(type = "text", glyph = e), 22.dp, it) }
            }
            for ((name, lucide) in CHANNEL_ICONS) {
                IconTile(value == "icon:$name", t("server.channelIcon.names.$name"), { onChange("icon:$name") }) { Icon(lucide, it, size = 20.dp) }
            }
        }
        Wrap {
            NxButton(t("server.channelIcon.uploadPicture"), { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "ImagePlus", enabled = !uploading)
            NxButton(t("server.channelIcon.chooseEmoji"), {
                Pickers.open(Pickers.Opener(listOf(Pickers.Tab.EMOJI), ch.serverId, thisServerOnly = true, draggable = false, closeOnEmoji = true, onEmoji = { p ->
                    val next = if (p.custom != null) "custom:${p.custom.id}" else "emoji:${p.text}"
                    emoji = next
                    onChange(next)
                }))
            }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "SmilePlus")
            if (value.isNotEmpty()) NxButton(t("server.channelIcon.removeIcon"), { onChange("") }, kind = ButtonKind.GHOST, size = ButtonSize.SM)
        }
    }
}

/** `.grid`: tiles at least 44 dp wide, as many to a row as fit, square. */
@Composable
private fun TileGrid(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val min = 44.dp.roundToPx()
        val width = constraints.maxWidth
        val cols = maxOf(1, (width + gap) / (min + gap))
        val tile = (width - gap * (cols - 1)) / cols
        val placeables = measurables.map { it.measure(Constraints.fixed(tile, tile)) }
        val rows = (placeables.size + cols - 1) / cols
        val height = rows * tile + maxOf(0, rows - 1) * gap
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.placeRelative((i % cols) * (tile + gap), (i / cols) * (tile + gap)) }
        }
    }
}

@Composable
private fun IconTile(checked: Boolean, label: String, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Sm
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (checked) c.bgActive else if (pressed) c.bgHover else Color.Transparent)
            .then(if (checked) Modifier.border(2.dp, c.brand, shape) else Modifier)
            .pressable(interaction, role = Role.RadioButton, onClick = onClick)
            .semantics {
                selected = checked
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) { content(if (checked) c.textStrong else c.textMuted) }
}

private data class Target(val id: String, val type: String, val name: String, val color: Long)

/**
 * The Permissions page: who has an overwrite here (@everyone first), the
 * picked one's Deny / Inherit / Allow grid, and for @everyone the private
 * switch. Saving a channel into privacy gives it its own key at once.
 */
@Composable
private fun ChannelPermissionsPage(ch: Channel, serverId: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val servers by Nexa.servers.byId.collectAsState()
    val users by Nexa.users.byId.collectAsState()
    val st = servers[serverId] ?: return
    val overwrites = ch.overwrites ?: emptyList()
    var selected by remember { mutableStateOf(serverId) }
    var adding by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // a channel's permissions are Manage Roles there, as the server holds them
    val editable = Nexa.servers.canI(st, P.MANAGE_ROLES, ch.id)
    val channels by Nexa.channels.byId.collectAsState()
    val parent = ch.parentId?.let { channels[it] }
    val synced = parent != null && Perms.sameOverwrites(ch.overwrites, parent.overwrites)
    // what the editor holds here is all they may set or clear; an administrator holds everything
    val mine = Nexa.servers.channelPerms(st, ch.id, st.me.userId)
    val grantable: Long? = if (P.has(mine, P.ADMINISTRATOR)) null else mine
    // a selection whose overwrite is gone falls back to @everyone, as the list does
    val current: Overwrite? = overwrites.firstOrNull { it.targetId == selected } ?: overwrites.firstOrNull { it.targetId == serverId }
    val curAllow = current?.let { P.parse(it.allow) } ?: 0L
    val curDeny = current?.let { P.parse(it.deny) } ?: 0L
    var allow by remember(selected, ch.overwrites) { mutableLongStateOf(curAllow) }
    var deny by remember(selected, ch.overwrites) { mutableLongStateOf(curDeny) }

    val targets = buildList {
        add(Target(serverId, "role", "@everyone", 0L))
        for (ow in overwrites) {
            if (ow.targetId == serverId) continue
            if (ow.targetType == "role") st.roles[ow.targetId]?.let { r -> add(Target(r.id, "role", r.name, r.color)) }
            else add(Target(ow.targetId, "member", Names.member(serverId, ow.targetId), 0L))
        }
    }
    val target = targets.firstOrNull { it.id == selected } ?: targets.first()
    val dirty = allow != curAllow || deny != curDeny
    val isPrivate = Perms.isPrivateChannel(serverId, overwrites)
    val wantPrivate = if (target.id == serverId) P.has(deny, P.VIEW_CHANNEL) else isPrivate

    suspend fun save(a: Long, d: Long, targetId: String, type: String): Boolean {
        busy = true
        return try {
            val res = Nexa.api.request("PUT", "/channels/${ch.id}/permissions/$targetId", buildJsonObject {
                put("type", type)
                put("allow", (a and d.inv()).toULong().toString())
                put("deny", d.toULong().toString())
            })
            ServerApi.reloadChannels(serverId)
            val needsOwnKey = ((res as? JsonObject)?.get("key_actions") as? JsonObject)?.get("needs_own_key")?.jsonPrimitive?.booleanOrNull == true
            if (needsOwnKey) {
                // the channel just became private: give it its own key so only its viewers ever hold one
                Nexa.keys.ensureConvKey(ch.id)
                ServerApi.reloadChannels(serverId)
            }
            Nexa.ui.toast(t("server.channelSettings.permissionsSaved"), "success")
            true
        } catch (e: Exception) {
            Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("server.channelSettings.couldNotSavePermissions"), "error")
            false
        } finally {
            busy = false
        }
    }
    // Sync Now: the channel takes its category's overwrites
    fun syncNow() {
        busy = true
        scope.launch {
            try {
                val res = Nexa.api.request("POST", "/channels/${ch.id}/permissions/sync")
                ServerApi.reloadChannels(serverId)
                if (((res as? JsonObject)?.get("key_actions") as? JsonObject)?.get("needs_own_key")?.jsonPrimitive?.booleanOrNull == true) {
                    Nexa.keys.ensureConvKey(ch.id)
                    ServerApi.reloadChannels(serverId)
                }
                selected = serverId
                Nexa.ui.toast(t("server.channelSettings.permissionsSynced"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("server.channelSettings.couldNotSavePermissions"), "error")
            } finally {
                busy = false
            }
        }
    }
    fun remove(targetId: String) {
        busy = true
        scope.launch {
            try {
                Nexa.api.request("DELETE", "/channels/${ch.id}/permissions/$targetId")
                ServerApi.reloadChannels(serverId)
                if (selected == targetId) selected = serverId
            } catch (e: Exception) {
                Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("server.channelSettings.couldNotRemoveTheOverwrite"), "error")
            } finally {
                busy = false
            }
        }
    }
    fun add(targetId: String, type: String) {
        adding = null
        query = ""
        // a private channel's new entry starts with View Channel allowed, so adding someone lets them in
        val a = if (isPrivate) P.VIEW_CHANNEL else 0L
        scope.launch { if (save(a, 0L, targetId, type)) selected = targetId }
    }

    // only what the server would let this editor set: roles below their highest, members they outrank
    val meId = st.me.userId
    val (myTop, iAmOwner) = Nexa.servers.memberHighest(st, meId)
    val candidateRoles = Nexa.servers.rolesByPosition(st).filter { r -> r.id != serverId && overwrites.none { it.targetId == r.id } && Perms.canManageRole(myTop, iAmOwner, r.position) }
    val q = query.trim().lowercase()
    val candidateMembers = st.members.values
        .filter { m -> overwrites.none { it.targetId == m.userId } }
        .filter { it.userId != meId || iAmOwner }
        .filter { Perms.canActOn(myTop, Nexa.servers.memberHighest(st, it.userId).first, iAmOwner, st.server.ownerId == it.userId) }
        .map { Triple(it.userId, Names.member(serverId, it.userId), users[it.userId]?.username ?: "") }
        .filter { (_, n, u) -> q.isEmpty() || n.lowercase().contains(q) || u.contains(q) }
        .sortedBy { it.second.lowercase() }
        .take(12)

    SettingsPage(SaveState(dirty, busy, { allow = curAllow; deny = curDeny }, { scope.launch { save(allow, deny, target.id, target.type) } })) {
        PageTitle(t("server.channelSettings.permissions"), Modifier.padding(bottom = 0.dp))
        Hint(
            (if (ch.isCategory) t("server.channelSettings.permsCategory") else t("server.channelSettings.permsChannel")) +
                (if (isPrivate) " " + t("server.channelSettings.permsPrivate") else ""),
            Modifier.padding(bottom = if (parent != null && !ch.isCategory) 12.dp else 16.dp),
        )
        // `.syncBar`: whether this channel follows its category's permissions
        if (parent != null && !ch.isCategory) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp)
                    .background(c.bgSurface, Radii.Sm)
                    .border(1.dp, if (synced) c.borderSubtle else c.warning.copy(alpha = 0.45f), Radii.Sm)
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (synced) "Link2" else "Link2Off", if (synced) c.brand else c.textWarning, size = 16.dp)
                Txt(
                    if (synced) t("server.channelSettings.syncedWith", "category" to parent.name) else t("server.channelSettings.notSyncedWith", "category" to parent.name),
                    style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.textMuted, modifier = Modifier.weight(1f),
                )
                if (!synced && editable) SmallButton(t("server.channelSettings.syncNow"), t("server.channelSettings.syncNow")) { if (!busy) Holds.unlessUnsaved { syncNow() } }
            }
        }
        // who: @everyone, then every role and member with an entry here
        Wrap(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Txt(t("server.channelSettings.rolesMembers"), style = Nx.type.label.copy(fontWeight = FontWeight(700)), color = c.textSubtle, modifier = Modifier.weight(1f))
            if (editable) {
                SmallButton("+ " + t("server.channelSettings.role"), t("server.channelSettings.addRole")) { adding = if (adding == "role") null else "role" }
                SmallButton("+ " + t("server.channelSettings.member"), t("server.channelSettings.addMember")) { adding = if (adding == "member") null else "member" }
            }
        }
        if (adding == "role") PickerBox {
            if (candidateRoles.isEmpty()) PickerEmpty(t("server.channelSettings.everyRoleAlreadyHasAnOverwrite"))
            for (r in candidateRoles) PickerRow({ Holds.unlessUnsaved { add(r.id, "role") } }) {
                ColorDot(r.color)
                Txt(r.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, maxLines = 1)
            }
        }
        if (adding == "member") PickerBox {
            SearchBox(query, { query = it }, t("server.channelSettings.searchMembers"), Modifier.padding(bottom = 4.dp), autofocus = true)
            if (candidateMembers.isEmpty()) PickerEmpty(if (q.isNotEmpty()) t("friends.nobodyHereMatches", "query" to query.trim()) else t("server.channelSettings.nobodyLeftToAdd"))
            for ((id, n, u) in candidateMembers) PickerRow({ Holds.unlessUnsaved { add(id, "member") } }) {
                Avatar(id, n, users[id]?.avatarId, 20.dp)
                fish.nexa.ui.chat.PersonName(id, n, serverId = serverId, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Txt("@$u", style = Nx.type.hint, color = c.textMuted, maxLines = 1)
            }
        }
        Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (x in targets) key(x.id) {
                TargetRow(x, x.id == target.id, editable && x.id != serverId, busy, users[x.id]?.avatarId,
                    onPick = { if (x.id != target.id) Holds.unlessUnsaved { selected = x.id } },
                    onRemove = { Holds.unlessUnsaved { remove(x.id) } },
                )
            }
        }
        if (target.id == serverId) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).surfaceCard(c).padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleLine(t("modals.createChannel.privateChannel"), wantPrivate, { on ->
                    if (on) {
                        deny = deny or P.VIEW_CHANNEL
                        allow = allow and P.VIEW_CHANNEL.inv()
                    } else deny = deny and P.VIEW_CHANNEL.inv()
                }, description = t("server.channelSettings.onlySelectedMembersAndRolesCan"), enabled = editable)
                if (wantPrivate) Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon("Lock", c.textMuted, size = 14.dp)
                    Txt(t("server.channelSettings.addRolesOrMembersOnThe"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                }
            }
        }
        OverwriteGrid(allow, deny, { a, d -> allow = a; deny = d }, editable, ch.type, grantable)
    }
}

@Composable
private fun TargetRow(x: Target, on: Boolean, removable: Boolean, busy: Boolean, avatarId: String?, onPick: () -> Unit, onRemove: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Sm
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                when {
                    on -> Modifier.background(c.bgSelected, shape).background(sheen(c), shape).topEdge(shape, c.edgeLight)
                    pressed -> Modifier.background(c.bgHover, shape)
                    else -> Modifier
                },
            )
            .clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .pressable(interaction, role = Role.Tab, onClick = onPick)
                .semantics { selected = on }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (x.type == "role") ColorDot(x.color) else Avatar(x.id, x.name, avatarId, 20.dp)
            fish.nexa.ui.chat.PersonName(x.id.takeIf { x.type == "member" }, x.name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(500)), color = if (on) c.textStrong else c.textChannel, maxLines = 1)
        }
        if (removable) RowIconButton("X", t("server.channelSettings.removeOverwrite"), onRemove, danger = true, enabled = !busy, size = 44.dp)
    }
}

/** `.smallBtn`: a small raised button (36 dp for a thumb). */
@Composable
private fun SmallButton(text: String, label: String, onClick: () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Sm
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .surfaceCard(c, shape)
            .then(if (pressed) Modifier.background(c.bgHover, shape) else Modifier)
            .clip(shape)
            .pressable(interaction, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, style = Nx.type.label.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(650)), color = c.text) }
}

/** `.picker`: a raised list to add from. */
@Composable
private fun PickerBox(content: @Composable () -> Unit) {
    val c = Nx.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .shadowMedium(Radii.Md, c)
            .surfaceCard(c)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun PickerRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Nx.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(Radii.Sm)
            .background(if (pressed) c.bgHover else Color.Transparent)
            .pressable(interaction, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun PickerEmpty(text: String) = Txt(text, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = Nx.colors.textMuted, modifier = Modifier.padding(8.dp))

/** The Delete page: what goes, and the button that deletes at once (no second question here). */
@Composable
private fun DeleteChannelPage(ch: Channel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val isCat = ch.isCategory
    SettingsPage {
        PageTitle(if (isCat) t("server.channelSettings.deleteCategory") else if (ch.isThread) t("threads.bar.delete") else t("server.channelSettings.deleteChannel"), Modifier.padding(bottom = 0.dp))
        Hint(if (isCat) t("server.channelSettings.deleteCategoryHint", "name" to ch.name) else t("server.channelSettings.deleteChannelHint", "name" to ch.name))
        Box(Modifier.padding(top = 24.dp)) {
            NxButton(if (isCat) t("server.channelSettings.deleteCategory") else if (ch.isThread) t("threads.bar.delete") else t("server.channelSettings.deleteChannel"), {
                busy = true
                scope.launch {
                    try {
                        Nexa.api.request("DELETE", "/channels/${ch.id}")
                        Nexa.channels.remove(ch.id)
                        onDone()
                    } catch (e: Exception) {
                        Nexa.ui.toast((e as? fish.nexa.net.ApiError)?.message ?: t("chat.messageMenu.couldNotDelete"), "error")
                        busy = false
                    }
                }
            }, kind = ButtonKind.DANGER, icon = "Trash2", loading = busy)
        }
    }
}
