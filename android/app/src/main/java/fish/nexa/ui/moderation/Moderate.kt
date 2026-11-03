package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.Nexa
import fish.nexa.data.ModMembership
import fish.nexa.data.ModUser
import fish.nexa.data.Moderation
import fish.nexa.data.PurgeCounts
import fish.nexa.data.StaffCase
import fish.nexa.data.TypingStyle
import fish.nexa.media.Media
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Avatar
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.IconButton
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.NxTextField
import fish.nexa.ui.components.SelectField
import fish.nexa.ui.components.Spinner
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.components.sink
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.Layer
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.Sheet
import fish.nexa.ui.nexa.actionIcon
import fish.nexa.ui.nexa.fieldLabel
import fish.nexa.ui.nexa.formatSpan
import fish.nexa.ui.nexa.severity
import fish.nexa.ui.profile.Widgets
import fish.nexa.ui.profile.statusEmojiText
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// The panel's dialogs, bottom sheets on a phone as the web's are: what the
// action is about at the top, its choices and the reason in the middle, and
// what it will do with Cancel and the button that does it at the foot.
// ---------------------------------------------------------------------------

/**
 * Opens one of the panel's dialogs: [top] stays put, [body] scrolls under it,
 * and [foot] is the shelf at the bottom. Its content composes once, so state
 * the three share lives in what the caller passes them.
 */
fun openModSheet(key: String, top: @Composable (close: () -> Unit) -> Unit, foot: @Composable (close: () -> Unit) -> Unit, body: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    lateinit var layer: Layer
    layer = Layer(key) { close ->
        Sheet(layer, title = null, closeButton = false, scroll = false, padding = 0.dp, footer = { foot(close) }) {
            top(close)
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) { body(close) }
        }
    }
    Layers.open(layer)
}

/** `.modalTop`: what the dialog acts on, its title, and the close button (and a way back, in the Moderate dialog). */
@Composable
fun ModSheetTop(title: String, close: () -> Unit, onBack: (() -> Unit)? = null, leading: @Composable () -> Unit, titleWords: fish.nexa.ui.chat.PersonWords? = null, target: @Composable () -> Unit) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(c.borderSubtle, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) IconButton("ArrowLeft", t("moderation.back"), onBack, Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }, tint = c.textMuted, size = 40.dp, iconSize = 20.dp)
        leading()
        Column(Modifier.weight(1f)) {
            if (titleWords != null) fish.nexa.ui.chat.PersonWordsText(titleWords, Nx.type.name.copy(fontSize = Nx.type.sp(19f), fontWeight = FontWeight(690)), c.textStrong, Modifier.semantics { heading() }, maxLines = 2)
            else Txt(title, style = Nx.type.name.copy(fontSize = Nx.type.sp(19f), fontWeight = FontWeight(690)), color = c.textStrong, maxLines = 2, modifier = Modifier.semantics { heading() })
            target()
        }
        IconButton("X", t("chat.attachments.close"), close, tint = c.textMuted, size = 44.dp, iconSize = 20.dp)
    }
}

/** `.modalFoot`: what the action does, on a line of its own on a phone, then Cancel and the button. */
@Composable
fun ModSheetFoot(summary: String, close: () -> Unit, personId: String? = null, personName: String = "", submit: @Composable () -> Unit) {
    val c = Nx.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fish.nexa.ui.chat.PersonText(personId, summary, personName, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            NxButton(t("moderation.cancel"), close, kind = ButtonKind.GHOST)
            submit()
        }
    }
}

