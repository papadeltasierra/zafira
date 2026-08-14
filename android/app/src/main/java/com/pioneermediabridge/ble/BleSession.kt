package com.pioneermediabridge.ble

/**
 * Handle on the GATT connection owned by MonitorService, so the Setup page can drive OTA
 * without opening a second connection to the device.
 */
object BleSession {
    @Volatile
    var writer: BleWriterManager? = null
}
