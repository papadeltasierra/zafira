package com.pioneermediabridge.parser

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A fully reassembled SDL (SmartDeviceLink) message.
 *
 * SDL BLE frame header (12 bytes):
 *   Byte 0: (version:4)(encryption:1)(frameType:3)
 *   Byte 1: serviceType
 *   Byte 2: frameInfo
 *   Byte 3: sessionId
 *   Bytes 4–7: dataSize (big-endian uint32)
 *   Bytes 8–11: messageId (big-endian uint32)
 *   Bytes 12+: payload
 */
data class SdlFrame(
    val version: Int,
    val frameType: Int,
    val serviceType: Int,
    val frameInfo: Int,
    val sessionId: Int,
    val messageId: Int,
    val payload: ByteArray,
    val fromController: Boolean
)

object SdlFrameType {
    const val CONTROL = 0
    const val SINGLE = 1
    const val FIRST = 2
    const val CONSECUTIVE = 3
}

object SdlServiceType {
    const val CONTROL = 0x00
    const val RPC = 0x07
    const val AUDIO = 0x0A
    const val VIDEO = 0x0B
    const val HYBRID = 0x0F
}

/**
 * Reassembles SDL frames from ATT write/notification payloads.
 *
 * SDL multi-frame messages arrive as a FIRST frame (containing total payload size)
 * followed by CONSECUTIVE frames. This class buffers them per (sessionId, messageId)
 * and emits only complete messages.
 *
 * Heuristic SDL detection: byte 0 upper nibble must be 1 or 2 (SDL protocol version),
 * byte 1 must be a known service type. This avoids needing GATT handle discovery.
 */
class SdlFrameAssembler {

    companion object {
        private const val TAG = "SdlFrameAssembler"
        private const val FRAME_HDR = 12
        private val KNOWN_SERVICE_TYPES = setOf(
            SdlServiceType.CONTROL,
            SdlServiceType.RPC,
            SdlServiceType.AUDIO,
            SdlServiceType.VIDEO,
            SdlServiceType.HYBRID
        )
    }

    // Reassembly state keyed by (sessionId, messageId)
    private data class AssemblyKey(val sessionId: Int, val messageId: Int, val fromController: Boolean)
    private data class AssemblyState(
        val version: Int,
        val serviceType: Int,
        val sessionId: Int,
        val messageId: Int,
        val totalSize: Int,
        val buffer: ByteArray,
        var filled: Int = 0,
        val fromController: Boolean
    )

    private val inProgress = HashMap<AssemblyKey, AssemblyState>()

    fun assemble(attFlow: Flow<AttPayload>): Flow<SdlFrame> = flow {
        attFlow.collect { att ->
            val data = att.value
            if (data.size < FRAME_HDR) return@collect

            val b0 = data[0].toInt() and 0xFF
            val version = (b0 shr 4) and 0x0F
            if (version < 1 || version > 5) {
                Log.v(TAG, "ATT payload version nibble=0x${(b0 shr 4).toString(16)} not SDL, skipping ${data.size}B")
                return@collect
            }

            val frameType = b0 and 0x07
            val serviceType = data[1].toInt() and 0xFF
            if (serviceType !in KNOWN_SERVICE_TYPES) {
                Log.v(TAG, "Unknown SDL service type=0x${serviceType.toString(16)}, skipping")
                return@collect
            }

            val frameInfo = data[2].toInt() and 0xFF
            val sessionId = data[3].toInt() and 0xFF

            val bb = ByteBuffer.wrap(data, 4, 8).order(ByteOrder.BIG_ENDIAN)
            val dataSize = bb.int
            val messageId = bb.int

            val payloadData = if (data.size > FRAME_HDR) data.copyOfRange(FRAME_HDR, data.size)
            else ByteArray(0)

            when (frameType) {
                SdlFrameType.SINGLE -> {
                    Log.d(TAG, "SDL SINGLE v$version svc=0x${serviceType.toString(16)} session=$sessionId msgId=$messageId ${payloadData.size}B")
                    emit(
                        SdlFrame(version, frameType, serviceType, frameInfo,
                            sessionId, messageId, payloadData, att.fromController)
                    )
                }
                SdlFrameType.FIRST -> {
                    if (dataSize <= 0 || dataSize > 512 * 1024) {
                        Log.w(TAG, "SDL FIRST frame with implausible dataSize=$dataSize – discarding")
                        return@collect
                    }
                    Log.d(TAG, "SDL FIRST v$version svc=0x${serviceType.toString(16)} session=$sessionId msgId=$messageId totalSize=$dataSize")
                    val buf = ByteArray(dataSize)
                    payloadData.copyInto(buf, 0, 0, minOf(payloadData.size, dataSize))
                    val key = AssemblyKey(sessionId, messageId, att.fromController)
                    inProgress[key] = AssemblyState(
                        version, serviceType, sessionId, messageId,
                        dataSize, buf, minOf(payloadData.size, dataSize), att.fromController
                    )
                }
                SdlFrameType.CONSECUTIVE -> {
                    val key = AssemblyKey(sessionId, messageId, att.fromController)
                    val state = inProgress[key] ?: run {
                        Log.w(TAG, "SDL CONSECUTIVE without matching FIRST (session=$sessionId msgId=$messageId) – discarding")
                        return@collect
                    }
                    val remaining = state.totalSize - state.filled
                    val toCopy = minOf(payloadData.size, remaining)
                    payloadData.copyInto(state.buffer, state.filled, 0, toCopy)
                    state.filled += toCopy

                    if (state.filled >= state.totalSize) {
                        inProgress.remove(key)
                        Log.d(TAG, "SDL multi-frame complete: svc=0x${state.serviceType.toString(16)} session=$sessionId msgId=$messageId ${state.totalSize}B")
                        emit(
                            SdlFrame(state.version, SdlFrameType.SINGLE, state.serviceType,
                                0, state.sessionId, state.messageId,
                                state.buffer, state.fromController)
                        )
                    }
                }
                SdlFrameType.CONTROL -> {
                    Log.d(TAG, "SDL CONTROL v$version svc=0x${serviceType.toString(16)} frameInfo=0x${frameInfo.toString(16)}")
                    emit(
                        SdlFrame(version, frameType, serviceType, frameInfo,
                            sessionId, messageId, payloadData, att.fromController)
                    )
                }
            }
        }
    }
}
