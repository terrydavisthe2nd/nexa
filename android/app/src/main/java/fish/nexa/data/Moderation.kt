package fish.nexa.data

import android.util.Log
import fish.nexa.Nexa
import fish.nexa.net.Activity
import fish.nexa.net.ApiError
import fish.nexa.net.Emoji
import fish.nexa.net.ServerTag
import fish.nexa.net.Sticker
import fish.nexa.net.WornTag
import fish.nexa.net.wireJson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// The staff's side of moderation (web/src/api/moderation.ts, api/bugs.ts and
// stores/moderation.ts): how much is waiting on them, what of it this member
// of staff has not read, and the panel's requests. The panel's views keep
// their own lists and hear about changes through `events`, so an open case,
// the Active Cases list and the log all move the moment anything happens.
// ---------------------------------------------------------------------------

@Serializable
data class CaseCounts(
    @SerialName("needs_reply") val needsReply: Int = 0,
    val open: Int = 0,
    @SerialName("in_effect") val inEffect: Int = 0,
    val closed: Int = 0,
    val all: Int = 0,
)

@Serializable
data class BugCounts(
    val waiting: Int = 0,
    val open: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val resolved: Int = 0,
    @SerialName("wont_fix") val wontFix: Int = 0,
    val closed: Int = 0,
    val all: Int = 0,
)

/** The case threads and bug reports waiting on the staff that this member of staff has not read. */
@Serializable
data class ModUnread(val cases: List<String> = emptyList(), val bugs: List<String> = emptyList()) {
    /** What every badge for the panel counts. */
    val count get() = cases.size + bugs.size
}

/** A case as the staff see it (StaffCase). */
@Serializable
data class StaffCase(
    val id: String,
    val number: Long = 0,
    val action: String = "",
    val reason: String = "",
    val details: JsonObject = JsonObject(emptyMap()),
    @SerialName("moderator_id") val moderatorId: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("lifted_at") val liftedAt: Long? = null,
    @SerialName("lifted_by") val liftedBy: String? = null,
    @SerialName("lift_reason") val liftReason: String = "",
    val status: String = "open",
    @SerialName("in_effect") val inEffect: Boolean = false,
    val ended: Boolean = false,
    val unread: Int = 0,
    val acknowledged: Boolean = false,
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
    @SerialName("user_id") val userId: String = "",
    @SerialName("target_username") val targetUsername: String = "",
    @SerialName("target_display_name") val targetDisplayName: String = "",
    val awaiting: String = "none",
    @SerialName("closed_at") val closedAt: Long? = null,
    @SerialName("closed_by") val closedBy: String? = null,
    @SerialName("notice_ack_at") val noticeAckAt: Long? = null,
    @SerialName("last_message") val lastMessage: CaseMessage? = null,
) {
    /** The same case as its member sees it, for the notice card both sides share. */
    fun asMember() = MemberCase(id, number, action, reason, details, moderatorId, createdAt, expiresAt, liftedAt, liftedBy, liftReason, status, inEffect, ended, unread, acknowledged, lastActivityAt)
}

/** A bug report as the staff see it (StaffBug). */
@Serializable
data class StaffBug(
    val id: String,
    val number: Long = 0,
    val subject: String = "",
    val description: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
    val status: String = "open",
    @SerialName("status_at") val statusAt: Long? = null,
    @SerialName("status_by") val statusBy: String? = null,
    val awaiting: String = "none",
    val attachments: List<NexaFile> = emptyList(),
    val unread: Int = 0,
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("user_id") val userId: String = "",
    @SerialName("reporter_username") val reporterUsername: String = "",
    @SerialName("reporter_display_name") val reporterDisplayName: String = "",
    @SerialName("last_message") val lastMessage: BugMessage? = null,
) {
    fun asMember() = MemberBug(id, number, subject, description, context, status, statusAt, statusBy, awaiting, attachments, unread, lastActivityAt, createdAt)
}

@Serializable
data class ModPresence(val status: String = "offline", val activities: List<Activity>? = null)

