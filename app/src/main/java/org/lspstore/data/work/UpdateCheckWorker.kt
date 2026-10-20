package org.lspstore.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import org.lspstore.LspStoreApp
import org.lspstore.data.install.ApkGate
import org.lspstore.data.install.GateResult
import org.lspstore.data.install.InstallOutcome
import org.lspstore.domain.model.Release
import org.lspstore.domain.model.ReleaseAsset
import org.lspstore.domain.model.UpdateInfo
import org.lspstore.domain.usecase.UpdateChecker
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.VariantDecision
import org.lspstore.domain.variant.VariantResolver
import org.lspstore.domain.variant.VariantSignature
import org.lspstore.domain.variant.onEvaluatedNoCandidate
import org.lspstore.domain.variant.parseTraits

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LspStoreApp).container
        return try {
            val channel = container.settingsRepository.updateChannel.first()
            val index = container.moduleRepository.getIndex(force = true)
            val installed = container.installedRepository.getInstalledModules()
            val skipped = container.libraryRepository.skippedUpdates.first()
            val ignored = container.libraryRepository.ignoredUpdates.first()
            val links = container.libraryRepository.links.first()
            val variantStates = container.libraryRepository.variantStates.first()
            // New releases are re-checked on the name layer, so a module that was
            // NOT_APPLICABLE can return to the updates list once it publishes a
            // compatible build.
            val effectiveStates = recheckNotApplicable(
                container = container,
                installed = installed,
                index = index,
                links = links,
                channel = channel,
                variantStates = variantStates,
            )
            val updates = UpdateChecker.findUpdates(
                installed = installed,
                index = index,
                channel = channel,
                skipped = skipped,
                links = links,
                ignored = ignored,
                variantStates = effectiveStates,
            )

            if (updates.isEmpty()) {
                return Result.success(resultData(checked = installed.size, updates = 0))
            }

            val autoUpdate = container.settingsRepository.autoUpdate.first()
            val useRoot = container.settingsRepository.rootInstall.first()
            val device = container.deviceProfile
            val resolver = VariantResolver(device)

            // Choose / NotApplicable do not count as updates: never silently pick
            // a variant in the background, and never download what cannot install.
            var skippedVariants = 0
            var ambiguousVariants = 0
            val ready = mutableListOf<ReadyUpdate>()
            updates.forEach { update ->
                val detail = runCatching {
                    container.moduleRepository.getDetail(update.module.packageName)
                }.getOrNull() ?: return@forEach
                val release = detail.latestReleaseFor(channel) ?: return@forEach
                val state = effectiveStates[update.module.packageName]
                    ?: effectiveStates[update.installed.packageName]
                    ?: ModuleVariantState()
                when (val decision = resolver.resolveForUpdate(state, release)) {
                    is VariantDecision.Pick -> ready += ReadyUpdate(update, release, decision.asset)
                    is VariantDecision.Choose -> {
                        skippedVariants++
                        ambiguousVariants++
                    }

                    is VariantDecision.NotApplicable -> {
                        skippedVariants++
                        container.libraryRepository.setVariantState(
                            update.module.packageName,
                            state.onEvaluatedNoCandidate(decision.reason),
                        )
                    }
                }
            }

            if (ready.isEmpty()) {
                return Result.success(
                    resultData(
                        checked = installed.size,
                        updates = 0,
                        failed = skippedVariants,
                    ),
                )
            }

            var succeeded = 0
            var failed = 0
            if (autoUpdate && useRoot) {
                ready.forEach { item ->
                    val update = item.info
                    val state = effectiveStates[update.module.packageName]
                        ?: effectiveStates[update.installed.packageName]
                        ?: ModuleVariantState()
                    // SUSPECT triggers a probe here; TRUSTED never does.
                    val resolution = container.resolveVariantUseCase.resolve(
                        release = item.release,
                        state = state,
                        device = device,
                        expectedPackage = update.installed.packageName,
                    )
                    val asset = when (val decision = resolution.decision) {
                        is VariantDecision.Pick -> decision.asset
                        is VariantDecision.NotApplicable -> {
                            container.libraryRepository.setVariantState(
                                update.module.packageName,
                                state.onEvaluatedNoCandidate(decision.reason),
                            )
                            skippedVariants++
                            return@forEach
                        }

                        is VariantDecision.Choose -> {
                            skippedVariants++
                            ambiguousVariants++
                            return@forEach
                        }
                    }
                    val file = runCatching { container.installer.download(asset) }.getOrNull()
                    if (file == null) {
                        failed++
                        return@forEach
                    }
                    val gate = ApkGate.check(
                        applicationContext,
                        file,
                        update.installed.packageName,
                        device,
                    )
                    if (gate is GateResult.Reject) {
                        failed++
                        // A gate hit is one failed attempt, not a verdict: do not
                        // mark the module NOT_APPLICABLE and hide it from updates.
                        container.libraryRepository.recordInstallResult(
                            update.module.packageName,
                            ok = false,
                        )
                        return@forEach
                    }
                    val outcome = runCatching {
                        container.installer.install(
                            file,
                            update.installed.packageName,
                            useRoot = true,
                            allowUi = false,
                        )
                    }.getOrElse { InstallOutcome.Failure(it.message ?: "failed") }

                    if (outcome is InstallOutcome.Completed) {
                        succeeded++
                        container.libraryRepository.recordInstallResult(
                            update.module.packageName,
                            ok = true,
                            signature = VariantSignature.of(parseTraits(asset.name)),
                        )
                    } else {
                        failed++
                        container.libraryRepository.recordInstallResult(
                            update.module.packageName,
                            ok = false,
                        )
                    }
                }
                UpdateNotifier.notifyAutoUpdateResult(applicationContext, succeeded, failed)
            } else {
                UpdateNotifier.notifyUpdates(
                    applicationContext,
                    ready.map { it.info },
                    ambiguous = ambiguousVariants,
                )
            }

            Result.success(
                resultData(
                    checked = installed.size,
                    updates = ready.size,
                    updated = succeeded,
                    failed = failed + skippedVariants,
                ),
            )
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private data class ReadyUpdate(
        val info: UpdateInfo,
        val release: Release,
        val asset: ReleaseAsset,
    )

    /**
     * Name-layer re-check of NOT_APPLICABLE modules before building the update
     * list. No probes, no downloads; one detail fetch per affected module.
     */
    private suspend fun recheckNotApplicable(
        container: org.lspstore.di.AppContainer,
        installed: List<org.lspstore.domain.model.InstalledModule>,
        index: List<org.lspstore.domain.model.Module>,
        links: Map<String, String>,
        channel: org.lspstore.domain.model.UpdateChannel,
        variantStates: Map<String, ModuleVariantState>,
    ): Map<String, ModuleVariantState> {
        val byPackage = index.associateBy { it.packageName }
        val resolver = VariantResolver(container.deviceProfile)
        val effective = variantStates.toMutableMap()
        installed.filter { it.isXposedModule }.forEach { module ->
            val repo = byPackage[module.packageName]
                ?: links[module.packageName]?.let(byPackage::get)
                ?: return@forEach
            val state = effective[repo.packageName] ?: effective[module.packageName] ?: return@forEach
            if (!state.isNotApplicable) return@forEach
            val detail = runCatching { container.moduleRepository.getDetail(repo.packageName) }
                .getOrNull() ?: return@forEach
            val release = detail.latestReleaseFor(channel) ?: return@forEach
            val decision = resolver.resolveForUpdate(state, release)
            if (decision !is VariantDecision.NotApplicable) {
                val revived = state.copy(
                    status = ModuleVariantState.Status.UNKNOWN,
                    reason = null,
                    updatedAt = System.currentTimeMillis(),
                )
                container.libraryRepository.setVariantState(repo.packageName, revived)
                effective[repo.packageName] = revived
            }
        }
        return effective
    }

    private fun resultData(
        checked: Int,
        updates: Int,
        updated: Int = 0,
        failed: Int = 0,
    ) = workDataOf(
        KEY_CHECKED to checked,
        KEY_UPDATES to updates,
        KEY_UPDATED to updated,
        KEY_FAILED to failed,
    )

    companion object {
        const val KEY_CHECKED = "checked"
        const val KEY_UPDATES = "updates"
        const val KEY_UPDATED = "updated"
        const val KEY_FAILED = "failed"
    }
}
