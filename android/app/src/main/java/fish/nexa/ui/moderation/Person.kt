package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ModEvent
import fish.nexa.data.ModMembership
import fish.nexa.data.ModPack
import fish.nexa.data.ModUser
import fish.nexa.data.Moderation
import fish.nexa.data.StaffCase
import fish.nexa.data.TypingStyle
import fish.nexa.media.Media
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sidewaysScroll
import fish.nexa.ui.markdown.MarkdownView
import fish.nexa.ui.markdown.RenderCtx
import fish.nexa.ui.nexa.fieldLabel
import fish.nexa.ui.profile.StatusEmojiImage
import fish.nexa.ui.profile.Widgets
import fish.nexa.ui.profile.statusEmojiText
import fish.nexa.ui.shell.openProfile
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One person, for the staff (PersonPage.tsx): their profile as everyone sees
 * it, with a way to take down any part of it, and every case about them.
 * Never anything they said in a conversation: the panel has no way to see it.
 */
@Composable
fun PersonPage(userId: String, cases: Boolean, caseId: String?) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    var data by remember(userId) { mutableStateOf<Moderation.Person?>(null) }
    var failed by remember(userId) { mutableStateOf(false) }
    val ownerId by Moderation.ownerId.collectAsState()
    Nexa.presence.byId.collectAsState().value

    fun load() {
        scope.launch {
            try {
                data = Moderation.user(userId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
            }
        }
    }
    LaunchedEffect(userId) { load() }
    LaunchedEffect(userId) {
        Moderation.events.collect { e -> if (e is ModEvent.Case && e.case.userId == userId) load() }
    }

    val d = data
    if (failed) {
        EmptyState(null, null, t("moderation.user.missing")) {
            NxButton(t("moderation.backToUsers"), { goPanel("users", replace = true) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        }
        return
    }
    if (d == null) {
        LoadingRow()
        return
    }
    val user = d.user
    val deleted = user.isDeleted
    val st = modStatus(user)
    val standing = listOfNotNull(
        user.ban?.let { b -> b.expiresAt?.let { t("moderation.state.bannedUntil", "when" to Time.relativeTo(it)) } ?: t("moderation.state.bannedForever") },
        user.mute?.let { m -> m.expiresAt?.let { t("moderation.state.mutedUntil", "when" to Time.relativeTo(it)) } ?: t("moderation.state.muted") },
    )
    val awaiting = d.cases.count { it.status == "open" && it.awaiting == "staff" }
    val base = "users/$userId"
    fun moderate(action: String? = null, preset: ModeratePreset? = null) = openModerate(user, d.servers, action, preset) { cs ->
        if (cs.action == "delete") load() else goPanel("$base/cases/${cs.id}")
    }

    Column(Modifier.fillMaxSize()) {
        PageHead(
            back = t(if(ReportNavigation.origin != null) "reports.returnToReport" else "moderation.backToUsers"),
            onBack = { panelBack("users") },
            leading = { Avatar(user.id, user.displayName, user.avatarId, 48.dp, status = if (deleted) null else st.status) },
            title = if (deleted) t("nexa.deletedUser") else user.displayName,
            titlePersonId = user.id.takeUnless { deleted },
            sub = listOf(if (deleted) t("moderation.user.deleted") else "@${user.username}", t("moderation.casesCount", "count" to d.cases.size)).plus(standing).joinToString(" · "),
            badge = {
                AltBadge(user)
                RankBadge(user.id, user.adminFlags, withIcon = true)
            },
            below = if (deleted) null else ({ StatusSummary(st, compact = true) }),
            actions = if (deleted) null else ({
                NxButton(t("moderation.menu.profile"), { openProfile(user.id) }, kind = ButtonKind.GHOST, size = ButtonSize.TOUCH, icon = "UserRound")
                // the owner gives and takes ranks, from here as from the grid's menu
                if (fish.nexa.data.StaffRanks.mine() == fish.nexa.data.StaffRank.OWNER && user.blocked != "self" && user.blocked != "owner") {
                    NxButton(t("staff.rankModal.menu"), { openRankSheet(user) { load() } }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "ShieldCheck")
                }
                // admins give Contributor and event badges by hand; bots hold none
                if (fish.nexa.data.StaffRanks.mine()?.atLeast(fish.nexa.data.StaffRank.ADMIN) == true && user.adminFlags and 64L == 0L) {
                    NxButton(t("badges.staff.giveMenu"), { openGiveBadgesSheet(user) }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = "Award")
                }
                if (user.blocked.isEmpty()) NxButton(t("moderation.moderate"), { moderate() }, size = ButtonSize.TOUCH, icon = "Gavel")
            }),
        )
        Row(Modifier.fillMaxWidth().sidewaysScroll().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SubTab(t("moderation.person.profileTab"), "IdCard", !cases) { goPanel(base, replace = true) }
            SubTab(t("moderation.person.casesTab"), "FolderOpen", cases, count = d.cases.size, badge = awaiting) { goPanel("$base/cases", replace = true) }
        }
        Rule()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !cases -> ProfileSheet(user, d.servers, d.packs, owner = user.id == ownerId) { action, preset -> moderate(action, preset) }
                d.cases.isEmpty() -> EmptyState("FolderOpen", t("moderation.user.noCases"), t("moderation.user.noCasesHint"))
                caseId != null -> StaffCasePane(caseId, showMember = false, onBack = { goPanel("$base/cases", replace = true) }) { changed -> data = d.copy(cases = d.cases.map { if (it.id == changed.id) changed else it }) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                    caseItems(d.cases, selected = null, showTarget = false) { goPanel("$base/cases/$it") }
                }
            }
        }
    }
}

