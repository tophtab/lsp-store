@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.PullToRefreshBox
import io.github.tophtab.lspstore.ui.components.SnackbarMessageEffect
import io.github.tophtab.lspstore.ui.components.UninstallConfirmDialog
import io.github.tophtab.lspstore.ui.design.KomiTopBar

@Composable
fun ModuleDetailScreen(packageName: String, onBack: () -> Unit, onOpenAuthor: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: ModuleDetailViewModel = viewModel(
        factory = ModuleDetailViewModel.factory(container, packageName),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // komi's detail bar keeps the title empty while the hero is on screen, since
    // the module identity is in the hero. Once the hero scrolls away the bar
    // takes the name over, so the page never loses its context.
    val heroScrolledAway by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val uninstallLabel = state.installed?.label ?: state.detail?.module?.title ?: packageName
    // A silent (root) uninstall would delete without any system prompt, so ask
    // here first; without root the system confirmation page is the prompt.
    var uninstallConfirm by remember { mutableStateOf(false) }
    fun requestUninstall() {
        if (state.rootInstall) {
            uninstallConfirm = true
        } else {
            viewModel.uninstall()
        }
    }
    val sourceUrl = state.detail?.sourceUrl ?: state.detail?.module?.url
    val canTranslate = state.detail?.let { detail ->
        !detail.module.summary.isNullOrBlank() ||
            !detail.readmeHtml.isNullOrBlank() ||
            listOf(detail.releases, detail.betaReleases, detail.snapshotReleases)
                .any { releases -> releases.any { !it.descriptionHtml.isNullOrBlank() } }
    } == true
    SnackbarMessageEffect(
        message = state.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // komi's detail bar keeps the title empty while the hero is visible;
            // the module identity lives in the hero below. A single action plus
            // the overflow menu mirrors KomiActionRow(maxVisible = 1).
            KomiTopBar(
                title = if (heroScrolledAway) {
                    state.detail?.module?.title.orEmpty()
                } else {
                    ""
                },
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    if (canTranslate) {
                        val translateDescription = stringResource(
                            if (state.showTranslation) {
                                R.string.detail_show_original
                            } else {
                                R.string.detail_translate_page
                            },
                        )
                        // A toggle button, not a plain action: the checked state
                        // is what says "the page is translated", and TalkBack
                        // announces it. The checked colours are the cyan
                        // fill + onPrimary pair, the only role pairing in this
                        // palette that keeps the icon at full contrast (11:1);
                        // tinting the bare icon with `primary` measured 1.13:1
                        // on the light surface and read as disabled.
                        // The checked fill is a rounded square, not the default
                        // circle: it mirrors the bottom bar's selected-item
                        // indicator, whose 16dp pill corners become a square
                        // silhouette around this square icon. `shapes.medium`
                        // is that same 16dp radius, and it is also the corner
                        // Material 3 gives a selected icon-only button.
                        IconToggleButton(
                            checked = state.showTranslation,
                            onCheckedChange = { viewModel.toggleTranslation() },
                            colors = IconButtonDefaults.iconToggleButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                checkedContainerColor = MaterialTheme.colorScheme.primary,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (state.showTranslation && state.isTranslating) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .semantics { contentDescription = translateDescription },
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Translate,
                                    contentDescription = translateDescription,
                                )
                            }
                        }
                    }
                    if (sourceUrl != null) {
                        IconButton(onClick = { uriHandler.openUri(sourceUrl) }) {
                            Icon(
                                Icons.Filled.OpenInBrowser,
                                contentDescription = stringResource(R.string.detail_open_source),
                            )
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
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
            detail != null -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::load,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                DetailContent(
                    readmeDocument = viewModel.readmeDocument,
                    state = state,
                    detail = detail,
                    listState = listState,
                    onInstallAsset = viewModel::installAsset,
                    onProbeAssets = viewModel::probeRelease,
                    onTranslateNotes = viewModel::translateNotes,
                    onOpenAuthor = onOpenAuthor,
                    onCancel = viewModel::cancelInstall,
                    onSelectChannel = viewModel::selectChannel,
                )
            }
        }
    }

    if (menuOpen) {
        DetailActionsSheet(
            state = state,
            repoPackageName = packageName,
            onDismiss = { menuOpen = false },
            onToggleFavourite = viewModel::toggleFavourite,
            onSetModuleIgnored = viewModel::setModuleIgnored,
            onUninstall = ::requestUninstall,
        )
    }

    if (uninstallConfirm) {
        UninstallConfirmDialog(
            label = uninstallLabel,
            onConfirm = {
                uninstallConfirm = false
                viewModel.uninstall()
            },
            onDismiss = { uninstallConfirm = false },
        )
    }

}
