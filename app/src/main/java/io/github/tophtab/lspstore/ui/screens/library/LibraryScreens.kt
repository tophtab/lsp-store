@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.library

import io.github.tophtab.lspstore.ui.components.LibraryModuleHeading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.EmptyBox
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.ModuleAvatar
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiConstrainedContent
import io.github.tophtab.lspstore.ui.design.KomiDialog
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTextField
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(kind.titleRes),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    if (kind == LibraryKind.RECENTLY_VIEWED && state.modules.isNotEmpty()) {
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
            state.modules.isEmpty() ->
                EmptyBox(stringResource(kind.emptyRes), Modifier.padding(innerPadding))
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
                        LibraryRow(
                            module = module,
                            installed = state.installedCopies[module.packageName],
                            kind = kind,
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
private fun LibraryRow(
    module: Module,
    installed: InstalledModule?,
    kind: LibraryKind,
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
            when (kind) {
                LibraryKind.FAVOURITES -> IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.library_remove_favourite),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                LibraryKind.RECENTLY_VIEWED -> Unit
            }
        }
    }
}

@Composable
fun IgnoredModulesScreen(onBack: () -> Unit, onOpenModule: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: IgnoredModulesViewModel = viewModel(
        factory = IgnoredModulesViewModel.factory(container),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.library_ignored_modules),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    IconButton(onClick = viewModel::openAddKeyword) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.library_add_ignore_keyword),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val nothingYet = !state.isLoading && state.error == null &&
            state.items.isEmpty() && state.keywords.isEmpty()
        if (nothingYet) {
            EmptyBox(
                message = stringResource(R.string.library_empty_ignored_modules),
                modifier = Modifier.padding(innerPadding),
                actionLabel = stringResource(R.string.library_add_ignore_keyword),
                onAction = viewModel::openAddKeyword,
            )
        } else {
            KomiConstrainedContent(
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
                    if (state.keywords.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine, key = "keywords-header") {
                            IgnoredSectionHeader(
                                pluralStringResource(
                                    R.plurals.library_keywords_section,
                                    state.keywords.size,
                                    state.keywords.size,
                                ),
                            )
                        }
                        items(state.keywords, key = { "kw:${it.keyword}" }) { item ->
                            KeywordRow(
                                item = item,
                                onRemove = { viewModel.removeKeyword(item.keyword) },
                            )
                        }
                    }
                    when {
                        // Keyword rules are local; only the module titles need the
                        // index, so a failed index load must not take the whole
                        // screen (and the keyword editor) down with it.
                        state.isLoading -> item(span = StaggeredGridItemSpan.FullLine, key = "loading") {
                            LoadingBox(Modifier.fillMaxWidth().height(160.dp))
                        }

                        state.error != null ->
                            item(span = StaggeredGridItemSpan.FullLine, key = "error") {
                                ErrorBox(
                                    message = state.error!!,
                                    onRetry = viewModel::refresh,
                                    modifier = Modifier.fillMaxWidth().height(200.dp),
                                )
                            }

                        state.items.isEmpty() ->
                            item(span = StaggeredGridItemSpan.FullLine, key = "modules-empty") {
                                KomiText(
                                    text = stringResource(R.string.library_empty_ignored_modules),
                                    role = KomiTextRole.Body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                )
                            }

                        else -> {
                            item(span = StaggeredGridItemSpan.FullLine, key = "modules-header") {
                                IgnoredSectionHeader(
                                    pluralStringResource(
                                        R.plurals.library_modules_section,
                                        state.items.size,
                                        state.items.size,
                                    ),
                                )
                            }
                            items(state.items, key = { it.packageName }) { item ->
                                KomiSurface(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onOpenModule(item.packageName) },
                                    contentPadding = PaddingValues(14.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LibraryModuleHeading(
                                            title = item.title,
                                            packageName = item.packageName,
                                            modifier = Modifier.weight(1f),
                                        )
                                        KomiButton(
                                            onClick = { viewModel.restore(item.packageName) },
                                            label = stringResource(R.string.action_unskip),
                                            variant = KomiButtonVariant.Outline,
                                            size = KomiButtonSize.Small,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.isAddingKeyword) {
        AddIgnoreKeywordDialog(
            draft = state.keywordDraft,
            error = state.keywordError,
            matchCount = state.keywordMatchCount,
            onDraftChange = viewModel::onKeywordDraftChange,
            onConfirm = viewModel::addKeyword,
            onDismiss = viewModel::dismissAddKeyword,
        )
    }
}

@Composable
private fun IgnoredSectionHeader(text: String) {
    KomiText(
        text = text,
        role = KomiTextRole.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun KeywordRow(item: IgnoredKeywordItem, onRemove: () -> Unit) {
    KomiSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                KomiText(
                    text = item.keyword,
                    role = KomiTextRole.Title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.matchCount?.let { count ->
                    KomiText(
                        text = pluralStringResource(R.plurals.library_ignore_keyword_matches, count, count),
                        role = KomiTextRole.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.library_remove_ignore_keyword),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddIgnoreKeywordDialog(
    draft: String,
    error: String?,
    matchCount: Int?,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    KomiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.library_add_ignore_keyword),
        confirmLabel = stringResource(R.string.action_add),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KomiTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.library_ignore_keyword_placeholder),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
            )
            KomiText(
                text = stringResource(R.string.library_ignore_keyword_hint),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            val feedback = error ?: matchCount?.let {
                pluralStringResource(R.plurals.library_ignore_keyword_preview, it, it)
            }
            if (feedback != null) {
                KomiText(
                    text = feedback,
                    role = KomiTextRole.Label,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 12.sp,
                )
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
                        KomiSurface(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LibraryModuleHeading(
                                    title = item.title,
                                    packageName = item.packageName,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    KomiText(
                                        text = if (item.isModuleIgnored) {
                                            stringResource(R.string.library_ignored_module)
                                        } else {
                                            stringResource(
                                                R.string.library_skipped_version,
                                                item.skippedVersion ?: "?",
                                            )
                                        } + (item.latestVersion?.let {
                                            stringResource(R.string.library_skipped_latest, it)
                                        } ?: ""),
                                        role = KomiTextRole.Label,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                KomiButton(
                                    onClick = { viewModel.restore(item) },
                                    label = stringResource(
                                        if (item.isModuleIgnored) {
                                            R.string.action_restore_update
                                        } else {
                                            R.string.action_unskip
                                        },
                                    ),
                                    variant = KomiButtonVariant.Outline,
                                    size = KomiButtonSize.Small,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
