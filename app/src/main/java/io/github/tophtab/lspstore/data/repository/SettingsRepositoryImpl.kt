package io.github.tophtab.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.tophtab.lspstore.data.install.DownloadSlots
import io.github.tophtab.lspstore.domain.model.SortOrder
import io.github.tophtab.lspstore.domain.model.ThemeMode
import io.github.tophtab.lspstore.domain.model.TranslationProvider
import io.github.tophtab.lspstore.domain.model.TranslationSettings

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

interface SettingsRepository {
    val sortOrder: Flow<SortOrder>
    val rootInstall: Flow<Boolean>
    val themeMode: Flow<ThemeMode>
    val amoled: Flow<Boolean>
    val dynamicColor: Flow<Boolean>
    val onboardingComplete: Flow<Boolean>
    val preferredMirror: Flow<String>
    val translationSettings: Flow<TranslationSettings>

    /** APK transfers allowed to run at once, shared by batches and module pages. */
    val maxConcurrentDownloads: Flow<Int>

    /** Periodic background check; off until the user turns it on. */
    val backgroundCheck: Flow<Boolean>

    /** Hours between background checks; one of UpdateScheduler.INTERVAL_OPTIONS. */
    val checkIntervalHours: Flow<Int>

    /** Require an unmetered network for the background check. */
    val wifiOnly: Flow<Boolean>

    /** Silent root install of updates found by the background check. */
    val autoUpdateRoot: Flow<Boolean>

    /** Remove the launcher entry; off until the user turns it on. */
    val hideLauncherIcon: Flow<Boolean>

    /** Notification dedup state: update set already announced. Not user-facing. */
    val notifiedUpdateSignature: Flow<String>

    suspend fun setSortOrder(order: SortOrder)
    suspend fun setRootInstall(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAmoled(enabled: Boolean)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setOnboardingComplete(complete: Boolean)
    suspend fun setTranslationSettings(settings: TranslationSettings)
    suspend fun setPreferredMirror(host: String)
    suspend fun setMaxConcurrentDownloads(count: Int)
    suspend fun setBackgroundCheck(enabled: Boolean)
    suspend fun setCheckIntervalHours(hours: Int)
    suspend fun setWifiOnly(enabled: Boolean)
    suspend fun setAutoUpdateRoot(enabled: Boolean)
    suspend fun setHideLauncherIcon(hidden: Boolean)
    suspend fun setNotifiedUpdateSignature(signature: String)
}

class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {

    private object Keys {
        val SORT = stringPreferencesKey("sort_order")
        val ROOT_INSTALL = booleanPreferencesKey("root_install")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
        val TRANSLATION_AUTO = booleanPreferencesKey("translation_auto")
        val DEEPL_KEY = stringPreferencesKey("deepl_key")
        val LIBRE_URL = stringPreferencesKey("libre_url")
        val LIBRE_KEY = stringPreferencesKey("libre_key")
        val MICROSOFT_KEY = stringPreferencesKey("microsoft_key")
        val MICROSOFT_REGION = stringPreferencesKey("microsoft_region")
        val PREFERRED_MIRROR = stringPreferencesKey("preferred_mirror")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val BACKGROUND_CHECK = booleanPreferencesKey("background_check_enabled")
        val CHECK_INTERVAL_HOURS = intPreferencesKey("check_interval_hours")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val AUTO_UPDATE_ROOT = booleanPreferencesKey("auto_update_root")
        val HIDE_LAUNCHER_ICON = booleanPreferencesKey("hide_launcher_icon")
        val NOTIFIED_UPDATE_SIGNATURE = stringPreferencesKey("notified_update_signature")
    }

    override val sortOrder: Flow<SortOrder> = context.settingsDataStore.data
        .map { raw -> SortOrder.entries.firstOrNull { it.name == raw[Keys.SORT] } ?: SortOrder.UPDATED }

