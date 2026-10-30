@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
    val focusManager = LocalFocusManager.current

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

    // A completed load is a new dataset, so the list starts at its first item
    // instead of keeping an offset that belonged to the old data. The first
    // load of the process counts too. The view model hands that scroll out once
    // per load; returning from a module page with the same tick does not replay
    // it. If a load finishes while the user is on a module page, the new
    // dataset is shown from the top when the list returns.
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
    // the user left, exactly like the refresh scroll above. The reset also runs
    // when the new query has no hits: the state is reset while the empty box is
    // showing, so a later refresh that adds hits does not reveal them mid-list.
    LaunchedEffect(state.searchTick) {
        if (!viewModel.consumeSearchScroll()) return@LaunchedEffect
        if (state.searchQuery.isNotBlank()) {
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

    // The segmented control is both the shelf picker and the search ordering.
    // Switching it shows a different view of the list, so start that view at
    // the top. The sentinel is saveable so a trip to a module page (same
    // selection, fresh composition) keeps the position instead.
    var settledTab by rememberSaveable { mutableIntStateOf(resolvedTab) }
    LaunchedEffect(resolvedTab) {
        if (resolvedTab != settledTab) {
            settledTab = resolvedTab
            listState.scrollToItem(0)
            searchListState.scrollToItem(0)
        }
    }

    val searching = state.searchQuery.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = topContentWindowInsets(),
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.recentlyUpdated.isEmpty() -> EmptyBox(
                message = stringResource(R.string.home_no_modules),
                modifier = Modifier.padding(innerPadding),
                actionLabel = stringResource(R.string.action_refresh),
                onAction = viewModel::refresh,
            )
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    KomiTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
                        placeholder = stringResource(R.string.import_search_hint),
                        leadingIcon = Icons.Filled.Search,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
