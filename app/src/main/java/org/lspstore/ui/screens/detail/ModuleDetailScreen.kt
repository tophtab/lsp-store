@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.detail

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.domain.model.ApkVerification
import org.lspstore.domain.model.ModuleDetail
import org.lspstore.domain.model.Release
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.components.ErrorBox
import org.lspstore.ui.components.LoadingBox
import org.lspstore.ui.components.ModuleAvatar
import org.lspstore.ui.components.VersionChip
import org.lspstore.ui.components.formatRelativeTime

@Composable
fun ModuleDetailScreen(packageName: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: ModuleDetailViewModel = viewModel(
        factory = ModuleDetailViewModel.factory(container, packageName),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.detail?.module?.title ?: packageName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                menuOpen = false
                                state.detail?.module?.let { module ->
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, module.title)
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "${module.title}\n${module.url ?: module.packageName}",
                                                )
                                            },
                                            "Share module",
                                        ),
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.isHidden) "Unhide" else "Hide") },
                            onClick = { menuOpen = false; viewModel.toggleHidden() },
                        )
                        if (state.latestVersionCode != null) {
                            if (state.isUpdateSkipped) {
                                DropdownMenuItem(
                                    text = { Text("Unskip this version") },
                                    onClick = { menuOpen = false; viewModel.unskipVersion() },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Skip this version") },
                                    onClick = { menuOpen = false; viewModel.skipThisVersion() },
                                )
                            }
                        }
                        state.installed?.let {
                            DropdownMenuItem(
                                text = { Text("Open app") },
                                onClick = {
                                    menuOpen = false
                                    context.packageManager.getLaunchIntentForPackage(packageName)
                                        ?.let { intent -> context.startActivity(intent) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("App info") },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:$packageName"),
                                        ),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Uninstall") },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(
                                        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")),
                                    )
                                },
                            )
                        }
                        state.detail?.sourceUrl?.let { url ->
                            DropdownMenuItem(
                                text = { Text("Open source") },
                                onClick = { menuOpen = false; uriHandler.openUri(url) },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val detail = state.detail
        when {
            state.isLoading -> LoadingBox(Modifier.padding(innerPadding))
            state.error != null -> ErrorBox(state.error!!, viewModel::load, Modifier.padding(innerPadding))
            detail != null -> DetailContent(
                state = state,
                detail = detail,
                onInstallLatest = viewModel::installLatest,
                onInstallAsset = viewModel::installAsset,
                onToggleFavourite = viewModel::toggleFavourite,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    state.pendingMismatch?.let { mismatch ->
        SignatureMismatchDialog(
            mismatch = mismatch,
            onInstallAnyway = viewModel::installAnyway,
            onDismiss = viewModel::dismissMismatch,
        )
    }
}

@Composable
private fun SignatureMismatchDialog(
    mismatch: ApkVerification.Mismatch,
    onInstallAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signature mismatch") },
        text = {
            Column {
                Text(
                    "The downloaded APK is signed with a different certificate than the installed module. " +
                        "Android will refuse this update unless the old app is uninstalled first.",
                )
                Spacer(Modifier.height(12.dp))
                Text("Installed signer", style = MaterialTheme.typography.labelMedium)
                mismatch.installedSigners.forEach { Text(signerLabel(it.sha256, it.subject), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Text("Downloaded signer", style = MaterialTheme.typography.labelMedium)
                mismatch.signers.forEach { Text(signerLabel(it.sha256, it.subject), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstallAnyway) { Text("Install anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun signerLabel(sha256: String, subject: String?): String {
    val shortHash = sha256.take(16)
    return if (subject.isNullOrBlank()) shortHash else "$shortHash\n$subject"
}

@Composable
private fun DetailContent(
    state: ModuleDetailUiState,
    detail: ModuleDetail,
    onInstallLatest: () -> Unit,
    onInstallAsset: (ReleaseAsset) -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(
            state = state,
            detail = detail,
            onInstallLatest = onInstallLatest,
            onToggleFavourite = onToggleFavourite,
        )
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Readme") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Releases") },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ReadmeView(
                    html = detail.readmeHtml,
                    baseUrl = detail.module.url,
                )
                else -> ReleasesList(
                    releases = detail.releasesFor(state.channel),
                    onInstallAsset = onInstallAsset,
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    state: ModuleDetailUiState,
    detail: ModuleDetail,
    onInstallLatest: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val module = detail.module
    val uriHandler = LocalUriHandler.current
    val latest = module.latestFor(state.channel)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModuleAvatar(module, size = 56)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = module.packageName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (module.stars > 0) {
                        Icon(Icons.Filled.Star, null, Modifier.size(14.dp))
                        Text(" ${module.stars}  ", style = MaterialTheme.typography.labelSmall)
                    }
                    module.contributor?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onToggleFavourite) {
                Icon(
                    imageVector = if (state.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favourite",
                    tint = if (state.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        module.summary?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            module.latestRelease?.let {
                VersionChip(it.versionName, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
            module.latestBetaRelease?.let {
                VersionChip(it.versionName, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }

        Spacer(Modifier.height(12.dp))
        state.installed?.let { installed ->
            Text(
                text = "Installed: ${installed.versionName ?: "?"} (${installed.versionCode})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.updateAvailable && latest != null) {
            Text(
                text = "Update available: ${latest.versionName} (${latest.versionCode})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(12.dp))
        val buttonLabel = when {
            latest == null -> "No release for this channel"
            state.updateAvailable -> "Update to ${latest.versionName}"
            state.installed != null -> "Reinstall ${latest.versionName}"
            else -> "Install ${latest.versionName}"
        }
        Button(
            onClick = onInstallLatest,
            enabled = latest != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Working...")
            } else {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel)
            }
        }
        state.downloadProgress?.let { progress ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            detail.sourceUrl?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text("Source") }
            }
            detail.homepageUrl?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text("Website") }
            }
            module.url?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text("Repo") }
            }
        }
    }
}

@Composable
private fun ReleasesList(
    releases: List<Release>,
    onInstallAsset: (ReleaseAsset) -> Unit,
) {
    if (releases.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No releases", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(releases, key = { it.tagName.ifBlank { it.hashCode().toString() } }) { release ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = release.version?.versionName ?: release.tagName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (release.isPrerelease) {
                        Spacer(Modifier.width(6.dp))
                        VersionChip("beta", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatRelativeTime(release.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                release.name?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                release.apkAssets.forEach { asset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(asset.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "${formatSize(asset.size)} - ${asset.downloadCount} downloads",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onInstallAsset(asset) }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                    }
                }
                if (release.apkAssets.isEmpty()) {
                    Text(
                        text = "No APK assets",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReadmeView(html: String?, baseUrl: String?) {
    if (html.isNullOrBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No readme", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                baseUrl ?: "https://modules.lsposed.org/",
                wrapHtml(html),
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}

private fun wrapHtml(body: String): String = """
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body { font-family: sans-serif; margin: 12px; line-height: 1.5; word-wrap: break-word; }
      img, table { max-width: 100%; height: auto; }
      pre { white-space: pre-wrap; }
    </style>
    </head><body>$body</body></html>
""".trimIndent()

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "?"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
}
