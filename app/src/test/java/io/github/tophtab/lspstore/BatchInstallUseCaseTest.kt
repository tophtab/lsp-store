package io.github.tophtab.lspstore

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.data.install.InstallOutcome
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BatchFailureKind
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.domain.usecase.BatchInstallUseCase
import io.github.tophtab.lspstore.domain.usecase.ModuleInstaller
import io.github.tophtab.lspstore.domain.usecase.ModuleInstallPreparer
import io.github.tophtab.lspstore.domain.usecase.PreparedInstall
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

/**
 * The batch queue is the part that broke on device twice (installer dialogs
 * stacking / the queue deadlocking after one delegated install), so it is worth
 * pinning down here instead of hand-testing on an emulator.
 */
class BatchInstallUseCaseTest {

    private val asset = ReleaseAsset(
        name = "module-1.1.apk",
        downloadUrl = "https://example.test/module-1.1.apk",
        fallbackUrl = null,
        size = 1_000L,
        downloadCount = 0,
        contentType = "application/vnd.android.package-archive",
    )

    private fun updateInfo(packageName: String): UpdateInfo {
        val version = ModuleVersion.parse("10-1.1")!!
        return UpdateInfo(
            module = Module(
                packageName = packageName,
                title = packageName.substringAfterLast('.'),
                summary = null,
                url = null,
                homepageUrl = null,
                sourceUrl = null,
                updatedAt = null,
                stars = 0,
                contributor = null,
                contributorAvatarUrl = null,
                latestRelease = version,
                latestBetaRelease = null,
                latestSnapshotRelease = null,
                latestReleaseTime = null,
            ),
            installed = InstalledModule(
                packageName = packageName,
                versionCode = 10L,
                versionName = "1.0",
                label = packageName,
                isXposedModule = true,
                description = null,
            ),
            latest = version,
        )
    }

    private fun ready(info: UpdateInfo) = PreparedInstall.Ready(
        info = info,
        file = File.createTempFile("apk", ".apk").apply { deleteOnExit() },
        asset = asset,
        state = ModuleVariantState(),
    )

    private class Recorder {
        val phases = mutableMapOf<String, BatchInstallPhase>()
        var installs = 0
    }

