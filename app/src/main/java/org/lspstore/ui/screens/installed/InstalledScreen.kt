@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.installed

import android.app.Activity
import android.content.Intent
import android.text.format.Formatter
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.variant.VariantResolver
import org.lspstore.ui.variant.variantReasonText
import org.lspstore.ui.variant.variantTags
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.components.EmptyBox
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleAvatar
import org.lspstore.ui.components.PackageAvatar
import org.lspstore.ui.design.KomiButton
import org.lspstore.domain.usecase.BatchInstallPhase
import org.lspstore.domain.usecase.BatchInstallState
import org.lspstore.ui.design.KomiButtonSize
import org.lspstore.ui.design.KomiButtonVariant
import org.lspstore.ui.design.KomiDialog
import org.lspstore.ui.design.KomiSectionHeader
import org.lspstore.ui.design.KomiSegment
import org.lspstore.ui.design.KomiSegmented
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.design.KomiTextField
import org.lspstore.ui.design.KomiTopBar
import org.lspstore.ui.design.topContentWindowInsets
import org.lspstore.ui.theme.KomiTextRole

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

    var query by remember { mutableStateOf("") }
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

    val updates = state.updates.filter(::matchUpdate)
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
    var selected by remember(repositoryCount, unknown.size) {
        mutableIntStateOf(0)
    }
    val selectedIndex = selected.coerceIn(0, (segments.size - 1).coerceAtLeast(0))

    var nextIndex = 0
    val repositoryIndex = if (repositoryCount > 0) nextIndex++ else -1

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) {
            if (segments.isNotEmpty()) listState.animateScrollToItem(0)
            viewModel.refresh()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = topContentWindowInsets(),
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.nav_installed),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.batchProgress?.let { progress ->
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KomiText(
                        text = progress,
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    androidx.compose.material3.TextButton(onClick = viewModel::cancelBatch) {
                        KomiText(
                            text = stringResource(R.string.action_cancel),
                            role = KomiTextRole.Label,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            KomiTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            selectedIndex == repositoryIndex && repositoryIndex >= 0 -> {
                                if (updates.isNotEmpty()) {
                                    item {
                                        KomiSectionHeader(
                                            title = stringResource(R.string.installed_section_updates, updates.size),
                                            actionLabel = stringResource(R.string.installed_update_all),
                                            onAction = viewModel::updateAll,
                                            actionEnabled = state.batchProgress == null && state.busyPackage == null,
                                        )
                                    }
                                    items(updates, key = { "u:${it.module.packageName}" }) { info ->
                                        UpdateRow(
                                            info = info,
                                            busy = state.busyPackage == info.module.packageName,
                                            batch = state.batchItems[info.module.packageName],
                                            batchRunning = state.batchProgress != null,
                                            isUpdateIgnored = isUpdateIgnored(
                                                info.installed.packageName,
                                                info.module.packageName,
                                            ),
                                            onOpen = { onOpenModule(info.module.packageName) },
                                            onUpdate = { viewModel.update(info) },
                                            onChooseVariant = {
                                                viewModel.openVariantPicker(info.module.packageName)
                                            },
                                            onToggleIgnoreUpdate = {
                                                viewModel.toggleIgnoreUpdate(info.module.packageName)
                                            },
                                        )
                                    }
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
    busy: Boolean,
    batch: BatchInstallState?,
    batchRunning: Boolean,
    isUpdateIgnored: Boolean,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onChooseVariant: () -> Unit,
    onToggleIgnoreUpdate: () -> Unit,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    val activeBatch = batch?.takeIf {
        it.phase == BatchInstallPhase.QUEUED ||
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
            if (busy || activeBatch != null) {
                // Determinate while the APK is actually transferring; spinning
                // while queued / waiting for the install slot.
                if (activeBatch?.phase == BatchInstallPhase.DOWNLOADING && activeBatch.total > 0L) {
                    CircularProgressIndicator(
                        progress = { activeBatch.progress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            } else {
                KomiButton(
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
            onToggleIgnoreUpdate = onToggleIgnoreUpdate,
        )
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
    onToggleIgnoreUpdate: (() -> Unit)?,
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
        )
    }
}
