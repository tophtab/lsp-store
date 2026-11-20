package io.github.tophtab.lspstore.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.LibraryModuleHeading
import io.github.tophtab.lspstore.ui.components.ModuleAvatar
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiEmpty
import io.github.tophtab.lspstore.ui.design.KomiError
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiLoading
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiTopBar

@Composable
fun FavouritesScreen(
    onBack: () -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: FavouritesViewModel = viewModel(factory = FavouritesViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.library_favourites),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> KomiLoading(Modifier.padding(innerPadding))
            state.error != null ->
                KomiError(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.modules.isEmpty() ->
                KomiEmpty(stringResource(R.string.library_empty_favourites), Modifier.padding(innerPadding))
            else -> KomiConstrainedContent(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                maxWidth = 1120.dp,
            ) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 320.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.modules, key = { it.packageName }) { module ->
                        FavouriteRow(
                            module = module,
                            installed = state.installedCopies[module.packageName],
                            onClick = { onOpenModule(module.packageName) },
                            onRemove = { viewModel.remove(module.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavouriteRow(
    module: Module,
    installed: InstalledModule?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    KomiSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed != null) {
                ModuleAvatar(
                    module,
                    size = 44,
                    installed = installed,
                )
                Spacer(Modifier.width(12.dp))
            }
            LibraryModuleHeading(
                title = module.title,
                packageName = module.packageName,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (installed != null) {
                        KomiInfoChip(stringResource(R.string.label_installed))
                    }
                    module.latestRelease?.let { KomiInfoChip(it.versionName) }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.library_remove_favourite),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
