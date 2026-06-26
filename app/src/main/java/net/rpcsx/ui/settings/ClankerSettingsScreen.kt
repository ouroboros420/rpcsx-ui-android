package net.rpcsx.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import net.rpcsx.R
import net.rpcsx.ThemeState
import net.rpcsx.ui.games.TileDisplay
import net.rpcsx.ui.settings.components.core.PreferenceHeader
import net.rpcsx.ui.settings.components.core.PreferenceIcon
import net.rpcsx.ui.settings.components.core.PreferenceSubtitle
import net.rpcsx.ui.settings.components.ColorPreference
import net.rpcsx.ui.settings.components.preference.HomePreference
import net.rpcsx.ui.settings.components.preference.SingleSelectionDialog
import net.rpcsx.ui.settings.components.preference.SliderPreference
import net.rpcsx.ui.settings.components.preference.SwitchPreference
import androidx.compose.ui.platform.LocalContext
import net.rpcsx.utils.CompileThreadPolicy
import net.rpcsx.utils.GameViewTheme
import net.rpcsx.utils.GpuTurbo
import net.rpcsx.utils.PowerPolicy
import net.rpcsx.utils.GeneralSettings

/**
 * A small Scaffold + back arrow wrapper so the Clanker setting sub-screens share
 * the same large-top-app-bar chrome as the rest of the app's settings screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClankerScaffold(
    title: String,
    navigateBack: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = title, fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(R.drawable.ic_keyboard_arrow_left), contentDescription = null)
                    }
                }
            )
        }
    ) { contentPadding -> content(contentPadding) }
}

/**
 * Hub for everything this fork (RPCSX-Clanker) adds on top of upstream RPCSX.
 * Keeps the fork's extras out of the main settings list and groups them into
 * click-through sub-categories instead of loose toggles.
 */
@Composable
fun ClankerSettingsScreen(
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit,
) {
    ClankerScaffold(stringResource(R.string.clanker_settings), navigateBack) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item(key = "clanker_themes") {
                HomePreference(
                    title = stringResource(R.string.clanker_themes),
                    icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_palette)) },
                    description = stringResource(R.string.clanker_themes_description),
                    onClick = { navigateTo("clanker_themes") }
                )
            }
            item(key = "clanker_features") {
                HomePreference(
                    title = stringResource(R.string.clanker_features),
                    icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_grid_on)) },
                    description = stringResource(R.string.clanker_features_description),
                    onClick = { navigateTo("clanker_features") }
                )
            }
            item(key = "clanker_netplay") {
                HomePreference(
                    title = stringResource(R.string.rpcn_title),
                    icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_cloud_download)) },
                    description = stringResource(R.string.rpcn_entry_description),
                    onClick = { navigateTo("rpcn_settings") }
                )
            }
            item(key = "clanker_patches") {
                HomePreference(
                    title = stringResource(R.string.patch_manager),
                    icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_build)) },
                    description = stringResource(R.string.patch_manager_description),
                    onClick = { navigateTo("patch_manager") }
                )
            }
        }
    }
}

