package fish.nexa.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fish.nexa.data.ReasonTone
import fish.nexa.data.ReportReceipt
import fish.nexa.data.Reports
import fish.nexa.media.Media
import fish.nexa.ui.Icon
import fish.nexa.ui.Time
import fish.nexa.ui.components.Txt
import fish.nexa.ui.profile.NexaAvatar
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.shadowLow
import fish.nexa.ui.theme.topEdge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reports.module.css .glyph: a reason's icon in a tile tinted by how urgent it is. */
@Composable
fun ReasonGlyph(reason: String, size: Dp = 34.dp) {
    val c = Nx.colors
    val (fg, bg) =
        when (Reports.tone(reason)) {
            ReasonTone.URGENT -> c.textDanger to mixSrgb(c.bgSurface, c.danger, 0.20f)
            ReasonTone.SERIOUS -> c.textWarning to mixSrgb(c.bgSurface, c.warning, 0.16f)
            ReasonTone.ROUTINE -> c.textMuted to c.bgSurface
        }
    val shape = RoundedCornerShape(if (size < 30.dp) Radii.xs else if (size > 40.dp) Radii.md else Radii.sm)
    Box(Modifier.size(size).background(bg, shape).topEdge(shape, c.edgeLight), contentAlignment = Alignment.Center) {
        Icon(Reports.icon(reason), fg, size = if (size < 30.dp) 15.dp else if (size > 40.dp) 22.dp else 18.dp)
    }
}

/** A pill in a report status's colour; [label] is the staff's or the reporter's word for it. */
@Composable
fun StatusPill(status: String, label: String, small: Boolean = false) {
    val c = Nx.colors
    val (fg, bg) =
        when (status) {
            "open" -> c.textBrand to mixSrgb(c.bgSurface, c.brand, 0.16f)
            "in_review" -> c.textWarning to mixSrgb(c.bgSurface, c.warning, 0.14f)
            "actioned" -> c.positive to mixSrgb(c.bgSurface, c.positive, 0.16f)
            else -> c.textSubtle to c.bgSurface
        }
    Box(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = if (small) 7.dp else 9.dp, vertical = 2.dp)) {
        Txt(label, style = Nx.type.hint.copy(fontSize = Nx.type.sp(if (small) 12f else 13f), fontWeight = FontWeight(600)), color = fg, maxLines = 1)
    }
}

@Composable
fun StaffStatusPill(status: String, small: Boolean = false) = StatusPill(status, t("reports.status.$status"), small)

/** Where a report stands, in the reporter's words. */
@Composable
fun ReceiptStatus(status: String) = StatusPill(status, t("reports.yours.status.$status"))

/**
 * ReceiptView.tsx: one report as the person who sent it sees it. What and why,
 * what they said, where it stands, and what staff told them once it closed.
 */
@Composable
fun ReceiptCard(r: ReportReceipt, compact: Boolean = false, showOutcome: Boolean = true, modifier: Modifier = Modifier) {
    val c = Nx.colors
    val shape = Radii.Md
    Column(
        modifier.fillMaxWidth().shadowLow(shape, c).background(c.bgRaised, shape).topEdge(shape, c.edgeLight).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReasonGlyph(r.reason)
            Column(Modifier.weight(1f)) {
                Txt(t("reports.reason.${r.reason}"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.textStrong)
                Txt(Reports.subjectLine(r), style = Nx.type.bodySmall, color = c.textMuted, maxLines = 2)
            }
            ReceiptStatus(r.status)
        }
        if (!compact) Txt(r.description, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text, maxLines = 4)
        if (showOutcome && r.outcomeAt > 0) {
            Row(
                Modifier.fillMaxWidth().background(mixSrgb(c.bgInput, c.brand, 0.09f), Radii.Sm).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NexaAvatar(20.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon("MessageSquareQuote", c.textBrand, size = 14.dp)
                        Txt(t("reports.yours.fromStaff"), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontWeight = FontWeight(600)), color = c.textBrand)
                    }
                    Txt(Reports.outcomeText(r), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text)
                }
            }
        }
        Txt(t("reports.yours.sentAt", "when" to Time.relativeTo(r.createdAt)), style = Nx.type.bodySmall, color = c.textMuted)
    }
}

/**
 * ReportTargetCard: what is being reported as a person recognises it, its
 * picture, name, kind and words. Pictures here are the live public ones.
 */
@Composable
fun TargetCard(kind: String, snapshot: JsonObject, picture: (@Composable (String, String, String?) -> Unit)? = null) {
    val c = Nx.colors
    fun str(k: String) = (snapshot[k] as? JsonPrimitive)?.contentOrNull ?: ""
    val name = str("display_name").ifEmpty { str("name") }.ifEmpty { str("username") }.ifEmpty { t("reports.kind.$kind") }
    val handle = if (kind == "user" && str("username").isNotEmpty()) "@" + str("username") else ""
    val pic: Triple<String, String, String?>? =
        when {
            str("avatar_id").isNotEmpty() -> Triple(str("avatar_id"), "avatar", null)
            str("icon_id").isNotEmpty() -> Triple(str("icon_id"), "icon", null)
            str("blob_id").isNotEmpty() -> Triple(str("blob_id"), if (kind == "emoji") "emoji" else "sticker", str("format"))
            str("cover_blob_id").isNotEmpty() -> Triple(str("cover_blob_id"), "sticker", str("cover_format"))
            else -> null
        }
    val text = listOf("about_me", "description", "topic", "status_text", "url").map { str(it) }.filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().background(c.bgInput, Radii.Md).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (pic != null) {
            val shape = if (pic.second == "avatar") CircleShape else Radii.Sm
            Box(Modifier.size(48.dp).clip(shape).background(c.bgSurface)) {
                if (picture != null) picture(pic.first, pic.second, pic.third)
                else
                    coil3.compose.AsyncImage(
                        model =
                            when (pic.second) {
                                "avatar" -> Media.avatarUrl(pic.first, 48, 1f)
                                "icon" -> Media.iconUrl(pic.first, 48, 1f)
                                "emoji" -> Media.emojiUrl(pic.first, 48, 1f)
                                else -> Media.stickerUrl(pic.first, 96, 1f)
                            },
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(name, style = Nx.type.heading.copy(fontSize = Nx.type.sp(17f)), color = c.textStrong, maxLines = 2)
            Txt(t("reports.kind.$kind") + if (handle.isNotEmpty()) " $handle" else "", style = Nx.type.bodySmall, color = c.textMuted, maxLines = 1)
            text.take(2).forEach { Txt(it, Modifier.padding(top = 4.dp), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.text, maxLines = 3) }
        }
    }
}
