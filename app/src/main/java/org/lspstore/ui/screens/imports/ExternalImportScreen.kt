@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.imports

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.Module
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.components.EmptyBox
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleAvatar
import org.lspstore.ui.design.KomiButton
import org.lspstore.ui.design.KomiButtonSize
import org.lspstore.ui.design.KomiButtonVariant
import org.lspstore.ui.design.KomiInfoChip
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.design.KomiTextField
import org.lspstore.ui.design.KomiTopBar
import org.lspstore.ui.theme.KomiTextRole

@Composable
fun ExternalImportScreen(onBack: () -> Unit, onOpenModule: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: ExternalImportViewModel = viewModel(factory = ExternalImportViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.import_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null ->
                ErrorBox(state.error!!, viewModel::refresh, Modifier.padding(innerPadding))
            else -> Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                KomiText(
                    text = stringResource(R.string.import_desc),
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.unknown.isEmpty()) {
                    EmptyBox(stringResource(R.string.import_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.unknown, key = { it.installed.packageName }) { item ->
                            UnknownRow(
                                item = item,
                                onOpen = {
                                    val linked = item.linkedModule
                                    if (linked != null) {
                                        onOpenModule(linked.packageName)
                                    } else {
                                        viewModel.openPicker(item.installed.packageName)
                                    }
                                },
                                onLink = { viewModel.openPicker(item.installed.packageName) },
                                onUnlink = { viewModel.unlink(item.installed.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.pickerOpen) {
        ModulePickerDialog(
            state = state,
            onQueryChange = viewModel::onPickerQueryChange,
            onSelect = viewModel::link,
            onDismiss = viewModel::closePicker,
        )
    }
}

@Composable
private fun UnknownRow(
    item: UnknownModuleItem,
    onOpen: () -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    KomiSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            KomiText(
                text = item.installed.label,
                role = KomiTextRole.Title,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            KomiText(
                text = item.installed.packageName,
                role = KomiTextRole.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            KomiText(
                text = stringResource(R.string.import_installed_version, item.installed.versionName ?: "?"),
                role = KomiTextRole.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (item.linkedModule != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KomiInfoChip(stringResource(R.string.import_linked_to))
                    Spacer(Modifier.width(8.dp))
                    KomiText(
                        text = item.linkedModule.title,
                        role = KomiTextRole.Body,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    KomiButton(
                        onClick = onUnlink,
                        label = stringResource(R.string.import_unlink),
                        variant = KomiButtonVariant.Text,
                        size = KomiButtonSize.Small,
                    )
                }
            } else {
                KomiButton(
                    onClick = onLink,
                    label = stringResource(R.string.import_link),
                    variant = KomiButtonVariant.Outline,
                    size = KomiButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun ModulePickerDialog(
    state: ExternalImportUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        KomiSurface(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            elevation = 8.dp,
        ) {
            Column {
                KomiText(
                    text = stringResource(R.string.import_pick_module),
                    role = KomiTextRole.Title,
                )
                Spacer(Modifier.height(10.dp))
                KomiTextField(
                    value = state.pickerQuery,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.import_search_hint),
                    leadingIcon = Icons.Filled.Search,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.pickerResults, key = { it.packageName }) { module ->
                        PickerRow(module = module, onClick = { onSelect(module.packageName) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                KomiButton(
                    onClick = onDismiss,
                    label = stringResource(R.string.action_cancel),
                    variant = KomiButtonVariant.Text,
                    size = KomiButtonSize.Small,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(module: Module, onClick: () -> Unit) {
    KomiSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = 0.dp,
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModuleAvatar(module, size = 36)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                KomiText(
                    text = module.title,
                    role = KomiTextRole.Body,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KomiText(
                    text = module.packageName,
                    role = KomiTextRole.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
