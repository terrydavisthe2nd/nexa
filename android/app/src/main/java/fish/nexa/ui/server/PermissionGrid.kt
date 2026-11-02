package fish.nexa.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.data.P
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ToggleLine
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii

/** One permission as the grids show it (lib/perms.ts permGroups). */
data class PermItem(val bit: Long, val label: String, val description: String, val channel: Set<String>)
data class PermGroup(val name: String, val items: List<PermItem>)

object PermInfo {
    private val TEXT = setOf("text")
    private val VOICE = setOf("voice")
    private val BOTH = setOf("text", "voice")
    private val NONE = emptySet<String>()
    /** What a link channel's overwrites can set: only who sees it and who manages it. */
    private val WITH_LINK = setOf("text", "voice", "link")

    /** The groups and permissions in the order the web lists them. */
    fun groups(): List<PermGroup> = listOf(
        PermGroup(
            t("perms.generalServerPermissions"),
            listOf(
                PermItem(P.VIEW_CHANNEL, t("perms.viewChannels"), t("perms.allowsMembersToViewChannelsBy"), WITH_LINK),
                PermItem(P.MANAGE_CHANNELS, t("perms.manageChannels"), t("perms.allowsMembersToCreateEditOr"), WITH_LINK),
                PermItem(P.MANAGE_ROLES, t("perms.manageRoles"), t("perms.allowsMembersToCreateNewRoles"), WITH_LINK),
                PermItem(P.MANAGE_EMOJIS, t("perms.manageExpressions"), t("perms.allowsMembersToAddOrRemove"), NONE),
                PermItem(P.MANAGE_WEBHOOKS, t("perms.manageWebhooks"), t("perms.manageWebhooksDescription"), TEXT),
                PermItem(P.VIEW_AUDIT_LOG, t("perms.viewAuditLog"), t("perms.allowsMembersToViewARecord"), NONE),
                PermItem(P.MANAGE_SERVER, t("perms.manageServer"), t("perms.allowsMembersToChangeThisServer"), NONE),
            ),
        ),
        PermGroup(
            t("perms.membershipPermissions"),
            listOf(
                PermItem(P.CREATE_INSTANT_INVITE, t("server.invites.createInvite"), t("perms.allowsMembersToInviteNewPeople"), BOTH),
                PermItem(P.CHANGE_NICKNAME, t("perms.changeNickname"), t("perms.allowsMembersToChangeTheirOwn"), NONE),
                PermItem(P.MANAGE_NICKNAMES, t("perms.manageNicknames"), t("perms.allowsMembersToChangeTheNicknames"), NONE),
                PermItem(P.KICK_MEMBERS, t("perms.kickMembers"), t("perms.allowsMembersToRemoveOtherMembers"), NONE),
                PermItem(P.BAN_MEMBERS, t("perms.banMembers"), t("perms.allowsMembersToPermanentlyBanOther"), NONE),
                PermItem(P.MODERATE_MEMBERS, t("perms.timeOutMembers"), t("perms.membersWithThisPermissionCanTime"), NONE),
            ),
        ),
        PermGroup(
            t("perms.textChannelPermissions"),
            listOf(
                PermItem(P.SEND_MESSAGES, t("perms.sendMessages"), t("perms.allowsMembersToSendMessagesIn"), TEXT),
                PermItem(P.EMBED_LINKS, t("perms.embedLinks"), t("perms.allowsLinksThatMembersShareTo"), TEXT),
                PermItem(P.ATTACH_FILES, t("perms.attachFiles"), t("perms.allowsMembersToUploadFilesOr"), TEXT),
                PermItem(P.ADD_REACTIONS, t("perms.addReactions"), t("perms.allowsMembersToAddNewEmoji"), TEXT),
                PermItem(P.MENTION_EVERYONE, t("perms.mentionEveryoneAndAllRoles"), t("perms.allowsMembersToUseEveryoneAnd"), TEXT),
                PermItem(P.MANAGE_MESSAGES, t("perms.manageMessages"), t("perms.allowsMembersToDeleteMessagesBy"), TEXT),
                PermItem(P.READ_MESSAGE_HISTORY, t("perms.readMessageHistory"), t("perms.allowsMembersToReadPreviousMessages"), TEXT),
                PermItem(P.USE_CUSTOM_TYPING, t("perms.useCustomTyping"), t("perms.useCustomTypingDescription"), BOTH),
                PermItem(P.CREATE_POLLS, t("perms.createPolls"), t("perms.allowsMembersToCreatePolls"), TEXT),
            ),
        ),
        PermGroup(
            t("perms.voiceChannelPermissions"),
            listOf(
                PermItem(P.CONNECT, t("perms.connect"), t("perms.allowsMembersToJoinVoiceChannels"), VOICE),
                PermItem(P.SPEAK, t("perms.speak"), t("perms.allowsMembersToTalkInVoice"), VOICE),
                PermItem(P.STREAM, t("perms.video"), t("perms.allowsMembersToShareTheirVideo"), VOICE),
                PermItem(P.USE_VAD, t("perms.useVoiceActivity"), t("perms.allowsMembersToSpeakInVoice"), VOICE),
                PermItem(P.PRIORITY_SPEAKER, t("perms.prioritySpeaker"), t("perms.allowsMembersToBeMoreEasily"), VOICE),
                PermItem(P.MUTE_MEMBERS, t("perms.muteMembers"), t("perms.allowsMembersToMuteOtherMembers"), VOICE),
                PermItem(P.DEAFEN_MEMBERS, t("perms.deafenMembers"), t("perms.allowsMembersToDeafenOtherMembers"), VOICE),
                PermItem(P.MOVE_MEMBERS, t("perms.moveMembers"), t("perms.allowsMembersToMoveOtherMembers"), VOICE),
            ),
        ),
        PermGroup(
            t("perms.appPermissions"),
            listOf(
                PermItem(P.USE_APPLICATION_COMMANDS, t("perms.useApplicationCommands"), t("perms.useApplicationCommandsDescription"), BOTH),
                PermItem(P.USE_EXTERNAL_APPS, t("perms.useExternalApps"), t("perms.useExternalAppsDescription"), BOTH),
            ),
        ),
        PermGroup(t("perms.advancedPermissions"), listOf(PermItem(P.ADMINISTRATOR, t("perms.administrator"), t("perms.membersWithThisPermissionHaveEvery"), NONE))),
    )

