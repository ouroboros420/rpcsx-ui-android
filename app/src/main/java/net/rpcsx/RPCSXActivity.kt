package net.rpcsx

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.activity.OnBackPressedCallback
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import net.rpcsx.databinding.ActivityRpcs3Binding
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.overlay.State
import net.rpcsx.utils.AnalogTriggerConfig
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.InputBindingPrefs
import net.rpcsx.utils.TriggerArbiter
import kotlin.concurrent.thread
import kotlin.math.abs

class RPCSXActivity : ComponentActivity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private var gamePadState: State = State()
    private var bootThread: Thread? = null
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }

    // Physical triggers: index 0 = left (KEYCODE_BUTTON_L2 + AXIS_LTRIGGER/AXIS_BRAKE),
    // 1 = right (KEYCODE_BUTTON_R2 + AXIS_RTRIGGER/AXIS_GAS). Each drives whatever PS3 button
    // its trigger key is bound to in Key Mappings (L2/R2 by default).
    private val triggerConfig by lazy { AnalogTriggerConfig.load() }
    private val triggerArbiters = arrayOf(TriggerArbiter(), TriggerArbiter())
    private var l2Pressure = -1
    private var r2Pressure = -1

    private companion object {
        // Game-reference string-extra keys accepted from external frontends, in priority order:
        // our internal "path", our documented "net.rpcsx.GAME", then the keys other Android
        // emulators standardised on (bootPath = DuckStation/AetherSX2; game_dir/iso_uri = aPS3e;
        // ROM = RetroArch; AutoStartFile = Dolphin; SelectedGame = Citra; game).
        val EXTERNAL_GAME_EXTRA_KEYS = listOf(
            "path", "net.rpcsx.GAME", "bootPath", "game_dir", "iso_uri",
            "ROM", "AutoStartFile", "SelectedGame", "game"
        )
    }

    // Back-button policy: during gameplay the system back button opens the
    // in-game quick (home) menu instead of exiting. Exit happens from that menu
    // ("Exit Game"), which stops the emulator; the exit watcher then finishes
    // this activity so we return to the library. Registered via the
    // OnBackPressedDispatcher so it fires reliably on Android 13+ predictive
    // back (overriding the deprecated onBackPressed() does not).
    private val watcherHandler = Handler(Looper.getMainLooper())
    private val stateWatcher = object : Runnable {
        override fun run() {
            // Started only once the game is live (see startExitWatcher), so a
            // Stopped state here means the user exited from the quick menu.
            if (RPCSX.getState() == EmulatorState.Stopped) {
                finish()
                return
            }
            watcherHandler.postDelayed(this, 500)
        }
    }

    private fun startExitWatcher() {
        watcherHandler.removeCallbacks(stateWatcher)
        watcherHandler.post(stateWatcher)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (RPCSX.getState()) {
                EmulatorState.Running, EmulatorState.Paused ->
                    // open_home_menu is idempotent, so spamming back is harmless.
                    runCatching { RPCSX.instance.openHomeMenu() }
                else ->
                    // Not in active gameplay (booting/stopped): leave to library.
                    finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRpcs3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        unregisterUsbEventListener = listenUsbEvents(this)
        enableFullScreenImmersive()
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Thermal-aware frame cap: cools the device under sustained load.
        net.rpcsx.utils.ThermalManager.register(this)

        // ADPF scheduler hints (opt-in): feed real frame work to PerformanceHintManager
        // so the SoC can run cooler at the same fps. No-op unless the toggle is on.
        net.rpcsx.utils.AdpfManager.register(this)

        // Sustained performance mode caps the SoC to a PASSIVELY-sustainable clock.
        // On an actively-cooled handheld (e.g. Retroid Pocket 6 has a fan) that just
        // caps the peak fps we want, with little thermal upside since the fan handles
        // cooling. Unproven to help our CPU/SPU-bound workload, so default OFF per the
        // "don't default-on unverified features" rule. User-toggleable: enable it to
        // trade peak fps for lower heat/fan on long sessions. No-op where unsupported.
        if ((GeneralSettings["sustained_performance"] as? Boolean) == true) {
            (getSystemService(POWER_SERVICE) as? PowerManager)?.let { pm ->
                if (pm.isSustainedPerformanceModeSupported) {
                    window.setSustainedPerformanceMode(true)
                }
            }
        }

        binding.oscToggle.setOnClickListener {
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        val gamePath = resolveGamePath(intent)
        if (gamePath == null) {
            // No usable game reference. The app's own launch always sends one; an external
            // app/frontend might send an empty or unrecognised intent. Open the library
            // instead of crashing on the old getStringExtra("path")!! null-assert.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        startBoot(gamePath)
    }

    /**
     * Boot a game path. A content:// URI pointing at an .iso is opened as a SAF fd and booted
     * directly (no extraction) via the core's fd entry point; everything else is a filesystem
     * path handed to the normal boot(). A fresh fd is opened on every boot because provider fds
     * do not survive provider restarts.
     */
    private fun bootGamePath(gamePath: String): BootResult {
        if (gamePath.startsWith("content://") && gamePath.substringBefore('?').endsWith(".iso", ignoreCase = true)) {
            val uri = gamePath.toUri()
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                ?: return BootResult.InvalidFileOrFolder
            // Native side owns the fd (detached); it closes it when the ISO device is torn down.
            val fd = pfd.detachFd()
            return RPCSX.bootIsoFd(fd, gamePath)
        }
        return RPCSX.boot(gamePath)
    }

    /**
     * Boot (or resume/switch to) a game on a background thread. Shared by the in-app launch
     * (onCreate) and external frontend launches that reuse the singleTask instance (onNewIntent).
     */
    private fun startBoot(gamePath: String) {
        RPCSX.lastPlayedGame = gamePath

        bootThread = thread {
            if (RPCSX.getState() != EmulatorState.Stopped) {
                val state = RPCSX.getState()
                Log.w("RPCSX State", state.name)

                if (state == EmulatorState.Paused && RPCSX.activeGame.value == gamePath) {
                    RPCSX.instance.resume()
                    runOnUiThread { startExitWatcher() }
                    return@thread
                }

                if (RPCSX.getState() != EmulatorState.Stopping && RPCSX.getState() != EmulatorState.Stopped) {
                    RPCSX.instance.kill()

                    while (RPCSX.getState() != EmulatorState.Stopped) {
                        Thread.sleep(300)
                        if (Thread.interrupted()) {
                            return@thread
                        }
                    }
                }
            }

            Log.w("RPCSX State", RPCSX.getState().name)
            RPCSX.activeGame.value = gamePath

            val bootResult = bootGamePath(gamePath)
            if (bootResult != BootResult.NoErrors) {
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    getString(R.string.error_with_msg, bootResult.name)
                )
                finish()
            } else {
                // Game is live: from here a Stopped state means a deliberate exit
                // (quick menu "Exit Game"), so it is safe to finish the activity.
                runOnUiThread { startExitWatcher() }
            }
        }
    }

    // singleTask: a frontend may launch a (possibly different) game while one runs. Re-resolve
    // and boot; startBoot's thread kills the current game or resumes if it is the same one.
    // Intents with no game reference are ignored (keep the running game).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveGamePath(intent)?.let { startBoot(it) }
    }

    /**
     * Resolve the game to boot from an incoming intent. Supports the app's own internal launch
     * (the "path" string extra) AND external Android emulation frontends (Daijisho, ES-DE,
     * Pegasus). Those launch us by explicit component with the game either as the ACTION_VIEW
     * data URI, or in a string extra - emulators standardised on different extra keys, so we
     * accept the common ones plus our own documented "net.rpcsx.GAME". Returns null when no
     * usable reference is present.
     */
    private fun resolveGamePath(intent: Intent): String? {
        for (key in EXTERNAL_GAME_EXTRA_KEYS) {
            val value = intent.getStringExtra(key)
            if (!value.isNullOrBlank()) {
                normalizeRef(value)?.let { return it }
            }
        }
        intent.data?.let { uri -> resolveUri(uri)?.let { return it } }
        return null
    }

    /** A bare extra value may be a raw filesystem path or a URI string. */
    private fun normalizeRef(ref: String): String? =
        if (ref.startsWith("/")) ref else resolveUri(ref.toUri())

    private fun resolveUri(uri: Uri): String? = when (uri.scheme) {
        null -> uri.toString().takeIf { it.startsWith("/") }
        "file" -> uri.path
        "content" -> resolveContentUri(uri)
        else -> uri.toString()
    }

    /**
     * Best-effort content:// -> filesystem path. RPCSX.boot() opens a filesystem path, so we map
     * the common Storage Access Framework case (the external-storage documents provider that
     * Daijisho {file.uri} / ES-DE %ROMSAF% produce) back to a real path. For other providers we
     * persist read access and return the URI string; if the core cannot open it the boot fails
     * with a normal error dialog rather than crashing.
     */
    private fun resolveContentUri(uri: Uri): String? {
        // Prefer a real filesystem path (the core opens paths, not SAF); fall back to the raw
        // URI string for providers we cannot map, letting the core fail with a normal dialog.
        return net.rpcsx.utils.SafPath.resolveTreeUriToRealPath(this, uri) ?: uri.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        watcherHandler.removeCallbacks(stateWatcher)
        RPCSX.state.value = EmulatorState.Paused
        net.rpcsx.utils.ThermalManager.unregister()
        net.rpcsx.utils.AdpfManager.unregister()
        unregisterUsbEventListener()
        // Never block the UI thread here. The boot thread can be parked inside the
        // blocking native RPCSX.boot() call (a first-boot PPU precompile runs for
        // minutes); Thread.interrupt() cannot break a native JNI call, so joining it
        // on the main thread froze the UI -> ANR ("Waited 5000ms for MotionEvent")
        // when the user backed out of a still-compiling first boot and then touched
        // the screen / recents. Both activities share this one main thread, so the
        // stall surfaces as a MainActivity input-dispatch ANR.
        //
        // Signal the core to stop so the in-flight precompile aborts (Emu.Kill is
        // idempotent and honored by the precompile's Emu.IsStopped() checks), then
        // drain the boot thread on a detached worker so onDestroy returns at once.
        val threadToDrain = bootThread
        bootThread = null
        if (threadToDrain != null) {
            thread(isDaemon = true, name = "rpcsx-boot-drain") {
                runCatching { RPCSX.instance.kill() }
                threadToDrain.interrupt()
                runCatching { threadToDrain.join() }
            }
        }
    }


    private fun keyCodeToPadBit(keyCode: Int): Pair<Int, Int> =
        inputBindings[keyCode] ?: Pair(0, 0)

    private fun triggerSide(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L2 -> 0
        KeyEvent.KEYCODE_BUTTON_R2 -> 1
        else -> -1
    }

    /**
     * Fold both physical triggers into the pad state: digital bit of each trigger's bound PS3
     * button, plus the L2/R2 pressure values (-1 = "no analog value, use the digital bit").
     */
    private fun applyTriggers() {
        val clearMask = IntArray(2)
        val setMask = IntArray(2)
        var l2 = -1
        var r2 = -1

        for (side in 0..1) {
            val (bit, index) = keyCodeToPadBit(
                if (side == 0) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2
            )
            if (bit == 0 || index !in 0..1) continue
            val pressure = triggerArbiters[side].pressure
            clearMask[index] = clearMask[index] or bit
            if (pressure > 0) setMask[index] = setMask[index] or bit

            if (triggerConfig.enabled && index == 1) {
                when (bit) {
                    Digital2Flags.CELL_PAD_CTRL_L2.bit -> l2 = maxOf(l2, pressure)
                    Digital2Flags.CELL_PAD_CTRL_R2.bit -> r2 = maxOf(r2, pressure)
                }
            }
        }

        for (index in 0..1) {
            gamePadState.digital[index] =
                (gamePadState.digital[index] and clearMask[index].inv()) or setMask[index]
        }
        l2Pressure = l2
        r2Pressure = r2
    }

    private fun isGamepadKey(event: KeyEvent?): Boolean =
        event != null && (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) != 0

    private fun onTriggerKey(side: Int, down: Boolean, event: KeyEvent): Boolean {
        triggerArbiters[side].onKey(down, event.eventTime)
        applyTriggers()
        sendGamepadData()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isGamepadKey(event)) {
            return super.onKeyDown(keyCode, event)
        }
        val side = triggerSide(keyCode)
        if (side >= 0 && keyCodeToPadBit(keyCode).first != 0) {
            // Repeats are swallowed by the arbiter; consume them so they can't leak to the UI.
            return onTriggerKey(side, true, event!!)
        }
        if (event!!.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }
        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyDown(keyCode, event)
        }

        gamePadState.digital[padBit.second] = gamePadState.digital[padBit.second] or padBit.first
        sendGamepadData()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isGamepadKey(event)) {
            return super.onKeyUp(keyCode, event)
        }
        val side = triggerSide(keyCode)
        if (side >= 0 && keyCodeToPadBit(keyCode).first != 0) {
            return onTriggerKey(side, false, event!!)
        }

        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyUp(keyCode, event)
        }

        gamePadState.digital[padBit.second] =
            gamePadState.digital[padBit.second] and padBit.first.inv()
        sendGamepadData()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        // Pads report triggers as LTRIGGER/RTRIGGER, BRAKE/GAS, or both: take the larger.
        val leftRaw = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rightRaw = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        triggerArbiters[0].onAxis(triggerConfig.axisToPressure(leftRaw), event.eventTime)
        triggerArbiters[1].onAxis(triggerConfig.axisToPressure(rightRaw), event.eventTime)
        applyTriggers()

        val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        gamePadState.digital[0] =
            gamePadState.digital[0] and (Digital1Flags.CELL_PAD_CTRL_LEFT.bit or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit or Digital1Flags.CELL_PAD_CTRL_UP.bit or Digital1Flags.CELL_PAD_CTRL_DOWN.bit).inv()
        if (abs(dpadX) > 0.1f) {
            if (dpadX < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            }
        }

        if (abs(dpadY) > 0.1f) {
            if (dpadY < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_UP.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            }
        }

        gamePadState.leftStickX = (event.getAxisValue(MotionEvent.AXIS_X) * 127 + 128).toInt()
        gamePadState.leftStickY = (event.getAxisValue(MotionEvent.AXIS_Y) * 127 + 128).toInt()
        gamePadState.rightStickX = (event.getAxisValue(MotionEvent.AXIS_Z) * 127 + 128).toInt()
        gamePadState.rightStickY = (event.getAxisValue(MotionEvent.AXIS_RZ) * 127 + 128).toInt()

        sendGamepadData()
        return true
    }

    private fun sendGamepadData() {
        RPCSX.instance.overlayPadDataEx(
            gamePadState.digital[0],
            gamePadState.digital[1],
            gamePadState.leftStickX,
            gamePadState.leftStickY,
            gamePadState.rightStickX,
            gamePadState.rightStickY,
            l2Pressure,
            r2Pressure
        )
    }

    private fun enableFullScreenImmersive() {
        with(window) {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            // Keep the screen on while a game is up: a large title's first-launch PPU
            // precompile (e.g. The Sims 3: 152 modules / 990k blocks) can run for many
            // minutes with the game on screen; without this the display can sleep and
            // the user force-closes thinking it hung.
            addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyInsetsToPadOverlay()
    }

    private fun applyInsetsToPadOverlay() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.padOverlay) { view, windowInsets ->
            // I don't think we need `displayCutout` insets here as well
            // Since there is hardly any overlay overlapping with it
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
                topMargin = insets.top
                bottomMargin = insets.bottom
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreenImmersive()
    }
}
