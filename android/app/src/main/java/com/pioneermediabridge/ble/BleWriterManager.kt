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
class BleWriterManager(private val context: Context) {

    private val TAG = "BleWriterManager"

    private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var mediaChar: BluetoothGattCharacteristic? = null
    private var timeChar: BluetoothGattCharacteristic? = null

    // Serialised write queue – only one outstanding write at a time
    private val writeQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writePending = false

    private var scope: CoroutineScope? = null
    private var timeSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private var scanCallback: ScanCallback? = null

    private var targetMac: String = ""
    private var targetName: String = ""

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services")
                    _state.value = BleConnectionState.DISCOVERING
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    mediaChar = null
                    timeChar = null
                    writeQueue.clear()
                    writePending = false
                    timeSyncJob?.cancel()
                    _state.value = BleConnectionState.DISCONNECTED
                    scheduleReconnect()
                }
            }
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
            if (mediaChar == null || timeChar == null) {
                Log.w(TAG, "Required characteristics missing")
                g.disconnect()
                return
            }
            Log.i(TAG, "Bridge service ready")
            _state.value = BleConnectionState.READY
            onReady()
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
            scope?.launch(Dispatchers.Main) {
                delay(BleConstants.WRITE_SETTLE_MS)
                drainQueue()
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(mac: String, name: String, coroutineScope: CoroutineScope) {
        scope = coroutineScope
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
        _state.value = BleConnectionState.DISCONNECTED
    }

    fun sendMediaInfo(info: MediaInfo) = enqueue(mediaChar, info.toBytes())

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun onReady() {
        sendTimeNow()
        timeSyncJob = scope?.launch {
            while (isActive) {
                delay(BleConstants.TIME_SYNC_INTERVAL_MS)
                sendTimeNow()
            }
        }
    }

    private fun sendTimeNow() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putLong(System.currentTimeMillis() / 1000L).array()
        enqueue(timeChar, buf)
    }

    private fun enqueue(char: BluetoothGattCharacteristic?, data: ByteArray) {
        val c = char ?: return
        if (_state.value != BleConnectionState.READY) return
        writeQueue.add(Pair(c, data))
        drainQueue()
    }

    private fun drainQueue() {
        if (writePending || writeQueue.isEmpty()) return
        val (char, data) = writeQueue.poll() ?: return
        val g = gatt ?: return
        writePending = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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
        writeQueue.clear()
        gatt?.close()
        gatt = null
        _state.value = BleConnectionState.DISCONNECTED
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
