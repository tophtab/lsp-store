@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.ModuleAvatar
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
fun LibraryListScreen(
    kind: LibraryKind,
    onBack: () -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: LibraryListViewModel = viewModel(
        key = "library-${kind.name}",
        factory = LibraryListViewModel.factory(container, kind),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(kind.titleRes),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    if (kind == LibraryKind.RECENTLY_VIEWED && state.modules.isNotEmpty()) {
                        KomiButton(
                            onClick = viewModel::clearAll,
                            label = stringResource(R.string.action_clear),
                            variant = KomiButtonVariant.Text,
                            size = KomiButtonSize.Small,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.modules.isEmpty() ->
                EmptyBox(stringResource(kind.emptyRes), Modifier.padding(innerPadding))
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.modules, key = { it.packageName }) { module ->
                    LibraryRow(
                        module = module,
                        installed = module.packageName in state.installedPackages,
                        kind = kind,
                        onClick = { onOpenModule(module.packageName) },
                        onRemove = { viewModel.remove(module.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    module: Module,
    installed: Boolean,
    kind: LibraryKind,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    KomiSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                ModuleAvatar(
                    module,
                    size = 44,
                    installedPackage = module.packageName,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                KomiText(
                    text = module.title,
                    role = KomiTextRole.Title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KomiText(
                    text = module.packageName,
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (installed) {
                        KomiInfoChip(stringResource(R.string.label_installed))
                    }
                    module.latestRelease?.let { KomiInfoChip(it.versionName) }
                }
            }
            when (kind) {
                LibraryKind.FAVOURITES -> IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.library_remove_favourite),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                LibraryKind.RECENTLY_VIEWED -> Unit
            }
        }
    }
}

@Composable
fun SkippedUpdatesScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: SkippedUpdatesViewModel = viewModel(factory = SkippedUpdatesViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.library_skipped),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    if (state.items.isNotEmpty()) {
                        KomiButton(
                            onClick = viewModel::clearAll,
                            label = stringResource(R.string.action_clear),
                            variant = KomiButtonVariant.Text,
                            size = KomiButtonSize.Small,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.items.isEmpty() ->
                EmptyBox(stringResource(R.string.library_empty_skipped), Modifier.padding(innerPadding))
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.packageName }) { item ->
                    KomiSurface(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                KomiText(
                                    text = item.title,
                                    role = KomiTextRole.Title,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                KomiText(
                                    text = item.packageName,
                                    role = KomiTextRole.Body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                KomiText(
                                    text = if (item.isModuleIgnored) {
                                        stringResource(R.string.library_ignored_module)
                                    } else {
                                        stringResource(
                                            R.string.library_skipped_version,
                                            item.skippedVersion ?: "?",
                                        )
                                    } + (item.latestVersion?.let {
                                        stringResource(R.string.library_skipped_latest, it)
                                    } ?: ""),
                                    role = KomiTextRole.Label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            KomiButton(
                                onClick = { viewModel.restore(item) },
                                label = stringResource(
                                    if (item.isModuleIgnored) {
                                        R.string.action_restore_update
                                    } else {
                                        R.string.action_unskip
                                    },
                                ),
                                variant = KomiButtonVariant.Outline,
                                size = KomiButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}
