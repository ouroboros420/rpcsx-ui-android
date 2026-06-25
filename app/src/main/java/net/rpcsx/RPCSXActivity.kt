package net.rpcsx

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
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
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.InputBindingPrefs
import kotlin.concurrent.thread
import kotlin.math.abs

class RPCSXActivity : ComponentActivity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private var gamePadState: State = State()
    private var usesAxisL2 = false
    private var usesAxisR2 = false
    private var bootThread: Thread? = null
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }

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

        val gamePath = intent.getStringExtra("path")!!
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

            val bootResult = RPCSX.boot(gamePath)
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


    private fun keyCodeToPadBit(keyCode: Int): Pair<Int, Int> {
        val event = inputBindings[keyCode] ?: Pair(0, 0)
        
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (usesAxisR2) return Pair(0, 0) else return event
        }
        
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            if (usesAxisL2) return Pair(0, 0) else return event
        }
        
        return event
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) == 0 || event.repeatCount != 0) {
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
        if (event == null || event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) == 0) {
            return super.onKeyUp(keyCode, event)
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

        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.1) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_L2.bit
            usesAxisL2 = true
        } else if (usesAxisL2) {
            usesAxisL2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        }

        if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.1) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_R2.bit
            usesAxisR2 = true
        } else if (usesAxisR2) {
            usesAxisR2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        }

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
        RPCSX.instance.overlayPadData(
            gamePadState.digital[0],
            gamePadState.digital[1],
            gamePadState.leftStickX,
            gamePadState.leftStickY,
            gamePadState.rightStickX,
            gamePadState.rightStickY
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
