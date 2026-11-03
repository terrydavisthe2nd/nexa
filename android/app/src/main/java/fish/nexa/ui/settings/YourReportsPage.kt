package fish.nexa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fish.nexa.data.Reports
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.reports.ReceiptCard
import fish.nexa.ui.reports.openReport
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * YourReportsPage.tsx: every report this account sent, where each stands, and
 * what staff said when they closed it (also told in the Nexa conversation).
 */
@Composable
fun YourReportsPage() {
    val c = Nx.colors
    val scope = rememberCoroutineScope()
    val list by Reports.mine.collectAsState()
    val more by Reports.mineMore.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun load(older: Boolean = false) {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            try {
                Reports.loadMine(older)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    PageTitle(t("reports.yours.title"))
    Hint(t("reports.yours.intro"))
    NxButton(t("reports.yours.reportSomething"), { openReport() }, Modifier.padding(top = 16.dp, bottom = 20.dp), icon = "Flag", size = ButtonSize.LG)
    val shown = list
    when {
        failed && shown.isNullOrEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(t("reports.failed"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textDanger)
            NxButton(t("reports.retry"), { load() }, kind = ButtonKind.SECONDARY, size = ButtonSize.TOUCH)
        }
        shown == null -> Txt(t("reports.loading"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f)), color = c.textSubtle)
        shown.isEmpty() -> EmptyBox(t("reports.yours.empty"))
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEach { ReceiptCard(it) }
        }
    }
    if (more) NxButton(t("reports.more"), { load(older = true) }, Modifier.padding(top = 16.dp), kind = ButtonKind.SECONDARY, loading = loading)
}
