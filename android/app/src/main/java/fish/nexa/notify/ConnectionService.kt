package fish.nexa.notify

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fish.nexa.MainActivity
import fish.nexa.Nexa
import fish.nexa.R
import fish.nexa.data.AuthState
import fish.nexa.ui.t
import kotlinx.coroutines.launch

/**
 * Whether Nexa keeps its connection open with the app closed, kept on this
 * device. Nexa has no push service, so this is how messages reach a phone
 * whose app is not on screen; it is on unless turned off in Settings.
 */
object Background {
    private fun prefs() = Nexa.app.getSharedPreferences("background", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs().getBoolean("enabled", true)
        set(v) {
            prefs().edit().putBoolean("enabled", v).apply()
            if (v) ConnectionService.start(Nexa.app) else ConnectionService.stop(Nexa.app)
        }

    /** The phone lets Nexa keep its connection while it sleeps (battery optimisation off for it). */
    fun runsFreely(): Boolean = Nexa.app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(Nexa.app.packageName)

    /** Asks the system to let Nexa stay connected while the phone sleeps. */
    @android.annotation.SuppressLint("BatteryLife")
    fun askToRunFreely(context: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(i) }
    }
}

/**
 * A foreground service while signed in, so the process (and with it the live
 * connection) outlives the app's window: the remote-messaging kind Android
 * made for exactly this. Its notification sits quietly at the bottom of the
 * shade and can be hidden from its channel.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifier.CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF8000FF.toInt())
            .setContentTitle(t("android.notify.connectedTitle"))
            .setContentText(t("android.notify.connectedBody"))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        try {
            ServiceCompat.startForeground(this, ID, n, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // told to stop while this start was on its way (see stop)
        if (arrivedToStop()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // after a reboot or a restart by the system, sign back in from what is kept on the device
        if (Nexa.auth.state.value == AuthState.Loading) Nexa.scope.launch { Nexa.auth.bootstrap() }
        else Nexa.session.wake()
        return START_STICKY
    }

    companion object {
        private const val ID = 7001

        /** Starts asked for that onStartCommand has not reached yet. */
        private var starting = 0
        private var stopAsked = false

        @Synchronized
        fun start(context: Context) {
            if (!Background.enabled) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
                starting++
                stopAsked = false
            } catch (_: Exception) {
                // not allowed from the background right now; the app starts it next time it opens
            }
        }

        /**
         * Android ends the app when a service started for the foreground is
         * stopped before it got there, so a start still on its way is left to
         * arrive, keep that promise and stop itself. A session found revoked
         * just after a reboot or an update did this: a start from BootReceiver,
         * then a stop from signing out.
         */
        @Synchronized
        fun stop(context: Context) {
            if (starting > 0) stopAsked = true
            else context.stopService(Intent(context, ConnectionService::class.java))
        }

        @Synchronized
        private fun arrivedToStop(): Boolean {
            if (starting > 0) starting--
            return stopAsked && starting == 0
        }
    }
}

/** After a restart of the phone or an update of the app: back to staying connected, if signed in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Nexa.secure.getString("token") == null) return
        ConnectionService.start(context)
    }
}
