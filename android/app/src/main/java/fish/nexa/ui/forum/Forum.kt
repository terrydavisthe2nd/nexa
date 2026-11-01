package fish.nexa.ui.forum

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.crypto.AttachmentMeta
import fish.nexa.data.Automod
import fish.nexa.data.AutomodNotices
import fish.nexa.data.ChatMessage
import fish.nexa.data.Ids
import fish.nexa.data.P
import fish.nexa.data.Send
import fish.nexa.data.Staged
import fish.nexa.media.Media
import fish.nexa.media.rememberAttachmentLoad
import fish.nexa.net.Channel
import fish.nexa.net.ForumTag
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.automod.automodReason
import fish.nexa.ui.chat.Staging
import fish.nexa.ui.chat.replyWords
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.describeError
import fish.nexa.ui.dialogs.FooterButtons
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.MenuItem
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.layers.openMenu
import fish.nexa.ui.media.copyOf
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.nav.Route
import fish.nexa.ui.shell.ChannelGlyph
import fish.nexa.ui.shell.NavButton
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import fish.nexa.ui.threads.ThreadListing
import fish.nexa.ui.threads.ThreadsUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A forum's post as the list shows it: the thread, and its first message opened. */
private class Post(val listing: ThreadListing, val first: ChatMessage?)

/** A post's first picture, as its small copy when it has one (postPicture.ts firstPicture). */
private fun firstPicture(att: List<AttachmentMeta>?): AttachmentMeta? {
    val pic = att?.firstOrNull { it.type.startsWith("image/") && it.spoiler != true } ?: return null
    return copyOf(pic) ?: pic
}

/** When a post last had something said in it. */
private fun activityOf(th: Channel): Long = Ids.createdAt(th.lastMessageId ?: th.id)

private fun prefs() = Nexa.app.getSharedPreferences("forum", android.content.Context.MODE_PRIVATE)

/**
 * ForumView.tsx: a forum's posts as a list or a gallery, filed under tags,
 * sorted by what is lively or what is new, searchable by title. Its
 * guidelines open at the top, and anyone who can write here can start a post.
 */
