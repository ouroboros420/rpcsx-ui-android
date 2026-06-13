package net.rpcsx.utils

import android.content.Context
import android.os.Build
import android.os.PowerManager
import net.rpcsx.RPCSX

/**
 * Maps the Android thermal status to a core frame-rate cap so the device can
 * cool under sustained load (less fan, fewer hard-throttle stutters) instead of
 * the SoC slamming into a hard throttle. Registered only while a game runs
 * (RPCSXActivity). Additive: NONE/LIGHT => no cap, so normal play is unaffected.
 * Gated on the Battery-saver toggle - power users who opt out keep full control.
 */
object ThermalManager {
    private var pm: PowerManager? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!PowerPolicy.enabled) return
        val p = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status -> apply(status) }
        runCatching {
            p.addThermalStatusListener(l)
            pm = p
            listener = l
            apply(p.currentThermalStatus)
        }
    }

    fun unregister() {
        val p = pm
        val l = listener
        if (p != null && l != null) runCatching { p.removeThermalStatusListener(l) }
        pm = null
        listener = null
        runCatching { RPCSX.instance.setThermalFrameCap(0f) }
    }

    private fun apply(status: Int) {
        val cap = when (status) {
            PowerManager.THERMAL_STATUS_MODERATE -> 45f
            PowerManager.THERMAL_STATUS_SEVERE -> 30f
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> 20f
            else -> 0f // NONE / LIGHT
        }
        runCatching { RPCSX.instance.setThermalFrameCap(cap) }
    }
}
