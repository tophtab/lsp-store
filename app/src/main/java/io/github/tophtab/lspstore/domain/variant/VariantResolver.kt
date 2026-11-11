package io.github.tophtab.lspstore.domain.variant

import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset

/**
 * One name-layer resolver for every install path (detail page, installed page,
 * batch update, background worker). Pure Kotlin so the scoring and the
 * "always return a defensible default" guarantees are unit tested.
 *
 * A release with several APKs is split by nine different axes in practice; the
 * resolver handles them in this order:
 *  1. identity — companion apps shipped in the same release are folded;
 *  2. device fit — debug split, ABI, libxposed API generation, vendor OS /
 *     Android version, minSdk (name layer), then the same from probes;
 *  3. author preference — edition/flavour builds that no device signal can
 *     decide: the plain build, then the most installed one, then the name.
 *
 * Only steps 1 and 2 may make a candidate unusable; everything else ranks.
 * A release always resolves to one [VariantDecision.Pick] unless every APK is a
 * companion app (or the release has no APK at all), so "update all" never has to
 * stop on a module.
 *
 * Inputs, in increasing order of cost:
 *  - file-name traits ([VariantTraits]) — always available;
 *  - P2 probe results ([ApkMeta]) — only for UNKNOWN / ambiguous / SUSPECT;
 *  - device profile ([DeviceProfile]) — vendor OS and LSPosed API from P1.
 *
 * A non-null [VariantSignature] means the module is TRUSTED: only the remembered
 * semantic variant is mapped, the name layer is never re-scored and probes are
 * never read.
 */
class VariantResolver(private val device: DeviceProfile) {

    private val deviceAndroid: Int? = androidVersionFromSdk(device.sdkInt)

    /**
     * Scores every APK of [release] and marks the ones that must not be offered
     * for direct installation (companion apps shipped in the same release).
     */
    fun candidates(
        release: Release,
        probes: Map<String, ApkMeta> = emptyMap(),
        allowProbes: Boolean = true,
        expectedPackage: String? = null,
        installed: InstalledVariantHint? = null,
    ): List<ScoredAsset> {
        val assets = release.apkAssets
        val multiVariant = assets.size > 1
        return assets.map { asset ->
            val traits = parseTraits(asset.name)
            val meta = if (allowProbes) probes[asset.name] else null
            val blocked = blockReason(asset.name, meta, multiVariant, expectedPackage)
            val debug = meta?.debuggable ?: traits.debugName
            ScoredAsset(
                asset = asset,
                traits = traits,
                score = if (blocked != null) 0 else score(traits, meta),
                recommended = false,
                blocked = blocked,
                warning = warning(traits, meta),
                debug = debug,
                meta = meta,
                installedFit = installedFit(traits, meta, installed),
            )
        }
    }

    /**
     * The same ordering [resolve] uses: installable candidates first, best fit
     * ahead of the rest, then the folded ones. UI lists and probe targets share
     * it so a row never disagrees with the default above it.
     */
    fun ranked(candidates: List<ScoredAsset>): List<ScoredAsset> {
        val eligible = candidates.filter { it.blocked == null }.sortedWith(ranking())
        val blocked = candidates.filter { it.blocked != null }
            .sortedWith(compareBy<ScoredAsset> { it.blocked?.kind?.ordinal ?: 0 }.thenBy { it.asset.name })
        return eligible + blocked
    }

