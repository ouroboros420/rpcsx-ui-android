package net.rpcsx.utils

import android.app.ActivityManager
import android.content.Context
import net.rpcsx.RPCSX
import net.rpcsx.utils.GeneralSettings.boolean

/**
 * App-side, device-adaptive default for the core's "Max LLVM Compile Threads".
 *
 * The PPU/SPU LLVM compiler runs one worker per core by default (the RPCSX/RPCS3
 * setting 0 = auto = all cores). Each concurrent module compile holds a lot of
 * RAM, so on low-memory devices "all cores" drives the device into the Android
 * Low Memory Killer mid-compile (the game appears to "not start"). The fix the
 * emulator expects is simply a lower thread count - this picks a memory-safe one
 * automatically so testers never have to find the buried setting.
 *
 * IMPORTANT: this does not invent a divergent recompiler mechanism. It feeds a
 * device-safe value into the core's existing per-pool thread sizing via a small
 * Android-only cap (setMaxCompileThreads -> get_compile_thread_cap). Unlike
 * writing the "Max LLVM Compile Threads" config, the cap is applied to the
 * EFFECTIVE thread count, so a per-game custom config (which overrides the global
 * config at boot) cannot silently undo it - that override was why a capped device
 * still OOM'd. The cap is transparent: it leaves the user's visible setting alone.
 * High-memory devices get 0 (no cap / all cores) - identical to stock behaviour.
 */
object CompileThreadPolicy {
    private const val KEY = "auto_compile_threads"

    /** Master switch (Clanker Settings). Default on: harmless on high-RAM. */
    var enabled: Boolean
        get() = GeneralSettings[KEY].boolean(true)
        set(value) { GeneralSettings[KEY] = value }

    /** Total physical RAM the kernel reports (matches the core's get_total_memory). */
    private fun totalRamGib(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0.0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Memory-safe compile-thread cap for this device. 0 = no cap (all cores).
     *
     * Calibrated against Android reality, NOT desktop math: total RAM badly
     * over-predicts what is usable for compilation. An 8 GB device reports
     * ~7.5 GiB here, yet the Low Memory Killer fires long before the app can use
     * half of that - it OOM'd at the 8-core default and only booted at 2 threads.
     * So the tiers are deliberately conservative: an 8 GB-class device is capped
     * to 2, and only genuinely large-RAM devices run uncapped.
     */
    fun safeThreads(context: Context): Int {
        val gib = totalRamGib(context)
        return when {
            gib <= 0.0 -> 0       // couldn't read RAM: don't interfere
            gib < 9.0 -> 2        // <=8 GB-class: confirmed OOM at auto, boots at 2
            gib < 13.0 -> 4       // 12 GB devices
            else -> 0             // 16 GB+: stock auto (all cores)
        }
    }

    /**
     * Device-scaled LLVM compile MEMORY budget in bytes (0 = let the core use its
     * own conservative fallback). This is the real OOM guard: it bounds how much
     * RAM concurrent module compiles may hold, so a worst-case large module (Mafia
     * II: ~1.67 GB) serializes (compiles alone) instead of coexisting with another
     * and blowing past the Low Memory Killer ceiling, while small modules still
     * compile concurrently.
     *
     * Why not the core's get_total_memory()/3: on Android sysconf over-reports (it
     * counts zRAM pages), so total/3 (~3.5 GiB on this 8 GB device) is far larger
     * than the ~3 GB the process can actually allocate before being killed. We use
     * ActivityManager.totalMem (honest physical RAM) to pick a safe scaled budget,
     * sized below one ~1.67 GB module on memory-constrained devices so big modules
     * serialize. This is applied independently of the thread cap toggle: the budget
     * is pure safety + device-scaling, not a feature to switch off.
     */
    fun safeBudgetBytes(context: Context): Long {
        val gib = totalRamGib(context)
        val mib = when {
            gib <= 0.0 -> 0L      // couldn't read RAM: core falls back to its own cap
            gib < 9.0 -> 1536L    // <=8 GB: below one ~1.67 GB module -> serialize big ones
            gib < 13.0 -> 2560L   // 12 GB
            gib < 24.0 -> 4096L   // 16 GB
            else -> 6144L         // 24 GB+
        }
        return mib * 1024L * 1024L
    }

    /**
     * Install the effective compile policy in the core. Call once after the core is
     * initialized and whenever the toggle changes - before any game boots.
     *
     * - Memory budget: always pushed (device-scaled safety; the real OOM guard).
     * - Thread cap: pushed only when enabled. When disabled (or on high-RAM
     *   devices) the cap is 0 == no cap; the memory budget still protects against
     *   OOM, so disabling the cap can't reintroduce the mid-compile crash.
     */
    fun apply(context: Context) {
        runCatching { RPCSX.instance.setCompileMemoryBudget(safeBudgetBytes(context)) }
        val cap = if (enabled) safeThreads(context) else 0
        runCatching { RPCSX.instance.setMaxCompileThreads(cap) }
    }
}
