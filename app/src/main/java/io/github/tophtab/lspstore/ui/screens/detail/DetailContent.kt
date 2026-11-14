@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.screens.detail

import android.text.Html
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import io.github.tophtab.lspstore.domain.model.expectedPackage
import io.github.tophtab.lspstore.domain.model.selectedRelease
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.selectInstallAsset
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.theme.LocalKomiType
import io.github.tophtab.lspstore.ui.variant.variantReasonText

/** Regular sections keep a 24 dp gap; README blocks use the list's 12 dp gap. */
private fun LazyListScope.detailItem(key: String, bottomSpace: Dp = 12.dp, content: @Composable () -> Unit) {
    item(key = key) { Box(Modifier.padding(bottom = bottomSpace)) { content() } }
}

/**
 * One scrolling page, following komi's detail block order: hero, channel + resource
 * pickers, install controls, stats, What's new, About this app. Release and APK
 * selection use komi's dropdown pickers in the header instead of a release
 * history list. Sections that need GitHub-only data (forks/issues/license) are
 * synthesised from the fields the repository does expose.
 */
@Composable
internal fun DetailContent(
    readmeDocument: ReadmeDocument,
    state: ModuleDetailUiState,
    detail: ModuleDetail,
    listState: LazyListState,
    onInstallAsset: (ResolvedInstallTarget) -> Unit,
    onProbeAssets: (Release, Boolean) -> Unit,
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
        mutableStateOf("")
    }
    val selectedRelease = detail.selectedRelease(state.channel, selectedReleaseTag)
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val device by container.deviceProfileFlow.collectAsStateWithLifecycle()
    val resolver = remember(device) { VariantResolver(device) }
    val emptyVariantState = remember { ModuleVariantState() }
    val variantState = state.variantState ?: emptyVariantState
    val probes = remember(state.probedTag, state.probes, selectedRelease) {
        if (state.probedRelease == selectedRelease) state.probes else emptyMap()
    }
    val installedHint = remember(state.installed) {
        state.installed?.let(InstalledVariantHint::of)
    }
    val expectedPackageName = expectedPackage(
        installedPackage = state.installed?.packageName,
        repoPackage = detail.module.packageName,
    )
    val fallbackDecision = remember(device, selectedRelease, variantState, probes, installedHint, expectedPackageName) {
        selectedRelease?.let {
            resolver.resolveForUpdate(
                variantState,
                it,
                probes,
                expectedPackage = expectedPackageName,
                installed = installedHint,
            )
        }
    }
    val recommendationReady = state.probedRelease == selectedRelease && state.variantResolution != null && !state.isProbing
    val decision = if (recommendationReady) state.variantResolution.decision else fallbackDecision
    // Multi-APK recommendations can use background metadata. A single APK
    // returns immediately; changing releases also cancels an obsolete request.
    LaunchedEffect(selectedRelease, device, variantState, installedHint, state.probeGeneration) {
        selectedRelease?.let { onProbeAssets(it, false) }
    }
    val candidates = remember(device, selectedRelease, variantState, decision, probes, installedHint, expectedPackageName) {
        val base = selectedRelease?.let {
            // Same expected package, installed hint and probe policy as the
            // decision above, so a row and the install button never disagree.
            resolver.candidates(
                it,
                probes = probes,
                allowProbes = true,
                expectedPackage = expectedPackageName,
                installed = installedHint,
            )
        } ?: emptyList()
        val recommendedName = (decision as? VariantDecision.Pick)?.asset?.name
        // Best fit first, so the default the install button uses is the first row
        // of the resource sheet too.
        resolver.ranked(base).map {
            it.copy(recommended = base.size > 1 && it.asset.name == recommendedName)
        }.sortedByDescending { it.recommended }
    }
    var userPickedAssetName by rememberSaveable(selectedRelease?.tagName) {
        mutableStateOf("")
    }
    val selectedAsset = selectInstallAsset(
        candidates = candidates,
        default = decision?.defaultAsset(),
        userPickedName = userPickedAssetName,
    )
    val selectedTarget = if (selectedRelease != null && selectedAsset != null) {
        ResolvedInstallTarget.of(selectedRelease, selectedAsset, userPickedAssetName.isNotBlank()).let { target ->
            val meta = probes[selectedAsset.name]
            target.copy(apkPackageName = meta?.packageName, apkVersionCode = meta?.versionCode, apkVersionName = meta?.versionName)
        }
    } else null
    // The stored verdict is only a cache of a past decision: the resolver just
    // re-checked it against the release on screen. A stale NOT_APPLICABLE - a
    // framework upgrade, or the pre-1.2.0 API-number block - must not disable the
    // install button (#65, #69).
    val notApplicableReason = (decision as? VariantDecision.NotApplicable)
        ?.let { context.variantReasonText(it.reason) }
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
    val readme = if (state.showTranslation) state.translatedReadmeHtml ?: detail.readmeHtml else detail.readmeHtml
    val readmeBlocks = rememberReadmeBlocks(readme, detail.module.url, readmeDocument)
    val readmeStyle = LocalKomiType.current.styleFor(KomiTextRole.Body).copy(fontSize = 14.sp)
    val afterReadme = if (state.installLogs.isEmpty()) 0.dp else 12.dp
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        detailItem(key = "hero") {
            DetailHero(
                state = state,
                detail = detail,
                onOpenAuthor = onOpenAuthor,
            )
        }

        detailItem(key = "pickers") {
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
                        selectedRelease?.let { onProbeAssets(it, true) }
                    },
                )
            }
        }

        detailItem(key = "install") {
            InstallControls(
                state = state,
                targetVersion = selectedRelease?.version,
                target = selectedTarget,
                showUpdateHint = state.updateAvailable && selectedRelease == latestRelease,
                installEnabled = selectedTarget != null,
                notApplicableReason = when {
                    selectedRelease == null && selectedReleaseTag.isNotBlank() -> stringResource(R.string.detail_resources_unavailable)
                    selectedRelease == null || selectedRelease.apkAssets.isEmpty() -> stringResource(R.string.detail_latest_unavailable)
                    else -> notApplicableReason
                },
                onInstall = {
                    // The button is only enabled with a selected asset, and the
                    // resource row above already shows which one it installs;
                    // changing it is one tap away.
                    selectedTarget?.let(onInstallAsset)
                },
                onCancel = onCancel,
            )
        }

        detailItem(key = "stats") {
            StatsSection(detail = detail)
        }

        if (notesText != null && currentRelease != null) {
            detailItem(key = "whats_new_header") {
                SectionHeading(stringResource(R.string.detail_whats_new))
            }
            detailItem(key = "whats_new") {
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

        detailItem(key = "about_header") {
            SectionHeading(stringResource(R.string.detail_about))
        }
        when {
            readmeBlocks == null -> detailItem(key = "about_loading", bottomSpace = afterReadme) {
                Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.CenterStart) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            readmeBlocks.isEmpty() -> detailItem(key = "about", bottomSpace = afterReadme) {
                KomiText(
                    stringResource(R.string.detail_no_readme),
                    role = KomiTextRole.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(
                count = readmeBlocks.size,
                key = { "readme:$it" },
                contentType = { readmeBlocks[it]::class },
            ) { index ->
                Box(Modifier.padding(bottom = if (index == readmeBlocks.lastIndex) afterReadme else 0.dp)) {
                    ReadmeBlockView(readmeBlocks[index], readmeStyle)
                }
            }
        }

        if (state.installLogs.isNotEmpty()) {
            detailItem(key = "logs_header") {
                SectionHeading(stringResource(R.string.detail_logs))
            }
            detailItem(key = "logs", bottomSpace = 0.dp) {
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
                    userPickedAssetName = asset.name
                    assetSheetVisible = false
                },
            )
        }
    }
}
