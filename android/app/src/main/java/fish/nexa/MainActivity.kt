package fish.nexa

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import fish.nexa.ui.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { Nexa.auth.bootstrap() }
        // the forms apps open, from wherever an interaction brings one
        fish.nexa.ui.apps.installAppForms()
        setContent { App() }
        // slow frames for the performance report, measured only while frames are drawn
        fish.nexa.data.PerfReports.watchFrames(window)
        if (!open(intent)) lifecycleScope.launch {
            Nexa.auth.state.first { it is fish.nexa.data.AuthState.Ready }
            fish.nexa.ui.nav.Nav.restore()
        }
    }

    override fun onDestroy() {
        fish.nexa.data.PerfReports.unwatchFrames(window)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /** A notification, a link or a share opened the app: act on it once signed in. False when it said nothing. */
    private fun open(intent: Intent?): Boolean {
        // debug builds only: open a screen directly for checking it (adb shell am start ... --es debug.open whatsnew)
        if (BuildConfig.DEBUG && intent?.getStringExtra("debug.open") == "whatsnew") {
            lifecycleScope.launch {
                Nexa.auth.state.first { it is fish.nexa.data.AuthState.Ready }
                fish.nexa.ui.WhatsNew.openAll()
            }
            return true
        }
        // ... --es debug.open settings/appearance (or settings/profiles/typing): a settings page, for the same
        val debugSettings = intent?.getStringExtra("debug.open")?.takeIf { BuildConfig.DEBUG && it.startsWith("settings") }
        if (debugSettings != null) {
            val parts = debugSettings.split("/")
            lifecycleScope.launch {
                Nexa.auth.state.first { it is fish.nexa.data.AuthState.Ready }
                fish.nexa.ui.settings.Settings.open(parts.getOrNull(1), parts.getOrNull(2))
            }
            return true
        }
        fish.nexa.ui.share.Share.from(intent)?.let { incoming ->
            val direct = fish.nexa.ui.share.Share.channelOf(intent)
            lifecycleScope.launch {
                Nexa.auth.state.first { it is fish.nexa.data.AuthState.Ready }
                if (direct != null && Nexa.channels.get(direct) != null) fish.nexa.ui.share.Share.deliver(incoming, direct)
                else fish.nexa.ui.share.Share.openPicker(incoming)
            }
            return true
        }
        // a shared note's key is the part after the #, which never leaves the device: it comes along, and
        // a link that adds an app says which and how in its query
        val path = intent?.getStringExtra(fish.nexa.notify.Notifier.EXTRA_PATH)
            ?: intent?.data?.takeIf { it.scheme == "https" }?.let { uri ->
                uri.path?.let { p -> p + (uri.encodedQuery?.let { "?$it" } ?: "") + (uri.encodedFragment?.let { "#$it" } ?: "") }
            }
            ?: return false
        lifecycleScope.launch {
            Nexa.auth.state.first { it is fish.nexa.data.AuthState.Ready }
            fish.nexa.ui.nav.Nav.openPath(path)
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        Nexa.ui.setVisible(true)
        Nexa.session.wake()
    }

    override fun onStop() {
        super.onStop()
        Nexa.ui.setVisible(false)
    }

    // The app's own pickers, cameras and share sheets cover it without leaving
    // it, so they do not count as leaving for the passcode's lock.
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        fish.nexa.data.LockTimers.picking = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (intent.action == Intent.ACTION_CHOOSER) fish.nexa.data.LockTimers.picking = true
        super.startActivity(intent, options)
    }
}
