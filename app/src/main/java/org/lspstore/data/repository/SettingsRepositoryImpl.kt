package org.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.lspstore.domain.model.ProxyConfig
import org.lspstore.domain.model.ProxyType
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.ThemeMode
import org.lspstore.domain.model.TranslationProvider
import org.lspstore.domain.model.TranslationSettings
import org.lspstore.domain.model.UpdateChannel

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl(private val context: Context) {

    private object Keys {
        val CHANNEL = stringPreferencesKey("update_channel")
        val SORT = stringPreferencesKey("sort_order")
        val AUTO_CHECK = booleanPreferencesKey("auto_check_updates")
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("background_check_enabled")
        val UPDATE_INTERVAL_HOURS = longPreferencesKey("background_check_interval_hours")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val ROOT_INSTALL = booleanPreferencesKey("root_install")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USER = stringPreferencesKey("proxy_user")
        val PROXY_PASS = stringPreferencesKey("proxy_pass")
        val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
        val DEEPL_KEY = stringPreferencesKey("deepl_key")
        val LIBRE_URL = stringPreferencesKey("libre_url")
        val LIBRE_KEY = stringPreferencesKey("libre_key")
        val MICROSOFT_KEY = stringPreferencesKey("microsoft_key")
        val MICROSOFT_REGION = stringPreferencesKey("microsoft_region")
        val PREFERRED_MIRROR = stringPreferencesKey("preferred_mirror")
        val TRANSLATION_TARGET = stringPreferencesKey("translation_target")
    }

    val updateChannel: Flow<UpdateChannel> = context.settingsDataStore.data
        .map { UpdateChannel.fromKey(it[Keys.CHANNEL]) }

    val sortOrder: Flow<SortOrder> = context.settingsDataStore.data
        .map { raw -> SortOrder.entries.firstOrNull { it.name == raw[Keys.SORT] } ?: SortOrder.UPDATED }

    val autoCheckUpdates: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AUTO_CHECK] ?: true }

    val backgroundCheckEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.UPDATE_CHECK_ENABLED] ?: true }

    val backgroundCheckIntervalHours: Flow<Long> = context.settingsDataStore.data
        .map { it[Keys.UPDATE_INTERVAL_HOURS] ?: DEFAULT_INTERVAL_HOURS }

    val autoUpdate: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AUTO_UPDATE] ?: false }

    val rootInstall: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ROOT_INSTALL] ?: false }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data
        .map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM }

    val amoled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AMOLED] ?: false }

    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: true }

    val onboardingComplete: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ONBOARDING_DONE] ?: false }

    val proxyConfig: Flow<ProxyConfig> = context.settingsDataStore.data.map { prefs ->
        ProxyConfig(
            enabled = prefs[Keys.PROXY_ENABLED] ?: false,
            type = ProxyType.entries.firstOrNull { it.name == prefs[Keys.PROXY_TYPE] } ?: ProxyType.HTTP,
            host = prefs[Keys.PROXY_HOST] ?: "",
            port = prefs[Keys.PROXY_PORT] ?: 0,
            username = prefs[Keys.PROXY_USER] ?: "",
            password = prefs[Keys.PROXY_PASS] ?: "",
        )
    }

    val preferredMirror: Flow<String> = context.settingsDataStore.data
        .map { it[Keys.PREFERRED_MIRROR] ?: "" }

    val translationSettings: Flow<TranslationSettings> = context.settingsDataStore.data.map { prefs ->
        TranslationSettings(
            provider = TranslationProvider.entries
                .firstOrNull { it.name == prefs[Keys.TRANSLATION_PROVIDER] }
                ?: TranslationProvider.DISABLED,
            targetLang = prefs[Keys.TRANSLATION_TARGET]
                ?: java.util.Locale.getDefault().language.take(2),
            deeplKey = prefs[Keys.DEEPL_KEY] ?: "",
            libreUrl = prefs[Keys.LIBRE_URL] ?: "",
            libreKey = prefs[Keys.LIBRE_KEY] ?: "",
            microsoftKey = prefs[Keys.MICROSOFT_KEY] ?: "",
            microsoftRegion = prefs[Keys.MICROSOFT_REGION] ?: "",
        )
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.settingsDataStore.edit { it[Keys.CHANNEL] = channel.key }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.settingsDataStore.edit { it[Keys.SORT] = order.name }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CHECK] = enabled }
    }

    suspend fun setBackgroundCheckEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.UPDATE_CHECK_ENABLED] = enabled }
    }

    suspend fun setBackgroundCheckIntervalHours(hours: Long) {
        context.settingsDataStore.edit { it[Keys.UPDATE_INTERVAL_HOURS] = hours }
    }

    suspend fun setAutoUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UPDATE] = enabled }
    }

    suspend fun setRootInstall(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ROOT_INSTALL] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAmoled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AMOLED] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_DONE] = complete }
    }

    suspend fun setProxyConfig(config: ProxyConfig) {
        context.settingsDataStore.edit {
            it[Keys.PROXY_ENABLED] = config.enabled
            it[Keys.PROXY_TYPE] = config.type.name
            it[Keys.PROXY_HOST] = config.host.trim()
            it[Keys.PROXY_PORT] = config.port
            it[Keys.PROXY_USER] = config.username
            it[Keys.PROXY_PASS] = config.password
        }
    }

    suspend fun setTranslationSettings(settings: TranslationSettings) {
        context.settingsDataStore.edit {
            it[Keys.TRANSLATION_PROVIDER] = settings.provider.name
            it[Keys.TRANSLATION_TARGET] = settings.targetLang
            it[Keys.DEEPL_KEY] = settings.deeplKey.trim()
            it[Keys.LIBRE_URL] = settings.libreUrl.trim()
            it[Keys.LIBRE_KEY] = settings.libreKey.trim()
            it[Keys.MICROSOFT_KEY] = settings.microsoftKey.trim()
            it[Keys.MICROSOFT_REGION] = settings.microsoftRegion.trim()
        }
    }

    suspend fun setPreferredMirror(host: String) {
        context.settingsDataStore.edit { it[Keys.PREFERRED_MIRROR] = host }
    }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12L
    }
}