@OptIn(FlowPreview::class)
@Composable
fun ForumView(channel: Channel) {
    val c = Nx.colors
    val serverId = channel.serverId ?: return
    val st = Nexa.servers.get(serverId)
    val forum = channel.forum
    val allTags = forum?.tags ?: emptyList()
    var sort by remember(channel.id) { mutableStateOf(forum?.sort ?: "activity") }
    // a media channel's posts are pictures and videos, always shown as a gallery two to a row
    val media = channel.isMedia
    var chosenLayout by remember(channel.id) { mutableStateOf(prefs().getString("layout:${channel.id}", null) ?: forum?.layout ?: "list") }
    val layout = if (media) "gallery" else chosenLayout
    var q by remember(channel.id) { mutableStateOf("") }
    val filter = remember(channel.id) { mutableStateListOf<String>() }
    var posts by remember(channel.id) { mutableStateOf<List<Post>?>(null) }
    var pinnedId by remember(channel.id) { mutableStateOf<String?>(null) }
    var more by remember(channel.id) { mutableStateOf(false) }
    var failed by remember(channel.id) { mutableStateOf(false) }
    var closed by remember(channel.id) { mutableStateOf<List<Post>?>(null) }
    var closedMore by remember(channel.id) { mutableStateOf(false) }
    var guidelines by remember(channel.id) { mutableStateOf(prefs().getBoolean("guide:${channel.id}", true)) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // a media post is made of files, so posting there also needs Attach Files
    val canPost = Nexa.servers.canI(st, P.SEND_MESSAGES, channel.id) && (!media || Nexa.servers.canI(st, P.ATTACH_FILES, channel.id))
    DisposableEffectOpen(channel)

    suspend fun opened(list: List<ThreadListing>): List<Post> = withContext(Dispatchers.Default) {
        list.map { th -> Post(th, th.firstMessage?.let { Nexa.messages.decrypt(it) }) }
    }

    suspend fun load(reset: Boolean) {
        failed = false
        try {
            val last = if (reset) null else posts?.lastOrNull()?.listing?.channel
            val before = last?.let { if (sort == "created") it.id else (it.lastMessageId ?: it.id) }
            val page = ThreadsUi.list(channel.id, before = before, q = q.trim().ifEmpty { null }, sort = sort, tags = filter.toList(), limit = 30)
            val got = opened(page.threads)
            posts = if (reset || posts == null) got else posts!! + got
            more = page.hasMore
            if (reset) {
                pinnedId = page.pinnedPost
                closed = null
            }
        } catch (_: Exception) {
            failed = true
        }
    }

    // asked again when what is asked for changes, a moment after typing stops
    LaunchedEffect(channel.id, sort, filter.toList(), reload) {
        delay(if (q.isNotEmpty()) 300 else 0)
        load(true)
    }
    LaunchedEffect(channel.id) {
        snapshotFlow { q }.drop(1).debounce(300).collect { load(true) }
    }
    // posts started, closed, or taken away while the forum is open
    LaunchedEffect(channel.id) {
        Nexa.channels.byId.map { m -> m.values.filter { it.parentId == channel.id && it.isThread }.map { it.id }.toSet() }
            .distinctUntilChanged().drop(1).debounce(400).collect { load(true) }
    }

    fun loadClosed() = scope.launch {
        try {
            val last = closed?.lastOrNull()?.listing?.channel
            val page = ThreadsUi.list(channel.id, archived = true, before = last?.thread?.archivedAt?.toString(), q = q.trim().ifEmpty { null }, tags = filter.toList(), limit = 30)
            closed = (closed ?: emptyList()) + opened(page.threads)
            closedMore = page.hasMore
        } catch (_: Exception) {
            Nexa.ui.toast(t("forum.closedFailed"), "error")
        }
    }

    val ordered = posts?.let { list ->
        val pin = pinnedId?.let { id -> list.firstOrNull { it.listing.channel.id == id } }
        if (pin != null) listOf(pin) + list.filter { it !== pin } else list
    }
    fun open(th: Channel) {
        Nexa.channels.upsert(listOf(th))
        Nav.go(Route.Server(serverId, th.id))
    }

    Column(Modifier.fillMaxSize()) {
        ForumHeader(channel, canPost)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (channel.topic.isNotBlank()) item("guide") {
                Guidelines(channel, guidelines) {
                    guidelines = !guidelines
                    prefs().edit().putBoolean("guide:${channel.id}", guidelines).apply()
                }
            }
            item("tools") {
                Tools(q, { q = it }, sort, layout) {
                    openMenu(
                        listOf(
                            MenuItem.Action(t("forum.sort.activity"), checked = sort == "activity") { sort = "activity" },
                            MenuItem.Action(t("forum.sort.created"), checked = sort == "created", separatorAfter = !media) { sort = "created" },
                        ) + if (media) emptyList() else listOf(
                            MenuItem.Action(t("forum.layout.list"), checked = layout == "list") {
                                chosenLayout = "list"
                                prefs().edit().putString("layout:${channel.id}", "list").apply()
                            },
                            MenuItem.Action(t("forum.layout.gallery"), checked = layout == "gallery") {
                                chosenLayout = "gallery"
                                prefs().edit().putString("layout:${channel.id}", "gallery").apply()
                            },
                        ),
                        title = t("forum.view.title"),
                        haptic = false,
                    )
                }
            }
            if (allTags.isNotEmpty()) item("filter") {
                TagFilter(allTags, filter.toList(), { id -> if (id in filter) filter.remove(id) else filter.add(id) }) { filter.clear() }
            }
            when {
                failed && posts == null -> item("failed") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(t("forum.failed"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center)
                        NxButton(t("chat.messageList.tryAgain"), { reload += 1 }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                    }
                }
                ordered == null -> item("loading") { Txt(t("forum.loading"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
                ordered.isEmpty() -> item("empty") {
                    val filtered = q.isNotBlank() || filter.isNotEmpty()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (media) "Images" else "MessagesSquare", c.textMuted, size = 36.dp)
                        Txt(if (filtered) t("forum.noMatches") else t("forum.noPosts"), style = Nx.type.heading.copy(fontSize = Nx.type.sp(18f)), color = c.textStrong, align = TextAlign.Center)
                        Txt(
                            when {
                                filtered -> t("forum.noMatchesHint")
                                canPost -> t(if (media) "media.noPostsHint" else "forum.noPostsHint")
                                else -> t(if (media) "media.noPostsReadOnly" else "forum.noPostsReadOnly")
                            },
                            style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
                        )
                        if (!filtered && canPost) NxButton(t("forum.newPost"), { openNewPost(channel.id) }, size = ButtonSize.MD, icon = "Plus", modifier = Modifier.padding(top = 6.dp))
                    }
                }
                media -> items(ordered.chunked(2), key = { row -> row.first().listing.channel.id }) { row ->
                    MediaRow(row) { p -> PostCard(p, allTags, layout, pinned = p.listing.channel.id == pinnedId, media = true, modifier = Modifier.weight(1f)) { open(p.listing.channel) } }
                }
                else -> items(ordered, key = { it.listing.channel.id }) { p ->
                    PostCard(p, allTags, layout, pinned = p.listing.channel.id == pinnedId) { open(p.listing.channel) }
                }
            }
            if (more) item("more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NxButton(t("forum.loadMore"), { scope.launch { load(false) } }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                }
            }
            if (ordered != null && !more) {
                val shut = closed
                if (shut == null) item("showClosed") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NxButton(t("forum.showClosed"), { loadClosed() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Archive")
                    }
                } else {
                    item("closedHead") {
                        if (shut.isEmpty()) Txt(t("forum.noClosed"), style = Nx.type.body, color = c.textMuted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
                        else Txt(t("forum.closedPosts"), style = Nx.type.heading.copy(fontSize = Nx.type.sp(16f)), color = c.textStrong, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                    if (media) items(shut.chunked(2), key = { row -> "closed-" + row.first().listing.channel.id }) { row ->
                        MediaRow(row) { p -> PostCard(p, allTags, layout, pinned = false, media = true, modifier = Modifier.weight(1f)) { open(p.listing.channel) } }
                    }
                    else items(shut, key = { "closed-" + it.listing.channel.id }) { p -> PostCard(p, allTags, layout, pinned = false) { open(p.listing.channel) } }
                    if (closedMore) item("closedMore") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            NxButton(t("forum.loadMore"), { loadClosed() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
                        }
                    }
                }
            }
        }
    }
}

/** The forum is the page being looked at: its server and channel are what the shell shows as open. */
@Composable
private fun DisposableEffectOpen(channel: Channel) {
    androidx.compose.runtime.DisposableEffect(channel.id) {
        Nexa.ui.open(channel.serverId, channel.id)
        onDispose { }
    }
}

@Composable
private fun ForumHeader(channel: Channel, canPost: Boolean) {
    val c = Nx.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .boxShadow(RoundedCornerShape(0.dp), 8f, 16f, c.shadeAt(0.9f), spread = -14f)
            .background(c.bgRaised)
            .background(sheen(c))
            .padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavButton()
        ChannelGlyph(channel, 22.dp, c.textMuted)
        Txt(channel.name, style = Nx.type.headerTitle.copy(fontSize = Nx.type.sp(16.5f), fontWeight = FontWeight(660)), color = c.textStrong, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 2.dp))
        if (canPost) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .size(40.dp)
                    .clip(Radii.Sm)
                    .background(brandGradient(c))
                    .then(if (pressed) Modifier.background(Color.Black.copy(alpha = 0.12f)) else Modifier)
                    .pressable(interaction) { openNewPost(channel.id) }
                    .semantics { contentDescription = t("forum.newPost") },
                contentAlignment = Alignment.Center,
            ) { Icon("Plus", c.onBrand, size = 20.dp) }
        }
    }
}

