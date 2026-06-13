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

/**
 * A patch as the user sees it: a single row even when the core registers it
 * under several game-version hashes (same name/author/version/notes). Toggling
 * flips every underlying hash, so whichever one matches the installed game
 * actually takes effect - and the list shows it only once instead of N times.
 */
data class PatchGroup(
    val name: String,
    val author: String,
    val version: String,
    val notes: String,
    val serials: List<String>,
    val titles: List<String>,
    val hashes: List<String>,
    val enabled: Boolean,
    /**
     * Stable identity unique across all groups: the exact tuple group() buckets
     * by. Neither the name nor the hash list is unique on its own (one program
     * hash hosts many patches, and a generic patch name repeats across games), so
     * this is the only safe LazyColumn key - a duplicate key force-closes the app.
     */
    val id: String,
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

    // Cache the parsed list: patchesList() is a JNI call that re-parses the
    // whole (~hundreds of KB) patch set every time, and listForSerial calls it
    // per per-game screen open. Invalidated when patches/enabled-state change.
    @Volatile
    private var cached: List<Patch>? = null

    fun invalidate() { cached = null }

    // Mirrors the core's patch_engine::get_patches_path() (= <config>/patches/),
    // since g_android_config_dir = rootDirectory + "config/".
    fun patchesDir(): File = File(RPCSX.rootDirectory + "config/patches/")

    fun list(): List<Patch> {
        cached?.let { return it }
        val raw = runCatching { RPCSX.instance.patchesList() }.getOrElse {
            android.util.Log.e("PatchRepository", "patchesList() JNI call failed", it)
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<Patch>>(raw)
        }.onSuccess { cached = it }.getOrElse {
            // Distinguish "core returned nothing" from "we couldn't parse it" - the
            // raw length + head make it obvious in logcat which one happened.
            // (Don't cache a parse failure - it may be transient.)
            android.util.Log.e(
                "PatchRepository",
                "failed to parse patch list (len=${raw.length}): ${raw.take(200)}", it
            )
            emptyList()
        }
    }

    /**
     * Patches that apply to a specific game. A patch matches when the game's
     * serial is in its serial list, or when it targets "ALL" serials AND this
     * game's title - "ALL" in the yml means every region of THAT game, not every
     * game, so without the title check it would leak patches from unrelated
     * titles (e.g. LittleBigPlanet patches showing under Demon's Souls).
     */
    fun listForSerial(serial: String, title: String? = null): List<Patch> =
        list().filter { p ->
            p.serials.any { it.equals(serial, ignoreCase = true) } ||
                (title != null &&
                    p.serials.any { it.equals("ALL", ignoreCase = true) } &&
                    p.titles.any { it.equals(title, ignoreCase = true) })
        }

    /**
     * Collapse the rows that are the SAME patch into one. The core lists a patch
     * once per program hash it targets, so a single patch shows up N times (once
     * per game version) with identical metadata. We merge only rows that share
     * name/author/version/notes AND the same target serials+titles, so multiple
     * hashes of one patch collapse to a single row while a generically-named
     * patch (e.g. "60 FPS") for a DIFFERENT game stays separate - merging across
     * games was what made toggling one row flip many unrelated patches.
     */
    /**
     * The group identity a single patch belongs to - the exact tuple group()
     * buckets by, joined with a control-free separator. Lets callers map an
     * individual Patch to its PatchGroup.id WITHOUT matching on hash (one program
     * hash hosts many patches, so a hash match flips unrelated rows).
     */
    fun groupIdOf(p: Patch): String =
        listOf(p.name, p.author, p.version, p.notes,
               p.serials.sorted(), p.titles.sorted()).joinToString("«|»")

    fun group(patches: List<Patch>): List<PatchGroup> =
        patches.groupBy { groupIdOf(it) }
            .map { (id, ps) ->
                val f = ps.first()
                PatchGroup(
                    name = f.name,
                    author = f.author,
                    version = f.version,
                    notes = f.notes,
                    serials = ps.flatMap { it.serials }.distinct(),
                    titles = ps.flatMap { it.titles }.distinct(),
                    hashes = ps.map { it.hash }.distinct(),
                    enabled = ps.any { it.enabled },
                    id = id,
                )
            }

    fun setEnabled(patch: Patch, enabled: Boolean): Boolean = runCatching {
        RPCSX.instance.patchSetEnabled(patch.hash, patch.name, enabled)
    }.getOrDefault(false).also { invalidate() }

    /** Flip every hash behind a grouped patch (attempts all; true if all succeed). */
    fun setEnabled(group: PatchGroup, enabled: Boolean): Boolean =
        group.hashes.map { hash ->
            runCatching { RPCSX.instance.patchSetEnabled(hash, group.name, enabled) }
                .getOrDefault(false)
        }.all { it }.also { invalidate() }

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
                invalidate()
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
        invalidate()
        true
    }.getOrDefault(false)
}