/**
 * A person's profile for the staff (ProfileSheet.tsx): as everyone sees it,
 * then every part of it that anyone can see, each with the way to take it
 * down, the servers they own, the packs they made, and the account's facts.
 */
@Composable
private fun ProfileSheet(user: ModUser, servers: List<ModMembership>, packs: List<ModPack>, owner: Boolean, onModerate: (String, ModeratePreset) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val c = Nx.colors
    val deleted = user.isDeleted
    // taking down part of a profile is for admins and above; a mod mutes and bans
    val mine = fish.nexa.data.StaffRanks.mine()
    val blocked = if (deleted) t("moderation.user.deleted") else blockedText(user.blocked).ifEmpty { if (mine != null && mine.atLeast(fish.nexa.data.StaffRank.ADMIN)) "" else t("staff.modOnlyRestricts") }
    val locked = blocked.isNotEmpty()
    fun reset(f: String) = onModerate("field_reset", ModeratePreset(fields = listOf(f)))
    val status = listOf(statusEmojiText(user.statusEmoji), user.statusText).filter { it.isNotEmpty() }.joinToString(" ")
    val nicknamed = servers.filter { it.nickname.isNotEmpty() }
    val profiled = servers.filter { it.hasProfile }
    // the same pictures in another place: what Remove takes as well
    val serverAvatars = servers.count { it.avatarId != null }
    val serverBanners = servers.count { it.bannerId != null }
    val typing = TypingStyle.text(TypingStyle.read(user.typingStyle))
    val nameLook = fish.nexa.data.NameStyle.text(fish.nexa.data.NameStyle.read(user.nameStyle))
    val widgets = widgetsText((user.widgets as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { Widgets.read(it) } ?: emptyList())
    val owned = servers.filter { it.owner }
    val density = LocalDensity.current.density

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PanelPad, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("face") { PublicFace(user) }
        item("public") {
            SheetPanel(t("moderation.sheet.publicTitle"), if (locked) blocked else t("moderation.sheet.publicHint")) {
                @Composable
                fun row(label: String, empty: Boolean, action: String, icon: String, run: () -> Unit, value: @Composable () -> Unit) =
                    FieldRowView(label, empty, { NxButton(action, run, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH, icon = icon, enabled = !locked && !empty) }, value)

                row(t("moderation.field.avatar"), user.avatarId == null && serverAvatars == 0, t("moderation.sheet.remove"), "ImageOff", { onModerate("avatar_remove", ModeratePreset(avatar = true, banner = false)) }) {
                    if (user.avatarId != null) Avatar(user.id, user.displayName, user.avatarId, 36.dp)
                    else FieldValue(if (serverAvatars > 0) t("moderation.sheet.inServers", "count" to serverAvatars) else t("moderation.sheet.notSet"), empty = serverAvatars == 0)
                }
                row(t("moderation.field.banner"), user.bannerId == null && serverBanners == 0, t("moderation.sheet.remove"), "ImageOff", { onModerate("avatar_remove", ModeratePreset(avatar = false, banner = true)) }) {
                    if (user.bannerId != null) Box(Modifier.width(120.dp).aspectRatio(3f).clip(Radii.Xs).ring(Radii.Xs, c.borderSubtle)) {
                        AsyncImage(Media.bannerUrl(user.bannerId, 240, density), t("moderation.field.banner"), imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    } else FieldValue(if (serverBanners > 0) t("moderation.sheet.inServers", "count" to serverBanners) else t("moderation.sheet.notSet"), empty = serverBanners == 0)
                }
                row(fieldLabel("display_name"), false, t("moderation.sheet.reset"), "Eraser", { reset("display_name") }) { FieldValue(user.displayName) }
                row(fieldLabel("username"), false, t("moderation.sheet.reset"), "Eraser", { reset("username") }) { FieldValue("@" + user.username) }
                row(fieldLabel("pronouns"), user.pronouns.isEmpty(), t("moderation.sheet.reset"), "Eraser", { reset("pronouns") }) { FieldValue(user.pronouns.ifEmpty { t("moderation.sheet.notSet") }, user.pronouns.isEmpty()) }
                row(fieldLabel("about_me"), user.aboutMe.isEmpty(), t("moderation.sheet.reset"), "Eraser", { reset("about_me") }) {
                    FieldValue(if (user.aboutMe.isNotEmpty()) previewText(user.aboutMe) else t("moderation.sheet.notSet"), user.aboutMe.isEmpty())
                }
                row(fieldLabel("status"), status.isEmpty(), t("moderation.sheet.clear"), "Eraser", { reset("status") }) {
                    if (status.isEmpty()) FieldValue(t("moderation.sheet.notSet"), true)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (user.statusEmoji.isNotEmpty()) StatusEmojiImage(user.statusEmoji, 18.dp)
                        FieldValue(user.statusText)
                    }
                }
                row(fieldLabel("nicknames"), nicknamed.isEmpty(), t("moderation.sheet.resetAll"), "Eraser", { reset("nicknames") }) {
                    if (nicknamed.isEmpty()) FieldValue(t("moderation.sheet.none"), true) else NickList(nicknamed.map { it.name to it.nickname })
                }
                row(fieldLabel("server_profiles"), profiled.isEmpty(), t("moderation.sheet.resetAll"), "Eraser", { reset("server_profiles") }) {
                    if (profiled.isEmpty()) FieldValue(t("moderation.sheet.none"), true)
                    else NickList(profiled.map { s -> s.name to listOf(s.pronouns, if (s.bio.isNotEmpty()) previewText(s.bio) else "", if (s.widgets.isNotEmpty() && s.widgets != "[]") t("moderation.field.widgets") else "").filter { it.isNotEmpty() }.joinToString(" / ") })
                }
                row(fieldLabel("typing_style"), typing.isEmpty(), t("moderation.sheet.clear"), "Eraser", { reset("typing_style") }) { FieldValue(typing.ifEmpty { t("moderation.sheet.notSet") }, typing.isEmpty()) }
                row(fieldLabel("name_style"), nameLook.isEmpty(), t("moderation.sheet.clear"), "Eraser", { reset("name_style") }) { FieldValue(nameLook.ifEmpty { t("moderation.sheet.notSet") }, nameLook.isEmpty()) }
                row(fieldLabel("widgets"), widgets.isEmpty(), t("moderation.sheet.removeAll"), "Eraser", { reset("widgets") }) {
                    if (widgets.isEmpty()) FieldValue(t("moderation.sheet.none"), true) else NickList(widgets)
                }
                row(fieldLabel("server_tag"), user.serverTag == null, t("moderation.sheet.takeOff"), "Eraser", { reset("server_tag") }) {
                    val tag = user.serverTag
                    if (tag == null) FieldValue(t("moderation.sheet.none"), true) else fish.nexa.ui.chat.ServerTagChip(tag, small = false, open = false)
                }
            }
        }
        if (owned.isNotEmpty()) item("owned") {
            SheetPanel(t("moderation.sheet.ownedTitle", "count" to owned.size)) {
                for (s in owned) OpenRow(s.name, t("moderation.servers.members", "count" to s.memberCount), { goPanel("servers/${s.serverId}") }) { ServerIcon(s.serverId, s.iconId, s.name, 36.dp) }
            }
        }
        if (packs.isNotEmpty()) item("packs") {
            SheetPanel(t("moderation.packs.theirPacksTitle", "count" to packs.size)) { PackRows(packs) }
        }
        item("account") {
            SheetPanel(t("moderation.sheet.accountTitle")) {
                FactsList(listOf(
                    Triple("CalendarDays", t("moderation.sheet.joined")) { FactText(Time.shortDate(user.createdAt)) },
                    Triple("Clock", t("moderation.sheet.lastActive")) { FactText(user.lastSeenAt?.let { Time.relative(it) } ?: t("moderation.sheet.never")) },
                    Triple("ShieldCheck", t("moderation.sheet.access")) {
                        FactText(
                            when {
                                owner -> t("profile.profileCard.owner")
                                user.isStaff -> fish.nexa.data.StaffRanks.of(null, user.adminFlags)?.label ?: t("moderation.staff")
                                user.isDisabled -> t("moderation.state.disabled")
                                else -> t("moderation.sheet.member")
                            },
                        )
                    },
                    Triple(null, t("moderation.sheet.userId")) { CopyId(user.id, t("moderation.menu.copyId")) },
                ))
            }
        }
        // what their calls used, for catching anyone running the allowance down
        if (mine != null && mine.atLeast(fish.nexa.data.StaffRank.ADMIN) && !deleted) item("calls") { CallUsagePanel(user.id) }
    }
}