/** An account that shares an address with another (an alt account, or someone on the same network). */
@Serializable
data class ModAlt(
    val user: ModUser,
    /** How many addresses the two have in common. */
    @SerialName("shared_ips") val sharedIps: Int = 0,
    /** When either was last at a shared address. */
    @SerialName("last_shared_at") val lastSharedAt: Long = 0,
)

/** The month's Realtime egress against the allowance, which SFU and TURN share (web MeterStatus). */
@Serializable
data class MeterStatus(
    val month: String = "",
    @SerialName("used_bytes") val used: Long = 0,
    @SerialName("limit_bytes") val limit: Long = 0,
    @SerialName("estimate_bytes") val estimate: Long = 0,
    /** Whether used rests on Cloudflare's measurement, which covers up to measuredUntil. */
    val measured: Boolean = false,
    @SerialName("measured_sfu_bytes") val measuredSfu: Long = 0,
    @SerialName("measured_turn_bytes") val measuredTurn: Long = 0,
    @SerialName("measured_until") val measuredUntil: Long = 0,
    @SerialName("measured_at") val measuredAt: Long = 0,
    @SerialName("video_paused") val videoPaused: Boolean = false,
    @SerialName("calls_paused") val callsPaused: Boolean = false,
)

/**
 * What one account's calls used over a span of days (web CallTotals): media to
 * its devices, media its own tracks sent others, TURN relay (measured only) and
 * time in calls; measured when Cloudflare measured every day of it.
 */
@Serializable
data class CallTotals(
    @SerialName("received_bytes") val received: Long = 0,
    @SerialName("sent_bytes") val sent: Long = 0,
    @SerialName("relay_bytes") val relay: Long = 0,
    val seconds: Long = 0,
    val measured: Boolean = false,
    @SerialName("last_day") val lastDay: String = "",
)

/** The account a row on the Calls page is about, as its public profile. */
@Serializable
data class CallUser(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    val flags: Long = 0,
)

/** One account on the staff's Calls page (web ModCallRow). */
@Serializable
data class ModCallRow(
    @SerialName("user_id") val userId: String,
    /** Null for someone without an account (a guest), or one deleted since. */
    val user: CallUser? = null,
    @SerialName("received_bytes") val received: Long = 0,
    @SerialName("sent_bytes") val sent: Long = 0,
    @SerialName("relay_bytes") val relay: Long = 0,
    val seconds: Long = 0,
    val measured: Boolean = false,
    @SerialName("in_call") val inCall: Boolean = false,
    /** Past its TURN relay for the day, so it gets none until the next UTC day. */
    @SerialName("relay_cut_off") val relayCutOff: Boolean = false,
)

/** An account in the panel's grid (ModUser). */
@Serializable
data class ModUser(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("banner_id") val bannerId: String? = null,
    @SerialName("about_me") val aboutMe: String = "",
    val pronouns: String = "",
    @SerialName("accent_color") val accentColor: Long? = null,
    @SerialName("accent_color_2") val accentColor2: Long? = null,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("status_emoji") val statusEmoji: String = "",
    @SerialName("status_expires_at") val statusExpiresAt: Long? = null,
    val flags: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("typing_style") val typingStyle: JsonElement? = null,
    @SerialName("name_style") val nameStyle: JsonElement? = null,
    @SerialName("server_tag") val serverTag: WornTag? = null,
    @SerialName("admin_flags") val adminFlags: Long = 0,
    @SerialName("case_count") val caseCount: Int = 0,
    @SerialName("open_cases") val openCases: Int = 0,
    @SerialName("awaiting_cases") val awaitingCases: Int = 0,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
    /** How many other accounts have used one of this account's addresses. */
    @SerialName("alt_count") val altCount: Int = 0,
    val mute: Restriction? = null,
    val ban: Restriction? = null,
    /** Why the viewer cannot act on this account ("" when they can). */
    val blocked: String = "",
    val presence: ModPresence? = null,
    /** The profile's widgets, on one account's page only. */
    val widgets: JsonElement? = null,
) {
    /** Holds any staff rank (data/StaffRanks). */
    val isStaff get() = adminFlags and StaffRanks.ALL != 0L
    val isDisabled get() = adminFlags and Moderation.FLAG_DISABLED != 0L
    val isDeleted get() = adminFlags and Moderation.FLAG_DELETED != 0L
}