    /**
     * @param signature non-null means TRUSTED: map the remembered signature only;
     *   do not re-score and never consult [probes]. No match falls back to the
     *   name layer (the author may have renamed the variant).
     * @param probes optional P2 probe results, keyed by asset name.
     * @param expectedPackage repository package name, used to spot companion
     *   apps shipped in the same release.
     */
    fun resolve(
        release: Release,
        signature: VariantSignature? = null,
        probes: Map<String, ApkMeta> = emptyMap(),
        allowProbes: Boolean = signature == null,
        expectedPackage: String? = null,
        installed: InstalledVariantHint? = null,
    ): VariantDecision {
        if (release.apkAssets.isEmpty()) {
            return VariantDecision.NotApplicable(Reason(Reason.Kind.NO_APK))
        }
        val scored = candidates(release, probes, allowProbes, expectedPackage, installed)
        val eligible = scored.filter { it.blocked == null }

        if (signature != null) {
            val matched = eligible.filter { signature.matches(it.traits) }
            if (matched.size == 1) {
                return VariantDecision.Pick(
                    asset = matched[0].asset,
                    reasons = listOf(Reason(Reason.Kind.REMEMBERED)) + matchReasons(matched[0]),
                    confidence = VariantDecision.Confidence.HIGH,
                )
            }
            if (matched.size > 1) {
                // The remembered signature still fits several assets (a seeded
                // signature may only know the ABI). Rank them as usual and keep
                // the best one as the default instead of stopping to ask.
                val top = matched.sortedWith(ranking()).first()
                return VariantDecision.Pick(
                    asset = top.asset,
                    reasons = listOf(Reason(Reason.Kind.REMEMBERED)) + matchReasons(top),
                    confidence = VariantDecision.Confidence.DEFAULT,
                )
            }
            // No match: fall through to the name layer. Still no probes.
        }

        if (eligible.isEmpty()) {
            // Only hard reasons get here (see [blockReason]): every APK of the
            // release is a companion app, not the module.
            val reason = scored.mapNotNull { it.blocked }.firstOrNull()
                ?: Reason(Reason.Kind.NO_APK)
            return VariantDecision.NotApplicable(reason)
        }

        // A probe that names the installed package identifies the update
        // unambiguously, whatever the file names say (releases that ship several
        // different modules/apps at once). Two candidates can share the package
        // (same module, different build), so only a unique match decides here.
        val installedCopy = eligible.filter { it.installedFit >= INSTALLED_PACKAGE_MATCH }
        if (installedCopy.size == 1) {
            return VariantDecision.Pick(
                asset = installedCopy.single().asset,
                reasons = matchReasons(installedCopy.single()),
                confidence = VariantDecision.Confidence.HIGH,
            )
        }

        // Debug builds are only a penalty. A release whose only non-debug
        // candidate is unique does not need to ask the user.
        val nonDebug = eligible.filter { !it.debug }
        if (nonDebug.size == 1) return pick(nonDebug.single())
        if (eligible.size == 1) return pick(eligible.single())

        val ranked = eligible.sortedWith(ranking())
        val top = ranked[0]
        val second = ranked[1]
        if (isDecisive(top, second)) return pick(top)

        // Nothing about the device tells the remaining candidates apart (edition,
        // flavour, or names that only differ in the author's build scheme). Asking
        // used to be the only option, which made "update all" skip those modules;
        // the ranking now ends in a stable preference ladder (base build, then
        // most installed, then name) and the result is a real default that the
        // resource picker can still override.
        return VariantDecision.Pick(
            asset = top.asset,
            reasons = matchReasons(top),
            confidence = VariantDecision.Confidence.DEFAULT,
        )
    }

    /**
     * TRUSTED modules skip probing and re-ranking; everything else goes through
     * the normal name/probe path. NOT_APPLICABLE is re-checked on the name layer
     * when a new release arrives.
     */
    fun resolveForUpdate(
        state: ModuleVariantState,
        release: Release,
        probes: Map<String, ApkMeta> = emptyMap(),
        expectedPackage: String? = null,
        installed: InstalledVariantHint? = null,
    ): VariantDecision = if (state.isTrusted) {
        resolve(
            release,
            state.signature,
            allowProbes = false,
            expectedPackage = expectedPackage,
            installed = installed,
        )
    } else {
        resolve(release, probes = probes, expectedPackage = expectedPackage, installed = installed)
    }

