package io.github.tophtab.lspstore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.VendorOs
import io.github.tophtab.lspstore.domain.variant.parseTraits

class VariantResolverTest {

    private val device = DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a", "armeabi-v7a"))
    private val resolver = VariantResolver(device)

    @Test
    fun autoDecisionNeverPrefersDebugWhenANonDebugVariantExists() {
        everyRelease().forEach { (pkg, release) ->
            val decision = resolver.resolve(release)
            if (decision !is VariantDecision.Pick) return@forEach
            if (!parseTraits(decision.asset.name).debugName) return@forEach
            val nonDebugEligible = resolver.candidates(release)
                .filter { it.blocked == null && !it.traits.debugName }
            assertTrue(
                "$pkg/${release.tagName} auto-picked debug ${decision.asset.name} " +
                    "although ${nonDebugEligible.map { it.asset.name }} is available",
                nonDebugEligible.isEmpty(),
            )
        }
    }

    @Test
    fun equalScoringCandidatesAreNeverSilentlyPickedAsFirst() {
        var checked = 0
        everyRelease().forEach { (pkg, release) ->
            val eligible = resolver.candidates(release).filter { it.blocked == null }
            if (eligible.size < 2) return@forEach
            if (eligible.map { it.score }.distinct().size != 1) return@forEach
            // A unique non-debug candidate is a deliberate debug de-prioritisation,
            // not an arbitrary first() pick.
            if (eligible.count { !it.traits.debugName } == 1) return@forEach
            // A different device-ABI fit is a real signal (and the resolver is
            // expected to use it), so tied scores alone are not "arbitrary".
            val fits = eligible.map { candidate ->
                candidate.traits.abis.firstOrNull { it in device.abis }
                    ?: if (candidate.traits.universal) "universal" else "none"
            }
            if (fits.distinct().size > 1) return@forEach
            checked++
            assertTrue(
                "$pkg/${release.tagName} tied at ${eligible.first().score} but was not Choose",
                resolver.resolve(release) is VariantDecision.Choose,
            )
        }
        assertTrue("expected tied multi-APK releases in the corpus", checked > 0)
    }

    @Test
    fun hyperCeilerAsksInsteadOfPreselectingOs2() {
        val release = latestRelease("com.sevtinge.hyperceiler")
        val decision = resolver.resolve(release)
        assertTrue("OS variants must not be guessed without a device profile", decision is VariantDecision.Choose)
        val candidates = (decision as VariantDecision.Choose).candidates
        assertEquals(
            listOf(
                "OS2_HyperCeiler-2.10.166-20260402-release.apk",
                "OS3_HyperCeiler-2.10.166-20260331-release.apk",
            ),
            candidates.map { it.asset.name },
        )
        assertTrue("neither OS variant may be marked recommended", candidates.none { it.recommended })
    }

    @Test
    fun killAppNowAsksButRecommendsTheLibXposedBuild() {
        val decision = resolver.resolve(latestRelease("com.asameer.killappnowext"))
        assertTrue(decision is VariantDecision.Choose)
        val candidates = (decision as VariantDecision.Choose).candidates
        val recommended = candidates.first { it.recommended }
        assertEquals(
            "KillAppNow_Ext_3.0_A16_OneUI8.x_API-101.0.1.apk",
            recommended.asset.name,
        )
        assertEquals(101, recommended.traits.libxposedApi)
    }

    @Test
    fun panelAndTileFlavorsAreNotGuessed() {
        val decision = resolver.resolve(latestRelease("com.qingyu.mi5g"))
        assertTrue(decision is VariantDecision.Choose)
        assertEquals(
            setOf(
                "FiveGSwitcher-2.0.4_14-panel.apk",
                "FiveGSwitcher-2.0.4_14-tile.apk",
            ),
            (decision as VariantDecision.Choose).candidates.map { it.asset.name }.toSet(),
        )
    }

    @Test
    fun mictsPicksTheModuleAndFoldsTheCompanionApp() {
        val release = latestRelease("com.parallelc.micts")
        val decision = resolver.resolve(release)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("MiCTS_2.6.apk", (decision as VariantDecision.Pick).asset.name)
        val folded = resolver.candidates(release).first { it.asset.name == "VISTrigger_2.6.apk" }
        assertEquals(Reason.Kind.BUNDLED_APP, folded.blocked?.kind)
    }

