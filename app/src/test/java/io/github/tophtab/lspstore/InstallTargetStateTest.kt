package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.backup.LibraryBackup
import io.github.tophtab.lspstore.data.install.InstallStage
import io.github.tophtab.lspstore.data.install.InstallTaskRegistry
import io.github.tophtab.lspstore.data.install.SystemInstallSerializer
import io.github.tophtab.lspstore.domain.model.*
import io.github.tophtab.lspstore.domain.usecase.*
import io.github.tophtab.lspstore.domain.variant.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InstallTargetStateTest {
    private fun asset(name: String = "OS2_module.apk") = ReleaseAsset(name, "https://example.invalid/$name", null, 100, 0, null)
    private fun release(tag: String = "4571-2.10", time: String = "2026-11-01T00:00:00Z") = Release(
        tag, null, null, false, Instant.parse(time), null, ModuleVersion.parse(tag), listOf(asset()),
    )
    private fun target() = ResolvedInstallTarget.of(release(), asset()).copy(
        apkPackageName = "com.example.module", apkVersionCode = 4559, apkVersionName = "2.10",
    )
    private fun installed() = InstalledModule("com.example.module", 4559, "2.10", "Module", true, null, lastUpdateTime = 1793577601000L)
    private fun record() = installed().let { ConfirmedInstallRecord(target(), it.packageName, it.versionCode, it.versionName, it.lastUpdateTime) }
    private fun pending(id: String = "task-1") = PendingInstall(
        "com.example.module", "repo", versionCode = 4559, versionName = "2.10", createdAt = 1793577600000L,
        taskId = id, target = target(), previousLastUpdateTime = 10L,
    )
    private fun detail(latest: String = "4571-2.10", releases: List<Release> = listOf(release())) = ModuleDetail(
        Module("repo", "Module", null, null, null, null, null, 0, null, null, ModuleVersion.parse(latest), null, null, release().publishedAt.toString()),
        null, emptyList(), emptyList(), releases, emptyList(), emptyList(), null, null,
    )

    private fun update(repo: String = "repo") = UpdateInfo(detail().module.copy(packageName = repo), installed().copy(packageName = "$repo.installed"), release().version!!)

    @Test fun concurrentBatchesDoNotPrepareOrInstallTheSameModuleTwice() = runTest {
        val registry = InstallTaskRegistry()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        var prepares = 0
        var installs = 0
        val preparer = object : ModuleInstallPreparer {
            override suspend fun prepare(info: UpdateInfo, includePreReleases: Boolean, state: ModuleVariantState, onAssetResolved: (ReleaseAsset) -> Unit, onProgress: (Long, Long) -> Unit): PreparedInstall {
                prepares++
                entered.complete(Unit)
                resume.await()
                return PreparedInstall.Ready(info, java.io.File("unused.apk"), asset(), state, target())
            }
        }
        val installer = object : ModuleInstaller {
            override suspend fun install(prepared: PreparedInstall.Ready): io.github.tophtab.lspstore.data.install.InstallOutcome {
                installs++
                return io.github.tophtab.lspstore.data.install.InstallOutcome.Completed
            }
        }
        val batch = BatchInstallUseCase(preparer, installer, {}, io.github.tophtab.lspstore.data.install.DownloadSlots { 3 }, registry)
        val first = async { batch.run(listOf(update()), batch.newControl(), { false }, emptyMap(), { true }, onItem = { _, _ -> }) }
        entered.await()
        val owner = registry.runs.value["repo"]?.taskId
        val phases = mutableListOf<BatchInstallPhase>()
        val duplicate = batch.run(listOf(update()), batch.newControl(), { false }, emptyMap(), { true }, onItem = { _, transform -> phases += transform(BatchInstallState(BatchInstallPhase.QUEUED)).phase })
        assertEquals(listOf(BatchInstallPhase.ALREADY_RUNNING), phases)
        assertEquals(owner, registry.runs.value["repo"]?.taskId)
        assertEquals(0, duplicate.delegated)
        assertTrue(duplicate.failures.isEmpty())
        assertEquals(1, prepares)
        resume.complete(Unit)
        first.await()
        assertEquals(1, installs)
        assertFalse(registry.isRunning("repo"))
    }

    @Test fun cancellingAModuleWaitingToInstallKeepsTheOtherBatchModulesRunning() = runTest {
        val registry = InstallTaskRegistry()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        val phases = mutableMapOf<String, BatchInstallPhase>()
        val preparer = object : ModuleInstallPreparer {
            override suspend fun prepare(info: UpdateInfo, includePreReleases: Boolean, state: ModuleVariantState, onAssetResolved: (ReleaseAsset) -> Unit, onProgress: (Long, Long) -> Unit) = PreparedInstall.Ready(info, java.io.File("unused.apk"), asset(), state, target())
        }
        val installer = object : ModuleInstaller {
            override suspend fun install(prepared: PreparedInstall.Ready): io.github.tophtab.lspstore.data.install.InstallOutcome {
                if (prepared.info.module.packageName == "first") {
                    registry.stage(requireNotNull(prepared.taskId), InstallStage.WAITING_TO_INSTALL)
                    entered.complete(Unit)
                    wait.await()
                }
                return io.github.tophtab.lspstore.data.install.InstallOutcome.Completed
            }
        }
        val batch = BatchInstallUseCase(preparer, installer, {}, io.github.tophtab.lspstore.data.install.DownloadSlots { 3 }, registry)
        val result = async { batch.run(listOf(update("first"), update("second")), batch.newControl(), { false }, emptyMap(), { true }, onItem = { repo, transform -> phases[repo] = transform(BatchInstallState(phases[repo] ?: BatchInstallPhase.QUEUED)).phase }) }
        entered.await()
        registry.cancel("first")
        assertTrue(result.await().failures.isEmpty())
        assertEquals(BatchInstallPhase.CANCELLED, phases["first"])
        assertEquals(BatchInstallPhase.COMPLETED, phases["second"])
        assertTrue(registry.runs.value.isEmpty())
    }

    @Test fun fourEntrypointsShareOneAtomicClaimBeforePreparing() = runTest {
        val registry = InstallTaskRegistry()
        val ids = List(4) { async { registry.claim("repo", "old.package") } }.awaitAll()
        assertEquals(1, ids.count { it != null })
        val id = ids.filterNotNull().single()
        InstallStage.entries.forEach { phase ->
            registry.stage(id, phase)
            assertNull(registry.claim("repo"))
            assertNull(registry.claim("another.repo", "old.package"))
        }
        assertTrue(registry.associate(id, "new.package"))
        assertNull(registry.claim("another.repo", "new.package"))
    }

    @Test fun processRecoveryBlocksRequestsUntilPendingIsRestored() = runTest {
        val registry = InstallTaskRegistry(initiallyReady = false)
        val claim = async { registry.claim("repo") }
        yield()
        assertFalse(claim.isCompleted)
        registry.restore(listOf(pending()))
        assertNull(claim.await())
        assertEquals(InstallStage.PENDING_CONFIRMATION, registry.runs.value["repo"]?.stage)
        assertNull(registry.claim("other", "com.example.module"))
    }

    @Test fun oldCleanupAndProgressCannotReleaseOrOverwriteANewTask() = runTest {
        val registry = InstallTaskRegistry()
        val old = requireNotNull(registry.claim("repo"))
        registry.release(old)
        val next = requireNotNull(registry.claim("repo"))
        registry.stage(next, InstallStage.DOWNLOADING)
        registry.release(old)
        registry.stage(old, InstallStage.INSTALLING)
        assertEquals(next, registry.runs.value["repo"]?.taskId)
        assertEquals(InstallStage.DOWNLOADING, registry.runs.value["repo"]?.stage)
    }

    @Test fun renamedPackagesCannotBeClaimedByTwoRepositoryTasks() = runTest {
        val registry = InstallTaskRegistry()
        val first = requireNotNull(registry.claim("repo.one"))
        val second = requireNotNull(registry.claim("repo.two"))
        assertTrue(registry.associate(first, "actual.package"))
        assertFalse(registry.associate(second, "actual.package"))
        registry.release(second)
        assertNull(registry.claim("repo.three", "actual.package"))
    }

    @Test fun returningInstallerSnapshotDoesNotIncludeANewTask() = runTest {
        val registry = InstallTaskRegistry()
        registry.restore(listOf(pending()))
        val returning = registry.pendingIds()
        registry.release("task-1")
        val next = requireNotNull(registry.claim("repo"))
        registry.stage(next, InstallStage.PENDING_CONFIRMATION)
        assertFalse(next in returning)
    }

    @Test fun serializerUsesTheTaskIdForLateCallbacksOnTheSamePackage() = runTest {
        val serializer = SystemInstallSerializer()
        serializer.awaitFreeAndMarkPending("pkg", taskId = "old")
        serializer.markCompleted("pkg", "old")
        serializer.awaitFreeAndMarkPending("pkg", taskId = "new")
        serializer.markCompleted("pkg", "old")
        assertTrue(serializer.isHeldBy("pkg"))
        serializer.markCompleted("pkg", "new")
        assertFalse(serializer.isHeldBy("pkg"))
    }

    @Test fun release4571WithApk4559IsCompleteButFutureAndRepublishedReleasesUpdate() {
        val installed = installed()
        val record = record()
        assertFalse(UpdateChecker.isUpdateAvailable(release().version!!, installed, release().publishedAt.toString(), record))
        assertTrue(UpdateChecker.isUpdateAvailable(ModuleVersion.parse("4572-next")!!, installed, "2026-11-02T00:00:00Z", record))
        assertTrue(UpdateChecker.isUpdateAvailable(release().version!!, installed, "2026-11-02T00:00:00Z", record))
        assertFalse(UpdateChecker.isUpdateAvailable(ModuleVersion.parse("4570-old")!!, installed, "2026-11-03T00:00:00Z", record))
    }

    @Test fun higherApkCodeDoesNotHideANewerSourceRelease() {
        val installed = installed().copy(versionCode = 5000)
        val record = record().copy(versionCode = 5000, target = target().copy(apkVersionCode = 5000))
        assertTrue(UpdateChecker.isUpdateAvailable(ModuleVersion.parse("4572-next")!!, installed, "2026-11-02T00:00:00Z", record))
    }

    @Test fun sameCodeSourceReleasesUsePublicationTimeEvenWithTheSameVersionName() {
        val record = record().copy(target = target().copy(releaseTag = "1-one", releaseVersion = ModuleVersion.parse("1-one")))
        assertTrue(UpdateChecker.isUpdateAvailable(ModuleVersion.parse("1-two")!!, installed(), "2026-11-02T00:00:00Z", record))
        assertFalse(UpdateChecker.isUpdateAvailable(ModuleVersion.parse("1-two")!!, installed(), "2026-10-31T00:00:00Z", record))
    }

    @Test fun packageVersionNameAndUpdateTimeAllInvalidateLocalSourceEvidence() {
        val state = ModuleVariantState(confirmedInstall = record(), preferenceMigrated = true).onUserPicked(VariantSignature.ofAsset("OS3_module.apk"))
        listOf(null, installed().copy(packageName = "other"), installed().copy(versionCode = 4560), installed().copy(versionName = "external"), installed().copy(lastUpdateTime = installed().lastUpdateTime + 1)).forEach { external ->
            val checked = state.forInstalled(external)
            assertNull(checked.confirmedInstall)
            assertEquals(state.explicitPreference, checked.explicitPreference)
            assertTrue(UpdateChecker.isUpdateAvailable(release().version!!, external ?: installed(), release().publishedAt.toString(), checked.confirmedInstall))
        }
    }

    @Test fun pendingRequiresTheActualApkAndANewInstallationIncludingDowngrades() {
        val pending = pending()
        assertFalse(pendingInstallLanded(pending, 4559, 10, "2.10"))
        assertFalse(pendingInstallLanded(pending, 4571, installed().lastUpdateTime, "2.10"))
        assertFalse(pendingInstallLanded(pending, 4559, installed().lastUpdateTime, "other"))
        assertTrue(pendingInstallLanded(pending.copy(previousLastUpdateTime = 100), 4559, installed().lastUpdateTime, "2.10"))
    }

    @Test fun unreadableApkMaySettleOnChangedPackageWithoutInventingItsIdentity() {
        val pending = pending().copy(target = target().copy(apkPackageName = null, apkVersionCode = null, apkVersionName = null))
        assertFalse(pendingInstallLanded(pending, 99, 10, "other"))
        assertTrue(pendingInstallLanded(pending, 99, installed().lastUpdateTime, "other"))
    }

    @Test fun preferenceSurvivesFailureAndSuccessAndAutoResetKeepsTheRecord() {
        val picked = VariantSignature.ofAsset("OS3_module.apk")
        val state = ModuleVariantState(confirmedInstall = record(), preferenceMigrated = true).onUserPicked(picked)
        assertEquals(picked, state.onInstallFailed().explicitPreference)
        val successful = state.onInstallSucceeded(target().signature)
        assertEquals(picked, successful.explicitPreference)
        assertEquals(picked, successful.recommendationSignature)
        val auto = successful.copy(explicitPreference = null)
        assertEquals(record(), auto.confirmedInstall)
        assertEquals(record().target.signature, auto.recommendationSignature)
        assertNull(ModuleVariantState(preferenceMigrated = true).onInstallSucceeded(target().signature).explicitPreference)
    }

    @Test fun cleaningALegacyVerdictPreservesItsExplicitAssetPreference() {
        val signature = VariantSignature.ofAsset("OS2_module.apk")
        val old = ModuleVariantState(status = ModuleVariantState.Status.NOT_APPLICABLE, signature = signature)
        val cleaned = mapOf("repo" to old).withoutNotApplicableVerdicts().getValue("repo")
        assertEquals(signature, cleaned.explicitPreference)
        assertFalse(cleaned.isNotApplicable)
        assertNull(cleaned.reason)
    }

    @Test fun legacyAssetPreferenceMigratesOnceAndPackageManagerSeedStaysEvidence() {
        val legacy = ModuleVariantState(signature = VariantSignature.ofAsset("OS2_module.apk", "pkg"))
        val migrated = legacy.migrated()
        assertEquals(legacy.signature, migrated.explicitPreference)
        assertNull(migrated.signature?.assetFamily)
        assertNull(migrated.copy(explicitPreference = null).migrated().explicitPreference)
        val seed = ModuleVariantState(signature = VariantSignature(abi = "arm64-v8a", packageName = "pkg")).migrated()
        assertNull(seed.explicitPreference)
        assertEquals("arm64-v8a", seed.signature?.abi)
    }

    @Test fun backupExportsOnlyPreferencesAndMergeRetainsLocalConfirmation() {
        val state = ModuleVariantState(confirmedInstall = record(), preferenceMigrated = true).onUserPicked(VariantSignature.ofAsset("OS3_module.apk"))
        val local = LibraryBackup(schemaVersion = 1, variantStates = mapOf("repo" to state))
        val portable = LibraryBackup.decode(LibraryBackup.encode(local.portable()))
        assertNull(portable.variantStates["repo"]?.confirmedInstall)
        assertEquals(state.explicitPreference, portable.variantStates["repo"]?.explicitPreference)
        assertEquals(record(), local.mergedWith(portable).variantStates["repo"]?.confirmedInstall)
        assertNull(portable.variantStates["repo"]?.signature)
    }

    @Test fun automaticSelectionTracksRefreshButManualHistoryStaysAndMissingHistoryIsUnavailable() {
        val old = release("4570-old", "2026-10-01T00:00:00Z")
        val initial = detail(releases = listOf(release(), old))
        assertEquals(release(), initial.selectedRelease(ReleaseChannel.STABLE, null))
        val newer = release("4572-next", "2026-11-02T00:00:00Z")
        val refreshed = detail("4572-next", listOf(newer, old))
        assertEquals(newer, refreshed.selectedRelease(ReleaseChannel.STABLE, null))
        assertEquals(old, refreshed.selectedRelease(ReleaseChannel.STABLE, old.tagName))
        assertNull(refreshed.selectedRelease(ReleaseChannel.STABLE, "removed"))
        assertNull(detail(releases = listOf(old)).selectedRelease(ReleaseChannel.STABLE, null))
        assertEquals(old, detail(releases = listOf(old)).selectedRelease(ReleaseChannel.STABLE, old.tagName))
    }

    @Test fun missingTargetMayUseAHigherReleaseButNeverAnOlderOne() {
        val higher = release("4572-next")
        assertEquals(higher, detail(releases = listOf(higher)).latestReleaseFor(ReleaseChannel.STABLE))
        assertNull(detail(releases = listOf(release("4570-old"))).latestReleaseFor(ReleaseChannel.STABLE))
    }

    @Test fun buttonsUseTheSelectedApkBeforeTheReleaseAndHandleHistory() {
        val target = target()
        assertEquals(InstallAction.INSTALL, installAction(target, null, null))
        assertEquals(InstallAction.UPDATE, installAction(target.copy(apkVersionCode = 4560), installed(), null))
        assertEquals(InstallAction.REINSTALL, installAction(target, installed(), null))
        assertEquals(InstallAction.UPDATE, installAction(target.copy(publishedAt = "2026-11-10T00:00:00Z"), installed(), ModuleVariantState(confirmedInstall = record())))
        assertEquals(InstallAction.DOWNGRADE, installAction(target.copy(apkVersionCode = 4558), installed(), null))
        val state = ModuleVariantState(confirmedInstall = record())
        assertEquals(InstallAction.REINSTALL, installAction(target.copy(apkVersionCode = null), installed(), state))
        assertEquals(InstallAction.DOWNGRADE, installAction(target.copy(apkVersionCode = null, releaseVersion = ModuleVersion.parse("4570-old")), installed(), state))
        assertEquals(InstallAction.REINSTALL, installAction(target.copy(apkVersionCode = null, releaseVersion = null), installed(), state))
    }

    @Test fun lateProbeResponsesCannotOverwriteANewReleaseOrRefresh() {
        val gate = ReleaseProbeRequests()
        val old = gate.begin(release())
        val next = gate.begin(release("4572-next"))
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(next))
        val refreshed = gate.begin(next.release.copy(assets = listOf(asset("new.apk"))))
        assertFalse(gate.isCurrent(next))
        assertTrue(gate.isCurrent(refreshed))
        gate.invalidate()
        assertFalse(gate.isCurrent(refreshed))
    }

    @Test fun batchCompletionPreservesFailureSkipCancelPriorityAndDelegationIsProcessed() {
        fun states(vararg phases: BatchInstallPhase) = phases.map { BatchInstallState(it) }
        val root = BatchInstallUseCase.Result()
        val delegated = BatchInstallUseCase.Result(delegated = 1)
        assertEquals(BatchCompletion.COMPLETED, batchCompletion(root, states(BatchInstallPhase.COMPLETED)))
        assertEquals(BatchCompletion.PROCESSED, batchCompletion(delegated, states(BatchInstallPhase.PENDING_CONFIRMATION)))
        assertEquals(BatchCompletion.PROCESSED, batchCompletion(root, states(BatchInstallPhase.ALREADY_RUNNING)))
        assertEquals(BatchCompletion.CANCELLED, batchCompletion(delegated, states(BatchInstallPhase.CANCELLED)))
        assertEquals(BatchCompletion.SKIPPED, batchCompletion(delegated, states(BatchInstallPhase.CANCELLED, BatchInstallPhase.SKIPPED)))
        assertEquals(BatchCompletion.FAILURES, batchCompletion(delegated, states(BatchInstallPhase.FAILED, BatchInstallPhase.SKIPPED)))
    }
}
