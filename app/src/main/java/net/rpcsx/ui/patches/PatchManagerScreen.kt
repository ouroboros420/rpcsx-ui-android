package net.rpcsx.ui.patches

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.R
import net.rpcsx.ui.settings.components.core.PreferenceIcon
import net.rpcsx.ui.settings.components.preference.HomeSwitchPreference
import net.rpcsx.utils.Patch
import net.rpcsx.utils.PatchGroup
import net.rpcsx.utils.PatchDownloadResult
import net.rpcsx.utils.PatchRepository

// RPCS3 uses the literal "All" as the title/serial for patches that apply to every
// game. Treat it as a synthetic "universal" group rather than a game name.
private const val UNIVERSAL_LABEL = "All games (universal)"

/** Every game this patch targets. Falls back to serials, then to the universal group. */
private fun gameLabelsFor(titles: List<String>, serials: List<String>): List<String> {
    val t = titles.filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
    if (t.isNotEmpty()) return t.distinctBy { it.lowercase() }
    val s = serials.filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
    if (s.isNotEmpty()) return s.distinctBy { it.lowercase() }
    return listOf(UNIVERSAL_LABEL)
}

/** Matches a patch by its own fields (not the game name, which the section handles). */
private fun patchMatches(p: PatchGroup, q: String): Boolean =
    p.name.contains(q, ignoreCase = true) ||
        p.author.contains(q, ignoreCase = true) ||
        p.notes.contains(q, ignoreCase = true) ||
        p.serials.any { it.contains(q, ignoreCase = true) }

/** One game section: the game label plus the patches shown for it under the current query. */
private data class GameGroup(
    val label: String,
    val total: Int,
    val enabledCount: Int,
    val shown: List<PatchGroup>,
)

private fun patchRowSubtitle(patch: PatchGroup): String {
    val author = patch.author.ifEmpty { "Unknown author" }
    val head = if (patch.version.isNotEmpty()) "$author · v${patch.version}" else author
    return if (patch.notes.isNotEmpty()) "$head\n${patch.notes}" else head
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchManagerScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var patches by remember { mutableStateOf<List<Patch>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    // Per-game expand state. While searching we ignore this and expand matches.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    fun refresh() {
        scope.launch {
            loading = true
            // The official set is ~850 KB / thousands of patches; parsing it must
            // not run on the composition thread or the screen ANRs ("can't see it").
            patches = withContext(Dispatchers.IO) { PatchRepository.list() }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Group every patch under each game it targets. Sorted alphabetically (case
    // insensitive), with the universal group pinned last. Recomputed only when the
    // patch set changes (e.g. download, import, or a single-row enable toggle).
    val groups = remember(patches) {
        // Collapse identical patches (same name/author/version/notes) registered
        // under multiple game-version hashes into one row first - this is what
        // removes the confusing duplicate rows. Then bucket each into the games
        // it targets.
        val grouped = PatchRepository.group(patches)
        val map = sortedMapOf<String, MutableList<PatchGroup>>(String.CASE_INSENSITIVE_ORDER)
        for (g in grouped) {
            for (label in gameLabelsFor(g.titles, g.serials)) {
                map.getOrPut(label) { mutableListOf() }.add(g)
            }
        }
        val universal = map.remove(UNIVERSAL_LABEL)
        buildList {
            map.forEach { (label, list) -> add(label to list.toList()) }
            if (universal != null) add(UNIVERSAL_LABEL to universal.toList())
        }
    }

    val q = query.trim()

    // Visible sections for the current query. A section shows when the game name
    // matches (then all its patches show) OR when one of its patches matches (then
    // only those rows show). Anything else is dropped entirely - so searching a game
    // name yields exactly that game and nothing else.
    val visibleGroups = remember(groups, q) {
        groups.mapNotNull { (label, ps) ->
            val gameMatches = q.isEmpty() || label.contains(q, ignoreCase = true)
            val shown = if (gameMatches) ps else ps.filter { patchMatches(it, q) }
            if (shown.isEmpty()) null
            else GameGroup(label, ps.size, ps.count { it.enabled }, shown)
        }
    }

    // Groups stay collapsed by default, including under search - tap a game to
    // expand it (consistent with the unsearched list; avoids the cluttered
    // "everything detailed" view).

    val totalPatches = remember(patches) { patches.size }
    val totalEnabled = remember(patches) { patches.count { it.enabled } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val content = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
                content != null && PatchRepository.importLocal(content)
            }
            patches = withContext(Dispatchers.IO) { PatchRepository.list() }
            Toast.makeText(
                context,
                if (ok) "Patch file imported" else "Import failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patch Manager") },
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
                    Text(
                        text = "Patches are off by default. Download the official set or import a file, then expand a game and enable the patches you want.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = withContext(Dispatchers.IO) { PatchRepository.downloadOfficial() }
                                patches = withContext(Dispatchers.IO) { PatchRepository.list() }
                                busy = false
                                val msg = when (result) {
                                    is PatchDownloadResult.Success ->
                                        if (result.updated) "Official patches downloaded" else "Already up to date"
                                    is PatchDownloadResult.Error -> "Download failed: ${result.message}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                        Text("Download official patches")
                    }

                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import patch file")
                    }

                    if (patches.isNotEmpty()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Search by game name, serial, patch or author") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear")
                                    }
                                }
                            }
                        )

                        val summary = if (q.isEmpty()) {
                            "$totalPatches patches across ${groups.size} games · $totalEnabled on"
                        } else {
                            val shownPatches = visibleGroups.sumOf { it.shown.size }
                            "$shownPatches patches in ${visibleGroups.size} games match \"$q\""
                        }
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (patches.isEmpty()) {
                item(key = "empty") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(24.dp)),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = "No patches yet. Tap \"Download official patches\" above; they are stored in config/patches/patch.yml and listed here.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            } else if (visibleGroups.isEmpty()) {
                item(key = "no_match") {
                    Text(
                        text = "No games or patches match \"$query\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
            } else {
                visibleGroups.forEach { group ->
                    val isExpanded = expanded[group.label] == true

                    item(key = "header/${group.label}") {
                        GameSectionHeader(
                            label = group.label,
                            enabledCount = group.enabledCount,
                            total = group.total,
                            expanded = isExpanded,
                            // Always collapsible, including during search.
                            collapsible = true,
                            onToggle = {
                                expanded[group.label] = !(expanded[group.label] ?: false)
                            }
                        )
                    }

                    if (isExpanded) {
                        items(
                            group.shown,
                            // group.id is the unique groupBy identity. Hashes are NOT
                            // unique (one program hash hosts many patches), so keying
                            // on them collided and force-closed the app.
                            key = { "${group.label}/${it.id}" }
                        ) { patch ->
                            HomeSwitchPreference(
                                title = patch.name.ifEmpty { "(unnamed patch)" },
                                description = patchRowSubtitle(patch),
                                checked = patch.enabled,
                                icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_build)) },
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            PatchRepository.setEnabled(patch, enabled)
                                        }
                                        if (ok) {
                                            // Flip every underlying hash optimistically (the row
                                            // collapses N game-version hashes) without re-parsing.
                                            patches = patches.map {
                                                if (it.hash in patch.hashes) it.copy(enabled = enabled) else it
                                            }
                                        } else {
                                            Toast.makeText(context, "Could not change patch", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameSectionHeader(
    label: String,
    enabledCount: Int,
    total: Int,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            val sub = if (enabledCount > 0) "$total patches · $enabledCount on" else "$total patches"
            Text(
                text = sub,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (collapsible) {
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down
                ),
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
