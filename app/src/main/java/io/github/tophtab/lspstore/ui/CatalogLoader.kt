package io.github.tophtab.lspstore.ui

import io.github.tophtab.lspstore.data.repository.InstalledModulesRepository
import io.github.tophtab.lspstore.data.repository.ModuleRepository
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Cache-first catalog loading shared by the browsing and installed screens. */
internal class CatalogLoader(
    private val modules: ModuleRepository,
    private val installed: InstalledModulesRepository,
) {
    /** The caller owns the job, dataset and refresh tick; scans run alongside the refresh. */
    suspend fun load(
        hasIndex: Boolean,
        forceRefresh: Boolean,
        forceScan: Boolean,
        onCache: (List<Module>, List<InstalledModule>?) -> Unit,
        onLoading: (isLoading: Boolean, isRefreshing: Boolean) -> Unit,
        onScanned: suspend (List<InstalledModule>) -> Unit,
        onRefreshed: (List<Module>) -> Unit,
        onFailure: (Throwable?) -> Unit,
    ) = coroutineScope {
        ensureActive()
        val showRefresh = forceRefresh || !modules.hasFetchedIndexThisSession()
        var hasContent = hasIndex
        var paintedFromCache = false
        if (!hasIndex) {
            modules.getCachedIndex()?.let { cached ->
                val cachedInstalled = installed.getCachedInstalledModules()
                ensureActive()
                onCache(cached, cachedInstalled)
                hasContent = cached.isNotEmpty()
                paintedFromCache = true
                onLoading(false, showRefresh)
            }
        }
        if (!paintedFromCache) {
            onLoading(!hasIndex, showRefresh && hasIndex)
        }

        launch {
            val scanned = try {
                installed.getInstalledModules(force = forceScan)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // An unreadable PackageManager scan leaves the cached list in place.
                return@launch
            }
            ensureActive()
            onScanned(scanned)
        }

        try {
            val refreshed = modules.refreshIndexIfNeeded(forceRefresh)
            ensureActive()
            onRefreshed(refreshed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ensureActive()
            // A refresh failure is visible only when there is no index to keep showing.
            onFailure(if (hasContent) null else failure)
        }
    }
}
