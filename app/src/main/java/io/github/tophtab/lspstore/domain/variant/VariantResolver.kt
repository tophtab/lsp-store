package io.github.tophtab.lspstore.domain.variant

import kotlin.math.log10
import kotlin.math.roundToInt
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset

/**
 * One name-layer resolver for every install path (detail page, installed page,
 * batch update, background worker). Pure Kotlin so the scoring and the
 * "never silently pick the first asset" guarantees are unit tested.
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
     * for direct installation (bundled apps, impossible minSdk, ...).
     */
    fun candidates(
        release: Release,
        probes: Map<String, ApkMeta> = emptyMap(),
        allowProbes: Boolean = true,
        expectedPackage: String? = null,
    ): List<ScoredAsset> {
        val assets = release.apkAssets
        val maxDownloads = assets.maxOfOrNull { it.downloadCount } ?: 0
        val multiVariant = assets.size > 1
        return assets.map { asset ->
            val traits = parseTraits(asset.name)
            val meta = if (allowProbes) probes[asset.name] else null
            val blocked = blockReason(asset.name, meta, multiVariant, expectedPackage)
            val debug = meta?.debuggable ?: traits.debugName
            ScoredAsset(
                asset = asset,
                traits = traits,
                score = if (blocked != null) 0 else score(asset, traits, meta, maxDownloads),
                recommended = false,
                blocked = blocked,
                warning = warning(traits, meta),
                debug = debug,
                meta = meta,
            )
        }
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
    ): VariantDecision {
        if (release.apkAssets.isEmpty()) {
            return VariantDecision.NotApplicable(Reason(Reason.Kind.NO_APK))
        }
        val scored = candidates(release, probes, allowProbes, expectedPackage)
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
                val ranked = matched.sortedWith(ranking())
                return choose(
                    scored = scored,
                    preferredNames = ranked.map { it.asset.name }.toSet(),
                    recommendedName = ranked.first().asset.name,
                )
            }
            // No match: fall through to the name layer. Still no probes.
        }

        if (eligible.isEmpty()) {
            // Only hard reasons can get here (see [blockReason]); the order is
            // "this build cannot run here" before "this APK is not the module".
            val reason = scored.mapNotNull { it.blocked }
                .firstOrNull { it.kind == Reason.Kind.MIN_SDK_TOO_HIGH }
                ?: scored.mapNotNull { it.blocked }.firstOrNull()
                ?: Reason(Reason.Kind.NO_APK)
            return VariantDecision.NotApplicable(reason)
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

        val recommended = if (top.score > second.score) top.asset.name else null
        return choose(scored, preferredNames = emptySet(), recommendedName = recommended)
    }

    /**
     * TRUSTED modules skip probing and re-ranking; everything else goes through
     * the normal name/probe path. Nothing about the device is remembered, so a
     * new release is judged on its own.
     */
    fun resolveForUpdate(
        state: ModuleVariantState,
        release: Release,
        probes: Map<String, ApkMeta> = emptyMap(),
        expectedPackage: String? = null,
    ): VariantDecision = if (state.isTrusted) {
        resolve(release, state.signature, allowProbes = false, expectedPackage = expectedPackage)
    } else {
        resolve(release, probes = probes, expectedPackage = expectedPackage)
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

    private fun choose(
        scored: List<ScoredAsset>,
        preferredNames: Set<String>,
        recommendedName: String?,
    ): VariantDecision.Choose {
        val eligible = scored.filter { it.blocked == null }
            .sortedWith(
                compareByDescending<ScoredAsset> { it.asset.name in preferredNames }
                    .then(ranking()),
            )
        val blocked = scored.filter { it.blocked != null }
            .sortedWith(compareBy<ScoredAsset> { it.blocked?.kind?.ordinal ?: 0 }.thenBy { it.asset.name })
        return VariantDecision.Choose(
            candidates = (eligible + blocked).map {
                it.copy(recommended = it.asset.name == recommendedName)
            },
        )
    }

    /**
     * Only what the platform enforces (the manifest minSdk) or what the APK
     * proves about itself (a foreign package without any Xposed declaration) may
     * block a candidate. Everything inferred from a file name - an `minsdk35`
     * token, an ABI token, the libxposed API number - is advisory: it ranks and
     * warns, and the APK itself decides later (#47, #65, #69).
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
        val minSdk = meta?.minSdk
        if (minSdk != null && minSdk > device.sdkInt) {
            return Reason(
                Reason.Kind.MIN_SDK_TOO_HIGH,
                listOf(minSdk.toString(), device.sdkInt.toString()),
            )
        }
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

    private fun score(
        asset: ReleaseAsset,
        traits: VariantTraits,
        meta: ApkMeta?,
        maxDownloads: Int,
    ): Int {
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
        if (maxDownloads > 1) {
            // Weak prior only: enough to order a tie, never to decide alone.
            val ratio = log10(asset.downloadCount + 1.0) / log10(maxDownloads + 1.0)
            score += (ratio * 5.0).roundToInt()
        }
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
     * Then the score, the largest compatible minSdk variant, and a stable order.
     */
    private fun ranking(): Comparator<ScoredAsset> =
        compareByDescending<ScoredAsset> { fitTier(it) }
            .thenByDescending { it.score }
            .thenByDescending { minSdkTier(it) }
            .thenBy { it.asset.name }

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
     * not fit this device (a minsdk/ABI hinted only by the file name) is the most
     * useful hint, then the libxposed API number (#47).
     */
    private fun warning(traits: VariantTraits, meta: ApkMeta?): Reason? =
        nameMinSdkWarning(traits, meta) ?: abiWarning(meta) ?: apiWarning(meta, traits)

    /**
     * A `minsdkNN` token above the device is advisory: authors mislabel files,
     * and the manifest - read by the probe, enforced at install - is the truth.
     * Nothing here may hide the candidate (#65, #69).
     */
    private fun nameMinSdkWarning(traits: VariantTraits, meta: ApkMeta?): Reason? {
        if (meta?.minSdk != null) return null
        val token = traits.minSdkToken ?: return null
        return if (token > device.sdkInt) {
            Reason(
                Reason.Kind.MIN_SDK_TOO_HIGH,
                listOf(token.toString(), device.sdkInt.toString()),
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