    /** Human-readable match labels, e.g. `["arm64-v8a", "libxposed API 102"]`. */
    fun matchLabels(traits: VariantTraits, meta: ApkMeta? = null): List<String> {
        val labels = mutableListOf<String>()
        val abis = meta?.abis?.takeIf { it.isNotEmpty() } ?: traits.abis
        abis.firstOrNull { it in device.abis }?.let { labels += it }
        if (labels.isEmpty() && (meta?.universal == true || traits.universal) && abis.isEmpty()) {
            labels += "universal"
        }
        device.vendorOs?.let { vendor ->
            traits.vendor?.takeIf {
                it.first == vendor.family && (vendor.major == null || it.second == null || it.second == vendor.major)
            }?.let { labels += VendorOs(it.first, it.second).label() }
        }
        if (traits.android != null && traits.android == deviceAndroid) {
            labels += "Android ${traits.android}"
        }
        val api = apiRequirement(meta, traits)
        when {
            api != null && apiSupported(api) -> labels += "libxposed API $api"
            api == null && traits.modernWord -> labels += "libxposed"
        }
        val minSdk = meta?.minSdk ?: traits.minSdkToken
        if (minSdk != null && minSdk <= device.sdkInt) labels += "minSdk $minSdk"
        return labels
    }

    private fun pick(candidate: ScoredAsset): VariantDecision.Pick = VariantDecision.Pick(
        asset = candidate.asset,
        reasons = buildList {
            addAll(matchReasons(candidate))
            if (candidate.debug) add(Reason(Reason.Kind.DEBUG_BUILD))
        },
        confidence = VariantDecision.Confidence.HIGH,
    )

    private fun matchReasons(candidate: ScoredAsset): List<Reason> {
        val labels = matchLabels(candidate.traits, candidate.meta)
        return if (labels.isEmpty()) {
            emptyList()
        } else {
            listOf(Reason(Reason.Kind.MATCHES_DEVICE, listOf(labels.joinToString(", "))))
        }
    }

    /**
     * Only what the APK proves about itself (a foreign package without any Xposed
     * declaration) may block a candidate, plus a companion-app looking name that
     * no probe has cleared yet. Everything else - a `minsdk35` token or the
     * manifest minSdk, an ABI token, the libxposed API number - is advisory: it
     * ranks and warns, and the system installer decides later (#47, #65, #69).
     *
     * The name layer is still what keeps a companion app out of the picker when
     * nothing has been probed yet, so a bundled name blocks until the archive is
     * read; a probe that shows the module's own package or declaration wins.
     */
    private fun blockReason(
        assetName: String,
        meta: ApkMeta?,
        multiVariant: Boolean,
        expectedPackage: String?,
    ): Reason? {
        if (meta?.packageName != null && expectedPackage != null &&
            meta.packageName != expectedPackage && !meta.hasXposedDeclaration
        ) {
            return Reason(
                Reason.Kind.PACKAGE_MISMATCH,
                listOf(meta.packageName, expectedPackage),
            )
        }
        // Bundled apps only exist inside multi-APK releases; a single APK named
        // "Pro" is still the module itself.
        if (multiVariant && isBundledName(assetName) && !identifiesAsModule(meta, expectedPackage)) {
            return Reason(Reason.Kind.BUNDLED_APP)
        }
        return null
    }

    /** The archive says it is the module, so a companion-app looking name is moot. */
    private fun identifiesAsModule(meta: ApkMeta?, expectedPackage: String?): Boolean {
        if (meta == null) return false
        return meta.hasXposedDeclaration ||
            (expectedPackage != null && meta.packageName == expectedPackage)
    }

