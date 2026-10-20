package org.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.ThemeMode
import org.lspstore.domain.model.TranslationProvider
import org.lspstore.domain.model.TranslationSettings
import org.lspstore.domain.model.UpdateChannel

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

interface SettingsRepository {
    val updateChannel: Flow<UpdateChannel>
    val sortOrder: Flow<SortOrder>
    val backgroundCheckEnabled: Flow<Boolean>
    val backgroundCheckIntervalHours: Flow<Long>
    val autoUpdate: Flow<Boolean>
    val rootInstall: Flow<Boolean>
    val themeMode: Flow<ThemeMode>
    val amoled: Flow<Boolean>
    val dynamicColor: Flow<Boolean>
    val onboardingComplete: Flow<Boolean>
    val preferredMirror: Flow<String>
    val translationSettings: Flow<TranslationSettings>

    suspend fun setUpdateChannel(channel: UpdateChannel)
    suspend fun setSortOrder(order: SortOrder)
    suspend fun setBackgroundCheckEnabled(enabled: Boolean)
    suspend fun setBackgroundCheckIntervalHours(hours: Long)
    suspend fun setAutoUpdate(enabled: Boolean)
    suspend fun setRootInstall(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAmoled(enabled: Boolean)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setOnboardingComplete(complete: Boolean)
    suspend fun setTranslationSettings(settings: TranslationSettings)
    suspend fun setPreferredMirror(host: String)

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12L
    }
}

class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {

    private object Keys {
        val CHANNEL = stringPreferencesKey("update_channel")
        val SORT = stringPreferencesKey("sort_order")
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("background_check_enabled")
        val UPDATE_INTERVAL_HOURS = longPreferencesKey("background_check_interval_hours")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val ROOT_INSTALL = booleanPreferencesKey("root_install")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
        val DEEPL_KEY = stringPreferencesKey("deepl_key")
        val LIBRE_URL = stringPreferencesKey("libre_url")
        val LIBRE_KEY = stringPreferencesKey("libre_key")
        val MICROSOFT_KEY = stringPreferencesKey("microsoft_key")
        val MICROSOFT_REGION = stringPreferencesKey("microsoft_region")
        val PREFERRED_MIRROR = stringPreferencesKey("preferred_mirror")
        val TRANSLATION_TARGET = stringPreferencesKey("translation_target")
    }

    override val updateChannel: Flow<UpdateChannel> = context.settingsDataStore.data
        .map { UpdateChannel.fromKey(it[Keys.CHANNEL]) }

    override val sortOrder: Flow<SortOrder> = context.settingsDataStore.data
        .map { raw -> SortOrder.entries.firstOrNull { it.name == raw[Keys.SORT] } ?: SortOrder.UPDATED }

    override val backgroundCheckEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.UPDATE_CHECK_ENABLED] ?: true }

    override val backgroundCheckIntervalHours: Flow<Long> = context.settingsDataStore.data
        .map { it[Keys.UPDATE_INTERVAL_HOURS] ?: DEFAULT_INTERVAL_HOURS }

    override val autoUpdate: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AUTO_UPDATE] ?: false }

    override val rootInstall: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ROOT_INSTALL] ?: false }

    override val themeMode: Flow<ThemeMode> = context.settingsDataStore.data
        .map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM }

    override val amoled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AMOLED] ?: false }

    override val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: false }

    override val onboardingComplete: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ONBOARDING_DONE] ?: false }

    override val preferredMirror: Flow<String> = context.settingsDataStore.data
        .map { it[Keys.PREFERRED_MIRROR] ?: "" }

    override val translationSettings: Flow<TranslationSettings> = context.settingsDataStore.data.map { prefs ->
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

    override suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.settingsDataStore.edit { it[Keys.CHANNEL] = channel.key }
    }

    override suspend fun setSortOrder(order: SortOrder) {
        context.settingsDataStore.edit { it[Keys.SORT] = order.name }
    }

    override suspend fun setBackgroundCheckEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.UPDATE_CHECK_ENABLED] = enabled }
    }

    override suspend fun setBackgroundCheckIntervalHours(hours: Long) {
        context.settingsDataStore.edit { it[Keys.UPDATE_INTERVAL_HOURS] = hours }
    }

    override suspend fun setAutoUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UPDATE] = enabled }
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
            it[Keys.TRANSLATION_TARGET] = settings.targetLang
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

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12L
    }
}
