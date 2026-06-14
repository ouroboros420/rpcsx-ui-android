package net.rpcsx.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Current RPCN config as the core reports it (rpcnGetConfig JSON). */
data class RpcnConfig(
    val host: String = "",
    val npid: String = "",
    val password: String = "",
    val token: String = "",
)

/** One server entry in the host list (rpcnGetHosts). */
data class RpcnHost(
    val description: String,
    val host: String,
)

/** A community-suggested server for a specific game (bundled/remote registry). */
data class RpcnSuggestedServer(
    val name: String,
    val host: String,
)

/**
 * App-side glue for RPCN (community-PSN online play). Every native call here may
 * block on the network and is therefore wrapped so callers can run it on
 * Dispatchers.IO; every call is also wrapped in runCatching so a thrown native
 * exception (e.g. the symbol not yet present in the core) degrades to a safe
 * default instead of crashing the UI.
 *
 * It also owns the per-game custom-server registry: a bundled JSON asset that
 * always works offline, plus an optional remote copy fetched with the same
 * networking pattern as the patch / community-config download. A remote fetch
 * failure silently falls back to the bundled asset (no error spam).
 */
object RpcnRepository {
    private const val TAG = "RpcnRepository"

    /** The default official RPCN server, used when the core reports no active host. */
    const val OFFICIAL_HOST = "np.rpcs3.net"
    const val OFFICIAL_DESCRIPTION = "RPCN Official"

    private const val ASSET_NAME = "rpcn_servers.json"

    // Updatable registry, fetched with the same raw.githubusercontent pattern as
    // the rest of the fork's remote data. If this fails we fall back to the
    // bundled asset, so the constant being unreachable is never fatal.
    private const val REMOTE_REGISTRY_URL =
        "https://raw.githubusercontent.com/Ouroboros420/rpcsx-ui-android/master/app/src/main/assets/rpcn_servers.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ---- native config / credentials -------------------------------------

    suspend fun getConfig(): RpcnConfig = withContext(Dispatchers.IO) {
        runCatching {
            val raw = RPCSX.instance.rpcnGetConfig()
            if (raw.isBlank()) return@runCatching RpcnConfig()
            val o = JSONObject(raw)
            RpcnConfig(
                host = o.optString("host"),
                npid = o.optString("npid"),
                password = o.optString("password"),
                token = o.optString("token"),
            )
        }.getOrElse {
            Log.e(TAG, "rpcnGetConfig failed", it)
            RpcnConfig()
        }
    }

    suspend fun setCredentials(npid: String, password: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { RPCSX.instance.rpcnSetCredentials(npid, password, token); true }
                .getOrElse { Log.e(TAG, "rpcnSetCredentials failed", it); false }
        }

    suspend fun createAccount(
        npid: String, password: String, onlineName: String, email: String, country: String
    ): String = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnCreateAccount(npid, password, onlineName, email, country) }
            .getOrElse { Log.e(TAG, "rpcnCreateAccount failed", it); it.message ?: "Account creation failed" }
    }

    suspend fun resendToken(): String = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnResendToken() }
            .getOrElse { Log.e(TAG, "rpcnResendToken failed", it); it.message ?: "Could not resend token" }
    }

    /** "" means connected + authenticated; anything else is a human-readable error. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnTestConnection() }
            .getOrElse { Log.e(TAG, "rpcnTestConnection failed", it); it.message ?: "Connection test failed" }
    }

    suspend fun setEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnSetEnabled(enabled); true }
            .getOrElse { Log.e(TAG, "rpcnSetEnabled failed", it); false }
    }

    suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnIsEnabled() }
            .getOrElse { Log.e(TAG, "rpcnIsEnabled failed", it); false }
    }

    // ---- native host list -------------------------------------------------

    suspend fun getHosts(): List<RpcnHost> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = RPCSX.instance.rpcnGetHosts()
            parseHosts(raw)
        }.getOrElse {
            Log.e(TAG, "rpcnGetHosts failed", it)
            // Always surface at least the official server so the list is never empty.
            listOf(RpcnHost(OFFICIAL_DESCRIPTION, OFFICIAL_HOST))
        }.ifEmpty { listOf(RpcnHost(OFFICIAL_DESCRIPTION, OFFICIAL_HOST)) }
    }

    private fun parseHosts(raw: String): List<RpcnHost> {
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<RpcnHost>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host")
            if (host.isBlank()) continue
            out.add(RpcnHost(o.optString("description").ifBlank { host }, host))
        }
        return out
    }

    suspend fun addHost(description: String, host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnAddHost(description, host) }
            .getOrElse { Log.e(TAG, "rpcnAddHost failed", it); false }
    }

    suspend fun removeHost(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnRemoveHost(host) }
            .getOrElse { Log.e(TAG, "rpcnRemoveHost failed", it); false }
    }

    suspend fun getActiveHost(): String = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnGetActiveHost() }
            .getOrElse { Log.e(TAG, "rpcnGetActiveHost failed", it); "" }
    }

    suspend fun setActiveHost(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { RPCSX.instance.rpcnSetActiveHost(host); true }
            .getOrElse { Log.e(TAG, "rpcnSetActiveHost failed", it); false }
    }

    /** The official server is never removable. */
    fun isRemovable(host: String): Boolean = !host.equals(OFFICIAL_HOST, ignoreCase = true)

    // ---- per-game suggested-server registry -------------------------------

    /**
     * Servers suggested for a game. Tries the remote registry first (best,
     * always up to date), then silently falls back to the bundled asset on any
     * failure. Never throws; returns an empty list when nothing matches so the
     * UI can simply show nothing.
     */
    suspend fun suggestedServersFor(context: Context, serial: String): List<RpcnSuggestedServer> =
        withContext(Dispatchers.IO) {
            if (serial.isBlank()) return@withContext emptyList()
            val remote = runCatching { fetchRemoteRegistry() }.getOrNull()
            val json = remote ?: runCatching { readBundledRegistry(context) }.getOrElse {
                Log.e(TAG, "bundled registry unreadable", it)
                return@withContext emptyList()
            }
            runCatching { parseRegistryFor(json, serial) }.getOrElse {
                Log.e(TAG, "registry parse failed", it)
                emptyList()
            }
        }

    private fun fetchRemoteRegistry(): String {
        val request = Request.Builder().url(REMOTE_REGISTRY_URL)
            .header("User-Agent", "rpcsx").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty().ifBlank { throw java.io.IOException("empty body") }
        }
    }

    private fun readBundledRegistry(context: Context): String =
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }

    private fun parseRegistryFor(json: String, serial: String): List<RpcnSuggestedServer> {
        val servers = JSONObject(json).optJSONObject("servers") ?: return emptyList()
        // Serial match is case-insensitive: scan keys rather than relying on exact case.
        val key = servers.keys().asSequence().firstOrNull { it.equals(serial, ignoreCase = true) }
            ?: return emptyList()
        val arr = servers.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<RpcnSuggestedServer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host")
            if (host.isBlank()) continue
            out.add(RpcnSuggestedServer(o.optString("name").ifBlank { host }, host))
        }
        return out
    }
}
