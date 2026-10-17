@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.mirrors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.data.remote.Mirror
import org.lspstore.ui.LocalAppContainer

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mirror_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.mirror_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))

            MirrorRow(
                title = stringResource(R.string.mirror_auto),
                subtitle = stringResource(R.string.mirror_auto_desc),
                selected = state.preferred.isBlank(),
                status = null,
                onSelect = { viewModel.select("") },
            )
            HorizontalDivider()

            state.mirrors.forEach { mirror ->
                MirrorRow(
                    title = mirror.name,
                    subtitle = mirror.host,
                    selected = state.preferred == mirror.host,
                    status = when {
                        mirror.host in state.failures -> stringResource(R.string.mirror_unreachable)
                        state.latencies[mirror.host] != null ->
                            stringResource(R.string.mirror_latency, state.latencies.getValue(mirror.host).toInt())
                        state.testing -> stringResource(R.string.mirror_testing)
                        else -> null
                    },
                    onSelect = { viewModel.select(mirror.host) },
                )
                HorizontalDivider()
            }

            Spacer(Modifier.width(8.dp))
            Button(onClick = viewModel::testAll, enabled = !state.testing) {
                Text(
                    stringResource(
                        if (state.testing) R.string.mirror_testing else R.string.mirror_test_all,
                    ),
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
