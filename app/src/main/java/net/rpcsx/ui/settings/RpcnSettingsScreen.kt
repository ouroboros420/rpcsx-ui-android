package net.rpcsx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.ui.settings.components.core.PreferenceHeader
import net.rpcsx.ui.settings.components.core.PreferenceSubtitle
import net.rpcsx.ui.settings.components.preference.SwitchPreference
import net.rpcsx.utils.PerGameConfigRepository
import net.rpcsx.utils.RpcnHost
import net.rpcsx.utils.RpcnRepository
import net.rpcsx.utils.RpcnSuggestedServer

/**
 * Netplay (RPCN) settings. Three clean sections: connection status + enable,
 * account (create / sign in), and servers (host list + custom add + per-game
 * suggestions). Every native call is off the main thread and wrapped so the
 * screen never crashes or hard-blocks for any game or network state - failures
 * surface as a concise snackbar and the UI stays usable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpcnSettingsScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val topBar = TopAppBarDefaults.enterAlwaysScrollBehavior()

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    // ---- shared state -----------------------------------------------------
    var enabled by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    var npid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var onlineName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("US") }
    var busyAccount by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf("") }

    var hosts by remember { mutableStateOf<List<RpcnHost>>(emptyList()) }
    var activeHost by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<RpcnSuggestedServer>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Current game serial, if any game is selected/running. Best-effort: prefer
    // the booted title id, fall back to deriving it from the active game path.
    val currentSerial = remember {
        runCatching { RPCSX.instance.getTitleId() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: RPCSX.activeGame.value?.let { PerGameConfigRepository.serialOf(it) }
                ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    suspend fun refreshHosts() {
        hosts = RpcnRepository.getHosts()
        activeHost = RpcnRepository.getActiveHost().ifBlank { RpcnRepository.OFFICIAL_HOST }
    }

    // Initial load: pull config, enabled state, hosts, and (if a game is known)
    // its suggested servers. All off the main thread; never throws.
    LaunchedEffect(Unit) {
        enabled = RpcnRepository.isEnabled()
        val cfg = RpcnRepository.getConfig()
        if (npid.isBlank()) npid = cfg.npid
        if (password.isBlank()) password = cfg.password
        if (token.isBlank()) token = cfg.token
        refreshHosts()
        if (currentSerial.isNotBlank()) {
            suggestions = RpcnRepository.suggestedServersFor(context, currentSerial)
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { desc, host ->
                showAddDialog = false
                scope.launch {
                    if (RpcnRepository.addHost(desc.ifBlank { host }, host)) {
                        toast(context.getString(R.string.rpcn_added, desc.ifBlank { host }))
                        refreshHosts()
                    } else {
                        toast(context.getString(R.string.rpcn_add_failed))
                    }
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(topBar.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.rpcn_title), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBar,
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(R.drawable.ic_keyboard_arrow_left), contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {

            // (A) Status + Enable -------------------------------------------
            item(key = "hdr_status") { PreferenceHeader(text = stringResource(R.string.rpcn_status_header)) }
            item(key = "enable_row") {
                SwitchPreference(
                    checked = enabled,
                    title = stringResource(R.string.rpcn_enable),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.rpcn_enable_summary), maxLines = 3) },
                    leadingIcon = null,
                    onClick = { value ->
                        enabled = value
                        scope.launch {
                            val ok = RpcnRepository.setEnabled(value)
                            if (!ok) {
                                enabled = !value
                                toast(context.getString(R.string.rpcn_toggle_failed))
                            }
                        }
                    }
                )
            }
            item(key = "status_action") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !testing,
                            onClick = {
                                testing = true
                                statusLine = ""
                                scope.launch {
                                    val err = RpcnRepository.testConnection()
                                    testing = false
                                    statusLine = if (err.isBlank())
                                        context.getString(R.string.rpcn_connected)
                                    else err
                                }
                            }
                        ) { Text(stringResource(R.string.rpcn_test_connection)) }
                        if (testing) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                        }
                    }
                    if (statusLine.isNotBlank()) {
                        PreferenceSubtitle(text = statusLine, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // (B) Account ----------------------------------------------------
            item(key = "hdr_account") { PreferenceHeader(text = stringResource(R.string.rpcn_account_header)) }
            item(key = "account_fields") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = npid, onValueChange = { npid = it }, singleLine = true,
                        label = { Text(stringResource(R.string.rpcn_npid)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text(stringResource(R.string.rpcn_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = onlineName, onValueChange = { onlineName = it }, singleLine = true,
                        label = { Text(stringResource(R.string.rpcn_online_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, singleLine = true,
                        label = { Text(stringResource(R.string.rpcn_email)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = country, onValueChange = { country = it.take(2).uppercase() }, singleLine = true,
                        label = { Text(stringResource(R.string.rpcn_country)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (accountError.isNotBlank()) {
                        Text(
                            accountError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !busyAccount,
                            onClick = {
                                accountError = ""
                                if (npid.isBlank() || password.isBlank()) {
                                    accountError = context.getString(R.string.rpcn_need_npid_password)
                                    return@OutlinedButton
                                }
                                busyAccount = true
                                scope.launch {
                                    val err = RpcnRepository.createAccount(
                                        npid.trim(), password, onlineName.trim(), email.trim(),
                                        country.trim().ifBlank { "US" }
                                    )
                                    busyAccount = false
                                    if (err.isBlank()) toast(context.getString(R.string.rpcn_account_created))
                                    else accountError = err
                                }
                            }
                        ) { Text(stringResource(R.string.rpcn_create_account)) }
                        TextButton(
                            enabled = !busyAccount,
                            onClick = {
                                busyAccount = true
                                scope.launch {
                                    val err = RpcnRepository.resendToken()
                                    busyAccount = false
                                    toast(if (err.isBlank()) context.getString(R.string.rpcn_token_resent) else err)
                                }
                            }
                        ) { Text(stringResource(R.string.rpcn_resend_token)) }
                        if (busyAccount) CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    }
                }
            }
            item(key = "token_row") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = token, onValueChange = { token = it }, singleLine = true,
                        label = { Text(stringResource(R.string.rpcn_token)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        enabled = !busyAccount,
                        onClick = {
                            accountError = ""
                            if (npid.isBlank() || password.isBlank()) {
                                accountError = context.getString(R.string.rpcn_need_npid_password)
                                return@OutlinedButton
                            }
                            busyAccount = true
                            statusLine = ""
                            scope.launch {
                                RpcnRepository.setCredentials(npid.trim(), password, token.trim())
                                val err = RpcnRepository.testConnection()
                                busyAccount = false
                                statusLine = if (err.isBlank()) context.getString(R.string.rpcn_connected) else err
                                toast(if (err.isBlank()) context.getString(R.string.rpcn_signed_in) else err)
                            }
                        }
                    ) { Text(stringResource(R.string.rpcn_sign_in)) }
                }
            }

            // (C) Servers (RPCN account host list) ---------------------------
            item(key = "hdr_servers") { PreferenceHeader(text = stringResource(R.string.rpcn_servers_header)) }

            if (suggestions.isNotEmpty()) {
                item(key = "hdr_suggested") {
                    PreferenceSubtitle(
                        text = stringResource(R.string.rpcn_suggested_header),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                items(suggestions.size) { idx ->
                    val s = suggestions[idx]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                Text(s.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    if (RpcnRepository.addHost(s.name, s.host)) {
                                        toast(context.getString(R.string.rpcn_added, s.name))
                                        refreshHosts()
                                    } else toast(context.getString(R.string.rpcn_add_failed))
                                }
                            }) { Text(stringResource(R.string.add)) }
                        }
                    }
                }
            }

            items(hosts.size) { idx ->
                val h = hosts[idx]
                val selected = h.host.equals(activeHost, ignoreCase = true)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        scope.launch {
                            if (RpcnRepository.setActiveHost(h.host)) activeHost = h.host
                            else toast(context.getString(R.string.rpcn_add_failed))
                        }
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = {
                            scope.launch {
                                if (RpcnRepository.setActiveHost(h.host)) activeHost = h.host
                            }
                        })
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(h.description, style = MaterialTheme.typography.bodyLarge)
                            Text(h.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (RpcnRepository.isRemovable(h.host)) {
                            IconButton(onClick = {
                                scope.launch {
                                    if (RpcnRepository.removeHost(h.host)) refreshHosts()
                                    else toast(context.getString(R.string.rpcn_add_failed))
                                }
                            }) {
                                Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = null)
                            }
                        }
                    }
                }
            }

            item(key = "add_server") {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rpcn_add_server))
                }
            }
        }
    }
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onAdd: (description: String, host: String) -> Unit) {
    var description by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rpcn_add_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, singleLine = true,
                    label = { Text(stringResource(R.string.rpcn_server_description)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text(stringResource(R.string.rpcn_server_host)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank(),
                onClick = { onAdd(description.trim(), host.trim()) }
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
