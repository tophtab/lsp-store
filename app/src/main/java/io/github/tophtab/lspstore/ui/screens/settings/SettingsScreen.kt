package io.github.tophtab.lspstore.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.update.UpdateScheduler
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.SnackbarMessageEffect
import io.github.tophtab.lspstore.ui.design.rootContentWindowInsets
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenFavourites: () -> Unit,
    onOpenRecentlyViewed: () -> Unit,
    onOpenSkipped: () -> Unit,
    onOpenIgnoredModules: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMirrors: () -> Unit,
    reselectTick: Int = 0,
) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    var translationExpanded by rememberSaveable { mutableStateOf(false) }
    var hideIconConfirm by rememberSaveable { mutableStateOf(false) }
    var customIntervalOpen by rememberSaveable { mutableStateOf(false) }
    var customIntervalInput by rememberSaveable { mutableStateOf("") }
    var customIntervalInvalid by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Resolved in composition: LocalContext.getString would keep a stale value
    // across a configuration change (LocalContextGetResourceValueCall).
    val notificationsNeeded = stringResource(R.string.settings_notifications_needed)

    // Turning the check on is useful with or without notifications; only the
    // announcement depends on the permission, so a denial is reported and the
    // setting stays on.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch { snackbarHostState.showSnackbar(notificationsNeeded) }
        }
    }
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openCustomInterval(initialHours: Int = state.checkIntervalHours) {
        customIntervalInput = initialHours.toString()
        customIntervalInvalid = false
        customIntervalOpen = true
    }

    fun submitCustomInterval() {
        val hours = UpdateScheduler.parseIntervalHours(customIntervalInput)
        if (hours == null) {
            customIntervalInvalid = true
            return
        }
        customIntervalOpen = false
        viewModel.setCheckIntervalHours(hours)
    }

    // Both directions go through the document picker: the user decides where a
    // backup lives, and no storage permission is needed either way.
    val backupExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportLibrary(uri) }
    val backupImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.readLibraryBackup(uri) }
    // The system folder picker returns a document tree; the ViewModel persists
    // its grant, so exports keep working after a restart.
    val downloadLocationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.setDownloadLocation(uri) }

    SnackbarMessageEffect(
        message = state.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) scrollState.animateScrollTo(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = rootContentWindowInsets(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // The scroll container spans the whole page so a drag in the empty
        // margin either side of the 640dp column still scrolls; only the
        // content itself is width-capped and centered.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppearanceSettingsGroup(
                    state = state,
                    onThemeModeChange = viewModel::setThemeMode,
                    onAmoledChange = viewModel::setAmoled,
                    onDynamicColorChange = viewModel::setDynamicColor,
                    onHideIconChange = { checked ->
                        if (checked) hideIconConfirm = true
                        else viewModel.setHideLauncherIcon(false)
                    },
                )
                TranslationSettingsGroup(
                    settings = state.translation,
                    expanded = translationExpanded,
                    onToggle = { translationExpanded = !translationExpanded },
                    onUpdate = viewModel::updateTranslation,
                )
                DownloadSettingsGroup(
                    state = state,
                    onPickLocation = {
                        downloadLocationPicker.launch(state.downloadLocation.takeIf { it.isNotBlank() }?.toUri())
                    },
                    onResetLocation = viewModel::resetDownloadLocation,
                    onMaxDownloadsChange = viewModel::setMaxConcurrentDownloads,
                )
                RootSettingsGroup(state, viewModel::setRootInstall)
                UpdateSettingsGroup(
                    state = state,
                    onBackgroundCheckChange = { checked ->
                        if (checked) requestNotificationPermission()
                        viewModel.setBackgroundCheck(checked)
                    },
                    onIntervalChange = viewModel::setCheckIntervalHours,
                    onCustomInterval = ::openCustomInterval,
                    onWifiOnlyChange = viewModel::setWifiOnly,
                    onAutoUpdateChange = viewModel::setAutoUpdateRoot,
                )
                LibrarySettingsGroup(onOpenFavourites, onOpenRecentlyViewed, onOpenIgnoredModules, onOpenSkipped)
                StorageSettingsGroup(state, viewModel::clearDownloadedApks, viewModel::clearIndexCache)
                BackupSettingsGroup(
                    onExport = { backupExport.launch(viewModel.backupFileName()) },
                    onImport = { backupImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
                AdvancedSettingsGroup(onOpenMirrors, onOpenAbout)
            }
        }
    }

    if (customIntervalOpen) {
        CustomIntervalDialog(
            input = customIntervalInput,
            invalid = customIntervalInvalid,
            onInputChange = { value ->
                customIntervalInput = value.filter(Char::isDigit).take(3)
                customIntervalInvalid = false
            },
            onConfirm = ::submitCustomInterval,
            onDismiss = { customIntervalOpen = false },
        )
    }

    state.pendingImport?.let { backup ->
        LibraryImportDialog(backup, viewModel::applyImport, viewModel::dismissImport)
    }

    if (hideIconConfirm) {
        HideLauncherIconDialog(
            onConfirm = {
                hideIconConfirm = false
                viewModel.setHideLauncherIcon(true)
            },
            onDismiss = { hideIconConfirm = false },
        )
    }
}
