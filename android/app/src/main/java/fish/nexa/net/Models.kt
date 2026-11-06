package fish.nexa.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the chatd REST API and gateway (web/src/api/types.ts).
 * Every id is a decimal string, every timestamp Unix milliseconds, every
 * binary field standard base64. Go marshals nil slices as null, so every list
 * has a default and `coerceInputValues` turns a null into it.
 */
typealias Snowflake = String

val wireJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class WornTag(
    @SerialName("server_id") val serverId: Snowflake = "",
    val text: String = "",
    val badge: String = "",
    val color: Long? = null,
)

@Serializable
data class PublicUser(
    val id: Snowflake,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: Snowflake? = null,
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    @SerialName("about_me") val aboutMe: String = "",
    val pronouns: String = "",
    @SerialName("accent_color") val accentColor: Long? = null,
    @SerialName("accent_color_2") val accentColor2: Long? = null,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("status_emoji") val statusEmoji: String = "",
    @SerialName("status_expires_at") val statusExpiresAt: Long? = null,
    @SerialName("public_key") val publicKey: String = "",
    val flags: Long = 0,
    @SerialName("group_link_only") val groupLinkOnly: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("typing_style") val typingStyle: JsonElement? = null,
    @SerialName("name_style") val nameStyle: JsonElement? = null,
    @SerialName("server_tag") val serverTag: WornTag? = null,
    /** Their subscription, shown as a badge: "" (none), "supporter" or "pro" (api/billing.go). */
    val plan: String = "",
) {
    val isAdmin get() = flags and 1L != 0L
    val isSystem get() = flags and 16L != 0L
    val isWebhook get() = flags and 32L != 0L
    val isGuest get() = flags and 8L != 0L
    /** An app's bot account. */
    val isBot get() = flags and BOT_FLAG != 0L
    /** A bot whose app reads every message where it is (its app has Message Content). */
    val readsAll get() = flags and BOT_READS_ALL_FLAG != 0L
    val name get() = displayName.ifBlank { username }

    companion object {
        const val BOT_FLAG = 64L
        const val BOT_READS_ALL_FLAG = 128L
    }
}

@Serializable
data class NexaRestriction(
    val until: Long? = null,
    val reason: String = "",
    @SerialName("case_id") val caseId: Snowflake? = null,
)

/** The account's moderation standing (api/moderation.ts NexaState); kept loose, as it grows. */
@Serializable
data class NexaState(
    val mute: JsonElement? = null,
    val ban: JsonElement? = null,
    val unread: Int = 0,
)

@Serializable
data class MeUser(
    val id: Snowflake,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: Snowflake? = null,
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    @SerialName("about_me") val aboutMe: String = "",
    val pronouns: String = "",
    @SerialName("accent_color") val accentColor: Long? = null,
    @SerialName("accent_color_2") val accentColor2: Long? = null,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("status_emoji") val statusEmoji: String = "",
    @SerialName("status_expires_at") val statusExpiresAt: Long? = null,
    @SerialName("public_key") val publicKey: String = "",
    val flags: Long = 0,
    @SerialName("group_link_only") val groupLinkOnly: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("typing_style") val typingStyle: JsonElement? = null,
    @SerialName("name_style") val nameStyle: JsonElement? = null,
    @SerialName("server_tag") val serverTag: WornTag? = null,
    /** Their subscription, shown as a badge: "" (none), "supporter" or "pro" (api/billing.go). */
    val plan: String = "",
    @SerialName("presence_pref") val presencePref: String = "online",
    @SerialName("dm_privacy") val dmPrivacy: String = "friends_servers",
    @SerialName("has_recovery") val hasRecovery: Boolean = false,
    @SerialName("mfa_enabled") val mfaEnabled: Boolean = false,
    @SerialName("backup_codes_left") val backupCodesLeft: Int = 0,
    val nexa: JsonElement? = null,
) {
    fun asPublic() = PublicUser(
        id, username, displayName, avatarId, bannerId, aboutMe, pronouns, accentColor, accentColor2, statusText, statusEmoji,
        statusExpiresAt, publicKey, flags and (1L or 16L or 32L), groupLinkOnly, createdAt, typingStyle, nameStyle, serverTag,
    )
    val isAdmin get() = flags and 1L != 0L
    val name get() = displayName.ifBlank { username }
}

@Serializable
data class Session(
    val id: Snowflake,
    @SerialName("user_agent") val userAgent: String = "",
    val ip: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    val current: Boolean = false,
)

@Serializable
data class Overwrite(
    @SerialName("channel_id") val channelId: Snowflake = "",
    @SerialName("target_id") val targetId: Snowflake,
    @SerialName("target_type") val targetType: String,
    val allow: String = "0",
    val deny: String = "0",
)

