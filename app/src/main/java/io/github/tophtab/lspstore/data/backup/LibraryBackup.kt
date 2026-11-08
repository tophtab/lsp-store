package io.github.tophtab.lspstore.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

/**
 * One JSON snapshot of the user library (everything in the `library` DataStore).
 *
 * Settings are deliberately absent: the translation API keys must never leave
 * the device in a file the user may copy to a cloud drive, so the backup covers
 * only favourites, ignored and skipped entries, links and variant choices.
 *
 * Fields only ever get added. [Json] ignores unknown keys, so a backup written
 * by a newer build still loads most of its content here, and [schemaVersion]
 * still gates files this build cannot interpret.
 */
@Serializable
data class LibraryBackup(
    val schemaVersion: Int = 0,
    val exportedAt: Long = 0L,
    val favourites: List<String> = emptyList(),
    val recentlyViewed: List<String> = emptyList(),
    /** package name -> raw skipped version ("versionCode-versionName") */
    val skippedUpdates: Map<String, String> = emptyMap(),
    val ignoredUpdates: List<String> = emptyList(),
    val ignoredModules: List<String> = emptyList(),
    val preReleaseModules: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    /** installed package name -> repository package name */
    val links: Map<String, String> = emptyMap(),
    /** repository package name -> install/variant state */
    val variantStates: Map<String, ModuleVariantState> = emptyMap(),
) {

    /**
     * Merges [incoming] under this snapshot. Lists become a union with the local
     * entries first; maps keep the local value on a key conflict. Nothing already
     * on the device is removed by a merge.
     */
    fun mergedWith(incoming: LibraryBackup): LibraryBackup = LibraryBackup(
        schemaVersion = SCHEMA_VERSION,
        exportedAt = incoming.exportedAt,
        favourites = mergeLists(favourites, incoming.favourites),
        recentlyViewed = mergeLists(recentlyViewed, incoming.recentlyViewed)
            .take(RECENT_LIMIT),
        skippedUpdates = incoming.skippedUpdates + skippedUpdates,
        ignoredUpdates = mergeLists(ignoredUpdates, incoming.ignoredUpdates),
        ignoredModules = mergeLists(ignoredModules, incoming.ignoredModules),
        preReleaseModules = mergeLists(preReleaseModules, incoming.preReleaseModules),
        searchHistory = mergeLists(searchHistory, incoming.searchHistory)
            .take(SEARCH_HISTORY_LIMIT),
        links = incoming.links + links,
        variantStates = incoming.variantStates + variantStates,
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val MIN_SCHEMA_VERSION = 1

        /** Same cap as the library repository applies when recording a view. */
        const val RECENT_LIMIT = 30

        /** Same cap as the library repository applies when recording a query. */
        const val SEARCH_HISTORY_LIMIT = 15

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(backup: LibraryBackup): String = json.encodeToString(serializer(), backup)

        /**
         * Parses [text] and checks the schema version. An unknown key is ignored,
         * a missing or foreign schema version is rejected so a file from another
         * app (or a future layout) never overwrites the library.
         */
        fun decode(text: String): LibraryBackup {
            val backup = try {
                json.decodeFromString(serializer(), text)
            } catch (error: Exception) {
                throw LibraryBackupException(LibraryBackupException.Problem.MALFORMED, error)
            }
            when {
                backup.schemaVersion > SCHEMA_VERSION ->
                    throw LibraryBackupException(LibraryBackupException.Problem.UNSUPPORTED_VERSION)
                backup.schemaVersion < MIN_SCHEMA_VERSION ->
                    throw LibraryBackupException(LibraryBackupException.Problem.MALFORMED)
            }
            return backup
        }

        /**
         * Union of two ordered lists: every local entry keeps its place, then the
         * incoming entries the device does not have yet follow in their order.
         */
        fun mergeLists(local: List<String>, incoming: List<String>): List<String> {
            val known = local.toMutableSet()
            return local + incoming.filter { known.add(it) }
        }
    }
}

/** Whether an imported snapshot replaces the library or only fills in gaps. */
enum class LibraryRestoreMode { MERGE, REPLACE }

/** A backup file that could not be parsed or comes from an unsupported schema. */
class LibraryBackupException(
    val problem: Problem,
    cause: Throwable? = null,
) : Exception(problem.name, cause) {
    enum class Problem { MALFORMED, UNSUPPORTED_VERSION }
}
