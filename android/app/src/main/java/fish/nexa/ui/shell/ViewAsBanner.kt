package fish.nexa.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx

/** ViewAsBanner.tsx: whose eyes the server is seen through while View Server as Role is on, with the way out. */
@Composable
fun ViewAsBanner() {
    val current by Nexa.servers.viewAs.collectAsState()
    val servers by Nexa.servers.byId.collectAsState()
    val v = current ?: return
    val st = servers[v.serverId] ?: return
    val role = st.roles[v.roleIds.firstOrNull() ?: st.server.id]
    val c = Nx.colors
    Column(Modifier.fillMaxWidth().background(c.bgRaised)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fish.nexa.ui.Icon("Eye", c.brand, size = 18.dp)
            Column(Modifier.weight(1f)) {
                Txt(t("server.viewAs.viewing", "role" to (role?.name ?: ""), "server" to st.server.name), style = Nx.type.bodySmall.copy(fontWeight = FontWeight(600)), color = c.text)
                Txt(t("server.viewAs.onlyYou"), style = Nx.type.bodySmall, color = c.textMuted)
            }
            NxButton(t("server.viewAs.exit"), { Nexa.servers.stopViewAs() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(2.dp).background(c.brand))
    }
}
