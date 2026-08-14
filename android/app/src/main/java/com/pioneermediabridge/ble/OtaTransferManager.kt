package com.pioneermediabridge.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GATT operations the transfer needs, supplied by [BleWriterManager]. */
interface OtaGatt {
    fun writeOtaControl(data: ByteArray): Boolean
    fun writeOtaData(data: ByteArray): Boolean
    fun setOtaActive(active: Boolean)
    val chunkSize: Int
}

/**
 * Drives one OTA session: START, windowed chunk writes gated on device ACKs, then COMMIT.
 * All state is confined to [scope], which must be single-threaded.
 */
class OtaTransferManager(private val scope: CoroutineScope) {

    private val TAG = "OtaTransfer"

    private val _progress = MutableStateFlow(OtaProgress())
    val progress: StateFlow<OtaProgress> = _progress.asStateFlow()

    private var gatt: OtaGatt? = null
    private var image: FirmwareImage? = null
    private var chunkSize = OtaProtocol.FALLBACK_CHUNK
    private var window = OtaProtocol.DEFAULT_WINDOW
    private var nextSeq = 0
    private var bytesSent = 0
    private var bytesAcked = 0
    private var watchdog: Job? = null

    val isActive: Boolean get() = _progress.value.isRunning

    /** The version the user chose, kept so the post-reboot report can be checked against it. */
    var targetVersion: SemVer? = null
        private set

    fun start(transport: OtaGatt, firmware: FirmwareImage) {
        if (isActive) {
            Log.w(TAG, "Transfer already running")
            return
        }

        gatt = transport
        image = firmware
        targetVersion = firmware.version
        chunkSize = transport.chunkSize.coerceAtLeast(OtaProtocol.FALLBACK_CHUNK)
        nextSeq = 0
        bytesSent = 0
        bytesAcked = 0
        transport.setOtaActive(true)

        _progress.value = OtaProgress(
            phase = OtaPhase.STARTING,
            totalBytes = firmware.size,
            message = "Preparing device…"
        )

        val payload = ByteBuffer.allocate(OtaProtocol.START_PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(OtaProtocol.CMD_START)
        payload.putInt(firmware.size)
        payload.put(firmware.version.major.toByte())
        payload.put(firmware.version.minor.toByte())
        payload.put(firmware.version.patch.toByte())
        payload.put(firmware.sha256)

        if (!transport.writeOtaControl(payload.array())) {
            fail("Could not send the update command to the device")
            return
        }
        armWatchdog()
    }

    fun cancel(userInitiated: Boolean = true) {
        if (!isActive) return
        gatt?.writeOtaControl(byteArrayOf(OtaProtocol.CMD_ABORT))
        if (userInitiated) {
            finish(OtaPhase.FAILED, "Update cancelled")
        }
    }

    fun onDisconnected() {
        when (_progress.value.phase) {
            OtaPhase.REBOOTING -> Unit // Expected: the device drops the link to reboot.
            OtaPhase.COMMITTING -> enterRebooting()
            OtaPhase.STARTING, OtaPhase.SENDING -> fail("Connection lost during transfer")
            else -> Unit
        }
    }

    /** Called when the device reports its version again, typically after the post-OTA reboot. */
    fun onFirmwareInfo(info: FirmwareInfo) {
        if (_progress.value.phase != OtaPhase.REBOOTING) return
        val expected = targetVersion ?: return

        if (info.version == expected) {
            finish(OtaPhase.SUCCESS, "Device is running ${info.version}")
        } else {
            finish(
                OtaPhase.FAILED,
                "Device came back on ${info.version}, not $expected. It may have rolled back."
            )
        }
    }

    fun onControlNotification(data: ByteArray) {
        if (data.isEmpty()) return
        armWatchdog()

        when (data[0].toInt() and 0xFF) {
            OtaProtocol.EVT_READY -> {
                if (data.size >= 7) {
                    window = readU16(data, 5).coerceIn(1, 64)
                }
                _progress.value = _progress.value.copy(
                    phase = OtaPhase.SENDING,
                    message = "Sending firmware…"
                )
                pumpWindow()
            }

            OtaProtocol.EVT_ACK -> {
                if (data.size >= 5) {
                    bytesAcked = readI32(data, 1)
                }
                val total = image?.size ?: 0
                if (bytesAcked >= total && total > 0) {
                    commit()
                } else {
                    pumpWindow()
                }
            }

            OtaProtocol.EVT_COMMITTED -> enterRebooting()

            OtaProtocol.EVT_ERROR -> {
                val reason = if (data.size >= 2) data[1].toInt() and 0xFF else 0
                fail(OtaProtocol.errorMessage(reason))
            }
        }
    }

    private fun pumpWindow() {
        val transport = gatt ?: return
        val firmware = image ?: return

        var queued = 0
        while (queued < window && bytesSent < firmware.size) {
            val end = minOf(bytesSent + chunkSize, firmware.size)
            val frame = ByteArray(2 + (end - bytesSent))
            frame[0] = (nextSeq and 0xFF).toByte()
            frame[1] = ((nextSeq shr 8) and 0xFF).toByte()
            firmware.bytes.copyInto(frame, 2, bytesSent, end)

            if (!transport.writeOtaData(frame)) {
                fail("Bluetooth write failed")
                return
            }

            bytesSent = end
            nextSeq = (nextSeq + 1) and 0xFFFF
            queued++
        }

        _progress.value = _progress.value.copy(bytesSent = bytesSent)
    }

    private fun commit() {
        val transport = gatt ?: return
        _progress.value = _progress.value.copy(
            phase = OtaPhase.COMMITTING,
            bytesSent = image?.size ?: bytesSent,
            message = "Verifying on device…"
        )
        if (!transport.writeOtaControl(byteArrayOf(OtaProtocol.CMD_COMMIT))) {
            fail("Could not send the commit command")
        }
    }

    private fun enterRebooting() {
        if (_progress.value.phase == OtaPhase.REBOOTING) return
        _progress.value = _progress.value.copy(
            phase = OtaPhase.REBOOTING,
            message = "Device is rebooting…"
        )
        gatt?.setOtaActive(false)
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(OtaProtocol.REBOOT_TIMEOUT_MS)
            if (_progress.value.phase == OtaPhase.REBOOTING) {
                finish(OtaPhase.FAILED, "Device did not come back online")
            }
        }
    }

    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(OtaProtocol.ACK_TIMEOUT_MS)
            if (_progress.value.phase == OtaPhase.STARTING || _progress.value.phase == OtaPhase.SENDING) {
                gatt?.writeOtaControl(byteArrayOf(OtaProtocol.CMD_ABORT))
                fail("Device stopped responding")
            }
        }
    }

    private fun fail(message: String) = finish(OtaPhase.FAILED, message)

    private fun finish(phase: OtaPhase, message: String) {
        Log.i(TAG, "OTA finished: $phase – $message")
        watchdog?.cancel()
        watchdog = null
        gatt?.setOtaActive(false)
        gatt = null
        image = null
        _progress.value = _progress.value.copy(phase = phase, message = message)
    }

    fun acknowledgeResult() {
        if (!isActive) {
            _progress.value = OtaProgress()
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun readI32(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
