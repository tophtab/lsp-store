package io.github.tophtab.lspstore.ui.screens.installed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.SnackbarMessageEffect
import io.github.tophtab.lspstore.ui.components.UninstallConfirmDialog
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiExtendedFab
import io.github.tophtab.lspstore.ui.design.KomiSegment
import io.github.tophtab.lspstore.ui.design.KomiSegmented
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.design.rootContentWindowInsets

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
    val focusManager = LocalFocusManager.current

    SnackbarMessageEffect(
        message = state.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
        // Batch summaries carry one line per failed module; the short
        // duration is not enough to read that.
        duration = if (state.message?.contains('\n') == true) {
            SnackbarDuration.Long
        } else {
            SnackbarDuration.Short
        },
    )

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


    // A silent (root) uninstall would delete without any system prompt, so the
    // row menu asks here first. Without root the system confirmation page is
    // already the prompt, and a second dialog would be noise.
    var uninstallConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun requestUninstall(packageName: String, label: String) {
        if (state.rootInstall) {
            uninstallConfirm = packageName to label
        } else {
            viewModel.uninstall(packageName, label)
        }
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

    val repositoryIndex = if (repositoryCount > 0) 0 else -1

    // A hand-off is processed for batch progress while its installation remains
    // pending. Duplicate requests are excluded from batchTotal by the ViewModel.
    val doneCount = state.rowStates.values.count {
        it.phase == BatchInstallPhase.COMPLETED ||
            it.phase == BatchInstallPhase.PENDING_CONFIRMATION ||
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

    // The list reserves room for the FAB only when a FAB is actually on
    // screen; otherwise the unknown segment and a clean repository segment
    // would end in dead space.
    val fabVisible = state.batchRunning && state.batchTotal > 1 ||
        !state.batchRunning && state.updates.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = rootContentWindowInsets(),
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
        KomiConstrainedContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = 1120.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                KomiTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
                    placeholder = stringResource(R.string.installed_search_hint),
                    leadingIcon = Icons.Filled.Search,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
                when {
                    state.isLoading -> LoadingBox(Modifier.weight(1f))
                    state.error != null ->
                        ErrorBox(state.error!!, viewModel::refresh, Modifier.weight(1f))

                    state.installedCount == 0 && state.pendingInstalls.isEmpty() -> EmptyBox(
                        message = stringResource(R.string.installed_empty),
                        modifier = Modifier.weight(1f),
                        actionLabel = stringResource(R.string.action_refresh),
                        onAction = viewModel::refresh,
                    )

                    segments.isEmpty() -> {
                        val searching = query.isNotBlank()
                        EmptyBox(
                            message = stringResource(
                                if (searching) R.string.search_empty else R.string.installed_empty,
                            ),
                            modifier = Modifier.weight(1f),
                            actionLabel = stringResource(
                                if (searching) R.string.action_clear else R.string.action_refresh,
                            ),
                            onAction = if (searching) {
                                { query = "" }
                            } else {
                                viewModel::refresh
                            },
                        )
                    }
                    else -> {
                        KomiSegmented(
                            segments = segments,
                            selectedIndex = selectedIndex,
                            onSelect = { selected = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        // The refresh container starts below the segment row, so the
                        // indicator appears between the capsules and the list and only
                        // a pull on the list refreshes.
                        InstalledList(
                            state = state,
                            updates = updates,
                            upToDate = upToDate,
                            unknown = unknown,
                            showRepository = selectedIndex == repositoryIndex && repositoryIndex >= 0,
                            listState = listState,
                            fabVisible = fabVisible,
                            onRefresh = viewModel::refresh,
                            actions = InstalledListActions(
                                onOpenModule = onOpenModule,
                                onOpenImports = onOpenImports,
                                onUpdate = viewModel::update,
                                onCancel = viewModel::cancelRow,
                                onChooseVariant = { viewModel.openVariantPicker(it) },
                                onSkipVersion = viewModel::skipVersion,
                                onToggleIgnoreUpdate = viewModel::toggleIgnoreUpdate,
                                onTogglePreReleases = viewModel::togglePreReleases,
                                onUninstall = ::requestUninstall,
                            ),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
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

    uninstallConfirm?.let { (packageName, label) ->
        UninstallConfirmDialog(
            label = label,
            onConfirm = {
                uninstallConfirm = null
                viewModel.uninstall(packageName, label)
            },
            onDismiss = { uninstallConfirm = null },
        )
    }

}
