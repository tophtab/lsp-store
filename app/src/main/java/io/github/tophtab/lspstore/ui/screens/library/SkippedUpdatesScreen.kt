package io.github.tophtab.lspstore.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

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
                    items(state.items, key = { it.packageName }) { item ->
                        LibraryRestoreCard(
                            title = item.title,
                            packageName = item.packageName,
                            restoreLabel = stringResource(if (item.isModuleIgnored) R.string.action_restore_update else R.string.action_unskip),
                            onRestore = { viewModel.restore(item) },
                        ) {
                            KomiText(
                                text = if (item.isModuleIgnored) {
                                    stringResource(R.string.library_ignored_module)
                                } else {
                                    stringResource(R.string.library_skipped_version, item.skippedVersion ?: "?")
                                } + (item.latestVersion?.let { stringResource(R.string.library_skipped_latest, it) } ?: ""),
                                role = KomiTextRole.Label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
