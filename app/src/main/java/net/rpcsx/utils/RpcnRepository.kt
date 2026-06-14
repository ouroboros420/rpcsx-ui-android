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
 * A per-game online server switch: redirects a game's dead first-party server
 * hostnames to a community replacement via the core's DNS hook (Net > IP swap
 * list). Distinct from the RPCN account/host list above - this is per-GAME DNS.
 */
data class GameServerSwitch(
    val name: String,
    /** Net "IP swap list" value: "host1=ip&&host2=ip&&...". */
    val swapList: String,
    /** Optional DNS override; blank = leave the game's current DNS untouched. */
    val dns: String = "",
)

/** Outcome of applying a per-game server switch. */
sealed class GameServerApplyResult {
    object Applied : GameServerApplyResult()
    data class Error(val message: String) : GameServerApplyResult()
}

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

    // Per-game DNS server-switch registry (distinct from the RPCN host list).
    private const val GAME_SERVERS_ASSET = "game_servers.json"

    // Updatable registry, fetched with the same raw.githubusercontent pattern as
    // the rest of the fork's remote data. If this fails we fall back to the
    // bundled asset, so the constant being unreachable is never fatal.
    private const val REMOTE_REGISTRY_URL =
        "https://raw.githubusercontent.com/Ouroboros420/rpcsx-ui-android/master/app/src/main/assets/rpcn_servers.json"

    private const val REMOTE_GAME_SERVERS_URL =
        "https://raw.githubusercontent.com/Ouroboros420/rpcsx-ui-android/master/app/src/main/assets/game_servers.json"

    // Core config node paths ("@@"-separated). Verified against the core's cfg
    // names in Emu/system_config.h (node "Net"): swap list "IP swap list",
    // internet enable enum "Internet enabled", DNS "DNS address".
    private const val PATH_SWAP_LIST = "Net@@IP swap list"
    private const val PATH_INTERNET = "Net@@Internet enabled"
    private const val PATH_DNS = "Net@@DNS address"

    // The "Internet enabled" cfg is an np_internet_status enum that serializes
    // via fmt_class_string to "Disconnected"/"Connected" (NOT "Enabled"). The
    // enabling value is therefore "Connected" (verified in
    // Emu/system_config_types.cpp). customConfigSet takes a JSON-encoded value,
    // so string/enum values are passed JSON-quoted.
    private const val INTERNET_ENABLED_VALUE = "Connected"

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

    // ---- per-game online server switch (DNS IP-swap) ----------------------

    /**
     * The community server switch known for a game, or null if none. Tries the
     * remote registry first (source of truth, always current), then silently
     * falls back to the bundled asset on any failure. Never throws.
     */
    suspend fun gameServerFor(context: Context, serial: String): GameServerSwitch? =
        withContext(Dispatchers.IO) {
            if (serial.isBlank()) return@withContext null
            val remote = runCatching { fetchRemote(REMOTE_GAME_SERVERS_URL) }.getOrNull()
            val json = remote ?: runCatching { readBundled(context, GAME_SERVERS_ASSET) }.getOrElse {
                Log.e(TAG, "bundled game-server registry unreadable", it)
                return@withContext null
            }
            runCatching { parseGameServer(json, serial) }.getOrElse {
                Log.e(TAG, "game-server registry parse failed", it)
                null
            }
        }

    private fun parseGameServer(json: String, serial: String): GameServerSwitch? {
        val games = JSONObject(json).optJSONObject("games") ?: return null
        // Serial match is case-insensitive: scan keys rather than relying on case.
        val key = games.keys().asSequence().firstOrNull { it.equals(serial, ignoreCase = true) }
            ?: return null
        val o = games.optJSONObject(key) ?: return null
        val swap = o.optString("swap_list")
        if (swap.isBlank()) return null
        return GameServerSwitch(
            name = o.optString("name").ifBlank { "Community server" },
            swapList = swap,
            dns = o.optString("dns"),
        )
    }

    /**
     * The easy button: apply a community server switch to a game's custom config.
     * Ensures the per-game config exists, writes the IP swap list + (optional)
     * DNS, and enables internet. All native calls are off the main thread and
     * wrapped; never throws. The game must be RESTARTED for the DNS hook to read
     * the new swap list at boot.
     */
    suspend fun applyGameServer(serial: String, switch: GameServerSwitch): GameServerApplyResult =
        withContext(Dispatchers.IO) {
            if (serial.isBlank()) {
                return@withContext GameServerApplyResult.Error("No game selected")
            }
            runCatching {
                // 1. Ensure the per-game custom config exists.
                if (!PerGameConfigRepository.hasCustomConfig(serial)) {
                    if (!PerGameConfigRepository.createCustomConfig(serial)) {
                        return@runCatching GameServerApplyResult.Error("Could not create game config")
                    }
                }
                // 2. IP swap list (string node -> JSON-quoted value).
                if (!PerGameConfigRepository.set(serial, PATH_SWAP_LIST, jsonStr(switch.swapList))) {
                    return@runCatching GameServerApplyResult.Error("Could not set server list")
                }
                // 3. Optional DNS override (only if the registry specifies one).
                if (switch.dns.isNotBlank()) {
                    PerGameConfigRepository.set(serial, PATH_DNS, jsonStr(switch.dns))
                }
                // 4. Enable internet (enum node -> "Connected").
                PerGameConfigRepository.set(serial, PATH_INTERNET, jsonStr(INTERNET_ENABLED_VALUE))
                GameServerApplyResult.Applied
            }.getOrElse {
                Log.e(TAG, "applyGameServer failed", it)
                GameServerApplyResult.Error(it.message ?: "Apply failed")
            }
        }

    /**
     * Advanced: write a raw IP swap list directly (manual entry) and enable
     * internet. Same paths/encoding as applyGameServer. Never throws.
     */
    suspend fun applyManualSwapList(serial: String, swapList: String): GameServerApplyResult =
        applyGameServer(serial, GameServerSwitch(name = "Manual", swapList = swapList, dns = ""))

    /** Current IP swap list for a game (empty if none / unreadable). */
    suspend fun currentSwapList(serial: String): String = withContext(Dispatchers.IO) {
        if (serial.isBlank()) return@withContext ""
        runCatching {
            val node = PerGameConfigRepository.get(serial, PATH_SWAP_LIST) ?: return@runCatching ""
            node.optString("value")
        }.getOrElse { "" }
    }

    /** JSON-encode a scalar string for customConfigSet (which json::parses it). */
    private fun jsonStr(value: String): String = JSONObject.quote(value)

    // Generic remote/bundled readers reused by both registries.
    private fun fetchRemote(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "rpcsx").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty().ifBlank { throw java.io.IOException("empty body") }
        }
    }

    private fun readBundled(context: Context, asset: String): String =
        context.assets.open(asset).bufferedReader().use { it.readText() }
}
