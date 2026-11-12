package io.github.tophtab.lspstore

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.ScoredAsset
import io.github.tophtab.lspstore.domain.variant.parseTraits
import io.github.tophtab.lspstore.domain.variant.selectInstallAsset

class AssetSelectionTest {

    private fun asset(name: String) = ReleaseAsset(
        name = name,
        downloadUrl = "",
        fallbackUrl = null,
        size = 0,
        downloadCount = 0,
        contentType = null,
    )

    private fun candidate(name: String, blocked: Reason? = null) = ScoredAsset(
        asset = asset(name),
        traits = parseTraits(name),
        score = 0,
        recommended = false,
        blocked = blocked,
    )

    @Test
    fun defaultFollowsCurrentEvidence() {
        val candidates = listOf(candidate("a.apk"), candidate("b.apk"))

        assertEquals("a.apk", selectInstallAsset(candidates, asset("a.apk"), "")?.name)
        // Probes or the installed copy changed the best candidate.
        assertEquals("b.apk", selectInstallAsset(candidates, asset("b.apk"), "")?.name)
    }

    @Test
    fun manualPickSurvivesEvidenceChange() {
        val candidates = listOf(candidate("a.apk"), candidate("b.apk"))

        assertEquals(
            "a.apk",
            selectInstallAsset(candidates, default = asset("b.apk"), userPickedName = "a.apk")?.name,
        )
    }

    @Test
    fun legacyBlockedMetadataCannotOverrideManualPick() {
        val blocked = Reason(Reason.Kind.PACKAGE_MISMATCH)
        val candidates = listOf(candidate("a.apk", blocked), candidate("b.apk"))

        assertEquals(
            "a.apk",
            selectInstallAsset(candidates, default = asset("b.apk"), userPickedName = "a.apk")?.name,
        )
    }

    @Test
    fun legacyBlockedMetadataCannotDisableDefault() {
        val blocked = Reason(Reason.Kind.PACKAGE_MISMATCH)
        val candidates = listOf(candidate("a.apk", blocked), candidate("b.apk"))

        assertEquals("a.apk", selectInstallAsset(candidates, default = asset("a.apk"), userPickedName = "")?.name)
    }

    @Test
    fun missingManualPickFallsBackToDefault() {
        val candidates = listOf(candidate("b.apk"))

        assertEquals(
            "b.apk",
            selectInstallAsset(candidates, default = asset("b.apk"), userPickedName = "a.apk")?.name,
        )
    }
}
