@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.author

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.MODULE_ITEM_FADE_MILLIS
import io.github.tophtab.lspstore.ui.components.MODULE_ITEM_PLACEMENT_MILLIS
import io.github.tophtab.lspstore.ui.components.ModuleCard
import io.github.tophtab.lspstore.ui.components.ModuleListExit
import io.github.tophtab.lspstore.ui.components.rememberModuleListExitState
import io.github.tophtab.lspstore.ui.design.KomiSectionHeader
import io.github.tophtab.lspstore.ui.design.KomiTopBar

@Composable
fun AuthorScreen(author: String, onBack: () -> Unit, onOpenModule: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: AuthorViewModel = viewModel(
        key = "author-$author",
        factory = AuthorViewModel.factory(container, author),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyStaggeredGridState()
    val listExit = rememberModuleListExitState(state.modules, key = author)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = state.author,
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.modules.isEmpty() && !listExit.hasContent ->
                EmptyBox(stringResource(R.string.author_no_modules), Modifier.padding(innerPadding))
            else -> ModuleListExit(listExit, Modifier.fillMaxSize().padding(innerPadding)) { displayed ->
                LazyVerticalStaggeredGrid(
                    state = listState,
                    columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "author-count", span = StaggeredGridItemSpan.FullLine) {
                        KomiSectionHeader(
                            title = pluralStringResource(R.plurals.author_module_count, displayed.size, displayed.size),
                        )
                    }
                    items(displayed, key = { it.packageName }) { module ->
                        ModuleCard(
                            module = module,
                            installed = module.packageName in state.installedPackages,
                            onOpen = { onOpenModule(module.packageName) },
                            onIgnore = { viewModel.ignoreModule(module.packageName) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = null,
                                placementSpec = tween(MODULE_ITEM_PLACEMENT_MILLIS, easing = FastOutSlowInEasing),
                                fadeOutSpec = tween(MODULE_ITEM_FADE_MILLIS, easing = FastOutSlowInEasing),
                            ),
                        )
                    }
                }
            }
        }
    }
}
