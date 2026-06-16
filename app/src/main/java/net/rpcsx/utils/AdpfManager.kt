package net.rpcsx.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.util.Log
import net.rpcsx.RPCSX

/**
 * ADPF (Android Dynamic Performance Framework) hint feed. OFF by default.
 *
 * The reactive [ThermalManager] only caps fps AFTER the SoC is already throttling
 * (fan already loud). ADPF works the other way round: it tells the OS scheduler the
 * presenting thread's real per-frame CPU work via PerformanceHintManager, so the
 * scheduler can pick the LOWEST CPU clock that still hits the frame target - same
 * fps, less heat, BEFORE any throttle. Purely advisory: if the device or scheduler
 * ignores the hint, behaviour is identical to off. Requires API 31 (S).
 *
 * The work figure comes from the core's RSX flip loop (wall interval minus the
 * frame-limiter sleep), polled here and forwarded once per ~frame. We also log
 * PowerManager.getThermalHeadroom() once a second so an on-device A/B run (hint on
 * vs off, same scene) can be PROVEN from the logs instead of guessed.
 *
 * Default OFF until that A/B proves it helps - per the project rule never to
 * default-on an unproven feature.
 */
object AdpfManager {
    private const val TAG = "AdpfManager"
    private const val KEY = "adpf_hints"

    // Fallback target (60fps budget) used only until the core reports the real frame
    // period. The live target is the measured frame period (deadline): we report actual
    // WORK (busy time, idle excluded), so a game whose work fits under the period lets the
    // scheduler ramp the clock DOWN (the heat win) instead of over-boosting a 30fps game
    // against a fixed 60fps target.
    private const val DEFAULT_TARGET_NANOS = 16_666_666L
    private const val TARGET_EPSILON_NANOS = 2_000_000L // 2ms - avoid spamming target updates
    private const val POLL_MS = 16L

    var enabled: Boolean
        get() = GeneralSettings[KEY] as? Boolean ?: false
        set(value) { GeneralSettings[KEY] = value }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var session: PerformanceHintManager.Session? = null
    private var sessionTid = 0
    private var pm: PowerManager? = null
    @Volatile private var running = false
    private var headroomTick = 0
    private var lastTargetNanos = 0L

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!enabled) return
        val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
        pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val t = HandlerThread("adpf-hint").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        running = true
        headroomTick = 0

        // The RSX thread may not have flipped yet when the game is still booting, so
        // the tid is 0 until the first frame. Poll until it appears, then create the
        // session with that tid and start reporting.
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                val tid = runCatching { RPCSX.instance.getRsxThreadTid() }.getOrDefault(0)

                // (Re)create the hint session when we have none, OR when the RSX thread's
                // tid changed - an in-app game restart spawns a NEW RSX thread, and the old
                // session would keep hinting a dead thread. Recreate so the scheduler tracks
                // the live thread.
                if (tid != 0 && (session == null || tid != sessionTid)) {
                    val period = runCatching { RPCSX.instance.getFramePeriodNanos() }.getOrDefault(0L)
                    val target = if (period > 0L) period else DEFAULT_TARGET_NANOS
                    runCatching { session?.close() }
                    session = runCatching { phm.createHintSession(intArrayOf(tid), target) }.getOrNull()
                    if (session != null) {
                        sessionTid = tid
                        lastTargetNanos = target
                        Log.i(TAG, "ADPF hint session created (rsx tid=$tid, target=${target}ns)")
                    } else {
                        // Device/driver does not support hint sessions - stop quietly.
                        Log.i(TAG, "ADPF hint session unsupported on this device; disabling feed")
                        running = false
                        return
                    }
                }

                val sess = session
                if (sess != null) {
                    // Track the deadline: if the frame period shifts (e.g. 30<->60fps), update
                    // the target so the scheduler aims for the real budget, not a fixed one.
                    val period = runCatching { RPCSX.instance.getFramePeriodNanos() }.getOrDefault(0L)
                    if (period > 0L && kotlin.math.abs(period - lastTargetNanos) > TARGET_EPSILON_NANOS) {
                        runCatching { sess.updateTargetWorkDuration(period) }
                        lastTargetNanos = period
                    }
                    val work = runCatching { RPCSX.instance.getFrameWorkNanos() }.getOrDefault(0L)
                    if (work > 0L) {
                        runCatching { sess.reportActualWorkDuration(work) }
                    }
                }

                // A/B telemetry: thermal headroom (0 = none, ~1 = at throttle).
                if (++headroomTick >= (1000L / POLL_MS).toInt()) {
                    headroomTick = 0
                    val hr = runCatching { pm?.getThermalHeadroom(10) }.getOrNull()
                    if (hr != null && !hr.isNaN()) {
                        Log.i(TAG, "thermal headroom=%.3f work=%.2fms".format(hr,
                            (runCatching { RPCSX.instance.getFrameWorkNanos() }.getOrDefault(0L)) / 1_000_000.0))
                    }
                }

                h.postDelayed(this, POLL_MS)
            }
        })
    }

    fun unregister() {
        running = false
        val h = handler
        val t = thread
        // Close the session ON the poll thread (h) so it serializes with any in-flight
        // report - closing it from this (main) thread could race a concurrent
        // reportActualWorkDuration on the poll thread (use-after-close).
        h?.removeCallbacksAndMessages(null)
        h?.post {
            runCatching { session?.close() }
            session = null
            sessionTid = 0
            t?.quitSafely()
        }
        handler = null
        thread = null
        pm = null
    }
}
