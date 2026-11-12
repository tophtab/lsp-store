package io.github.tophtab.lspstore.domain.model

import io.github.tophtab.lspstore.domain.usecase.UpdateChecker
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantSignature
import kotlinx.serialization.Serializable

/** Immutable provenance of the APK chosen for this attempt. */
@Serializable
data class ResolvedInstallTarget(
    val releaseTag: String,
    val releaseVersion: ModuleVersion?,
    val publishedAt: String?,
    val asset: ReleaseAsset,
    val signature: VariantSignature,
    val explicitSelection: Boolean = false,
    val apkPackageName: String? = null,
    val apkVersionCode: Long? = null,
    val apkVersionName: String? = null,
) {
    companion object {
        fun of(release: Release, asset: ReleaseAsset, explicit: Boolean = false) = ResolvedInstallTarget(
            release.tagName, release.version, release.publishedAt?.toString(), asset,
            VariantSignature.ofAsset(asset.name), explicit,
        )
    }
}

/** Local PackageManager evidence; never imported as proof from another device. */
@Serializable
data class ConfirmedInstallRecord(
    val target: ResolvedInstallTarget,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val lastUpdateTime: Long,
) {
    fun matches(installed: InstalledModule?): Boolean = installed != null &&
        installed.packageName == packageName && installed.versionCode == versionCode &&
        installed.versionName == versionName && installed.lastUpdateTime == lastUpdateTime &&
        lastUpdateTime > 0L
}

/** Selected APK versions take precedence over release labels. */
enum class InstallAction { INSTALL, UPDATE, REINSTALL, DOWNGRADE }

fun installAction(target: ResolvedInstallTarget?, installed: InstalledModule?, state: ModuleVariantState?): InstallAction {
    if (installed == null) return InstallAction.INSTALL
    val code = target?.apkVersionCode
    if (code != null) return when {
        code > installed.versionCode -> InstallAction.UPDATE
        code < installed.versionCode -> InstallAction.DOWNGRADE
        else -> {
            val record = state?.confirmedInstall?.takeIf { it.matches(installed) }
            val version = target.releaseVersion
            val published = target.publishedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val update = if (record != null && version != null) {
                UpdateChecker.isUpdateAvailable(version, installed, target.publishedAt, record)
            } else published != null && installed.lastUpdateTime > 0 && published > installed.lastUpdateTime
            if (update) InstallAction.UPDATE else InstallAction.REINSTALL
        }
    }
    val record = state?.confirmedInstall?.takeIf { it.matches(installed) }
    val reference = record?.target?.releaseVersion?.versionCode ?: installed.versionCode
    val selected = target?.releaseVersion?.versionCode ?: return InstallAction.REINSTALL
    return when {
        selected > reference -> InstallAction.UPDATE
        selected < reference -> InstallAction.DOWNGRADE
        else -> InstallAction.REINSTALL
    }
}
