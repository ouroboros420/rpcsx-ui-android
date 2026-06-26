package net.rpcsx.utils

import net.rpcsx.RPCSX
import net.rpcsx.utils.GeneralSettings.boolean

/**
 * App-side opt-in GPU turbo (Clanker Settings, default OFF). When on, asks the native layer to
 * force the Adreno GPU to its maximum clocks (disables DVFS scaling) via adrenotools' KGSL ioctl,
 * removing the ramp-up stutter in GPU-bound scenes at the cost of extra heat and battery. It is
 * the opposite of [PowerPolicy] (battery saver), so the UI keeps the two mutually exclusive. The
 * native call opens /dev/kgsl-3d0 itself and no-ops on non-Adreno hardware, so it is always safe.
 */
object GpuTurbo {
    private const val KEY = "gpu_turbo"

    var enabled: Boolean
        get() = GeneralSettings[KEY].boolean(false)
        set(value) { GeneralSettings[KEY] = value }

    /** Push the current state to the GPU. Call at startup and on toggle. */
    fun apply() {
        runCatching { RPCSX.instance.setGpuTurbo(enabled) }
    }
}
