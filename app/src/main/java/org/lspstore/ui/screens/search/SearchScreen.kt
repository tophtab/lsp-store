@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.UpdateChannel
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.labelRes
import org.lspstore.ui.components.EmptyBox
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleCard

@Composable
fun SearchScreen(onOpenModule: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_search)) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(order.labelRes())) },
                                    onClick = {
                                        viewModel.onSortChange(order)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder, state.totalCount)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.recordQuery() }),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UpdateChannel.entries.forEach { channel ->
                    FilterChip(
                        selected = state.selectedChannel == channel,
                        onClick = { viewModel.onChannelChange(channel) },
                        label = { Text(stringResource(channel.labelRes())) },
                    )
                }
                FilterChip(
                    selected = state.showFavouritesOnly,
                    onClick = viewModel::toggleFavouritesOnly,
                    label = { Text(stringResource(R.string.search_favourites)) },
                )
            }

            if (state.query.isBlank() && state.history.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.search_recent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearHistory) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.history.forEach { entry ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.onQueryChange(entry) },
                            label = { Text(entry) },
                        )
                    }
                }
            }

            when {
                state.isLoading -> LoadingBox()
                state.error != null -> ErrorBox(message = state.error!!, onRetry = viewModel::refresh)
                state.modules.isEmpty() -> EmptyBox(stringResource(R.string.search_empty))
                else -> {
                    Text(
                        text = stringResource(R.string.search_count, state.modules.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.modules, key = { it.packageName }) { module ->
                            ModuleCard(
                                module = module,
                                installed = module.packageName in state.installedPackages,
                                isFavourite = module.packageName in state.favourites,
                                onOpen = { onOpenModule(module.packageName) },
                                onToggleFavourite = { viewModel.toggleFavourite(module.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}
