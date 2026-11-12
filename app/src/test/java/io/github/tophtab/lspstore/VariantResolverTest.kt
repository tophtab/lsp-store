package io.github.tophtab.lspstore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
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
                .filter {
                    it.blocked == null && !it.traits.debugName &&
                        it.warning?.kind != Reason.Kind.BUNDLED_APP
                }
            assertTrue(
                "$pkg/${release.tagName} auto-picked debug ${decision.asset.name} " +
                    "although ${nonDebugEligible.map { it.asset.name }} is available",
                nonDebugEligible.isEmpty(),
            )
        }
    }

    @Test
    fun everyReleaseResolvesToAnInstallableDefaultOrNothing() {
        everyRelease().forEach { (pkg, release) ->
            val decision = resolver.resolve(release)
            when (decision) {
                is VariantDecision.Pick -> assertTrue(
                    "$pkg/${release.tagName} picked a blocked asset",
                    resolver.candidates(release).none {
                        it.asset.name == decision.asset.name && it.blocked != null
                    },
                )

                // The only allowed "no default" is a release without an APK or a
                // release whose every APK is a companion app. Nothing else may
                // stop an install or an update-all run.
                is VariantDecision.NotApplicable -> {
                    val eligible = resolver.candidates(release).filter { it.blocked == null }
                    assertTrue(
                        "$pkg/${release.tagName} gave up although ${eligible.map { it.asset.name }} fits",
                        eligible.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun tiedCandidatesDefaultToTheMostInstalledBuildInsteadOfTheAlphabeticalFirst() {
        var checked = 0
        everyRelease().forEach { (pkg, release) ->
            val eligible = resolver.candidates(release).filter { it.blocked == null }
            if (eligible.size < 2) return@forEach
            // A unique non-debug candidate, a device ABI fit or a real device
            // signal is a legitimate reason to rank, so only the remaining pile
            // (edition/flavour/name-only splits) is inspected here.
            val fits = eligible.map { candidate ->
                candidate.traits.abis.firstOrNull { it in device.abis }
                    ?: if (candidate.traits.universal) "universal" else "none"
            }
            if (fits.distinct().size > 1) return@forEach
            if (eligible.map { it.score }.distinct().size != 1) return@forEach
            // A minSdk split, a different advertised OS/Android version or a
            // different libxposed API number are real signals too.
            if (eligible.map { it.meta?.minSdk ?: it.traits.minSdkToken ?: -1 }.distinct().size > 1) {
                return@forEach
            }
            if (eligible.map { it.traits.vendor?.second ?: it.traits.android ?: 0 }
                    .distinct().size > 1
            ) {
                return@forEach
            }
            if (eligible.map { it.traits.libxposedApi ?: 0 }.distinct().size > 1) return@forEach
            if (eligible.count { !it.traits.debugName } == 1) return@forEach
            val decision = resolver.resolve(release) as VariantDecision.Pick
            val plain = eligible.filter { it.traits.edition.isEmpty() && it.traits.flavors.isEmpty() }
                .ifEmpty { eligible }
            val expected = plain.maxByOrNull { it.asset.downloadCount }!!.asset.name
            if (expected != eligible.first().asset.name) checked++
            assertEquals(
                "$pkg/${release.tagName} did not fall back to the most installed build",
                expected,
                decision.asset.name,
            )
            if (expected == eligible.first().asset.name) return@forEach
            assertTrue(
                "$pkg/${release.tagName} kept the alphabetical first asset",
                decision.asset.name != eligible.first().asset.name,
            )
        }
        assertTrue("expected tied multi-APK releases in the corpus", checked > 0)
    }

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
    fun hyperCeilerDefaultsToTheOsBuildMostUsersInstalled() {
        val release = latestRelease("com.sevtinge.hyperceiler")
        val decision = resolver.resolve(release)
        // No device profile: OS2 and OS3 score the same, so the default is the
        // build the repository's users actually installed (OS3, 117k vs 60k).
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "OS3_HyperCeiler-2.10.166-20260331-release.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
        assertEquals(VariantDecision.Confidence.DEFAULT, decision.confidence)
        // Both rows stay one tap away in the resource sheet.
        assertEquals(
            listOf(
                "OS3_HyperCeiler-2.10.166-20260331-release.apk",
                "OS2_HyperCeiler-2.10.166-20260402-release.apk",
            ),
            resolver.ranked(resolver.candidates(release)).map { it.asset.name },
        )
    }

    @Test
    fun killAppNowDefaultsToTheAndroid16LibXposedBuild() {
        val decision = resolver.resolve(latestRelease("com.asameer.killappnowext"))
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(
            "KillAppNow_Ext_3.0_A16_OneUI8.x_API-101.0.1.apk",
            (decision as VariantDecision.Pick).asset.name,
        )
    }

    @Test
    fun panelAndTileFlavorsDefaultToTheMostInstalledPanel() {
        val decision = resolver.resolve(latestRelease("com.qingyu.mi5g"))
        assertTrue(decision is VariantDecision.Pick)
        // Both are legitimate flavours; the repository's users picked panel by a
        // wide margin (20k vs 7.9k), so that is the default.
        assertEquals("FiveGSwitcher-2.0.4_14-panel.apk", (decision as VariantDecision.Pick).asset.name)
        assertEquals(VariantDecision.Confidence.DEFAULT, decision.confidence)
        assertEquals(
            setOf(
                "FiveGSwitcher-2.0.4_14-panel.apk",
                "FiveGSwitcher-2.0.4_14-tile.apk",
            ),
            resolver.candidates(latestRelease("com.qingyu.mi5g"))
                .filter { it.blocked == null }
                .map { it.asset.name }
                .toSet(),
        )
    }

    @Test
    fun mictsPicksTheModuleAndWarnsAboutTheUnprobedCompanionName() {
        val release = latestRelease("com.parallelc.micts")
        val decision = resolver.resolve(release)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("MiCTS_2.6.apk", (decision as VariantDecision.Pick).asset.name)
        val folded = resolver.candidates(release).first { it.asset.name == "VISTrigger_2.6.apk" }
        assertNull(folded.blocked)
        assertEquals(Reason.Kind.BUNDLED_APP, folded.warning?.kind)
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

        // A renamed release falls back to the name layer, still without probes,
        // and still produces a usable default.
        val unmatched = resolver.resolve(release, VariantSignature(abi = "x86_64"), probes)
        assertTrue(unmatched is VariantDecision.Pick)
        assertEquals(VariantDecision.Confidence.DEFAULT, (unmatched as VariantDecision.Pick).confidence)
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
    fun aMinsdkTokenInTheFileNameOnlyWarns() {
        val oldDevice = VariantResolver(DeviceProfile(sdkInt = 33, abis = listOf("arm64-v8a")))
        val release = releaseOf("app-arm64Minsdk35-release.apk")
        // Authors mislabel files, and the manifest - which the probe reads and the
        // installer enforces - is the truth, so the token may not hide the build.
        val decision = oldDevice.resolve(release)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("app-arm64Minsdk35-release.apk", (decision as VariantDecision.Pick).asset.name)
        val candidate = oldDevice.candidates(release).single()
        assertEquals(Reason.Kind.MIN_SDK_TOO_HIGH, candidate.warning?.kind)
        assertEquals(listOf("35", "33"), candidate.warning?.args)
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
        assertTrue("renamed modules may only be a warning", decision is VariantDecision.Pick)
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

    @Test
    fun newerDeclaredApiStillInstallsAndOnlyWarns() {
        val older = VariantResolver(
            DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a"), lsposedApi = 100),
        )
        val release = releaseOf("Hook_app-api-102.apk")
        val decision = older.resolve(release)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals("Hook_app-api-102.apk", (decision as VariantDecision.Pick).asset.name)

        val candidate = older.candidates(release).single()
        assertEquals(Reason.Kind.API_TOO_NEW, candidate.warning?.kind)
        assertEquals(listOf("102"), candidate.warning?.args)
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

    private fun asset(name: String, downloads: Int): ReleaseAsset = ReleaseAsset(
        name = name,
        downloadUrl = "https://example.invalid/$name",
        fallbackUrl = null,
        size = 1024,
        downloadCount = downloads,
        contentType = null,
    )

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