    /** PermissionGrid.tsx permsForChannel: the bits an overwrite can set on this kind of channel (a category: all of them). */
    fun forChannel(type: String?): Set<Long> {
        // announcement channels, forums and media channels take a text channel's
        val kind = when (type) {
            "announcement", "forum", "media" -> "text"
            else -> type
        }
        val out = LinkedHashSet<Long>()
        for (g in groups()) for (it in g.items) {
            if (it.channel.isEmpty()) continue
            if (kind == null || kind == "category" || kind in it.channel) out += it.bit
        }
        return out
    }

    /** The labels of every known bit in [bits], comma separated, or null when none (the audit log's wording). */
    fun labels(bits: Long): String? {
        val names = groups().flatMap { it.items }.filter { bits and it.bit != 0L }.map { it.label }
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/**
 * RoleGrid: a switch for each of a role's server-wide permissions, with its
 * description. [grantable], when given, is what the editor holds: nobody may
 * hand out what they do not have, so the rest are shown but fixed.
 */
@Composable
fun RoleGrid(value: Long, onChange: (Long) -> Unit, editable: Boolean, filter: String, grantable: Long?) {
    val q = filter.trim().lowercase()
    Column {
        for (g in PermInfo.groups()) {
            val items = g.items.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.description.lowercase().contains(q) }
            if (items.isEmpty()) continue
            GroupHead(g.name)
            for (it in items) {
                ToggleLine(
                    it.label,
                    P.has(value, it.bit),
                    { on -> onChange(if (on) value or it.bit else value and it.bit.inv()) },
                    description = it.description,
                    enabled = editable && (grantable == null || P.has(grantable, it.bit)),
                )
            }
        }
    }
}

@Composable
private fun GroupHead(text: String) {
    Txt(
        text,
        style = Nx.type.body.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(700)),
        color = Nx.colors.textStrong,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp).semantics { heading() },
    )
}

