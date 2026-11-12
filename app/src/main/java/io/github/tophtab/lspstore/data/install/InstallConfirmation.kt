package io.github.tophtab.lspstore.data.install

import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.data.repository.LibraryRepository
import io.github.tophtab.lspstore.data.repository.PendingInstallRepository
import io.github.tophtab.lspstore.domain.model.ConfirmedInstallRecord
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.usecase.pendingInstallLanded
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The only path that writes installation provenance, before removing pending. */
class InstallConfirmation(
    private val library: LibraryRepository,
    private val pendingRepository: PendingInstallRepository,
    private val registry: InstallTaskRegistry,
    private val serializer: SystemInstallSerializer,
    private val readInstalled: suspend (String) -> InstalledModule?,
) {
    private val mutex = Mutex()

    suspend fun confirm(pending: PendingInstall): Boolean = mutex.withLock {
        if (pendingRepository.pending.first()[pending.packageName] != pending) return@withLock false
        val installed = readInstalled(pending.packageName) ?: return@withLock false
        if (!pendingInstallLanded(pending, installed.versionCode, installed.lastUpdateTime, installed.versionName)) return@withLock false
        val repo = pending.repoPackageName ?: pending.packageName
        if (pending.packageName != repo) library.linkModule(pending.packageName, repo)
        val target = pending.target
        if (target?.apkPackageName != null && target.apkVersionCode != null) {
            library.recordConfirmedInstall(repo, ConfirmedInstallRecord(target, installed.packageName, installed.versionCode, installed.versionName, installed.lastUpdateTime))
        } else {
            // Legacy pending can settle, but has no trustworthy release source.
            library.recordInstallResult(repo, true, pending.assetName.takeIf { it.isNotBlank() }?.let {
                VariantSignature.ofAsset(it, pending.packageName)
            })
        }
        pendingRepository.clearIfCurrent(pending)
        val id = InstallTaskRegistry.idOf(pending)
        serializer.markCompleted(pending.packageName, id)
        registry.release(id)
        true
    }

    suspend fun cancel(pending: PendingInstall, beforeRelease: suspend () -> Unit = {}): Boolean = mutex.withLock {
        if (pendingRepository.pending.first()[pending.packageName] != pending) return@withLock false
        beforeRelease()
        if (!pendingRepository.clearIfCurrent(pending)) return@withLock false
        val id = InstallTaskRegistry.idOf(pending)
        serializer.markCompleted(pending.packageName, id)
        registry.release(id)
        true
    }

    suspend fun installed(packageName: String): InstalledModule? = readInstalled(packageName)
}
