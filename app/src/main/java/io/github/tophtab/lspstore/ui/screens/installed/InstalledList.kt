package io.github.tophtab.lspstore.ui.screens.installed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import io.github.tophtab.lspstore.ui.components.PullToRefreshBox
import io.github.tophtab.lspstore.ui.design.KomiSectionHeader

internal data class InstalledListActions(
    val onOpenModule: (String) -> Unit,
    val onOpenImports: () -> Unit,
    val onUpdate: (UpdateInfo) -> Unit,
    val onCancel: (String) -> Unit,
    val onChooseVariant: (String) -> Unit,
    val onSkipVersion: (UpdateInfo) -> Unit,
    val onToggleIgnoreUpdate: (String) -> Unit,
    val onTogglePreReleases: (String) -> Unit,
    val onUninstall: (String, String) -> Unit,
)

@Composable
internal fun InstalledList(
    state: InstalledUiState,
    progressFor: (String) -> Flow<BatchInstallState?>,
    updates: List<UpdateInfo>,
    upToDate: List<InstalledModule>,
    unknown: List<InstalledModule>,
    showRepository: Boolean,
    listState: LazyListState,
    fabVisible: Boolean,
    onRefresh: () -> Unit,
    actions: InstalledListActions,
    modifier: Modifier = Modifier,
) {
    fun isUpdateIgnored(
        installedPackage: String,
        repoPackageName: String?,
    ): Boolean {
        return installedPackage in state.ignoredUpdates ||
            (repoPackageName != null && repoPackageName in state.ignoredUpdates)
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Extra bottom room so the last row stays tappable
            // behind the update-all FAB. Without a FAB the
            // list matches the other screens' 32.dp end.
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = if (fabVisible) 96.dp else 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                showRepository -> {
                    items(updates, key = { "u:${it.module.packageName}" }) { info ->
                        val progress = remember(progressFor, info.module.packageName) { progressFor(info.module.packageName) }
                        val install by progress.collectAsStateWithLifecycle(
                            initialValue = state.rowStates[info.module.packageName] ?: state.detailRuns[info.module.packageName],
                        )
                        UpdateRow(
                            info = info,
                            install = install,
                            isUpdateIgnored = isUpdateIgnored(
                                info.installed.packageName,
                                info.module.packageName,
                            ),
                            onOpen = { actions.onOpenModule(info.module.packageName) },
                            onUpdate = { actions.onUpdate(info) },
                            onCancel = {
                                actions.onCancel(info.module.packageName)
                            },
                            onChooseVariant = {
                                actions.onChooseVariant(info.module.packageName)
                            },
                            onSkipVersion = { actions.onSkipVersion(info) },
                            onToggleIgnoreUpdate = {
                                actions.onToggleIgnoreUpdate(info.module.packageName)
                            },
                            preReleasesEnabled =
                                info.module.packageName in state.preReleases,
                            canTrackPreReleases = info.module.hasPreReleases,
                            onTogglePreReleases = {
                                actions.onTogglePreReleases(info.module.packageName)
                            },
                            onUninstall = {
                                actions.onUninstall(
                                    info.installed.packageName,
                                    info.module.title,
                                )
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
                                actions.onOpenModule(
                                    repoPackage ?: module.packageName,
                                )
                            },
                            onChooseVariant = repoPackage?.let { name ->
                                { actions.onChooseVariant(name) }
                            },
                            onToggleIgnoreUpdate = repoPackage?.let { name ->
                                { actions.onToggleIgnoreUpdate(name) }
                            },
                            preReleasesEnabled = repoPackage != null && repoPackage in state.preReleases,
                            canTrackPreReleases = repoPackage != null && repoPackage in state.preReleaseCapable,
                            onTogglePreReleases = repoPackage?.let { name ->
                                { actions.onTogglePreReleases(name) }
                            },
                            onUninstall = {
                                actions.onUninstall(module.packageName, module.label)
                            },
                        )
                    }
                }

                else -> {
                    item {
                        KomiSectionHeader(
                            title = stringResource(R.string.installed_section_unknown, unknown.size),
                            actionLabel = stringResource(R.string.import_link),
                            onAction = actions.onOpenImports,
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
                            onUninstall = {
                                actions.onUninstall(module.packageName, module.label)
                            },
                        )
                    }
                }
            }
        }
    }
}
