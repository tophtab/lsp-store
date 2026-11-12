package io.github.tophtab.lspstore

import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import io.github.tophtab.lspstore.data.install.*
import io.github.tophtab.lspstore.data.repository.*
import io.github.tophtab.lspstore.domain.model.*
import io.github.tophtab.lspstore.domain.usecase.*
import io.github.tophtab.lspstore.domain.variant.*

class TrustInstallTest {
    private val repoPackage = "com.example.module"
    private val asset = ReleaseAsset("module.apk", "https://example.invalid/module.apk", null, 100, 0, null)
    private val module = io.github.tophtab.lspstore.domain.model.Module(
        repoPackage, "Module", null, null, null, null, null, 0, null, null,
        ModuleVersion.parse("10-1.0"), null, null, null,
    )
    private fun installed(pkg: String = repoPackage, code: Long = 9, xposed: Boolean = true) =
        InstalledModule(pkg, code, "1.$code", "Module", xposed, null)

    private class Driver(var result: InstallOutcome) : ApkInstallDriver {
        val files = mutableListOf<File>()
        val invalidated = mutableListOf<ReleaseAsset>()
        override suspend fun install(file: File, useRoot: Boolean, allowUi: Boolean): InstallOutcome {
            files += file
            return result
        }
        override suspend fun invalidateDownload(asset: ReleaseAsset) { invalidated += asset }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private class Recorded {
        val pending = mutableListOf<PendingInstall>()
        val signatures = mutableListOf<VariantSignature>()
        val links = mutableListOf<Pair<String, String>>()
    }

    private fun pipeline(driver: Driver, recorded: Recorded): ModuleInstaller {
        val settings = fake(SettingsRepository::class.java) { name, _ ->
            check(name == "getRootInstall")
            flowOf(false)
        }
        val library = fake(LibraryRepository::class.java) { name, args ->
            when (name) {
                "recordInstallResult" -> (args[2] as? VariantSignature)?.let { recorded.signatures += it }
                "linkModule" -> recorded.links.add(args[0] as String to args[1] as String)
                else -> error("Unexpected library operation: $name")
            }
            Unit
        }
        val currentPending = kotlinx.coroutines.flow.MutableStateFlow<Map<String, PendingInstall>>(emptyMap())
        val pending = fake(PendingInstallRepository::class.java) { name, args ->
            when (name) {
                "getPending" -> return@fake currentPending
                "markPending" -> {
                    val entry = args[0] as PendingInstall
                    recorded.pending.add(entry)
                    currentPending.value = mapOf(entry.packageName to entry)
                }
                "clearIfCurrent" -> {
                    val entry = args[0] as PendingInstall
                    val matches = currentPending.value[entry.packageName] == entry
                    if (matches) currentPending.value = emptyMap()
                    return@fake matches
                }
                "clear" -> Unit
                else -> error("Unexpected pending operation: $name")
            }
            Unit
        }
        val registry = InstallTaskRegistry()
        val serializer = SystemInstallSerializer()
        val confirmation = InstallConfirmation(library, pending, registry, serializer) { pkg ->
            val entry = currentPending.value[pkg]
            entry?.let { installed(pkg, it.versionCode).copy(versionName = it.versionName, lastUpdateTime = it.createdAt + 1L) }
        }
        val execution = InstallExecution(driver, settings, library, pending, serializer, registry, confirmation)
        val useCase = InstallPreparedApkUseCase(execution)
        return object : ModuleInstaller {
            override suspend fun install(prepared: PreparedInstall.Ready): InstallOutcome {
                val id = requireNotNull(registry.claim(prepared.info.module.packageName, prepared.info.installed.packageName))
                return useCase.install(prepared.copy(taskId = id))
            }
        }
    }

    private fun ready(file: File) = PreparedInstall.Ready(
        UpdateInfo(module, installed(), module.latestRelease!!), file, asset, ModuleVariantState(),
    )

    @Test
    fun unreadableDownloadedFileIsStillHandedToInstaller() = runBlocking {
        val file = File.createTempFile("install", ".apk")
        try {
            file.writeText("<html>temporary download error</html>")
            val driver = Driver(InstallOutcome.DelegatedToSystem)
            val recorded = Recorded()
            assertEquals(InstallOutcome.DelegatedToSystem, pipeline(driver, recorded).install(ready(file)))
            assertEquals(listOf(file), driver.files)
            assertEquals(repoPackage, recorded.pending.single().packageName)
            assertTrue(driver.invalidated.isEmpty())
        } finally { file.delete() }
    }