/** A server someone owns, or has a profile of their own in. */
@Serializable
data class ModMembership(
    @SerialName("server_id") val serverId: String,
    val name: String = "",
    @SerialName("icon_id") val iconId: String? = null,
    val owner: Boolean = false,
    val nickname: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("banner_id") val bannerId: String? = null,
    val bio: String = "",
    val pronouns: String = "",
    val widgets: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("joined_at") val joinedAt: Long = 0,
) {
    /** Words of its own that a server_profiles reset clears (hasServerProfile). */
    val hasProfile get() = bio.isNotEmpty() || pronouns.isNotEmpty() || (widgets.isNotEmpty() && widgets != "[]")
}

/** An app as the staff's Bots tab lists it; its id is its bot's (web api/moderation.ts ModApp). */
@Serializable
data class ModApp(
    val id: String,
    @SerialName("owner_id") val ownerId: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("icon_id") val iconId: String? = null,
    /** It reads every message it can see. */
    @SerialName("reads_all") val readsAll: Boolean = false,
    /** Its bot is banned or turned off, so the app does nothing. */
    val disabled: Boolean = false,
    val public: Boolean = true,
    val servers: Int = 0,
    val accounts: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
)

/** A server as the panel shows it. */
@Serializable
data class ModServer(
    val id: String,
    val nsfw: Boolean = false,
    val name: String = "",
    @SerialName("icon_id") val iconId: String? = null,
    @SerialName("banner_id") val bannerId: String? = null,
    @SerialName("owner_id") val ownerId: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("channel_count") val channelCount: Int = 0,
    val tag: ServerTag? = null,
    /** Its owner lists it on Discover. */
    val discoverable: Boolean = false,
    /** Staff hid it from Discover, and it stays hidden until they show it again. */
    @SerialName("discovery_hidden") val discoveryHidden: Boolean = false,
    val blocked: String = "",
)

/** A server a pack or an emoji belongs to. */
@Serializable
data class ModServerRef(
    val id: String,
    val name: String = "",
    @SerialName("icon_id") val iconId: String? = null,
    @SerialName("owner_id") val ownerId: String = "",
)

/** A sticker or emoji pack as the panel shows it. */
@Serializable
data class ModPack(
    val id: String,
    val kind: String = "sticker",
    @SerialName("owner_user_id") val ownerUserId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    val name: String = "",
    val description: String = "",
    @SerialName("cover_sticker_id") val coverStickerId: String? = null,
    val public: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    val stickers: List<Sticker> = emptyList(),
    @SerialName("sticker_count") val stickerCount: Int = 0,
    val installs: Int = 0,
    @SerialName("discover_blocked") val discoverBlocked: Boolean = false,
    @SerialName("owner_id") val ownerId: String = "",
    val blocked: String = "",
) {
    val emoji get() = kind == "emoji"
}