/** Appearance: light/dark, Material You, AMOLED. */
@Composable
fun ClankerThemesScreen(navigateBack: () -> Unit) {
    ClankerScaffold(stringResource(R.string.clanker_themes), navigateBack) { contentPadding ->
        // Game-view theming state, lifted so the radius/colour rows enable/disable
        // live when their parent toggle flips.
        var roundedCorners by remember { mutableStateOf(GameViewTheme.roundedCorners) }
        var radiusDp by remember { mutableStateOf(GameViewTheme.cornerRadiusDp.toFloat()) }
        var border by remember { mutableStateOf(GameViewTheme.border) }
        var borderWidthDp by remember { mutableStateOf(GameViewTheme.borderWidthDp.toFloat()) }
        var borderColor by remember { mutableStateOf(GameViewTheme.borderColor) }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item(key = "hdr_theme") {
                PreferenceHeader(text = stringResource(R.string.clanker_theme_appearance))
            }
            item(key = "theme_mode") {
                val labels = listOf(
                    stringResource(R.string.clanker_theme_system),
                    stringResource(R.string.clanker_theme_light),
                    stringResource(R.string.clanker_theme_dark),
                )
                val values = listOf("system", "light", "dark")
                SingleSelectionDialog(
                    currentValue = labels[values.indexOf(ThemeState.mode).coerceAtLeast(0)],
                    values = labels,
                    icon = null,
                    title = stringResource(R.string.clanker_theme_mode),
                    onValueChange = { label ->
                        ThemeState.mode = values[labels.indexOf(label).coerceAtLeast(0)]
                    }
                )
            }
            if (ThemeState.dynamicSupported) {
                item(key = "theme_dynamic") {
                    SwitchPreference(
                        checked = ThemeState.dynamicColor,
                        title = stringResource(R.string.clanker_theme_material_you),
                        subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_theme_material_you_summary), maxLines = 2) },
                        leadingIcon = null,
                        onClick = { ThemeState.dynamicColor = it }
                    )
                }
            }
            item(key = "theme_amoled") {
                SwitchPreference(
                    checked = ThemeState.amoled,
                    title = stringResource(R.string.clanker_theme_amoled),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_theme_amoled_summary), maxLines = 2) },
                    leadingIcon = null,
                    onClick = { ThemeState.amoled = it }
                )
            }
            item(key = "accent_color") {
                ColorPreference(
                    title = stringResource(R.string.clanker_accent),
                    subtitle = stringResource(R.string.clanker_accent_summary),
                    color = ThemeState.accentColor,
                    allowOff = true,
                    onColor = { ThemeState.accentColor = it }
                )
            }

            item(key = "hdr_gameview") {
                PreferenceHeader(text = stringResource(R.string.clanker_gameview_header))
            }
            item(key = "gv_rounded") {
                SwitchPreference(
                    checked = roundedCorners,
                    title = stringResource(R.string.clanker_gv_rounded),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_gv_rounded_summary), maxLines = 2) },
                    leadingIcon = null,
                    onClick = { GameViewTheme.roundedCorners = it; roundedCorners = it }
                )
            }
            item(key = "gv_radius") {
                SliderPreference(
                    value = radiusDp,
                    onValueChange = { GameViewTheme.cornerRadiusDp = it.toInt(); radiusDp = it.toInt().toFloat() },
                    title = stringResource(R.string.clanker_gv_radius),
                    enabled = roundedCorners,
                    valueRange = 0f..64f,
                    valueContent = { Text("${radiusDp.toInt()} dp") }
                )
            }
            item(key = "gv_border") {
                SwitchPreference(
                    checked = border,
                    title = stringResource(R.string.clanker_gv_border),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_gv_border_summary), maxLines = 2) },
                    leadingIcon = null,
                    onClick = { GameViewTheme.border = it; border = it }
                )
            }
            item(key = "gv_border_width") {
                SliderPreference(
                    value = borderWidthDp,
                    onValueChange = { GameViewTheme.borderWidthDp = it.toInt(); borderWidthDp = it.toInt().toFloat() },
                    title = stringResource(R.string.clanker_gv_border_width),
                    enabled = border,
                    valueRange = 0f..16f,
                    valueContent = { Text("${borderWidthDp.toInt()} dp") }
                )
            }
            item(key = "gv_border_color") {
                ColorPreference(
                    title = stringResource(R.string.clanker_gv_border_color),
                    subtitle = stringResource(R.string.clanker_gv_border_color_summary),
                    color = borderColor,
                    enabled = border,
                    onColor = { GameViewTheme.borderColor = it; borderColor = it }
                )
            }
        }
    }
}

