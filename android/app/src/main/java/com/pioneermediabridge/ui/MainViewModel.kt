package com.pioneermediabridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pioneermediabridge.ble.BleConnectionState
import com.pioneermediabridge.model.SettingsRepository
import kotlinx.coroutines.flow.*

data class MainUiState(
    val bleState: String = BleConnectionState.DISCONNECTED.name,
    val currentMedia: String = "Idle",
    val isConfigured: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    private val _bleState = MutableStateFlow(BleConnectionState.DISCONNECTED.name)
    private val _currentMedia = MutableStateFlow("Idle")

    val uiState: StateFlow<MainUiState> = combine(
        repo.settings,
        _bleState,
        _currentMedia
    ) { settings, bleState, media ->
        MainUiState(
            bleState = bleState,
            currentMedia = media,
            isConfigured = settings.isConfigured
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun updateFromBroadcast(mediaInfo: String, bleState: String) {
        _currentMedia.value = mediaInfo
        _bleState.value = bleState
    }

    suspend fun isConfigured(): Boolean = repo.settings.first().isConfigured
}
