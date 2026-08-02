package com.pioneermediabridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pioneermediabridge.model.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts [MonitorService] automatically after device boot if the service was
 * previously enabled by the user.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val repo = SettingsRepository(context)
            val settings = repo.settings.first()
            if (settings.serviceEnabled && settings.isConfigured) {
                Log.i("BootReceiver", "Auto-starting MonitorService")
                MonitorService.start(context)
            }
        }
    }
}
