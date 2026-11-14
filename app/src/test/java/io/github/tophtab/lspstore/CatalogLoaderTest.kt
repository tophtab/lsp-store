package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.ui.CatalogLoader
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLoaderTest {
    @Test
    fun cachePaintsBeforeBothParallelOperationsFinish() = runTest {
        val fixture = Fixture()
        val cached = listOf(uiModule("cached"))
        val cachedInstalled = listOf(uiInstalled("cached"))
        fixture.modules.cache = cached
        fixture.installed.cache = cachedInstalled
        val refresh = CompletableDeferred<List<Module>>()
        val scan = CompletableDeferred<List<InstalledModule>>()
        fixture.modules.refresh = { refresh.await() }
        fixture.installed.scan = { scan.await() }

        val job = launch { fixture.load() }
        runCurrent()
        assertEquals(cached, fixture.index)
        assertEquals(cachedInstalled, fixture.packages)
        assertEquals(listOf(false to true), fixture.loading)
        assertEquals(listOf(false), fixture.modules.refreshForces)
        assertEquals(listOf(false), fixture.installed.scanForces)
        assertTrue(fixture.refreshed.isEmpty())

        val fresh = listOf(uiModule("fresh"))
        refresh.complete(fresh)
        runCurrent()
        assertEquals(listOf(fresh), fixture.refreshed)
        assertFalse(job.isCompleted)
        scan.complete(listOf(uiInstalled("fresh")))
        job.join()
        assertEquals(listOf(uiInstalled("fresh")), fixture.packages)
    }

    @Test
    fun anEmptyCacheStillPaintsAnEmptyFirstScreen() = runTest {
        val fixture = Fixture()
        fixture.modules.cache = emptyList()
        fixture.load()
        assertEquals(1, fixture.cachePaints)
        assertEquals(listOf(false to true), fixture.loading)
        assertEquals(1, fixture.installed.cacheReads)
    }

    @Test
    fun noCacheUsesTheInitialLoadingState() = runTest {
        val fixture = Fixture()
        fixture.load()
        assertEquals(0, fixture.cachePaints)
        assertEquals(listOf(true to false), fixture.loading)
        assertEquals(0, fixture.installed.cacheReads)
        assertEquals(listOf(listOf(uiModule("fresh"))), fixture.refreshed)
    }

    @Test
    fun anExistingSessionIndexSkipsDiskAndTheRefreshIndicator() = runTest {
        val fixture = Fixture()
        fixture.modules.fetched = true
        fixture.load(hasIndex = true)
        assertEquals(0, fixture.modules.cacheReads)
        assertEquals(0, fixture.installed.cacheReads)
        assertEquals(listOf(false to false), fixture.loading)
    }

    @Test
    fun anExistingIndexStillShowsTheAutomaticFirstSessionRefresh() = runTest {
        val fixture = Fixture()
        fixture.load(hasIndex = true)
        assertEquals(listOf(false to true), fixture.loading)
    }

    @Test
    fun homeRefreshKeepsAnOrdinaryScanAndInstalledRefreshForcesIt() = runTest {
        for (forceScan in listOf(false, true)) {
            val fixture = Fixture()
            fixture.modules.fetched = true
            fixture.load(hasIndex = true, forceRefresh = true, forceScan = forceScan)
            assertEquals(listOf(true), fixture.modules.refreshForces)
            assertEquals(listOf(forceScan), fixture.installed.scanForces)
            assertEquals(listOf(false to true), fixture.loading)
        }
    }

    @Test
    fun aRefreshFailureKeepsNonemptyContentButReportsFailureWithoutIt() = runTest {
        val failure = IOException("offline")
        for (cache in listOf(null, emptyList(), listOf(uiModule("cached")))) {
            val fixture = Fixture()
            fixture.modules.cache = cache
            fixture.modules.refresh = { throw failure }
            fixture.load()
            assertEquals(1, fixture.failures.size)
            if (cache.isNullOrEmpty()) assertSame(failure, fixture.failures.single())
            else assertNull(fixture.failures.single())
            assertEquals(cache.orEmpty(), fixture.index)
        }
        val warm = Fixture()
        warm.modules.refresh = { throw failure }
        warm.load(hasIndex = true)
        assertNull(warm.failures.single())
    }

    @Test
    fun aScanFailureKeepsCachedPackagesAndDoesNotFailTheIndexRefresh() = runTest {
        val fixture = Fixture()
        fixture.modules.cache = listOf(uiModule("cached"))
        fixture.installed.cache = listOf(uiInstalled("cached"))
        fixture.installed.scan = { throw IOException("PackageManager unavailable") }
        fixture.load()
        assertEquals(listOf(uiInstalled("cached")), fixture.packages)
        assertEquals(1, fixture.refreshed.size)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun cancellingALoadDoesNotReportAFailureOrPublishLateResults() = runTest {
        val fixture = Fixture()
        fixture.modules.refresh = {
            withContext(NonCancellable) { delay(100) }
            throw IOException("late failure")
        }
        fixture.installed.scan = {
            withContext(NonCancellable) { delay(100) }
            listOf(uiInstalled("late"))
        }
        val job = launch { fixture.load() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(fixture.failures.isEmpty())
        assertTrue(fixture.refreshed.isEmpty())
        assertTrue(fixture.packages.isEmpty())
    }

    @Test
    fun aReplacementRefreshIsNotOverwrittenByCancelledWork() = runTest {
        val fixture = Fixture()
        var refreshes = 0
        fixture.modules.refresh = {
            if (++refreshes == 1) {
                withContext(NonCancellable) { delay(100) }
                listOf(uiModule("old"))
            } else listOf(uiModule("new"))
        }
        var scans = 0
        fixture.installed.scan = {
            if (++scans == 1) {
                withContext(NonCancellable) { delay(100) }
                listOf(uiInstalled("old"))
            } else listOf(uiInstalled("new"))
        }
        val old = launch { fixture.load() }
        runCurrent()
        old.cancel()
        launch { fixture.load(forceRefresh = true) }
        advanceUntilIdle()
        assertEquals(listOf(listOf(uiModule("new"))), fixture.refreshed)
        assertEquals(listOf(uiInstalled("new")), fixture.packages)
        assertEquals(2, fixture.loading.size)
        assertTrue(fixture.failures.isEmpty())
    }

    private class Fixture {
        val modules = FakeModules()
        val installed = FakeInstalled()
        val loading = mutableListOf<Pair<Boolean, Boolean>>()
        val refreshed = mutableListOf<List<Module>>()
        val failures = mutableListOf<Throwable?>()
        var cachePaints = 0
        var index = emptyList<Module>()
        var packages = emptyList<InstalledModule>()

        suspend fun load(hasIndex: Boolean = false, forceRefresh: Boolean = false, forceScan: Boolean = false) {
            CatalogLoader(modules, installed).load(
                hasIndex = hasIndex,
                forceRefresh = forceRefresh,
                forceScan = forceScan,
                onCache = { cached, scanned ->
                    cachePaints++
                    index = cached
                    scanned?.let { packages = it }
                },
                onLoading = { busy, refreshing -> loading += busy to refreshing },
                onScanned = { packages = it },
                onRefreshed = { index = it; refreshed += it },
                onFailure = { failures += it },
            )
        }
    }

    private class FakeModules : ModuleRepository {
        var cache: List<Module>? = null
        var cacheReads = 0
        var fetched = false
        var refresh: suspend (Boolean) -> List<Module> = { listOf(uiModule("fresh")) }
        val refreshForces = mutableListOf<Boolean>()
        override suspend fun getCachedIndex(): List<Module>? { cacheReads++; return cache }
        override suspend fun refreshIndexIfNeeded(force: Boolean): List<Module> {
            refreshForces += force
            return refresh(force)
        }
        override fun hasFetchedIndexThisSession() = fetched
        override suspend fun getIndex(force: Boolean): List<Module> = error("Unexpected getIndex")
        override suspend fun getDetail(packageName: String): ModuleDetail = error("Unexpected detail")
        override suspend fun clearCache() = Unit
        override fun cacheSizeBytes() = 0L
    }

    private class FakeInstalled : InstalledModulesRepository {
        var cache: List<InstalledModule>? = null
        var cacheReads = 0
        var scan: suspend (Boolean) -> List<InstalledModule> = { emptyList() }
        val scanForces = mutableListOf<Boolean>()
        override suspend fun getCachedInstalledModules(): List<InstalledModule>? { cacheReads++; return cache }
        override suspend fun getInstalledModules(force: Boolean): List<InstalledModule> {
            scanForces += force
            return scan(force)
        }
        override suspend fun getInstalledModule(packageName: String): InstalledModule? = error("Unexpected lookup")
    }
}
