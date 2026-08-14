package com.pioneermediabridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.DeadObjectException
import android.util.Log
import com.pioneermediabridge.model.MediaInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY, ERROR }

@SuppressLint("MissingPermission")
class BleWriterManager(private val context: Context) : OtaGatt {

    private val TAG = "BleWriterManager"

    private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _firmwareInfo = MutableStateFlow<FirmwareInfo?>(null)
    val firmwareInfo: StateFlow<FirmwareInfo?> = _firmwareInfo.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var mediaChar: BluetoothGattCharacteristic? = null
    private var timeChar: BluetoothGattCharacteristic? = null
    private var powerUpChar: BluetoothGattCharacteristic? = null
    private var firmwareInfoChar: BluetoothGattCharacteristic? = null
    private var otaControlChar: BluetoothGattCharacteristic? = null
    private var otaDataChar: BluetoothGattCharacteristic? = null
    private var mtuRequestPending = false
    private var negotiatedMtu = 23
    private var otaActive = false

    private data class PendingWrite(
        val char: BluetoothGattCharacteristic,
        val data: ByteArray,
        val writeType: Int
    )

    // Serialised write queue – only one outstanding write at a time
    private val writeQueue = ArrayDeque<PendingWrite>()
    private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()
    private var writePending = false

    private var scope: CoroutineScope? = null
    private var timeSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private var scanCallback: ScanCallback? = null

    private var targetMac: String = ""
    private var targetName: String = ""

    /** Non-null once a monitoring session is running; drives the Setup page OTA flow. */
    var otaTransfer: OtaTransferManager? = null
        private set

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services")
                    _state.value = BleConnectionState.DISCOVERING
                    mtuRequestPending = g.requestMtu(BleConstants.REQUESTED_MTU)
                    if (!mtuRequestPending) {
                        Log.w(TAG, "MTU request failed; continuing with default MTU")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    mediaChar = null
                    timeChar = null
                    powerUpChar = null
                    firmwareInfoChar = null
                    otaControlChar = null
                    otaDataChar = null
                    writeQueue.clear()
                    pendingSubscriptions.clear()
                    writePending = false
                    timeSyncJob?.cancel()
                    _state.value = BleConnectionState.DISCONNECTED
                    otaTransfer?.onDisconnected()
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuRequestPending = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU negotiated: $mtu")
                negotiatedMtu = mtu
            } else {
                Log.w(TAG, "MTU negotiation failed: status=$status, mtu=$mtu")
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                g.disconnect()
                return
            }
            val svc = g.getService(BleConstants.BRIDGE_SERVICE_UUID)
            if (svc == null) {
                Log.w(TAG, "Bridge service not found – check remote device firmware")
                g.disconnect()
                return
            }
            mediaChar = svc.getCharacteristic(BleConstants.MEDIA_INFO_CHAR_UUID)
            timeChar = svc.getCharacteristic(BleConstants.TIME_SYNC_CHAR_UUID)
            powerUpChar = svc.getCharacteristic(BleConstants.POWER_UP_CHAR_UUID)
            if (mediaChar == null || timeChar == null || powerUpChar == null) {
                Log.w(TAG, "Required characteristics missing")
                g.disconnect()
                return
            }

            // OTA characteristics are optional so the app still works with pre-OTA firmware.
            firmwareInfoChar = svc.getCharacteristic(BleConstants.FIRMWARE_INFO_CHAR_UUID)
            otaControlChar = svc.getCharacteristic(BleConstants.OTA_CONTROL_CHAR_UUID)
            otaDataChar = svc.getCharacteristic(BleConstants.OTA_DATA_CHAR_UUID)
            if (firmwareInfoChar == null) {
                Log.w(TAG, "Device firmware predates OTA support; assuming version 0.0.0")
                _firmwareInfo.value = FirmwareInfo(SemVer.ZERO, false, 0, 0)
            }

