package net.rpcsx.ui.games

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GeneralSettings.string
import kotlinx.coroutines.launch
import net.rpcsx.BuildConfig
import net.rpcsx.EmulatorState
import net.rpcsx.FirmwareRepository
import net.rpcsx.Game
import net.rpcsx.GameFlag
import net.rpcsx.GameInfo
import net.rpcsx.GameProgress
import net.rpcsx.GameProgressType
import net.rpcsx.GameRepository
import net.rpcsx.ProgressRepository
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.RPCSXActivity
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.RpcsxUpdater
import net.rpcsx.utils.UiUpdater
import java.io.File
import kotlin.concurrent.thread

private fun withAlpha(color: Color, alpha: Float): Color {
    return Color(
        red = color.red, green = color.green, blue = color.blue, alpha = alpha
    )
}

/**
 * Game tile display mode, persisted in app settings and observed by the grid.
 * Cycles icon (landscape PS3 icons) -> boxart (portrait 2D covers) ->
 * boxart3d (the same covers tilted into a pseudo-3D shelf box).
 */
object TileDisplay {
    var mode by mutableStateOf(GeneralSettings["tile_mode"].string("icon"))
        private set

    /** True for both 2D and pseudo-3D box-art layouts (portrait covers). */
    val isBoxArt: Boolean get() = mode == "boxart" || mode == "boxart3d"
    val is3d: Boolean get() = mode == "boxart3d"

    fun toggle() {
        mode = when (mode) {
            "icon" -> "boxart"
            "boxart" -> "boxart3d"
            else -> "icon"
        }
        GeneralSettings["tile_mode"] = mode
    }

    /** Set the layout directly (used by the Clanker Features settings selector). */
    fun select(newMode: String) {
        mode = newMode
        GeneralSettings["tile_mode"] = newMode
    }
}

// GameTDB hosts covers per region; a given title id only exists under the
// region(s) it released in, so we try the common ones in turn and let the last
// fallback be the local PS3 icon. Order: English regions first, then JP/EU.
private val coverRegions = listOf("EN", "US", "JA", "EN", "DE", "FR", "ES", "IT")

/** Candidate GameTDB cover URLs for a PS3 title id, most-likely region first. */
private fun gametdbCoverUrls(titleId: String?): List<String> =
    titleId?.takeIf { it.isNotBlank() }?.let { id ->
        coverRegions.distinct().map { r -> "https://art.gametdb.com/ps3/coverM/$r/$id.jpg" }
    } ?: emptyList()

/**
 * Front-cover image that walks the candidate region URLs in order, advancing to
 * the next whenever one fails to load, and shows [fallback] once all are
 * exhausted. Coil disk-caches each successful fetch.
 */
@Composable
private fun CoverImage(
    urls: List<String>,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    if (urls.isEmpty()) { fallback(); return }
    var idx by remember(urls) { mutableStateOf(0) }
    if (idx >= urls.size) { fallback(); return }
    SubcomposeAsyncImage(
        model = urls[idx],
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        // On a 404/failure advance to the next region; recomposition retries it.
        error = { LaunchedEffect(idx) { idx++ } }
    )
}

/**
 * Pseudo-3D "boxed game on a shelf": the front cover tilted in perspective with
 * a synthesized spine on the hinge edge and a faint sheen. Uses just the front
 * cover (not per-title spine cropping), so it looks consistent across every
 * title regardless of GameTDB's cover layout.
 */
