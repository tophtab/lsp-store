package org.lspstore.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.UpdateChannel

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl(private val context: Context) {

    private object Keys {
        val CHANNEL = stringPreferencesKey("update_channel")
        val SORT = stringPreferencesKey("sort_order")
        val AUTO_CHECK = booleanPreferencesKey("auto_check_updates")
        val ROOT_INSTALL = booleanPreferencesKey("root_install")
        val FAVOURITES = stringSetPreferencesKey("favourites")
    }

    val updateChannel: Flow<UpdateChannel> = context.settingsDataStore.data
        .map { UpdateChannel.fromKey(it[Keys.CHANNEL]) }

    val sortOrder: Flow<SortOrder> = context.settingsDataStore.data
        .map { raw -> SortOrder.entries.firstOrNull { it.name == raw[Keys.SORT] } ?: SortOrder.UPDATED }

    val autoCheckUpdates: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.AUTO_CHECK] ?: true }

    val rootInstall: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.ROOT_INSTALL] ?: false }

    val favourites: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[Keys.FAVOURITES] ?: emptySet() }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.settingsDataStore.edit { it[Keys.CHANNEL] = channel.key }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.settingsDataStore.edit { it[Keys.SORT] = order.name }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CHECK] = enabled }
    }

    suspend fun setRootInstall(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ROOT_INSTALL] = enabled }
    }

    suspend fun toggleFavourite(packageName: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.FAVOURITES] ?: emptySet()
            prefs[Keys.FAVOURITES] = if (packageName in current) current - packageName else current + packageName
        }
    }
}
