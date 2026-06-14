package net.rpcsx.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.R
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.settings.components.core.PreferenceHeader
import net.rpcsx.ui.settings.components.preference.SingleSelectionDialog
import net.rpcsx.ui.settings.components.preference.SliderPreference
import net.rpcsx.ui.settings.components.preference.SwitchPreference
import net.rpcsx.utils.CommunityConfigFetch
import net.rpcsx.utils.GameServerApplyResult
import net.rpcsx.utils.GameServerSwitch
import net.rpcsx.utils.Patch
import net.rpcsx.utils.PatchRepository
import net.rpcsx.utils.PerGameConfigRepository
import net.rpcsx.utils.RpcnRepository
import org.json.JSONObject

private sealed class ConfigEntry {
    data class Header(val title: String) : ConfigEntry()
    data class Leaf(val path: String, val label: String, val obj: JSONObject) : ConfigEntry()
}

private data class LoadedConfig(val hasCustom: Boolean, val entries: List<ConfigEntry>)

/** True when this leaf's current value overrides (differs from) its default. */
private fun ConfigEntry.isChangedLeaf(): Boolean {
    if (this !is ConfigEntry.Leaf) return false
    if (!obj.has("value") || !obj.has("default")) return false
    return obj.optString("value") != obj.optString("default")
}

private fun List<ConfigEntry>.changedCount(): Int = count { it.isChangedLeaf() }

