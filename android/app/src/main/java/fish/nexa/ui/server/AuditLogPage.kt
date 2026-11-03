package fish.nexa.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.P
import fish.nexa.net.AuditEntry
import fish.nexa.net.PublicUser
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.shell.Names
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
private data class AuditReply(val entries: List<AuditEntry> = emptyList(), val users: List<PublicUser> = emptyList())

private const val PAGE = 50

/** Every audited action, as the server records them. */
private val ACTIONS = listOf(
    "server_update", "server_transfer", "channel_create", "channel_update", "channel_delete", "channel_positions", "overwrite_update", "overwrite_delete", "overwrite_sync",
    "role_create", "role_update", "role_delete", "role_positions", "member_update", "member_role_add", "member_role_remove", "member_kick", "member_ban",
    "member_unban", "member_voice", "emoji_create", "emoji_update", "emoji_delete", "sticker_pack_create", "sticker_pack_update", "sticker_pack_delete",
    "invite_create", "invite_delete", "webhook_create", "webhook_update", "webhook_delete", "server_tag_update", "server_tag_delete",
    "invite_delete_all", "vanity_url_update", "invites_pause", "invites_resume", "member_prune", "member_timeout", "member_timeout_remove", "member_quarantine_lift",
    "message_delete", "message_bulk_delete", "message_pin", "message_unpin", "message_publish", "thread_create", "thread_update", "thread_delete",
    "follow_create", "follow_delete", "automod_rule_create", "automod_rule_update", "automod_rule_delete", "automod_action",
)

/** What each audited action says, looked up when drawn so it follows the language. */
private fun verb(action: String): String = if (action in ACTIONS) t("server.auditLog.verb.$action") else action

/** AutoMod's own id, which stands for it as the one who did what it did. */
private const val AUTOMOD = "0"

/** What each kind of channel is called where one is made. */
private fun channelKind(type: String?): String = when (type) {
    "voice" -> t("modals.createChannel.voice")
    "category" -> t("server.auditLog.category")
    "announcement" -> t("modals.createChannel.announcement")
    "forum" -> t("modals.createChannel.forum")
    "media" -> t("modals.createChannel.media")
    "link" -> t("modals.createChannel.link")
    "thread" -> t("threads.create.public")
    "private_thread" -> t("threads.create.private")
    else -> t("modals.createChannel.text")
}

/** lib/moderation.ts formatSpan: "7 days", "12 hours". */
fun formatSpan(ms: Long): String {
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    val week = 7 * day
    for ((size, k) in listOf(week to "nexa.span.weeks", day to "nexa.span.days", hour to "nexa.span.hours", minute to "nexa.span.minutes")) {
        if (ms >= size && ms % size == 0L) return t(k, "count" to ms / size)
    }
    if (ms >= day) return t("nexa.span.days", "count" to Math.round(ms.toDouble() / day))
    if (ms >= hour) return t("nexa.span.hours", "count" to Math.round(ms.toDouble() / hour))
    return t("nexa.span.minutes", "count" to maxOf(1L, Math.round(ms.toDouble() / minute)))
}

/**
 * AuditLogPage: every moderation and configuration change, newest first, a
 * page at a time, narrowed on the server to one person (or AutoMod) and one
 * kind of action. What is done while it is open comes in at the top. Needs
 * View Audit Log.
 */
