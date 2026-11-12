package io.github.tophtab.lspstore.data.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.tophtab.lspstore.domain.model.InstalledModule

@Suppress("DEPRECATION")
internal class AndroidInstalledPackageSource(private val context: Context) : InstalledPackageSource {
    override val environmentKey: String
        get() = "${Build.VERSION.SDK_INT}:${context.resources.configuration.locales.toLanguageTags()}"

    // Enumerate without metadata/providers: one large Binder reply with those
    // flags can fail the entire list. Expensive fields stay in per-package reads.
    override fun allPackages(): List<InstalledPackageCandidate> =
        context.packageManager.getInstalledPackages(0).map(::candidate)

    override fun packageNamed(packageName: String): InstalledPackageCandidate? = try {
        candidate(context.packageManager.getPackageInfo(packageName, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun candidate(pkg: PackageInfo): InstalledPackageCandidate {
        val app = pkg.applicationInfo
        val fallback = InstalledModule(
            packageName = pkg.packageName,
            versionCode = pkg.longVersionCode,
            versionName = pkg.versionName,
            label = pkg.packageName,
            isXposedModule = false,
            description = null,
            minSdk = app?.minSdkVersion ?: 0,
            targetSdk = app?.targetSdkVersion ?: 0,
            lastUpdateTime = pkg.lastUpdateTime,
        )
        return InstalledPackageCandidate(
            packageName = pkg.packageName,
            fingerprint = PackageFingerprint(
                versionCode = pkg.longVersionCode,
                versionName = pkg.versionName,
                lastUpdateTime = pkg.lastUpdateTime,
                sourceDir = app?.sourceDir,
                splitSourceDirs = app?.splitSourceDirs?.toList().orEmpty(),
                environment = environmentKey,
                minSdk = app?.minSdkVersion ?: 0,
                targetSdk = app?.targetSdkVersion ?: 0,
                flags = app?.flags ?: 0,
                nativeLibraryDir = app?.nativeLibraryDir,
            ),
            fallback = fallback,
            read = {
                if (app == null) InstalledPackageRead(fallback, reusable = false)
                else read(pkg, app)
            },
        )
    }

    private fun read(pkg: PackageInfo, app: ApplicationInfo): InstalledPackageRead {
        val pm = context.packageManager
        val extras = runCatching {
            pm.getPackageInfo(pkg.packageName, PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS)
        }.getOrNull()
        val meta = extras?.applicationInfo?.metaData
        val legacy = meta != null && (meta.containsKey("xposedmodule") || meta.containsKey("xposedminversion"))
        val provider = extras?.providers?.any {
            it.name == "io.github.libxposed.service.XposedProvider"
        } == true
        val marker = if (legacy || provider) false else ModuleMarker.readModernModule(app.sourceDir, app.splitSourceDirs)
        val label = runCatching { pm.getApplicationLabel(app).toString() }
        val description = runCatching { meta?.getString("xposeddescription") }
        val module = ScannedPackage(
            packageName = pkg.packageName,
            label = label.getOrDefault(pkg.packageName),
            apiLevel = Build.VERSION.SDK_INT,
            longVersionCode = pkg.longVersionCode,
            legacyVersionCode = pkg.versionCode,
            versionName = pkg.versionName,
            hasLegacyXposedMeta = legacy,
            hasLibXposedProvider = provider,
            hasModernMarker = marker == true,
            description = description.getOrNull(),
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            reflectedPrimaryCpuAbi = runCatching {
                app.javaClass.getField("primaryCpuAbi").get(app) as? String
            }.getOrNull(),
            nativeLibraryDir = app.nativeLibraryDir,
            debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            lastUpdateTime = pkg.lastUpdateTime,
        ).toInstalledModule()
        return InstalledPackageRead(
            module,
            reusable = extras?.applicationInfo != null && marker != null && label.isSuccess && description.isSuccess,
        )
    }
}