@Composable
private fun Cover3D(
    urls: List<String>,
    fallback: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = -20f
                cameraDistance = 14f * density
                // Pivot near the right edge so the left side recedes into depth.
                transformOrigin = TransformOrigin(0.78f, 0.5f)
            }
    ) {
        CoverImage(urls, Modifier.fillMaxSize(), fallback)

        // Spine: a dark gradient strip on the left simulating box thickness.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(10.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )

        // Sheen: a faint diagonal highlight + shade for a glossy-case look.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.12f),
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameItem(game: Game, onConfigure: () -> Unit = {}) {
    val context = LocalContext.current
    val menuExpanded = remember { mutableStateOf(false) }
    val iconExists = remember { mutableStateOf(false) }
    val emulatorState by remember { RPCSX.state }
    val emulatorActiveGame by remember { RPCSX.activeGame }

    val installKeyLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                val fd = descriptor?.parcelFileDescriptor?.fd

                if (fd != null) {
                    val installProgress = ProgressRepository.create(context, context.getString(R.string.license_installation))

                    game.addProgress(GameProgress(installProgress, GameProgressType.Compile))

                    thread(isDaemon = true) {
                        if (!RPCSX.instance.installKey(fd, installProgress, game.info.path)) {
                            try {
                                ProgressRepository.onProgressEvent(installProgress, -1, 0)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        try {
                            descriptor.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    try {
                        descriptor?.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

    Column {
        DropdownMenu(
            expanded = menuExpanded.value, onDismissRequest = { menuExpanded.value = false }) {
            if (game.progressList.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.configure)) },
                    leadingIcon = { Icon(painter = painterResource(id = R.drawable.ic_build), contentDescription = null) },
                    onClick = {
                        menuExpanded.value = false
                        onConfigure()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = { Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = null) },
                    onClick = {
                        menuExpanded.value = false
                        val deleteProgress = ProgressRepository.create(context, context.getString(R.string.deleting_game))
                        game.addProgress(GameProgress(deleteProgress, GameProgressType.Compile))
                        ProgressRepository.onProgressEvent(deleteProgress, 1, 0L)
                        val path = File(game.info.path)
                        if (path.exists()) {
                            path.deleteRecursively()
                            FileUtil.deleteCache(
                                context,
                                game.info.path.trimEnd('/').substringAfterLast("/")
                            ) { success ->
                                if (!success) {
                                    AlertDialogQueue.showDialog(
                                        title = context.getString(R.string.unexpected_error),
                                        message = context.getString(R.string.failed_to_delete_game_cache),
                                        confirmText = context.getString(R.string.close),
                                        dismissText = ""
                                    )
                                }
                                ProgressRepository.onProgressEvent(deleteProgress, 100, 100)
                                GameRepository.remove(game)
                            }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear_cache)) },
                    leadingIcon = { Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = null) },
                    onClick = {
                        menuExpanded.value = false
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.clear_cache),
                            message = context.getString(R.string.clear_cache_description),
                            onConfirm = {
                                FileUtil.deleteCache(context, game.info.path.trimEnd('/').substringAfterLast("/")) { success ->
                                    AlertDialogQueue.showDialog(
                                        title = context.getString(if (success) R.string.clear_cache else R.string.unexpected_error),
                                        message = context.getString(if (success) R.string.cache_cleared else R.string.failed_to_delete_game_cache),
                                        confirmText = context.getString(R.string.close),
                                        dismissText = ""
                                    )
                                }
                            },
                            confirmText = context.getString(R.string.clear_cache),
                            dismissText = context.getString(R.string.close)
                        )
                    }
                )
            }
        }

        Card(
            shape = if (TileDisplay.isBoxArt) MaterialTheme.shapes.medium else RectangleShape,
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = click@{
                    if (game.hasFlag(GameFlag.Locked)) {
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.missing_key),
                            message = context.getString(R.string.game_require_key),
                            onConfirm = { installKeyLauncher.launch("*/*") },
                            onDismiss = {},
                            confirmText = context.getString(R.string.install_rap_file)
                        )

                        return@click
                    }

                    if (FirmwareRepository.version.value == null) {
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.missing_firmware),
                            message = context.getString(R.string.install_firmware_to_continue)
                        )
                    } else if (FirmwareRepository.progressChannel.value != null) {
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.missing_firmware),
                            message = context.getString(R.string.wait_until_firmware_install)
                        )
                    } else if (game.info.path != "$" && game.findProgress(
                            arrayOf(
                                GameProgressType.Install, GameProgressType.Remove
                            )
                        ) == null
                    ) {
                        if (game.findProgress(GameProgressType.Compile) != null) {
                            AlertDialogQueue.showDialog(
                                title = context.getString(R.string.game_compiling_not_finished),
                                message = context.getString(R.string.wait_until_game_compile)
                            )
                        } else {
                            GameRepository.onBoot(game)
                            val emulatorWindow = Intent(
                                context, RPCSXActivity::class.java
                            )
                            emulatorWindow.putExtra("path", game.info.path)
                            context.startActivity(emulatorWindow)
                        }
                    }
                }, onLongClick = {
                    if (game.info.name.value != "VSH") {
                        menuExpanded.value = true
                    }
                })
        ) {
            // Check the icon file off the main thread (was a per-recomposition
            // File.exists() stat in composition -> grid jank during installs).
            // Re-checks when the icon path or the install-progress state changes.
            val iconPath = game.info.iconPath.value
            val progressCount = game.progressList.size
            LaunchedEffect(iconPath, progressCount) {
                if (iconPath == null) {
                    iconExists.value = false
                    return@LaunchedEffect
                }
                if (!iconExists.value) {
                    iconExists.value = withContext(Dispatchers.IO) { File(iconPath).exists() }
                }
            }

            val boxArt = TileDisplay.isBoxArt && game.info.name.value != "VSH"
            val coverUrls = if (boxArt) gametdbCoverUrls(game.info.titleId.value) else emptyList()

            Box(
                modifier = (if (boxArt) Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                            else Modifier.height(110.dp).fillMaxSize())
                    .align(alignment = Alignment.CenterHorizontally)
            ) {
                if (boxArt && coverUrls.isNotEmpty()) {
                    // Portrait cover from GameTDB (disk-cached by Coil), tried across
                    // regions; falls back to the local PS3 icon when none resolve.
                    val iconFallback: @Composable () -> Unit = {
                        if (game.info.iconPath.value != null) {
                            // The local icon is landscape; Fit (not Crop) keeps its
                            // aspect inside the portrait box instead of stretching it.
                            AsyncImage(
                                model = game.info.iconPath.value,
                                contentScale = ContentScale.Fit,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (TileDisplay.is3d) {
                        Cover3D(coverUrls, iconFallback)
                    } else {
                        CoverImage(coverUrls, Modifier.fillMaxSize(), iconFallback)
                    }
                } else if (game.info.iconPath.value != null && iconExists.value) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AsyncImage(
                            model = game.info.iconPath.value,
                            contentScale = if (game.info.name.value == "VSH") ContentScale.Fit else ContentScale.Crop,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                        )
                    }
                }

                // Game version (APP_VER) badge so users can tell which patch applies
                game.info.version.value?.takeIf { it.isNotBlank() }?.let { ver ->
                    Text(
                        text = "v$ver",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(
                                withAlpha(Color.Black, 0.55f),
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                if (game.progressList.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(withAlpha(Color.DarkGray, 0.6f))
                    ) {}

                    val progressChannel = game.progressList.first().id
                    val progress = ProgressRepository.getItem(progressChannel)
                    val progressValue = progress?.value?.value
                    val maxValue = progress?.value?.max

                    if (progressValue != null && maxValue != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (maxValue.longValue != 0L) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(64.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    progress = {
                                        progressValue.longValue.toFloat() / maxValue.longValue.toFloat()
                                    },
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(64.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        }
                    }
                } else if (emulatorState == EmulatorState.Paused && emulatorActiveGame == game.info.path) {
                    Card(modifier = Modifier.padding(5.dp)) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_play),
                            contentDescription = null
                        )
                    }
                }

                if (game.hasFlag(GameFlag.Locked) || game.hasFlag(GameFlag.Trial)) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Card(
                            onClick = {
                                installKeyLauncher.launch("*/*")
                            }) {

                            Icon(
                                painter = painterResource(id = R.drawable.ic_lock),
                                contentDescription = "Game is locked",
                                modifier = Modifier
                                    .size(30.dp)
                                    .padding(7.dp)
                            )
                        }
                    }
                }

//                val name = game.info.name.value
//                if (name != null) {
//                    Row(
//                        verticalAlignment = Alignment.Bottom,
//                        horizontalArrangement = Arrangement.Center,
//                        modifier = Modifier.fillMaxSize()
//                    ) {
//                        Text(name, textAlign = TextAlign.Center)
//                    }
//                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(navigateToConfig: (Game) -> Unit = {}) {
    val context = LocalContext.current
    val games = remember { GameRepository.list() }
    val isRefreshing by remember { GameRepository.isRefreshing }
    val state = rememberPullToRefreshState()
    var uiUpdateVersion by remember { mutableStateOf<String?>(null) }
    var uiUpdate by remember { mutableStateOf(false) }
    var uiUpdateProgressValue by remember { mutableLongStateOf(0) }
    var uiUpdateProgressMax by remember { mutableLongStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val rpcsxLibrary by remember { RPCSX.activeLibrary }
    var rpcsxInstallLibraryFailed by remember { mutableStateOf(false) }
    var rpcsxUpdateVersion by remember { mutableStateOf<String?>(null) }
    var rpcsxUpdate by remember { mutableStateOf(false) }
    var rpcsxUpdateProgressValue by remember { mutableLongStateOf(0) }
    var rpcsxUpdateProgressMax by remember { mutableLongStateOf(0) }
    val activeDialogs = remember { AlertDialogQueue.dialogs }

    val gameInProgress = games.find { it.progressList.isNotEmpty() }

    var updatesChecked by rememberSaveable { mutableStateOf(false) }

    val checkForUpdates = suspend {
        rpcsxUpdateVersion = RpcsxUpdater.checkForUpdate()
        uiUpdateVersion = UiUpdater.checkForUpdate(context)

        if (rpcsxUpdateVersion == null && rpcsxLibrary == null) {
            rpcsxInstallLibraryFailed = true
        }
    }

    LaunchedEffect(Unit) {
        if (!updatesChecked) {
            updatesChecked = true
            checkForUpdates()
        }
    }

    if (uiUpdateVersion != null && rpcsxUpdateVersion == null && activeDialogs.isEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!uiUpdate) uiUpdateVersion = null },
            title = {
                Text(
                    if (uiUpdate) stringResource(R.string.downloading_ui, uiUpdateVersion!!)
                    else stringResource(R.string.ui_update_available)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uiUpdate) {
                        if (uiUpdateProgressMax == 0L) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                { uiUpdateProgressValue / uiUpdateProgressMax.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.current_and_new_version, BuildConfig.Version, uiUpdateVersion!!))
                    }
                }
            },
            confirmButton = {
                if (!uiUpdate) {
                    TextButton(onClick = {
                        uiUpdate = true

                        coroutineScope.launch {
                            val file = UiUpdater.downloadUpdate(
                                context,
                                File("${context.getExternalFilesDir(null)!!.absolutePath}/cache/")
                            ) { value, max ->
                                uiUpdateProgressValue = value
                                uiUpdateProgressMax = max
                            }
                            uiUpdate = false
                            uiUpdateVersion = null

                            if (file != null) {
                                UiUpdater.installUpdate(context, file)
                            }
                        }
                    }) {
                        Text(stringResource(R.string.update))
                    }
                }
            })
    }

    if (rpcsxLibrary == null && rpcsxUpdateVersion == null && !rpcsxUpdate && activeDialogs.isEmpty()) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.missing_rpcsx_lib)) },
            text = { Text(stringResource(R.string.downloading_latest_rpcsx)) },
            confirmButton = {}
        )
    }

    if (rpcsxUpdateVersion != null && activeDialogs.isEmpty()) {
        val startUpdate = {
            rpcsxUpdate = true

            coroutineScope.launch {
                val file = RpcsxUpdater.downloadUpdate(
                    File(context.filesDir.canonicalPath)
                ) { value, max ->
                    rpcsxUpdateProgressValue = value
                    rpcsxUpdateProgressMax = max
                }

                if (file != null) {
                    RpcsxUpdater.installUpdate(context, file)
                } else if (rpcsxLibrary == null) {
                    rpcsxInstallLibraryFailed = true
                }

                rpcsxUpdate = false
                rpcsxUpdateVersion = null
            }
        }

        if (rpcsxLibrary == null) {
            startUpdate()
        }

        AlertDialog(
            onDismissRequest = {
                if (!rpcsxUpdate && rpcsxLibrary != null) rpcsxUpdateVersion = null
            },
            title = {
                Text(
                    if (rpcsxUpdate) stringResource(R.string.downloading_rpcsx, rpcsxUpdateVersion!!)
                    else stringResource(R.string.rpcsx_update_available)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (rpcsxUpdate) {
                        if (rpcsxUpdateProgressMax == 0L) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                { rpcsxUpdateProgressValue / rpcsxUpdateProgressMax.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.current_and_new_version,
                                RpcsxUpdater.getCurrentVersion() ?: stringResource(R.string.none),
                                rpcsxUpdateVersion!!
                            )
                        )
                    }
                }
            },
            confirmButton = {
                if (!rpcsxUpdate) {
                    TextButton(onClick = {
                        startUpdate()
                    }) {
                        Text(stringResource(R.string.update))
                    }
                }
            })
    }

    if (rpcsxInstallLibraryFailed) {
        val installRpcsxLauncher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    rpcsxInstallLibraryFailed = false

                    val target = File(context.filesDir.canonicalPath, "librpcsx_unknown_unknown.so")
                    if (target.exists()) {
                        target.delete()
                    }

                    FileUtil.saveFile(context, uri, target.path)

                    if (RPCSX.instance.getLibraryVersion(target.path) != null) {
                        RpcsxUpdater.installUpdate(context, target, isCustom = true)
                    } else {
                        rpcsxInstallLibraryFailed = true
                    }
                }
            }

        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.failed_to_download_rpcsx)) },
            text = {},
            confirmButton = {
                TextButton(onClick = {
                    rpcsxInstallLibraryFailed = false
                    coroutineScope.launch { checkForUpdates() }
                }) {
                    Text(stringResource(R.string.retry))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    installRpcsxLauncher.launch("*/*")
                }) {
                    Text(stringResource(R.string.install_custom_version))
                }
            })
    }

    if (rpcsxLibrary == null) {
        return
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = state,
        onRefresh = {
            if (gameInProgress == null && !isRefreshing) {
                GameRepository.queueRefresh()
            }
        },
        indicator = {
            if (gameInProgress == null) {
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary
                )
            }
        },
    ) {
        LazyVerticalGrid(
            // Portrait covers tile densely (narrower cells); landscape icons keep the wider cell.
            columns = GridCells.Adaptive(minSize = if (TileDisplay.isBoxArt) 116.dp else 320.dp * 0.6f),
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            items(count = games.size, key = { index -> games[index].info.path }) { index ->
                GameItem(games[index], onConfigure = { navigateToConfig(games[index]) })
            }
        }
    }
}

@Preview
@Composable
fun GamesScreenPreview() {
    listOf(
        "Minecraft", "Skate 3", "Mirror's Edge", "Demon's Souls"
    ).forEach { x -> GameRepository.addPreview(arrayOf(GameInfo(x, x))) }

    GamesScreen()
}