/** `.nickList`: what someone is in each server (or each widget), the server's name small before it. */
@Composable
private fun NickList(rows: List<Pair<String, String>>) {
    val c = Nx.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((where, what) in rows) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(where, style = Nx.type.hint.copy(fontSize = Nx.type.sp(12.5f)), color = c.textMuted, maxLines = 1, modifier = Modifier.fillMaxWidth(0.4f))
            Txt(what, style = Nx.type.body.copy(fontSize = Nx.type.sp(14.5f)), color = c.text, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The profile as everyone sees it: banner, picture, names, status and about
 * me, drawn the way the app draws a profile (PublicFace).
 */
@Composable
private fun PublicFace(user: ModUser) {
    val c = Nx.colors
    val st = modStatus(user)
    var open by remember(user.aboutMe) { mutableStateOf(false) }
    var folds by remember(user.aboutMe) { mutableStateOf(false) }
    val status = listOf(statusEmojiText(user.statusEmoji), user.statusText).filter { it.isNotEmpty() }.joinToString(" ")
    Box(Modifier.fillMaxWidth().modCard(c)) {
        Column(Modifier.fillMaxWidth()) {
            CardBanner(user.bannerId, profileBrush(user.accentColor, user.accentColor2, user.id), 120.dp)
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.pullUp(48.dp).background(cardFill(c), CircleShape).padding(6.dp)) {
                    Avatar(user.id, user.displayName, user.avatarId, 84.dp, status = st.status)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        fish.nexa.ui.chat.PersonName(user.id, user.displayName, style = Nx.type.name.copy(fontSize = Nx.type.sp(21f), lineHeight = Nx.type.sp(25.2f), fontWeight = FontWeight(700)), color = c.textStrong)
                        Txt("@" + user.username + if (user.pronouns.isNotEmpty()) " · " + user.pronouns else "", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.textMuted)
                    }
                    if (status.isNotEmpty()) Row(
                        Modifier.background(c.textStrong.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).topEdge(RoundedCornerShape(14.dp), c.edgeLight).padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (user.statusEmoji.isNotEmpty()) StatusEmojiImage(user.statusEmoji, 18.dp)
                        Txt(user.statusText, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text)
                    }
                    FaceSection(t("moderation.sheet.aboutMe")) {
                        if (user.aboutMe.isNotEmpty()) {
                            FoldBox(163.dp, open, { folds = it }) { MarkdownView(user.aboutMe, RenderCtx(surface = cardFill(c), bodyPx = 14f, bodyLine = 20.3f)) }
                            if (folds || open) Txt(
                                if (open) t("chat.embeds.showLess") else t("chat.embeds.showMore"),
                                style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textMuted,
                                modifier = Modifier.clip(Radii.Xs).pressable(remember { MutableInteractionSource() }) { open = !open }.padding(vertical = 6.dp),
                            )
                        } else Txt(t("moderation.noAbout"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontStyle = FontStyle.Italic), color = c.textMuted)
                    }
                    FaceSection(t("moderation.sheet.memberSince")) { Txt(Time.shortDate(user.createdAt), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) }
                }
            }
        }
        // what the card is: said on the banner, small, where it never covers the picture
        FaceCaption(Modifier.align(Alignment.TopStart))
    }
}

