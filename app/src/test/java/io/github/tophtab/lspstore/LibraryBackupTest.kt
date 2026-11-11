package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.backup.LibraryBackup
import io.github.tophtab.lspstore.data.backup.LibraryBackupException
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryBackupTest {

    private fun sample() = LibraryBackup(
        schemaVersion = LibraryBackup.SCHEMA_VERSION,
        exportedAt = 1_700_000_000_000L,
        favourites = listOf("com.example.a", "com.example.b"),
        recentlyViewed = listOf("com.example.c"),
        skippedUpdates = mapOf("com.example.a" to "10400-1.4.0"),
        ignoredUpdates = listOf("com.example.d"),
        ignoredModules = listOf("com.example.e"),
        preReleaseModules = listOf("com.example.f"),
        searchHistory = listOf("example"),
        links = mapOf("com.installed" to "com.example.a"),
        variantStates = mapOf(
            "com.example.a" to ModuleVariantState(
                status = ModuleVariantState.Status.TRUSTED,
                updatedAt = 42L,
            ),
        ),
    )

    @Test
    fun `round trip keeps every library field`() {
        val encoded = LibraryBackup.encode(sample())

        assertEquals(sample(), LibraryBackup.decode(encoded))
    }

    @Test
    fun `unknown fields are ignored`() {
        val encoded = LibraryBackup.encode(sample())
            .replaceFirst("{", "{\n  \"futureField\": [1, 2],")

        assertEquals(sample(), LibraryBackup.decode(encoded))
    }

    @Test
    fun `backup from a newer schema is rejected`() {
        val encoded = LibraryBackup.encode(
            sample().copy(schemaVersion = LibraryBackup.SCHEMA_VERSION + 1),
        )

        val error = assertThrows(LibraryBackupException::class.java) {
            LibraryBackup.decode(encoded)
        }
        assertEquals(LibraryBackupException.Problem.UNSUPPORTED_VERSION, error.problem)
    }

    @Test
    fun `file without a schema version is rejected`() {
        val error = assertThrows(LibraryBackupException::class.java) {
            LibraryBackup.decode("""{"favourites":["com.example.a"]}""")
        }
        assertEquals(LibraryBackupException.Problem.MALFORMED, error.problem)
    }

    @Test
    fun `a backup carrying a verdict from an older build still imports`() {
        // 1.5.3 wrote NOT_APPLICABLE and a reason key into the same payload. The
        // status is gone from the model now, but rejecting the whole file would
        // lose the favourites, links and skipped versions next to it (#73).
        val legacy =
            """
            {
              "schemaVersion": 1,
              "favourites": ["com.example.a"],
              "links": {"com.installed": "com.example.a"},
              "skippedUpdates": {"com.example.a": "10400-1.4.0"},
              "variantStates": {
                "com.example.a": {
                  "status": "NOT_APPLICABLE",
                  "signature": {"abi": "arm64-v8a"},
                  "failures": 0,
                  "reason": "MIN_SDK_TOO_HIGH\u001f35\u001f33",
                  "updatedAt": 5
                }
              }
            }
            """.trimIndent()

        val backup = LibraryBackup.decode(legacy)
        val state = backup.variantStates.getValue("com.example.a")

        assertEquals(ModuleVariantState.Status.UNKNOWN, state.status)
        assertEquals("arm64-v8a", state.signature?.abi)
        assertEquals(listOf("com.example.a"), backup.favourites)
        assertEquals(mapOf("com.example.a" to "10400-1.4.0"), backup.skippedUpdates)
    }

    @Test
    fun `non json content is rejected`() {
        val error = assertThrows(LibraryBackupException::class.java) {
            LibraryBackup.decode("not a backup")
        }
        assertEquals(LibraryBackupException.Problem.MALFORMED, error.problem)
    }

    @Test
    fun `merge unions lists with local entries first`() {
        val local = LibraryBackup(
            favourites = listOf("com.example.a", "com.example.b"),
            ignoredModules = listOf("com.example.x"),
            recentlyViewed = listOf("com.example.r1"),
        )
        val incoming = LibraryBackup(
            favourites = listOf("com.example.b", "com.example.c"),
            ignoredModules = listOf("com.example.y"),
            recentlyViewed = listOf("com.example.r2", "com.example.r1"),
        )

        val merged = local.mergedWith(incoming)

        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c"),
            merged.favourites,
        )
        assertEquals(listOf("com.example.x", "com.example.y"), merged.ignoredModules)
        assertEquals(listOf("com.example.r1", "com.example.r2"), merged.recentlyViewed)
    }

    @Test
    fun `merge keeps local map entries on conflict and adds new keys`() {
        val local = LibraryBackup(
            skippedUpdates = mapOf("com.example.a" to "local-version"),
            links = mapOf("com.installed" to "com.local"),
            variantStates = mapOf(
                "com.example.a" to ModuleVariantState(updatedAt = 1L),
            ),
        )
        val incoming = LibraryBackup(
            skippedUpdates = mapOf(
                "com.example.a" to "backup-version",
                "com.example.b" to "backup-only",
            ),
            links = mapOf(
                "com.installed" to "com.backup",
                "com.other" to "com.backup",
            ),
            variantStates = mapOf(
                "com.example.a" to ModuleVariantState(updatedAt = 9L),
                "com.example.b" to ModuleVariantState(updatedAt = 9L),
            ),
        )

        val merged = local.mergedWith(incoming)

        assertEquals("local-version", merged.skippedUpdates["com.example.a"])
        assertEquals("backup-only", merged.skippedUpdates["com.example.b"])
        assertEquals("com.local", merged.links["com.installed"])
        assertEquals("com.backup", merged.links["com.other"])
        assertEquals(1L, merged.variantStates.getValue("com.example.a").updatedAt)
        assertEquals(9L, merged.variantStates.getValue("com.example.b").updatedAt)
    }

    @Test
    fun `merge caps recent views and search history`() {
        val local = (1..LibraryBackup.RECENT_LIMIT).map { "local-$it" }
        val incoming = (1..5).map { "incoming-$it" }
        val localSearches = (1..LibraryBackup.SEARCH_HISTORY_LIMIT).map { "local-$it" }
        val incomingSearches = (1..5).map { "incoming-$it" }

        val merged = LibraryBackup(
            recentlyViewed = local,
            searchHistory = localSearches,
        ).mergedWith(
            LibraryBackup(
                recentlyViewed = incoming,
                searchHistory = incomingSearches,
            ),
        )

        assertEquals(LibraryBackup.RECENT_LIMIT, merged.recentlyViewed.size)
        assertEquals(LibraryBackup.SEARCH_HISTORY_LIMIT, merged.searchHistory.size)
        assertEquals("local-1", merged.recentlyViewed.first())
        assertEquals("local-1", merged.searchHistory.first())
    }
}
