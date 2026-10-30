@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.installed

import android.app.Activity
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.ui.variant.variantReasonText
import io.github.tophtab.lspstore.ui.variant.variantTags
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.ModuleAvatar
import io.github.tophtab.lspstore.ui.components.PackageAvatar
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiDialog
import io.github.tophtab.lspstore.ui.design.KomiExtendedFab
import io.github.tophtab.lspstore.ui.design.KomiSectionHeader
import io.github.tophtab.lspstore.ui.design.KomiSegment
import io.github.tophtab.lspstore.ui.design.KomiSegmented
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.design.topContentWindowInsets
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import java.text.NumberFormat

@Composable
fun InstalledScreen(
    onOpenModule: (String) -> Unit,
    onOpenImports: () -> Unit,
    reselectTick: Int = 0,
) {
    val container = LocalAppContainer.current
    val viewModel: InstalledViewModel = viewModel(factory = InstalledViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.message) {
        state.message?.let {
            // Batch summaries carry one line per failed module; the short
            // duration is not enough to read that.
            snackbarHostState.showSnackbar(
                message = it,
                duration = if (it.contains('\n')) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            viewModel.clearMessage()
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    // The query the list was last reset for. Saveable for the same reason as the
    // query: coming back from a module page restores both as a matching pair, so
    // the restored query is not mistaken for an edit and the list keeps the
    // position the user left.
    var settledQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != settledQuery) {
            settledQuery = query
            listState.scrollToItem(0)
        }
    }
    fun InstalledModule.matches() = query.isBlank() ||
        label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)
    fun matchUpdate(info: UpdateInfo) = query.isBlank() ||
        info.module.title.contains(query, ignoreCase = true) ||
        info.module.packageName.contains(query, ignoreCase = true)
    fun isUpdateIgnored(
        installedPackage: String,
        repoPackageName: String?,
    ): Boolean {
        return installedPackage in state.ignoredUpdates ||
            (repoPackageName != null && repoPackageName in state.ignoredUpdates)
    }

    val updates = state.updates
        .filter(::matchUpdate)
        .filterNot { info ->
            // A finished row keeps its check for a moment, then leaves the
            // updates list without waiting for the rest of the batch.
            val row = state.rowStates[info.module.packageName]
            row != null &&
                row.phase == BatchInstallPhase.COMPLETED &&
                !row.noticeVisible
        }
    val upToDate = state.upToDate.filter { it.matches() }
    val unknown = state.unknown.filter { it.matches() }

    val repositoryCount = updates.size + upToDate.size
    val segments = buildList {
        if (repositoryCount > 0) {
            add(KomiSegment(stringResource(R.string.installed_section_up_to_date, repositoryCount)))
        }
        if (unknown.isNotEmpty()) {
            add(KomiSegment(stringResource(R.string.installed_section_unknown, unknown.size)))
        }
    }
    // The segment choice decides which list the restored offset belongs to, so
    // it has to survive the trip to a module page (and a process death) just
    // like the offset does. A query can make the unknown segment disappear;
    // only then fall back to the first segment.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(segments.size) {
        // While the data is still loading there is no segment to validate
        // against; resetting on the empty list would wipe the restored choice
        // before the lists arrive.
        if (segments.isNotEmpty() && selected > segments.lastIndex) selected = 0
    }
    val selectedIndex = selected.coerceIn(0, (segments.size - 1).coerceAtLeast(0))

    // Changing segments shows a different list, so start it at the top. The
    // sentinel is saveable so returning from a module page (same segment, fresh
    // composition) keeps the position instead.
    var settledSegment by rememberSaveable { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != settledSegment) {
            settledSegment = selected
            listState.scrollToItem(0)
        }
    }

    var nextIndex = 0
    val repositoryIndex = if (repositoryCount > 0) nextIndex++ else -1

    // The stop FAB counts every row that reached a terminal phase, including
    // cancelled ones, so the fraction never stalls.
    val doneCount = state.rowStates.values.count {
        it.phase == BatchInstallPhase.COMPLETED ||
            it.phase == BatchInstallPhase.FAILED ||
            it.phase == BatchInstallPhase.SKIPPED ||
            it.phase == BatchInstallPhase.CANCELLED
    }

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) {
            if (segments.isNotEmpty()) listState.animateScrollToItem(0)
            viewModel.refresh()
        }
    }

    // A completed load is a new dataset, so the list starts at its first item
    // instead of keeping an offset that belonged to the old data. The first
    // load of the process counts too. The view model hands that scroll out once
    // per load; returning from a module page with the same tick does not replay
    // it. If a load finishes while the user is on a module page, the new
    // dataset is shown from the top when the list returns.
    LaunchedEffect(state.refreshTick) {
        if (!viewModel.consumeRefreshScroll()) return@LaunchedEffect
        if (segments.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = topContentWindowInsets(),
        floatingActionButton = {
            // While a batch runs the FAB is its only cancel-all control (the
            // top bar no longer carries one). A single update shows its state
            // in the row, so it gets no FAB.
            when {
                state.batchRunning && state.batchTotal > 1 -> KomiExtendedFab(
                    onClick = viewModel::cancelBatch,
                    label = stringResource(
                        R.string.installed_batch_stop,
                        doneCount,
                        state.batchTotal,
                    ),
                    icon = Icons.Filled.Stop,
                )

                !state.batchRunning && state.updates.isNotEmpty() -> KomiExtendedFab(
                    onClick = viewModel::updateAll,
                    label = stringResource(
                        R.string.installed_update_all_count,
                        state.updates.size,
                    ),
                    icon = Icons.Filled.SystemUpdate,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KomiTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
                placeholder = stringResource(R.string.installed_search_hint),
                leadingIcon = Icons.Filled.Search,
                singleLine = true,
            )
            when {
                state.isLoading -> LoadingBox()
                state.error != null -> ErrorBox(state.error!!, viewModel::refresh)
                state.installedCount == 0 && state.pendingInstalls.isEmpty() -> EmptyBox(stringResource(R.string.installed_empty))
                segments.isEmpty() -> EmptyBox(stringResource(R.string.installed_empty))
                else -> {
                    KomiSegmented(
                        segments = segments,
                        selectedIndex = selectedIndex,
                        onSelect = { selected = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Extra bottom room so the last row stays tappable
                        // behind the update-all FAB.
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            selectedIndex == repositoryIndex && repositoryIndex >= 0 -> {
                                items(updates, key = { "u:${it.module.packageName}" }) { info ->
                                    UpdateRow(
                                        info = info,
                                        install = state.rowStates[info.module.packageName]
                                            ?: state.detailRuns[info.module.packageName],
                                        batchRunning = state.batchRunning,
                                        isUpdateIgnored = isUpdateIgnored(
                                            info.installed.packageName,
                                            info.module.packageName,
                                        ),
                                        onOpen = { onOpenModule(info.module.packageName) },
                                        onUpdate = { viewModel.update(info) },
                                        onCancel = {
                                            viewModel.cancelRow(info.module.packageName)
                                        },
                                        onChooseVariant = {
                                            viewModel.openVariantPicker(info.module.packageName)
                                        },
                                        onSkipVersion = { viewModel.skipVersion(info) },
                                        onToggleIgnoreUpdate = {
                                            viewModel.toggleIgnoreUpdate(info.module.packageName)
                                        },
                                        preReleasesEnabled =
                                            info.module.packageName in state.preReleases,
                                        canTrackPreReleases = info.module.hasPreReleases,
                                        onTogglePreReleases = {
                                            viewModel.togglePreReleases(info.module.packageName)
                                        },
                                    )
                                }
                                items(upToDate, key = { "c:${it.packageName}" }) { module ->
                                    val repoPackage = state.repoPackageByInstalled[module.packageName]
                                    InstalledRow(
                                        module = module,
                                        repoPackageName = repoPackage,
                                        isUpdateIgnored = isUpdateIgnored(
                                            module.packageName,
                                            repoPackage,
                                        ),
                                        onClick = {
                                            onOpenModule(
                                                repoPackage ?: module.packageName,
                                            )
                                        },
                                        onChooseVariant = repoPackage?.let { name ->
                                            { viewModel.openVariantPicker(name) }
                                        },
                                        onToggleIgnoreUpdate = repoPackage?.let { name ->
                                            { viewModel.toggleIgnoreUpdate(name) }
                                        },
                                        preReleasesEnabled = repoPackage != null && repoPackage in state.preReleases,
                                        canTrackPreReleases = repoPackage != null && repoPackage in state.preReleaseCapable,
                                        onTogglePreReleases = repoPackage?.let { name ->
                                            { viewModel.togglePreReleases(name) }
                                        },
                                    )
                                }
                            }

                            else -> {
                                item {
                                    KomiSectionHeader(
                                        title = stringResource(R.string.installed_section_unknown, unknown.size),
                                        actionLabel = stringResource(R.string.import_link),
                                        onAction = onOpenImports,
                                    )
                                }
                                items(unknown, key = { "x:${it.packageName}" }) { module ->
                                    InstalledRow(
                                        module = module,
                                        repoPackageName = null,
                                        isUpdateIgnored = false,
                                        onClick = null,
                                        onChooseVariant = null,
                                        onToggleIgnoreUpdate = null,
                                        preReleasesEnabled = false,
                                        canTrackPreReleases = false,
                                        onTogglePreReleases = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    state.variantPicker?.let { picker ->
        VariantPickerDialog(
            picker = picker,
            onSelect = viewModel::selectVariant,
            onDismiss = viewModel::dismissVariantPicker,
        )
    }

}

@Composable
private fun UpdateRow(
    info: UpdateInfo,
    install: BatchInstallState?,
    batchRunning: Boolean,
    isUpdateIgnored: Boolean,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onChooseVariant: () -> Unit,
    onSkipVersion: () -> Unit,
    onToggleIgnoreUpdate: () -> Unit,
    preReleasesEnabled: Boolean,
    canTrackPreReleases: Boolean,
    onTogglePreReleases: () -> Unit,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    val active = install?.takeIf {
        it.phase == BatchInstallPhase.QUEUED ||
            it.phase == BatchInstallPhase.PREPARING ||
            it.phase == BatchInstallPhase.DOWNLOADING ||
            it.phase == BatchInstallPhase.WAITING_TO_INSTALL ||
            it.phase == BatchInstallPhase.INSTALLING
    }
    KomiSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModuleAvatar(
                info.module,
                size = 44,
                installedPackage = info.installed.packageName,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                KomiText(
                    text = info.module.title,
                    role = KomiTextRole.Title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KomiText(
                    text = stringResource(
                        R.string.installed_version_arrow,
                        info.installed.versionName ?: "?",
                        info.latest.versionName,
                    ),
                    role = KomiTextRole.Mono,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                active != null -> InstallPill(
                    state = active,
                    // Once the APK is downloaded and waiting for the installer
                    // there is nothing left to cancel.
                    onCancel = if (
                        active.phase == BatchInstallPhase.QUEUED ||
                        active.phase == BatchInstallPhase.PREPARING ||
                        active.phase == BatchInstallPhase.DOWNLOADING
                    ) {
                        onCancel
                    } else {
                        null
                    },
                )

                install?.phase == BatchInstallPhase.COMPLETED && install.noticeVisible ->
                    InstallPill(state = install, onCancel = null)

                install?.phase == BatchInstallPhase.FAILED && install.noticeVisible ->
                    InstallPill(state = install, onCancel = onUpdate, retry = true)

                // Cancelled/skipped rows, and terminal marks that already
                // expired, go back to the normal action.
                else -> KomiButton(
                    onClick = onUpdate,
                    label = stringResource(R.string.action_update),
                    variant = KomiButtonVariant.Primary,
                    size = KomiButtonSize.Small,
                    enabled = !batchRunning,
                    leadingIcon = Icons.Filled.SystemUpdate,
                )
            }
            Spacer(Modifier.width(2.dp))
            MoreActionsButton { actionsOpen = true }
        }
    }

    if (actionsOpen) {
        ModuleActionsSheet(
            title = info.module.title,
            repoPackageName = info.module.packageName,
            uninstallPackageName = info.installed.packageName,
            isUpdateIgnored = isUpdateIgnored,
            onDismiss = { actionsOpen = false },
            onChooseVariant = onChooseVariant,
            onSkipVersion = onSkipVersion,
            onToggleIgnoreUpdate = onToggleIgnoreUpdate,
            preReleasesEnabled = if (canTrackPreReleases) preReleasesEnabled else null,
            onTogglePreReleases = onTogglePreReleases,
        )
    }
}

/**
 * The update button while a row is transferring or installing: its background
 * fills left to right with the download and the label stays inside the pill.
 * The pill doubles as the cancel button while the APK is still on the wire.
 */
@Composable
private fun InstallPill(
    state: BatchInstallState,
    onCancel: (() -> Unit)?,
    retry: Boolean = false,
) {
    val failed = state.phase == BatchInstallPhase.FAILED
    val colors = MaterialTheme.colorScheme
    // Tonal container + its on-color: the text stays legible on the unfilled
    // part and on the progress tint alike, in both light and dark themes.
    val container = if (failed) colors.errorContainer else colors.primaryContainer
    val content = if (failed) colors.onErrorContainer else colors.onPrimaryContainer
    // primary and primaryContainer are the same teal in the light theme, so the
    // fill is a tint of the content colour instead: it stays visible in both
    // themes and with dynamic colour.
    val fillColor = colors.onPrimaryContainer.copy(alpha = 0.20f)
    val filled = when (state.phase) {
        BatchInstallPhase.DOWNLOADING -> state.progress
        BatchInstallPhase.WAITING_TO_INSTALL,
        BatchInstallPhase.INSTALLING,
        BatchInstallPhase.COMPLETED,
        -> 1f

        else -> 0f
    }
    val animatedFill by animateFloatAsState(
        targetValue = filled,
        animationSpec = tween(durationMillis = 350),
        label = "row-download-progress",
    )
    val percent = remember {
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }
    }
    val label = when (state.phase) {
        BatchInstallPhase.QUEUED -> stringResource(R.string.installed_state_queued)
        BatchInstallPhase.PREPARING -> stringResource(R.string.installed_state_preparing)
        // 100% is the install stage's business; the transfer caps at 99 so a
        // half-finished frame cannot claim completion.
        BatchInstallPhase.DOWNLOADING ->
            percent.format(state.progress.coerceIn(0f, 0.99f).toDouble())

        BatchInstallPhase.WAITING_TO_INSTALL -> stringResource(R.string.installed_state_waiting)
        BatchInstallPhase.INSTALLING -> stringResource(R.string.installed_state_installing)
        BatchInstallPhase.COMPLETED -> stringResource(R.string.installed_state_done)
        BatchInstallPhase.FAILED -> stringResource(R.string.installed_state_failed)
        BatchInstallPhase.SKIPPED,
        BatchInstallPhase.CANCELLED,
        -> ""
    }
    val icon = when (state.phase) {
        BatchInstallPhase.COMPLETED -> Icons.Filled.Check
        BatchInstallPhase.FAILED -> Icons.Filled.Refresh
        else -> null
    }
    val actionLabel = when {
        retry -> stringResource(R.string.installed_retry_update)
        onCancel != null -> stringResource(R.string.installed_cancel_download)
        else -> null
    }
    // Same capsule as the small KomiButton this replaces.
    val shape = CircleShape
    Box(
        modifier = Modifier
            .widthIn(min = 90.dp)
            .height(34.dp)
            .clip(shape)
            .background(container)
            .drawBehind {
                if (!failed && animatedFill > 0f) {
                    drawRect(
                        color = fillColor,
                        size = Size(width = size.width * animatedFill, height = size.height),
                    )
                }
            }
            .then(
                if (onCancel != null) {
                    Modifier.clickable(
                        onClickLabel = actionLabel,
                        role = Role.Button,
                        onClick = onCancel,
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                if (state.phase == BatchInstallPhase.DOWNLOADING) {
                    progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(14.dp),
                )
            }
            KomiText(
                text = label,
                role = KomiTextRole.Mono,
                color = content,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun InstalledRow(
    module: InstalledModule,
    repoPackageName: String?,
    isUpdateIgnored: Boolean,
    onClick: (() -> Unit)?,
    onChooseVariant: (() -> Unit)?,
    onToggleIgnoreUpdate: (() -> Unit)?,
    preReleasesEnabled: Boolean,
    canTrackPreReleases: Boolean,
    onTogglePreReleases: (() -> Unit)?,
) {
    val context = LocalContext.current
    var actionsOpen by remember { mutableStateOf(false) }
    // Some LSPosed modules have no launcher activity. For those, the Open action
    // is intentionally absent; the More menu still exposes variant/update
    // actions and uninstall.
    // Key on versionCode too so an update that adds/removes a launcher is picked
    // up without leaving and re-entering the screen.
    val launchIntent = remember(module.packageName, module.versionCode) {
        context.packageManager.getLaunchIntentForPackage(module.packageName)
    }

    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PackageAvatar(
                packageName = module.packageName,
                label = module.label,
                size = 48,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f, fill = true)) {
                KomiText(
                    text = module.label,
                    role = KomiTextRole.Title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KomiText(
                    text = module.versionName ?: "?",
                    role = KomiTextRole.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                )
            }

            if (launchIntent != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { context.startActivity(launchIntent) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.detail_open_app),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(4.dp))
            MoreActionsButton { actionsOpen = true }
        }
    }
    if (onClick != null) {
        KomiSurface(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    } else {
        KomiSurface(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    }

    if (actionsOpen) {
        ModuleActionsSheet(
            title = module.label,
            repoPackageName = repoPackageName,
            uninstallPackageName = module.packageName,
            isUpdateIgnored = isUpdateIgnored,
            onDismiss = { actionsOpen = false },
            onChooseVariant = onChooseVariant,
            onToggleIgnoreUpdate = onToggleIgnoreUpdate,
            preReleasesEnabled = if (canTrackPreReleases) preReleasesEnabled else null,
            onTogglePreReleases = onTogglePreReleases,
        )
    }
}

@Composable
private fun MoreActionsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.action_more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ModuleActionsSheet(
    title: String,
    repoPackageName: String?,
    uninstallPackageName: String,
    isUpdateIgnored: Boolean,
    onDismiss: () -> Unit,
    onChooseVariant: (() -> Unit)?,
    onSkipVersion: (() -> Unit)? = null,
    onToggleIgnoreUpdate: (() -> Unit)?,
    preReleasesEnabled: Boolean? = null,
    onTogglePreReleases: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            KomiText(
                text = title,
                role = KomiTextRole.Title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repoPackageName != null && onChooseVariant != null) {
                InstalledActionRow(
                    icon = Icons.Filled.Tune,
                    label = stringResource(R.string.installed_choose_variant),
                ) {
                    onDismiss()
                    onChooseVariant()
                }
            }
            if (preReleasesEnabled != null && onTogglePreReleases != null) {
                InstalledActionRow(
                    icon = Icons.Filled.Science,
                    label = stringResource(R.string.installed_pre_releases),
                    checked = preReleasesEnabled,
                ) {
                    onDismiss()
                    onTogglePreReleases()
                }
            }
            if (onSkipVersion != null) {
                InstalledActionRow(
                    icon = Icons.Filled.SkipNext,
                    label = stringResource(R.string.detail_skip_version),
                ) {
                    onDismiss()
                    onSkipVersion()
                }
            }
            if (repoPackageName != null && onToggleIgnoreUpdate != null) {
                InstalledActionRow(
                    icon = if (isUpdateIgnored) Icons.Filled.Restore else Icons.Filled.Block,
                    label = stringResource(
                        if (isUpdateIgnored) {
                            R.string.installed_unignore_update
                        } else {
                            R.string.installed_ignore_update
                        },
                    ),
                ) {
                    onDismiss()
                    onToggleIgnoreUpdate()
                }
            }
            InstalledActionRow(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.detail_uninstall),
                tint = MaterialTheme.colorScheme.error,
            ) {
                onDismiss()
                context.startActivity(
                    Intent(Intent.ACTION_DELETE, "package:$uninstallPackageName".toUri()),
                )
            }
        }
    }
}

@Composable
private fun VariantPickerDialog(
    picker: InstalledVariantPicker,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.installed_choose_variant),
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            KomiText(
                text = picker.title,
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            KomiText(
                text = stringResource(
                    if (picker.needsConfirm) {
                        R.string.variant_needs_confirm
                    } else {
                        R.string.variant_picker_subtitle
                    },
                ),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            when {
                picker.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                picker.error != null -> KomiText(
                    text = picker.error,
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )

                else -> {
                    val eligible = picker.candidates.filter { it.blocked == null }
                    val blocked = picker.candidates.filter { it.blocked != null }
                    val current = picker.selectedAssetName
                        ?.takeIf { name -> eligible.any { it.asset.name == name } }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        item {
                            VariantChoiceRow(
                                label = stringResource(R.string.installed_variant_auto),
                                subtitle = stringResource(R.string.installed_variant_auto_subtitle),
                                selected = current == null,
                                onClick = { onSelect(null) },
                            )
                        }
                        items(eligible, key = { it.asset.name }) { candidate ->
                            val labels = if (candidate.recommended) {
                                resolver.matchLabels(candidate.traits, candidate.meta)
                            } else {
                                emptyList()
                            }
                            val reason = labels.takeIf { it.isNotEmpty() }?.let {
                                stringResource(
                                    R.string.variant_reason_matches_device,
                                    it.joinToString(", "),
                                )
                            }
                            val tags = variantTags(candidate.traits, candidate.meta)
                            val subtitle = listOfNotNull(
                                Formatter.formatShortFileSize(context, candidate.asset.size),
                                tags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                reason,
                            ).joinToString(" - ")
                            VariantChoiceRow(
                                label = candidate.asset.name,
                                subtitle = subtitle,
                                selected = current == candidate.asset.name,
                                badge = when {
                                    candidate.recommended ->
                                        stringResource(R.string.variant_recommended)
                                    candidate.debug ->
                                        stringResource(R.string.variant_debug_badge)
                                    else -> null
                                },
                                onClick = { onSelect(candidate.asset.name) },
                            )
                        }
                        if (blocked.isNotEmpty()) {
                            item {
                                KomiText(
                                    text = stringResource(
                                        R.string.variant_bundled_apps,
                                        blocked.size,
                                    ),
                                    role = KomiTextRole.Label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            items(blocked, key = { "blocked:${it.asset.name}" }) { candidate ->
                                VariantChoiceRow(
                                    label = candidate.asset.name,
                                    subtitle = candidate.blocked?.let {
                                        context.variantReasonText(it)
                                    },
                                    selected = false,
                                    enabled = false,
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = (if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            badge?.let {
                KomiText(
                    text = it,
                    role = KomiTextRole.Label,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            KomiText(
                text = label,
                role = KomiTextRole.Body,
                fontSize = 13.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            subtitle?.let {
                KomiText(
                    text = it,
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InstalledActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (tint == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        tint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        KomiText(
            text = label,
            role = KomiTextRole.Body,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
