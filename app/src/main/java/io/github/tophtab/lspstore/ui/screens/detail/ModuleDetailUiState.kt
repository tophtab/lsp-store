package io.github.tophtab.lspstore.ui.screens.detail

import io.github.tophtab.lspstore.data.install.ModuleInstallRun
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseChannel
import io.github.tophtab.lspstore.domain.usecase.UpdateChecker
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

data class ModuleDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val detail: ModuleDetail? = null,
    val installed: InstalledModule? = null,
    /**
     * Stream shown on this page. The three buttons above the resource picker pick
     * it; a channel choice also updates the persisted pre-release opt-in for the
     * streams that have an update-check meaning.
     */
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
    val isFavourite: Boolean = false,
    /** Whether the user hid this module from the repository's browsing lists. */
    val isModuleIgnored: Boolean = false,
    /**
     * Keyword rules that also hide this module. They are rules, not entries, so
     * this page cannot remove them; it only explains why the module is hidden.
     */
    val hiddenByKeywords: List<String> = emptyList(),
    /** Download/install started from this page, still running. */
    val activeInstall: ModuleInstallRun? = null,
    val message: String? = null,
    val translatedSummary: String? = null,
    /** README translated as HTML, with its markup preserved. */
    val translatedReadmeHtml: String? = null,
    /** Release notes translated as plain text, keyed by release tag. */
    val translatedNotes: Map<String, String> = emptyMap(),
    /** Whether the page currently shows translations instead of the originals. */
    val showTranslation: Boolean = false,
    /** The persisted auto-translate setting: the only source of automatic translation. */
    val autoTranslate: Boolean = false,
    /** Increments on every manual tap that starts translating the page. */
    val translateRequest: Int = 0,
    /** Number of translation requests currently in flight. */
    val translationsInFlight: Int = 0,
    val variantState: ModuleVariantState? = null,
    /** P2 probe results for [probedTag], shown in the picker/sheet. */
    val probes: Map<String, ApkMeta> = emptyMap(),
    val probedTag: String? = null,
    val probedRelease: Release? = null,
    val variantResolution: io.github.tophtab.lspstore.domain.usecase.VariantResolution? = null,
    val probeGeneration: Int = 0,
    val isProbing: Boolean = false,
    val pendingInstall: PendingInstall? = null,
    val installLogs: List<String> = emptyList(),
    /** Mirrors the root switch: a silent uninstall asks for confirmation first. */
    val rootInstall: Boolean = false,
) {
    val isTranslating: Boolean
        get() = translationsInFlight > 0

    val latestVersion: ModuleVersion?
        get() = detail?.module?.latestFor(channel)

    val updateAvailable: Boolean
        get() {
            val installed = installed ?: return false
            val module = detail?.module ?: return false
            val latest = module.latestFor(channel) ?: return false
            // Same rule as the Installed tab (UpdateChecker), including the
            // same-code re-upload case: a versionName-only difference caused by
            // author naming is not an update, a re-upload after the installed
            // copy landed is.
            return UpdateChecker.isUpdateAvailable(
                latest = latest,
                installed = installed,
                publishedRaw = module.latestTimeFor(channel),
                confirmed = variantState?.confirmedInstall,
            )
        }
}

internal fun ModuleDetailUiState.withoutProgress(): ModuleDetailUiState = copy(
    activeInstall = activeInstall?.copy(downloaded = 0, total = 0),
)
