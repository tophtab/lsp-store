package io.github.tophtab.lspstore.data.repository

import io.github.tophtab.lspstore.domain.model.ConfirmedInstallRecord
import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import io.github.tophtab.lspstore.data.backup.LibraryBackup
import io.github.tophtab.lspstore.data.backup.LibraryRestoreMode
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.SkippedVersion
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.onInstallFailed
import io.github.tophtab.lspstore.domain.variant.onInstallSucceeded
import io.github.tophtab.lspstore.domain.variant.onUserPicked
import io.github.tophtab.lspstore.domain.variant.withoutNotApplicableVerdicts

private val Context.libraryDataStore by preferencesDataStore(name = "library")

/**
 * User-curated lists: favourites, recently viewed, ignored modules and ignored
 * updates. Ordered lists are stored newline separated; skipped updates as
 * "package:tag" lines (older builds wrote "package:versionCode").
 */
interface LibraryRepository {
    val favourites: Flow<List<String>>
    val recentlyViewed: Flow<List<String>>
    val skippedUpdates: Flow<Map<String, SkippedVersion>>
    val ignoredUpdates: Flow<Set<String>>

    /** Repository package names hidden from every browsing list. */
    val ignoredModules: Flow<Set<String>>

    /** Repository package names whose update checks also follow pre-releases. */
    val preReleaseModules: Flow<Set<String>>
    val searchHistory: Flow<List<String>>
    val links: Flow<Map<String, String>>
    val variantStates: Flow<Map<String, ModuleVariantState>>

    suspend fun toggleFavourite(packageName: String)
    suspend fun addRecentlyViewed(packageName: String)
    suspend fun clearRecentlyViewed()
    suspend fun skipUpdate(packageName: String, version: ModuleVersion)
    suspend fun unskipUpdate(packageName: String)
    suspend fun clearSkippedUpdates()
    suspend fun setUpdateCheckEnabled(packageName: String, enabled: Boolean)
    suspend fun setModuleIgnored(packageName: String, ignored: Boolean)
    suspend fun clearIgnoredModules()
    suspend fun setPreReleasesEnabled(packageName: String, enabled: Boolean)
    suspend fun clearIgnoredUpdates()
    suspend fun addSearchQuery(query: String)
    suspend fun clearSearchHistory()
    suspend fun linkModule(installedPackage: String, repoPackage: String)
    suspend fun unlinkModule(installedPackage: String)
    suspend fun setVariantState(packageName: String, state: ModuleVariantState)
    suspend fun clearVariantState(packageName: String)
    suspend fun setExplicitPreference(packageName: String, signature: VariantSignature) {
        val current = variantStates.first()[packageName] ?: ModuleVariantState()
        setVariantState(packageName, current.migrated().onUserPicked(signature))
    }
    suspend fun seedVariantState(packageName: String, state: ModuleVariantState) {
        if (packageName !in variantStates.first()) setVariantState(packageName, state)
    }
    suspend fun recordInstallResult(
        packageName: String,
        ok: Boolean,
        signature: VariantSignature? = null,
    )

    suspend fun recordConfirmedInstall(packageName: String, record: ConfirmedInstallRecord) {
        val state = variantStates.first()[packageName] ?: ModuleVariantState()
        setVariantState(packageName, state.onInstallSucceeded(record.target.signature.copy(packageName = record.packageName)).copy(confirmedInstall = record, preferenceMigrated = true))
    }

    suspend fun invalidateConfirmedInstall(packageName: String, expected: ConfirmedInstallRecord) {
        val current = variantStates.first()[packageName] ?: return
        if (current.confirmedInstall == expected) setVariantState(packageName, current.copy(confirmedInstall = null, signature = null))
    }

    /** Idempotent one-off migration of the legacy asset-name preferences. */
    suspend fun migratePreferredVariants()

    /**
     * Idempotent migration: forget all legacy NOT_APPLICABLE verdicts. A failed
     * attempt must not hide a module or prevent it being evaluated again.
     */
    suspend fun dropNotApplicableVerdicts()

    /** Whole library as one payload for an export file. */
    suspend fun snapshot(): LibraryBackup

    /**
     * Applies an imported [backup]. [LibraryRestoreMode.MERGE] keeps every local
     * entry and only adds what is missing; [LibraryRestoreMode.REPLACE] discards
     * the current library first.
     */
    suspend fun restore(backup: LibraryBackup, mode: LibraryRestoreMode)
}

class LibraryRepositoryImpl(private val context: Context) : LibraryRepository {

    private object Keys {
        val FAVOURITES = stringPreferencesKey("favourites")
        val RECENTLY_VIEWED = stringPreferencesKey("recently_viewed")
        val SKIPPED = stringPreferencesKey("skipped")
        val IGNORED_UPDATES = stringPreferencesKey("ignored_updates")
        val IGNORED_MODULES = stringPreferencesKey("ignored_modules")
        val PRE_RELEASE_MODULES = stringPreferencesKey("pre_release_modules")
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
        .decodedField(Keys.FAVOURITES, ::decodeList)

