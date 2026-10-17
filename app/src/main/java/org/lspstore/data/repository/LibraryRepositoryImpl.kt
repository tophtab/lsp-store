package org.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.libraryDataStore by preferencesDataStore(name = "library")

/**
 * User-curated lists: favourites, recently viewed, hidden modules and skipped
 * updates. Ordered lists are stored newline separated; skipped updates as
 * "package:versionCode" lines.
 */
class LibraryRepositoryImpl(private val context: Context) {

    private object Keys {
        val FAVOURITES = stringPreferencesKey("favourites")
        val RECENTLY_VIEWED = stringPreferencesKey("recently_viewed")
        val HIDDEN = stringPreferencesKey("hidden")
        val SKIPPED = stringPreferencesKey("skipped")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val favourites: Flow<List<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.FAVOURITES]) }

    val recentlyViewed: Flow<List<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.RECENTLY_VIEWED]) }

    val hidden: Flow<Set<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.HIDDEN]).toSet() }

    val skippedUpdates: Flow<Map<String, Long>> = context.libraryDataStore.data
        .map { decodeSkipped(it[Keys.SKIPPED]) }

    val searchHistory: Flow<List<String>> = context.libraryDataStore.data
        .map { decodeList(it[Keys.SEARCH_HISTORY]) }

    suspend fun toggleFavourite(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.FAVOURITES])
            prefs[Keys.FAVOURITES] = encodeList(
                if (packageName in current) current - packageName else listOf(packageName) + current,
            )
        }
    }

    suspend fun addRecentlyViewed(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.RECENTLY_VIEWED])
            val updated = (listOf(packageName) + current.filterNot { it == packageName }).take(MAX_RECENT)
            prefs[Keys.RECENTLY_VIEWED] = encodeList(updated)
        }
    }

    suspend fun clearRecentlyViewed() {
        context.libraryDataStore.edit { it[Keys.RECENTLY_VIEWED] = "" }
    }

    suspend fun toggleHidden(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.HIDDEN])
            prefs[Keys.HIDDEN] = encodeList(
                if (packageName in current) current - packageName else listOf(packageName) + current,
            )
        }
    }

    suspend fun skipUpdate(packageName: String, versionCode: Long) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            current[packageName] = versionCode
            prefs[Keys.SKIPPED] = current.entries.joinToString("\n") { "${it.key}:${it.value}" }
        }
    }

    suspend fun unskipUpdate(packageName: String) {
        context.libraryDataStore.edit { prefs ->
            val current = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            current.remove(packageName)
            prefs[Keys.SKIPPED] = current.entries.joinToString("\n") { "${it.key}:${it.value}" }
        }
    }

    suspend fun clearSkippedUpdates() {
        context.libraryDataStore.edit { it[Keys.SKIPPED] = "" }
    }

    suspend fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        context.libraryDataStore.edit { prefs ->
            val current = decodeList(prefs[Keys.SEARCH_HISTORY])
            val updated = (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(MAX_SEARCH_HISTORY)
            prefs[Keys.SEARCH_HISTORY] = encodeList(updated)
        }
    }

    suspend fun clearSearchHistory() {
        context.libraryDataStore.edit { it[Keys.SEARCH_HISTORY] = "" }
    }

    suspend fun exportJson(): String {
        val prefs = context.libraryDataStore.data.first()
        val backup = LibraryBackup(
            favourites = decodeList(prefs[Keys.FAVOURITES]),
            recentlyViewed = decodeList(prefs[Keys.RECENTLY_VIEWED]),
            hidden = decodeList(prefs[Keys.HIDDEN]),
            skipped = decodeSkipped(prefs[Keys.SKIPPED]),
        )
        return json.encodeToString(LibraryBackup.serializer(), backup)
    }

    suspend fun importJson(raw: String): LibraryImportResult {
        val backup = runCatching {
            json.decodeFromString(LibraryBackup.serializer(), raw)
        }.getOrElse { error ->
            return LibraryImportResult(error = error.message ?: "Invalid backup file")
        }

        context.libraryDataStore.edit { prefs ->
            val favourites = (backup.favourites + decodeList(prefs[Keys.FAVOURITES])).distinct()
            prefs[Keys.FAVOURITES] = encodeList(favourites)

            val recent = (backup.recentlyViewed + decodeList(prefs[Keys.RECENTLY_VIEWED]))
                .distinct()
                .take(MAX_RECENT)
            prefs[Keys.RECENTLY_VIEWED] = encodeList(recent)

            val hidden = (backup.hidden + decodeList(prefs[Keys.HIDDEN])).distinct()
            prefs[Keys.HIDDEN] = encodeList(hidden)

            val skipped = decodeSkipped(prefs[Keys.SKIPPED]).toMutableMap()
            backup.skipped.forEach { (packageName, versionCode) -> skipped[packageName] = versionCode }
            prefs[Keys.SKIPPED] = skipped.entries.joinToString("\n") { "${it.key}:${it.value}" }
        }

        return LibraryImportResult(
            favourites = backup.favourites.size,
            recentlyViewed = backup.recentlyViewed.size,
            hidden = backup.hidden.size,
            skipped = backup.skipped.size,
        )
    }

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

    private companion object {
        const val MAX_RECENT = 30
        const val MAX_SEARCH_HISTORY = 15
    }
}

@Serializable
data class LibraryBackup(
    val version: Int = 1,
    val favourites: List<String> = emptyList(),
    val recentlyViewed: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val skipped: Map<String, Long> = emptyMap(),
)

data class LibraryImportResult(
    val favourites: Int = 0,
    val recentlyViewed: Int = 0,
    val hidden: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
)
