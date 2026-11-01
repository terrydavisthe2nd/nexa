package fish.nexa.ui.shell

import fish.nexa.Nexa
import fish.nexa.net.Channel
import fish.nexa.ui.markdown.AppLinks
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.t
import java.net.URI

/**
 * Link channels (web lib/linkChannel.ts): a name in a server's channel list
 * that opens an address instead of holding messages. One that leads to Nexa
 * itself (an invite, a profile, a channel) opens inside the app; anything
 * else is shown in full first and opens in the browser.
 */
object LinkChannels {
    data class Destination(val href: String, val host: String)

    /** A link channel's address as the apps open it: a whole http or https address with a host and no password in it. */
    fun destination(raw: String?): Destination? {
        val text = raw?.trim()
        if (text.isNullOrEmpty() || text.length > 1024 || text.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        return try {
            val uri = URI(text)
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            Destination(text, if (uri.port != -1) "${uri.host}:${uri.port}" else uri.host)
        } catch (_: Exception) {
            null
        }
    }

    /** Opens what a link channel leads to: in the app when it is Nexa's own, otherwise after showing the whole address. */
    fun open(ch: Channel) {
        val dest = destination(ch.url)
        if (dest == null) {
            Nexa.ui.toast(t("channelLink.broken"), "error")
            return
        }
        when (val app = AppLinks.appLink(dest.href, Nexa.api.baseUrl)) {
            is AppLinks.AppLink.Route -> if (Nav.openPath(app.path)) return
            is AppLinks.AppLink.Invite -> { fish.nexa.ui.dialogs.Dialogs.openInvite(app.code, app.hash); return }
            is AppLinks.AppLink.Profile -> { fish.nexa.ui.profile.Profiles.openByUsername(app.username); return }
            is AppLinks.AppLink.Note -> { fish.nexa.ui.notes.openNoteLink(app.token, app.hash); return }
            null -> Unit
        }
        confirm(t("channelLink.openTitle"), t("channelLink.openBody", "name" to ch.name) + "\n\n" + dest.href, t("textMenu.openLink"), danger = false) {
            fish.nexa.ui.profile.openLink(dest.href)
        }
    }
}
