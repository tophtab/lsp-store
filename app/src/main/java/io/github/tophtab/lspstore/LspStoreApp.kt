package io.github.tophtab.lspstore

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import io.github.tophtab.lspstore.data.device.LauncherIcon
import io.github.tophtab.lspstore.data.install.PackageEventReceiver
import io.github.tophtab.lspstore.data.update.UpdateScheduler
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.variant.ModuleVariantState
import io.github.tophtab.lspstore.domain.variant.VariantSignature

class LspStoreApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /** Outlives any single screen/receiver; used for install bookkeeping. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ContextCompat.registerReceiver(
            this,
            PackageEventReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        applyMirror()
        applyLauncherIcon()
        syncUpdateSchedule()
        reconcilePendingInstalls()
        seedVariantStates()
        dropNotApplicableVerdicts()
        refreshDeviceProfile()
    }

    /** Coil loader with SVG support and a browser UA, so CDN badges render. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", BROWSER_UA)
                                .header(
                                    "Accept",
                                    "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                                )
                                .build(),
                        )
                    }
                    .build()
            }
            .build()

    /** P1 vendor OS / LSPosed API; async so startup never waits on getprop. */
    private fun refreshDeviceProfile() {
        appScope.launch {
            runCatching { container.deviceProfileProvider.refresh() }
        }
    }

    private fun reconcilePendingInstalls() {
        appScope.launch {
            // Records delegated installs that landed while the process was dead
            // (system installer, package broadcast missed in the background).
            runCatching { container.reconcilePendingInstalls() }
        }
    }

    /**
     * First-launch variant seed: already installed modules become TRUSTED from
     * PackageManager alone, so existing users never need a network probe. Runs
     * once (idempotent) and also performs the v1 asset-name migration.
     */
    private fun seedVariantStates() {
        val prefs = getSharedPreferences(VARIANT_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VARIANT_SEED_DONE, false)) return
        appScope.launch {
            runCatching {
                container.libraryRepository.migratePreferredVariants()
                val installed = container.installedRepository.getInstalledModules()
                val states = container.libraryRepository.variantStates.first()
                val links = container.libraryRepository.links.first()
                val now = System.currentTimeMillis()
                installed.filter { it.isXposedModule }.forEach { module ->
                    val repoPackage = links[module.packageName] ?: module.packageName
                    if (repoPackage in states) return@forEach
                    container.libraryRepository.seedVariantState(
                        repoPackage,
                        ModuleVariantState(
                            status = ModuleVariantState.Status.TRUSTED,
                            signature = VariantSignature.of(module),
                            updatedAt = now,
                        ),
                    )
                }
                prefs.edit().putBoolean(KEY_VARIANT_SEED_DONE, true).apply()
            }
        }
    }

    /**
     * Forget every legacy NOT_APPLICABLE verdict, including the minSdk and ABI
     * verdicts left behind after the earlier API-only cleanup (#65, #69).
     */
    private fun dropNotApplicableVerdicts() {
        val prefs = getSharedPreferences(VARIANT_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VERDICT_CLEANUP_DONE, false)) return
        appScope.launch {
            runCatching {
                container.libraryRepository.dropNotApplicableVerdicts()
                prefs.edit().putBoolean(KEY_VERDICT_CLEANUP_DONE, true).apply()
            }
        }
    }

    /**
     * Re-applies the stored launcher visibility. Component state lives in
     * PackageManager, not in the app's data, so a package update or a restored
     * backup can leave the alias out of sync with the setting.
     */
    private fun applyLauncherIcon() {
        appScope.launch {
            runCatching {
                val hidden = container.settingsRepository.hideLauncherIcon.first()
                LauncherIcon.setHidden(this@LspStoreApp, hidden)
            }
        }
    }

    private fun applyMirror() {
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsRepository.preferredMirror.collect { host ->
                container.mirrorState.preferred = host
            }
        }
    }

    /**
     * Re-enqueues the periodic check with the stored settings. `reset = false`
     * keeps a schedule WorkManager already holds, so a restart does not postpone
     * the next run; only a settings change restarts the cycle.
     */
    private fun syncUpdateSchedule() {
        appScope.launch {
            UpdateScheduler.sync(
                context = this@LspStoreApp,
                enabled = container.settingsRepository.backgroundCheck.first(),
                intervalHours = container.settingsRepository.checkIntervalHours.first(),
                wifiOnly = container.settingsRepository.wifiOnly.first(),
                reset = false,
            )
        }
    }


    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36 LSPStore/1.0"
        const val VARIANT_PREFS = "variant_state"
        const val KEY_VARIANT_SEED_DONE = "variant_seed_done"
        const val KEY_VERDICT_CLEANUP_DONE = "not_applicable_verdict_cleanup_done"
    }
}
