package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.*
import io.github.tophtab.lspstore.domain.usecase.*
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledListSnapshotTest {
    private fun snapshot(
        installed: List<InstalledModule>,
        modules: List<Module> = listOf(uiModule()),
        links: Map<String, String> = emptyMap(),
        preferences: InstalledListPreferences = InstalledListPreferences(),
    ): InstalledListSnapshot = InstalledListSnapshot.build(
        InstalledCatalog.build(RepositoryIndex.build(modules), InstalledCopies.build(installed, links), preferences.variantStates),
        preferences,
    )

    @Test
    fun directLinkedAndUnknownCopiesShareOneClassification() {
        val result = snapshot(
            listOf(uiInstalled(), uiInstalled("renamed", xposed = false), uiInstalled("unknown"), uiInstalled("ordinary", xposed = false)),
            listOf(uiModule(), uiModule("repo")), mapOf("renamed" to "repo"),
        )
        assertEquals(setOf("module", "repo"), result.updates.map { it.module.packageName }.toSet())
        assertEquals(listOf("unknown"), result.unknown.map { it.packageName })
        assertEquals(3, result.installedCount)
        assertEquals(mapOf("module" to "module", "renamed" to "repo"), result.repoPackageByInstalled)
    }

    @Test
    fun aConfirmedReplacementWinsAndUninstallFallsBackToTheNewestRemainingCopy() {
        val old = uiInstalled(code = 10)
        val replacement = uiInstalled("replacement", code = 1, xposed = false, updatedAt = 100)
        val module = uiModule().copy(latestRelease = ModuleVersion.parse("20-new"))
        val release = uiRelease("15-confirmed")
        val confirmed = ConfirmedInstallRecord(ResolvedInstallTarget.of(release, release.apkAssets.single()), "replacement", 1, "old", 100)
        val preferences = InstalledListPreferences(variantStates = mapOf("module" to ModuleVariantState(confirmedInstall = confirmed)))
        val links = mapOf("replacement" to "module")
        assertEquals("replacement", snapshot(listOf(old, replacement), listOf(module), links, preferences).updates.single().installed.packageName)
        assertEquals("module", snapshot(listOf(old), listOf(module), links, preferences).updates.single().installed.packageName)
    }

    @Test
    fun legacyVariantKeysUseTheSameCopyOnThePageAndInTheUpdateChecker() {
        val older = uiInstalled("renamed", code = 1, updatedAt = 100)
        val newer = uiInstalled(code = 8)
        val release = uiRelease("1-confirmed")
        val record = ConfirmedInstallRecord(ResolvedInstallTarget.of(release, release.apkAssets.single()), "renamed", 1, "old", 100)
        val preferences = InstalledListPreferences(variantStates = mapOf("renamed" to ModuleVariantState(confirmedInstall = record)))
        val modules = listOf(uiModule().copy(latestRelease = ModuleVersion.parse("9-new")))
        val links = mapOf("renamed" to "module")
        val result = snapshot(listOf(older, newer), modules, links, preferences)
        val updates = UpdateChecker.findUpdates(listOf(older, newer), modules, links = links, variantStates = preferences.variantStates)
        assertEquals(updates, result.updates)
        assertEquals("renamed", updates.single().installed.packageName)
        assertEquals(older, result.installedByRepo["module"])
        assertEquals(updates.map { it.installed.packageName }.toSet(), result.repoPackageByInstalled.keys)
    }

    @Test
    fun ignoredSkippedAndLegacyPreReleasePreferencesKeepTheirRules() {
        val module = uiModule().copy(latestBetaRelease = ModuleVersion.parse("3-beta"))
        val installed = listOf(uiInstalled("renamed"))
        val links = mapOf("renamed" to "module")
        val preferences = InstalledListPreferences(preReleases = setOf("renamed"))
        val result = snapshot(installed, listOf(module), links, preferences)
        assertEquals("3-beta", result.updates.single().latest.raw)
        assertEquals(setOf("renamed", "module"), result.preReleases)
        for (key in listOf("renamed", "module")) {
            assertTrue(snapshot(installed, listOf(module), links, preferences.copy(ignoredUpdates = setOf(key))).updates.isEmpty())
            val skipped = preferences.copy(skipped = mapOf(key to SkippedVersion("3-beta")))
            assertTrue(snapshot(installed, listOf(module), links, skipped).updates.isEmpty())
        }
    }

    @Test
    fun pendingCopiesLeaveUpdatesAndPendingEntriesKeepTheirCreationOrder() {
        val preferences = InstalledListPreferences(pending = mapOf(
            "module" to PendingInstall("module", createdAt = 10),
            "other" to PendingInstall("other", createdAt = 20),
        ))
        val result = snapshot(listOf(uiInstalled()), preferences = preferences)
        assertTrue(result.updates.isEmpty())
        assertEquals(listOf("other", "module"), result.pendingInstalls.map { it.packageName })
        assertEquals(listOf("module"), result.upToDate.map { it.packageName })
    }

    @Test
    fun sameCodeReuploadsAndAlphabeticalIdleOrderArePreserved() {
        val module = uiModule().copy(latestRelease = ModuleVersion.parse("1-reupload"), latestReleaseTime = "2023-11-15T00:00:00Z")
        val installed = uiInstalled(updatedAt = 1_700_000_000_000)
        assertEquals(1, snapshot(listOf(installed), listOf(module)).updates.size)
        val result = snapshot(listOf(uiInstalled("z", "Alpha", code = 2), uiInstalled("a", "alpha", code = 2)), listOf(uiModule("z"), uiModule("a")))
        assertEquals(listOf("a", "z"), result.upToDate.map { it.packageName })
    }

    @Test
    fun preparedIndexesTraverseRawInputsOnceAndAreReusedByEverySnapshot() {
        val modules = CountingList(List(400) { uiModule("repo$it").copy(latestRelease = ModuleVersion.parse("10-new")) })
        val installed = CountingList(List(1_200) { uiInstalled("copy$it", code = (it % 3 + 1).toLong()) })
        val links = (0 until 1_200).associate { "copy$it" to "repo${it / 3}" }
        val repository = RepositoryIndex.build(modules)
        val copies = InstalledCopies.build(installed, links)
        repeat(20) {
            val catalog = InstalledCatalog.build(repository, copies)
            assertEquals(400, UpdateChecker.findUpdates(catalog).size)
            assertEquals(400, InstalledListSnapshot.build(catalog, InstalledListPreferences()).installedCount)
            assertEquals("copy2", copies.forRepo("repo0")?.packageName)
        }
        assertEquals(400, modules.reads)
        assertEquals(1_200, installed.reads)
    }

    @Test
    fun aQueuedObsoleteCalculationCannotPublishAfterPreferencesChange() = runTest {
        val dispatcher = QueuedDispatcher()
        val preferences = MutableStateFlow(InstalledListPreferences())
        val results = mutableListOf<InstalledListSnapshot>()
        backgroundScope.launch {
            installedListSnapshots(
                MutableStateFlow(RepositoryIndex.build(listOf(uiModule()))),
                MutableStateFlow(InstalledCopies.build(listOf(uiInstalled()), emptyMap())),
                preferences, dispatcher,
            ).collect { results += it }
        }
        runCurrent()
        preferences.value = InstalledListPreferences(ignoredUpdates = setOf("module"))
        runCurrent()
        repeat(3) { dispatcher.runAll(); runCurrent() }
        assertEquals(1, results.size)
        assertTrue(results.single().updates.isEmpty())
        assertEquals(setOf("module"), results.single().ignoredUpdates)
    }

    private class CountingList<T>(private val entries: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = entries.size
        override fun get(index: Int): T { reads++; return entries[index] }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued += block }
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }
}