    override val rootInstall: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ROOT_INSTALL] ?: false }

    override val themeMode: Flow<ThemeMode> = context.settingsDataStore.data
        .map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM }

    override val amoled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AMOLED] ?: true }

    override val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: false }

    override val onboardingComplete: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ONBOARDING_DONE] ?: false }

    override val preferredMirror: Flow<String> = context.settingsDataStore.data
        .map { it[Keys.PREFERRED_MIRROR] ?: "" }

    override val maxConcurrentDownloads: Flow<Int> = context.settingsDataStore.data
        .map { prefs ->
            (prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: DownloadSlots.DEFAULT_LIMIT)
                .coerceIn(1, DownloadSlots.MAX_LIMIT)
        }

    override val backgroundCheck: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.BACKGROUND_CHECK] ?: false }

    override val checkIntervalHours: Flow<Int> = context.settingsDataStore.data
        .map { (it[Keys.CHECK_INTERVAL_HOURS] ?: 24).coerceIn(1, 168) }

    override val wifiOnly: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.WIFI_ONLY] ?: false }

    override val autoUpdateRoot: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AUTO_UPDATE_ROOT] ?: false }

    override val hideLauncherIcon: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.HIDE_LAUNCHER_ICON] ?: false }

    override val notifiedUpdateSignature: Flow<String> = context.settingsDataStore.data
        .map { it[Keys.NOTIFIED_UPDATE_SIGNATURE] ?: "" }

    override val translationSettings: Flow<TranslationSettings> = context.settingsDataStore.data.map { prefs ->
        TranslationSettings(
            provider = TranslationProvider.entries
                .firstOrNull { it.name == prefs[Keys.TRANSLATION_PROVIDER] }
                ?: TranslationProvider.GOOGLE,
            autoTranslate = prefs[Keys.TRANSLATION_AUTO] ?: false,
            deeplKey = prefs[Keys.DEEPL_KEY] ?: "",
            libreUrl = prefs[Keys.LIBRE_URL] ?: "",
            libreKey = prefs[Keys.LIBRE_KEY] ?: "",
            microsoftKey = prefs[Keys.MICROSOFT_KEY] ?: "",
            microsoftRegion = prefs[Keys.MICROSOFT_REGION] ?: "",
        )
    }

    override suspend fun setSortOrder(order: SortOrder) {
        context.settingsDataStore.edit { it[Keys.SORT] = order.name }
    }

    override suspend fun setRootInstall(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ROOT_INSTALL] = enabled }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setAmoled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AMOLED] = enabled }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_DONE] = complete }
    }

    override suspend fun setTranslationSettings(settings: TranslationSettings) {
        context.settingsDataStore.edit {
            it[Keys.TRANSLATION_PROVIDER] = settings.provider.name
            it[Keys.TRANSLATION_AUTO] = settings.autoTranslate
            it[Keys.DEEPL_KEY] = settings.deeplKey.trim()
            it[Keys.LIBRE_URL] = settings.libreUrl.trim()
            it[Keys.LIBRE_KEY] = settings.libreKey.trim()
            it[Keys.MICROSOFT_KEY] = settings.microsoftKey.trim()
            it[Keys.MICROSOFT_REGION] = settings.microsoftRegion.trim()
        }
    }

    override suspend fun setPreferredMirror(host: String) {
        context.settingsDataStore.edit { it[Keys.PREFERRED_MIRROR] = host }
    }

    override suspend fun setMaxConcurrentDownloads(count: Int) {
        context.settingsDataStore.edit {
            it[Keys.MAX_CONCURRENT_DOWNLOADS] = count.coerceIn(1, DownloadSlots.MAX_LIMIT)
        }
    }

    override suspend fun setBackgroundCheck(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BACKGROUND_CHECK] = enabled }
    }

    override suspend fun setCheckIntervalHours(hours: Int) {
        context.settingsDataStore.edit { it[Keys.CHECK_INTERVAL_HOURS] = hours.coerceIn(1, 168) }
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    override suspend fun setAutoUpdateRoot(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UPDATE_ROOT] = enabled }
    }

    override suspend fun setHideLauncherIcon(hidden: Boolean) {
        context.settingsDataStore.edit { it[Keys.HIDE_LAUNCHER_ICON] = hidden }
    }

    override suspend fun setNotifiedUpdateSignature(signature: String) {
        context.settingsDataStore.edit { it[Keys.NOTIFIED_UPDATE_SIGNATURE] = signature }
    }
}
