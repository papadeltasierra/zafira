package com.pioneermediabridge.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_PIONEER_MAC = stringPreferencesKey("pioneer_mac")
        val KEY_PIONEER_NAME = stringPreferencesKey("pioneer_name")
        val KEY_OUTPUT_BLE_MAC = stringPreferencesKey("output_ble_mac")
        val KEY_OUTPUT_BLE_NAME = stringPreferencesKey("output_ble_name")
        val KEY_SNOOP_FILE_PATH = stringPreferencesKey("snoop_file_path")
        val KEY_SNOOP_SOCKET_NAME = stringPreferencesKey("snoop_socket_name")
        val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            pioneerMac = prefs[KEY_PIONEER_MAC] ?: "",
            pioneerName = prefs[KEY_PIONEER_NAME] ?: "",
            outputBleMac = prefs[KEY_OUTPUT_BLE_MAC] ?: "",
            outputBleName = prefs[KEY_OUTPUT_BLE_NAME] ?: "",
            snoopFilePath = prefs[KEY_SNOOP_FILE_PATH] ?: AppSettings.DEFAULT_SNOOP_PATH,
            snoopSocketName = prefs[KEY_SNOOP_SOCKET_NAME] ?: AppSettings.DEFAULT_SOCKET_NAME,
            serviceEnabled = prefs[KEY_SERVICE_ENABLED] ?: false
        )
    }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PIONEER_MAC] = s.pioneerMac
            prefs[KEY_PIONEER_NAME] = s.pioneerName
            prefs[KEY_OUTPUT_BLE_MAC] = s.outputBleMac
            prefs[KEY_OUTPUT_BLE_NAME] = s.outputBleName
            prefs[KEY_SNOOP_FILE_PATH] = s.snoopFilePath
            prefs[KEY_SNOOP_SOCKET_NAME] = s.snoopSocketName
            prefs[KEY_SERVICE_ENABLED] = s.serviceEnabled
        }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SERVICE_ENABLED] = enabled }
    }
}
