@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.ModuleCard
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = state.author,
                subtitle = stringResource(R.string.detail_contributors),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.modules.isEmpty() ->
                EmptyBox(stringResource(R.string.author_no_modules), Modifier.padding(innerPadding))
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    KomiSectionHeader(
                        title = stringResource(R.string.author_module_count, state.modules.size),
                    )
                }
                items(state.modules, key = { it.packageName }) { module ->
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
