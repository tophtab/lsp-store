package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.VariantFixtures.asset
import io.github.tophtab.lspstore.VariantFixtures.latestRelease
import io.github.tophtab.lspstore.VariantFixtures.releaseOf
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantProbeRulesTest {

    private val device = DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a", "armeabi-v7a"))
    private val resolver = VariantResolver(device)

    @Test
    fun anUpdatePicksTheInstalledPackageOverTheMorePopularAsset() {
        val release = Release(
            tagName = "1-1.0",
            name = null,
            url = null,
            isPrerelease = false,
            publishedAt = null,
            descriptionHtml = null,
            version = ModuleVersion.parse("1-1.0"),
            assets = listOf(
                asset("Other-App-release.apk", downloads = 9000),
                asset("Installed-App-release.apk", downloads = 5),
            ),
        )
        val probes = mapOf(
            "Other-App-release.apk" to ApkMeta(
                packageName = "com.other.module",
                hasXposedDeclaration = true,
            ),
            "Installed-App-release.apk" to ApkMeta(
                packageName = "com.example.module",
                hasXposedDeclaration = true,
            ),
        )
        val installed = InstalledVariantHint(
            packageName = "com.example.module",
            abi = "arm64-v8a",
            minSdk = 29,
            targetSdk = 34,
        )

        // An update follows what is on the device, not what other users installed.
        val decision = resolver.resolve(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
            installed = installed,
        )
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("Installed-App-release.apk", (decision as VariantDecision.Pick).asset.name)
        assertEquals(VariantDecision.Confidence.HIGH, decision.confidence)

        // The same release for a device that does not have the module: there is
        // no installed copy to follow, so the device falls back to the build
        // most users installed.
        val firstInstall = resolver.resolve(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        )
        assertEquals(
            "Other-App-release.apk",
            (firstInstall as VariantDecision.Pick).asset.name,
        )
    }

    @Test
    fun trustedSignatureMapsWithoutProbingOrRescoring() {
        val release = latestRelease("com.sevtinge.hyperceiler")
        val signature = VariantSignature(os = "hyperos3")
        val probes = CountingProbes(
            release.apkAssets.associate { it.name to ApkMeta(debuggable = false) },
        )

        val decision = resolver.resolve(release, signature, probes)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "OS3_HyperCeiler-2.10.166-20260331-release.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
        assertTrue(decision.reasons.any { it.kind == Reason.Kind.REMEMBERED })
        assertEquals("trusted path must not read probes", 0, probes.reads)

        // A renamed release falls back to the name layer, still without probes,
        // and still produces a usable default.
        val unmatched = resolver.resolve(release, VariantSignature(abi = "x86_64"), probes)
        assertTrue(unmatched is VariantDecision.Pick)
        assertEquals(VariantDecision.Confidence.DEFAULT, (unmatched as VariantDecision.Pick).confidence)
        assertEquals(0, probes.reads)
    }

    @Test
    fun cachedProbesDoNotOverrideRememberedVariant() {
        val release = latestRelease("com.sevtinge.hyperceiler")
        val probes = CountingProbes(
            release.apkAssets.associate { it.name to ApkMeta(debuggable = true) },
        )
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = VariantSignature(os = "hyperos2"),
        )
        val decision = resolver.resolveForUpdate(state, release, probes)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "OS2_HyperCeiler-2.10.166-20260402-release.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
        assertTrue(probes.reads > 0)
    }

    @Test
    fun aForeignAbiFromTheProbeOnlyWarns() {
        val release = releaseOf("app.apk")
        val probes = mapOf(
            "app.apk" to ApkMeta(abis = setOf("riscv64"), hasXposedDeclaration = true),
        )
        // Installing never fails on a foreign lib/ directory and the module may
        // not load its native part at all, so this is advisory like the API (#47).
        val decision = resolver.resolve(release, probes = probes, expectedPackage = "com.example")
        assertTrue(decision is VariantDecision.Pick)
        val candidate = resolver.candidates(release, probes = probes, expectedPackage = "com.example")
            .single()
        assertEquals(Reason.Kind.ABI_INCOMPATIBLE, candidate.warning?.kind)
        assertEquals(listOf("riscv64"), candidate.warning?.args)
    }

    @Test
    fun aCompanionLookingNameIsKeptWhenTheArchiveIsTheModule() {
        val release = releaseOf("module.apk", "Module_Client.apk")
        val probes = mapOf(
            "Module_Client.apk" to ApkMeta(
                packageName = "com.example.module",
                hasXposedDeclaration = true,
            ),
        )
        val candidate = resolver.candidates(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        ).first { it.asset.name == "Module_Client.apk" }

        assertNull("the archive identifies itself as the module", candidate.blocked)
    }

    @Test
    fun aProbedMinSdkAboveTheDeviceOnlyWarns() {
        val release = releaseOf("app.apk")
        val probes = mapOf("app.apk" to ApkMeta(minSdk = 37, hasXposedDeclaration = true))
        // The manifest value is real, but refusing here would grey out the only
        // candidate: the system installer rejects the package, so the user is
        // warned and may still try (#75).
        val decision = resolver.resolve(release, probes = probes, expectedPackage = "com.example")
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("app.apk", (decision as VariantDecision.Pick).asset.name)
        val candidate = resolver.candidates(
            release,
            probes = probes,
            expectedPackage = "com.example",
        ).single()
        assertNull(candidate.blocked)
        assertEquals(Reason.Kind.MIN_SDK_TOO_HIGH, candidate.warning?.kind)
        assertEquals(listOf("37", "36"), candidate.warning?.args)
    }

    @Test
    fun foreignPackageWithoutDeclarationStaysSelectable() {
        val release = releaseOf("module.apk", "companion.apk")
        val probes = mapOf(
            "companion.apk" to ApkMeta(packageName = "com.other", hasXposedDeclaration = false),
        )
        val decision = resolver.resolve(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        )
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("module.apk", (decision as VariantDecision.Pick).asset.name)

        val folded = resolver.candidates(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        ).first { it.asset.name == "companion.apk" }
        assertNull(folded.blocked)
    }

    @Test
    fun probePackageMismatchWithDeclarationIsKeptAsACandidate() {
        val release = releaseOf("module.apk", "renamed.apk")
        val probes = mapOf(
            "renamed.apk" to ApkMeta(packageName = "com.other", hasXposedDeclaration = true),
        )
        val decision = resolver.resolve(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        )
        assertTrue("renamed modules may only be a warning", decision is VariantDecision.Pick)
        val blocked = resolver.candidates(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        ).filter { it.blocked != null }
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun aLegacyEntryMakesTheDeclaredApiAdvisory() {
        val older = VariantResolver(
            DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a"), lsposedApi = 100),
        )
        val release = releaseOf("module.apk")
        val probes = mapOf(
            "module.apk" to ApkMeta(
                packageName = "com.example.module",
                hasXposedDeclaration = true,
                minApiVersion = 102,
                targetApiVersion = 100,
                hasLegacyInit = true,
            ),
        )
        assertNull(older.candidates(release, probes = probes).single().warning)
        assertTrue(older.resolve(release, probes = probes) is VariantDecision.Pick)
    }

    @Test
    fun targetApiVersionDecidesTheWarningInsteadOfTheAuthorsFloor() {
        val older = VariantResolver(
            DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a"), lsposedApi = 100),
        )
        val release = releaseOf("module.apk")
        val probes = mapOf(
            "module.apk" to ApkMeta(
                packageName = "com.example.module",
                hasXposedDeclaration = true,
                minApiVersion = 102,
                targetApiVersion = 101,
            ),
        )
        assertEquals(
            listOf("101"),
            older.candidates(release, probes = probes).single().warning?.args,
        )
    }

    private class CountingProbes(
        private val delegate: Map<String, ApkMeta>,
    ) : Map<String, ApkMeta> by delegate {
        var reads = 0
            private set

        override fun get(key: String): ApkMeta? {
            reads++
            return delegate[key]
        }
    }
}
