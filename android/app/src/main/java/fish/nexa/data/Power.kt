package fish.nexa.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import fish.nexa.Nexa
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone says about its battery, for what only runs when it costs little. */
object Power {
    private val _charging = MutableStateFlow(false)
    /** On a charger. */
    val charging: StateFlow<Boolean> = _charging

    private val _saver = MutableStateFlow(false)
    /** Battery Saver is on. */
    val saver: StateFlow<Boolean> = _saver

    fun install(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(c)
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        read(context)
    }

    private fun read(context: Context) {
        _charging.value = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        _saver.value = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }

    /** Work nobody is waiting on, that costs battery and data: on a charger, on Wi-Fi or the like, and not in Battery Saver. */
    fun plentiful(): Boolean {
        read(Nexa.app)
        if (!_charging.value || _saver.value) return false
        val cm = Nexa.app.getSystemService(ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }
}
