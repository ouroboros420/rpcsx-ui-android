package net.rpcsx.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.channels.DevRpcsxChannel
import java.io.File
import kotlin.system.exitProcess


object RpcsxUpdater {
    // The core embeds build decorations after the CalVer date-time: " Draft"/" RC"
    // for untagged/pre-release builds (with an optional tag number) and a trailing
    // "+" when built from a dirty tree, e.g. "2026.06.06-1436 Draft+". Strip them so
    // an installed build compares equal to its clean release tag. The old code used
    // removeSuffix(" Draft"), which could not strip "Draft+" (the string ends in '+',
    // not " Draft"), so the updater kept re-offering the version already installed.
    private fun normalizeVersion(raw: String): String =
        raw.trim()
            .replace(Regex("\\s*(Draft|RC|Release)\\s*\\d*"), "")
            .replace("+", "")
            .trim()

    fun getCurrentVersion(): String? {
        if (RPCSX.activeLibrary.value == null) {
            return null
        }

        // Fall back to the device arch when the installed arch is unknown
        // (e.g. a side-loaded library whose filename doesn't encode it),
        // otherwise the version would read "...-null".
        val arch = GeneralSettings["rpcsx_installed_arch"] as? String ?: getArch()
        return "v" + normalizeVersion(RPCSX.instance.getVersion()) + "-" + arch
    }

    fun getFileArch(file: File): String? {
        val parts = file.name.removeSuffix(".so").split("_")
        if (parts.size != 3) {
            return null
        }

        return parts[1]
    }
    fun getFileVersion(file: File): String? {
        val parts = file.name.removeSuffix(".so").split("_")
        if (parts.size != 3) {
            return null
        }
        val arch = parts[1]
        val version = parts[2]
        return "$version-$arch"
    }

    fun getAbi(): String = Build.SUPPORTED_64_BIT_ABIS[0]

    fun getArch(): String {
        return when (getAbi()) {
            "x86_64" -> "x86-64"
            else -> GeneralSettings["rpcsx_arch"] as? String ?: "armv8-a"
        }
    }

    fun setArch(arch: String) {
        GeneralSettings["rpcsx_arch"] = arch
    }

    suspend fun checkForUpdate(): String? {
        // The user deliberately side-loaded a custom core; don't nag to replace it
        // with a release build (which is frequently older than the custom one).
        if (GeneralSettings["rpcsx_custom_library"] as? Boolean == true) {
            return null
        }

        val url = DevRpcsxChannel // TODO: update once RPCSX has release with android support

        val arch = getArch()
        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                // Normalize the tag the same way as the installed version so a clean
                // release tag and a "Draft+"-decorated local build compare equal.
                val releaseVersion = "v" + normalizeVersion(release.name.removePrefix("v")) + "-" + arch

                if (release.assets.find { it.name == "librpcsx-android-${getAbi()}-${arch}.so" }?.browser_download_url == null) {
                    return null
                }

                if (RPCSX.activeLibrary.value == null) {
                    return releaseVersion
                }

                if (getCurrentVersion() != releaseVersion && releaseVersion != GeneralSettings["rpcsx_bad_version"]) {
                    return releaseVersion
                }
            }
            is GitHub.FetchResult.Error -> {
//                AlertDialogQueue.showDialog("Check For RPCSX Updates Error", fetchResult.message)
            }
        }

        return null
    }

    suspend fun downloadUpdate(destinationDir: File, progressCallback: (Long, Long) -> Unit): File? {
        val url = DevRpcsxChannel // TODO: GeneralSettings["rpcsx_channel"] as String
        val arch = getArch()

        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                val releaseVersion = "v" + normalizeVersion(release.name.removePrefix("v")) + "-" + arch
                val releaseAsset = release.assets.find { it.name == "librpcsx-android-${getAbi()}-$arch.so" }

                if (releaseVersion != getCurrentVersion() && releaseAsset?.browser_download_url != null) {
                    val target = File(destinationDir, "librpcsx-android_${arch}_${release.name}.so")

                    if (target.exists()) {
                        return target
                    }

                    val tmp = File(destinationDir, "librpcsx.so.tmp")
                    if (tmp.exists()) {
                        withContext(Dispatchers.IO) {
                            tmp.delete()
                        }
                    }

                    withContext(Dispatchers.IO) {
                        tmp.createNewFile()
                    }

                    tmp.deleteOnExit()

                    when (val downloadStatus = GitHub.downloadAsset(releaseAsset.browser_download_url, tmp, progressCallback)) {
                        is GitHub.DownloadStatus.Success -> {
                            withContext(Dispatchers.IO) {
                                tmp.renameTo(target)
                            }
                            return target
                        }
                        is GitHub.DownloadStatus.Error ->
                            AlertDialogQueue.showDialog("RPCSX Download Error", downloadStatus.message ?: "Unexpected error")
                    }
                }
            }
            is GitHub.FetchResult.Error -> {
                AlertDialogQueue.showDialog("RPCSX Download Error", fetchResult.message)
            }
        }

        return null
    }

    fun installUpdate(context: Context, updateFile: File, isCustom: Boolean = false): Boolean {
        // Remember whether this core was hand-installed by the user (side-loaded) vs
        // pulled from the release channel, so the updater doesn't keep offering to
        // "update" (downgrade) a deliberately side-loaded custom build.
        GeneralSettings["rpcsx_custom_library"] = isCustom

        val restart = {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            mainIntent.setPackage(context.packageName)
            context.startActivity(mainIntent)
            GeneralSettings.sync()
            exitProcess(0)
        }

        val prevLibrary = GeneralSettings["rpcsx_library"] as? String
        val prevArch = GeneralSettings["rpcsx_installed_arch"] as? String
        GeneralSettings["rpcsx_library"] = updateFile.toString()
        GeneralSettings["rpcsx_update_status"] = null
        GeneralSettings["rpcsx_installed_arch"] = getFileArch(updateFile) ?: getArch()

        Log.e("RPCSX-UI", "registered update file ${GeneralSettings["rpcsx_library"]}")

        if (prevLibrary == null) {
            restart()
        }

        GeneralSettings["rpcsx_prev_library"] = prevLibrary
        GeneralSettings["rpcsx_prev_installed_arch"] = prevArch
        AlertDialogQueue.showDialog(
            title = context.getString(R.string.rpcsx_update_available),
            message = context.getString(R.string.restart_ui_to_apply_change),
            onConfirm = { restart() }
        )
        return true
    }
}
