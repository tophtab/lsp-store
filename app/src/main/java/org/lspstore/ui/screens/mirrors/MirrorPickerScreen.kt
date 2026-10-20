@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.mirrors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.design.KomiButton
import org.lspstore.ui.design.KomiConstrainedContent
import org.lspstore.ui.design.KomiDivider
import org.lspstore.ui.design.KomiSettingsGroup
import org.lspstore.ui.design.KomiSettingsSectionHead
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.design.KomiTopBar
import org.lspstore.ui.theme.KomiTextRole

@Composable
fun MirrorPickerScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: MirrorPickerViewModel = viewModel(factory = MirrorPickerViewModel.factory(container))
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
                title = stringResource(R.string.mirror_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        KomiConstrainedContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
            ) {
                KomiText(
                    text = stringResource(R.string.mirror_desc),
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                KomiSettingsSectionHead(stringResource(R.string.mirror_title))
                KomiSettingsGroup {
                    MirrorRow(
                        title = stringResource(R.string.mirror_auto),
                        subtitle = stringResource(R.string.mirror_auto_desc),
                        selected = state.preferred.isBlank(),
                        status = null,
                        onSelect = { viewModel.select("") },
                    )
                    state.mirrors.forEachIndexed { index, mirror ->
                        MirrorRow(
                            title = mirror.name,
                            subtitle = mirror.host,
                            selected = state.preferred == mirror.host,
                            status = when {
                                mirror.host in state.failures ->
                                    stringResource(R.string.mirror_unreachable)

                                state.latencies[mirror.host] != null ->
                                    stringResource(
                                        R.string.mirror_latency,
                                        state.latencies.getValue(mirror.host).toInt(),
                                    )

                                state.testing -> stringResource(R.string.mirror_testing)
                                else -> null
                            },
                            onSelect = { viewModel.select(mirror.host) },
                            last = index == state.mirrors.lastIndex,
                        )
                    }
                }
                KomiButton(
                    onClick = viewModel::testAll,
                    label = stringResource(
                        if (state.testing) R.string.mirror_testing else R.string.mirror_test_all,
                    ),
                    modifier = Modifier.padding(top = 14.dp),
                    enabled = !state.testing,
                    loading = state.testing,
                )
            }
        }
    }
}

@Composable
private fun MirrorRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    status: String?,
    onSelect: () -> Unit,
    last: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                KomiText(
                    text = title,
                    role = KomiTextRole.Body,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                KomiText(
                    text = subtitle,
                    role = KomiTextRole.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            status?.let {
                KomiText(
                    text = it,
                    role = KomiTextRole.Mono,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        if (!last) {
            KomiDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
