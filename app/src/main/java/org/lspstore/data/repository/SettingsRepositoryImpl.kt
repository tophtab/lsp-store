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

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12L
    }
}