@Serializable
data class Channel(
    val id: Snowflake,
    @SerialName("server_id") val serverId: Snowflake? = null,
    val type: String = "text",
    val name: String = "",
    val topic: String = "",
    val position: Int = 0,
    @SerialName("parent_id") val parentId: Snowflake? = null,
    val nsfw: Boolean = false,
    @SerialName("slowmode_seconds") val slowmodeSeconds: Int = 0,
    @SerialName("user_limit") val userLimit: Int = 0,
    @SerialName("owner_id") val ownerId: Snowflake? = null,
    @SerialName("icon_id") val iconId: Snowflake? = null,
    @SerialName("last_message_id") val lastMessageId: Snowflake? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("recipient_ids") val recipientIds: List<Snowflake> = emptyList(),
    @SerialName("key_conv_id") val keyConvId: Snowflake = "",
    val overwrites: List<Overwrite>? = null,
    val locked: Boolean = false,
    val glyph: String? = null,
    /** How a thread stands (types thread and private_thread). */
    val thread: ThreadMeta? = null,
    /** A forum channel's own settings. */
    val forum: ForumSettings? = null,
    /** Where a link channel leads: a whole http or https address. */
    val url: String? = null,
    /** On a channel threads start in: how many quiet minutes close a new thread (absent: three days). */
    @SerialName("default_auto_archive") val defaultAutoArchive: Int? = null,
    /** On a thread, as listed or announced: how many people are in it. */
    @SerialName("member_count") val memberCount: Int? = null,
) {
    val isDmLike get() = type == "dm" || type == "group_dm" || type == "subchat"
    val isText get() = type == "text"
    val isVoice get() = type == "voice"
    val isCategory get() = type == "category"
    val isAnnouncement get() = type == "announcement"
    /** A channel that only holds posts: a forum, or a media channel (a forum of pictures and videos). */
    val isForum get() = type == "forum" || type == "media"
    /** A forum whose posts are pictures and videos, shown as a gallery. */
    val isMedia get() = type == "media"
    /** An entry in the channel list that opens an address; it holds no messages. */
    val isLink get() = type == "link"
    /** A thread, or a forum's post (which is one). */
    val isThread get() = type == "thread" || type == "private_thread"
    /** Where messages are read and written in a server: text and announcement channels, and threads. */
    val isTextLike get() = type == "text" || type == "announcement" || isThread
    /** Holds messages that count as unread: a text-like channel, or the chat of a voice channel. */
    val hasChat get() = isTextLike || type == "voice"
    /** Channels threads start in (a forum's posts are its threads). */
    val holdsThreads get() = type == "text" || type == "announcement" || isForum
}

/** How a thread stands (web ThreadMeta). */
@Serializable
data class ThreadMeta(
    val archived: Boolean = false,
    @SerialName("archived_at") val archivedAt: Long? = null,
    val locked: Boolean = false,
    /** Minutes without messages after which it closes itself. */
    @SerialName("auto_archive") val autoArchive: Int = 4320,
    /** The message it was started from, if any. */
    @SerialName("starter_id") val starterId: Snowflake? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    /** A forum post's tags. */
    @SerialName("applied_tags") val appliedTags: List<Snowflake> = emptyList(),
)

@Serializable
data class ForumTag(
    val id: Snowflake,
    val name: String = "",
    /** A single emoji, or "" for none. */
    val emoji: String = "",
    /** Only people who manage threads may put it on a post. */
    val moderated: Boolean = false,
)

@Serializable
data class ForumSettings(
    val tags: List<ForumTag> = emptyList(),
    @SerialName("default_reaction") val defaultReaction: String = "",
    val sort: String = "activity",
    val layout: String = "list",
    @SerialName("require_tag") val requireTag: Boolean = false,
    @SerialName("pinned_post") val pinnedPost: Snowflake? = null,
    /** A media channel whose pictures and videos offer no download buttons. */
    @SerialName("hide_downloads") val hideDownloads: Boolean = false,
)

/** A thread as the message it was started from shows it. */
@Serializable
data class ThreadSummary(
    val id: Snowflake,
    val name: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_message_id") val lastMessageId: Snowflake? = null,
    val archived: Boolean = false,
    val locked: Boolean = false,
    /** A public thread's newest message, sealed, for the card under the message it hangs from. */
    @SerialName("last_message") val lastMessage: Message? = null,
)

/** On a copy of a post published from an announcement channel: where it came from. */
@Serializable
data class CrosspostInfo(
    @SerialName("follow_id") val followId: Snowflake = "",
    @SerialName("server_id") val serverId: Snowflake = "",
    @SerialName("server_name") val serverName: String = "",
    @SerialName("server_icon_id") val serverIconId: Snowflake? = null,
    @SerialName("channel_id") val channelId: Snowflake = "",
    @SerialName("channel_name") val channelName: String = "",
    @SerialName("message_id") val messageId: Snowflake = "",
    val deleted: Boolean = false,
)

