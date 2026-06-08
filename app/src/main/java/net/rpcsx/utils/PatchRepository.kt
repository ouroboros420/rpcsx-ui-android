package net.rpcsx.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.rpcsx.RPCSX
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** One patch as reported by the core (RPCSX.patchesList JSON). */
@Serializable
data class Patch(
    val hash: String = "",
    val name: String = "",
    val author: String = "",
    val version: String = "",
    val notes: String = "",
    val serials: List<String> = emptyList(),
    /** Game titles this patch targets (e.g. "LittleBigPlanet 2"), from the patch yml. */
    val titles: List<String> = emptyList(),
    val enabled: Boolean = false,
)

sealed class PatchDownloadResult {
    /** updated=false means the local patch set was already up to date. */
    data class Success(val updated: Boolean) : PatchDownloadResult()
    data class Error(val message: String) : PatchDownloadResult()
}

/**
 * App-side glue for the core patch engine. Listing/toggling go through the core
 * (which owns the YAML parsing + patch_config.yml); downloading/importing just
 * place the right files into the core's patches directory.
 */
object PatchRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    // Mirrors the core's patch_engine::get_patches_path() (= <config>/patches/),
    // since g_android_config_dir = rootDirectory + "config/".
    fun patchesDir(): File = File(RPCSX.rootDirectory + "config/patches/")

    fun list(): List<Patch> {
        val raw = runCatching { RPCSX.instance.patchesList() }.getOrElse {
            android.util.Log.e("PatchRepository", "patchesList() JNI call failed", it)
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<Patch>>(raw)
        }.getOrElse {
            // Distinguish "core returned nothing" from "we couldn't parse it" - the
            // raw length + head make it obvious in logcat which one happened.
            android.util.Log.e(
                "PatchRepository",
                "failed to parse patch list (len=${raw.length}): ${raw.take(200)}", it
            )
            emptyList()
        }
    }

    /** Patches that apply to a game: ones targeting its serial, plus universal ones. */
    fun listForSerial(serial: String): List<Patch> =
        list().filter { it.serials.isEmpty() || it.serials.contains(serial) }

    fun setEnabled(patch: Patch, enabled: Boolean): Boolean = runCatching {
        RPCSX.instance.patchSetEnabled(patch.hash, patch.name, enabled)
    }.getOrDefault(false)

    /**
     * Download the official RPCS3 patch set. The API answers with JSON
     * {return_code, version, sha256, patch}; we extract `patch` (the YAML) and
     * write it to patches/patch.yml, which the engine loads on the next boot.
     */
    fun downloadOfficial(): PatchDownloadResult {
        return try {
            val version = RPCSX.instance.patchEngineVersion().ifEmpty { "1.2" }
            val url = "https://rpcs3.net/compatibility?patch&api=v1&v=$version"
            val request = Request.Builder().url(url).header("User-Agent", "rpcsx").build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return PatchDownloadResult.Error("HTTP ${resp.code}")

                val obj = JSONObject(resp.body?.string().orEmpty())
                when (val rc = obj.optInt("return_code", -255)) {
                    0 -> Unit // new patches available
                    1 -> return PatchDownloadResult.Success(updated = false)
                    -1 -> return PatchDownloadResult.Error("No patches found for version $version")
                    else -> return PatchDownloadResult.Error("Server error (code $rc)")
                }

                val content = obj.optString("patch")
                if (content.isEmpty()) return PatchDownloadResult.Error("Empty patch content")

                patchesDir().mkdirs()
                File(patchesDir(), "patch.yml").writeText(content)
                PatchDownloadResult.Success(updated = true)
            }
        } catch (e: Throwable) {
            PatchDownloadResult.Error(e.message ?: "Download failed")
        }
    }

    /** Import a user-picked patch file; the engine loads imported_patch.yml too. */
    fun importLocal(content: String): Boolean = runCatching {
        patchesDir().mkdirs()
        File(patchesDir(), "imported_patch.yml").writeText(content)
        true
    }.getOrDefault(false)
}
