package io.github.tophtab.lspstore.data.install

import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** App-wide ownership, claimed before release resolution, and retained across UI hand-offs. */
class InstallTaskRegistry(initiallyReady: Boolean = true) {
    private val ready = CompletableDeferred<Unit>().apply { if (initiallyReady) complete(Unit) }
    private val state = MutableStateFlow<Map<String, ModuleInstallRun>>(emptyMap())
    val runs = state.asStateFlow()
    private val aliases = mutableMapOf<String, Set<String>>()
    private val cancellations = mutableMapOf<String, () -> Unit>()

    @Synchronized
    fun restore(pending: Collection<PendingInstall>) {
        pending.forEach { entry ->
            val repo = entry.repoPackageName ?: entry.packageName
            val id = entry.taskId ?: legacyId(entry)
            state.value = state.value + (repo to ModuleInstallRun(repo, entry.target?.asset, taskId = id, stage = InstallStage.PENDING_CONFIRMATION))
            aliases[id] = setOf(repo, entry.packageName)
        }
        ready.complete(Unit)
    }

    suspend fun claim(repo: String, installedPackage: String? = null): String? {
        ready.await()
        return claimReady(repo, installedPackage)
    }

    @Synchronized
    private fun claimReady(repo: String, installedPackage: String?): String? {
        val keys = setOfNotNull(repo, installedPackage)
        if (aliases.values.any { it.intersect(keys).isNotEmpty() }) return null
        val id = UUID.randomUUID().toString()
        aliases[id] = keys
        state.value = state.value + (repo to ModuleInstallRun(repo, null, taskId = id))
        return id
    }

    @Synchronized
    fun associate(id: String, packageName: String): Boolean {
        val own = aliases[id] ?: return false
        if (aliases.any { (other, names) -> other != id && packageName in names }) return false
        aliases[id] = own + packageName
        return true
    }

    @Synchronized
    fun update(id: String, transform: (ModuleInstallRun) -> ModuleInstallRun) {
        val entry = state.value.entries.firstOrNull { it.value.taskId == id } ?: return
        state.value = state.value + (entry.key to transform(entry.value))
    }

    fun stage(id: String, stage: InstallStage) = update(id) { it.copy(stage = stage) }
    fun asset(id: String, asset: ReleaseAsset) = update(id) { it.copy(asset = asset, total = asset.size) }

    @Synchronized
    fun release(id: String) {
        state.value = state.value.filterValues { it.taskId != id }
        aliases.remove(id)
        cancellations.remove(id)
    }

    @Synchronized
    fun onCancel(id: String, action: () -> Unit) { if (id in aliases) cancellations[id] = action }

    @Synchronized
    fun cancel(repo: String) {
        val run = state.value[repo] ?: return
        if (run.stage in setOf(InstallStage.QUEUED, InstallStage.PREPARING, InstallStage.DOWNLOADING, InstallStage.WAITING_TO_INSTALL)) cancellations[run.taskId]?.invoke()
    }

    fun pendingIds(): Set<String> = state.value.values.filter { it.stage == InstallStage.PENDING_CONFIRMATION }.map { it.taskId }.toSet()

    fun owns(id: String): Boolean = state.value.values.any { it.taskId == id }
    fun isRunning(repo: String): Boolean = state.value.containsKey(repo)
    fun runningPackages(): Set<String> = state.value.keys

    companion object {
        fun legacyId(pending: PendingInstall): String = "legacy:${pending.packageName}:${pending.createdAt}"
        fun idOf(pending: PendingInstall): String = pending.taskId ?: legacyId(pending)
    }
}