    private fun score(traits: VariantTraits, meta: ApkMeta?): Int {
        var score = 0
        val abis = meta?.abis?.takeIf { it.isNotEmpty() } ?: traits.abis
        val abiMatch = abis.firstOrNull { it in device.abis }
        when {
            abiMatch != null -> score += 25
            (meta?.universal == true || traits.universal) -> score += 10
        }
        val minSdk = meta?.minSdk ?: traits.minSdkToken
        if (minSdk != null && minSdk <= device.sdkInt) score += 20 + minSdk / 10
        if (traits.android != null && traits.android == deviceAndroid) score += 30
        device.vendorOs?.let { vendor ->
            val candidate = traits.vendor
            if (candidate != null && candidate.first == vendor.family &&
                (vendor.major == null || candidate.second == null || candidate.second == vendor.major)
            ) {
                score += if (candidate.second != null && candidate.second == vendor.major) 40 else 20
            }
        }
        val requiredApi = apiRequirement(meta, traits)
        if ((requiredApi != null && apiSupported(requiredApi)) ||
            (requiredApi == null && traits.modernWord)
        ) {
            score += 20
        }
        val debug = meta?.debuggable ?: traits.debugName
        if (debug) score -= 20
        // Downloads are deliberately not part of this score: they are a
        // preference tie-break in [ranking], so they can never outrank the
        // author's plain build or a device signal.
        return score.coerceAtLeast(0)
    }

    /**
     * Strong, device-specific signals. Confidence comes from "top matches more of
     * these than the runner-up", never from the download prior alone.
     */
    private fun strongSignals(candidate: ScoredAsset): Set<String> {
        val traits = candidate.traits
        val meta = candidate.meta
        val signals = linkedSetOf<String>()
        val abis = meta?.abis?.takeIf { it.isNotEmpty() } ?: traits.abis
        val matchedAbi = abis.firstOrNull { it in device.abis }
        if (matchedAbi != null) signals += "abi:$matchedAbi"
        if (matchedAbi == null && abis.isEmpty() &&
            (meta?.universal == true || traits.universal)
        ) {
            signals += "universal"
        }
        val minSdk = meta?.minSdk ?: traits.minSdkToken
        if (minSdk != null && minSdk <= device.sdkInt) signals += "minsdk:$minSdk"
        if (traits.android != null && traits.android == deviceAndroid) {
            signals += "android:${traits.android}"
        }
        device.vendorOs?.let { vendor ->
            val candidate = traits.vendor
            if (candidate != null && candidate.first == vendor.family &&
                (vendor.major == null || candidate.second == null || candidate.second == vendor.major)
            ) {
                signals += "os:${VendorOs(candidate.first, candidate.second).token}"
            }
        }
        if (device.lsposedApi != null) {
            val requiredApi = apiRequirement(meta, traits)
            when {
                requiredApi != null && requiredApi <= device.lsposedApi ->
                    signals += "api:libxposed"

                requiredApi == null && traits.modernWord -> signals += "api:libxposed"
            }
        }
        return signals
    }

    private fun isDecisive(top: ScoredAsset, second: ScoredAsset): Boolean {
        if (fitTier(top) > fitTier(second)) return true
        val topSignals = strongSignals(top)
        val secondSignals = strongSignals(second)
        if (topSignals.size > secondSignals.size && topSignals.containsAll(secondSignals)) return true
        if (topSignals == secondSignals) return false
        // minSdk split: prefer the largest compatible build. Everything else
        // about the two candidates must match.
        val topBase = topSignals.filterNot { it.startsWith(MINSDK) }.toSet()
        val secondBase = secondSignals.filterNot { it.startsWith(MINSDK) }.toSet()
        val topMin = topSignals.firstOrNull { it.startsWith(MINSDK) }?.substringAfter(':')?.toIntOrNull()
        val secondMin =
            secondSignals.firstOrNull { it.startsWith(MINSDK) }?.substringAfter(':')?.toIntOrNull()
        return topBase == secondBase && topMin != null && secondMin != null && topMin > secondMin
    }

    /** 2 = native ABI match, 1 = universal only, 0 = no ABI signal in the name. */
    private fun fitTier(candidate: ScoredAsset): Int {
        val abis = candidate.meta?.abis?.takeIf { it.isNotEmpty() } ?: candidate.traits.abis
        val index = device.abis.indexOfFirst { it in abis }
        return when {
            index >= 0 -> 100 - index
            (candidate.meta?.universal == true || candidate.traits.universal) && abis.isEmpty() -> 1
            else -> 0
        }
    }

