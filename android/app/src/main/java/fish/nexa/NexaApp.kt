package fish.nexa

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import fish.nexa.data.AuthState
import kotlinx.coroutines.launch
import fish.nexa.ui.I18n
import fish.nexa.ui.Lucide
import fish.nexa.ui.auth.CaptchaHost

class NexaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        fish.nexa.data.Ota.init(this)
        Nexa.init(this)
        I18n.init(this)
        I18n.use(null)
        Lucide.init(this)
        fish.nexa.media.Media.init(this)
        fish.nexa.media.Twemoji.init(this)
        fish.nexa.ui.markdown.EmojiAssets.init(this)
        // the picker offers only emoji Twemoji has drawn; the tables load off the main thread
        fish.nexa.ui.markdown.EmojiData.has = { fish.nexa.media.Twemoji.has(it) }
        Nexa.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            fish.nexa.ui.markdown.Shortcodes.preload()
            fish.nexa.ui.markdown.EmojiData.preload()
        }
        Nexa.auth.captcha = CaptchaHost
        fish.nexa.notify.Notifier.install()
        fish.nexa.ui.nexa.NexaAlerts.install()
        fish.nexa.notify.StaffAlerts.install()
        fish.nexa.data.Power.install(this)
        fish.nexa.ui.components.Decor.install()
        fish.nexa.search.SearchIndex.install()
        // notifications read a message's words as plain text, mentions as names
        fish.nexa.notify.Notifier.plainText = { src -> fish.nexa.ui.markdown.Markdown.plainText(fish.nexa.ui.markdown.Markdown.parse(src), fish.nexa.ui.chat.AppNames) }
        // signed in: stay connected in the background (Nexa has no push); signed out: stop
        Nexa.scope.launch {
            Nexa.auth.state.collect { s ->
                when (s) {
                    is AuthState.Ready -> fish.nexa.notify.ConnectionService.start(this@NexaApp)
                    AuthState.Anonymous -> fish.nexa.notify.ConnectionService.stop(this@NexaApp)
                    else -> Unit
                }
            }
        }
        // calls: rings, the mute buttons and the voice events, from the start
        fish.nexa.voice.Calls.install(this)
        fish.nexa.voice.Pip.install(this)
        Nexa.scope.launch { Nexa.auth.bootstrap() }
        watchNetwork()
        fish.nexa.data.Ota.start()
    }

    /**
     * Says at once when the phone loses its network (the banner), and when it
     * comes back retries signing in if that failed offline, and reconnects.
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        Nexa.ui.setOnline(cm.activeNetwork != null)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Nexa.ui.setOnline(true)
                    if (Nexa.auth.state.value is AuthState.Offline) Nexa.scope.launch { Nexa.auth.bootstrap() }
                    else Nexa.session.wake()
                }

                override fun onLost(network: Network) {
                    // moving from Wi-Fi to mobile data is not going offline
                    val now = cm.activeNetwork
                    Nexa.ui.setOnline(now != null && now != network)
                }
            })
        }
    }
}
