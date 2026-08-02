package com.pioneermediabridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pioneermediabridge.model.AppSettings
import com.pioneermediabridge.model.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings()
    )

    fun save(
        pioneerMac: String,
        pioneerName: String,
        outputBleMac: String,
        outputBleName: String,
        snoopFilePath: String,
        snoopSocketName: String
    ) {
        viewModelScope.launch {
            repo.save(
                AppSettings(
                    pioneerMac = pioneerMac.trim(),
                    pioneerName = pioneerName.trim(),
                    outputBleMac = outputBleMac.trim(),
                    outputBleName = outputBleName.trim(),
                    snoopFilePath = snoopFilePath.trim().ifBlank { AppSettings.DEFAULT_SNOOP_PATH },
                    snoopSocketName = snoopSocketName.trim().ifBlank { AppSettings.DEFAULT_SOCKET_NAME }
                )
            )
        }
    }
}