/** `.faceCaption`: "As Everyone Sees It", on the banner. */
@Composable
fun FaceCaption(modifier: Modifier) {
    Box(modifier.padding(start = 12.dp, top = 10.dp).background(androidx.compose.ui.graphics.Color(0x8C0A0612), RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Txt(t("moderation.sheet.asSeen"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11.5f), fontWeight = FontWeight(650)), color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
    }
}

/** `.faceSection`: one part of the face under a rule, its label small and bold. */
@Composable
fun FaceSection(label: String, content: @Composable () -> Unit) {
    val c = Nx.colors
    Column(Modifier.fillMaxWidth().topRule(c).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(label, style = Nx.type.name.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted)
        content()
    }
}

/** Packs as rows that open their page, on a person's or a server's page (PackRows). */
@Composable
fun PackRows(packs: List<ModPack>) {
    for (pk in packs) OpenRow(
        pk.name,
        (if (pk.emoji) t("moderation.packs.emojiCount", "count" to pk.stickerCount) else t("moderation.packs.stickerCount", "count" to pk.stickerCount)) +
            if (pk.discoverBlocked) " · " + t("moderation.packs.keptPrivate") else if (pk.public) " · " + t("moderation.packs.public") else "",
        { goPanel("expressions/packs/${pk.id}") },
    ) { PackCover(pk, big = false) }
}

/** A case, in the colour of what it did. */
fun caseTitle(cs: StaffCase) = t("moderation.case.titled", "number" to cs.number, "action" to actionLabel(cs.action))