/** The target line under a dialog's title: @name, and its mute or ban. */
@Composable
private fun TargetLine(u: ModUser) {
    val c = Nx.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("@" + u.username, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.textMuted, maxLines = 1)
        if (u.mute != null) Tag(t("moderation.state.muted"), "warn")
        if (u.ban != null) Tag(t("moderation.state.banned"), "danger")
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
private val MUTE_PRESETS = listOf(HOUR, 6 * HOUR, 12 * HOUR, DAY, 3 * DAY, WEEK, 2 * WEEK, 30 * DAY)
private val BAN_PRESETS = listOf(DAY, 3 * DAY, WEEK, 30 * DAY, 90 * DAY, 365 * DAY)
private val UNITS = listOf(MINUTE, HOUR, DAY, WEEK)
private const val MAX_MUTE = 365 * DAY
private const val MAX_BAN = 5 * 365 * DAY
private val ACTIONS = listOf("avatar_remove", "field_reset", "mute", "ban", "delete")

/** The actions a rank may take: a mod mutes and bans, an admin does all but delete. */
private fun actionsFor(rank: fish.nexa.data.StaffRank?): List<String> = when {
    rank == null || !rank.atLeast(fish.nexa.data.StaffRank.ADMIN) -> listOf("mute", "ban")
    !rank.atLeast(fish.nexa.data.StaffRank.DEVELOPER) -> ACTIONS - "delete"
    else -> ACTIONS
}
private val RESET_FIELDS = listOf("username", "display_name", "about_me", "pronouns", "status", "nicknames", "server_profiles", "typing_style", "name_style", "widgets", "server_tag")
/** The fields a reset empties rather than writing a stand-in in. */
private val CLEARED = setOf("status", "nicknames", "server_profiles", "typing_style", "name_style", "widgets", "server_tag")

private fun unitLabel(u: Long) = when (u) {
    MINUTE -> t("moderation.unit.minutes")
    HOUR -> t("moderation.unit.hours")
    DAY -> t("moderation.unit.days")
    else -> t("moderation.unit.weeks")
}

private fun actionTitle(a: String) = when (a) {
    "avatar_remove" -> t("moderation.act.avatarTitle")
    "field_reset" -> t("moderation.act.fieldsTitle")
    "mute" -> t("moderation.act.muteTitle")
    "ban" -> t("moderation.act.banTitle")
    else -> t("moderation.act.deleteTitle")
}

private fun actionBlurb(a: String) = when (a) {
    "avatar_remove" -> t("moderation.act.avatarDesc")
    "field_reset" -> t("moderation.act.fieldsDesc")
    "mute" -> t("moderation.act.muteDesc")
    "ban" -> t("moderation.act.banDesc")
    else -> t("moderation.act.deleteDesc")
}

/** What a dialog opened from one field of a profile starts with chosen. */
data class ModeratePreset(val fields: List<String> = emptyList(), val avatar: Boolean? = null, val banner: Boolean? = null)

/** One length of time, picked from the usual ones or typed in; -1 is for good. */
private class Duration(initial: Long) {
    var value by mutableStateOf(initial)
    var custom by mutableStateOf(false)
    var amount by mutableStateOf("2")
    var unit by mutableStateOf(DAY)
}

/** A choice of how long, from the usual lengths or typed in, and when that ends. */
@Composable
private fun DurationPicker(d: Duration, presets: List<Long>, max: Long, permanent: Boolean) {
    val c = Nx.colors
    fun pickCustom(n: String, u: Long) {
        d.amount = n
        d.unit = u
        val v = (n.toDoubleOrNull()?.let { Math.round(it) } ?: 0L) * u
        d.value = if (v > 0) minOf(v, max) else 0L
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(t("moderation.form.duration"))
        ChipRow(t("moderation.form.duration")) {
            for (p in presets) ModChip(formatSpan(p), !d.custom && d.value == p, { d.custom = false; d.value = p })
            ModChip(t("moderation.form.custom"), d.custom, { d.custom = true; pickCustom(d.amount, d.unit) })
            if (permanent) ModChip(t("moderation.form.permanent"), !d.custom && d.value == -1L, { d.custom = false; d.value = -1L }, danger = true)
        }
        if (d.custom) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NxTextField(d.amount, { pickCustom(it.filter { ch -> ch.isDigit() }.take(6), d.unit) }, Modifier.width(96.dp), keyboardType = KeyboardType.Number, label = null, placeholder = t("moderation.form.amount"))
            SelectField(null, UNITS.map { it to unitLabel(it) }, d.unit, Modifier.weight(1f)) { pickCustom(d.amount, it) }
        }
        Txt(
            when {
                d.value == -1L -> t("moderation.form.endsNever")
                d.value > 0 -> t("moderation.form.endsAt", "when" to Time.full(System.currentTimeMillis() + d.value))
                else -> t("moderation.form.pickLength")
            },
            style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = c.textSubtle,
        )
    }
}

/** `.actionTile`: one of the actions, in the colour of how serious it is. */
@Composable
private fun ActionTile(action: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Nx.colors
    val (tone, toneBg) = toneColours(severity(action), c)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.Md
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .sink(pressed && enabled)
            .background(if (pressed) mixSrgb(c.bgSurface, tone, 0.08f) else c.bgSurface, shape)
            .background(sheen(c), shape)
            .border(1.dp, if (pressed) tone.copy(alpha = 0.7f) else c.borderSubtle, shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(interaction, enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).background(toneBg, CircleShape), contentAlignment = Alignment.Center) { Icon(actionIcon(action), tone, size = 20.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(actionTitle(action), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong)
            Txt(desc, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f), lineHeight = Nx.type.sp(17.5f)), color = c.textMuted)
        }
    }
}

