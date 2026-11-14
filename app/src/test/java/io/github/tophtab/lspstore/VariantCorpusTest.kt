package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.VariantFixtures.everyRelease
import io.github.tophtab.lspstore.VariantFixtures.latestRelease
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.parseTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantCorpusTest {

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
}
