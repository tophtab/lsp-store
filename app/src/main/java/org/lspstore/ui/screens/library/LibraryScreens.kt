@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.Module
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.components.EmptyBox
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleAvatar
import org.lspstore.ui.components.VersionChip

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(kind.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (kind == LibraryKind.RECENTLY_VIEWED && state.modules.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAll) { Text(stringResource(R.string.action_clear)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null -> ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.modules.isEmpty() -> EmptyBox(stringResource(kind.emptyRes), Modifier.padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ModuleAvatar(module, size = 44)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = module.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                module.latestRelease?.let {
                    VersionChip(
                        text = it.versionName,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (installed) {
                    VersionChip(
                        text = stringResource(R.string.label_installed),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            when (kind) {
                LibraryKind.FAVOURITES -> IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.library_remove_favourite))
                }
                LibraryKind.HIDDEN -> IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.action_unhide))
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_skipped)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAll) { Text(stringResource(R.string.action_clear)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null -> ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.items.isEmpty() -> EmptyBox(stringResource(R.string.library_empty_skipped), Modifier.padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.packageName }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.library_skipped_version, item.skippedVersionCode) +
                                        (item.latestVersionCode?.let {
                                            stringResource(R.string.library_skipped_latest, it)
                                        } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { viewModel.unskip(item.packageName) }) {
                                Text(stringResource(R.string.action_unskip))
                            }
                        }
                    }
                }
            }
        }
    }
}

