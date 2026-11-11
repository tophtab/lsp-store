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

    private fun updateInfo(packageName: String, installedPackage: String = packageName): UpdateInfo {
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
                packageName = installedPackage,
                versionCode = 10L,
                versionName = "1.0",
                label = installedPackage,
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
        isActive: () -> Boolean = { true },
        onInstalled: (String) -> Unit = {},
        downloadLimit: Int = 3,
        downloadDelay: (UpdateInfo) -> Long = { 0L },
        installLast: (UpdateInfo) -> Boolean = { false },
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
                delay(downloadDelay(info))
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
                installLast = installLast,
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

        assertEquals(2, recorder.installs)
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun installLastTargetRunsAfterEveryOtherTarget() {
        val selfPackage = "io.github.tophtab.lspstore"
        val targets = listOf(
            updateInfo(selfPackage),
            updateInfo("com.example.a"),
            updateInfo("com.example.b"),
        )
        val installed = mutableListOf<String>()

        val (result, recorder) = run(
            targets,
            // The deferred target downloads instantly; without the deferral it
            // would win the install queue while the other two are still
            // transferring and would kill the process before they land.
            downloadDelay = { info ->
                if (info.module.packageName == selfPackage) 0L else 20L
            },
            installLast = { info ->
                info.module.packageName == selfPackage ||
                    info.installed.packageName == selfPackage
            },
            onInstalled = { installed += it },
        )

        assertEquals(3, recorder.installs)
        assertEquals(selfPackage, installed.last())
        assertTrue(result.failures.isEmpty())
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases[selfPackage])
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

        assertEquals(2, recorder.installs)
        assertEquals(BatchInstallPhase.FAILED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun delegatedHandOffDoesNotBlockTheNextTarget() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))
        val installed = mutableListOf<String>()

        val (result, recorder) = run(
            targets,
            installer = { InstallOutcome.DelegatedToSystem },
            onInstalled = { installed += it },
        )

        // A cancelled installer dialog must not freeze "update all": both
        // hand-offs leave this queue, and the next one waits at the install
        // gate (released by the broadcast or the resume reconcile).
        assertEquals(2, result.delegated)
        assertEquals(listOf("com.example.a", "com.example.b"), installed)
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

        assertEquals(1, recorder.installs)
        assertEquals(BatchInstallPhase.SKIPPED, recorder.phases["com.example.a"])
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun aReleaseWithNothingForThisDeviceIsSkippedNotFailed() {
        val targets = listOf(updateInfo("com.example.a"), updateInfo("com.example.b"))

        val (result, recorder) = run(
            targets,
            prepared = { info ->
                if (info.module.packageName == "com.example.a") {
                    PreparedInstall.NoCandidate(info, "needs Android 15")
                } else {
                    ready(info)
                }
            },
        )

        assertEquals(1, recorder.installs)
        assertEquals(BatchInstallPhase.SKIPPED, recorder.phases["com.example.a"])
        // Not a failure and not something the user must decide: the background
        // check must not count or announce it (#73).
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("com.example.a"), result.noCandidate)
        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["com.example.b"])
    }

    @Test
    fun failedPreparationIsReportedAsFailure() {
        val targets = listOf(updateInfo("com.example.a"))

        val (result, recorder) = run(
            targets,
            prepared = { info -> PreparedInstall.Failed(info, "no asset") },
        )

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

        assertTrue(installs.size < targets.size)
    }

    /**
     * A linked module (repository package != installed package) is shown, keyed
     * and cancelled by its repository package. The install half of the queue used
     * to report under the installed package, so the row stayed at
     * "waiting to install" forever and the batch summary counted no outcome.
     */
    @Test
    fun linkedModuleReportsItsInstallPhaseUnderTheRepositoryKey() {
        val targets = listOf(
            updateInfo("dev.example.repo", installedPackage = "dev.example.renamed"),
        )

        val (result, recorder) = run(targets)

        assertEquals(BatchInstallPhase.COMPLETED, recorder.phases["dev.example.repo"])
        assertFalse(recorder.phases.containsKey("dev.example.renamed"))
        assertTrue(result.failures.isEmpty())
    }

    /** The same key mix-up hid failures: a failed install left the row pending. */
    @Test
    fun linkedModuleInstallFailureIsMarkedOnTheRepositoryRow() {
        val targets = listOf(
            updateInfo("dev.example.repo", installedPackage = "dev.example.renamed"),
        )

        val (result, recorder) = run(targets, installer = { InstallOutcome.Failure("boom") })

        assertEquals(BatchInstallPhase.FAILED, recorder.phases["dev.example.repo"])
        assertEquals(1, result.failures.size)
        assertEquals("dev.example.repo", result.failures.single().packageName)
    }
}