    @Test
    fun foreignPackageWithoutModuleDeclarationOrDeviceFitIsInstalledAndRecorded() = runBlocking {
        val file = File.createTempFile("install", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(RangeProbeTest.ManifestFixture(
                    packageName = "com.other.app", minSdk = 99, meta = emptyMap(), provider = false,
                ).bytes())
                zip.closeEntry()
            }
            val driver = Driver(InstallOutcome.Completed)
            val recorded = Recorded()
            assertEquals(InstallOutcome.Completed, pipeline(driver, recorded).install(ready(file)))
            assertEquals(listOf(file), driver.files)
            assertEquals("com.other.app", recorded.signatures.single().packageName)
            assertEquals(listOf("com.other.app" to repoPackage), recorded.links)
        } finally { file.delete() }
    }

    @Test
    fun installerFailureInvalidatesDownloadForNextAttempt() = runBlocking {
        val file = File.createTempFile("install", ".apk")
        try {
            val driver = Driver(InstallOutcome.Failure("Installer refused"))
            assertEquals(driver.result, pipeline(driver, Recorded()).install(ready(file)))
            assertEquals(listOf(file), driver.files)
            assertEquals(listOf(asset), driver.invalidated)
        } finally { file.delete() }
    }

    @Test
    fun delegatedRenamedInstallUsesArchiveVersionAndLinksOnlyAfterCompletion() = runBlocking {
        val file = File.createTempFile("install", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(RangeProbeTest.ManifestFixture(
                    packageName = "com.other.app", versionCode = 7, meta = emptyMap(),
                ).bytes())
                zip.closeEntry()
            }
            val recorded = Recorded()
            pipeline(Driver(InstallOutcome.DelegatedToSystem), recorded).install(ready(file))
            assertEquals("com.other.app", recorded.pending.single().packageName)
            assertEquals(7L, recorded.pending.single().versionCode)
            assertEquals(assetCacheKey(asset), recorded.pending.single().downloadKey)
            assertTrue(recorded.links.isEmpty())
            assertTrue(recorded.signatures.isEmpty())
        } finally { file.delete() }
    }

    @Test
    fun oldPackageStopsUpdatingWhenRenamedLatestCopyAlreadyExists() {
        val renamed = installed("com.example.renamed", 10)
        val links = mapOf(renamed.packageName to repoPackage)
        assertTrue(UpdateChecker.findUpdates(listOf(installed(), renamed), listOf(module), links = links).isEmpty())
        val next = module.copy(latestRelease = ModuleVersion.parse("11-1.1"))
        assertEquals(renamed.packageName, UpdateChecker.findUpdates(
            listOf(installed(), renamed), listOf(next), links = links,
        ).single().installed.packageName)
        assertEquals(repoPackage, UpdateChecker.findUpdates(
            listOf(installed()), listOf(next), links = links,
        ).single().installed.packageName)
    }

    @Test
    fun confirmedUserChoiceWinsOverHigherCodeOnAnotherInstalledPackage() {
        val selected = installed("com.example.selected", 8, xposed = false)
        val updates = UpdateChecker.findUpdates(
            listOf(installed(code = 10), selected), listOf(module),
            links = mapOf(selected.packageName to repoPackage),
            variantStates = mapOf(repoPackage to ModuleVariantState(
                signature = VariantSignature.ofAsset("selected_1.0.apk", selected.packageName),
            )),
        )
        assertEquals(selected.packageName, updates.single().installed.packageName)
    }

    @Test
    fun explicitlyInstalledRepositoryAppIsUpdatedWithoutAnyModuleDeclaration() {
        val updates = UpdateChecker.findUpdates(
            listOf(installed(xposed = false)), listOf(module),
            variantStates = mapOf(repoPackage to ModuleVariantState(
                signature = VariantSignature.ofAsset("module.apk", repoPackage),
            )),
        )
        assertEquals(repoPackage, updates.single().installed.packageName)
    }

    @Test
    fun cacheInvalidationAfterProcessDeathDeletesOnlyThatAsset() {
        val directory = Files.createTempDirectory("download-retry").toFile()
        try {
            val key = "0123456789abcdef"
            val badFile = File(directory, "$key-module.apk").apply { writeText("bad download") }
            val other = File(directory, "fedcba9876543210-module.apk").apply { writeText("another download") }
            invalidateCachedDownload(directory, key)
            assertFalse(badFile.exists())
            assertEquals("another download", other.readText())
            invalidateCachedDownload(directory, key)
            assertTrue(other.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun interactiveInstallWaitsUntilStoreResumes() = runBlocking {
        val state = InstallForegroundState()
        var continued = false
        val waiting = async { state.awaitResumed(); continued = true }
        yield()
        assertFalse(continued)
        state.setResumed(true)
        waiting.await()
        assertTrue(continued)
        state.setResumed(false)
        val again = async { state.awaitResumed() }
        yield()
        assertFalse(again.isCompleted)
        again.cancel()
    }

    @Test
    fun rememberedAssetFamilySurvivesVersionChangesAndKeepsNamedApiAndAbi() {
        val trigger = VariantSignature.ofAsset("VISTrigger_2.6.apk")
        assertTrue(trigger.matches("VISTrigger_2.7.apk", parseTraits("VISTrigger_2.7.apk")))
        assertFalse(trigger.matches("MiCTS_2.7.apk", parseTraits("MiCTS_2.7.apk")))
        val api = VariantSignature.ofAsset("module-api-100-x86_64-v1.0.apk")
        assertTrue(api.matches("module-api-100-x86_64-v2.0.apk", parseTraits("module-api-100-x86_64-v2.0.apk")))
        assertFalse(api.matches("module-api-102-x86_64-v2.0.apk", parseTraits("module-api-102-x86_64-v2.0.apk")))
        assertEquals("module-beta", VariantSignature.familyOf("module-v1.0-beta1.apk"))
        assertEquals("module-beta", VariantSignature.familyOf("module-v2.0-beta2.apk"))
        assertNotEquals(VariantSignature.familyOf("module-v2.0.apk"), VariantSignature.familyOf("module-v2.0-beta2.apk"))
    }

    @Test
    fun failedRootDoesNotHoldSlotWhileWaitingForForegroundFallback() = runBlocking {
        val foreground = InstallForegroundState()
        val serializer = SystemInstallSerializer()
        val roots = mutableListOf<Boolean>()
        val driver = object : ApkInstallDriver {
            override suspend fun install(file: File, useRoot: Boolean, allowUi: Boolean): InstallOutcome {
                roots += useRoot
                return if (useRoot) InstallOutcome.Failure("root unavailable") else InstallOutcome.DelegatedToSystem
            }
            override suspend fun invalidateDownload(asset: ReleaseAsset) {}
            override suspend fun awaitForeground() { foreground.awaitResumed() }
        }
        val waiting = async {
            installWithSlot(driver, serializer, File("unused.apk"), repoPackage, useRoot = true, allowUi = true)
        }
        yield()
        assertEquals(listOf(true), roots)
        assertFalse(serializer.isHeldBy(repoPackage))
        assertFalse(waiting.isCompleted)
        foreground.setResumed(true)
        assertEquals(InstallOutcome.DelegatedToSystem, waiting.await())
        assertEquals(listOf(true, false), roots)
        assertTrue(serializer.isHeldBy(repoPackage))
    }

    @Test
    fun storePausingWhileQueueWaitsDoesNotHoldSlotOrLaunchNextInstaller() = runBlocking {
        val foreground = InstallForegroundState().apply { setResumed(true) }
        val serializer = SystemInstallSerializer()
        serializer.awaitFreeAndMarkPending("previous.package")
        var handedOff = false
        val driver = object : ApkInstallDriver {
            override val canLaunchUi: Boolean get() = foreground.isResumed
            override suspend fun install(file: File, useRoot: Boolean, allowUi: Boolean): InstallOutcome {
                handedOff = true
                return InstallOutcome.DelegatedToSystem
            }
            override suspend fun invalidateDownload(asset: ReleaseAsset) {}
            override suspend fun awaitForeground() { foreground.awaitResumed() }
        }
        val waiting = async {
            installWithSlot(driver, serializer, File("unused.apk"), repoPackage, useRoot = false, allowUi = true)
        }
        yield()
        foreground.setResumed(false)
        serializer.markCompleted("previous.package")
        yield()
        assertFalse(handedOff)
        assertFalse(serializer.isHeldBy(repoPackage))
        foreground.setResumed(true)
        assertEquals(InstallOutcome.DelegatedToSystem, waiting.await())
        assertTrue(handedOff)
    }
}
