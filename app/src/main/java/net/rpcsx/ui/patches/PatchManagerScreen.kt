package net.rpcsx.ui.patches

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.R
import net.rpcsx.ui.settings.components.core.PreferenceIcon
import net.rpcsx.ui.settings.components.preference.HomeSwitchPreference
import net.rpcsx.utils.Patch
import net.rpcsx.utils.PatchDownloadResult
import net.rpcsx.utils.PatchRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchManagerScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var patches by remember { mutableStateOf<List<Patch>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

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
                        text = "Patches are off by default. Download the official set or import a file, then enable the ones you want per game.",
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
            } else {
                items(patches, key = { it.hash + "/" + it.name }) { patch ->
                    HomeSwitchPreference(
                        title = patch.name.ifEmpty { "(unnamed patch)" },
                        description = patchSubtitle(patch),
                        checked = patch.enabled,
                        icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_build)) },
                        onCheckedChange = { enabled ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    PatchRepository.setEnabled(patch, enabled)
                                }
                                if (ok) {
                                    // Update just this row instead of re-parsing the
                                    // whole 850 KB patch set on every toggle.
                                    patches = patches.map {
                                        if (it.hash == patch.hash && it.name == patch.name)
                                            it.copy(enabled = enabled) else it
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

private fun patchSubtitle(patch: Patch): String {
    val author = patch.author.ifEmpty { "Unknown author" }
    val games = when (patch.serials.size) {
        0 -> "all games"
        1 -> patch.serials.first()
        else -> "${patch.serials.size} games"
    }
    return if (patch.notes.isNotEmpty()) "$author · $games\n${patch.notes}" else "$author · $games"
}