@Composable
private fun Guidelines(channel: Channel, open: Boolean, toggle: () -> Unit) {
    val c = Nx.colors
    val shape = Radii.Md
    Column(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .topEdge(shape, c.edgeLight),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressable(remember { MutableInteractionSource() }, onClick = toggle)
                .semantics { selected = open }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(t("forum.settings.guidelines"), style = Nx.type.body.copy(fontWeight = FontWeight(650)), color = c.textStrong, modifier = Modifier.weight(1f))
            Icon(if (open) "ChevronUp" else "ChevronDown", c.textMuted, size = 18.dp)
        }
        if (open) Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            fish.nexa.ui.markdown.MarkdownView(channel.topic, fish.nexa.ui.markdown.RenderCtx(serverId = channel.serverId, surface = c.bgSurface))
        }
    }
}

@Composable
private fun Tools(q: String, onQ: (String) -> Unit, sort: String, layout: String, onView: () -> Unit) {
    val c = Nx.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            // the magnifier and the way to clear it, as the web's search box has them
            fish.nexa.ui.server.SearchBox(q, onQ, t("forum.search"))
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            Modifier
                .size(46.dp)
                .ring(Radii.Sm, c.borderSubtle)
                .clip(Radii.Sm)
                .background(if (pressed) c.bgHover else c.bgSurface)
                .pressable(interaction, onClick = onView)
                .semantics { contentDescription = t("forum.view.title") + ": " + (if (sort == "created") t("forum.sort.created") else t("forum.sort.activity")) + ", " + (if (layout == "gallery") t("forum.layout.gallery") else t("forum.layout.list")) },
            contentAlignment = Alignment.Center,
        ) { Icon("SlidersHorizontal", c.icon, size = 18.dp) }
    }
}

