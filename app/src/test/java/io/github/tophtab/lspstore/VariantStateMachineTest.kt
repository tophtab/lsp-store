package io.github.tophtab.lspstore

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
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import io.github.tophtab.lspstore.domain.variant.onEvaluatedNoCandidate
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
    fun threeConsecutiveFailuresGiveUp() {
        var state = ModuleVariantState()
        repeat(3) { state = state.onInstallFailed() }
        assertEquals(ModuleVariantState.Status.NOT_APPLICABLE, state.status)
        assertEquals(3, state.failures)
    }

    @Test
    fun userPickIsTrustedImmediately() {
        val picked = VariantSignature(os = "hyperos2")
        val state = ModuleVariantState(status = ModuleVariantState.Status.NOT_APPLICABLE)
            .onUserPicked(picked, now = 30L)

        assertEquals(ModuleVariantState.Status.TRUSTED, state.status)
        assertEquals(picked, state.signature)
        assertEquals(0, state.failures)
    }

    @Test
    fun noCandidateStoresAStorageKeyNotText() {
        val reason = Reason(
            Reason.Kind.MIN_SDK_TOO_HIGH,
            listOf("35", "33"),
        )
        val state = ModuleVariantState().onEvaluatedNoCandidate(reason)
        assertEquals(ModuleVariantState.Status.NOT_APPLICABLE, state.status)
        assertEquals(reason, Reason.fromStorage(state.reason))
        assertEquals("MIN_SDK_TOO_HIGH\u001f35\u001f33", state.reason)
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
