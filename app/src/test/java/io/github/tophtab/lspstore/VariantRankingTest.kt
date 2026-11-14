package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.VariantFixtures.asset
import io.github.tophtab.lspstore.VariantFixtures.releaseOf
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantRankingTest {

    private val device = DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a", "armeabi-v7a"))
    private val resolver = VariantResolver(device)

    @Test
    fun aPlainBuildOutranksADecoratedOneEvenWithFewerDownloads() {
        val release = Release(
            tagName = "1-1.0",
            name = null,
            url = null,
            isPrerelease = false,
            publishedAt = null,
            descriptionHtml = null,
            version = ModuleVersion.parse("1-1.0"),
            assets = listOf(
                asset("KnoxPatch-v0.8.3.apk", downloads = 10),
                asset("KnoxPatch-v0.8.3-sep-lite.apk", downloads = 900),
            ),
        )
        val decision = resolver.resolve(release)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("KnoxPatch-v0.8.3.apk", (decision as VariantDecision.Pick).asset.name)
        assertEquals(VariantDecision.Confidence.DEFAULT, decision.confidence)
    }

    @Test
    fun theInstalledHintOnlyOrdersCandidatesTheDeviceCannotSplit() {
        val release = releaseOf("app-release.apk", "app-release-lite.apk")
        val installed = InstalledVariantHint(packageName = "com.example.module", abi = "arm64-v8a")
        // Neither probe identifies the package, and both names are plain builds:
        // the hint cannot invent a preference, so the ladder still decides.
        val decision = resolver.resolve(release, installed = installed)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("app-release.apk", (decision as VariantDecision.Pick).asset.name)
    }

    @Test
    fun aBrandNewReleaseWithNoDownloadsResolvesDeterministically() {
        val release = releaseOf("app-tile.apk", "app-panel.apk")
        val first = resolver.resolve(release) as VariantDecision.Pick
        val second = resolver.resolve(release) as VariantDecision.Pick
        assertEquals(first.asset.name, second.asset.name)
        assertEquals("app-panel.apk", first.asset.name)
    }

    @Test
    fun singleDebugApkInstallsWithoutConfirmation() {
        // Synthetic on purpose: the live corpus changes, and a module that only
        // ships debug builds must never turn into a confirmation prompt.
        val decision = resolver.resolve(releaseOf("app-debug.apk"))
        assertTrue(decision is VariantDecision.Pick)
        val pick = decision as VariantDecision.Pick
        assertEquals("app-debug.apk", pick.asset.name)
        assertEquals(VariantDecision.Confidence.HIGH, pick.confidence)
        assertTrue(pick.reasons.none { it.kind == Reason.Kind.DEBUG_BUILD })
    }

    @Test
    fun abiPreferenceIsTheOnlyTieBreakThatNeedsNoConfirmation() {
        val abiRelease = releaseOf("app-armeabi-v7a-release.apk", "app-arm64-v8a-release.apk")
        val abiDecision = resolver.resolve(abiRelease)
        assertTrue(abiDecision is VariantDecision.Pick)
        assertEquals("app-arm64-v8a-release.apk", (abiDecision as VariantDecision.Pick).asset.name)

        val universalVsArm = releaseOf("app-universal.apk", "app-arm64-v8a-release.apk")
        assertEquals(
            "app-arm64-v8a-release.apk",
            (resolver.resolve(universalVsArm) as VariantDecision.Pick).asset.name,
        )

        // 32-bit ARM is still native on a device that advertises armeabi-v7a,
        // so it beats universal, which only wins when no native build fits.
        val universalVsOther = releaseOf("app-universal.apk", "app-armeabi-v7a-release.apk")
        assertEquals(
            "app-armeabi-v7a-release.apk",
            (resolver.resolve(universalVsOther) as VariantDecision.Pick).asset.name,
        )
        val arm64Only = VariantResolver(DeviceProfile(36, listOf("arm64-v8a")))
        assertEquals(
            "app-universal.apk",
            (arm64Only.resolve(universalVsOther) as VariantDecision.Pick).asset.name,
        )
    }

    @Test
    fun debugAndReleasePairNeedsNoQuestion() {
        val decision = resolver.resolve(
            releaseOf("app-debug.apk", "app-release.apk"),
        )
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("app-release.apk", (decision as VariantDecision.Pick).asset.name)
    }

    @Test
    fun aBundledNameOnlyWarnsWithoutProbeEvidenceAndIsNotTheDefault() {
        val release = releaseOf("module.apk", "Module_Client.apk")
        val candidates = resolver.candidates(release, expectedPackage = "com.example.module")

        assertEquals(
            Reason.Kind.BUNDLED_APP,
            candidates.first { it.asset.name == "Module_Client.apk" }.warning?.kind,
        )
        assertTrue(candidates.all { it.blocked == null })
        assertEquals("module.apk", (resolver.resolve(release) as VariantDecision.Pick).asset.name)
    }

    @Test
    fun aSuspectedCompanionDoesNotReplaceTheOnlyDebugModuleAsTheDefault() {
        val release = releaseOf("module-debug.apk", "Module_Client.apk")
        val decision = resolver.resolve(release) as VariantDecision.Pick
        assertEquals("module-debug.apk", decision.asset.name)
        assertTrue(resolver.candidates(release).all { it.blocked == null })
    }

    @Test
    fun singleApkIsStillJudged() {
        val decision = resolver.resolve(releaseOf("app-arm64-v8a-release.apk"))
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(VariantDecision.Confidence.HIGH, (decision as VariantDecision.Pick).confidence)
        assertTrue(decision.reasons.any { it.kind == Reason.Kind.MATCHES_DEVICE })
    }

    @Test
    fun emptyReleaseIsNotApplicable() {
        val release = Release(
            tagName = "1-1.0",
            name = null,
            url = null,
            isPrerelease = false,
            publishedAt = null,
            descriptionHtml = null,
            version = ModuleVersion.parse("1-1.0"),
            assets = emptyList(),
        )
        val decision = resolver.resolve(release)
        assertEquals(Reason.Kind.NO_APK, (decision as VariantDecision.NotApplicable).reason.kind)
    }
}
