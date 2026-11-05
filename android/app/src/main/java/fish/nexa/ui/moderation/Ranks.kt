package fish.nexa.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.ModUser
import fish.nexa.data.Moderation
import fish.nexa.data.StaffRank
import fish.nexa.data.StaffRanks
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.theme.ring
import fish.nexa.ui.describeError
import fish.nexa.ui.layers.openSheet
import fish.nexa.ui.settings.RadioCard
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.launch

/** The ranks the owner gives, from the top of the ladder down, then none. */
private val LADDER = listOf(StaffRank.DEVELOPER, StaffRank.ADMIN, StaffRank.MOD, StaffRank.HELPER, null)

/**
 * The web's RankModal: the owner's choice of someone's rank, the ladder of
 * what can be given with what each rung can do, the one held now marked.
 * Only the owner is offered it; the server refuses anyone else.
 */
fun openRankSheet(u: ModUser, onDone: () -> Unit) {
    val held = StaffRanks.of(u.id, u.adminFlags, Moderation.ownerId.value?.ifEmpty { null }).takeIf { it != StaffRank.OWNER }
    var pick by mutableStateOf(held)
    var busy by mutableStateOf(false)
    openSheet("staff-rank", title = t("staff.rankModal.title", "name" to u.displayName), heading = { fish.nexa.ui.chat.PersonHeading(fish.nexa.ui.chat.PersonWords(t("staff.rankModal.title", "name" to fish.nexa.ui.chat.PERSON_SLOT), listOf(u.id to u.displayName))) }, footer = { close ->
        val scope = rememberCoroutineScope()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            NxButton(t("chat.editMessage.cancel"), { close() }, kind = ButtonKind.SECONDARY)
            NxButton(t("staff.rankModal.save"), {
                busy = true
                scope.launch {
                    try {
                        Moderation.access(u.id, rank = pick?.key ?: "")
                        Nexa.ui.toast(t("staff.rankModal.done", "name" to u.displayName, "rank" to (pick?.label ?: t("staff.rankModal.none"))), "success")
                        onDone()
                        close()
                    } catch (e: Exception) {
                        Nexa.ui.toast(describeError(e, t("moderation.failed")), "error")
                    } finally {
                        busy = false
                    }
                }
            }, loading = busy, enabled = pick != held)
        }
    }) {
        val c = Nx.colors
        Txt(t("staff.rankModal.body"), style = Nx.type.bodySmall, color = c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
        for (rank in LADDER) {
            // No Rank sits a little apart from the ranks above it
            if (rank == null) Box(Modifier.size(6.dp))
            RadioCard(
                label = (rank?.label ?: t("staff.rankModal.none")) + if (rank == held) "  ·  " + t("staff.rankModal.current") else "",
                selected = pick == rank,
                onClick = { pick = rank },
                description = rank?.powers ?: t("staff.rankModal.nonePowers"),
                leading = { RankDisc(rank) },
            )
        }
    }
}

/** A rung's mark: the rank's icon in the accent's disc, a plain one for No Rank. */
@Composable
private fun RankDisc(rank: StaffRank?) {
    val c = Nx.colors
    Box(
        Modifier
            .size(34.dp)
            .then(if (rank != null) Modifier.ring(CircleShape, c.brand.copy(alpha = 0.30f)).background(c.brand.copy(alpha = 0.16f), CircleShape) else Modifier.ring(CircleShape, c.borderSubtle).background(c.bgSurface, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(rank?.icon ?: "UserRound", if (rank != null) c.textBrand else c.textMuted, size = 18.dp)
    }
}