/** On AutoMod's note in an alert channel: what it caught and did. */
@Serializable
data class AutomodNote(
    @SerialName("event_id") val eventId: Snowflake = "",
    @SerialName("rule_id") val ruleId: Snowflake? = null,
    @SerialName("rule_name") val ruleName: String = "",
    @SerialName("rule_kind") val ruleKind: String = "",
    @SerialName("channel_id") val channelId: Snowflake? = null,
    val actions: List<String> = emptyList(),
    /** What was caught, sealed with the server's key. */
    val content: JsonElement? = null,
    val matched: String? = null,
    val mentions: Int? = null,
    val raid: Boolean = false,
    val messages: Int? = null,
    val seconds: Int? = null,
    val title: Boolean = false,
    @SerialName("message_id") val messageId: Snowflake? = null,
)

@Serializable
data class ServerTag(
    val text: String = "",
    val badge: String = "",
    val color: Long? = null,
    val limited: Boolean = false,
    val roles: List<Snowflake> = emptyList(),
    val join: Boolean = false,
)

@Serializable
data class Server(
    val id: Snowflake,
    val nsfw: Boolean = false,
    val name: String = "",
    @SerialName("icon_id") val iconId: Snowflake? = null,
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    @SerialName("owner_id") val ownerId: Snowflake = "",
    val description: String = "",
    @SerialName("system_channel_id") val systemChannelId: Snowflake? = null,
    @SerialName("default_notifications") val defaultNotifications: Int = 0,
    val flags: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    val tag: ServerTag? = null,
    /** When the server takes new members again; null while it takes them. */
    @SerialName("invites_paused_until") val invitesPausedUntil: Long? = null,
    /** How it is listed on Discovery. */
    val discoverable: Boolean = false,
    @SerialName("discovery_category") val discoveryCategory: String = "",
    @SerialName("discovery_tags") val discoveryTags: List<String> = emptyList(),
    @SerialName("discovery_language") val discoveryLanguage: String = "",
    /** Taken off Discovery by Nexa's staff. */
    @SerialName("discovery_hidden") val discoveryHidden: Boolean = false,
    /** How old an account, and how long a membership, has to be to talk here. */
    @SerialName("verify_account_minutes") val verifyAccountMinutes: Int = 0,
    @SerialName("verify_member_minutes") val verifyMemberMinutes: Int = 0,
    /** When the AutoMod rules members' apps apply last changed; 0 when there are none. */
    @SerialName("automod_at") val automodAt: Long = 0,
    /** How many Pro accounts boost it; one or more lets every member send bigger files. */
    val boosts: Int = 0,
) {
    val invitesPaused get() = (invitesPausedUntil ?: 0L) > System.currentTimeMillis()
}

@Serializable
data class Member(
    @SerialName("server_id") val serverId: Snowflake = "",
    @SerialName("user_id") val userId: Snowflake,
    val nickname: String = "",
    @SerialName("avatar_id") val avatarId: Snowflake? = null,
    /** The rest of the member's profile in this server, shown there in place of their own. */
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    val bio: String = "",
    val pronouns: String = "",
    @SerialName("accent_color") val accentColor: Long? = null,
    @SerialName("accent_color_2") val accentColor2: Long? = null,
    @SerialName("joined_at") val joinedAt: Long = 0,
    @SerialName("timeout_until") val timeoutUntil: Long? = null,
    val flags: Long = 0,
    @SerialName("role_ids") val roleIds: List<Snowflake> = emptyList(),
) {
    /** AutoMod holds the member for what their name or profile says. */
    val quarantined get() = flags and 1L != 0L
}

