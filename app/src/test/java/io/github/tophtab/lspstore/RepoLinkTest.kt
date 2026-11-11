package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.repoModuleFor
import io.github.tophtab.lspstore.domain.model.repoPackagesOf

/**
 * Every browsing screen asks "which repository module is this installed app?",
 * so the answer is pinned once here: a module installed under its repository
 * package resolves to itself, a renamed or imported one through its manual link,
 * and an app the index does not know stays unknown.
 */
class RepoLinkTest {

    private fun module(packageName: String) = Module(
        packageName = packageName,
        title = packageName.substringAfterLast('.'),
        summary = null,
        url = null,
        homepageUrl = null,
        sourceUrl = null,
        updatedAt = null,
        stars = 0,
        contributor = null,
        contributorAvatarUrl = null,
        latestRelease = null,
        latestBetaRelease = null,
        latestSnapshotRelease = null,
        latestReleaseTime = null,
    )

    private fun installed(packageName: String, xposed: Boolean = true) = InstalledModule(
        packageName = packageName,
        versionCode = 1L,
        versionName = "1.0",
        label = packageName,
        isXposedModule = xposed,
        description = null,
    )

    private val index = listOf(
        module("dev.example.direct"),
        module("dev.example.repo"),
    ).associateBy { it.packageName }

    @Test
    fun moduleInstalledUnderItsRepositoryPackageResolvesToItself() {
        assertEquals("dev.example.direct", repoModuleFor("dev.example.direct", index, emptyMap())?.packageName)
    }

    @Test
    fun renamedModuleResolvesThroughItsManualLink() {
        val links = mapOf("dev.example.renamed" to "dev.example.repo")
        assertEquals("dev.example.repo", repoModuleFor("dev.example.renamed", index, links)?.packageName)
    }

    @Test
    fun unknownPackageHasNoRepositoryEntry() {
        assertNull(repoModuleFor("com.other.app", index, emptyMap()))
    }

    @Test
    fun linkToAModuleThatLeftTheIndexResolvesToNull() {
        val links = mapOf("dev.example.gone" to "dev.example.removed")
        assertNull(repoModuleFor("dev.example.gone", index, links))
    }

    @Test
    fun installedPackagesAreOnlyTheKnownXposedModules() {
        val installed = listOf(
            installed("dev.example.direct"),
            installed("dev.example.renamed"),
            // Linked, but the index no longer has the target.
            installed("dev.example.gone"),
            // A plain app that happens to share a repository package name.
            installed("dev.example.repo", xposed = false),
            installed("com.other.app"),
        )
        val links = mapOf(
            "dev.example.renamed" to "dev.example.repo",
            "dev.example.gone" to "dev.example.removed",
        )

        assertEquals(
            setOf("dev.example.direct", "dev.example.repo"),
            repoPackagesOf(installed, index, links),
        )
    }
}
