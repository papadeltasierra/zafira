package com.pioneermediabridge.ble

object OtaProtocol {
    const val CMD_START: Byte = 0x01
    const val CMD_ABORT: Byte = 0x02
    const val CMD_COMMIT: Byte = 0x03

    const val EVT_READY = 0x80
    const val EVT_ACK = 0x81
    const val EVT_COMMITTED = 0x82
    const val EVT_ERROR = 0xEF

    const val START_PAYLOAD_LEN = 40
    const val FIRMWARE_INFO_LEN = 12

    const val DEFAULT_WINDOW = 16
    const val ACK_TIMEOUT_MS = 15_000L
    const val REBOOT_TIMEOUT_MS = 120_000L
    /** Used until the device reports its own limit in Firmware Info. */
    const val FALLBACK_CHUNK = 20

    fun errorMessage(reason: Int): String = when (reason) {
        1 -> "Device is busy with another update"
        2 -> "Image is too large for the device OTA slot"
        3 -> "Chunk arrived out of order"
        4 -> "Device flash write failed"
        5 -> "Image hash did not match on the device"
        6 -> "Device rejected the image as invalid"
        7 -> "Device timed out waiting for data"
        8 -> "Device rejected the command (no active session)"
        9 -> "Device could not keep up with the transfer"
        else -> "Device reported error $reason"
    }
}

data class FirmwareInfo(
    val version: SemVer,
    val pendingVerify: Boolean,
    val otaSlotSize: Int,
    val maxChunk: Int
)

enum class OtaPhase { IDLE, VALIDATING, STARTING, SENDING, COMMITTING, REBOOTING, SUCCESS, FAILED }

data class OtaProgress(
    val phase: OtaPhase = OtaPhase.IDLE,
    val bytesSent: Int = 0,
    val totalBytes: Int = 0,
    val message: String = ""
) {
    val percent: Int get() = if (totalBytes <= 0) 0 else (bytesSent.toLong() * 100 / totalBytes).toInt()
    val isRunning: Boolean
        get() = phase == OtaPhase.STARTING || phase == OtaPhase.SENDING ||
            phase == OtaPhase.COMMITTING || phase == OtaPhase.REBOOTING
}