    /**
     * Device ABI fit comes first: the download prior is a tie-break, never a
     * reason to hand an arm64 build to an x86_64-first device (or vice versa).
     * Then the score, the largest compatible minSdk variant, the author's plain
     * build, and finally "what most users of this module actually installed".
     *
     * The last two only ever order candidates that the device cannot tell apart
     * (edition/flavour splits such as `full`/`lite`, `panel`/`tile`, or names
     * that differ only in the author's build scheme), so the result is the
     * community's default rather than an alphabetical accident. The file name is
     * the last resort, which keeps the order deterministic for brand-new releases
     * whose assets all have zero downloads.
     */
    private fun ranking(): Comparator<ScoredAsset> =
        compareByDescending<ScoredAsset> { fitTier(it) }
            .thenByDescending { it.score }
            .thenByDescending { it.installedFit }
            .thenByDescending { minSdkTier(it) }
            .thenByDescending { osTier(it) }
            .thenByDescending { apiTier(it) }
            .thenByDescending { plainBuildTier(it) }
            .thenByDescending { it.asset.downloadCount }
            .thenBy { it.asset.name }

    /**
     * 1 for the author's undecorated build (`app-release.apk`, `KnoxPatch.apk`),
     * 0 once the name carries an edition or flavour token (`-lite`, `-full`,
     * `-log`, `-noui`, ...). A `KnoxPatch-sep-lite.apk` next to `KnoxPatch.apk`
     * must never win just because `s` sorts behind `K`.
     */
    private fun plainBuildTier(candidate: ScoredAsset): Int =
        if (candidate.traits.edition.isEmpty() && candidate.traits.flavors.isEmpty()) 1 else 0

    /**
     * Observable overlap with the installed copy. Weights only order candidates
     * that the device cannot tell apart, except for the package identity, which
     * is proof that the archive is the app the user already runs.
     */
    private fun installedFit(
        traits: VariantTraits,
        meta: ApkMeta?,
        installed: InstalledVariantHint?,
    ): Int {
        if (installed == null) return 0
        var fit = 0
        if (meta?.packageName != null && meta.packageName == installed.packageName) {
            fit += INSTALLED_PACKAGE_MATCH
        }
        val abis = meta?.abis?.takeIf { it.isNotEmpty() } ?: traits.abis
        if (installed.abi != null && installed.abi in abis) fit += 4
        if (meta != null && candidatesGenerationsDiffer(meta, traits, installed)) {
            // Only a probed archive knows its declaration, so an unprobed name
            // never wins or loses points for it.
            fit += 4
        }
        if (installed.minSdk != null && installed.minSdk == meta?.minSdk) fit += 2
        if (installed.targetSdk != null && installed.targetSdk == meta?.targetSdk) fit += 1
        val debug = meta?.debuggable ?: traits.debugName
        if (debug == installed.debuggable) fit += 2
        return fit
    }

    private fun candidatesGenerationsDiffer(
        meta: ApkMeta,
        traits: VariantTraits,
        installed: InstalledVariantHint,
    ): Boolean {
        val modern = meta.targetApiVersion != null || meta.minApiVersion != null ||
            traits.libxposedApi != null || traits.modernWord
        return modern == installed.libXposed
    }

    /**
     * The newest OS / Android version a candidate advertises. With a vendor
     * profile this is a real device match and already scored; without one it is
     * the better guess than the alphabetically first name (`OS2_...` used to win
     * over `OS3_...` purely because `2` sorts before `3`).
     */
    private fun osTier(candidate: ScoredAsset): Int =
        candidate.traits.vendor?.second ?: candidate.traits.android ?: 0

