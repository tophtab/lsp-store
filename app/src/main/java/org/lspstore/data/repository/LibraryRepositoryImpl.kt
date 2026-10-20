package org.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.lspstore.domain.variant.ModuleVariantState
import org.lspstore.domain.variant.VariantSignature
import org.lspstore.domain.variant.onInstallFailed
import org.lspstore.domain.variant.onInstallSucceeded
import org.lspstore.domain.variant.parseTraits

private val Context.libraryDataStore by preferencesDataStore(name = "library")

/**
 * User-curated lists: favourites, recently viewed and ignored updates.
 * Ordered lists are stored newline separated; skipped updates as
 * "package:versionCode" lines.
 */
interface LibraryRepository {
    val favourites: Flow<List<String>>
    val recentlyViewed: Flow<List<String>>
    val skippedUpdates: Flow<Map<String, Long>>
    val ignoredUpdates: Flow<Set<String>>
    val searchHistory: Flow<List<String>>
    val links: Flow<Map<String, String>>
    val variantStates: Flow<Map<String, ModuleVariantState>>

    suspend fun toggleFavourite(packageName: String)
    suspend fun addRecentlyViewed(packageName: String)
    suspend fun clearRecentlyViewed()
    suspend fun skipUpdate(packageName: String, versionCode: Long)
    suspend fun unskipUpdate(packageName: String)
    suspend fun clearSkippedUpdates()
    suspend fun setUpdateCheckEnabled(packageName: String, enabled: Boolean)
    suspend fun clearIgnoredUpdates()
    suspend fun addSearchQuery(query: String)
    suspend fun clearSearchHistory()
    suspend fun linkModule(installedPackage: String, repoPackage: String)
    suspend fun unlinkModule(installedPackage: String)
    suspend fun setVariantState(packageName: String, state: ModuleVariantState)
    suspend fun clearVariantState(packageName: String)
    suspend fun recordInstallResult(
        packageName: String,
        ok: Boolean,
        signature: VariantSignature? = null,
    )

    /** Idempotent one-off migration of the legacy asset-name preferences. */
    suspend fun migratePreferredVariants()
}

class LibraryRepositoryImpl(private val context: Context) : LibraryRepository {

    private object Keys {
        val FAVOURITES = stringPreferencesKey("favourites")
        val RECENTLY_VIEWED = stringPreferencesKey("recently_viewed")
        val SKIPPED = stringPreferencesKey("skipped")
        val IGNORED_UPDATES = stringPreferencesKey("ignored_updates")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val LINKS = stringPreferencesKey("manual_links")
        val PREFERRED_VARIANTS = stringPreferencesKey("preferred_variants")
        val VARIANT_STATE = stringPreferencesKey("variant_state")
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val variantStatesSerializer =
        MapSerializer(String.serializer(), ModuleVariantState.serializer())

