package io.github.tophtab.lspstore

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
        applyMirror()
        reconcilePendingInstalls()
        seedVariantStates()
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
                    container.libraryRepository.setVariantState(
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

    private fun applyMirror() {
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsRepository.preferredMirror.collect { host ->
                container.mirrorState.preferred = host
            }
        }
    }


    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36 LSPStore/1.0"
        const val VARIANT_PREFS = "variant_state"
        const val KEY_VARIANT_SEED_DONE = "variant_seed_done"
    }
}