            pendingSubscriptions.clear()
            firmwareInfoChar?.let { pendingSubscriptions.add(it) }
            otaControlChar?.let { pendingSubscriptions.add(it) }
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed on ${descriptor.characteristic.uuid}: $status")
            }
            subscribeNext(g)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleRead(g, characteristic.uuid, value, status)
        }

        @Deprecated("Required for API < 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleRead(g, characteristic.uuid, characteristic.value ?: ByteArray(0), status)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic.uuid, value)
        }

        @Deprecated("Required for API < 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed: status=$status on ${characteristic.uuid}")
            }
            writePending = false
            if (otaActive) {
                drainQueue()
                return
            }
            scope?.launch(Dispatchers.Main) {
                delay(BleConstants.WRITE_SETTLE_MS)
                drainQueue()
            }
        }
    }

    // ── Discovery chain ───────────────────────────────────────────────────────

    private fun subscribeNext(g: BluetoothGatt) {
        val char = pendingSubscriptions.poll()
        if (char == null) {
            readFirmwareInfoOrFinish(g)
            return
        }

        val cccd = char.getDescriptor(BleConstants.CCCD_UUID)
        if (cccd == null || !g.setCharacteristicNotification(char, true)) {
            Log.w(TAG, "Cannot subscribe to ${char.uuid}")
            subscribeNext(g)
            return
        }

        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = enable
                g.writeDescriptor(cccd)
            }
        }
        if (!started) subscribeNext(g)
    }

    private fun readFirmwareInfoOrFinish(g: BluetoothGatt) {
        val char = firmwareInfoChar
        if (char == null || !g.readCharacteristic(char)) {
            markReady()
        }
    }

    private fun handleRead(g: BluetoothGatt, uuid: java.util.UUID, value: ByteArray, status: Int) {
        if (uuid == BleConstants.FIRMWARE_INFO_CHAR_UUID) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseFirmwareInfo(value)
            } else {
                Log.w(TAG, "Firmware info read failed: $status")
            }
            markReady()
        }
    }

    private fun handleNotification(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.FIRMWARE_INFO_CHAR_UUID -> parseFirmwareInfo(value)
            BleConstants.OTA_CONTROL_CHAR_UUID -> otaTransfer?.onControlNotification(value)
        }
    }

    private fun parseFirmwareInfo(value: ByteArray) {
        if (value.size < OtaProtocol.FIRMWARE_INFO_LEN) {
            Log.w(TAG, "Firmware info payload too short: ${value.size}")
            return
        }
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val version = SemVer(
            value[0].toInt() and 0xFF,
            value[1].toInt() and 0xFF,
            value[2].toInt() and 0xFF
        )
        val info = FirmwareInfo(
            version = version,
            pendingVerify = (value[3].toInt() and 0xFF) == 1,
            otaSlotSize = buffer.getInt(4),
            maxChunk = buffer.getInt(8)
        )
        Log.i(TAG, "Device firmware $version (pendingVerify=${info.pendingVerify})")
        _firmwareInfo.value = info
        otaTransfer?.onFirmwareInfo(info)
    }

    private fun markReady() {
        Log.i(TAG, "Bridge service ready")
        _state.value = BleConnectionState.READY
        onReady()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(mac: String, name: String, coroutineScope: CoroutineScope) {
        scope = coroutineScope
        otaTransfer = OtaTransferManager(coroutineScope)
        targetMac = mac
        targetName = name
        initiateConnect()
    }

    fun stop() {
        reconnectJob?.cancel()
        timeSyncJob?.cancel()
        scanCallback?.let {
            adapter()?.bluetoothLeScanner?.stopScan(it)
            scanCallback = null
        }
        gatt?.close()
        gatt = null
        otaTransfer = null
        _firmwareInfo.value = null
        _state.value = BleConnectionState.DISCONNECTED
    }

    fun sendMediaInfo(info: MediaInfo) {
        // Media updates are dropped while an image transfer owns the link.
        if (otaActive) return
        enqueue(mediaChar, info.toBytes())
    }

    // ── OtaGatt ────────────────────────────────────────────────────────────

    override val chunkSize: Int
        get() {
            // A write must fit both the negotiated MTU and the 512-octet attribute limit.
            val attPayload = minOf(negotiatedMtu - 3, OtaProtocol.MAX_ATTRIBUTE_LENGTH)
            val localLimit = attPayload - OtaProtocol.SEQUENCE_HEADER_LEN
            val deviceLimit = _firmwareInfo.value?.maxChunk ?: 0
            return when {
                localLimit < OtaProtocol.FALLBACK_CHUNK -> OtaProtocol.FALLBACK_CHUNK
                deviceLimit > 0 -> minOf(deviceLimit, localLimit)
                else -> localLimit
            }
        }

    override fun writeOtaControl(data: ByteArray): Boolean =
        enqueue(otaControlChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    override fun writeOtaData(data: ByteArray): Boolean = enqueue(otaDataChar, data)

    override fun setOtaActive(active: Boolean) {
        otaActive = active
        timeSyncJob?.let { if (active) it.cancel() }
        gatt?.requestConnectionPriority(
            if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        )
        if (!active) startTimeSync()
    }

    /** True when the connected device exposes the OTA characteristics. */
    val supportsOta: Boolean get() = otaControlChar != null && otaDataChar != null

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun onReady() {
        sendPowerUp()
        sendTimeNow()
        startTimeSync()
    }

    private fun startTimeSync() {
        timeSyncJob?.cancel()
        timeSyncJob = scope?.launch {
            while (isActive) {
                delay(BleConstants.TIME_SYNC_INTERVAL_MS)
                sendTimeNow()
            }
        }
    }

    private fun sendPowerUp() {
        try {
            // Send a single byte (0x01) to indicate power-up/initialization
            enqueue(powerUpChar, byteArrayOf(0x01))
            Log.i(TAG, "Power-up indication sent to ESP32")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send power-up indication", e)
        }
    }

    private fun sendTimeNow() {
        if (otaActive) return
        try {
            enqueue(timeChar, RdsClockTime.encode())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unable to encode RDS clock time", e)
        }
    }

    private fun enqueue(
        char: BluetoothGattCharacteristic?,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    ): Boolean {
        val c = char ?: return false
        if (_state.value != BleConnectionState.READY) return false
        writeQueue.add(PendingWrite(c, data, writeType))
        drainQueue()
        return true
    }

    private fun drainQueue() {
        if (writePending || writeQueue.isEmpty()) return
        val pending = writeQueue.poll() ?: return
        val g = gatt ?: return
        val char = pending.char
        val data = pending.data
        writePending = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = g.writeCharacteristic(char, data, pending.writeType)
                if (result != BluetoothStatusCodes.SUCCESS) {
                    Log.w(TAG, "Write rejected (code=$result, ${data.size} bytes) on ${char.uuid}")
                    writePending = false
                    otaTransfer?.onWriteRejected(result)
                    return
                }
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                char.writeType = pending.writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        } catch (e: DeadObjectException) {
            Log.w(TAG, "Gatt binder died during write; reconnecting", e)
            writePending = false
            handleGattWriteFailure()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Gatt write failed unexpectedly; reconnecting", e)
            writePending = false
            handleGattWriteFailure()
        }
    }

    private fun handleGattWriteFailure() {
        timeSyncJob?.cancel()
        mediaChar = null
        timeChar = null
        powerUpChar = null
        firmwareInfoChar = null
        otaControlChar = null
        otaDataChar = null
        writeQueue.clear()
        gatt?.close()
        gatt = null
        _state.value = BleConnectionState.DISCONNECTED
        otaTransfer?.onDisconnected()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            delay(BleConstants.RECONNECT_DELAY_MS)
            initiateConnect()
        }
    }

    private fun initiateConnect() {
        val a = adapter() ?: run { _state.value = BleConnectionState.ERROR; return }
        if (!a.isEnabled) { _state.value = BleConnectionState.ERROR; return }

        if (targetMac.isNotBlank()) {
            connectByMac(a, targetMac)
        } else if (targetName.isNotBlank()) {
            scanForDevice(a, targetName)
        }
    }

    private fun connectByMac(adapter: BluetoothAdapter, mac: String) {
        val device = try { adapter.getRemoteDevice(mac) }
        catch (_: IllegalArgumentException) {
            Log.e(TAG, "Invalid MAC: $mac")
            _state.value = BleConnectionState.ERROR
            return
        }
        _state.value = BleConnectionState.CONNECTING
        gatt?.close()
        gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scanForDevice(adapter: BluetoothAdapter, name: String) {
        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = BleConnectionState.ERROR; return
        }
        _state.value = BleConnectionState.SCANNING
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == name) {
                    scanner.stopScan(this)
                    scanCallback = null
                    targetMac = result.device.address
                    connectByMac(adapter, targetMac)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _state.value = BleConnectionState.ERROR
            }
        }
        scanCallback = cb
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb
        )
        scope?.launch {
            delay(BleConstants.SCAN_TIMEOUT_MS)
            if (_state.value == BleConnectionState.SCANNING) {
                scanner.stopScan(cb)
                scanCallback = null
                Log.w(TAG, "Scan timeout: '$name' not found")
                scheduleReconnect()
            }
        }
    }

    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
