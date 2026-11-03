package fish.nexa.ui.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.net.ApiError
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.describeError
import fish.nexa.ui.markdown.AppLinks
import fish.nexa.ui.settings.keptAboveSaveBar
import fish.nexa.ui.shell.confirm
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.ringGlow
import fish.nexa.ui.theme.sunken
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A server's vanity link (`GET /servers/{id}/vanity-url`). */
@Serializable
data class VanityLinkInfo(val code: String? = null, val uses: Int = 0, @SerialName("channel_id") val channelId: String? = null)

/** What the server checks too: 3 to 32 of a-z, 0-9 and hyphens, a letter or number at each end. */
private val VALID = Regex("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")

/** Typing towards a valid code, as the web's `vanityDraft`: lowercase, spaces become hyphens, anything else is dropped. */
fun vanityDraft(raw: String): String =
    raw.lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9-]"), "").take(32)

private fun vanityError(e: Exception): String = when ((e as? ApiError)?.code) {
    "invalid_vanity" -> t("server.invites.vanityInvalid")
    "vanity_taken" -> t("server.invites.vanityTaken")
    "vanity_reserved" -> t("server.invites.vanityReserved")
    "no_channel" -> t("server.invites.vanityNoChannel")
    else -> describeError(e, t("server.invites.vanityFailed"))
}

/**
 * Server Settings, Invites: the server's vanity link (web `VanityLink.tsx`).
 * The field reads as the whole link, the instance's address and /invite/
 * quiet before the code being typed.
 */
@Composable
fun VanityLinkSection(serverId: String) {
    val c = Nx.colors
    val type = Nx.type
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<VanityLinkInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(serverId, attempt) {
        try {
            val l = Nexa.api.get("/servers/$serverId/vanity-url", VanityLinkInfo.serializer())
            link = l
            draft = l.code ?: ""
            failed = false
        } catch (_: Exception) {
            failed = true
        }
    }
    val origin = AppLinks.shareOrigin(Nexa.api.baseUrl)
    val host = origin.substringAfter("://")
    fun address(code: String) = "$origin/invite/$code"
    val current = link?.code
    val changed = draft != (current ?: "")

    suspend fun put(code: String?): VanityLinkInfo {
        val body = buildJsonObject { put("code", code?.let { JsonPrimitive(it) } ?: JsonNull) }
        return Nexa.api.request("PATCH", "/servers/$serverId/vanity-url", VanityLinkInfo.serializer(), body)
    }
    fun save() {
        if (!changed || saving || draft.isEmpty()) return
        if (!VALID.matches(draft)) {
            error = t("server.invites.vanityInvalid")
            return
        }
        saving = true
        scope.launch {
            try {
                val next = put(draft)
                link = next
                draft = next.code ?: ""
                error = null
                Nexa.ui.toast(t("server.invites.vanitySaved"), "success")
            } catch (e: Exception) {
                error = vanityError(e)
            } finally {
                saving = false
            }
        }
    }
    fun remove() {
        val code = current ?: return
        confirm(t("server.invites.vanityRemoveTitle"), t("server.invites.vanityRemoveBody", "link" to address(code)), t("server.invites.vanityRemove")) {
            try {
                link = put(null)
                draft = ""
                error = null
                Nexa.ui.toast(t("server.invites.vanityRemoved"), "success")
            } catch (e: Exception) {
                Nexa.ui.toast(vanityError(e), "error")
            }
        }
    }

    H5(t("server.invites.vanityTitle"), top = 8.dp)
    Hint(t("server.invites.vanityHint"), top = 0.dp)
    if (failed) {
        LoadFailed(t("server.invites.vanityLoadFailed")) { attempt++ }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = Radii.Sm
    val edge by animateColorAsState(
        when {
            error != null -> c.danger
            focused -> c.focusRing.copy(alpha = 0.75f)
            else -> c.borderSubtle
        },
        tween(Nx.motion.fast), label = "edge",
    )
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .keptAboveSaveBar(draft, focused)
                .then(if (focused && error == null) Modifier.ringGlow(shape, c) else Modifier)
                .sunken(shape, c)
                .border(1.dp, edge, shape)
                .height(42.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // the address gives way first on a narrow screen, from its start
            BasicText(
                "$host/invite/",
                style = type.body.copy(fontSize = type.sp(15f), color = c.textMuted),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(2f, fill = false).semantics { invisibleToUser() },
            )
            Box(Modifier.weight(1f).widthIn(min = 96.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = vanityDraft(it); error = null },
                    enabled = link != null,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = type.input.copy(color = c.textStrong, fontWeight = FontWeight(600)),
                    cursorBrush = SolidColor(c.focusRing),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp).semantics {
                        contentDescription = t("server.invites.vanityTitle")
                        error?.let { error(it) }
                    },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.defaultMinSize(minHeight = 42.dp)) {
                            if (draft.isEmpty()) Txt(t("server.invites.vanityPlaceholder"), style = type.input, color = c.textPlaceholder, maxLines = 1)
                            inner()
                        }
                    },
                )
            }
        }
        NxButton(t("server.invites.vanitySave"), ::save, enabled = changed && draft.isNotEmpty(), loading = saving)
    }
    error?.let {
        Txt(it, style = type.bodySmall.copy(fontSize = type.sp(13f)), color = c.textDanger, modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (current != null) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(t("server.invites.vanityJoins", "count" to (link?.uses ?: 0)), style = type.bodySmall.copy(fontSize = type.sp(13.5f)), color = c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxButton(t("server.invites.copyLink"), { copyAndSay(address(current), t("server.invites.linkCopied")) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, icon = "Copy")
                NxButton(t("server.invites.vanityRemove"), ::remove, kind = ButtonKind.GHOST, size = ButtonSize.SM, icon = "Trash2")
            }
        }
    }
}
