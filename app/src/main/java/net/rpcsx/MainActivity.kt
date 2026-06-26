package net.rpcsx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.navigation.AppNavHost
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GitHub
import net.rpcsx.utils.RpcsxUpdater
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var unregisterUsbEventListener: () -> Unit
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GeneralSettings.init(this)

        // If the previous session was killed (OOM/SIGKILL/native crash), surface
        // the real reason - the core log cannot capture an uncatchable kill.
        net.rpcsx.utils.ExitReasonReporter.reportLastAbnormalExit(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!RPCSX.initialized) {
            Permission.PostNotifications.requestPermission(this)

            with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
                val channel = NotificationChannel(
                    "rpcsx-progress",
                    getString(R.string.installation_progress),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                createNotificationChannel(channel)
            }

            RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null).toString()
            if (!RPCSX.rootDirectory.endsWith("/")) {
                RPCSX.rootDirectory += "/"
            }

            // The native overlay code (save/message/OSK dialogs) loads PS-button
            // glyphs from <config>/Icons/ui/*.png. They ship as APK assets; extract
            // them on first run so dialogs render with proper button icons.
            val iconsDir = File(RPCSX.rootDirectory + "config", "Icons")
            if (!File(iconsDir, "ui").exists()) {
                FileUtil.extractAssetDir(applicationContext, "Icons", iconsDir)
            }

            lifecycleScope.launch {
                GameRepository.load()
            }

            FirmwareRepository.load()
            GitHub.initialize(this)

            var rpcsxLibrary = GeneralSettings["rpcsx_library"] as? String
            val rpcsxUpdateStatus = GeneralSettings["rpcsx_update_status"]
            val rpcsxPrevLibrary = GeneralSettings["rpcsx_prev_library"] as? String

            // Crash-loop breaker: openLibrary() runs the core .so's native static init, which can
            // hard-crash (e.g. a wrong-arch / incompatible core) - a native crash Kotlin cannot
            // catch. Guard it with a persistent flag set right before the load and cleared right
            // after. If a previous launch set the flag and never cleared it, the core crashed
            // mid-load: unset it so the app still starts (no data loss) and the user can import a
            // working core, instead of being stuck in a boot crash loop.
            if (rpcsxLibrary != null && GeneralSettings["rpcsx_core_loading"] == true) {
                GeneralSettings["rpcsx_core_loading"] = false
                GeneralSettings["rpcsx_library"] = null
                GeneralSettings["rpcsx_prev_library"] = null
                GeneralSettings.sync()
                rpcsxLibrary = null
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_update_rpcsx),
                    "The selected core failed to load and has been unset. Import a working core to continue."
                )
            }

            if (rpcsxLibrary != null) {
                if (rpcsxUpdateStatus == false && rpcsxPrevLibrary != null) {
                    GeneralSettings["rpcsx_library"] = rpcsxPrevLibrary
                    GeneralSettings["rpcsx_installed_arch"] = GeneralSettings["rpcsx_prev_installed_arch"]
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_bad_version"] = RpcsxUpdater.getFileVersion(File(rpcsxLibrary))
                    GeneralSettings.sync()

                    File(rpcsxLibrary).delete()
                    rpcsxLibrary = rpcsxPrevLibrary

                    AlertDialogQueue.showDialog(
                        getString(R.string.failed_to_update_rpcsx),
                        getString(R.string.failed_to_load_new_version)
                    )
                } else if (rpcsxUpdateStatus == null) {
                    GeneralSettings["rpcsx_update_status"] = false
                    GeneralSettings.sync()
                }

                // Mark "loading" before the native load; cleared only if the load returns
                // (no crash). A still-set flag on next launch trips the breaker above. These MUST
                // be synchronous commits so the flag is durably on disk before openLibrary can
                // hard-crash the process (apply()/sync() do not guarantee a flush before the crash).
                GeneralSettings.setValueSync("rpcsx_core_loading", true)
                RPCSX.openLibrary(rpcsxLibrary)
                GeneralSettings.setValueSync("rpcsx_core_loading", false)
            }

            val nativeLibraryDir =
                packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibraryDir

            if (RPCSX.activeLibrary.value != null) {
                // Hand the core the app-private dir for secrets (rpcn.yml) BEFORE
                // initialize, so the very first RPCN config load resolves to internal
                // storage. No-ops gracefully on older core .so builds.
                RPCSX.instance.setRpcnConfigDir(applicationContext.filesDir.absolutePath)
                RPCSX.instance.initialize(
                    RPCSX.rootDirectory,
                    UserRepository.getUserFromSettings()
                )
                // Apply the device-adaptive compile-thread cap before any game can
                // boot, so low-RAM devices don't OOM during first-boot compilation.
                net.rpcsx.utils.CompileThreadPolicy.apply(applicationContext)
                // Android battery-saver (FIFO present + low SPU busy-wait); default on.
                net.rpcsx.utils.PowerPolicy.apply()
                // GPU turbo (max Adreno clocks); opt-in, default off, mutually exclusive with
                // battery-saver.
                net.rpcsx.utils.GpuTurbo.apply()
                // Experimental: bias heavy threads to the big CPU cluster (default off).
                runCatching {
                    RPCSX.instance.setCpuAffinityMode(GeneralSettings["cpu_affinity"] as? Boolean ?: false)
                }
                // Experimental: low-power WFE waiting (default off).
                runCatching {
                    RPCSX.instance.setWfeMode(GeneralSettings["wfe_mode"] as? Boolean ?: false)
                }
                // Smooth shaders: the async SPIR-V interpreter is currently DISABLED in the core
                // (it destabilised the Vulkan backend - shared_mutex underflow at boot; see
                // VKGSRender), so this flag is inert. Kept wired for a future stable re-land;
                // shaders already compile asynchronously (async_recompiler) by default. Off.
                runCatching {
                    RPCSX.instance.setSmoothShaders(GeneralSettings["smooth_shaders"] as? Boolean ?: false)
                }
                val gpuDriverPath = GeneralSettings["gpu_driver_path"] as? String
                val gpuDriverName = GeneralSettings["gpu_driver_name"] as? String

                if (gpuDriverPath != null && gpuDriverName != null) {
                    RPCSX.instance.setCustomDriver(gpuDriverPath, gpuDriverName, nativeLibraryDir)
                }

                lifecycleScope.launch {
                    UserRepository.load()
                }

                // Auto-connect RPCN on startup if the user left it enabled, so the live status
                // reflects the session without re-opening settings. Off-thread, best-effort; the
                // core's persistent client ref holds the connection once testConnection lands.
                lifecycleScope.launch {
                    runCatching {
                        if (net.rpcsx.utils.RpcnRepository.isEnabled()) {
                            net.rpcsx.utils.RpcnRepository.testConnection()
                        }
                    }
                }

                RPCSX.initialized = true

                thread {
                    RPCSX.instance.startMainThreadProcessor()
                }

                thread {
                    RPCSX.instance.processCompilationQueue()
                }

                GeneralSettings["rpcsx_update_status"] = true
                if (rpcsxPrevLibrary != null) {
                    if (rpcsxLibrary != rpcsxPrevLibrary) {
                        File(rpcsxPrevLibrary).delete()
                    }

                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings.sync()
                }
            }

            val updateFile = File(RPCSX.rootDirectory + "cache", "rpcsx-${BuildConfig.Version}.apk")
            if (updateFile.exists()) {
                updateFile.delete()
            }
        }

        setContent {
            RPCSXTheme {
                AppNavHost()
            }
        }

        if (RPCSX.activeLibrary.value != null) {
            unregisterUsbEventListener = listenUsbEvents(this)
        } else {
            unregisterUsbEventListener = {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
    }
}
