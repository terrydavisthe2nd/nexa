package fish.nexa.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.components.Txt
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The host's optional deadline, kept through a lost connection and refreshed after resume. */
@Composable
fun MaintenanceBanner() {
    var rebootAt by remember { mutableLongStateOf(0L) }
    var serverAt by remember { mutableLongStateOf(0L) }
    var received by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            // an effect goes on running with the app out of sight, and each of these wakes the radio
            Nexa.ui.visible.first { it }
            try {
                val status = Nexa.api.get("/maintenance").jsonObject
                rebootAt = status["reboot_at"]?.jsonPrimitive?.longOrNull ?: 0L
                serverAt = status["now"]?.jsonPrimitive?.longOrNull ?: 0L
                received = android.os.SystemClock.elapsedRealtime()
                now = received
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Older servers and downtime keep the app usable. */ }
            delay(10 * 60_000)
        }
    }
    val left = rebootAt - (serverAt + (now - received).coerceAtLeast(0))
    val visible = rebootAt > 0 && left <= 150 * 60_000L
    LaunchedEffect(visible) {
        while (true) {
            Nexa.ui.visible.first { it }
            now = android.os.SystemClock.elapsedRealtime()
            delay(if (visible) 1000 else 60_000)
        }
    }
    if (!visible) return
    val secs = ((left + 999) / 1000).coerceAtLeast(0)
    val time = "%d:%02d:%02d".format(java.util.Locale.ROOT, secs / 3600, (secs / 60) % 60, secs % 60)
    Row(
        Modifier.fillMaxWidth().background(Nx.colors.bgRaised).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fish.nexa.ui.Icon("Timer", Nx.colors.textWarning, size = 20.dp)
        Column(Modifier.weight(1f)) {
            Txt(if (secs > 0) t("maintenance.countdown", "time" to time) else t("maintenance.pending"), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = Nx.colors.textWarning)
            Txt(t("maintenance.downtime"), style = Nx.type.bodySmall, color = Nx.colors.text)
        }
    }
}