    /** The newest libxposed API number, mirroring the "modern wins" decision. */
    private fun apiTier(candidate: ScoredAsset): Int =
        apiRequirement(candidate.meta, candidate.traits) ?: 0

    private fun minSdkTier(candidate: ScoredAsset): Int =
        candidate.meta?.minSdk ?: candidate.traits.minSdkToken ?: -1

    /**
     * The libxposed API this candidate is expected to be loaded with. The
     * loaders pick their strategy from `targetApiVersion` plus the legacy
     * entry; `minApiVersion` is only the author's floor (see
     * [libXposedApiRequirement]).
     */
    private fun apiRequirement(meta: ApkMeta?, traits: VariantTraits): Int? =
        libXposedApiRequirement(
            targetApiVersion = meta?.targetApiVersion,
            hasLegacyInit = meta?.hasLegacyInit == true,
            minApiVersion = meta?.minApiVersion ?: traits.libxposedApi,
        )

    /**
     * The single advisory line a picker row can show. A build that probably does
     * not fit this device (a minsdk above the device, ABI) is the most useful
     * hint, then the libxposed API number (#47).
     */
    private fun warning(traits: VariantTraits, meta: ApkMeta?): Reason? =
        minSdkWarning(traits, meta) ?: abiWarning(meta) ?: apiWarning(meta, traits)

    /**
     * A minSdk above the device - read from the manifest or guessed from a
     * `minsdkNN` token - is advisory like the ABI and the API number: the system
     * installer is the only thing that can refuse the package, and the user
     * decides whether to try. Nothing here may hide or grey out a candidate
     * (#65, #69).
     */
    private fun minSdkWarning(traits: VariantTraits, meta: ApkMeta?): Reason? {
        val required = meta?.minSdk ?: traits.minSdkToken ?: return null
        return if (required > device.sdkInt) {
            Reason(
                Reason.Kind.MIN_SDK_TOO_HIGH,
                listOf(required.toString(), device.sdkInt.toString()),
            )
        } else {
            null
        }
    }

    /**
     * A foreign `lib/<abi>/` directory is advisory too: installing never fails on
     * one, a module may not load its native part at all, and an ARM translation
     * layer can still run it.
     */
    private fun abiWarning(meta: ApkMeta?): Reason? {
        if (meta == null || meta.abis.isEmpty() || meta.universal) return null
        if (meta.abis.any { it in device.abis }) return null
        return Reason(Reason.Kind.ABI_INCOMPATIBLE, listOf(meta.abis.joinToString(", ")))
    }

    /**
     * A newer API than the framework reports is advisory: LSPosed/Vector load
     * the module anyway, so it only ranks lower and warns (#47).
     */
    private fun apiWarning(meta: ApkMeta?, traits: VariantTraits): Reason? {
        val supported = device.lsposedApi ?: return null
        val required = apiRequirement(meta, traits) ?: return null
        return if (required > supported) {
            Reason(Reason.Kind.API_TOO_NEW, listOf(required.toString()))
        } else {
            null
        }
    }

    private fun apiSupported(required: Int?): Boolean {
        if (required == null) return true
        val supported = device.lsposedApi ?: return true
        return required <= supported
    }

    private fun isBundledName(assetName: String): Boolean {
        val name = assetName.lowercase()
        return BUNDLED_SUBSTRINGS.any { name.contains(it) } ||
            BUNDLED_SPECIAL.any { name.contains(it) }
    }

    private companion object {
        /** [installedFit] weight of a probe that names the installed package. */
        const val INSTALLED_PACKAGE_MATCH = 16

        const val MINSDK = "minsdk:"
        val BUNDLED_SUBSTRINGS = listOf(
            "wearos",
            "wear_app",
            "wearapp",
            "bridge",
            "provider",
            "client",
            "trigger",
            "demo",
        )
        // `pro` alone is a legitimate edition variant; only the known standalone
        // companion app gets folded.
        val BUNDLED_SPECIAL = listOf("luapro", "xlua.pro")
    }
}
