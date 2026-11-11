@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.tophtab.lspstore.ui.screens.detail

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.parseTraits
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.components.ErrorBox
import io.github.tophtab.lspstore.ui.components.LoadingBox
import io.github.tophtab.lspstore.ui.components.PullToRefreshBox
import io.github.tophtab.lspstore.ui.components.SnackbarMessageEffect
import io.github.tophtab.lspstore.ui.components.formatRelativeTime
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiDialog
import io.github.tophtab.lspstore.ui.design.KomiDivider
import io.github.tophtab.lspstore.ui.design.KomiInfoChip
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.design.KomiTopBar
import io.github.tophtab.lspstore.ui.design.compactCount
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.theme.LocalKomiType
import io.github.tophtab.lspstore.ui.variant.variantReasonText
import io.github.tophtab.lspstore.ui.variant.variantTags

@Composable
fun ModuleDetailScreen(packageName: String, onBack: () -> Unit, onOpenAuthor: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: ModuleDetailViewModel = viewModel(
        factory = ModuleDetailViewModel.factory(container, packageName),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // komi's detail bar keeps the title empty while the hero is on screen, since
    // the module identity is in the hero. Once the hero scrolls away the bar
    // takes the name over, so the page never loses its context.
    val heroScrolledAway by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val shareTitle = stringResource(R.string.detail_share_module)
    val installedPackage = state.installed?.packageName ?: packageName
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
                    state = state,
                    detail = detail,
                    listState = listState,
                    onInstallLatest = viewModel::installLatest,
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
        ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                ActionSheetRow(
                    icon = if (state.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    label = stringResource(
                        if (state.isFavourite) R.string.library_remove_favourite else R.string.module_favourite,
                    ),
                    onClick = { menuOpen = false; viewModel.toggleFavourite() },
                )
                ActionSheetRow(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.action_share),
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
                                    shareTitle,
                                ),
                            )
                        }
                    },
                )
                ActionSheetRow(
                    icon = if (state.isModuleIgnored) Icons.Filled.Restore else Icons.Filled.Block,
                    label = stringResource(
                        if (state.isModuleIgnored) R.string.detail_unignore_module
                        else R.string.detail_ignore_module,
                    ),
                    onClick = {
                        menuOpen = false
                        viewModel.setModuleIgnored(!state.isModuleIgnored)
                    },
                )
                state.installed?.let {
                    ActionSheetRow(
                        icon = Icons.Filled.OpenInNew,
                        label = stringResource(R.string.detail_open_app),
                        onClick = {
                            menuOpen = false
                            context.packageManager.getLaunchIntentForPackage(installedPackage)
                                ?.let { intent -> context.startActivity(intent) }
                        },
                    )
                    ActionSheetRow(
                        icon = Icons.Outlined.Info,
                        label = stringResource(R.string.detail_app_info),
                        onClick = {
                            menuOpen = false
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    "package:$installedPackage".toUri(),
                                ),
                            )
                        },
                    )
                    ActionSheetRow(
                        icon = Icons.Filled.Delete,
                        label = stringResource(R.string.detail_uninstall),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            menuOpen = false
                            requestUninstall()
                        },
                    )
                }
            }
        }
    }

    state.variantPicker?.let { picker ->
        VariantPickerDialog(
            picker = picker,
            onSelect = { asset ->
                viewModel.dismissVariantPicker()
                viewModel.installAsset(asset)
            },
            onDismiss = viewModel::dismissVariantPicker,
        )
    }

    if (uninstallConfirm) {
        KomiDialog(
            onDismissRequest = { uninstallConfirm = false },
            title = uninstallLabel,
            confirmLabel = stringResource(R.string.detail_uninstall),
            confirmVariant = KomiButtonVariant.Destructive,
            onConfirm = {
                uninstallConfirm = false
                viewModel.uninstall()
            },
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { uninstallConfirm = false },
        ) {
            KomiText(
                text = stringResource(R.string.installed_uninstall_confirm_message, uninstallLabel),
                role = KomiTextRole.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }
}

/** Overflow action row for the top-bar sheet, mirroring komi's action sheet. */
@Composable
private fun ActionSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (tint == Color.Unspecified) colors.onSurface else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        KomiText(
            text = label,
            role = KomiTextRole.Body,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * One scrolling page, following komi's detail block order: hero, channel + resource
 * pickers, install controls, stats, What's new, About this app. Release and APK
 * selection use komi's dropdown pickers in the header instead of a release
 * history list. Sections that need GitHub-only data (forks/issues/license) are
 * synthesised from the fields the repository does expose.
 */
@Composable
private fun DetailContent(
    state: ModuleDetailUiState,
    detail: ModuleDetail,
    listState: LazyListState,
    onInstallLatest: () -> Unit,
    onInstallAsset: (ReleaseAsset) -> Unit,
    onProbeAssets: (Release) -> Unit,
    onTranslateNotes: (String, String, Boolean) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onCancel: () -> Unit,
    onSelectChannel: (ReleaseChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val releases = detail.releasesFor(state.channel)
    val latestRelease = detail.latestReleaseFor(state.channel)
    // The release and asset are kept by name, not by object: the lists are
    // rebuilt whenever the detail or the probes change, and a saved object
    // would not survive the trip through the saved-state registry.
    var selectedReleaseTag by rememberSaveable(state.channel) {
        mutableStateOf(latestRelease?.tagName.orEmpty())
    }
    val selectedRelease = releases.firstOrNull { it.tagName == selectedReleaseTag } ?: latestRelease
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
    val emptyVariantState = remember { ModuleVariantState() }
    val variantState = state.variantState ?: emptyVariantState
    val probes = remember(state.probedTag, state.probes, selectedRelease) {
        if (state.probedTag == selectedRelease?.tagName) state.probes else emptyMap()
    }
    val decision = remember(selectedRelease, variantState, probes) {
        selectedRelease?.let {
            resolver.resolveForUpdate(
                variantState,
                it,
                probes,
                expectedPackage = detail.module.packageName,
            )
        }
    }
    // Lazy validation still probes on the detail page (variant-selection.md 7.1):
    // one ranged read per candidate, cached per asset. TRUSTED modules never probe.
    LaunchedEffect(selectedRelease?.tagName) {
        selectedRelease?.let { onProbeAssets(it) }
    }
    val candidates = remember(selectedRelease, decision, probes) {
        val base = selectedRelease?.let {
            // Same expected package as the decision above, so a candidate row and
            // the install button never disagree about what is blocked.
            resolver.candidates(
                it,
                probes = probes,
                expectedPackage = detail.module.packageName,
            )
        } ?: emptyList()
        val recommendedName = when (decision) {
            is VariantDecision.Pick -> decision.asset.name
            is VariantDecision.Choose ->
                decision.candidates.firstOrNull { it.recommended }?.asset?.name

            else -> null
        }
        base.map { it.copy(recommended = it.asset.name == recommendedName) }
    }
    var selectedAssetName by rememberSaveable(selectedRelease?.tagName, variantState) {
        mutableStateOf(decision?.defaultAsset()?.name.orEmpty())
    }
    val selectedAsset = candidates.firstOrNull { it.asset.name == selectedAssetName }?.asset
    var userPickedAsset by rememberSaveable(selectedRelease?.tagName, variantState) { mutableStateOf(false) }
    // The decision is derived from this release and this device right now; the
    // only thing the store remembers is which variant worked before. A release
    // that has no file for this device says so for this visit and is asked again
    // next time (#65, #69, #73).
    val notApplicableReason = (decision as? VariantDecision.NotApplicable)
        ?.let { context.variantReasonText(it.reason) }
    val selectedDebug = selectedAsset?.let { asset ->
        candidates.firstOrNull { it.asset.name == asset.name }?.debug
            ?: parseTraits(asset.name).debugName
    } == true
    var versionSheetVisible by rememberSaveable { mutableStateOf(false) }
    var assetSheetVisible by rememberSaveable { mutableStateOf(false) }
    val currentRelease = selectedRelease
    var whatsNewExpanded by rememberSaveable(currentRelease?.tagName) { mutableStateOf(false) }
    val notesHtml = currentRelease?.descriptionHtml?.takeIf { it.isNotBlank() }
    val notesText = remember(notesHtml) {
        notesHtml
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            ?.takeIf { it.isNotEmpty() }
    }
    // The page knows which release is selected, so it asks for that release's
    // notes translation. A manual translate request is handled once for the
    // release on screen; only the auto-translate setting keeps translating new
    // releases as the user switches between them.
    var handledTranslateRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(
        state.showTranslation,
        state.autoTranslate,
        state.translateRequest,
        currentRelease?.tagName,
        notesText,
    ) {
        val tag = currentRelease?.tagName
        if (!state.showTranslation || tag == null || notesText.isNullOrBlank()) {
            return@LaunchedEffect
        }
        val manualRequest = state.translateRequest != handledTranslateRequest
        if (manualRequest) handledTranslateRequest = state.translateRequest
        if (manualRequest || state.autoTranslate) {
            onTranslateNotes(tag, notesText, manualRequest)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "hero") {
            DetailHero(
                state = state,
                detail = detail,
                onOpenAuthor = onOpenAuthor,
            )
        }

        item(key = "pickers") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // komi's order: stream buttons first, then the resource/version
                // pickers they filter. A module without pre-releases greys the
                // pre-release button out instead of hiding it.
                ReleaseChannelRow(
                    channel = state.channel,
                    hasPreReleases = detail.module.hasPreReleases,
                    onSelect = onSelectChannel,
                )
                VersionAssetPickerRow(
                    selectedRelease = selectedRelease,
                    selectedAsset = selectedAsset,
                    apkCount = selectedRelease?.apkAssets?.size ?: 0,
                    onOpenVersions = { versionSheetVisible = true },
                    onOpenAssets = {
                        assetSheetVisible = true
                        selectedRelease?.let(onProbeAssets)
                    },
                )
            }
        }

        item(key = "install") {
            InstallControls(
                state = state,
                targetVersion = selectedRelease?.version,
                showUpdateHint = state.updateAvailable && selectedRelease == latestRelease,
                installEnabled = (selectedAsset != null || decision is VariantDecision.Choose) &&
                    decision !is VariantDecision.NotApplicable,
                notApplicableReason = notApplicableReason,
                debugBuild = selectedDebug,
                onInstall = {
                    val asset = selectedAsset
                    when {
                        // Ambiguous release: the user must confirm in the picker.
                        !userPickedAsset && decision is VariantDecision.Choose -> onInstallLatest()
                        asset != null -> onInstallAsset(asset)
                        else -> onInstallLatest()
                    }
                },
                onCancel = onCancel,
            )
        }

        item(key = "stats") {
            StatsSection(detail = detail)
        }

        if (notesText != null && currentRelease != null) {
            item(key = "whats_new_header") {
                SectionHeading(stringResource(R.string.detail_whats_new))
            }
            item(key = "whats_new") {
                val displayedNotes = if (state.showTranslation) {
                    state.translatedNotes[currentRelease.tagName] ?: notesText
                } else {
                    notesText
                }
                WhatsNewCard(
                    release = currentRelease,
                    notes = displayedNotes,
                    expanded = whatsNewExpanded,
                    onToggleExpanded = { whatsNewExpanded = !whatsNewExpanded },
                )
            }
        }

        item(key = "about_header") {
            SectionHeading(stringResource(R.string.detail_about))
        }
        item(key = "about") {
            val readme = if (state.showTranslation) {
                state.translatedReadmeHtml ?: detail.readmeHtml
            } else {
                detail.readmeHtml
            }
            if (readme.isNullOrBlank()) {
                KomiText(
                    text = stringResource(R.string.detail_no_readme),
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ReadmeContent(html = readme, baseUrl = detail.module.url)
            }
        }

        if (state.installLogs.isNotEmpty()) {
            item(key = "logs_header") {
                SectionHeading(stringResource(R.string.detail_logs))
            }
            item(key = "logs") {
                InstallLogCard(lines = state.installLogs)
            }
        }
    }

    if (versionSheetVisible) {
        ModalBottomSheet(onDismissRequest = { versionSheetVisible = false }) {
            VersionSheetContent(
                releases = releases,
                selectedRelease = selectedRelease,
                latestRelease = latestRelease,
                onSelect = { release ->
                    selectedReleaseTag = release.tagName
                    versionSheetVisible = false
                },
            )
        }
    }

    if (assetSheetVisible) {
        ModalBottomSheet(onDismissRequest = { assetSheetVisible = false }) {
            AssetSheetContent(
                candidates = candidates,
                selectedAsset = selectedAsset,
                onSelect = { asset ->
                    selectedAssetName = asset.name
                    userPickedAsset = true
                    assetSheetVisible = false
                },
            )
        }
    }
}
