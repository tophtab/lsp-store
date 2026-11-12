package io.github.tophtab.lspstore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.domain.model.ConfirmedInstallRecord
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import io.github.tophtab.lspstore.domain.model.ResolvedInstallTarget
import io.github.tophtab.lspstore.domain.usecase.ResolveVariantUseCase
import io.github.tophtab.lspstore.domain.variant.ApkMeta
import io.github.tophtab.lspstore.domain.variant.ApkProbe
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.InstalledVariantHint
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VariantSignature

class ResolveVariantUseCaseTest {

    private val device = DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a", "armeabi-v7a"))

    @Test
    fun cachedMetadataIsUsedEvenForATrustedNameDecisionWithoutMoreProbes() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-universal.apk")
        val probe = object : ApkProbe {
            var calls = 0
            override suspend fun cached(asset: ReleaseAsset): ApkMeta? = ApkMeta(packageName = "com.example.module", versionCode = 4559)
            override suspend fun fetch(asset: ReleaseAsset): ApkMeta? { calls++; return null }
        }
        val state = ModuleVariantState(status = ModuleVariantState.Status.TRUSTED, signature = VariantSignature(abi = "arm64-v8a"))
        val resolution = ResolveVariantUseCase(probe).resolve(release, state, device)
        assertEquals(2, resolution.probes.size)
        assertEquals(0, probe.calls)
        assertEquals("app-arm64-v8a.apk", (resolution.decision as VariantDecision.Pick).asset.name)
    }

    @Test
    fun trustedModulesNeverProbe() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-universal.apk")
        val probe = FakeProbe(emptyMap())
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.TRUSTED,
            signature = VariantSignature(abi = "arm64-v8a"),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("app-arm64-v8a.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(0, probe.calls)
        assertTrue(resolution.probes.isEmpty())
    }

    @Test
    fun singleApkBypassesProbeAndCacheForEveryStatusAndPickerRequest() = runBlocking {
        val single = releaseOf("app.apk")
        val release = single.copy(assets = single.assets + single.assets.single().copy(name = "sources.zip"))
        val probe = object : ApkProbe {
            override suspend fun cached(asset: ReleaseAsset): ApkMeta? =
                error("A single APK must not read the probe cache")
            override suspend fun fetch(asset: ReleaseAsset): ApkMeta? =
                error("A single APK must not be probed")
        }
        val useCase = ResolveVariantUseCase(probe)
        for (status in ModuleVariantState.Status.entries) {
            for (forceProbe in listOf(false, true)) {
                val resolution = useCase.resolve(
                    release, ModuleVariantState(status = status), device,
                    expectedPackage = "com.example.module", forceProbe = forceProbe,
                    installed = InstalledVariantHint(packageName = "com.example.module"),
                )
                assertEquals("app.apk", (resolution.decision as VariantDecision.Pick).asset.name)
                assertTrue(resolution.probes.isEmpty())
            }
        }
    }

    @Test
    fun clearDeviceChoiceDoesNotProbeJustBecauseItIsNewOrPreviouslyFailed() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk")
        val probe = FakeProbe(emptyMap())
        for (status in ModuleVariantState.Status.entries) {
            val resolution = ResolveVariantUseCase(probe).resolve(
                release, ModuleVariantState(status = status), device,
            )
            assertEquals("app-arm64-v8a.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        }
        assertEquals(0, probe.calls)
    }

    @Test
    fun ambiguousReleaseProbesTheTopCandidatesAndReResolves() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(
                    packageName = "com.example.module",
                    minSdk = 33,
                    hasXposedDeclaration = true,
                ),
                "OS3_Module.apk" to ApkMeta(
                    packageName = "com.example.module",
                    minSdk = 35,
                    hasXposedDeclaration = true,
                ),
            ),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("OS3_Module.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(2, probe.calls)
    }

    @Test
    fun legacyNotApplicableIsProbedAgain() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(minSdk = 33),
                "OS3_Module.apk" to ApkMeta(minSdk = 35),
            ),
        )
        val state = ModuleVariantState(status = ModuleVariantState.Status.NOT_APPLICABLE)
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        // An old verdict must not suppress the probes needed to recover it.
        val decision = resolution.decision as VariantDecision.Pick
        assertEquals("OS3_Module.apk", decision.asset.name)
        assertEquals(2, probe.calls)
    }

    @Test
    fun userOpeningThePickerMayProbeANotApplicableModule() = runBlocking {
        val release = releaseOf("OS2_Module.apk", "OS3_Module.apk")
        val probe = FakeProbe(
            mapOf(
                "OS2_Module.apk" to ApkMeta(minSdk = 33, hasXposedDeclaration = true),
                "OS3_Module.apk" to ApkMeta(minSdk = 35, hasXposedDeclaration = true),
            ),
        )
        val state = ModuleVariantState(status = ModuleVariantState.Status.NOT_APPLICABLE)
        val resolution = ResolveVariantUseCase(probe)
            .resolve(
                release,
                state,
                device,
                expectedPackage = "com.example.module",
                forceProbe = true,
            )

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals(2, probe.calls)
    }

    @Test
    fun uniquelyRememberedTypeIsReusedAfterAFailedAttempt() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk")
        val probe = FakeProbe(
            mapOf(
                "app-arm64-v8a.apk" to ApkMeta(
                    packageName = "com.example.module",
                    abis = setOf("arm64-v8a"),
                    hasXposedDeclaration = true,
                ),
            ),
        )
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.SUSPECT,
            signature = VariantSignature(abi = "arm64-v8a"),
        )
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, state, device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals("app-arm64-v8a.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(0, probe.calls)
    }

    @Test
    fun confirmedInstallationAndExplicitPreferenceCarryTheirTypeAcrossVersionsWithoutProbing() = runBlocking {
        val installed = InstalledModule(
            "com.example.module", 1, "1.0", "Module", true, null, lastUpdateTime = 100L,
        )
        val previous = releaseOf("Module-full-v1.0.apk", "Module-lite-v1.0.apk")
        val target = ResolvedInstallTarget.of(previous, previous.assets.first())
        val confirmed = ConfirmedInstallRecord(
            target, installed.packageName, installed.versionCode, installed.versionName, installed.lastUpdateTime,
        )
        val next = releaseOf("Module-full-v2.0.apk", "Module-lite-v2.0.apk").let {
            it.copy(assets = it.assets.map { asset ->
                asset.copy(downloadCount = if ("lite" in asset.name) 1000 else 0)
            })
        }
        val probe = object : ApkProbe {
            override suspend fun cached(asset: ReleaseAsset): ApkMeta? =
                error("A known asset family must be reused directly")
            override suspend fun fetch(asset: ReleaseAsset): ApkMeta? =
                error("A known asset family must not be probed")
        }
        val state = ModuleVariantState(
            status = ModuleVariantState.Status.SUSPECT,
            confirmedInstall = confirmed,
            preferenceMigrated = true,
        )
        val useCase = ResolveVariantUseCase(probe)
        val followed = useCase.resolve(
            next, state.forInstalled(installed), device,
            installed = InstalledVariantHint.of(installed), expectedPackage = installed.packageName,
        )
        assertEquals("Module-full-v2.0.apk", (followed.decision as VariantDecision.Pick).asset.name)

        val preferred = useCase.resolve(
            next, state.copy(explicitPreference = VariantSignature.ofAsset("Module-lite-v1.0.apk"))
                .forInstalled(installed), device,
            installed = InstalledVariantHint.of(installed), expectedPackage = installed.packageName,
        )
        assertEquals("Module-lite-v2.0.apk", (preferred.decision as VariantDecision.Pick).asset.name)
    }

    @Test
    fun updateWithUnclearHistoryProbesEvenWhenTheDeviceHasAClearDefault() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-universal.apk")
        val probe = FakeProbe(mapOf(
            "app-arm64-v8a.apk" to ApkMeta(packageName = "com.other.module", hasXposedDeclaration = true),
            "app-universal.apk" to ApkMeta(packageName = "com.example.module", hasXposedDeclaration = true),
        ))
        val resolution = ResolveVariantUseCase(probe).resolve(
            release, ModuleVariantState(), device,
            expectedPackage = "com.example.module",
            installed = InstalledVariantHint(packageName = "com.example.module"),
        )
        assertEquals(2, probe.calls)
        assertEquals("app-universal.apk", (resolution.decision as VariantDecision.Pick).asset.name)
    }

    @Test
    fun cachedInstalledIdentityResolvesAnUpdateWithoutNetworkRequests() = runBlocking {
        val release = releaseOf("app-arm64-v8a.apk", "app-universal.apk")
        val probe = object : ApkProbe {
            override suspend fun cached(asset: ReleaseAsset): ApkMeta? = ApkMeta(
                packageName = if ("universal" in asset.name) "com.example.module" else "com.other.module",
                hasXposedDeclaration = true,
            )
            override suspend fun fetch(asset: ReleaseAsset): ApkMeta? =
                error("The cached installed-package match is already sufficient")
        }
        val resolution = ResolveVariantUseCase(probe).resolve(
            release, ModuleVariantState(), device,
            expectedPackage = "com.example.module",
            installed = InstalledVariantHint(packageName = "com.example.module"),
        )
        assertEquals("app-universal.apk", (resolution.decision as VariantDecision.Pick).asset.name)
        assertEquals(2, resolution.probes.size)
    }

    @Test
    fun theInstalledCopyDecidesAnAmbiguousUpdate() = runBlocking {
        val release = releaseOf("app-release.apk", "module.apk")
        val probe = FakeProbe(
            mapOf(
                "app-release.apk" to ApkMeta(
                    packageName = "com.other.module",
                    hasXposedDeclaration = true,
                ),
                "module.apk" to ApkMeta(
                    packageName = "com.example.module",
                    hasXposedDeclaration = true,
                ),
            ),
        )
        val installed = InstalledVariantHint(
            packageName = "com.example.module",
            abi = "arm64-v8a",
            minSdk = 29,
            targetSdk = 34,
        )
        val resolution = ResolveVariantUseCase(probe).resolve(
            release = release,
            state = ModuleVariantState(),
            device = device,
            expectedPackage = "com.example.module",
            installed = installed,
        )

        val decision = resolution.decision as VariantDecision.Pick
        assertEquals("module.apk", decision.asset.name)
        assertEquals(VariantDecision.Confidence.HIGH, decision.confidence)
        assertEquals(2, probe.calls)
    }

    @Test
    fun probeFailureKeepsTheNameLayerDecision() = runBlocking {
        val release = releaseOf("app-full.apk", "app-lite.apk")
        val probe = FakeProbe(emptyMap())
        val expected = VariantResolver(device).resolve(release).defaultAsset()
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertTrue(resolution.decision is VariantDecision.Pick)
        assertEquals(expected, (resolution.decision as VariantDecision.Pick).asset)
        assertEquals(2, probe.calls)
        assertTrue(resolution.probes.isEmpty())
    }

    @Test
    fun aNameThatLooksLikeACompanionAppIsProbedInsteadOfHidden() = runBlocking {
        val release = releaseOf("Module_Client.apk", "Module_Provider.apk")
        val probe = FakeProbe(
            mapOf(
                "Module_Client.apk" to ApkMeta(
                    packageName = "com.example.module",
                    hasXposedDeclaration = true,
                ),
                "Module_Provider.apk" to ApkMeta(
                    packageName = "com.example.module",
                    hasXposedDeclaration = true,
                ),
            ),
        )
        // Every name looks like a companion app, so the name layer alone would
        // write a permanent "not applicable" verdict. One probe settles it and
        // the release still resolves to a default.
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        val decision = resolution.decision as VariantDecision.Pick
        assertEquals("Module_Client.apk", decision.asset.name)
        assertEquals(
            setOf("Module_Client.apk", "Module_Provider.apk"),
            VariantResolver(device)
                .candidates(release, probes = resolution.probes, expectedPackage = "com.example.module")
                .filter { it.blocked == null }
                .map { it.asset.name }
                .toSet(),
        )
        assertEquals(2, probe.calls)
    }

    @Test
    fun aMissingApkIsAVerdictWithoutProbing() = runBlocking {
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
        val probe = FakeProbe(emptyMap())
        val resolution = ResolveVariantUseCase(probe)
            .resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")

        assertEquals(
            Reason.Kind.NO_APK,
            (resolution.decision as VariantDecision.NotApplicable).reason.kind,
        )
        assertEquals(0, probe.calls)
    }

    @Test
    fun failedProbesCannotDisableEveryCompanionLookingName() = runBlocking {
        val release = releaseOf("Module_Client.apk", "Module_Provider.apk")
        val resolution = ResolveVariantUseCase(FakeProbe(emptyMap())).resolve(
            release, ModuleVariantState(), device, expectedPackage = "com.example.module",
        )
        assertTrue(resolution.decision is VariantDecision.Pick)
        assertTrue(
            VariantResolver(device).candidates(release, expectedPackage = "com.example.module")
                .all { it.blocked == null },
        )
    }

    @Test
    fun aRenamedLegacyModuleWithOnlyAnInitEntryIsNotACompanion() = runBlocking {
        val release = releaseOf("Module_Client.apk", "Module_Provider.apk")
        val resolution = ResolveVariantUseCase(
            FakeProbe(
                release.apkAssets.associate { asset ->
                    asset.name to ApkMeta(packageName = "com.example.renamed", hasLegacyInit = true)
                },
            ),
        ).resolve(release, ModuleVariantState(), device, expectedPackage = "com.example.module")
        assertTrue(resolution.decision is VariantDecision.Pick)
        assertTrue(
            VariantResolver(device).candidates(
                release, probes = resolution.probes, expectedPackage = "com.example.module",
            ).all { it.blocked == null },
        )
    }

    @Test
    fun forceProbeIncludesEveryApkOfTrustedMixedRelease() = runBlocking {
        val release = releaseOf("MiCTS_2.7.apk", "VISTrigger_2.7.apk")
        val probe = FakeProbe(release.apkAssets.associate {
            it.name to ApkMeta(packageName = "com.example.module", hasXposedDeclaration = false)
        })
        val resolution = ResolveVariantUseCase(probe).resolve(
            release,
            ModuleVariantState(status = ModuleVariantState.Status.TRUSTED,
                signature = VariantSignature.ofAsset("VISTrigger_2.6.apk")),
            device, expectedPackage = "com.example.module", forceProbe = true,
        )
        assertEquals(2, probe.calls)
        assertEquals(release.apkAssets.map { it.name }.toSet(), resolution.probes.keys)
        assertEquals("VISTrigger_2.7.apk", (resolution.decision as VariantDecision.Pick).asset.name)
    }

    @Test
    fun manualFlavourSurvivesUpgradeAndFailedInstallWithUnavailableProbes() = runBlocking {
        val release = releaseOf("MiCTS_2.7.apk", "VISTrigger_2.7.apk")
        for (status in listOf(ModuleVariantState.Status.TRUSTED, ModuleVariantState.Status.SUSPECT)) {
            val probe = FakeProbe(emptyMap())
            val result = ResolveVariantUseCase(probe).resolve(
                release,
                ModuleVariantState(status = status, signature = VariantSignature.ofAsset("VISTrigger_2.6.apk")),
                device, expectedPackage = "com.example.module",
            )
            assertEquals("VISTrigger_2.7.apk", (result.decision as VariantDecision.Pick).asset.name)
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun partiallySeededTrustDoesNotSkipProbesNeededToFollowInstalledPackage() = runBlocking {
        val release = releaseOf("app-release.apk", "module.apk")
        val probe = FakeProbe(mapOf(
            "app-release.apk" to ApkMeta(packageName = "com.other", hasXposedDeclaration = true),
            "module.apk" to ApkMeta(packageName = "com.example.module", hasXposedDeclaration = true),
        ))
        val result = ResolveVariantUseCase(probe).resolve(
            release, ModuleVariantState(status = ModuleVariantState.Status.TRUSTED, signature = VariantSignature()),
            device, expectedPackage = "com.example.module",
            installed = InstalledVariantHint(packageName = "com.example.module"),
        )
        assertEquals(2, probe.calls)
        assertEquals("module.apk", (result.decision as VariantDecision.Pick).asset.name)
    }

    private class FakeProbe(private val metas: Map<String, ApkMeta>) : ApkProbe {
        var calls = 0
            private set

        override suspend fun fetch(asset: ReleaseAsset): ApkMeta? {
            calls++
            return metas[asset.name]
        }
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
}