    override val recentlyViewed: Flow<List<String>> = context.libraryDataStore.data
        .decodedField(Keys.RECENTLY_VIEWED, ::decodeList)

    override val skippedUpdates: Flow<Map<String, SkippedVersion>> = context.libraryDataStore.data
        .decodedField(Keys.SKIPPED, ::decodeSkipped)

    /** Installed or repository package names whose updates the user ignored. */
    override val ignoredUpdates: Flow<Set<String>> = context.libraryDataStore.data
        .decodedField(Keys.IGNORED_UPDATES) { decodeList(it).toSet() }

    /** Repository package names the user hid from browsing. */
    override val ignoredModules: Flow<Set<String>> = context.libraryDataStore.data
        .decodedField(Keys.IGNORED_MODULES) { decodeList(it).toSet() }

    /** Repository package names that opted into pre-release tracking. */
    override val preReleaseModules: Flow<Set<String>> = context.libraryDataStore.data
        .decodedField(Keys.PRE_RELEASE_MODULES) { decodeList(it).toSet() }

    override val searchHistory: Flow<List<String>> = context.libraryDataStore.data
        .decodedField(Keys.SEARCH_HISTORY, ::decodeList)

    /** installed package name -> repository module package name */
    override val links: Flow<Map<String, String>> = context.libraryDataStore.data
        .decodedField(Keys.LINKS, ::decodeLinks)

    /** repository module package name -> variant status + semantic signature */
    override val variantStates: Flow<Map<String, ModuleVariantState>> = context.libraryDataStore.data
        .decodedField(Keys.VARIANT_STATE) { raw ->
            decodeVariantStates(raw).mapValues { it.value.migrated() }.withoutNotApplicableVerdicts()
        }

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