@Composable
private fun TagFilter(all: List<ForumTag>, on: List<String>, toggle: (String) -> Unit, clear: () -> Unit) {
    val c = Nx.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = t("forum.filterByTag") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon("Tag", c.textMuted, size = 16.dp)
        for (tag in all) {
            val chosen = tag.id in on
            val shape = RoundedCornerShape(999.dp)
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .then(if (chosen) Modifier else Modifier.ring(shape, c.borderSubtle))
                    .clip(shape)
                    .then(if (chosen) Modifier.background(brandGradient(c)) else Modifier.background(c.bgSurface))
                    .pressable(remember { MutableInteractionSource() }) { toggle(tag.id) }
                    .semantics { selected = chosen }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (tag.emoji.isNotEmpty()) fish.nexa.media.TwemojiImage(tag.emoji, 14.dp)
                Txt(tag.name, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (chosen) c.onBrand else c.text, maxLines = 1)
            }
        }
        if (on.isNotEmpty()) Txt(
            t("forum.clearTags"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.link,
            modifier = Modifier.pressable(remember { MutableInteractionSource() }, onClick = clear).padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

/** Two media posts side by side (Forum.module.css .list[data-media] at phone width); an odd last one keeps its half. */
@Composable
private fun MediaRow(row: List<Post>, card: @Composable androidx.compose.foundation.layout.RowScope.(Post) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in row) card(p)
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

/** One post as the forum lists it: what it is called, its tags, how it starts, and how lively it is. */
@Composable
private fun PostCard(post: Post, allTags: List<ForumTag>, layout: String, pinned: Boolean, media: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onOpen: () -> Unit) {
    val c = Nx.colors
    val channels by Nexa.channels.byId.collectAsState()
    val th = channels[post.listing.channel.id] ?: post.listing.channel
    val sid = th.serverId
    val author = th.ownerId ?: post.first?.authorId ?: ""
    val shown = Names.member(sid, author)
    @Suppress("UNUSED_VARIABLE") val automodVersion by Automod.version.collectAsState()
    val held = post.first?.let { Automod.heldBy(it, Nexa.users.meId) } != null
    val pic = if (held) null else firstPicture(post.first?.payload?.att)
    // a media post that is a video with no picture shows that it plays
    val video = media && !held && pic == null && post.first?.payload?.att.orEmpty().any { it.type.startsWith("video/") && it.spoiler != true }
    val replies = maxOf(0, (th.thread?.messageCount ?: 1) - 1)
    val isClosed = th.thread?.archived == true
    val tags = allTags.filter { it.id in (th.thread?.appliedTags ?: emptyList()) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Lg
    Column(
        modifier
            .ring(shape, if (pressed) c.border else c.borderSubtle)
            .clip(shape)
            .background(if (pressed) c.bgHover else c.bgRaised, shape)
            .topEdge(shape, c.edgeLight)
            .pressable(interaction, onClick = onOpen)
            .semantics { contentDescription = th.name },
    ) {
        // a media post's picture is the post: a tall tile, with the words stepping back to a caption
        if (layout == "gallery") Box(Modifier.fillMaxWidth().aspectRatio(if (media) 4f / 5f else 16f / 9f).background(c.bgSurface), contentAlignment = Alignment.Center) {
            when {
                pic != null -> PostPicture(pic, Modifier.fillMaxSize())
                video -> Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon("Play", Color.White, size = 22.dp, filled = true, modifier = Modifier.padding(start = 3.dp))
                }
                else -> Icon(if (media) "Images" else "MessagesSquare", c.textMuted, size = 28.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(if (media) 10.dp else 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (media) 4.dp else 6.dp)) {
                if (pinned || isClosed || tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (pinned) Pill("Pin", t("forum.post.pinned"), c.textBrand, c.brand.copy(alpha = 0.16f))
                    if (isClosed) Pill("Archive", t("threads.chip.closed"), c.textMuted, c.bgActive)
                    for (tag in tags) TagPill(tag)
                }
                Txt(th.name, style = Nx.type.name.copy(fontSize = Nx.type.sp(if (media) 15f else 16.5f), fontWeight = FontWeight(700)), color = c.textStrong, maxLines = if (media) 1 else 2)
                val words = when {
                    held -> t("automod.held.text")
                    post.first?.error != null -> t("chat.messageItem.waitingForTheEncryptionKey")
                    post.first != null -> post.first.payload?.t?.takeIf { it.isNotBlank() }?.let { replyWords(post.first) } ?: ""
                    else -> ""
                }
                val line = androidx.compose.ui.text.buildAnnotatedString {
                    if (!held && post.first?.error == null) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight(650), color = c.text))
                        append(shown)
                        pop()
                        if (words.isNotEmpty()) append(": ")
                    }
                    append(words)
                }
                androidx.compose.foundation.text.BasicText(line, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(19.6f), color = c.textMuted), maxLines = if (media) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(author, shown, Nexa.users.get(author)?.avatarId, 18.dp)
                    Row(Modifier.semantics { contentDescription = t("forum.post.repliesLong", "count" to replies) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon("MessageSquare", c.textMuted, size = 14.dp)
                        Txt(replies.toString(), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
                    }
                    Spacer(Modifier.weight(1f))
                    Txt(Time.relative(activityOf(th)), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1)
                }
            }
            if (layout == "list" && pic != null) PostPicture(pic, Modifier.size(72.dp).clip(Radii.Sm))
        }
    }
}

@Composable
private fun PostPicture(att: AttachmentMeta, modifier: Modifier) {
    val load = rememberAttachmentLoad(att, true)
    val file = load.file
    Box(modifier.background(Nx.colors.bgInput)) {
        if (file != null) AsyncImage(file, null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Pill(icon: String, text: String, fg: Color, bg: Color) {
    Row(
        Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, fg, size = 12.dp)
        Txt(text, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = fg, maxLines = 1)
    }
}

@Composable
private fun TagPill(tag: ForumTag) {
    val c = Nx.colors
    Row(
        Modifier.background(c.bgActive, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (tag.emoji.isNotEmpty()) fish.nexa.media.TwemojiImage(tag.emoji, 12.dp)
        Txt(tag.name, style = Nx.type.hint.copy(fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// a new post (NewPostDialog.tsx)

private const val MAX_POST_FILES = 10

/** A new post: a title, its tags, and its first message, which goes up for everyone with it. */
fun openNewPost(forumId: String) = fish.nexa.ui.dialogs.open("newPost") { layer, close -> NewPostSheet(layer, forumId, close) }

@Composable
private fun NewPostSheet(layer: Layer, forumId: String, close: () -> Unit) {
    val c = Nx.colors
    val forum = Nexa.channels.get(forumId) ?: return
    val serverId = forum.serverId ?: return
    val st = Nexa.servers.get(serverId)
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val applied = remember { mutableStateListOf<String>() }
    val files = remember { mutableStateListOf<Staged>() }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val manage = Nexa.servers.canI(st, P.MANAGE_THREADS, forumId)
    val canAttach = Nexa.servers.canI(st, P.ATTACH_FILES, forumId)
    // a media channel's post is its pictures and videos; the words are a caption
    val media = forum.isMedia
    fun isMediaFile(type: String) = type.startsWith("image/") || type.startsWith("video/")
    val tags = forum.forum?.tags ?: emptyList()
    val notice = AutomodNotices.byChannel[forumId]
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            for (u in uris) {
                if (files.size >= MAX_POST_FILES) break
                val s = Staging.stage(u, forumId) ?: continue
                if (media && !isMediaFile(s.type)) {
                    Nexa.ui.toast(t("media.post.notMedia", "name" to s.name), "error")
                    s.file.delete()
                    continue
                }
                if (!fish.nexa.data.Uploads.fits(s.file.length(), forumId)) {
                    Nexa.ui.toast(t("forum.post.fileTooBig", "name" to s.name), "error")
                    s.file.delete()
                    continue
                }
                files += s
            }
        }
    }
    fun post() {
        val name = title.trim()
        val body = text.trim()
        problem = null
        when {
            name.isEmpty() -> problem = t("forum.post.needTitle")
            media && files.none { isMediaFile(it.type) } -> problem = t("media.post.needMedia")
            body.isEmpty() && files.isEmpty() -> problem = t("forum.post.needWords")
            forum.forum?.requireTag == true && applied.isEmpty() -> problem = t("forum.post.needTag")
        }
        if (problem != null || busy) return
        // AutoMod reads it before anything goes: a post it stops never goes up
        val screened = Send.screen(forumId, body, files.map { it.name })
        if (screened is Send.Screened.Stopped) return
        busy = true
        scope.launch {
            try {
                val res = Nexa.api.request("POST", "/channels/$forumId/threads", buildJsonObject {
                    put("name", name)
                    put("applied_tags", JsonArray(applied.map { JsonPrimitive(it) }))
                }) as kotlinx.serialization.json.JsonObject
                val thread = fish.nexa.net.wireJson.decodeFromJsonElement(Channel.serializer(), res["thread"]!!)
                Nexa.channels.upsert(listOf(thread))
                Nexa.threads.setJoined(thread.id, true)
                close()
                Nav.go(Route.Server(serverId, thread.id))
                // the post's first message, sealed for the post: it goes up for everyone with it
                if (files.isNotEmpty()) Send.sendWithFiles(thread.id, body, files.toList()) else Send.sendMessage(thread.id, body)
            } catch (e: Exception) {
                problem = describeError(e, t("forum.post.failed"))
            } finally {
                busy = false
            }
        }
    }
    // `.files`: the picked files as small cards, with the button after them
    val picker: @Composable () -> Unit = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            for (f in files.toList()) Row(
                Modifier.heightIn(min = 40.dp).ring(Radii.Sm, c.borderSubtle).background(c.bgSurface, Radii.Sm).padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (f.preview != null && f.type.startsWith("image/")) AsyncImage(java.io.File(f.preview), null, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                else Icon(if (f.type.startsWith("video/")) "Play" else "File", c.textMuted, size = 20.dp)
                Txt(f.name, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.text, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
                Txt(fish.nexa.ui.Time.bytes(f.size), style = Nx.type.hint.copy(fontSize = Nx.type.sp(12f)), color = c.textMuted, maxLines = 1)
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).pressable(remember { MutableInteractionSource() }) {
                        files.remove(f)
                        f.file.delete()
                    }.semantics { contentDescription = t("composer.composer.removeNamed", "name" to f.name) },
                    contentAlignment = Alignment.Center,
                ) { Icon("X", c.icon, size = 14.dp) }
            }
            if (files.size < MAX_POST_FILES) NxButton(
                if (media) t("media.post.addMedia") else t("forum.post.addFiles"),
                { pick.launch(if (media) arrayOf("image/*", "video/*") else arrayOf("*/*")) },
                kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = if (media) "ImagePlus" else "Paperclip",
            )
        } }
    Sheet(layer, title = t("forum.newPostIn", "name" to forum.name), maxWidth = 620.dp, footer = {
        FooterButtons(t("forum.post.submit"), ::post, close, enabled = title.isNotBlank() && (!media || files.isNotEmpty()), busy = busy)
    }) {
        if (notice != null) Row(
            Modifier.fillMaxWidth().background(c.bgSurface, Radii.Sm).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon("ShieldAlert", c.textDanger, size = 18.dp)
            Txt(t("automod.notice.stopped") + " " + (notice.message.ifEmpty { automodReason(notice.kind, notice.matched) }), style = Nx.type.bodySmall, color = c.text, modifier = Modifier.weight(1f))
        }
        NxTextField(title, { title = it.take(100) }, label = t("forum.post.title"), required = true, placeholder = t("forum.post.titlePlaceholder"), capitalize = true)
        if (tags.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(if (forum.forum?.requireTag == true) t("forum.post.tagsRequired") else t("forum.post.tags"), style = Nx.type.label, color = c.textSubtle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in tags) {
                    val chosen = tag.id in applied
                    val locked = tag.moderated && !manage
                    val shape = RoundedCornerShape(999.dp)
                    Row(
                        Modifier
                            .heightIn(min = 36.dp)
                            .then(if (chosen) Modifier else Modifier.ring(shape, c.borderSubtle))
                            .clip(shape)
                            .then(if (chosen) Modifier.background(brandGradient(c)) else Modifier.background(c.bgSurface))
                            .pressable(remember { MutableInteractionSource() }) {
                                when {
                                    locked -> Nexa.ui.toast(t("forum.post.moderatedTag"))
                                    chosen -> applied.remove(tag.id)
                                    applied.size < 5 -> applied.add(tag.id)
                                }
                            }
                            .semantics { selected = chosen }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (locked) Icon("Lock", c.textMuted, size = 12.dp)
                        if (tag.emoji.isNotEmpty()) fish.nexa.media.TwemojiImage(tag.emoji, 14.dp)
                        Txt(tag.name, style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = if (chosen) c.onBrand else if (locked) c.textMuted else c.text, maxLines = 1)
                    }
                }
            }
            Txt(t("forum.post.tagsHint", "count" to 5), style = Nx.type.hint, color = c.textMuted)
        }
        if (media) fish.nexa.ui.server.Field(t("media.post.media"), required = true) {
            if (canAttach) picker() else Txt(t("media.post.cantAttach"), style = Nx.type.bodySmall, color = c.textDanger)
        }
        fish.nexa.ui.server.Field(t("forum.post.message")) {
            fish.nexa.ui.format.FormattedTextField(
                text, { text = it }, Send.MAX_MESSAGE_LENGTH,
                placeholder = if (media) t("media.post.captionPlaceholder") else t("forum.post.messagePlaceholder"),
                title = t("forum.post.message"),
                serverId = serverId,
                minLines = if (media) 3 else 6,
            )
        }
        if (canAttach && !media) picker()
        problem?.let { Txt(it, style = Nx.type.bodySmall, color = c.textDanger) }
    }
}