enum class Tri { DENY, NEUTRAL, ALLOW }

fun triOf(allow: Long, deny: Long, bit: Long): Tri = when {
    P.has(allow, bit) -> Tri.ALLOW
    P.has(deny, bit) -> Tri.DENY
    else -> Tri.NEUTRAL
}

/**
 * OverwriteGrid: for each permission this kind of channel has, Deny, Inherit
 * or Allow, as a three-part control (red, neutral, green when chosen).
 */
@Composable
fun OverwriteGrid(allow: Long, deny: Long, onChange: (Long, Long) -> Unit, editable: Boolean, channelType: String?, grantable: Long?) {
    val c = Nx.colors
    val names = PermInfo.forChannel(channelType)
    fun set(bit: Long, v: Tri) {
        var a = allow and bit.inv()
        var d = deny and bit.inv()
        if (v == Tri.ALLOW) a = a or bit
        if (v == Tri.DENY) d = d or bit
        onChange(a, d)
    }
    Column {
        for (g in PermInfo.groups()) {
            val items = g.items.filter { it.bit in names }
            if (items.isEmpty()) continue
            GroupHead(g.name)
            for (it in items) {
                val cur = triOf(allow, deny, it.bit)
                val rowEditable = editable && (grantable == null || P.has(grantable, it.bit))
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Txt(it.label, style = Nx.type.body, color = c.text)
                            Txt(it.description, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.55f)), color = c.textMuted)
                        }
                        TriControl(it.label, cur, rowEditable) { v -> set(it.bit, v) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                }
            }
        }
    }
}

/** `.tri`: three joined buttons, 44 × 40 each for a thumb. */
@Composable
private fun TriControl(label: String, cur: Tri, enabled: Boolean, onPick: (Tri) -> Unit) {
    val c = Nx.colors
    val shape = Radii.Xs
    Row(
        Modifier
            .border(1.dp, c.border, shape)
            .clip(shape)
            .alpha(if (enabled) 1f else 0.6f)
            .semantics { contentDescription = label },
    ) {
        for ((i, kind) in listOf(Tri.DENY, Tri.NEUTRAL, Tri.ALLOW).withIndex()) {
            if (i > 0) Box(Modifier.size(1.dp, 40.dp).background(c.border))
            val on = cur == kind
            val bg = when {
                !on -> c.bgSurface
                kind == Tri.DENY -> c.danger
                kind == Tri.ALLOW -> c.positive
                else -> c.bgActive
            }
            val fg = when {
                !on -> c.textMuted
                kind == Tri.DENY -> c.onDanger
                kind == Tri.ALLOW -> c.onPositive
                else -> c.textStrong
            }
            Box(
                Modifier
                    .size(44.dp, 40.dp)
                    .background(bg)
                    .pressable(remember { MutableInteractionSource() }, enabled = enabled, role = Role.RadioButton) { onPick(kind) }
                    .semantics {
                        selected = on
                        contentDescription = when (kind) {
                            Tri.DENY -> t("server.permissionGrid.deny")
                            Tri.NEUTRAL -> t("server.permissionGrid.inherit")
                            Tri.ALLOW -> t("server.permissionGrid.allow")
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (kind) {
                        Tri.DENY -> "X"
                        Tri.NEUTRAL -> "Minus"
                        Tri.ALLOW -> "Check"
                    },
                    fg,
                    size = 16.dp,
                )
            }
        }
    }
}
