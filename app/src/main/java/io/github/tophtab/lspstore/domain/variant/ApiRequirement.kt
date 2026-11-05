package io.github.tophtab.lspstore.domain.variant

/**
 * The libxposed API an APK actually needs, mirroring the loaders' strategy
 * (`FileSystem.loadModule` in LSPosed and Vector):
 *
 *  - `targetApiVersion >= 101` → the APK is loaded as a modern module, whatever
 *    else it carries, and that target is the API it was built against;
 *  - otherwise `assets/xposed_init` makes it a legacy module, so the libxposed
 *    API numbers in `module.prop` do not apply at all;
 *  - a `targetApiVersion` below 101 without a legacy entry is the API-100 era
 *    build;
 *  - with neither, `minApiVersion` is the only hint left.
 *
 * `minApiVersion` is the author's stated floor, not something the framework
 * enforces (Vector's daemon never reads it), so a mismatch must never refuse an
 * install — it only ranks and warns. See #47.
 */
fun libXposedApiRequirement(
    targetApiVersion: Int?,
    hasLegacyInit: Boolean,
    minApiVersion: Int?,
): Int? = when {
    targetApiVersion != null && targetApiVersion >= 101 -> targetApiVersion
    hasLegacyInit -> null
    targetApiVersion != null -> targetApiVersion
    else -> minApiVersion
}