private fun flattenConfig(
    node: JSONObject,
    pathPrefix: String,
    displayPrefix: String,
    out: MutableList<ConfigEntry>
) {
    val keys = node.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val child = node.optJSONObject(key) ?: continue
        val path = if (pathPrefix.isEmpty()) key else "$pathPrefix@@$key"
        if (child.has("type")) {
            out.add(ConfigEntry.Leaf(path, key, child))
        } else {
            val title = if (displayPrefix.isEmpty()) key else "$displayPrefix / $key"
            out.add(ConfigEntry.Header(title))
            flattenConfig(child, path, title, out)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerGameConfigScreen(serial: String, gameName: String, navigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reloadKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    // Load off the composition thread: get() does a JNI call that builds the whole
    // cfg tree and returns a large JSON, which would jank/freeze the screen.
    var loaded by remember { mutableStateOf<LoadedConfig?>(null) }
    LaunchedEffect(serial, reloadKey) {
        loaded = withContext(Dispatchers.IO) {
            val has = PerGameConfigRepository.hasCustomConfig(serial)
            val root = PerGameConfigRepository.get(serial)
            val list = if (root == null) emptyList() else buildList { flattenConfig(root, "", "", this) }
            LoadedConfig(has, list)
        }
    }
    val configLoading = loaded == null
    val hasCustom = loaded?.hasCustom ?: false
    val entries = loaded?.entries ?: emptyList()
    val totalChanged = remember(entries) { entries.changedCount() }

    // Group the flat config into top-level sections (Core, Video, Audio, ...).
    // A new section starts at each top-level header (one with no " / "); its
    // sub-headers and leaves become the section body.
    val settingsSections = remember(entries) {
        val sections = mutableListOf<Pair<String, MutableList<ConfigEntry>>>()
        for (e in entries) {
            if (e is ConfigEntry.Header && !e.title.contains(" / ")) {
                sections.add(e.title to mutableListOf())
            } else {
                if (sections.isEmpty()) sections.add("Settings" to mutableListOf())
                sections.last().second.add(e)
            }
        }
        sections.map { it.first to it.second.toList() }
    }

    // Sections collapse by default (absent == collapsed) so the whole screen is
    // scannable at a glance; the user expands only what they need.
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    fun reload() { reloadKey++ }

    // Patches that apply to this game, loaded off the composition thread.
    var patches by remember { mutableStateOf<List<Patch>>(emptyList()) }
    var patchesLoading by remember { mutableStateOf(true) }
    LaunchedEffect(serial, reloadKey) {
        patchesLoading = true
        patches = withContext(Dispatchers.IO) { PatchRepository.listForSerial(serial, gameName) }
        patchesLoading = false
    }
    // Collapse patches that are identical to the user (registered under several
    // game-version hashes) into one row each, toggling all hashes at once.
    val patchGroups = remember(patches) { PatchRepository.group(patches) }

    // Per-game "online server" (DNS host-switch). gameServer is non-null only
    // when this title has a known community replacement server; manualSwapList
    // prefills the advanced manual-redirect field. Loaded off the main thread.
    var gameServer by remember { mutableStateOf<GameServerSwitch?>(null) }
    var manualSwapList by remember { mutableStateOf("") }
    var showManualSwap by remember { mutableStateOf(false) }
    var applyingServer by remember { mutableStateOf(false) }
    LaunchedEffect(serial) {
        gameServer = RpcnRepository.gameServerFor(context, serial)
        manualSwapList = RpcnRepository.currentSwapList(serial)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(gameName.ifEmpty { serial })
                        Text(
                            text = serial,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_arrow_left),
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item(key = "actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        val baseText = if (hasCustom)
                            "This game uses a custom configuration. Changes below apply only to this game."
                        else
                            "This game uses the global configuration. Changing any setting below, or applying the community config, creates a custom configuration just for this game."
                        Text(
                            // Append a single-line summary of how many settings differ
                            // from default, so the impact is visible at a glance.
                            text = if (totalChanged > 0)
                                "$baseText\n\n★ $totalChanged setting${if (totalChanged == 1) "" else "s"} differ from default (marked *)."
                            else baseText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                // Fetch first and PREVIEW what the config changes,
                                // then let the user confirm before it's applied.
                                val fetch = withContext(Dispatchers.IO) {
                                    PerGameConfigRepository.fetchCommunityConfig(serial)
                                }
                                busy = false
                                when (fetch) {
                                    is CommunityConfigFetch.Found -> {
                                        AlertDialogQueue.showDialog(
                                            title = "Apply community config?",
                                            message = "RPCS3's recommended settings for this game " +
                                                "(this becomes a custom config, overriding the global one):\n\n" +
                                                fetch.yaml.trim(),
                                            confirmText = "Apply",
                                            dismissText = "Cancel",
                                            onConfirm = {
                                                scope.launch {
                                                    busy = true
                                                    val ok = withContext(Dispatchers.IO) {
                                                        PerGameConfigRepository.importConfig(serial, fetch.yaml)
                                                    }
                                                    busy = false
                                                    if (ok) reload()
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "Community configuration applied"
                                                        else "Config rejected by emulator",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
                                    }
                                    is CommunityConfigFetch.NotFound ->
                                        Toast.makeText(context, "No community configuration for this game", Toast.LENGTH_SHORT).show()
                                    is CommunityConfigFetch.Error ->
                                        Toast.makeText(context, "Failed: ${fetch.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(painterResource(R.drawable.ic_cloud_download), contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Use RPCS3 community config")
                    }

                    // Game online server (per-game DNS host-switch). One-tap apply
                    // when this title has a known community replacement server, plus
                    // a collapsible advanced manual-redirect field. Redirects this
                    // game's network traffic to a community server (the originals are
                    // offline) and needs a game restart to take effect.
                    val gs = gameServer
                    if (gs != null) {
                        Button(
                            onClick = {
                                applyingServer = true
                                scope.launch {
                                    val res = RpcnRepository.applyGameServer(serial, gs)
                                    applyingServer = false
                                    when (res) {
                                        is GameServerApplyResult.Applied -> {
                                            manualSwapList = RpcnRepository.currentSwapList(serial)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.game_server_applied),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        is GameServerApplyResult.Error ->
                                            Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !applyingServer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (applyingServer) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(painterResource(R.drawable.ic_cloud_download), contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(context.getString(R.string.game_server_apply, gs.name))
                        }
                        Text(
                            text = context.getString(R.string.game_server_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    // Advanced: manual server redirect, collapsed by default so it
                    // does not clutter. Lets power users point any game at a host
                    // even when there is no known community server.
                    OutlinedButton(
                        onClick = { showManualSwap = !showManualSwap },
                        enabled = !applyingServer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            context.getString(
                                if (showManualSwap) R.string.game_server_manual_hide
                                else R.string.game_server_manual_show
                            )
                        )
                    }
                    if (showManualSwap) {
                        OutlinedTextField(
                            value = manualSwapList,
                            onValueChange = { manualSwapList = it },
                            singleLine = false,
                            label = { Text(context.getString(R.string.game_server_manual_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                applyingServer = true
                                scope.launch {
                                    val res = RpcnRepository.applyManualSwapList(serial, manualSwapList.trim())
                                    applyingServer = false
                                    Toast.makeText(
                                        context,
                                        when (res) {
                                            is GameServerApplyResult.Applied ->
                                                context.getString(R.string.game_server_applied)
                                            is GameServerApplyResult.Error -> res.message
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = !applyingServer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(context.getString(R.string.game_server_manual_save))
                        }
                    }

                    if (hasCustom) {
                        OutlinedButton(
                            onClick = {
                                AlertDialogQueue.showDialog(
                                    title = "Remove custom configuration",
                                    message = "Reset $serial back to the global configuration?",
                                    onConfirm = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                PerGameConfigRepository.deleteCustomConfig(serial)
                                            }
                                            reload()
                                            Toast.makeText(
                                                context,
                                                "Reset to global configuration",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Remove custom configuration")
                        }
                    }
                }
            }

            // Patches - collapsible, collapsed by default.
            item(key = "patches_header") {
                CollapsibleSectionHeader(
                    title = "Patches",
                    subtitle = if (patchesLoading) null else "${patchGroups.size}",
                    expanded = expandedSections["__patches"] == true,
                    onToggle = {
                        expandedSections["__patches"] = !(expandedSections["__patches"] ?: false)
                    }
                )
            }
            if (expandedSections["__patches"] == true) {
                if (patchesLoading) {
                    item(key = "patches_loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                } else if (patchGroups.isEmpty()) {
                    item(key = "patches_empty") {
                        Text(
                            text = "No patches for this game. Open Settings -> Patch Manager to download the official set.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(patchGroups, key = { "patch:" + it.id }) { patch ->
                        var patchEnabled by remember(patch.id) { mutableStateOf(patch.enabled) }
                        val patchSub = listOf(
                            patch.author.takeIf { it.isNotEmpty() }?.let { "by $it" },
                            patch.notes.takeIf { it.isNotEmpty() }
                        ).filterNotNull().joinToString(" · ")
                        SwitchPreference(
                            checked = patchEnabled,
                            title = patch.name.ifEmpty { "(unnamed patch)" },
                            leadingIcon = null,
                            subtitle = patchSub.takeIf { it.isNotEmpty() }?.let {
                                {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { value ->
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { PatchRepository.setEnabled(patch, value) }
                                    if (ok) {
                                        patchEnabled = value
                                    } else {
                                        Toast.makeText(context, "Could not change patch", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Settings - one collapsible section per top-level category, all
            // collapsed by default so everything is findable without scrolling.
            if (configLoading) {
                item(key = "settings_loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (entries.isEmpty()) {
                item(key = "empty") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = "Per-game settings are unavailable. Update the emulator core to use this feature.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            } else {
                settingsSections.forEach { (sectionTitle, sectionEntries) ->
                    item(key = "sec/$sectionTitle") {
                        val changed = sectionEntries.changedCount()
                        CollapsibleSectionHeader(
                            title = sectionTitle,
                            // Show how many settings in this section differ from
                            // default without having to expand it.
                            subtitle = if (changed > 0) "$changed changed" else null,
                            expanded = expandedSections[sectionTitle] == true,
                            onToggle = {
                                expandedSections[sectionTitle] = !(expandedSections[sectionTitle] ?: false)
                            }
                        )
                    }
                    if (expandedSections[sectionTitle] == true) {
                        items(sectionEntries, key = { "sec/$sectionTitle/" + entryKey(it) }) { entry ->
                            when (entry) {
                                is ConfigEntry.Header -> PreferenceHeader(text = entry.title)
                                is ConfigEntry.Leaf -> ConfigLeaf(serial, entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            painter = painterResource(
                if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down
            ),
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun entryKey(entry: ConfigEntry): String = when (entry) {
    is ConfigEntry.Header -> "h:" + entry.title
    is ConfigEntry.Leaf -> "l:" + entry.path
}

@Composable
private fun ConfigLeaf(serial: String, leaf: ConfigEntry.Leaf) {
    val context = LocalContext.current
    val obj = leaf.obj
    val key = leaf.label
    val path = leaf.path

    fun fail(value: String) {
        AlertDialogQueue.showDialog(
            context.getString(R.string.error),
            context.getString(R.string.failed_to_assign_value, value, path)
        )
    }

    when (if (obj.has("type")) obj.getString("type") else null) {
        "bool" -> {
            var value by remember(path) { mutableStateOf(obj.getBoolean("value")) }
            val def = obj.getBoolean("default")
            SwitchPreference(
                checked = value,
                title = key + if (value == def) "" else " *",
                leadingIcon = null,
                onClick = { newValue ->
                    if (PerGameConfigRepository.set(serial, path, if (newValue) "true" else "false")) {
                        obj.put("value", newValue)
                        value = newValue
                    } else fail(newValue.toString())
                }
            )
        }

        "enum" -> {
            var value by remember(path) { mutableStateOf(obj.getString("value")) }
            val def = obj.getString("default")
            val variantsJson = obj.getJSONArray("variants")
            val variants = ArrayList<String>()
            for (i in 0 until variantsJson.length()) variants.add(variantsJson.getString(i))
            SingleSelectionDialog(
                currentValue = if (value in variants) value else variants.firstOrNull(),
                values = variants,
                icon = null,
                title = key + if (value == def) "" else " *",
                onValueChange = { newValue ->
                    if (PerGameConfigRepository.set(serial, path, "\"" + newValue + "\"")) {
                        obj.put("value", newValue)
                        value = newValue
                    } else fail(newValue)
                }
            )
        }

        "uint", "int" -> {
            var min = 0L
            var max = 0L
            var def = 0L
            var initial = 0L
            try {
                initial = obj.getString("value").toLong()
                max = obj.getString("max").toLong()
                min = obj.getString("min").toLong()
                def = obj.getString("default").toLong()
            } catch (_: Exception) {
            }
            var value by remember(path) { androidx.compose.runtime.mutableLongStateOf(initial) }
            if (min < max) {
                SliderPreference(
                    value = value.toFloat(),
                    valueRange = min.toFloat()..max.toFloat(),
                    title = key + if (value == def) "" else " *",
                    steps = (max - min).toInt() - 1,
                    onValueChange = { newValue ->
                        val v = newValue.toLong()
                        if (PerGameConfigRepository.set(serial, path, v.toString())) {
                            obj.put("value", v.toString())
                            value = v
                        } else fail(v.toString())
                    }
                )
            }
        }

        else -> { /* unsupported leaf type: skip */ }
    }
}