/** One sticker or emoji among the newest uploads. */
@Serializable
data class ModUpload(
    val kind: String = "sticker",
    val id: String,
    val name: String = "",
    @SerialName("blob_id") val blobId: String = "",
    val animated: Boolean = false,
    val format: String? = null,
    @SerialName("pack_id") val packId: String? = null,
    @SerialName("pack_name") val packName: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class ModLogEntry(
    val id: String,
    val action: String = "",
    @SerialName("moderator_id") val moderatorId: String = "",
    @SerialName("moderator_username") val moderatorUsername: String = "",
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("target_username") val targetUsername: String = "",
    @SerialName("case_id") val caseId: String? = null,
    @SerialName("case_number") val caseNumber: Long? = null,
    val reason: String = "",
    val details: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class PurgeCounts(
    val messages: Int = 0,
    val attachments: Int = 0,
    @SerialName("attachment_bytes") val attachmentBytes: Long = 0,
    @SerialName("owned_servers") val ownedServers: Int = 0,
    @SerialName("owned_groups") val ownedGroups: Int = 0,
    val servers: Int = 0,
    val groups: Int = 0,
    val packs: Int = 0,
    val reactions: Int = 0,
)

/** Something that happened that open views of the panel follow. */
sealed interface ModEvent {
    data class Case(val case: StaffCase, val messages: List<CaseMessage>) : ModEvent
    data class Log(val entry: ModLogEntry) : ModEvent
    data class News(val announcement: Announcement) : ModEvent
    data class NewsGone(val id: String) : ModEvent
    data class Bug(val report: StaffBug, val messages: List<BugMessage>) : ModEvent
}

object Moderation {
    private const val TAG = "Moderation"
    const val FLAG_ADMIN = 1L
    const val FLAG_DISABLED = 2L
    const val FLAG_DELETED = 4L

    val counts = MutableStateFlow<CaseCounts?>(null)
    val bugCounts = MutableStateFlow<BugCounts?>(null)
    private val _unread = MutableStateFlow(ModUnread())
    /** What the badges on the drawer, the Moderation row and the panel's tabs count. */
    val unread: StateFlow<ModUnread> = _unread
    val ownerId = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<ModEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ModEvent> = _events

    private fun <T> decode(s: KSerializer<T>, el: JsonElement?): T? =
        if (el == null || el is JsonNull) null else runCatching { wireJson.decodeFromJsonElement(s, el) }.getOrNull()

    private fun people(el: JsonElement?) = NexaInbox.mergePeople(NexaInbox.peopleOf(el))

    fun setUnread(u: ModUnread?) {
        if (u != null) _unread.value = u
    }

    /** The `staff` part of an account's standing: what is waiting on them (absent for everyone else). */
    fun applyStaff(state: JsonElement?) {
        val staff = (state as? JsonObject)?.get("staff") as? JsonObject ?: return
        decode(CaseCounts.serializer(), staff["counts"])?.let { counts.value = it }
        decode(BugCounts.serializer(), staff["bugs"])?.let { bugCounts.value = it }
        decode(ModUnread.serializer(), staff["unread"])?.let { _unread.value = it }
        (staff["owner_id"] as? JsonPrimitive)?.contentOrNull?.let { ownerId.value = it }
    }

    /** mod_case, mod_log, mod_bug, mod_unread, and the announcements every account hears. */
    fun onEvent(type: String, d: JsonElement?) {
        val o = d as? JsonObject
        try {
            when (type) {
                "mod_unread" -> decode(ModUnread.serializer(), d)?.let { _unread.value = it }
                "mod_case" -> {
                    o ?: return
                    people(o["people"])
                    decode(CaseCounts.serializer(), o["counts"])?.let { counts.value = it }
                    val c = decode(StaffCase.serializer(), o["case"]) ?: return
                    _events.tryEmit(ModEvent.Case(c, decode(ListSerializer(CaseMessage.serializer()), o["messages"]) ?: emptyList()))
                }
                "mod_log" -> {
                    o ?: return
                    people(o["people"])
                    decode(ModLogEntry.serializer(), o["entry"])?.let { _events.tryEmit(ModEvent.Log(it)) }
                }
                "mod_bug" -> {
                    o ?: return
                    people(o["people"])
                    decode(BugCounts.serializer(), o["counts"])?.let { bugCounts.value = it }
                    val r = decode(StaffBug.serializer(), o["report"]) ?: return
                    _events.tryEmit(ModEvent.Bug(r, decode(ListSerializer(BugMessage.serializer()), o["messages"]) ?: emptyList()))
                }
                "nexa_announcement" -> decode(Announcement.serializer(), o?.get("announcement"))?.let { _events.tryEmit(ModEvent.News(it)) }
                "nexa_announcement_delete" -> (o?.get("id") as? JsonPrimitive)?.contentOrNull?.let { _events.tryEmit(ModEvent.NewsGone(it)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "event $type", e)
        }
    }

    /** Mark as Read: every case thread and bug report waiting on the staff, on every device of this account. */
    suspend fun readAll() {
        val before = _unread.value
        // gone at once; the server's own word follows as mod_unread
        _unread.value = ModUnread()
        try {
            _unread.value = request(ModUnread.serializer(), "POST", "/moderation/read")
        } catch (e: Exception) {
            if (_unread.value.count == 0) _unread.value = before
            throw e
        }
    }

    /** Reads one case thread or bug report up to a line (all of it without one). */
    fun readThread(kind: String, id: String, messageId: String?) {
        val u = _unread.value
        val list = if (kind == "case") u.cases else u.bugs
        if (id !in list) return
        _unread.value = if (kind == "case") u.copy(cases = u.cases - id) else u.copy(bugs = u.bugs - id)
        val path = if (kind == "case") "/moderation/cases/$id/read" else "/moderation/bugs/$id/read"
        Nexa.scope.launch {
            runCatching { request(ModUnread.serializer(), "POST", path, buildJsonObject { if (messageId != null) put("message_id", messageId) }) }
                .onSuccess { _unread.value = it }
        }
    }

    fun reset() {
        counts.value = null
        bugCounts.value = null
        _unread.value = ModUnread()
        ownerId.value = null
    }

    // -----------------------------------------------------------------------
    // requests

    private suspend fun <T> request(s: KSerializer<T>, method: String, path: String, body: JsonElement? = null): T =
        Nexa.api.request(method, path, s, body)

    private fun q(vararg pairs: Pair<String, Any?>): String {
        val parts = pairs.mapNotNull { (k, v) ->
            val s = v?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            "$k=" + java.net.URLEncoder.encode(s, "UTF-8")
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    private fun obj(el: JsonElement): JsonObject = el as? JsonObject ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
    private fun <T> list(o: JsonObject, key: String, s: KSerializer<T>): List<T> = decode(ListSerializer(s), o[key]) ?: emptyList()
    private fun peopleIn(o: JsonObject) = people(o["people"])
    private val serverRefs = MapSerializer(String.serializer(), ModServerRef.serializer())
    private fun serversIn(o: JsonObject): Map<String, ModServerRef> = decode(serverRefs, o["servers"]) ?: emptyMap()
    private fun bool(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull == "true"

    data class Summary(val counts: CaseCounts?, val bugs: BugCounts?, val unread: ModUnread?, val ownerId: String?)

    suspend fun summary(): Summary {
        val o = obj(Nexa.api.get("/moderation/summary"))
        peopleIn(o)
        val s = Summary(decode(CaseCounts.serializer(), o["counts"]), decode(BugCounts.serializer(), o["bugs"]), decode(ModUnread.serializer(), o["unread"]), (o["owner_id"] as? JsonPrimitive)?.contentOrNull)
        s.counts?.let { counts.value = it }
        s.bugs?.let { bugCounts.value = it }
        s.unread?.let { _unread.value = it }
        s.ownerId?.let { ownerId.value = it }
        return s
    }

    suspend fun users(q: String, show: String, sort: String, cursor: String, limit: Int): Pair<List<ModUser>, String> {
        val o = obj(Nexa.api.get("/moderation/users" + q("q" to q.trim(), "show" to show, "sort" to sort, "cursor" to cursor, "limit" to limit)))
        return list(o, "users", ModUser.serializer()) to ((o["next_cursor"] as? JsonPrimitive)?.contentOrNull ?: "")
    }

    data class Person(val user: ModUser, val cases: List<StaffCase>, val servers: List<ModMembership>, val packs: List<ModPack>)

    suspend fun user(id: String): Person {
        val o = obj(Nexa.api.get("/moderation/users/$id"))
        peopleIn(o)
        val user = decode(ModUser.serializer(), o["user"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return Person(user, list(o, "cases", StaffCase.serializer()), list(o, "servers", ModMembership.serializer()), list(o, "packs", ModPack.serializer()))
    }

    data class ServerPage(val servers: List<ModServer>, val total: Int, val more: Boolean)

    data class AppPage(val apps: List<ModApp>, val total: Int, val more: Boolean)

    /** Every app on the instance, for the staff's Bots tab (GET /moderation/apps). */
    suspend fun apps(q: String, sort: String, offset: Int, limit: Int): AppPage {
        val o = obj(Nexa.api.get("/moderation/apps" + q("q" to q.trim(), "sort" to sort, "offset" to offset.takeIf { it > 0 }, "limit" to limit)))
        peopleIn(o)
        return AppPage(list(o, "apps", ModApp.serializer()), (o["total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, bool(o, "has_more"))
    }

    data class CallsPage(
        val accounts: List<ModCallRow>,
        val more: Boolean,
        val meter: MeterStatus?,
        /** What Cloudflare measured that no account could be named for. */
        val unattributed: CallTotals,
        /** Whether Cloudflare's measurements are set up, rather than Nexa's estimate alone. */
        val measurements: Boolean,
        val turnDailyLimit: Long,
    )

    /** Accounts by what their calls used over a period (today, week, month), largest first. */
    suspend fun calls(period: String, sort: String, offset: Int): CallsPage {
        val o = obj(Nexa.api.get("/moderation/calls" + q("period" to period, "sort" to sort, "offset" to offset.takeIf { it > 0 })))
        return CallsPage(
            list(o, "accounts", ModCallRow.serializer()),
            bool(o, "has_more"),
            decode(MeterStatus.serializer(), o["meter"]),
            decode(CallTotals.serializer(), o["unattributed"]) ?: CallTotals(),
            bool(o, "measurements"),
            (o["turn_daily_limit_bytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }

    data class UserCalls(val today: CallTotals, val week: CallTotals, val month: CallTotals, val inCall: Boolean, val relayCutOff: Boolean, val measurements: Boolean)

    /** One account's calls today, over seven days and this month. */
    suspend fun userCalls(id: String): UserCalls {
        val o = obj(Nexa.api.get("/moderation/users/$id/calls"))
        fun totals(k: String) = decode(CallTotals.serializer(), o[k]) ?: CallTotals()
        return UserCalls(totals("today"), totals("week"), totals("month"), bool(o, "in_call"), bool(o, "relay_cut_off"), bool(o, "measurements"))
    }

    suspend fun servers(q: String, sort: String, offset: Int, limit: Int, discovery: String? = null): ServerPage {
        val o = obj(Nexa.api.get("/moderation/servers" + q("discovery" to discovery, "q" to q.trim(), "sort" to sort, "offset" to offset.takeIf { it > 0 }, "limit" to limit)))
        peopleIn(o)
        return ServerPage(list(o, "servers", ModServer.serializer()), (o["total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, bool(o, "has_more"))
    }

    data class OneServer(val server: ModServer, val cases: List<StaffCase>, val emojis: List<Emoji>, val packs: List<ModPack>)

    suspend fun server(id: String): OneServer {
        val o = obj(Nexa.api.get("/moderation/servers/$id"))
        peopleIn(o)
        val server = decode(ModServer.serializer(), o["server"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return OneServer(server, list(o, "cases", StaffCase.serializer()), list(o, "emojis", Emoji.serializer()), list(o, "packs", ModPack.serializer()))
    }

    data class PackList(val packs: List<ModPack>, val servers: Map<String, ModServerRef>, val more: Boolean)

    suspend fun packs(q: String, scope: String, sort: String, offset: Int, limit: Int): PackList {
        val o = obj(Nexa.api.get("/moderation/packs" + q("q" to q.trim(), "scope" to scope, "sort" to sort, "offset" to offset.takeIf { it > 0 }, "limit" to limit)))
        peopleIn(o)
        return PackList(list(o, "packs", ModPack.serializer()), serversIn(o), bool(o, "has_more"))
    }

    data class OnePack(val pack: ModPack, val cases: List<StaffCase>, val servers: Map<String, ModServerRef>)

    suspend fun pack(id: String): OnePack {
        val o = obj(Nexa.api.get("/moderation/packs/$id"))
        peopleIn(o)
        val pack = decode(ModPack.serializer(), o["pack"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return OnePack(pack, list(o, "cases", StaffCase.serializer()), serversIn(o))
    }

    data class Uploads(val uploads: List<ModUpload>, val servers: Map<String, ModServerRef>, val more: Boolean)

    suspend fun uploads(after: ModUpload?, limit: Int): Uploads {
        val o = obj(Nexa.api.get("/moderation/uploads" + q("before" to after?.createdAt, "before_id" to after?.id, "limit" to limit)))
        peopleIn(o)
        return Uploads(list(o, "uploads", ModUpload.serializer()), serversIn(o), bool(o, "has_more"))
    }

    private suspend fun caseOf(method: String, path: String, body: JsonElement?): StaffCase {
        val o = obj(Nexa.api.request(method, path, body))
        peopleIn(o)
        return decode(StaffCase.serializer(), o["case"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
    }

    /** Takes down stickers in a pack, its name, description or public listing, or the whole pack. */
    suspend fun actOnPack(id: String, stickers: List<String>, fields: List<String>, delete: Boolean, block: Boolean, reason: String): StaffCase =
        caseOf("POST", "/moderation/packs/$id/actions", buildJsonObject {
            if (delete) put("delete", true) else {
                put("stickers", JsonArray(stickers.map { JsonPrimitive(it) }))
                put("fields", JsonArray(fields.map { JsonPrimitive(it) }))
            }
            put("block", block)
            put("reason", reason)
        })

    /** Lets a server the staff hid from Discover show up there again. */
    suspend fun showOnDiscover(id: String) {
        Nexa.api.request("DELETE", "/moderation/servers/$id/discovery-hold")
    }

    suspend fun relistPack(id: String, reason: String) {
        Nexa.api.request("POST", "/moderation/packs/$id/relist", buildJsonObject { put("reason", reason) })
    }

    suspend fun actOnEmojis(serverId: String, emojis: List<String>, block: Boolean, reason: String): StaffCase =
        caseOf("POST", "/moderation/servers/$serverId/emojis/actions", buildJsonObject {
            put("emojis", JsonArray(emojis.map { JsonPrimitive(it) }))
            put("block", block)
            put("reason", reason)
        })

    suspend fun actOnServer(id: String, fields: List<String>, reason: String): StaffCase =
        caseOf("POST", "/moderation/servers/$id/actions", buildJsonObject {
            put("fields", JsonArray(fields.map { JsonPrimitive(it) }))
            put("reason", reason)
        })

    /** The accounts seen at the same addresses as this one, the most shared first. */
    suspend fun alts(id: String): List<ModAlt> = list(obj(Nexa.api.get("/moderation/users/$id/alts")), "alts", ModAlt.serializer())

    suspend fun purgePreview(id: String): PurgeCounts = request(PurgeCounts.serializer(), "GET", "/moderation/users/$id/purge-preview")

    /** An action on an account: the body is ActionRequest's. */
    suspend fun act(id: String, body: JsonObject): StaffCase = caseOf("POST", "/moderation/users/$id/actions", body)

    data class CasePage(val cases: List<StaffCase>, val counts: CaseCounts?)

    suspend fun cases(view: String, after: StaffCase?, limit: Int): CasePage {
        val o = obj(Nexa.api.get("/moderation/cases" + q("view" to view, "before_at" to after?.lastActivityAt, "before_id" to after?.id, "limit" to limit)))
        peopleIn(o)
        val c = decode(CaseCounts.serializer(), o["counts"])
        c?.let { counts.value = it }
        return CasePage(list(o, "cases", StaffCase.serializer()), c)
    }

    data class CaseThread(val case: StaffCase, val messages: List<CaseMessage>)

    suspend fun thread(caseId: String): CaseThread {
        val o = obj(Nexa.api.get("/moderation/cases/$caseId"))
        peopleIn(o)
        val c = decode(StaffCase.serializer(), o["case"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return CaseThread(c, list(o, "messages", CaseMessage.serializer()))
    }

    private fun files(fs: List<NexaFile>) = JsonArray(fs.map { f -> buildJsonObject { put("id", f.id); put("name", f.name) } })

    suspend fun reply(caseId: String, body: String, fs: List<NexaFile>): CaseThread {
        val o = obj(Nexa.api.request("POST", "/moderation/cases/$caseId/messages", buildJsonObject { put("body", body); put("files", files(fs)) }))
        val c = decode(StaffCase.serializer(), o["case"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return CaseThread(c, list(o, "messages", CaseMessage.serializer()))
    }

    suspend fun lift(caseId: String, reason: String): StaffCase = caseOf("POST", "/moderation/cases/$caseId/lift", buildJsonObject { put("reason", reason) })

    suspend fun setClosed(caseId: String, closed: Boolean): StaffCase = caseOf("POST", "/moderation/cases/$caseId/${if (closed) "close" else "reopen"}", null)

    suspend fun log(actions: List<String>?, q: String, before: String?, limit: Int): List<ModLogEntry> {
        val o = obj(Nexa.api.get("/moderation/log" + q("action" to actions?.takeIf { it.isNotEmpty() }?.joinToString(","), "q" to q.trim(), "before" to before, "limit" to limit)))
        peopleIn(o)
        return list(o, "entries", ModLogEntry.serializer())
    }

    data class News(val announcements: List<Announcement>, val recipients: Int)

    suspend fun announcements(): News {
        val o = obj(Nexa.api.get("/moderation/announcements"))
        peopleIn(o)
        return News(list(o, "announcements", Announcement.serializer()), (o["recipients"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)
    }

    suspend fun announce(title: String, body: String): Announcement {
        val o = obj(Nexa.api.request("POST", "/moderation/announcements", buildJsonObject { put("title", title); put("body", body) }))
        peopleIn(o)
        return decode(Announcement.serializer(), o["announcement"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
    }

    suspend fun retract(id: String) {
        Nexa.api.request("DELETE", "/moderation/announcements/$id")
    }

    /** Gives or takes a staff rank ("" for none; the owner alone), or disables or restores sign-in. */
    suspend fun access(id: String, rank: String? = null, disabled: Boolean? = null) {
        Nexa.api.request("PATCH", "/admin/users/$id", buildJsonObject {
            if (rank != null) put("rank", rank)
            if (disabled != null) put("disabled", disabled)
        })
    }

    // bug reports, the staff's side

    data class BugList(val reports: List<StaffBug>, val counts: BugCounts?, val more: Boolean)

    suspend fun bugs(view: String, files: Boolean, q: String, sort: String, after: StaffBug?, limit: Int): BugList {
        val status = if (view == "waiting" || view == "all") null else view
        val o = obj(Nexa.api.get("/moderation/bugs" + q(
            "status" to status,
            "waiting" to if (view == "waiting") "1" else null,
            "files" to if (files) "1" else null,
            "q" to q.trim(),
            "sort" to sort,
            "before_at" to if (after != null && sort == "activity") after.lastActivityAt else null,
            "before_id" to after?.id,
            "limit" to limit,
        )))
        peopleIn(o)
        val c = decode(BugCounts.serializer(), o["counts"])
        c?.let { bugCounts.value = it }
        return BugList(list(o, "reports", StaffBug.serializer()), c, bool(o, "has_more"))
    }

    data class BugThread(val report: StaffBug, val messages: List<BugMessage>)

    suspend fun bugThread(id: String): BugThread {
        val o = obj(Nexa.api.get("/moderation/bugs/$id"))
        peopleIn(o)
        val r = decode(StaffBug.serializer(), o["report"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return BugThread(r, list(o, "messages", BugMessage.serializer()))
    }

    suspend fun bugReply(id: String, body: String, fs: List<NexaFile>): BugThread {
        val o = obj(Nexa.api.request("POST", "/moderation/bugs/$id/messages", buildJsonObject { put("body", body); put("files", files(fs)) }))
        peopleIn(o)
        val r = decode(StaffBug.serializer(), o["report"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return BugThread(r, list(o, "messages", BugMessage.serializer()))
    }

    suspend fun setBugStatus(id: String, status: String): BugThread {
        val o = obj(Nexa.api.request("POST", "/moderation/bugs/$id/status", buildJsonObject { put("status", status) }))
        peopleIn(o)
        val r = decode(StaffBug.serializer(), o["report"]) ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")
        return BugThread(r, list(o, "messages", BugMessage.serializer()))
    }
}
