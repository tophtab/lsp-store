@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.components.EmptyBox
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleCard

@Composable
fun AuthorScreen(author: String, onBack: () -> Unit, onOpenModule: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: AuthorViewModel = viewModel(
        key = "author-$author",
        factory = AuthorViewModel.factory(container, author),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null -> ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            state.modules.isEmpty() -> EmptyBox(stringResource(R.string.author_no_modules), Modifier.padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.author_module_count, state.modules.size),
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
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