/** What the Moderate dialog holds while it is open. */
private class ModerateState(u: ModUser, initial: String?, preset: ModeratePreset?, servers: List<ModMembership>?) {
    var action by mutableStateOf(initial)
    var reason by mutableStateOf("")
    var avatar by mutableStateOf(preset?.avatar ?: (u.avatarId != null || servers?.any { it.avatarId != null } == true))
    var banner by mutableStateOf(preset?.banner ?: (u.avatarId == null && (u.bannerId != null || servers?.any { it.bannerId != null } == true)))
    var fields by mutableStateOf(preset?.fields ?: emptyList())
    val muteFor = Duration(DAY)
    val banFor = Duration(WEEK)
    var confirm by mutableStateOf("")
    var preview by mutableStateOf<PurgeCounts?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Taking an action against an account (ModerateModal.tsx): pick it, set it
 * up, say why. The reason goes to the member, word for word, with the
 * moderator's name, so the dialog says so beside the box it is typed in.
 */
fun openModerate(u: ModUser, servers: List<ModMembership>?, initial: String? = null, preset: ModeratePreset? = null, bulk: List<ModUser>? = null, onDone: (StaffCase) -> Unit) {
    // everyone the action is for when it is more than one account (u is the first); each gets a case of their own
    val many = bulk?.takeIf { it.size > 1 }
    val mine = fish.nexa.data.StaffRanks.mine()
    val isMod = mine == null || !mine.atLeast(fish.nexa.data.StaffRank.ADMIN)
    val offered = actionsFor(mine)
    // a mod mutes and bans for a week at most, and never for good
    val maxMute = if (isMod) fish.nexa.data.StaffRanks.MOD_MAX_MS else MAX_MUTE
    val maxBan = if (isMod) fish.nexa.data.StaffRanks.MOD_MAX_MS else MAX_BAN
    val s = ModerateState(u, initial?.takeIf { it in offered }, preset, servers)
    if (many != null) {
        s.avatar = true
        s.banner = false
    }
    // for many accounts, the number of them is what is typed
    val confirmWord = many?.size?.toString() ?: u.username
    val nicknamed = servers?.filter { it.nickname.isNotEmpty() }
    val profiled = servers?.filter { it.hasProfile }
    // a picture of theirs in one of their servers counts as theirs to take down
    val serverAvatar = servers?.any { it.avatarId != null } == true
    val serverBanner = servers?.any { it.bannerId != null } == true
    val hasAvatar = many != null || u.avatarId != null || serverAvatar
    val hasBanner = many != null || u.bannerId != null || serverBanner
    val widgets = (u.widgets as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { Widgets.read(it) } ?: emptyList()
    val values = mapOf(
        "username" to "@" + u.username,
        "display_name" to u.displayName,
        "about_me" to u.aboutMe,
        "pronouns" to u.pronouns,
        "status" to listOf(statusEmojiText(u.statusEmoji), u.statusText).filter { it.isNotEmpty() }.joinToString(" "),
        // without the list, the server says whether there are any
        "nicknames" to (nicknamed?.joinToString(", ") { "${it.name}: ${it.nickname}" } ?: t("moderation.form.everyServer")),
        "server_profiles" to (profiled?.joinToString(", ") { it.name } ?: t("moderation.form.everyServer")),
        "typing_style" to TypingStyle.text(TypingStyle.read(u.typingStyle)),
        "name_style" to fish.nexa.data.NameStyle.text(fish.nexa.data.NameStyle.read(u.nameStyle)),
        "widgets" to widgetsText(widgets).joinToString(" / ") { (title, text) -> "$title: $text" },
        "server_tag" to (u.serverTag?.text ?: ""),
    )

    fun ready(): Boolean {
        val a = s.action ?: return false
        if (!reasonOk(s.reason)) return false
        return when (a) {
            "avatar_remove" -> s.avatar || s.banner
            "field_reset" -> s.fields.isNotEmpty()
            "mute" -> s.muteFor.value in MINUTE..maxMute
            "ban" -> (s.banFor.value == -1L && !isMod) || s.banFor.value in MINUTE..maxBan
            else -> s.confirm.trim().lowercase() == confirmWord
        }
    }

    fun submitLabel(): String = when (s.action) {
        "avatar_remove" -> if (s.avatar && s.banner) t("moderation.submit.removeBoth") else if (s.banner) t("moderation.submit.removeBanner") else t("moderation.submit.removeAvatar")
        "field_reset" -> t("moderation.submit.resetFields", "count" to maxOf(1, s.fields.size))
        "mute" -> if (s.muteFor.value > 0) t("moderation.submit.muteFor", "span" to formatSpan(s.muteFor.value)) else t("moderation.act.muteTitle")
        "ban" -> if (s.banFor.value == -1L) t("moderation.submit.banForever") else if (s.banFor.value > 0) t("moderation.submit.banFor", "span" to formatSpan(s.banFor.value)) else t("moderation.act.banTitle")
        "delete" -> t("moderation.submit.delete")
        else -> t("moderation.submit.choose")
    }

    fun submit(close: () -> Unit) {
        val a = s.action ?: return
        if (!ready() || s.busy) return
        val body = buildJsonObject {
            put("action", a)
            put("reason", s.reason.trim())
            if (a == "avatar_remove") put("images", buildJsonObject { put("avatar", s.avatar); put("banner", s.banner) })
            if (a == "field_reset") put("fields", JsonArray(s.fields.map { JsonPrimitive(it) }))
            if (a == "mute") put("duration_ms", s.muteFor.value)
            if (a == "ban") { if (s.banFor.value == -1L) put("permanent", true) else put("duration_ms", s.banFor.value) }
        }
        s.busy = true
        s.error = null
        if (many != null) {
            // one at a time, so each member gets their own case and message
            Nexa.scope.launch {
                var done = 0
                val failed = ArrayList<String>()
                for (x in many) {
                    try {
                        val cs = actOnOne(x, if (a == "delete") JsonObject(body + ("confirm" to JsonPrimitive(x.username))) else body)
                        if (cs != null) {
                            done++
                            onDone(cs)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failed += x.displayName + ": " + describeError(e, t("moderation.failed"))
                    }
                }
                s.busy = false
                if (failed.isEmpty()) {
                    Nexa.ui.toast(t("moderation.bulk.done", "count" to done), "success")
                    close()
                } else {
                    s.error = (listOf(t("moderation.bulk.partly", "done" to done, "count" to many.size)) + failed).joinToString("\n")
                }
            }
            return
        }
        val one = if (a == "delete") JsonObject(body + ("confirm" to JsonPrimitive(u.username))) else body
        Nexa.scope.launch {
            try {
                val cs = Moderation.act(u.id, one)
                Nexa.ui.toast(t("moderation.done", "number" to cs.number, "name" to u.displayName), "success")
                onDone(cs)
                close()
            } catch (e: Exception) {
                s.error = describeError(e, t("moderation.failed"))
            } finally {
                s.busy = false
            }
        }
    }

    openModSheet(
        "moderate",
        top = { close ->
            ModSheetTop(
                s.action?.let { actionTitle(it) } ?: if (many != null) t("moderation.bulk.title", "count" to many.size) else t("moderation.moderateNamed", "name" to u.displayName),
                close,
                onBack = if (s.action != null && initial == null) ({ s.action = null; s.error = null }) else null,
                titleWords = if (s.action == null && many == null) fish.nexa.ui.chat.PersonWords(t("moderation.moderateNamed", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(u.id to u.displayName)) else null,
                leading = { if (many != null) AvatarStack(many) else Avatar(u.id, u.displayName, u.avatarId, 40.dp, status = modStatus(u).status) },
            ) {
                if (many != null) Txt(many.joinToString(", ") { "@" + it.username }, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f)), color = Nx.colors.textMuted, maxLines = 1)
                else TargetLine(u)
            }
        },
        foot = { close ->
            ModSheetFoot(if (s.action != null) t("moderation.form.notifies") else t("moderation.form.chooseHint"), close) {
                if (s.action != null) NxButton(submitLabel(), { submit(close) }, kind = if (severity(s.action!!) == "danger") ButtonKind.DANGER else ButtonKind.PRIMARY, enabled = ready(), loading = s.busy)
            }
        },
    ) {
        val c = Nx.colors
        when (s.action) {
            null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(t("moderation.form.chooseAction"))
                for (a in offered) {
                    val nothing = a == "avatar_remove" && !hasAvatar && !hasBanner
                    val desc = when {
                        many != null -> actionBlurb(a)
                        nothing -> t("moderation.act.avatarNone")
                        a == "mute" && u.mute != null -> u.mute.expiresAt?.let { t("moderation.act.alreadyMuted", "when" to Time.relativeTo(it)) } ?: t("moderation.act.alreadyMutedForever")
                        a == "ban" && u.ban != null -> u.ban.expiresAt?.let { t("moderation.act.alreadyBanned", "when" to Time.relativeTo(it)) } ?: t("moderation.act.alreadyBannedForever")
                        else -> actionBlurb(a)
                    }
                    ActionTile(a, desc, !nothing) { s.action = a }
                }
            }
            "avatar_remove" -> if (many != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(t("moderation.form.whatToRemove"))
                PickBox(s.avatar, true, { s.avatar = !s.avatar }, label = t("moderation.field.avatar")) { PickText(t("moderation.field.avatar"), t("moderation.bulk.whereSet")) }
                PickBox(s.banner, true, { s.banner = !s.banner }, label = t("moderation.field.banner")) { PickText(t("moderation.field.banner"), t("moderation.bulk.whereSet")) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(t("moderation.form.whatToRemove"))
                PickBox(s.avatar, hasAvatar, { s.avatar = !s.avatar }, label = t("moderation.field.avatar")) {
                    Avatar(u.id, u.displayName, u.avatarId, 32.dp)
                    PickText(t("moderation.field.avatar"), if (u.avatarId != null) t("moderation.form.avatarAlso") else if (serverAvatar) t("moderation.form.inServersOnly") else t("moderation.form.none"))
                }
                PickBox(s.banner, hasBanner, { s.banner = !s.banner }, label = t("moderation.field.banner")) {
                    BannerChip(u.bannerId, profileBrush(u.accentColor, u.accentColor2, u.id))
                    PickText(t("moderation.field.banner"), if (u.bannerId != null) t("moderation.form.bannerAlso") else if (serverBanner) t("moderation.form.inServersOnly") else t("moderation.form.none"))
                }
            }
            "field_reset" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(t("moderation.form.whatToReset"))
                for (f in RESET_FIELDS) {
                    val empty = when {
                        many != null -> false
                        else -> when (f) {
                        "nicknames" -> nicknamed != null && nicknamed.isEmpty()
                        "server_profiles" -> profiled != null && profiled.isEmpty()
                        "username", "display_name" -> false
                        else -> values[f].isNullOrEmpty()
                        }
                    }
                    PickBox(f in s.fields, !empty, { s.fields = if (f in s.fields) s.fields - f else s.fields + f }, label = fieldLabel(f)) {
                        PickText(fieldLabel(f), if (many != null) t("moderation.bulk.whereSet") else if (empty) t("moderation.form.empty") else values[f])
                    }
                }
                NoteBox(
                    when {
                        "username" in s.fields -> t("moderation.form.resetUsernameNote")
                        s.fields.isNotEmpty() && s.fields.all { it in CLEARED } -> t("moderation.form.clearNote")
                        else -> t("moderation.form.resetNote")
                    },
                )
            }
            "mute" -> {
                DurationPicker(s.muteFor, MUTE_PRESETS.filter { it <= maxMute }, maxMute, permanent = false)
                NoteBox(t("moderation.form.muteNote") + if (isMod) " " + t("staff.modWeek") else "")
            }
            "ban" -> {
                DurationPicker(s.banFor, BAN_PRESETS.filter { it <= maxBan }, maxBan, permanent = !isMod)
                NoteBox(t("moderation.form.banNote") + if (isMod) " " + t("staff.modWeek") else "")
            }
            "delete" -> {
                LaunchedEffect(Unit) { if (s.preview == null && many == null) s.preview = runCatching { Moderation.purgePreview(u.id) }.getOrNull() }
                DangerBox {
                    Txt(if (many != null) t("moderation.bulk.deleteWarning", "count" to many.size) else t("moderation.delete.warning"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(700)), color = c.textDanger)
                    val p = s.preview
                    if (many != null) Unit
                    else if (p == null) Box(Modifier.padding(8.dp)) { Spinner(size = 20.dp) }
                    else Column(Modifier.padding(start = 6.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (line in listOf(
                            t("moderation.delete.messages", "count" to p.messages),
                            t("moderation.delete.files", "count" to p.attachments, "size" to Time.bytes(p.attachmentBytes)),
                            t("moderation.delete.servers", "count" to p.ownedServers),
                            t("moderation.delete.groups", "count" to p.ownedGroups),
                            t("moderation.delete.packsAndReactions", "packs" to p.packs, "reactions" to p.reactions),
                            t("moderation.delete.leaves", "servers" to (p.servers - p.ownedServers), "groups" to (p.groups - p.ownedGroups)),
                        )) Txt("•  $line", style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text)
                    }
                    Txt(t("moderation.delete.kept"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text, modifier = Modifier.padding(top = 8.dp))
                }
                // not in capitals like the other labels: the username in it is typed exactly as shown
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(if (many != null) t("moderation.bulk.confirmLabel", "count" to many.size) else t("moderation.delete.confirmLabel", "username" to u.username), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13.5f), fontWeight = FontWeight(600)), color = c.textSubtle)
                    NxTextField(s.confirm, { s.confirm = it }, placeholder = confirmWord)
                }
            }
        }
        if (s.action != null) ReasonSection(s.reason, { s.reason = it }, t("moderation.form.reasonPlaceholder"), t("moderation.form.reasonVisible"), quickReasons(t("moderation.quick.evasion")))
        s.error?.let { e -> DangerBox { Txt(e, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text) } }
    }
}

