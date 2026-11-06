package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BackgroundUpdatePlan

/** Pure decisions of the background check: notification dedup and target filtering. */
class BackgroundUpdatePlanTest {

    private fun update(repoPackage: String, installedPackage: String, code: Long) = UpdateInfo(
        module = Module(
            packageName = repoPackage,
            title = repoPackage.substringAfterLast('.'),
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            updatedAt = null,
            stars = 0,
            contributor = null,
            contributorAvatarUrl = null,
            latestRelease = ModuleVersion(code, "1.$code", "$code-1.$code"),
            latestBetaRelease = null,
            latestSnapshotRelease = null,
            latestReleaseTime = null,
        ),
        installed = InstalledModule(
            packageName = installedPackage,
            versionCode = code - 1,
            versionName = "1.${code - 1}",
            label = installedPackage,
            isXposedModule = true,
            description = null,
        ),
        latest = ModuleVersion(code, "1.$code", "$code-1.$code"),
    )

    @Test
    fun signatureIgnoresOrder() {
        val first = update("a.one", "a.one", 10)
        val second = update("b.two", "b.two", 20)

        assertEquals(
            BackgroundUpdatePlan.notificationSignature(listOf(first, second)),
            BackgroundUpdatePlan.notificationSignature(listOf(second, first)),
        )
        assertEquals("", BackgroundUpdatePlan.notificationSignature(emptyList()))
    }

    @Test
    fun signatureChangesWithTheLatestVersionCode() {
        val before = BackgroundUpdatePlan.notificationSignature(listOf(update("a.one", "a.one", 10)))
        val after = BackgroundUpdatePlan.notificationSignature(listOf(update("a.one", "a.one", 11)))

        assertEquals(false, before == after)
    }

    @Test
    fun autoUpdateTargetsDropOwnedPackagesAndKeepOrder() {
        val pendingInstalled = update("repo.pending", "installed.pending", 10)
        val runningRepo = update("running.repo", "installed.repo", 20)
        val runningInstalled = update("repo.installed", "running.installed", 30)
        val first = update("free.one", "free.one", 40)
        val second = update("free.two", "free.two", 50)

        val targets = BackgroundUpdatePlan.autoUpdateTargets(
            updates = listOf(pendingInstalled, runningRepo, runningInstalled, first, second),
            pendingPackages = setOf("installed.pending"),
            runningPackages = setOf("running.repo", "running.installed"),
        )

        assertEquals(listOf(first, second), targets)
    }
}
