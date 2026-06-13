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
import net.rpcsx.ui.settings.components.preference.HomePreference
import net.rpcsx.ui.settings.components.preference.SingleSelectionDialog
import net.rpcsx.ui.settings.components.preference.SwitchPreference
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
        }
    }
}

/** Fork features: game-tile layout and the sustained-performance switch. */
@Composable
fun ClankerFeaturesScreen(navigateBack: () -> Unit) {
    ClankerScaffold(stringResource(R.string.clanker_features), navigateBack) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item(key = "hdr_library") {
                PreferenceHeader(text = stringResource(R.string.clanker_features_library))
            }
            item(key = "tile_mode") {
                val labels = listOf(
                    stringResource(R.string.clanker_tile_icons),
                    stringResource(R.string.clanker_tile_boxart),
                    stringResource(R.string.clanker_tile_boxart3d),
                )
                val values = listOf("icon", "boxart", "boxart3d")
                SingleSelectionDialog(
                    currentValue = labels[values.indexOf(TileDisplay.mode).coerceAtLeast(0)],
                    values = labels,
                    icon = null,
                    title = stringResource(R.string.clanker_tile_mode),
                    subtitle = { PreferenceSubtitle(text = stringResource(R.string.clanker_tile_mode_summary), maxLines = 2) },
                    onValueChange = { label ->
                        TileDisplay.select(values[labels.indexOf(label).coerceAtLeast(0)])
                    }
                )
            }

            item(key = "hdr_performance") {
                PreferenceHeader(text = stringResource(R.string.clanker_features_performance))
            }
            item(key = "sustained_performance") {
                var itemValue by remember {
                    mutableStateOf(GeneralSettings["sustained_performance"] as Boolean? ?: true)
                }
                val def = true
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
        }
    }
}
