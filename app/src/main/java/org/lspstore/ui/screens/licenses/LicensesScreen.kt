package org.lspstore.ui.screens.licenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lspstore.R

data class LicenseEntry(
    val name: String,
    val license: String,
    val url: String,
)

private val libraries = listOf(
    LicenseEntry("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    LicenseEntry("Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    LicenseEntry("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    LicenseEntry("Compose Multiplatform / Jetpack Compose", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
    LicenseEntry("AndroidX Core / Lifecycle / Navigation", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX WorkManager", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX DataStore", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("Ktor", "Apache-2.0", "https://github.com/ktorio/ktor"),
    LicenseEntry("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    LicenseEntry("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
    LicenseEntry("Material Icons", "Apache-2.0", "https://github.com/google/material-design-icons"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            items(libraries) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(entry.url) } }
                        .padding(vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = entry.license,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider()
            }
            item {
                Text(
                    text = stringResource(R.string.licenses_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