@Serializable
data class Role(
    val id: Snowflake,
    @SerialName("server_id") val serverId: Snowflake = "",
    val name: String = "",
    val color: Long = 0,
    val hoist: Boolean = false,
    val mentionable: Boolean = false,
    val position: Int = 0,
    val permissions: String = "0",
    @SerialName("icon_id") val iconId: Snowflake? = null,
    /** An emoji for its icon ("custom:<id>" for one of the server's own), or "". */
    @SerialName("icon_emoji") val iconEmoji: String = "",
    /** With a second colour the name is a gradient; with a third, a shimmer across all three. */
    val color2: Long? = null,
    val color3: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class ServerView(
    val id: Snowflake,
    val nsfw: Boolean = false,
    val name: String = "",
    @SerialName("icon_id") val iconId: Snowflake? = null,
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    @SerialName("owner_id") val ownerId: Snowflake = "",
    val description: String = "",
    @SerialName("system_channel_id") val systemChannelId: Snowflake? = null,
    @SerialName("default_notifications") val defaultNotifications: Int = 0,
    val flags: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    val tag: ServerTag? = null,
    @SerialName("invites_paused_until") val invitesPausedUntil: Long? = null,
    val discoverable: Boolean = false,
    @SerialName("discovery_category") val discoveryCategory: String = "",
    @SerialName("discovery_tags") val discoveryTags: List<String> = emptyList(),
    @SerialName("discovery_language") val discoveryLanguage: String = "",
    @SerialName("discovery_hidden") val discoveryHidden: Boolean = false,
    @SerialName("verify_account_minutes") val verifyAccountMinutes: Int = 0,
    @SerialName("verify_member_minutes") val verifyMemberMinutes: Int = 0,
    @SerialName("automod_at") val automodAt: Long = 0,
    /** How many Pro accounts boost it; one or more lets every member send bigger files. */
    val boosts: Int = 0,
    val channels: List<Channel> = emptyList(),
    /** Threads still going that this member can see, and the ones they are in. */
    val threads: List<Channel> = emptyList(),
    @SerialName("joined_threads") val joinedThreads: List<Snowflake> = emptyList(),
    val roles: List<Role> = emptyList(),
    val members: List<Member> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 0,
    val me: Member? = null,
    @SerialName("my_permissions") val myPermissions: String = "0",
) {
    fun server() = Server(
        id = id, nsfw = nsfw, name = name, iconId = iconId, bannerId = bannerId, ownerId = ownerId, description = description, systemChannelId = systemChannelId,
        defaultNotifications = defaultNotifications, flags = flags, createdAt = createdAt, tag = tag, invitesPausedUntil = invitesPausedUntil,
        discoverable = discoverable, discoveryCategory = discoveryCategory, discoveryTags = discoveryTags, discoveryLanguage = discoveryLanguage,
        discoveryHidden = discoveryHidden, verifyAccountMinutes = verifyAccountMinutes, verifyMemberMinutes = verifyMemberMinutes, automodAt = automodAt,
    )
}

@Serializable
data class Invite(
    val code: String,
    @SerialName("server_id") val serverId: Snowflake? = null,
    @SerialName("channel_id") val channelId: Snowflake? = null,
    @SerialName("inviter_id") val inviterId: Snowflake = "",
    @SerialName("max_uses") val maxUses: Int = 0,
    val uses: Int = 0,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class InviteServer(
    val id: Snowflake,
    val name: String = "",
    @SerialName("icon_id") val iconId: Snowflake? = null,
    @SerialName("banner_id") val bannerId: Snowflake? = null,
    val description: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("online_count") val onlineCount: Int = 0,
)

@Serializable
data class InviteGroup(
    val id: Snowflake,
    val name: String = "",
    @SerialName("icon_id") val iconId: Snowflake? = null,
    @SerialName("member_count") val memberCount: Int = 0,
)

@Serializable
data class InviteChannel(val id: Snowflake, val name: String = "", val type: String = "text")

@Serializable
data class InvitePreview(
    val code: String,
    val nsfw: Boolean = false,
    @SerialName("age_gate") val ageGate: Boolean = false,
    val server: InviteServer? = null,
    val group: InviteGroup? = null,
    val channel: InviteChannel? = null,
    val inviter: PublicUser? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

@Serializable
data class Ban(
    @SerialName("server_id") val serverId: Snowflake = "",
    @SerialName("user_id") val userId: Snowflake,
    val reason: String = "",
    @SerialName("banned_by") val bannedBy: Snowflake = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class AuditEntry(
    val id: Snowflake,
    @SerialName("server_id") val serverId: Snowflake = "",
    @SerialName("actor_id") val actorId: Snowflake = "",
    val action: String = "",
    @SerialName("target_type") val targetType: String = "",
    @SerialName("target_id") val targetId: Snowflake? = null,
    val changes: JsonElement? = null,
    val reason: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class Emoji(
    val id: Snowflake,
    @SerialName("server_id") val serverId: Snowflake = "",
    val name: String = "",
    @SerialName("blob_id") val blobId: Snowflake = "",
    val animated: Boolean = false,
    @SerialName("creator_id") val creatorId: Snowflake = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class Sticker(
    val id: Snowflake,
    @SerialName("pack_id") val packId: Snowflake = "",
    val name: String = "",
    val tags: String = "",
    @SerialName("blob_id") val blobId: Snowflake = "",
    val format: String = "png",
    val position: Int = 0,
    @SerialName("creator_id") val creatorId: Snowflake = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val animated: Boolean = false,
)

@Serializable
data class StickerPack(
    val id: Snowflake,
    val kind: String = "sticker",
    @SerialName("owner_user_id") val ownerUserId: Snowflake? = null,
    @SerialName("server_id") val serverId: Snowflake? = null,
    val name: String = "",
    val description: String = "",
    @SerialName("cover_sticker_id") val coverStickerId: Snowflake? = null,
    val public: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val stickers: List<Sticker> = emptyList(),
    val installed: Boolean = false,
    /** How many the whole pack holds, where [stickers] is only a preview (Discovery). */
    @SerialName("sticker_count") val stickerCount: Int? = null,
    /** How many people installed it, where someone is deciding whether to. */
    @SerialName("install_count") val installCount: Int? = null,
    /** Who made it, where a pack is looked at on its own. */
    val owner: PublicUser? = null,
)

@Serializable
data class DiscoverPacksPage(val packs: List<StickerPack> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false, @SerialName("next_offset") val nextOffset: Int? = null)

@Serializable
data class StickerInfo(val sticker: Sticker, val pack: StickerPack, val users: List<PublicUser> = emptyList())

@Serializable
data class GifResult(val id: String, val url: String, val preview: String = "", val w: Int = 0, val h: Int = 0, val title: String = "")

@Serializable
data class GifPage(val results: List<GifResult> = emptyList(), val next: String = "")

@Serializable
data class CallUser(
    val id: Snowflake,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: Snowflake? = null,
    val flags: Long = 0,
)

@Serializable
data class GuestEpoch(val epoch: Int = 0)

@Serializable
data class VoiceState(
    @SerialName("sfu_generation") val sfuGeneration: Long = 0,
    @SerialName("user_id") val userId: Snowflake,
    @SerialName("channel_id") val channelId: Snowflake? = null,
    @SerialName("old_channel_id") val oldChannelId: Snowflake? = null,
    @SerialName("session_id") val sessionId: Snowflake = "",
    @SerialName("self_mute") val selfMute: Boolean = false,
    @SerialName("self_deaf") val selfDeaf: Boolean = false,
    val video: Boolean = false,
    val streaming: Boolean = false,
    /** A moderator's mute, or a channel that lets nobody speak (then `suppress` is set too). */
    @SerialName("server_mute") val serverMute: Boolean = false,
    @SerialName("server_deaf") val serverDeaf: Boolean = false,
    /** The channel denies Speak: serverMute is set for that, not for anything a moderator did. */
    val suppress: Boolean = false,
    /** They lack Use Stereo Voice here, so this app decodes them in one channel whatever they send. */
    @SerialName("mono_only") val monoOnly: Boolean = false,
    /** Talking as Priority Speaker now: everyone else is turned down while they are heard. */
    @SerialName("priority_speaker") val prioritySpeaker: Boolean = false,
    @SerialName("joined_at") val joinedAt: Long = 0,
    /** When the call in this channel began, the same for everyone in it (0 from older servers). */
    @SerialName("call_since") val callSince: Long = 0,
    val media: Map<String, String>? = null,
    /** The video codecs besides VP8 the participant's device can watch: "h264" for a phone's screen share. */
    val codecs: List<String>? = null,
    val user: CallUser? = null,
    val guest: GuestEpoch? = null,
    /** On a leave: "moved" when another device of the account picked the call up, "disconnected" when a moderator put them out. */
    val reason: String? = null,
)

@Serializable
data class GuestInfo(
    val id: Snowflake,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("channel_id") val channelId: Snowflake = "",
    val epoch: Int = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    val sfu: Boolean = false,
)

@Serializable
data class IceServer(val urls: JsonElement, val username: String? = null, val credential: String? = null)

@Serializable
data class InstanceInfo(
    val name: String = "Nexa",
    val version: String = "",
    val registration: String = "open",
    @SerialName("needs_key") val needsKey: Boolean = false,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 104857600,
    @SerialName("gif_search") val gifSearch: Boolean = false,
    @SerialName("public_url") val publicUrl: String = "",
    @SerialName("turn_configured") val turnConfigured: Boolean = false,
    val captcha: CaptchaInfo? = null,
    /** Turnstile's site key, which this app uses; `captcha` names hCaptcha for older apps while that fallback is set up. */
    val turnstile: TurnstileInfo? = null,
    @SerialName("upload_single_bytes") val uploadSingleBytes: Long = 33554432,
    @SerialName("upload_chunk_bytes") val uploadChunkBytes: Long = 33554432,
    @SerialName("max_sticker_bytes") val maxStickerBytes: Long = 5242880,
    /** Every plan's limits (api/billing.go); absent from servers before plans. */
    val plans: PlansInfo? = null,
)

/** What one plan allows (api/billing.go planLimits). */
@Serializable
data class PlanLimits(
    @SerialName("file_max") val fileMax: Long = 1L shl 30,
    @SerialName("month_upload_max") val monthUploadMax: Long = 5L shl 30,
    @SerialName("share_quality") val shareQuality: Boolean = false,
    @SerialName("share_max_bps") val shareMaxBps: Long = 6_000_000,
    @SerialName("name_effects") val nameEffects: Boolean = false,
    val boosts: Int = 0,
)

@Serializable
data class PlansInfo(
    val enabled: Boolean = false,
    val free: PlanLimits = PlanLimits(),
    val supporter: PlanLimits = PlanLimits(2L shl 30, 100L shl 30, true, 12_000_000, true, 0),
    val pro: PlanLimits = PlanLimits(4L shl 30, 500L shl 30, true, 20_000_000, true, 2),
    @SerialName("boost_file_max") val boostFileMax: Long = 2L shl 30,
)

/** One of an account's boosts, with the server it is on. */
@Serializable
data class Boost(
    val id: Snowflake,
    @SerialName("server_id") val serverId: Snowflake,
    @SerialName("server_name") val serverName: String = "",
    @SerialName("server_icon_id") val serverIconId: Snowflake? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class PlanPrice(val amount: Long = 0, val currency: String = "usd")

/** An account's side of its subscription (GET /billing). */
@Serializable
data class Billing(
    val enabled: Boolean = false,
    val plan: String = "",
    val limits: PlanLimits = PlanLimits(),
    @SerialName("month_used") val monthUsed: Long = 0,
    @SerialName("month_frees_at") val monthFreesAt: Long = 0,
    val boosts: List<Boost> = emptyList(),
    val period: String = "",
    val status: String = "",
    @SerialName("period_end") val periodEnd: Long = 0,
    @SerialName("cancel_at_end") val cancelAtEnd: Boolean = false,
    val manageable: Boolean = false,
    val prices: Map<String, Map<String, PlanPrice>> = emptyMap(),
    /** What a month or a year of a plan costs as a gift, paid once. */
    @SerialName("gift_prices") val giftPrices: Map<String, Map<String, PlanPrice>> = emptyMap(),
    /** Plans given to this account that haven't run out, soonest first. */
    val gifts: List<PlanGift>? = null,
    /** Gift links this account bought, newest first. */
    @SerialName("gift_links") val giftLinks: List<GiftLink>? = null,
)

/** A plan bought as a link, which whoever opens it first claims (store.GiftLink). */
@Serializable
data class GiftLink(
    val code: String,
    val plan: String = "",
    val days: Int = 30,
    @SerialName("created_at") val createdAt: Long = 0,
    val claimed: Boolean = false,
    @SerialName("claimed_by") val claimedBy: Snowflake = "",
    @SerialName("claimed_name") val claimedName: String = "",
)

/** A gift link as someone who opened it sees it (api/billing.go giftLinkView). */
@Serializable
data class GiftLinkView(
    val code: String,
    @SerialName("from_id") val fromId: Snowflake = "",
    @SerialName("from_name") val fromName: String = "",
    val plan: String = "",
    val days: Int = 30,
    val claimed: Boolean = false,
    @SerialName("claimed_by_me") val claimedByMe: Boolean = false,
    val mine: Boolean = false,
)

/** A plan someone gave this account, for a month or a year (store.Gift). */
@Serializable
data class PlanGift(
    @SerialName("from_id") val fromId: Snowflake,
    @SerialName("from_name") val fromName: String = "",
    val plan: String = "",
    @SerialName("starts_at") val startsAt: Long = 0,
    @SerialName("ends_at") val endsAt: Long = 0,
)

@Serializable
data class CaptchaInfo(val provider: String = "", @SerialName("site_key") val siteKey: String = "")

@Serializable
data class Attachment(val id: Snowflake = "", @SerialName("blob_id") val blobId: Snowflake = "", val size: Long = 0)

@Serializable
data class ReactionSummary(val emoji: String, val count: Int = 0, val me: Boolean = false)

@Serializable
data class PollAnswerCount(val id: Int, val count: Int = 0, @SerialName("me_voted") val meVoted: Boolean = false)

@Serializable
data class PollTally(
    @SerialName("answer_counts") val answerCounts: List<PollAnswerCount> = emptyList(),
    @SerialName("total_voters") val totalVoters: Int = 0,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("is_finalized") val isFinalized: Boolean = false,
)

@Serializable
data class PollOutcome(
    @SerialName("answer_counts") val answerCounts: List<PollAnswerCount> = emptyList(),
    @SerialName("total_votes") val totalVotes: Int = 0,
    @SerialName("total_voters") val totalVoters: Int = 0,
    @SerialName("victor_answer_id") val victorAnswerId: Int = 0,
    @SerialName("victor_answer_votes") val victorAnswerVotes: Int = 0,
)

@Serializable
data class Message(
    val id: Snowflake,
    @SerialName("channel_id") val channelId: Snowflake,
    @SerialName("author_id") val authorId: Snowflake = "",
    val type: String = "default",
    val ciphertext: JsonElement? = null,
    @SerialName("key_conv_id") val keyConvId: Snowflake? = null,
    @SerialName("key_epoch") val keyEpoch: Int? = null,
    @SerialName("reply_to_id") val replyToId: Snowflake? = null,
    val mentions: List<Snowflake> = emptyList(),
    @SerialName("mention_roles") val mentionRoles: List<Snowflake> = emptyList(),
    @SerialName("mention_everyone") val mentionEveryone: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<ReactionSummary> = emptyList(),
    val flags: Long = 0,
    val nonce: String? = null,
    @SerialName("edited_at") val editedAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("webhook_id") val webhookId: Snowflake? = null,
    val webhook: JsonElement? = null,
    @SerialName("poll_results") val pollResults: PollTally? = null,
    @SerialName("poll_result") val pollResult: PollOutcome? = null,
    /** The thread started from this message, if any. */
    val thread: ThreadSummary? = null,
    /** On a copy of a post published from an announcement channel: where it came from. */
    val crosspost: CrosspostInfo? = null,
    /** A post of an announcement channel that has been published. */
    val published: Boolean = false,
    /** On AutoMod's note in an alert channel: what it caught and did. */
    val automod: AutomodNote? = null,
    /**
     * What a reply (or a pin's or a poll's notice) points at, sent along by the
     * server: the original as it is now, or null once it has been deleted.
     * [NOT_SAID] where the server did not say (ReplyTargets fetches it then).
     */
    @SerialName("referenced_message") val referenced: JsonElement? = NOT_SAID,
) {
    val pinned get() = flags and MSG_FLAG_PINNED != 0L
    val suppressEmbeds get() = flags and MSG_FLAG_SUPPRESS_EMBEDS != 0L
    val silent get() = flags and MSG_FLAG_SUPPRESS_NOTIFICATIONS != 0L
    /** An app's answer only this person sees. */
    val ephemeral get() = flags and MSG_FLAG_EPHEMERAL != 0L
    /** An app's answer still thinking. */
    val loading get() = flags and MSG_FLAG_LOADING != 0L

    companion object {
        const val MSG_FLAG_PINNED = 1L
        const val MSG_FLAG_EPHEMERAL = 1L shl 6
        const val MSG_FLAG_LOADING = 1L shl 7
        const val MSG_FLAG_SUPPRESS_EMBEDS = 4L
        const val MSG_FLAG_SUPPRESS_NOTIFICATIONS = 1L shl 12
        const val MSG_FLAG_COMPONENTS_V2 = 1L shl 15
        /** An announcement channel's post that has gone out to the channels following it. */
        const val MSG_FLAG_PUBLISHED = 1L shl 20
        /** [referenced] when the server sent nothing about the original. */
        val NOT_SAID: JsonElement = kotlinx.serialization.json.JsonPrimitive("not-said")
    }
}

@Serializable
data class PollRequest(
    @SerialName("answer_count") val answerCount: Int,
    val duration: Int,
    @SerialName("allow_multiselect") val allowMultiselect: Boolean,
)

@Serializable
data class Webhook(
    val id: Snowflake,
    val type: Int = 1,
    @SerialName("server_id") val serverId: Snowflake = "",
    @SerialName("channel_id") val channelId: Snowflake = "",
    val name: String = "",
    val avatar: Snowflake? = null,
    val token: String? = null,
    val url: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class Relationship(
    val user: PublicUser,
    val type: String,
    val nickname: String = "",
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class ReadState(
    @SerialName("channel_id") val channelId: Snowflake,
    @SerialName("last_read_message_id") val lastReadMessageId: Snowflake? = null,
    @SerialName("last_ack_at") val lastAckAt: Long = 0,
    @SerialName("mention_count") val mentionCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    val manual: Boolean = false,
)

@Serializable
data class KeyGrant(
    @SerialName("conv_id") val convId: Snowflake,
    val epoch: Int,
    @SerialName("user_id") val userId: Snowflake = "",
    @SerialName("wrapped_key") val wrappedKey: String,
    @SerialName("wrapped_by") val wrappedBy: Snowflake = "",
    @SerialName("created_at") val createdAt: Long = 0,
    /** The epoch's fingerprint of its key; missing on older epochs. */
    val commitment: String? = null,
)

@Serializable
data class KeyNeed(
    @SerialName("conv_id") val convId: Snowflake,
    val epoch: Int,
    @SerialName("user_ids") val userIds: List<Snowflake> = emptyList(),
    val rotate: Boolean = false,
)

@Serializable
data class KeyEpoch(
    @SerialName("conv_id") val convId: Snowflake,
    val epoch: Int,
    @SerialName("created_by") val createdBy: Snowflake = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val reason: String = "",
)

@Serializable
data class KeyFlags(
    @SerialName("conv_id") val convId: Snowflake,
    @SerialName("needs_rotation") val needsRotation: Boolean = false,
    @SerialName("needs_own_key") val needsOwnKey: Boolean = false,
)

@Serializable
data class ActivityTimestamps(val start: Long? = null, val end: Long? = null)

@Serializable
data class ActivityAssets(
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
    @SerialName("large_url") val largeUrl: String? = null,
    @SerialName("small_image") val smallImage: String? = null,
    @SerialName("small_text") val smallText: String? = null,
    @SerialName("small_url") val smallUrl: String? = null,
)

@Serializable
data class ActivityButton(val label: String, val url: String)

@Serializable
data class ActivityParty(val id: String? = null, val size: List<Long>? = null)

@Serializable
data class Activity(
    @SerialName("application_id") val applicationId: String? = null,
    val name: String = "",
    val type: Int = 0,
    @SerialName("status_display_type") val statusDisplayType: Int? = null,
    val details: String? = null,
    @SerialName("details_url") val detailsUrl: String? = null,
    val state: String? = null,
    @SerialName("state_url") val stateUrl: String? = null,
    val timestamps: ActivityTimestamps? = null,
    val assets: ActivityAssets? = null,
    val party: ActivityParty? = null,
    val buttons: List<ActivityButton>? = null,
    val instance: Boolean = false,
    @SerialName("icon_url") val iconUrl: String? = null,
    /** A game the desktop app recognised running, and IGDB's id for it when the server found one. */
    val detected: Boolean = false,
    @SerialName("game_id") val gameId: String? = null,
    val source: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class Presence(
    @SerialName("user_id") val userId: Snowflake,
    val status: String = "offline",
    @SerialName("status_text") val statusText: String = "",
    @SerialName("status_emoji") val statusEmoji: String = "",
    @SerialName("status_expires_at") val statusExpiresAt: Long? = null,
    val activities: List<Activity>? = null,
)

@Serializable
data class SettingsBlob(val blob: String = "", val version: Long = 0, @SerialName("updated_at") val updatedAt: Long? = null)

@Serializable
data class GroupAddNotice(
    @SerialName("channel_id") val channelId: Snowflake,
    @SerialName("added_by") val addedBy: Snowflake,
    @SerialName("added_at") val addedAt: Long = 0,
)

@Serializable
data class ReadyPayload(
    @SerialName("session_id") val sessionId: Snowflake = "",
    val user: MeUser,
    val users: List<PublicUser> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val relationships: List<Relationship> = emptyList(),
    @SerialName("read_states") val readStates: List<ReadState> = emptyList(),
    val presences: List<Presence> = emptyList(),
    @SerialName("key_grants") val keyGrants: List<KeyGrant> = emptyList(),
    val settings: SettingsBlob? = null,
    val servers: List<ServerView> = emptyList(),
    val emojis: List<Emoji> = emptyList(),
    @SerialName("sticker_packs") val stickerPacks: List<StickerPack> = emptyList(),
    @SerialName("voice_states") val voiceStates: List<VoiceState> = emptyList(),
    @SerialName("group_add_notices") val groupAddNotices: List<GroupAddNotice> = emptyList(),
    val guest: GuestInfo? = null,
    val nexa: JsonElement? = null,
    /** The server's clock as the ready went out (Unix ms), 0 from older servers. */
    val now: Long = 0,
)

@Serializable
data class KdfParamsJson(val m: Int, val t: Int, val p: Int, val v: Int = 1)

@Serializable
data class KdfInfo(val salt: String, val params: KdfParamsJson)

@Serializable
data class RegisterRequest(
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("auth_key") val authKey: String,
    @SerialName("kdf_salt") val kdfSalt: String,
    @SerialName("kdf_params") val kdfParams: KdfParamsJson,
    @SerialName("public_key") val publicKey: String,
    @SerialName("enc_private_key") val encPrivateKey: String,
    @SerialName("enc_private_key_recovery") val encPrivateKeyRecovery: String? = null,
    @SerialName("recovery_auth_key") val recoveryAuthKey: String? = null,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("registration_key") val registrationKey: String? = null,
)

/** A login answer: either a session (user, token and the key bundle) or a request for a second factor. */
@Serializable
data class LoginResult(
    val user: MeUser? = null,
    val token: String? = null,
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("enc_private_key") val encPrivateKey: String? = null,
    @SerialName("kdf_salt") val kdfSalt: String? = null,
    @SerialName("kdf_params") val kdfParams: KdfParamsJson? = null,
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    val ticket: String? = null,
    @SerialName("backup_codes_left") val backupCodesLeft: Int = 0,
)

@Serializable
data class KeyBundle(
    @SerialName("public_key") val publicKey: String,
    @SerialName("enc_private_key") val encPrivateKey: String,
    @SerialName("kdf_salt") val kdfSalt: String,
    @SerialName("kdf_params") val kdfParams: KdfParamsJson,
)

@Serializable
data class RecoverBegin(
    @SerialName("public_key") val publicKey: String,
    @SerialName("enc_private_key_recovery") val encPrivateKeyRecovery: String,
    val ticket: String,
)

@Serializable
data class MfaStatus(val enabled: Boolean = false, @SerialName("backup_codes_left") val backupCodesLeft: Int = 0, @SerialName("enabled_at") val enabledAt: Long? = null)

@Serializable
data class TotpSetup(val secret: String, val formatted: String = "", val uri: String = "", val ticket: String)

@Serializable
data class UnfurlResult(
    val url: String,
    val type: String = "link",
    val raw: JsonObject? = null,
)

@Serializable
data class TurnstileInfo(@SerialName("site_key") val siteKey: String = "")
