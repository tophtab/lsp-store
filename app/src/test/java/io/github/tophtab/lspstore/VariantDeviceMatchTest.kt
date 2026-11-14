package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.VariantFixtures.latestRelease
import io.github.tophtab.lspstore.VariantFixtures.releaseOf
import io.github.tophtab.lspstore.domain.variant.DeviceProfile
import io.github.tophtab.lspstore.domain.variant.Reason
import io.github.tophtab.lspstore.domain.variant.VariantDecision
import io.github.tophtab.lspstore.domain.variant.VariantResolver
import io.github.tophtab.lspstore.domain.variant.VendorOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantDeviceMatchTest {

    private val device = DeviceProfile(sdkInt = 36, abis = listOf("arm64-v8a", "armeabi-v7a"))
    private val resolver = VariantResolver(device)

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
}