/**
 * One account's part of an action for many. Pictures they do not have are
 * left out rather than failing: null when there was nothing of theirs to take.
 */
private suspend fun actOnOne(u: ModUser, body: JsonObject): StaffCase? {
    try {
        return Moderation.act(u.id, body)
    } catch (e: fish.nexa.net.ApiError) {
        val images = body["images"] as? JsonObject
        if (images == null || (e.code != "no_avatar" && e.code != "no_banner")) throw e
        val avatar = e.code == "no_banner" && (images["avatar"] as? JsonPrimitive)?.content == "true"
        val banner = e.code == "no_avatar" && (images["banner"] as? JsonPrimitive)?.content == "true"
        if (!avatar && !banner) return null
        return actOnOne(u, JsonObject(body + ("images" to buildJsonObject { put("avatar", avatar); put("banner", banner) })))
    }
}

/** `.avatarStack`: the first few of the accounts a dialog is for, overlapping. */
@Composable
fun AvatarStack(users: List<ModUser>, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val c = Nx.colors
    val shown = users.take(4)
    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
        for (u in shown) {
            Box(Modifier.border(2.dp, c.bgFloating, CircleShape)) { Avatar(u.id, u.displayName, u.avatarId, size) }
        }
        if (users.size > shown.size) Box(Modifier.size(size).background(c.bgActive, CircleShape).border(2.dp, c.bgFloating, CircleShape), contentAlignment = Alignment.Center) {
            Txt("+" + (users.size - shown.size), style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f), fontWeight = FontWeight(700)), color = c.textStrong)
        }
    }
}

