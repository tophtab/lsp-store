package io.github.tophtab.lspstore.domain.usecase

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import io.github.tophtab.lspstore.data.install.ApkInstaller
import io.github.tophtab.lspstore.data.install.ModuleInstallCoordinator
import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.data.repository.SettingsRepository
import io.github.tophtab.lspstore.data.update.UpdateNotifier
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

/** How one background cycle ended. */
sealed interface BackgroundUpdateOutcome {
    /** Index or installed scan could not be refreshed; the worker retries. */
    data object Retry : BackgroundUpdateOutcome

    /** The cycle finished; notifications and installs were handled inside. */
    data object Done : BackgroundUpdateOutcome
}

/**
 * One periodic check: refresh the repository index and the installed list, find
 * updates, then either install them silently (root + auto-update enabled) or
 * announce them.
 *
 * Reuses the batch pipeline unchanged, so variant selection, the install gate,
 * pending-install bookkeeping and install serialization behave exactly as they
 * do for "update all" from the Installed tab.
 */
class BackgroundUpdateCheck(
    private val moduleRepository: ModuleRepository,
    private val installedRepository: InstalledModulesRepository,
    private val libraryRepository: LibraryRepository,
    private val pendingRepository: PendingInstallRepository,
    private val settingsRepository: SettingsRepository,
    private val installer: ApkInstaller,
    private val batchInstall: BatchInstallUseCase,
    private val coordinator: ModuleInstallCoordinator,
    private val notifier: UpdateNotifier,
    private val selfPackage: String,
    private val prunePending: suspend (List<InstalledModule>) -> List<PendingInstall>,
) {

    suspend fun run(): BackgroundUpdateOutcome {
        val index = try {
            moduleRepository.getIndex(force = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return BackgroundUpdateOutcome.Retry
        }
        val installed = try {
            installedRepository.getInstalledModules(force = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return BackgroundUpdateOutcome.Retry
        }
        // A delegated install that landed while the app was closed still sits in
        // the pending list; settling it first keeps the module out of the updates.
        prunePending(installed)

        val preReleases = libraryRepository.preReleaseModules.first()
        val skipped = libraryRepository.skippedUpdates.first()
        val ignored = libraryRepository.ignoredUpdates.first()
        val links = libraryRepository.links.first()
        val variantStates = libraryRepository.variantStates.first()
        val pending = pendingRepository.pending.first()

        val updates = UpdateChecker.findUpdates(
            installed = installed,
            index = index,
            preReleases = preReleases,
            skipped = skipped,
            links = links,
            ignored = ignored,
            variantStates = variantStates,
        ).filterNot { it.installed.packageName in pending.keys }

        if (updates.isEmpty()) {
            settingsRepository.setNotifiedUpdateSignature("")
            return BackgroundUpdateOutcome.Done
        }

        val auto = settingsRepository.autoUpdateRoot.first() &&
            settingsRepository.rootInstall.first() &&
            installer.isRootAvailable()

        if (auto) {
            return autoUpdate(updates, pending.keys, preReleases, variantStates)
        }
        return notify(updates)
    }

    private suspend fun autoUpdate(
        updates: List<UpdateInfo>,
        pendingPackages: Set<String>,
        preReleases: Set<String>,
        variantStates: Map<String, ModuleVariantState>,
    ): BackgroundUpdateOutcome {
        val targets = BackgroundUpdatePlan.autoUpdateTargets(
            updates = updates,
            pendingPackages = pendingPackages,
            runningPackages = coordinator.runningPackages(),
        )
        if (targets.isEmpty()) return BackgroundUpdateOutcome.Done

        // Terminal phases are collected here instead of a UI state: the batch
        // reports every transition, and the counts below are all the summary
        // needs. Download transitions run on per-package coroutines and install
        // transitions on the single install coroutine, so distinct keys never
        // race.
        val phases = ConcurrentHashMap<String, BatchInstallPhase>()
        val result = batchInstall.run(
            targets = targets,
            control = batchInstall.newControl(),
            includePreReleases = { it in preReleases },
            variantStates = variantStates,
            isActive = { true },
            installLast = { info ->
                info.module.packageName == selfPackage ||
                    info.installed.packageName == selfPackage
            },
            onItem = { packageName, transform ->
                val next = transform(
                    BatchInstallState(phase = phases[packageName] ?: BatchInstallPhase.QUEUED),
                )
                phases[packageName] = next.phase
            },
        )

        val installedOk = phases.count { it.value == BatchInstallPhase.COMPLETED }
        val failed = result.failures.size
        val skipped = phases.count { it.value == BatchInstallPhase.SKIPPED }
        notifier.notifyAutoUpdateResult(installedOk, failed)

        // A skipped target needs a variant choice, so it was deliberately not
        // installed silently. Announce it through the normal update notification
        // instead of leaving it invisible.
        val skippedTargets = targets.filter {
            phases[it.module.packageName] == BatchInstallPhase.SKIPPED
        }
        val posted = skippedTargets.isNotEmpty() && notifier.notifyUpdates(skippedTargets)
        settingsRepository.setNotifiedUpdateSignature(
            if (posted) BackgroundUpdatePlan.notificationSignature(skippedTargets) else "",
        )
        return BackgroundUpdateOutcome.Done
    }

    private suspend fun notify(
        updates: List<UpdateInfo>,
    ): BackgroundUpdateOutcome {
        val signature = BackgroundUpdatePlan.notificationSignature(updates)
        if (signature == settingsRepository.notifiedUpdateSignature.first()) {
            return BackgroundUpdateOutcome.Done
        }
        // Persist only when the notification landed: a denied permission must
        // not swallow the announcement once the user grants it.
        val posted = notifier.notifyUpdates(updates)
        if (posted) settingsRepository.setNotifiedUpdateSignature(signature)
        return BackgroundUpdateOutcome.Done
    }
}