    @Test
    fun singleDebugApkInstallsWithoutConfirmation() {
        val decision = resolver.resolve(latestRelease("com.souleven.nothingos"))
        assertTrue(decision is VariantDecision.Pick)
        val pick = decision as VariantDecision.Pick
        assertEquals("app-debug.apk", pick.asset.name)
        assertEquals(VariantDecision.Confidence.HIGH, pick.confidence)
        assertTrue(pick.reasons.any { it.kind == Reason.Kind.DEBUG_BUILD })
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

        // A renamed release falls back to the name layer, still without probes.
        val unmatched = resolver.resolve(release, VariantSignature(abi = "x86_64"), probes)
        assertTrue(unmatched is VariantDecision.Choose)
        assertEquals(0, probes.reads)
    }

    @Test
    fun trustedStateResolveForUpdateDoesNotTouchProbes() {
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
        assertEquals(0, probes.reads)
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
    fun incompatibleMinSdkMakesTheModuleNotApplicable() {
        val oldDevice = VariantResolver(DeviceProfile(sdkInt = 33, abis = listOf("arm64-v8a")))
        val decision = oldDevice.resolve(releaseOf("app-arm64Minsdk35-release.apk"))
        assertTrue(decision is VariantDecision.NotApplicable)
        val reason = (decision as VariantDecision.NotApplicable).reason
        assertEquals(Reason.Kind.MIN_SDK_TOO_HIGH, reason.kind)
        assertEquals(listOf("35", "33"), reason.args)
    }

    @Test
    fun hyperCeilerPicksOs3WithAVendorProfile() {
        val profiled = VariantResolver(
            DeviceProfile(
                sdkInt = 36,
                abis = listOf("arm64-v8a"),
                vendorOs = VendorOs(VendorOs.Family.HYPEROS, 3),
            ),
        )
        val decision = profiled.resolve(latestRelease("com.sevtinge.hyperceiler"))
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "OS3_HyperCeiler-2.10.166-20260331-release.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
        assertTrue(decision.reasons.any { it.kind == Reason.Kind.MATCHES_DEVICE })
    }

    @Test
    fun killAppNowPicksTheOneUi8LibXposedBuild() {
        val profiled = VariantResolver(
            DeviceProfile(
                sdkInt = 36,
                abis = listOf("arm64-v8a"),
                vendorOs = VendorOs(VendorOs.Family.ONEUI, 8),
                lsposedApi = 102,
            ),
        )
        val decision = profiled.resolve(latestRelease("com.asameer.killappnowext"))
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "KillAppNow_Ext_3.0_A16_OneUI8.x_API-101.0.1.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
    }

    @Test
    fun minSdkSplitPrefersTheLargestCompatibleBuild() {
        val decision = resolver.resolve(
            latestRelease("io.github.xjyzs.systemednotificationblocker"),
        )
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "app-arm64Minsdk35-release.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
    }

    @Test
    fun deviceAbiFitOutranksTheDownloadPrior() {
        // XposedSmsCode ships arm64/arm32/x86_64/x86/universal with very uneven
        // download counts. The x86_64-first emulator profile must still get the
        // x86_64 build; the arm64 profile must get arm64.
        val release = latestRelease("com.github.tianma8023.xposed.smscode")
        val x86Device = VariantResolver(
            DeviceProfile(36, listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")),
        )
        val x86Decision = x86Device.resolve(release)
        assertTrue(x86Decision is VariantDecision.Pick)
        assertTrue(
            (x86Decision as VariantDecision.Pick).asset.name,
            x86Decision.asset.name.startsWith("x86_64_"),
        )

        val armDecision = resolver.resolve(release)
        assertTrue(armDecision is VariantDecision.Pick)
        assertTrue(
            (armDecision as VariantDecision.Pick).asset.name,
            armDecision.asset.name.startsWith("arm64-v8a_"),
        )
    }

    @Test
    fun probesBlockARealIncompatibleAbi() {
        val release = releaseOf("app.apk")
        val probes = mapOf(
            "app.apk" to ApkMeta(abis = setOf("riscv64"), hasXposedDeclaration = true),
        )
        val decision = resolver.resolve(release, probes = probes, expectedPackage = "com.example")
        assertTrue(decision is VariantDecision.NotApplicable)
        assertEquals(
            Reason.Kind.ABI_INCOMPATIBLE,
            (decision as VariantDecision.NotApplicable).reason.kind,
        )
    }

    @Test
    fun probesBlockMinSdkAboveTheDevice() {
        val release = releaseOf("app.apk")
        val probes = mapOf("app.apk" to ApkMeta(minSdk = 37, hasXposedDeclaration = true))
        val decision = resolver.resolve(release, probes = probes, expectedPackage = "com.example")
        assertTrue(decision is VariantDecision.NotApplicable)
        assertEquals(
            Reason.Kind.MIN_SDK_TOO_HIGH,
            (decision as VariantDecision.NotApplicable).reason.kind,
        )
    }

    @Test
    fun probePackageMismatchWithoutDeclarationFoldsTheCompanionApp() {
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
        assertEquals(Reason.Kind.PACKAGE_MISMATCH, folded.blocked?.kind)
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
        assertTrue("renamed modules may only be a warning", decision is VariantDecision.Choose)
        val blocked = resolver.candidates(
            release,
            probes = probes,
            expectedPackage = "com.example.module",
        ).filter { it.blocked != null }
        assertTrue(blocked.isEmpty())
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

    private fun releaseOf(vararg names: String): Release = Release(
        tagName = "1-1.0",
        name = null,
        url = null,
        isPrerelease = false,
        publishedAt = null,
        descriptionHtml = null,
        version = ModuleVersion.parse("1-1.0"),
        assets = names.map { name ->
            ReleaseAsset(
                name = name,
                downloadUrl = "https://example.invalid/$name",
                fallbackUrl = null,
                size = 1024,
                downloadCount = 0,
                contentType = null,
            )
        },
    )

    private fun latestRelease(pkg: String): Release {
        val entry = corpus().firstOrNull { it.pkg == pkg }
        assertNotNull("missing corpus entry $pkg", entry)
        val module = entry!!
        return module.releases.firstOrNull { it.tagName == module.latestTag }
            ?: module.releases.first()
    }

    private fun everyRelease(): List<Pair<String, Release>> = corpus().flatMap { module ->
        module.releases.map { module.pkg to it }
    }

    private val cachedCorpus: List<CorpusModule> by lazy { loadCorpus() }

    private fun corpus(): List<CorpusModule> = cachedCorpus

    private fun loadCorpus(): List<CorpusModule> {
        val text = checkNotNull(javaClass.classLoader?.getResource("variant-corpus.json")) {
            "variant-corpus.json missing from test resources"
        }.readText()
        val root = Json.parseToJsonElement(text).jsonArray
        return root.map { module ->
            val obj = module.jsonObject
            val pkg = obj.getValue("pkg").jsonPrimitive.content
            val latestTag = obj["repoLatest"]?.jsonPrimitive?.content
            val releases = obj.getValue("releases").jsonArray.map { element ->
                val release = element.jsonObject
                val tag = release.getValue("tag").jsonPrimitive.content
                val assets = release.getValue("apks").jsonArray.map { apkElement ->
                    val apk = apkElement.jsonArray
                    ReleaseAsset(
                        name = apk[0].jsonPrimitive.content,
                        downloadUrl = "https://example.invalid/${apk[0].jsonPrimitive.content}",
                        fallbackUrl = null,
                        size = apk[1].jsonPrimitive.content.toLong(),
                        downloadCount = apk[2].jsonPrimitive.content.toInt(),
                        contentType = null,
                    )
                }
                Release(
                    tagName = tag,
                    name = null,
                    url = null,
                    isPrerelease = false,
                    publishedAt = null,
                    descriptionHtml = null,
                    version = ModuleVersion.parse(tag),
                    assets = assets,
                )
            }
            CorpusModule(pkg, latestTag, releases)
        }
    }

    private data class CorpusModule(
        val pkg: String,
        val latestTag: String?,
        val releases: List<Release>,
    )

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
