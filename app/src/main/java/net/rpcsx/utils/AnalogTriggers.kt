package net.rpcsx.utils

import kotlin.math.pow

/**
 * Analog (pressure) L2/R2 for physical gamepads.
 *
 * The PS3 L2/R2 are pressure buttons (0-255, reported to games in press mode). Android pads
 * report a trigger as AXIS_LTRIGGER/AXIS_RTRIGGER and/or AXIS_BRAKE/AXIS_GAS, and many
 * handhelds (AYN Odin 2, Retroid, ...) ALSO send KEYCODE_BUTTON_L2/R2 key events for the same
 * physical trigger. This file holds the Android-free part: settings, the response curve and
 * the key/axis arbitration. RPCSXActivity reads the MotionEvent axes and feeds this.
 */
data class AnalogTriggerConfig(
    /** Send the trigger's travel to the PS3 as pressure. Off = legacy on/off triggers. */
    val enabled: Boolean = DEFAULT_ENABLED,
    /** Travel (0-50 %) ignored at the start of the pull, for triggers that never rest at 0. */
    val deadzonePercent: Int = DEFAULT_DEADZONE,
    /**
     * Response curve exponent in percent (50-250). 100 = linear. Above 100 the first part of
     * the pull is gentler (finer throttle control on short handheld triggers; full pressure
     * still needs a full pull), below 100 pressure builds faster.
     */
    val curvePercent: Int = DEFAULT_CURVE,
) {
    /** Map a raw axis value (0..1) to PS3 pressure 0..255; 0 means "not pressed". */
    fun axisToPressure(raw: Float): Int {
        val x = if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)
        if (!enabled) {
            // Legacy behaviour: a plain digital threshold, full pressure when pressed.
            return if (x > LEGACY_DIGITAL_THRESHOLD) 255 else 0
        }
        val dz = deadzonePercent.coerceIn(0, MAX_DEADZONE) / 100f
        if (x <= dz) return 0
        var t = (x - dz) / (1f - dz)
        val exponent = curvePercent.coerceIn(MIN_CURVE, MAX_CURVE) / 100f
        if (exponent != 1f) t = t.pow(exponent)
        // Any travel past the deadzone is at least 1 so pressure and the digital bit agree.
        return (t * 255f + 0.5f).toInt().coerceIn(1, 255)
    }

    companion object {
        const val KEY_ENABLED = "analog_triggers"
        const val KEY_DEADZONE = "analog_triggers_deadzone"
        const val KEY_CURVE = "analog_triggers_curve"

        const val DEFAULT_ENABLED = true
        const val DEFAULT_DEADZONE = 5
        const val DEFAULT_CURVE = 100
        const val MAX_DEADZONE = 50
        const val MIN_CURVE = 50
        const val MAX_CURVE = 250
        const val LEGACY_DIGITAL_THRESHOLD = 0.1f

        fun load(): AnalogTriggerConfig = AnalogTriggerConfig(
            enabled = GeneralSettings[KEY_ENABLED] as? Boolean ?: DEFAULT_ENABLED,
            deadzonePercent = GeneralSettings[KEY_DEADZONE] as? Int ?: DEFAULT_DEADZONE,
            curvePercent = GeneralSettings[KEY_CURVE] as? Int ?: DEFAULT_CURVE,
        )
    }
}

/**
 * Key/axis arbitration for ONE physical trigger.
 *
 * - While the axis reports travel, the axis owns the trigger: a key-down (or key repeat) must
 *   not snap a half-pulled trigger to full pressure.
 * - A device in "digital mode" may list the axis but only send keys, so when the axis stays
 *   idle a held key still gives a full press.
 * - A press is "claimed" by the axis if the axis moved during it, or moved within
 *   [axisGraceMs] before the key-down (drivers usually emit the key from an axis threshold).
 *   A claimed press never falls back to the key, so letting go (axis returns to 0 a moment
 *   before the key-up arrives) does not flash a full press either.
 */
class TriggerArbiter(private val axisGraceMs: Long = 150L) {
    private var keyHeld = false
    private var claimedByAxis = false
    private var axisPressure = 0
    private var lastAxisActiveMs = Long.MIN_VALUE / 2

    fun onAxis(pressure: Int, nowMs: Long) {
        axisPressure = pressure.coerceIn(0, 255)
        if (axisPressure > 0) {
            lastAxisActiveMs = nowMs
            if (keyHeld) claimedByAxis = true
        }
    }

    fun onKey(down: Boolean, nowMs: Long) {
        if (down) {
            if (!keyHeld) { // repeats are ignored
                keyHeld = true
                claimedByAxis = axisPressure > 0 || nowMs - lastAxisActiveMs <= axisGraceMs
            }
        } else {
            keyHeld = false
            claimedByAxis = false
        }
    }

    /** Effective pressure 0..255 (0 = released). */
    val pressure: Int
        get() = when {
            axisPressure > 0 -> axisPressure
            keyHeld && !claimedByAxis -> 255
            else -> 0
        }

    fun reset() {
        keyHeld = false
        claimedByAxis = false
        axisPressure = 0
        lastAxisActiveMs = Long.MIN_VALUE / 2
    }
}
