@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lspstore.R
import org.lspstore.ui.design.KomiConstrainedContent
import org.lspstore.ui.design.KomiDivider
import org.lspstore.ui.design.KomiSettingsSectionHead
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.design.KomiTopBar
import org.lspstore.ui.theme.KomiTextRole

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
    LicenseEntry("Geist / Geist Mono", "SIL OFL 1.1", "https://github.com/vercel/geist-font"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.licenses_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { innerPadding ->
        KomiConstrainedContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                item {
                    KomiText(
                        text = stringResource(R.string.licenses_intro),
                        role = KomiTextRole.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item { KomiSettingsSectionHead(stringResource(R.string.licenses_title)) }
                item {
                    KomiSurface {
                        Column {
                            libraries.forEachIndexed { index, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { runCatching { uriHandler.openUri(entry.url) } }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        KomiText(
                                            text = entry.name,
                                            role = KomiTextRole.Body,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        KomiText(
                                            text = entry.url,
                                            role = KomiTextRole.Mono,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    KomiText(
                                        text = entry.license,
                                        role = KomiTextRole.Label,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                }
                                if (index != libraries.lastIndex) {
                                    KomiDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    KomiText(
                        text = stringResource(R.string.licenses_app),
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}
