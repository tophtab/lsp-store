package io.github.tophtab.lspstore.domain.variant

import io.github.tophtab.lspstore.domain.model.InstalledModule

/**
 * What the copy already on the device looks like, read from PackageManager only
 * (no network, no probes).
 *
 * This is the strongest signal an *update* has and a first install does not: the
 * user is already running that build, so the new release should default to the
 * same package, ABI, libxposed generation and API levels instead of switching to
 * whatever other users downloaded. Dimensions PackageManager cannot see
 * (edition/flavour, vendor OS) stay out; those are covered by the remembered
 * [VariantSignature] once the module was installed through the store, and by the
 * plain-build/download ladder only when nothing else knows better.
 */
data class InstalledVariantHint(
    val packageName: String = "",
    /** `ApplicationInfo.primaryCpuAbi`: the ABI the installed APK actually uses. */
    val abi: String? = null,
    /** Whether the installed APK is a debug build. */
    val debuggable: Boolean = false,
    /** True when the installed APK declares itself as a modern libxposed module. */
    val libXposed: Boolean = false,
    /** Manifest minSdk of the installed APK, null when the cache predates it. */
    val minSdk: Int? = null,
    /** Manifest targetSdk of the installed APK, null when the cache predates it. */
    val targetSdk: Int? = null,
) {
    companion object {
        /** Package-manager projection of an installed module; cheap and local. */
        fun of(module: InstalledModule): InstalledVariantHint = InstalledVariantHint(
            packageName = module.packageName,
            abi = module.primaryCpuAbi?.takeIf { it.isNotBlank() },
            debuggable = module.debuggable,
            libXposed = module.hasLibXposedProvider,
            minSdk = module.minSdk.takeIf { it > 0 },
            targetSdk = module.targetSdk.takeIf { it > 0 },
        )
    }
}