    override val favourites: Flow<List<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.FAVOURITES]) }

    override val recentlyViewed: Flow<List<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.RECENTLY_VIEWED]) }

    override val skippedUpdates: Flow<Map<String, Long>> = context.libraryDataStore.data
        .map { decodeSkipped(it[Keys.SKIPPED]) }

    /** Installed or repository package names whose updates the user ignored. */
    override val ignoredUpdates: Flow<Set<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.IGNORED_UPDATES]).toSet() }

    override val searchHistory: Flow<List<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.SEARCH_HISTORY]) }

    /** installed package name -> repository module package name */
    override val links: Flow<Map<String, String>> = context.libraryDataStore.data
        .map { decodeLinks(it[Keys.LINKS]) }

    /** repository module package name -> variant status + semantic signature */
    override val variantStates: Flow<Map<String, ModuleVariantState>> = context.libraryDataStore.data
        .map { decodeVariantStates(it[Keys.VARIANT_STATE]) }

    override suspend fun toggleFavourite(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.FAVOURITES])
            prefs[Keys.FAVOURITES] = encodeList(
                if (packageName in current) current - packageName else listOf(packageName) + current,
            )
        }
    }

    override suspend fun addRecentlyViewed(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.RECENTLY_VIEWED])
            val updated = (listOf(packageName) + current.filterNot { it == packageName }).take(MAX_RECENT)
            prefs[Keys.RECENTLY_VIEWED] = encodeList(updated)
        }
    }

    override suspend fun clearRecentlyViewed() {
        context.libraryDataStore.edit { it[Keys.RECENTLY_VIEWED] = "" }
    }

    override suspend fun skipUpdate(packageName: String, versionCode: Long) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            current[packageName] = versionCode
            prefs[Keys.SKIPPED] = current.entries.joinToString("\n") { "${it.key}:${it.value}" }
        }
    }

    override suspend fun unskipUpdate(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            current.remove(packageName)
            prefs[Keys.SKIPPED] = current.entries.joinToString("\n") { "${it.key}:${it.value}" }
        }
    }

    override suspend fun clearSkippedUpdates() {
        context.libraryDataStore.edit { it[Keys.SKIPPED] = "" }
    }

    override suspend fun setUpdateCheckEnabled(packageName: String, enabled: Boolean) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.IGNORED_UPDATES])
            prefs[Keys.IGNORED_UPDATES] = encodeList(
                if (enabled) current - packageName else listOf(packageName) + current,
            )
        }
    }

    override suspend fun clearIgnoredUpdates() {
        context.libraryDataStore.edit { it[Keys.IGNORED_UPDATES] = "" }
    }

    override suspend fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.SEARCH_HISTORY])
            val updated = (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(MAX_SEARCH_HISTORY)
            prefs[Keys.SEARCH_HISTORY] = encodeList(updated)
        }
    }

    override suspend fun clearSearchHistory() {
        context.libraryDataStore.edit { it[Keys.SEARCH_HISTORY] = "" }
    }

    override suspend fun linkModule(installedPackage: String, repoPackage: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeLinks(prefs[Keys.LINKS]).toMutableMap()
            current[installedPackage] = repoPackage
            prefs[Keys.LINKS] = encodeLinks(current)
        }
    }

    override suspend fun unlinkModule(installedPackage: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeLinks(prefs[Keys.LINKS]).toMutableMap()
            current.remove(installedPackage)
            prefs[Keys.LINKS] = encodeLinks(current)
        }
    }

    /**
     * Pins the semantic variant used the next time this module is updated, and
     * marks it trusted right away: the user has stated what they want.
     */
    override suspend fun setVariantState(packageName: String, state: ModuleVariantState) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            current[packageName] = state
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(current)
        }
    }

    override suspend fun clearVariantState(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            current.remove(packageName)
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(current)
        }
    }

    /**
     * Records one install attempt. Success remembers the semantic signature and
     * resets the failure count; three consecutive failures give up and mark the
     * module as not applicable on this device.
     */
    override suspend fun recordInstallResult(
        packageName: String,
        ok: Boolean,
        signature: VariantSignature?,
    ) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            val state = current[packageName] ?: ModuleVariantState()
            current[packageName] = if (ok) {
                state.onInstallSucceeded(signature)
            } else {
                state.onInstallFailed()
            }
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(current)
        }
    }

    /**
     * One-off migration from v1's asset file names to semantic signatures. The
     * file name is the only information those entries had.
     */
    override suspend fun migratePreferredVariants() {
        context.libraryDataStore.edit { prefs ->
            val legacy = decodeLinks(prefs[Keys.PREFERRED_VARIANTS])
            if (legacy.isEmpty()) return@edit
            val states = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            val now = System.currentTimeMillis()
            legacy.forEach { (packageName, assetName) ->
                if (packageName !in states) {
                    states[packageName] = ModuleVariantState(
                        status = ModuleVariantState.Status.TRUSTED,
                        signature = VariantSignature.of(parseTraits(assetName)),
                        updatedAt = now,
                    )
                }
            }
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(states)
            prefs[Keys.PREFERRED_VARIANTS] = ""
        }
    }

    private fun decodeVariantStates(raw: String?): Map<String, ModuleVariantState> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(variantStatesSerializer, raw)
        }.getOrDefault(emptyMap())
    }

    private fun encodeVariantStates(states: Map<String, ModuleVariantState>): String =
        json.encodeToString(variantStatesSerializer, states)

    private fun decodeList(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun encodeList(values: List<String>): String = values.joinToString("\n")

    private fun decodeSkipped(raw: String?): Map<String, Long> =
        raw?.split('\n')
            ?.mapNotNull { line ->
                val index = line.lastIndexOf(':')
                if (index <= 0) return@mapNotNull null
                val code = line.substring(index + 1).toLongOrNull() ?: return@mapNotNull null
                line.substring(0, index) to code
            }
            ?.toMap()
            ?: emptyMap()

    private fun decodeLinks(raw: String?): Map<String, String> =
        raw?.split('\n')
            ?.mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@mapNotNull null
                line.substring(0, index) to line.substring(index + 1)
            }
            ?.toMap()
            ?: emptyMap()

    private fun encodeLinks(links: Map<String, String>): String =
        links.entries.joinToString("\n") { "${it.key}=${it.value}" }

    private companion object {
        const val MAX_RECENT = 30
        const val MAX_SEARCH_HISTORY = 15
    }
}