/** Fork features: game-tile layout and the sustained-performance switch. */
@Composable
fun ClankerFeaturesScreen(navigateBack: () -> Unit) {
    ClankerScaffold(stringResource(R.string.clanker_features), navigateBack) { contentPadding ->
        // Battery saver and GPU turbo are mutually exclusive (opposite power goals). Hoist both
        // switch states to the screen scope so flipping one updates the OTHER switch's UI live,
        // not just its backing store (otherwise the auto-disabled switch stayed visually on until
        // the screen was reopened - the reported "didn't exclude each other visually").
        var batterySaverOn by remember { mutableStateOf(PowerPolicy.enabled) }
        var gpuTurboOn by remember { mutableStateOf(GpuTurbo.enabled) }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // Game tile layout (box-art covers) selector removed for now - the
            // cover feature is cut until remote loading is reliable on-device.

            item(key = "hdr_performance") {
                PreferenceHeader(text = stringResource(R.string.clanker_features_performance))
            }
            item(key = "battery_saver") {
                SwitchPreference(
                    checked = batterySaverOn,
                    title = stringResource(R.string.clanker_battery_saver),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_battery_saver_summary), maxLines = 3) },
                    leadingIcon = null,
                    onClick = { value ->
                        PowerPolicy.enabled = value
                        PowerPolicy.apply()
                        batterySaverOn = value
                        // Mutually exclusive with GPU turbo: turning this on flips turbo off live.
                        if (value && gpuTurboOn) {
                            GpuTurbo.enabled = false
                            GpuTurbo.apply()
                            gpuTurboOn = false
                        }
                    }
                )
            }
            item(key = "gpu_turbo") {
                SwitchPreference(
                    checked = gpuTurboOn,
                    title = stringResource(R.string.clanker_gpu_turbo),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_gpu_turbo_summary), maxLines = 4) },
                    leadingIcon = null,
                    onClick = { value ->
                        GpuTurbo.enabled = value
                        GpuTurbo.apply()
                        gpuTurboOn = value
                        // Mutually exclusive with battery saver: turning this on flips saver off live.
                        if (value && batterySaverOn) {
                            PowerPolicy.enabled = false
                            PowerPolicy.apply()
                            batterySaverOn = false
                        }
                    }
                )
            }
            item(key = "cpu_affinity") {
                var itemValue by remember { mutableStateOf(GeneralSettings["cpu_affinity"] as? Boolean ?: false) }
                SwitchPreference(
                    checked = itemValue,
                    title = stringResource(R.string.clanker_cpu_affinity),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_cpu_affinity_summary), maxLines = 3) },
                    leadingIcon = null,
                    onClick = { value ->
                        GeneralSettings.setValue("cpu_affinity", value)
                        runCatching { net.rpcsx.RPCSX.instance.setCpuAffinityMode(value) }
                        itemValue = value
                    }
                )
            }
            item(key = "wfe_mode") {
                var itemValue by remember { mutableStateOf(GeneralSettings["wfe_mode"] as? Boolean ?: false) }
                SwitchPreference(
                    checked = itemValue,
                    title = stringResource(R.string.clanker_wfe),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_wfe_summary), maxLines = 3) },
                    leadingIcon = null,
                    onClick = { value ->
                        GeneralSettings.setValue("wfe_mode", value)
                        runCatching { net.rpcsx.RPCSX.instance.setWfeMode(value) }
                        itemValue = value
                    }
                )
            }
            // Smooth-shaders toggle removed: the async SPIR-V interpreter destabilised the
            // Vulkan backend (boot crash) and is a net fps loss on mobile Turnip, so the core
            // stays on async_recompiler (already the default, stutter-free background compile).
            item(key = "auto_compile_threads") {
                val context = LocalContext.current
                var itemValue by remember { mutableStateOf(CompileThreadPolicy.enabled) }
                val safe = remember { CompileThreadPolicy.safeThreads(context) }
                SwitchPreference(
                    checked = itemValue,
                    title = stringResource(R.string.clanker_auto_threads),
                    subtitle = {
                        PreferenceSubtitle(
                            text = stringResource(
                                R.string.clanker_auto_threads_summary,
                                if (safe == 0) stringResource(R.string.clanker_auto_threads_all)
                                else "$safe threads"
                            ),
                            maxLines = 3
                        )
                    },
                    leadingIcon = null,
                    onClick = { value ->
                        CompileThreadPolicy.enabled = value
                        CompileThreadPolicy.apply(context)
                        itemValue = value
                    }
                )
            }
            item(key = "sustained_performance") {
                var itemValue by remember {
                    mutableStateOf(GeneralSettings["sustained_performance"] as Boolean? ?: false)
                }
                val def = false
                SwitchPreference(
                    checked = itemValue,
                    title = stringResource(R.string.enable_sustained_performance) + if (itemValue == def) "" else " *",
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.sustained_performance_summary), maxLines = 3) },
                    leadingIcon = null,
                    onClick = { value ->
                        GeneralSettings.setValue("sustained_performance", value)
                        itemValue = value
                    }
                )
            }
            item(key = "adpf_hints") {
                var itemValue by remember { mutableStateOf(net.rpcsx.utils.AdpfManager.enabled) }
                SwitchPreference(
                    checked = itemValue,
                    title = stringResource(R.string.clanker_adpf),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_adpf_summary), maxLines = 4) },
                    leadingIcon = null,
                    onClick = { value ->
                        net.rpcsx.utils.AdpfManager.enabled = value
                        itemValue = value
                    }
                )
            }
        }
    }
}
