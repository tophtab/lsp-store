@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.Module
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.components.EmptyBox
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleCardCompact

@Composable
fun HomeScreen(
    onOpenModule: (String) -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavourites: () -> Unit,
    onOpenRecentlyViewed: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.nav_search))
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null -> ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.recentlyUpdated.isEmpty() -> EmptyBox(stringResource(R.string.home_no_modules), Modifier.padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (state.updateCount > 0) {
                    item {
                        UpdatesBanner(count = state.updateCount, onClick = onOpenUpdates)
                    }
                }
                item {
                    Section(
                        title = stringResource(R.string.home_section_recently_updated),
                        modules = state.recentlyUpdated,
                        installed = state.installedPackages,
                        onOpenModule = onOpenModule,
                    )
                }
                item {
                    Section(
                        title = stringResource(R.string.home_section_most_starred),
                        modules = state.mostStarred,
                        installed = state.installedPackages,
                        onOpenModule = onOpenModule,
                    )
                }
                item {
                    Section(
                        title = stringResource(R.string.home_section_beta),
                        modules = state.betaBuilds,
                        installed = state.installedPackages,
                        onOpenModule = onOpenModule,
                    )
                }
                if (state.favourites.isNotEmpty()) {
                    item {
                        Section(
                            title = stringResource(R.string.home_section_favourites),
                            modules = state.favourites,
                            installed = state.installedPackages,
                            onOpenModule = onOpenModule,
                            action = stringResource(R.string.home_see_all) to onOpenFavourites,
                        )
                    }
                }
                if (state.recentlyViewed.isNotEmpty()) {
                    item {
                        Section(
                            title = stringResource(R.string.home_section_recently_viewed),
                            modules = state.recentlyViewed,
                            installed = state.installedPackages,
                            onOpenModule = onOpenModule,
                            action = stringResource(R.string.home_see_all) to onOpenRecentlyViewed,
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onOpenSearch,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.home_browse_all))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesBanner(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.home_updates_banner, count),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.home_updates_banner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    modules: List<Module>,
    installed: Set<String>,
    onOpenModule: (String) -> Unit,
    action: Pair<String, () -> Unit>? = null,
) {
    if (modules.isEmpty()) return
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(label) }
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(modules, key = { it.packageName }) { module ->
                ModuleCardCompact(
                    module = module,
                    installed = module.packageName in installed,
                    onClick = { onOpenModule(module.packageName) },
                )
            }
        }
    }
}
