package io.github.tophtab.lspstore

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.onInstallFailed
import io.github.tophtab.lspstore.domain.variant.onInstallSucceeded
import io.github.tophtab.lspstore.domain.variant.onUserPicked

class VariantStateMachineTest {

    private val signature = VariantSignature(
        abi = "arm64-v8a",
        api = "libxposed",
        os = "hyperos3",
    )

    @Test
    fun successIsTrustedAndClearsFailures() {
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.SUSPECT,
            failures = 2,
        ).onInstallSucceeded(signature, now = 10L)

        assertEquals(ModuleVariantState.Status.TRUSTED, state.status)
        assertEquals(signature, state.signature)
        assertEquals(0, state.failures)
        assertEquals(10L, state.updatedAt)
    }

    @Test
    fun firstFailureIsSuspectAndKeepsTheSignature() {
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = signature,
            failures = 0,
        ).onInstallFailed(now = 20L)

        assertEquals(ModuleVariantState.Status.SUSPECT, state.status)
        assertEquals(1, state.failures)
        assertEquals(signature, state.signature)
    }

    @Test
    fun repeatedFailuresKeepTheModuleVisibleAndBoundTheCounter() {
        var state = ModuleVariantState()
        repeat(5) { state = state.onInstallFailed() }
        // An install failure is not a verdict about the device: the module keeps
        // re-probing its variants and stays in the update list (#65, #69).
        assertEquals(ModuleVariantState.Status.SUSPECT, state.status)
        assertEquals(ModuleVariantState.MAX_FAILURES, state.failures)
    }

    @Test
    fun userPickIsTrustedImmediately() {
        val picked = VariantSignature(os = "hyperos2")
        val state = ModuleVariantState(status = ModuleVariantState.Status.SUSPECT, failures = 2)
            .onUserPicked(picked, now = 30L)

        assertEquals(ModuleVariantState.Status.TRUSTED, state.status)
        assertEquals(picked, state.signature)
        assertEquals(0, state.failures)
    }

    @Test
    fun aStoredVerdictFromAnOlderBuildDecodesAsUnknownAndKeepsTheSignature() {
        // Builds before #73 wrote NOT_APPLICABLE plus a reason key. Neither is part
        // of the model any more; decoding one costs a probe, never a hidden module.
        val legacy = """
            {
              "verdict": {"status": "NOT_APPLICABLE", "reason": "MIN_SDK_TOO_HIGH\u001f35\u001f33",
                          "failures": 0, "updatedAt": 5},
              "trusted": {"status": "TRUSTED", "signature": {"abi": "arm64-v8a"},
                          "failures": 0, "updatedAt": 7}
            }
        """.trimIndent()

        val decoded = json.decodeFromString(
            MapSerializer(String.serializer(), ModuleVariantState.serializer()),
            legacy,
        )

        assertEquals(ModuleVariantState.Status.UNKNOWN, decoded.getValue("verdict").status)
        assertEquals(ModuleVariantState.Status.TRUSTED, decoded.getValue("trusted").status)
        assertEquals(VariantSignature(abi = "arm64-v8a"), decoded.getValue("trusted").signature)
    }

    @Test
    fun anUnknownStatusIsToleratedByAPlainDecoder() {
        // No coerceInputValues, no special config: the type itself has to accept a
        // status it does not know, wherever the bytes come from (#73).
        val decoded = Json.decodeFromString(
            ModuleVariantState.serializer(),
            "{\"status\":\"WITHDRAWN_BY_A_FUTURE_BUILD\"}",
        )

        assertEquals(ModuleVariantState.Status.UNKNOWN, decoded.status)
    }

    /** Mirrors the library repository's JSON config, without any coercion. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = signature,
            updatedAt = 42L,
        )
        val raw = json.encodeToString(ModuleVariantState.serializer(), state)
        assertEquals(state, json.decodeFromString(ModuleVariantState.serializer(), raw))
    }

    @Test
    fun trustedStateDoesNotReadProbes() {
        val release = Release(
            tagName = "1-1.0",
            name = null,
            url = null,
            isPrerelease = false,
            publishedAt = null,
            descriptionHtml = null,
            version = ModuleVersion.parse("1-1.0"),
            assets = listOf(
                asset("OS3_module.apk"),
                asset("OS2_module.apk"),
            ),
        )
        val probes = object : Map<String, ApkMeta> by emptyMap() {
            var reads = 0
            override fun get(key: String): ApkMeta? {
                reads++
                return null
            }
        }
        val trusted = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = VariantSignature(os = "hyperos3"),
        )
        val decision = VariantResolver(DeviceProfile(36, listOf("arm64-v8a")))
            .resolveForUpdate(trusted, release, probes)
        assertTrue(decision is VariantDecision.Pick)
        assertEquals(0, probes.reads)

        // UNKNOWN does consult probes (the P2 hook is live).
        VariantResolver(DeviceProfile(36, listOf("arm64-v8a")))
            .resolveForUpdate(ModuleVariantState(), release, probes)
        assertTrue("untrusted path should probe", probes.reads > 0)
    }

    private fun asset(name: String) = ReleaseAsset(
        name = name,
        downloadUrl = "https://example.invalid/$name",
        fallbackUrl = null,
        size = 1024,
        downloadCount = 0,
        contentType = null,
    )
}