    private fun run(
        targets: List<UpdateInfo>,
        prepared: (UpdateInfo) -> PreparedInstall = { ready(it) },
        installer: (PreparedInstall.Ready) -> InstallOutcome = { InstallOutcome.Completed },
        settled: Boolean = true,
        isActive: () -> Boolean = { true },
        onInstalled: (String) -> Unit = {},
        released: MutableList<String> = mutableListOf(),
        downloadLimit: Int = 3,
    ): Pair<BatchInstallUseCase.Result, Recorder> {
        val recorder = Recorder()
        val preparer = object : ModuleInstallPreparer {
            override suspend fun prepare(
                info: UpdateInfo,
                includePreReleases: Boolean,
                state: ModuleVariantState,
                onAssetResolved: (ReleaseAsset) -> Unit,
                onProgress: (downloaded: Long, total: Long) -> Unit,
            ): PreparedInstall {
                onAssetResolved(asset)
                onProgress(500L, 1_000L)
                return prepared(info)
            }
        }
        val install = object : ModuleInstaller {
            override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
                recorder.installs++
                onInstalled(prepared.info.module.packageName)
                delay(5)
                return installer(prepared)
            }
        }
        val useCase = BatchInstallUseCase(
            prepareInstall = preparer,
            installPrepared = install,
            awaitInstallSettled = { _, _ -> settled },
            releaseInstallSlot = { released += it },
            abandonDownload = {},
            downloadSlots = DownloadSlots { downloadLimit },
        )
        val result = runBlocking {
            useCase.run(
                targets = targets,
                control = useCase.newControl(),
                includePreReleases = { false },
                variantStates = emptyMap(),
                isActive = isActive,
                onItem = { packageName, transform ->
                    val current = BatchInstallState(
                        phase = recorder.phases[packageName] ?: BatchInstallPhase.QUEUED,
                    )
                    recorder.phases[packageName] = transform(current).phase
                },
            )
        }
        return result to recorder
    }

    @Test
    fun allModulesCompleteWhenEveryInstallSucceeds() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))

        val (result, recorder) = run(targets)

        assertFalse(result.installInterrupted)
        assertEquals(2, recorder.installs)
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun oneFailureDoesNotStopTheRest() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))

        val (result, recorder) = run(
            targets,
            installer = { prepared ->
                if (prepared.info.module.packageName == "com.example.a") {
                    InstallOutcome.Failure("boom")
                } else {
                    InstallOutcome.Completed
                }
            },
        )

        assertFalse(result.installInterrupted)
        assertEquals(2, recorder.installs)
        assertEquals(BatchInstallPhase.FAILED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun delegatedInstallThatNeverSettlesSkipsTheRestOfTheQueue() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))
        val released = mutableListOf<String>()
        val installed = mutableListOf<String>()

        val (result, recorder) = run(
            targets,
            installer = { InstallOutcome.DelegatedToSystem },
            settled = false,
            onInstalled = { installed += it },
            released = released,
        )

        assertTrue(result.installInterrupted)
        // Only the hand-off that never resolved reached the installer.
        assertEquals(listOf("com.example.a"), installed)
        assertEquals(BatchInstallPhase.SKIPPED, recorder.phases["com.example.a"])
        // The install slot has to be given back, otherwise the queue deadlocks.
        assertEquals(listOf("com.example.a"), released)
    }

    @Test
    fun delegatedInstallThatSettlesContinuesTheQueue() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))

        val (result, recorder) = run(
            targets,
            installer = { InstallOutcome.DelegatedToSystem },
            settled = true,
        )

        assertFalse(result.installInterrupted)
        assertEquals(2, recorder.installs)
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun ambiguousVariantIsSkippedWithoutInstalling() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))

        val (result, recorder) = run(
            targets,
            prepared = { info ->
                if (info.module.packageName == "com.example.a") {
                    PreparedInstall.NeedsVariant(info)
                } else {
                    ready(info)
                }
            },
        )

        assertFalse(result.installInterrupted)
        assertEquals(1, recorder.installs)
        assertEquals(BatchInstallPhase.SKIPPED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun failedPreparationIsReportedAsFailure() {
        val targets = listOf(updateInfo("com.example.a"))

        val (result, recorder) = run(
            targets,
            prepared = { info -> PreparedInstall.Failed(info, "no asset") },
        )

        assertFalse(result.installInterrupted)
        assertEquals(0, recorder.installs)
        assertEquals(BatchInstallPhase.FAILED, recorder.phases["com.example.a"])
        // The reason has to survive the queue, otherwise the summary can only
        // tell the user "Failures: 1".
        assertEquals(BatchFailureKind.DOWNLOAD, result.failures.single().kind)
        assertEquals("no asset", result.failures.single().detail)
    }

    @Test
    fun installFailureMessageReachesTheSummary() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))

        val (result, _) = run(
            targets,
            installer = { prepared ->
                if (prepared.info.module.packageName == "com.example.a") {
                    InstallOutcome.Completed
                } else {
                    InstallOutcome.Failure("boom")
                }
            },
        )

        val failed = result.failures.single()
        assertEquals("com.example.b", failed.packageName)
        assertEquals("b", failed.title)
        assertEquals(BatchFailureKind.INSTALL, failed.kind)
        assertEquals("boom", failed.detail)
    }

    @Test
    fun installsNeverOverlapEvenWhenDownloadsDo() {
        val targets = (1..4).map { updateInfo("com.example.$it") }
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()

        runBlocking {
            val preparer = object : ModuleInstallPreparer {
                override suspend fun prepare(
                    info: UpdateInfo,
                    includePreReleases: Boolean,
                    state: ModuleVariantState,
                    onAssetResolved: (ReleaseAsset) -> Unit,
                    onProgress: (downloaded: Long, total: Long) -> Unit,
                ): PreparedInstall {
                    onAssetResolved(asset)
                    onProgress(1_000L, 1_000L)
                    return ready(info)
                }
            }
            val installer = object : ModuleInstaller {
                override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
                    delay(10)
                    concurrent.decrementAndGet()
                    return InstallOutcome.Completed
                }
            }
            val useCase = BatchInstallUseCase(
                prepareInstall = preparer,
                installPrepared = installer,
                awaitInstallSettled = { _, _ -> true },
                releaseInstallSlot = {},
                abandonDownload = {},
                downloadSlots = DownloadSlots { 3 },
            )
            useCase.run(
                targets = targets,
                control = useCase.newControl(),
                includePreReleases = { false },
                variantStates = emptyMap(),
                isActive = { true },
                onItem = { _, _ -> },
            )
        }

        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun downloadLimitCapsConcurrentDownloads() {
        val targets = (1..6).map { updateInfo("com.example.$it") }

        for (limit in 1..3) {
            val concurrent = AtomicInteger()
            val maxConcurrent = AtomicInteger()
            runBlocking {
                val preparer = object : ModuleInstallPreparer {
                    override suspend fun prepare(
                        info: UpdateInfo,
                        includePreReleases: Boolean,
                        state: ModuleVariantState,
                        onAssetResolved: (ReleaseAsset) -> Unit,
                        onProgress: (downloaded: Long, total: Long) -> Unit,
                    ): PreparedInstall {
                        onAssetResolved(asset)
                        val now = concurrent.incrementAndGet()
                        maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
                        delay(20)
                        concurrent.decrementAndGet()
                        return ready(info)
                    }
                }
                val installer = object : ModuleInstaller {
                    override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome =
                        InstallOutcome.Completed
                }
                val useCase = BatchInstallUseCase(
                    prepareInstall = preparer,
                    installPrepared = installer,
                    awaitInstallSettled = { _, _ -> true },
                    releaseInstallSlot = {},
                    abandonDownload = {},
                    downloadSlots = DownloadSlots { limit },
                )
                useCase.run(
                    targets = targets,
                    control = useCase.newControl(),
                    includePreReleases = { false },
                    variantStates = emptyMap(),
                    isActive = { true },
                    onItem = { _, _ -> },
                )
            }

            assertEquals("limit $limit", limit, maxConcurrent.get())
        }
    }

    @Test
    fun cancellingOneRowLeavesTheRestOfTheBatchAlone() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))
        val phases = mutableMapOf<String, BatchInstallPhase>()
        val installs = mutableListOf<String>()

        val result = runBlocking {
            val preparer = object : ModuleInstallPreparer {
                override suspend fun prepare(
                    info: UpdateInfo,
                    includePreReleases: Boolean,
                    state: ModuleVariantState,
                    onAssetResolved: (ReleaseAsset) -> Unit,
                    onProgress: (downloaded: Long, total: Long) -> Unit,
                ): PreparedInstall {
                    onAssetResolved(asset)
                    delay(50)
                    return ready(info)
                }
            }
            val installer = object : ModuleInstaller {
                override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
                    installs += prepared.info.module.packageName
                    return InstallOutcome.Completed
                }
            }
            val useCase = BatchInstallUseCase(
                prepareInstall = preparer,
                installPrepared = installer,
                awaitInstallSettled = { _, _ -> true },
                releaseInstallSlot = {},
                abandonDownload = {},
                downloadSlots = DownloadSlots { 3 },
            )
            val control = useCase.newControl()
            launch {
                delay(10)
                control.cancel("com.example.a")
            }
            useCase.run(
                targets = targets,
                control = control,
                includePreReleases = { false },
                variantStates = emptyMap(),
                isActive = { true },
                onItem = { packageName, transform ->
                    val current = BatchInstallState(
                        phase = phases[packageName] ?: BatchInstallPhase.QUEUED,
                    )
                    phases[packageName] = transform(current).phase
                },
            )
        }

        assertEquals(BatchInstallPhase.CANCELLED, phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, phases["com.example.b"])
        assertEquals(listOf("com.example.b"), installs)
        // A cancel is not a failure: nothing should reach the summary.
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun cancelledBatchStopsDequeuingInstalls() {
        val targets = (1..3).map { updateInfo("com.example.$it") }
        val installs = mutableListOf<String>()
        val active = AtomicInteger(1)

        val (result, _) = run(
            targets,
            isActive = { active.get() == 1 },
            onInstalled = {
                installs += it
                // The user cancels right after the first hand-off.
                active.set(0)
            },
        )

        assertFalse(result.installInterrupted)
        assertTrue(installs.size < targets.size)
    }
}
