package com.pioneermediabridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pioneermediabridge.MainActivity
import com.pioneermediabridge.R
import com.pioneermediabridge.ble.BleConnectionState
import com.pioneermediabridge.ble.BleSession
import com.pioneermediabridge.ble.BleWriterManager
import com.pioneermediabridge.model.AppSettings
import com.pioneermediabridge.model.MediaInfo
import com.pioneermediabridge.model.SettingsRepository
import com.pioneermediabridge.parser.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MonitorService : LifecycleService() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "pioneer_bridge"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.pioneermediabridge.START"
        const val ACTION_STOP = "com.pioneermediabridge.STOP"

        // Broadcast for status updates to MainActivity
        const val BROADCAST_STATUS = "com.pioneermediabridge.STATUS"
        const val EXTRA_MEDIA_INFO = "media_info"
        const val EXTRA_BLE_STATE = "ble_state"

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    private val bleWriter = BleWriterManager(this)
    private var monitorJob: Job? = null
    private var lastMediaInfo: MediaInfo = MediaInfo.Idle
    private var currentBleState: BleConnectionState = BleConnectionState.DISCONNECTED

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> {
                if (monitorJob?.isActive == true) {
                    // MainActivity may issue ACTION_START whenever it enters foreground.
                    // Reuse the current monitor session instead of tearing down BLE.
                    broadcastStatus(lastMediaInfo, currentBleState)
                    return START_STICKY
                }
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        BleSession.writer = null
        bleWriter.stop()
        currentBleState = BleConnectionState.DISCONNECTED
        broadcastStatus(lastMediaInfo, currentBleState)
        super.onDestroy()
    }

    // ── Core monitoring logic ─────────────────────────────────────────────────

    private fun startMonitoring() {
        startForeground(NOTIF_ID, buildNotification("Starting…", "Connecting"))

        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            val repo = SettingsRepository(this@MonitorService)
            val settings = repo.settings.first()

            if (!settings.isConfigured) {
                Log.w(TAG, "Not configured – stopping")
                updateNotification("Not configured", "Open app to set up")
                return@launch
            }

            // Start output BLE writer
            bleWriter.start(settings.outputBleMac, settings.outputBleName, this)
            BleSession.writer = bleWriter

            // Observe BLE state changes for notification updates
            launch {
                bleWriter.connectionState.collect { state ->
                    currentBleState = state
                    val label = when (state) {
                        BleConnectionState.DISCONNECTED -> "Output: disconnected"
                        BleConnectionState.SCANNING -> "Output: scanning…"
                        BleConnectionState.CONNECTING -> "Output: connecting…"
                        BleConnectionState.DISCOVERING -> "Output: discovering…"
                        BleConnectionState.READY -> "Output: connected"
                        BleConnectionState.ERROR -> "Output: error"
                    }
                    updateNotification(lastMediaInfo.toString(), label)
                    broadcastStatus(lastMediaInfo, state)
                }
            }

            // Android 16+ can expose a live TCP snoop logger endpoint on localhost.
            // On older versions fall back to reading the btsnoop log file.
            val rawRecords = if (android.os.Build.VERSION.SDK_INT >= 36) {
                Log.i(TAG, "API 36: using TCP snoop socket 127.0.0.1:8872")
                updateNotification("Starting…", "Connecting to snoop socket (TCP)")
                BtSnoopSocketReader().records()
            } else {
                val snoopPath = resolveSnoopPath(settings) ?: run {
                    Log.e(TAG, "Cannot find readable btsnoop file")
                    updateNotification("Snoop file not found", "Check Settings and grant 'All files' permission")
                    return@launch
                }
                Log.i(TAG, "Using btsnoop file: $snoopPath")
                BtSnoopReader(snoopPath).records()
            }

            val mediaFlow = ClassicSmartSyncParser(
                pioneerMacBytes = settings.pioneerMacBytes,
                excludedMacBytes = settings.outputBleMacBytes
            ).parse(rawRecords)

            mediaFlow
                .distinctUntilChanged()
                .collect { info ->
                    lastMediaInfo = info
                    Log.i(TAG, "Media: $info")
                    bleWriter.sendMediaInfo(info)
                    updateNotification(
                        info.toString(),
                        bleWriter.connectionState.value.name
                    )
                    broadcastStatus(info, bleWriter.connectionState.value)
                }
        }
    }

    private fun resolveSnoopPath(settings: AppSettings): String? {
        val candidates = buildList {
            if (settings.snoopFilePath.isNotBlank()) add(settings.snoopFilePath)
            addAll(AppSettings.CANDIDATE_PATHS)
        }
        return candidates.firstOrNull { java.io.File(it).canRead() }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Zafira",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Bluetooth media bridge status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentTitle: String, contentText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun broadcastStatus(info: MediaInfo, state: BleConnectionState) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_MEDIA_INFO, info.toString())
            putExtra(EXTRA_BLE_STATE, state.name)
        }
        sendBroadcast(intent)
    }
}
