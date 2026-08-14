package com.pioneermediabridge.ble

import java.util.UUID

object BleConstants {
    // SDL BLE Transport service (Pioneer AppLink / SmartDeviceLink BLE spec)
    val SDL_SERVICE_UUID: UUID = UUID.fromString("0000923A-0000-1000-8000-00805F9B34FB")
    val SDL_WRITE_CHAR_UUID: UUID = UUID.fromString("00009245-0000-1000-8000-00805F9B34FB")
    val SDL_READ_CHAR_UUID: UUID = UUID.fromString("00009246-0000-1000-8000-00805F9B34FB")
    val SDL_CONTROL_CHAR_UUID: UUID = UUID.fromString("00009247-0000-1000-8000-00805F9B34FB")

    // Custom bridge output service (receiver device must implement these)
    val BRIDGE_SERVICE_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678901")
    val MEDIA_INFO_CHAR_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678902")
    val TIME_SYNC_CHAR_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678903")
    val POWER_UP_CHAR_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678904")
    val FIRMWARE_INFO_CHAR_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678905")
    val OTA_CONTROL_CHAR_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678906")
    val OTA_DATA_CHAR_UUID: UUID = UUID.fromString("A1234567-1234-1234-1234-A12345678907")

    // Standard GATT descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Timing
    const val TIME_SYNC_INTERVAL_MS = 30L * 60L * 1000L  // 30 minutes
    const val RECONNECT_DELAY_MS = 5_000L
    const val SCAN_TIMEOUT_MS = 30_000L
    const val WRITE_SETTLE_MS = 50L                        // gap between writes
    const val REQUESTED_MTU = 517                          // ATT maximum, needed for OTA throughput
}
