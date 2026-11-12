package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.*
import io.github.tophtab.lspstore.data.repository.*
import io.github.tophtab.lspstore.domain.model.*
import io.github.tophtab.lspstore.domain.variant.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class InstallConfirmationTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T

    private inner class Harness {
        val events = mutableListOf<String>()
        val states = MutableStateFlow<Map<String, ModuleVariantState>>(emptyMap())
        val pending = MutableStateFlow<Map<String, PendingInstall>>(emptyMap())
        val registry = InstallTaskRegistry()
        val serializer = SystemInstallSerializer()
        var installed: InstalledModule? = null
        val library = fake(LibraryRepository::class.java) { name, args ->
            when (name) {
                "getVariantStates" -> states
                "linkModule" -> { events += "link:${args[0]}:${args[1]}"; Unit }
                "recordConfirmedInstall" -> {
                    events += "record"
                    val repo = args[0] as String
                    states.value = states.value + (repo to (states.value[repo] ?: ModuleVariantState()).copy(confirmedInstall = args[1] as ConfirmedInstallRecord))
                    Unit
                }
                "recordInstallResult" -> { events += "legacy"; Unit }
                else -> error("Unexpected library call $name")
            }
        }
        val repository = fake(PendingInstallRepository::class.java) { name, args ->
            when (name) {
                "getPending" -> pending
                "clearIfCurrent" -> {
                    val entry = args[0] as PendingInstall
                    val matches = pending.value[entry.packageName] == entry
                    if (matches) {
                        events += "clear"
                        pending.value = pending.value - entry.packageName
                    }
                    matches
                }
                else -> error("Unexpected pending call $name")
            }
        }
        val confirmation = InstallConfirmation(library, repository, registry, serializer) { installed?.takeIf { entry -> entry.packageName == it } }
        suspend fun begin(target: Boolean = true): PendingInstall {
            val id = requireNotNull(registry.claim("repo", "actual"))
            val asset = ReleaseAsset("OS2_module.apk", "https://example.invalid/a", null, 10, 0, null)
            val source = ResolvedInstallTarget("4571-release", ModuleVersion.parse("4571-release"), "2026-11-01T00:00:00Z", asset, VariantSignature.ofAsset(asset.name), apkPackageName = "actual", apkVersionCode = 4559, apkVersionName = "os2")
            val entry = PendingInstall("actual", "repo", versionCode = 4559, versionName = "os2", createdAt = 100, taskId = id, target = source.takeIf { target }, previousLastUpdateTime = 50)
            pending.value = mapOf("actual" to entry)
            registry.stage(id, InstallStage.PENDING_CONFIRMATION)
            serializer.awaitFreeAndMarkPending("actual", taskId = id)
            return entry
        }
        fun landed() { installed = InstalledModule("actual", 4559, "os2", "Module", false, null, lastUpdateTime = 101) }
    }

    @Test fun confirmationWritesSourceBeforeClearingAndKeepsExplicitPreference() = runTest {
        val harness = Harness()
        val entry = harness.begin()
        val preference = VariantSignature.ofAsset("OS3_module.apk")
        harness.states.value = mapOf("repo" to ModuleVariantState(explicitPreference = preference, preferenceMigrated = true))
        harness.landed()
        assertTrue(harness.confirmation.confirm(entry))
        assertEquals(listOf("link:actual:repo", "record", "clear"), harness.events)
        assertEquals(preference, harness.states.value["repo"]?.explicitPreference)
        assertEquals(entry.target, harness.states.value["repo"]?.confirmedInstall?.target)
        assertFalse(harness.registry.isRunning("repo"))
        assertFalse(harness.serializer.isHeldBy("actual"))
        assertTrue(harness.pending.value.isEmpty())
    }

    @Test fun merelyOpeningTheInstallerOrSeeingTheOldPackageNeverRecordsARelease() = runTest {
        val harness = Harness()
        val entry = harness.begin()
        harness.installed = InstalledModule("actual", 4559, "os2", "Module", false, null, lastUpdateTime = 50)
        assertFalse(harness.confirmation.confirm(entry))
        assertTrue(harness.events.isEmpty())
        assertTrue(harness.registry.isRunning("repo"))
        assertEquals(entry, harness.pending.value["actual"])
        harness.installed = null
        assertFalse(harness.confirmation.confirm(entry))
        assertTrue(harness.states.value.isEmpty())
    }

    @Test fun aDifferentNewPackageVersionDoesNotProveTheRequestedApkLanded() = runTest {
        val harness = Harness()
        val entry = harness.begin()
        harness.landed()
        harness.installed = harness.installed?.copy(versionCode = 5000)
        assertFalse(harness.confirmation.confirm(entry))
        assertTrue(harness.states.value.isEmpty())
        assertTrue(harness.registry.isRunning("repo"))
    }

    @Test fun cancellationAndLateCompletionCannotClearANewPendingOnTheSamePackage() = runTest {
        val harness = Harness()
        val old = harness.begin()
        assertTrue(harness.confirmation.cancel(old))
        val next = harness.begin()
        harness.landed()
        assertFalse(harness.confirmation.cancel(old))
        assertFalse(harness.confirmation.confirm(old))
        assertEquals(next, harness.pending.value["actual"])
        assertEquals(next.taskId, harness.registry.runs.value["repo"]?.taskId)
        assertTrue(harness.serializer.isHeldBy("actual"))
        assertTrue(harness.states.value.isEmpty())
    }

    @Test fun cancelledApkCacheIsInvalidatedBeforeANewTaskCanClaimItsModule() = runTest {
        val harness = Harness()
        val entry = harness.begin()
        var cleaned = false
        assertTrue(harness.confirmation.cancel(entry) {
            assertNull(harness.registry.claim("repo", "actual"))
            assertEquals(entry, harness.pending.value["actual"])
            cleaned = true
        })
        assertTrue(cleaned)
        val next = harness.begin()
        assertFalse(harness.confirmation.cancel(entry) { fail("A stale callback must not invalidate the new task's APK") })
        assertEquals(next, harness.pending.value["actual"])
    }

    @Test fun legacyPendingSettlesWithoutFabricatingReleaseProvenance() = runTest {
        val harness = Harness()
        val entry = harness.begin(target = false)
        harness.landed()
        assertTrue(harness.confirmation.confirm(entry))
        assertFalse("record" in harness.events)
        assertTrue("legacy" in harness.events)
        assertTrue(harness.states.value.isEmpty())
    }

    @Test fun unreadableApkIdentityNeverCreatesAConfirmedSource() = runTest {
        val harness = Harness()
        val original = harness.begin()
        val entry = original.copy(target = original.target?.copy(apkPackageName = null, apkVersionCode = null, apkVersionName = null))
        harness.pending.value = mapOf(entry.packageName to entry)
        harness.landed()
        assertTrue(harness.confirmation.confirm(entry))
        assertFalse("record" in harness.events)
        assertTrue(harness.states.value.isEmpty())
    }
}
