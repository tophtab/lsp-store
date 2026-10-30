@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.ModuleCard
import io.github.tophtab.lspstore.ui.design.KomiSegment
import io.github.tophtab.lspstore.ui.design.KomiSegmented
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.design.topContentWindowInsets
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
fun HomeScreen(
    onOpenModule: (String) -> Unit,
    reselectTick: Int = 0,
) {
    val container = LocalAppContainer.current
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyStaggeredGridState()
    val searchListState = rememberLazyStaggeredGridState()

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) {
            if (state.searchQuery.isNotBlank()) {
                searchListState.animateScrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
            viewModel.refresh()
        }
    }

    // A finished refresh returns to the top: the newest modules are there, and
    // staying on the old offset made the refresh look like a no-op. The startup
    // refresh counts too, so opening the app always lands on the newest modules
    // instead of wherever the list was left last time. The view model hands that
    // scroll out once per refresh, so coming back from a module page (same tick,
    // fresh composition) keeps the position the user left.
    LaunchedEffect(state.refreshTick) {
        if (!viewModel.consumeRefreshScroll()) return@LaunchedEffect
        if (state.searchQuery.isNotBlank()) {
            if (state.searchResults.isNotEmpty()) searchListState.animateScrollToItem(0)
        } else if (state.recentlyUpdated.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // A new query is a new result list; the previous offset belongs to the old
    // one. The view model hands the reset out once per query change, so coming
    // back from a module page (same tick, fresh composition) keeps the position
    // the user left, exactly like the refresh scroll above.
    LaunchedEffect(state.searchTick) {
        if (!viewModel.consumeSearchScroll()) return@LaunchedEffect
        if (state.searchQuery.isNotBlank() && state.searchResults.isNotEmpty()) {
            searchListState.scrollToItem(0)
        }
    }

    val tabs = buildList {
        add(
            HomeTab(
                kind = HomeTabKind.UPDATED,
                label = stringResource(R.string.home_section_recently_updated),
                modules = state.recentlyUpdated,
            ),
        )
        add(
            HomeTab(
                kind = HomeTabKind.STARRED,
                label = stringResource(R.string.home_section_most_starred),
                modules = state.mostStarred,
            ),
        )
    }
    val resolvedTab = state.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val searching = state.searchQuery.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = topContentWindowInsets(),
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.nav_repository),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.recentlyUpdated.isEmpty() ->
                EmptyBox(stringResource(R.string.home_no_modules), Modifier.padding(innerPadding))
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    KomiTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        placeholder = stringResource(R.string.import_search_hint),
                        leadingIcon = Icons.Filled.Search,
                        singleLine = true,
                        trailing = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearSearch) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.action_clear),
                                    )
                                }
                            }
                        },
                    )

                    KomiSegmented(
                        segments = tabs.map { KomiSegment(it.label) },
                        selectedIndex = resolvedTab,
                        onSelect = viewModel::selectTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )

                    if (searching) {
                        val searchResults = if (resolvedTab == 1) {
                            // The "Most starred" tab also controls search ordering.
                            state.searchResults.sortedByDescending { it.stars }
                        } else {
                            // HomeViewModel already sorts by last update.
                            state.searchResults
                        }
                        if (searchResults.isEmpty()) {
                            EmptyBox(stringResource(R.string.search_empty))
                        } else {
                            KomiText(
                                text = stringResource(R.string.search_count, searchResults.size),
                                role = KomiTextRole.Label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                maxLines = 1,
                            )
                            LazyVerticalStaggeredGrid(
                                state = searchListState,
                                columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = 32.dp,
                                ),
                                verticalItemSpacing = 10.dp,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(searchResults, key = { it.packageName }) { module ->
                                    ModuleCard(
                                        module = module,
                                        installed = module.packageName in state.installedPackages,
                                        onOpen = { onOpenModule(module.packageName) },
                                    )
                                }
                            }
                        }
                    } else {
                        LazyVerticalStaggeredGrid(
                            state = listState,
                            columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                bottom = 32.dp,
                            ),
                            verticalItemSpacing = 10.dp,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(tabs[resolvedTab].modules, key = { it.packageName }) { module ->
                                ModuleCard(
                                    module = module,
                                    installed = module.packageName in state.installedPackages,
                                    onOpen = { onOpenModule(module.packageName) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class HomeTabKind { UPDATED, STARRED }

private data class HomeTab(
    val kind: HomeTabKind,
    val label: String,
    val modules: List<Module>,
)