    override suspend fun skipUpdate(packageName: String, version: ModuleVersion) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            current[packageName] = SkippedVersion(version.raw)
            prefs[Keys.SKIPPED] = current.entries.joinToString("\n") { "${it.key}:${it.value.raw}" }
        }
    }

    override suspend fun unskipUpdate(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            current.remove(packageName)
            prefs[Keys.SKIPPED] = current.entries.joinToString("\n") { "${it.key}:${it.value.raw}" }
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

    override suspend fun setPreReleasesEnabled(packageName: String, enabled: Boolean) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.PRE_RELEASE_MODULES])
            prefs[Keys.PRE_RELEASE_MODULES] = encodeList(
                if (enabled) listOf(packageName) + (current - packageName) else current - packageName,
            )
        }
    }

    override suspend fun clearIgnoredUpdates() {
        context.libraryDataStore.edit { it[Keys.IGNORED_UPDATES] = "" }
    }

    override suspend fun setModuleIgnored(packageName: String, ignored: Boolean) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.IGNORED_MODULES])
            prefs[Keys.IGNORED_MODULES] = encodeList(
                // Newest first, so the ignored list opens on what was just hidden.
                if (ignored) listOf(packageName) + (current - packageName) else current - packageName,
            )
        }
    }

    override suspend fun clearIgnoredModules() {
        context.libraryDataStore.edit { it[Keys.IGNORED_MODULES] = "" }
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
     * Writes the full local state; preference and confirmation changes use their
     * dedicated atomic edits so they cannot overwrite each other.
     */
    override suspend fun setVariantState(packageName: String, state: ModuleVariantState) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            current[packageName] = state
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(current)
        }
    }

    override suspend fun setExplicitPreference(packageName: String, signature: VariantSignature) {
        context.libraryDataStore.edit { prefs ->
            val states = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            val current = (states[packageName] ?: ModuleVariantState()).migrated()
            states[packageName] = current.onUserPicked(signature)
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(states)
        }
    }

    override suspend fun seedVariantState(packageName: String, state: ModuleVariantState) {
        context.libraryDataStore.edit { prefs ->
            val states = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            if (packageName in states) return@edit
            states[packageName] = state.migrated()
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(states)
        }
    }

    override suspend fun clearVariantState(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            current[packageName]?.let { state ->
                current[packageName] = state.migrated().copy(explicitPreference = null, preferenceMigrated = true)
            }
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(current)
        }
    }

    /**
     * Records one install attempt. Success remembers the semantic signature and
     * resets the failure count; unsuccessful attempts remain retryable.
     */
    override suspend fun recordInstallResult(
        packageName: String,
        ok: Boolean,
        signature: VariantSignature?,
    ) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            val state = (current[packageName] ?: ModuleVariantState()).migrated()
            current[packageName] = if (ok) {
                state.onInstallSucceeded(signature)
            } else {
                state.onInstallFailed()
            }
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(current)
        }
    }

    override suspend fun invalidateConfirmedInstall(packageName: String, expected: ConfirmedInstallRecord) {
        context.libraryDataStore.edit { prefs ->
            val states = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            val current = states[packageName] ?: return@edit
            if (current.confirmedInstall != expected) return@edit
            states[packageName] = current.migrated().copy(confirmedInstall = null, signature = null)
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(states)
        }
    }

    override suspend fun recordConfirmedInstall(packageName: String, record: ConfirmedInstallRecord) {
        context.libraryDataStore.edit { prefs ->
            val states = decodeVariantStates(prefs[Keys.VARIANT_STATE]).toMutableMap()
            val state = (states[packageName] ?: ModuleVariantState()).migrated()
            states[packageName] = state.onInstallSucceeded(record.target.signature.copy(packageName = record.packageName))
                .copy(confirmedInstall = record, preferenceMigrated = true)
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(states)
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
                        signature = VariantSignature.ofAsset(assetName),
                        updatedAt = now,
                    )
                }
            }
            prefs[Keys.VARIANT_STATE] = encodeVariantStates(states)
            prefs[Keys.PREFERRED_VARIANTS] = ""
        }
    }

    override suspend fun dropNotApplicableVerdicts() {
        context.libraryDataStore.edit { prefs ->
            val current = decodeVariantStates(prefs[Keys.VARIANT_STATE])
            val cleaned = current.withoutNotApplicableVerdicts()
            if (cleaned != current) {
                prefs[Keys.VARIANT_STATE] = encodeVariantStates(cleaned)
            }
        }
    }

    override suspend fun snapshot(): LibraryBackup =
        readBackup(context.libraryDataStore.data.first()).portable().copy(
            exportedAt = System.currentTimeMillis(),
        )

    override suspend fun restore(backup: LibraryBackup, mode: LibraryRestoreMode) {
        context.libraryDataStore.edit { prefs ->
            val target = when (mode) {
                LibraryRestoreMode.MERGE -> readBackup(prefs).mergedWith(backup.portable())
                LibraryRestoreMode.REPLACE -> backup.portable()
            }
            writeBackup(prefs, target)
            prefs[Keys.PREFERRED_VARIANTS] = ""
        }
    }

    private fun readBackup(prefs: Preferences): LibraryBackup = LibraryBackup(
        schemaVersion = LibraryBackup.SCHEMA_VERSION,
        favourites = decodeList(prefs[Keys.FAVOURITES]),
        recentlyViewed = decodeList(prefs[Keys.RECENTLY_VIEWED]),
        skippedUpdates = decodeSkipped(prefs[Keys.SKIPPED]).mapValues { it.value.raw },
        ignoredUpdates = decodeList(prefs[Keys.IGNORED_UPDATES]),
        ignoredModules = decodeList(prefs[Keys.IGNORED_MODULES]),
        preReleaseModules = decodeList(prefs[Keys.PRE_RELEASE_MODULES]),
        searchHistory = decodeList(prefs[Keys.SEARCH_HISTORY]),
        links = decodeLinks(prefs[Keys.LINKS]),
        variantStates = decodeVariantStates(prefs[Keys.VARIANT_STATE]),
    )

    /**
     * Writes every library key, including empty ones: a replace must also clear
     * entries the backup does not have. Restore also drains legacy preferences;
     * no settings key is touched.
     */
    private fun writeBackup(prefs: MutablePreferences, backup: LibraryBackup) {
        prefs[Keys.FAVOURITES] = encodeList(backup.favourites)
        prefs[Keys.RECENTLY_VIEWED] = encodeList(backup.recentlyViewed.take(MAX_RECENT))
        prefs[Keys.SKIPPED] = backup.skippedUpdates.entries.joinToString("\n") {
            "${it.key}:${it.value}"
        }
        prefs[Keys.IGNORED_UPDATES] = encodeList(backup.ignoredUpdates)
        prefs[Keys.IGNORED_MODULES] = encodeList(backup.ignoredModules)
        prefs[Keys.PRE_RELEASE_MODULES] = encodeList(backup.preReleaseModules)
        prefs[Keys.SEARCH_HISTORY] = encodeList(backup.searchHistory.take(MAX_SEARCH_HISTORY))
        prefs[Keys.LINKS] = encodeLinks(backup.links)
        prefs[Keys.VARIANT_STATE] = encodeVariantStates(backup.variantStates)
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

    private fun decodeSkipped(raw: String?): Map<String, SkippedVersion> =
        raw?.split('\n')
            ?.mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val value = line.substring(index + 1)
                if (value.isBlank()) return@mapNotNull null
                line.substring(0, index) to SkippedVersion(value)
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