@Composable
fun AuditLogPage(serverId: String) {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val users by Nexa.users.byId.collectAsState()
    val channels by Nexa.channels.byId.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    val packs by Nexa.expressions.packs.collectAsState()
    val st = servers[serverId]
    var entries by remember { mutableStateOf<List<AuditEntry>?>(null) }
    var done by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var actor by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    val expanded = remember { mutableStateListOf<String>() }
    // who has done things here, as far as this log has been read
    val actors = remember { mutableStateListOf<String>() }

    fun load(before: String?) {
        busy = true
        failed = false
        scope.launch {
            try {
                val q = buildString {
                    append("limit=$PAGE")
                    if (before != null) append("&before=$before")
                    if (actor.isNotEmpty()) append("&actor_id=$actor")
                    if (action.isNotEmpty()) append("&action=$action")
                }
                val res = Nexa.api.get("/servers/$serverId/audit-log?$q", AuditReply.serializer())
                Nexa.users.upsert(res.users)
                entries = (if (before != null) entries.orEmpty() else emptyList()) + res.entries
                for (e in res.entries) if (e.actorId !in actors) actors += e.actorId
                done = res.entries.size < PAGE
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    // the filters are asked of the server, so older entries are found as well
    LaunchedEffect(serverId, actor, action) {
        entries = null
        load(null)
    }
    // what is done while the log is open comes in at the top
    LaunchedEffect(serverId, actor, action) {
        Nexa.session.events.collect { ev ->
            if (ev.t != "audit_log_entry") return@collect
            val e = runCatching { fish.nexa.net.wireJson.decodeFromJsonElement(AuditEntry.serializer(), ev.d!!) }.getOrNull() ?: return@collect
            if (e.serverId != serverId || (actor.isNotEmpty() && e.actorId != actor) || (action.isNotEmpty() && e.action != action)) return@collect
            val cur = entries ?: return@collect
            if (cur.none { it.id == e.id }) entries = listOf(e) + cur
            if (e.actorId !in actors) actors += e.actorId
        }
    }

    fun str(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun num(v: JsonElement?): Long? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    fun truthy(v: JsonElement?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonPrimitive -> v.booleanOrNull ?: (v.longOrNull?.let { it != 0L }) ?: v.content.isNotEmpty()
        else -> true
    }
    fun shownChannel(id: String): String? = channels[id]?.let { if (it.isText || it.isAnnouncement) "#${it.name}" else it.name }
    fun roleName(id: String): String? = if (id == serverId) "@everyone" else st?.roles?.get(id)?.name
    fun userName(id: String): String? = users[id]?.let { Names.display(it) }
    fun actorName(id: String): String = if (id == AUTOMOD) t("automod.name") else Names.display(users[id])
    val none = t("settings.profile.none")
    fun onOff(v: JsonElement?) = if (truthy(v)) t("server.auditLog.on") else t("server.channelSettings.slowmodeOff")
    fun permNames(v: JsonElement?): String = PermInfo.labels(P.parse((v as? JsonPrimitive)?.contentOrNull)) ?: none
    fun changedOrRemoved(v: JsonElement?) = if (truthy(v)) t("server.auditLog.changed") else t("server.auditLog.removed")

    fun targetName(e: AuditEntry): String {
        val id = e.targetId ?: return ""
        val changes = e.changes as? JsonObject
        val name = str(changes?.get("name"))
        return when (e.targetType) {
            "user" -> Names.display(users[id])
            "channel" -> shownChannel(id) ?: name?.let { "#$it" } ?: t("server.auditLog.aDeletedChannel")
            "role" -> st?.roles?.get(id)?.name ?: name ?: t("server.auditLog.aDeletedRole")
            "emoji" -> name?.let { ":$it:" } ?: ""
            // a rename records the new name, other changes the name it has; creating and deleting the one it had
            "webhook" -> (name?.ifEmpty { null } ?: str(changes?.get("current_name"))) ?: ""
            // pack updates record no changes, so the pack's current name is the only one there is
            "sticker_pack" -> name ?: packs[id]?.name ?: ""
            else -> ""
        }
    }

    // each recorded field by the name the screen that sets it uses, its value in that screen's words
    fun field(k: String, v: JsonElement?, e: AuditEntry, obj: JsonObject): Pair<String, String>? {
        val s = str(v)
        val n = num(v)
        return when (k) {
            "name" -> t("modals.subchat.name") to (s?.ifEmpty { null } ?: none)
            "description" -> t("server.serverOverview.serverDescription") to (s?.ifEmpty { null } ?: none)
            "topic" -> t("server.channelSettings.channelTopic") to (s?.ifEmpty { null } ?: none)
            "type" -> t("modals.createChannel.channelType") to channelKind(s)
            "system_channel_id" -> t("server.serverOverview.systemMessagesChannel") to (s?.let { shownChannel(it) } ?: t("server.serverOverview.noSystemMessages"))
            "default_notifications" -> t("server.serverOverview.defaultNotificationSettings") to if (n == 1L) t("server.serverOverview.onlyMentions") else t("server.serverOverview.allMessages")
            "nsfw" -> t("server.channelSettings.ageRestrictedChannel") to onOff(v)
            "slowmode_seconds" -> t("server.channelSettings.slowmode") to (n?.takeIf { it != 0L }?.let { Time.shortDuration(it.toDouble()) } ?: t("server.channelSettings.slowmodeOff"))
            "user_limit" -> t("server.auditLog.userLimit") to (n?.takeIf { it != 0L }?.toString() ?: t("server.channelSettings.noLimit"))
            "color" -> t("server.roles.roleColour") to (n?.takeIf { it != 0L }?.let { hexOf(it) } ?: t("server.roles.default"))
            "tag" -> t("serverTag.page.tagLabel") to (s?.ifEmpty { null } ?: none)
            "tag_badge" -> t("serverTag.page.badge") to if (s?.startsWith("image:") == true) t("serverTag.page.yourPicture") else (s ?: "").removePrefix("icon:")
            "tag_color" -> t("serverTag.page.color") to (n?.let { hexOf(it) } ?: t("serverTag.page.accent"))
            "tag_limited" -> t("serverTag.page.who") to if (truthy(v)) t("serverTag.page.someRoles") else t("serverTag.page.everyone")
            "tag_join" -> t("serverTag.page.join") to onOff(v)
            "hoist" -> t("server.roles.displayRoleMembersSeparatelyFromOnline") to onOff(v)
            "mentionable" -> t("server.roles.allowAnyoneToMentionThisRole") to onOff(v)
            "permissions" -> t("server.channelSettings.permissions") to permNames(v)
            "allow" -> t("server.permissionGrid.allow") to permNames(v)
            "deny" -> t("server.permissionGrid.deny") to permNames(v)
            "target_id", "target" -> {
                // an overwrite names who it is for
                if (s == null) null
                else {
                    val member = str(obj["target_type"]) == "member"
                    (if (member) t("server.channelSettings.member") else t("server.channelSettings.role")) to ((if (member) userName(s) else roleName(s) ?: userName(s)) ?: s)
                }
            }
            "role" -> t("server.channelSettings.role") to (s?.let { roleName(it) } ?: t("server.auditLog.aDeletedRole"))
            "roles" -> t("server.members.roles") to ((v as? JsonArray)?.takeIf { it.isNotEmpty() }?.joinToString(", ") { roleName((it as? JsonPrimitive)?.content ?: "") ?: t("server.auditLog.aDeletedRole") } ?: none)
            "nick" -> t("modals.serverProfile.nickname") to (s?.ifEmpty { null } ?: none)
            "timeout_until" -> t("server.members.timedOut") to (n?.takeIf { it > 0 }?.let { Time.full(it) } ?: none)
            "mute" -> t("voice.voice.mute") to onOff(v)
            "deaf" -> t("shell.user.deafen") to onOff(v)
            // the channel an entry is about is its own target; a voice move names where to
            "channel_id" -> if (s == e.targetId) null else t("server.invites.channel") to (s?.let { shownChannel(it) } ?: t("server.auditLog.disconnected"))
            // a thread's parent is the channel it was started in
            "parent_id" -> if (e.action.startsWith("thread_")) t("server.invites.channel") to (s?.let { shownChannel(it) } ?: t("server.auditLog.aDeletedChannel"))
            else t("server.auditLog.category") to (s?.let { shownChannel(it) } ?: none)
            "code" -> t("server.invites.code") to (s ?: "")
            "max_age" -> t("modals.groupInvite.expireAfter") to (n?.takeIf { it != 0L }?.let { formatSpan(it * 1000) } ?: t("server.invites.never"))
            "max_uses" -> t("modals.groupInvite.maxNumberOfUses") to (n?.takeIf { it != 0L }?.toString() ?: t("modals.invite.noLimit"))
            "delete_message_days" -> t("server.memberAction.deleteMessageHistory") to (n?.takeIf { it != 0L }?.let { formatSpan(it * 86_400_000L) } ?: none)
            "icon" -> (if (e.targetType == "role") t("roles.icon.label") else t("server.serverOverview.serverIcon")) to changedOrRemoved(v)
            "icon_emoji" -> t("roles.icon.label") to (s?.ifEmpty { null }?.let { if (it.startsWith("custom:")) t("server.auditLog.changed") else it } ?: t("server.auditLog.removed"))
            "color2" -> t("roles.style.second") to (n?.takeIf { it != 0L }?.let { hexOf(it) } ?: none)
            "color3" -> t("roles.style.third") to (n?.takeIf { it != 0L }?.let { hexOf(it) } ?: none)
            "glyph" -> t("server.channelIcon.title") to (s?.ifEmpty { null }?.let { if (it.startsWith("custom:")) t("server.auditLog.changed") else it } ?: t("server.channelIcon.default"))
            "forum" -> t("server.auditLog.forumSettings") to t("server.auditLog.changed")
            "applied_tags" -> t("forum.settings.tags") to ((v as? JsonArray)?.let { t("server.auditLog.itemsCount", "count" to it.size) } ?: none)
            "enabled" -> t("server.auditLog.status") to onOff(v)
            "discoverable" -> t("discovery.settings.list") to onOff(v)
            "discovery_category" -> t("discovery.settings.category") to (s?.ifEmpty { null }?.let { t("discovery.cat.$it") } ?: t("discovery.settings.noCategory"))
            "discovery_tags" -> t("discovery.settings.tags") to ((v as? JsonArray)?.takeIf { it.isNotEmpty() }?.joinToString(", ") { (it as? JsonPrimitive)?.content ?: "" } ?: none)
            "discovery_language" -> t("discovery.settings.language") to (s?.ifEmpty { null }?.let { tag -> fish.nexa.ui.I18n.languages.firstOrNull { it.tag == tag }?.endonym ?: tag } ?: t("discovery.anyLanguage"))
            "verify_account_minutes" -> t("safety.accountAge") to (n?.takeIf { it != 0L }?.let { formatSpan(it * 60_000L) } ?: t("safety.noWait"))
            "verify_member_minutes" -> t("safety.memberAge") to (n?.takeIf { it != 0L }?.let { formatSpan(it * 60_000L) } ?: t("safety.noWait"))
            "bio" -> t("settings.profile.aboutMe") to changedOrRemoved(v)
            "pronouns" -> t("settings.profile.pronouns") to (s?.ifEmpty { null } ?: none)
            "server_avatar" -> t("serverProfile.picture") to t("server.auditLog.removed")
            "server_banner" -> t("serverProfile.banner") to t("server.auditLog.removed")
            "quarantined" -> t("automod.action.held") to onOff(v)
            "banner" -> t("server.serverOverview.serverBanner") to changedOrRemoved(v)
            "avatar" -> t("integrations.image") to changedOrRemoved(v)
            "rule" -> t("automod.log.rule") to (s ?: "")
            "kind" -> t("automod.log.kind") to (s?.let { t("automod.kind.$it") } ?: "")
            "actions" -> t("server.auditLog.whatItDid") to ((v as? JsonArray)?.joinToString(", ") {
                when ((it as? JsonPrimitive)?.content) {
                    "block" -> t("automod.action.blocked")
                    "timeout" -> t("automod.action.timedOut")
                    "quarantine" -> t("automod.action.held")
                    else -> t("automod.action.alerted")
                }
            } ?: "")
            "matched" -> t("automod.log.matched") to (s ?: "")
            "mentions" -> t("automod.log.mentions") to (n ?: 0L).toString()
            // a published post went to this many channels that follow this one
            "count" -> if (e.action == "message_publish") t("server.auditLog.sentTo") to t("server.auditLog.followingChannels", "count" to (n ?: 0L))
            else t("server.auditLog.count") to (n ?: 0L).toString()
            "days" -> t("members.prune.days") to (n?.takeIf { it != 0L }?.let { formatSpan(it * 86_400_000L) } ?: "")
            "invites_paused_until" -> t("server.invites.pausedHead") to (n?.takeIf { it != 0L }?.let { Time.full(it) } ?: none)
            "archived" -> t("threads.chip.closed") to onOff(v)
            "locked" -> t("threads.settings.locked") to onOff(v)
            "auto_archive", "default_auto_archive" -> t("threads.create.closeAfter") to (n?.takeIf { it != 0L }?.let { formatSpan(it * 60_000L) } ?: none)
            "source_server" -> t("follows.log.server") to (s ?: "")
            "source_channel" -> t("follows.log.channel") to (s ?: "")
            "author_id" -> t("server.auditLog.author") to (s?.let { userName(it) ?: it } ?: "")
            "message_id", "follow_id", "bulk", "raid", "seconds", "messages", "title", "target_type", "current_name" -> null
            else -> k to when (v) {
                is JsonArray -> t("server.auditLog.itemsCount", "count" to v.size)
                is JsonPrimitive -> if (v.isString) v.content else v.toString()
                null -> "null"
                else -> v.toString()
            }
        }
    }

    fun changesText(e: AuditEntry): String {
        val ch = e.changes ?: return ""
        // the reorders record the whole new order, which says nothing read as text
        if (ch is JsonArray) return t("server.auditLog.itemsCount", "count" to ch.size)
        val obj = ch as? JsonObject ?: return ""
        return obj.entries.mapNotNull { (k, v) -> field(k, v, e, obj)?.let { "${it.first}: ${it.second}" } }.joinToString(" · ")
    }

    val list = entries.orEmpty()
    val filtering = actor.isNotEmpty() || action.isNotEmpty()
    val actionChoices = remember(fish.nexa.ui.I18n.locale) { ACTIONS.map { it to verb(it) }.sortedBy { it.second.lowercase() } }

    SettingsPage {
        PageTitle(t("server.auditLog.auditLog"), Modifier.padding(bottom = 0.dp))
        Hint(t("server.auditLog.everyModerationAndConfigurationChangeIn"), Modifier.padding(bottom = 16.dp))
        if (list.isNotEmpty() || filtering) Wrap(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(Modifier.weight(1f).widthIn(min = 160.dp)) {
                SelectField(
                    listOf("" to t("server.auditLog.anyone"), AUTOMOD to t("automod.name")) + actors.filter { it != AUTOMOD }.map { it to Names.display(users[it]) },
                    actor, { actor = it }, title = t("server.auditLog.filterByMember"), optionPersonId = { it.takeUnless { id -> id.isEmpty() || id == AUTOMOD } },
                )
            }
            Box(Modifier.weight(1f).widthIn(min = 160.dp)) {
                SelectField(listOf("" to t("server.auditLog.anyAction")) + actionChoices, action, { action = it }, title = t("server.auditLog.filterByAction"))
            }
        }
        when {
            entries == null && !failed -> Loading(t("server.auditLog.loadingTheAuditLog"))
            list.isEmpty() && !failed && entries != null -> Hint(if (filtering) t("server.auditLog.nothingMatches") else t("server.auditLog.nothingHasHappenedYet"), top = 0.dp)
        }
        for (e in list) key(e.id) {
            val name = actorName(e.actorId)
            val target = targetName(e)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).surfaceCard(c).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (e.actorId == AUTOMOD) Box(
                    Modifier.size(32.dp).background(c.brand.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { fish.nexa.ui.Icon("ShieldCheck", c.textBrand, size = 18.dp) }
                else Avatar(e.actorId, name, users[e.actorId]?.avatarId, 32.dp)
                Column(Modifier.weight(1f)) {
                    fish.nexa.ui.chat.PersonLine(Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(21f)), c.text, serverId = serverId, nameTextStyle = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(21f), fontWeight = FontWeight(600)), nameColor = c.textStrong) { names ->
                        buildAnnotatedString {
                            if (e.actorId == AUTOMOD) withStyle(SpanStyle(color = c.textStrong, fontWeight = FontWeight(600))) { append(name) }
                            else names.append(this, e.actorId, name)
                            append(" ")
                            append(verb(e.action))
                            if (target.isNotEmpty()) {
                                append(" ")
                                if (e.targetType == "user") names.append(this, e.targetId, target)
                                else withStyle(SpanStyle(color = c.textStrong, fontWeight = FontWeight(600))) { append(target) }
                            }
                        }
                    }
                    Txt(
                        Time.full(e.createdAt) + if (e.reason.isNotEmpty()) " · " + t("server.auditLog.reason", "reason" to e.reason) else "",
                        style = Nx.type.hint,
                        color = c.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    val text = changesText(e)
                    if (text.isNotEmpty()) {
                        val open = e.id in expanded
                        val long = text.length > 300
                        val detailStyle = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f))
                        fish.nexa.ui.chat.PersonLine(detailStyle, c.textMuted, Modifier.padding(top = 4.dp), serverId = serverId) { detailNames ->
                            buildAnnotatedString {
                                val obj = e.changes as? JsonObject
                                if (obj == null) append(text)
                                else {
                                    var remaining = if (long && !open) 299 else Int.MAX_VALUE
                                    for ((k, v) in obj) {
                                        val value = field(k, v, e, obj) ?: continue
                                        val prefix = (if (length > 0) " · " else "") + value.first + ": "
                                        if (prefix.length + value.second.length > remaining) {
                                            append((prefix + value.second).take(remaining.coerceAtLeast(0)))
                                            append("…")
                                            break
                                        }
                                        append(prefix)
                                        val id = when {
                                            (k == "target_id" || k == "target") && str(obj["target_type"]) == "member" -> str(v)
                                            k == "nick" && e.targetType == "user" && str(v)?.isNotEmpty() == true -> e.targetId
                                            else -> null
                                        }
                                        if (id != null) detailNames.append(this, id, value.second) else append(value.second)
                                        remaining -= prefix.length + value.second.length
                                    }
                                }
                            }
                        }
                        if (long) NxButton(
                            if (open) t("server.auditLog.showLess") else t("server.auditLog.showAll"),
                            { if (open) expanded.remove(e.id) else expanded.add(e.id) },
                            kind = ButtonKind.LINK, inline = true,
                        )
                    }
                }
            }
        }
        if (failed) LoadFailed(if (list.isNotEmpty()) t("server.auditLog.theRestOfTheLogCould") else t("server.auditLog.theAuditLogCouldNotBe")) { load(list.lastOrNull()?.id) }
        if (list.isNotEmpty() && !done && !failed) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            NxButton(t("server.auditLog.loadMore"), { load(list.last().id) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, loading = busy)
        }
    }
}
