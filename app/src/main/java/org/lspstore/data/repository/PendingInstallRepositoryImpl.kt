package org.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lspstore.domain.model.InstalledModule
import org.lspstore.domain.model.PendingInstall
import org.lspstore.domain.usecase.prunePendingInstalls

private val Context.pendingInstallDataStore by preferencesDataStore(name = "pending_installs")

interface PendingInstallRepository {
    /** package name -> pending install */
    val pending: Flow<Map<String, PendingInstall>>

    suspend fun markPending(install: PendingInstall)

    suspend fun clear(packageName: String)

    suspend fun clearAll()

    /** Drops stale entries and returns the ones whose target version has landed. */
    suspend fun prune(
        installed: List<InstalledModule>,
        now: Long = System.currentTimeMillis(),
    ): List<PendingInstall>
}

/**
 * Persists installs that were handed to an external installer but have not been
 * confirmed by a package broadcast yet, mirroring komi-store's
 * `isPendingInstall` flag on its installed-app records.
 *
 * The state survives process death so the UI can keep showing "pending install"
 * until [clear] (package broadcast) or [prune] (target version reached / stale).
 */
class PendingInstallRepositoryImpl(private val context: Context) : PendingInstallRepository {

    private object Keys {
        val INSTALLS = stringPreferencesKey("pending_installs")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val pending: Flow<Map<String, PendingInstall>> = context.pendingInstallDataStore.data
        .map { prefs -> decode(prefs[Keys.INSTALLS]).associateBy { it.packageName } }

    override suspend fun markPending(install: PendingInstall) {
        context.pendingInstallDataStore.edit { prefs ->
            val current = decode(prefs[Keys.INSTALLS])
                .filterNot { it.packageName == install.packageName }
            prefs[Keys.INSTALLS] = encode(current + install)
        }
    }

    override suspend fun clear(packageName: String) {
        context.pendingInstallDataStore.edit { prefs ->
            val current = decode(prefs[Keys.INSTALLS])
            val updated = current.filterNot { it.packageName == packageName }
            if (updated.size != current.size) {
                prefs[Keys.INSTALLS] = encode(updated)
            }
        }
    }

    override suspend fun clearAll() {
        context.pendingInstallDataStore.edit { it[Keys.INSTALLS] = "" }
    }

    /**
     * Drops stale entries and returns the ones whose target version has landed.
     *
     * The decision table itself is [prunePendingInstalls] (pure, JVM-tested);
     * this method only reads and rewrites the store.
     */
    override suspend fun prune(
        installed: List<InstalledModule>,
        now: Long,
    ): List<PendingInstall> {
        var landed = emptyList<PendingInstall>()
        context.pendingInstallDataStore.edit { prefs ->
            val current = decode(prefs[Keys.INSTALLS])
            if (current.isEmpty()) return@edit
            val result = prunePendingInstalls(current, installed, now)
            if (result.kept.size != current.size) {
                prefs[Keys.INSTALLS] = encode(result.kept)
            }
            landed = result.landed
        }
        return landed
    }

    private fun decode(raw: String?): List<PendingInstall> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(PendingInstallState.serializer(), raw).installs
        }.getOrDefault(emptyList())
    }

    private fun encode(installs: List<PendingInstall>): String =
        json.encodeToString(PendingInstallState.serializer(), PendingInstallState(installs))

    @Serializable
    private data class PendingInstallState(
        val installs: List<PendingInstall> = emptyList(),
    )

}