/** `.bannerPreview`: a banner, small, beside its pick. */
@Composable
fun BannerChip(bannerId: String?, brush: androidx.compose.ui.graphics.Brush) {
    @Suppress("UNUSED_VARIABLE") val mediaAnimationPreferences = fish.nexa.Nexa.settings.doc.collectAsState().value
    val density = LocalDensity.current.density
    Box(Modifier.size(72.dp, 32.dp).clip(RoundedCornerShape(6.dp)).background(brush)) {
        if (bannerId != null) AsyncImage(Media.bannerUrl(bannerId, 160, density), null, imageLoader = Media.loader, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/** widgetsText: each widget's title and what it shows, as words. */
fun widgetsText(widgets: List<Widgets.Widget>): List<Pair<String, String>> = widgets.map { w ->
    when (w) {
        is Widgets.GameWidget -> Widgets.title(w.type) to listOfNotNull(w.games.joinToString(", ") { it.name }, w.note).filter { it.isNotEmpty() }.joinToString(" · ")
        is Widgets.MusicWidget -> Widgets.title(w.type) to w.music.joinToString(", ") { m -> if (m.by != null) "${m.name} (${m.by})" else m.name }
        is Widgets.CardWidget -> Widgets.title(w.type) to (listOfNotNull(w.title, w.text) + w.stats.map { "${it.label}: ${it.value}" } + w.links.map { it.url }).filter { it.isNotEmpty() }.joinToString(" · ")
        is Widgets.CountdownWidget -> Widgets.title(w.type) to listOfNotNull(w.emoji, w.title, w.date ?: w.at?.let { java.time.Instant.ofEpochMilli(it).toString() }).joinToString(" ")
        is Widgets.LocalTimeWidget -> Widgets.title(w.type) to w.zone
    }
}

/** The person a staff action names, for the dialogs' "Owned by" lines. */
@Composable
fun ownerName(id: String?): String = nameOf(person(id))
