package com.pioneermediabridge.parser

import android.util.Log
import com.pioneermediabridge.model.MediaInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Parses Pioneer Smart Sync media updates from classic Bluetooth RFCOMM traffic.
 *
 * Pipeline:
 * HCI ACL -> L2CAP PDU -> RFCOMM UIH payload -> Smart Sync frame (0x9F 0x02 ... 0x9F 0x03)
 */
class ClassicSmartSyncParser(
    private val pioneerMacBytes: ByteArray?,
    private val excludedMacBytes: ByteArray? = null
) {

    companion object {
        private const val TAG = "ClassicSmartSyncParser"

        private const val H4_ACL = 0x02
        private const val H4_EVT = 0x04

        private const val EVT_CONNECTION_COMPLETE = 0x03
        private const val EVT_DISCONNECT_COMPLETE = 0x05

        private const val PB_FIRST = 0x02
        private const val PB_CONT = 0x01
        private const val PB_FIRST_NF = 0x00

        private const val RFCOMM_UIH = 0xEF
    }

    private data class L2capAssembly(
        val expectedSize: Int,
        val cid: Int,
        val buffer: ByteArray,
        var filled: Int
    )

    private val trackedHandles = HashSet<Int>()
    private val l2capAssemblies = HashMap<Int, L2capAssembly>()

    private var streamingArtist = ""
    private var streamingTrack = ""
    private var streamingAlbum = ""
    private var streamingGenre = ""

    private var radioStation = ""
    private var radioText = ""
    private var radioProgrammeType = ""
    private var radioSignal = ""

    fun parse(records: Flow<HciRecord>): Flow<MediaInfo> = flow {
        pioneerMacBytes?.let {
            Log.i(TAG, "Looking for Pioneer classic connection: ${it.toMacString()}")
        } ?: Log.w(TAG, "No Pioneer MAC configured - matching Smart Sync frames on classic links")

        excludedMacBytes?.let {
            Log.i(TAG, "Excluding output BLE MAC from classic tracking: ${it.toMacString()}")
        }

        records.collect { record ->
            if (record.data.isEmpty()) return@collect

            when (record.data[0].toInt() and 0xFF) {
                H4_EVT -> handleEvent(record.data)
                H4_ACL -> {
                    val parsed = handleAcl(record.data)
                    if (parsed != null) emit(parsed)
                }
            }
        }
    }

    private fun handleEvent(data: ByteArray) {
        if (data.size < 3) return
        when (data[1].toInt() and 0xFF) {
            EVT_CONNECTION_COMPLETE -> handleClassicConnectionComplete(data)
            EVT_DISCONNECT_COMPLETE -> handleDisconnect(data)
        }
    }

    private fun handleClassicConnectionComplete(data: ByteArray) {
        // status(1), handle(2), bd_addr(6), link_type(1), enc_mode(1)
        if (data.size < 14) return
        val status = data[3].toInt() and 0xFF
        if (status != 0x00) return

        val handle = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0x0F) shl 8)
        val addr = data.copyOfRange(6, 12)
        val addrStr = addr.toMacString()

        if (excludedMacBytes != null && addr.contentEquals(excludedMacBytes)) {
            Log.d(TAG, "Classic connection addr=$addrStr handle=0x${handle.toString(16).padStart(4, '0')} excluded (output BLE device)")
            return
        }

        if (pioneerMacBytes == null || addr.contentEquals(pioneerMacBytes)) {
            trackedHandles.add(handle)
            Log.i(TAG, "Tracking classic connection: addr=$addrStr handle=0x${handle.toString(16).padStart(4, '0')}")
        }
    }

    private fun handleDisconnect(data: ByteArray) {
        if (data.size < 6) return
        val handle = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0x0F) shl 8)
        trackedHandles.remove(handle)
        l2capAssemblies.remove(handle)
    }

    private fun handleAcl(data: ByteArray): MediaInfo? {
        if (data.size < 5) return null

        val handleAndFlags = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val connHandle = handleAndFlags and 0x0FFF
        val pbFlag = (handleAndFlags shr 12) and 0x03
        val isTracked = trackedHandles.contains(connHandle)

        val payload = data.copyOfRange(5, data.size)
        if (payload.isEmpty()) return null

        return when (pbFlag) {
            PB_FIRST, PB_FIRST_NF -> handleFirstAclFragment(connHandle, payload, isTracked)
            PB_CONT -> if (isTracked) handleContinuationAclFragment(connHandle, payload) else null
            else -> null
        }
    }

    private fun handleFirstAclFragment(connHandle: Int, payload: ByteArray, isTracked: Boolean): MediaInfo? {
        if (payload.size < 4) return null

        val l2capLen = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val cid = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
        val body = payload.copyOfRange(4, payload.size)

        if (!isTracked) {
            if (!looksLikeSmartSyncCarrier(body)) return null
            trackedHandles.add(connHandle)
            Log.i(TAG, "Recovered classic handle from RFCOMM payload: handle=0x${connHandle.toString(16).padStart(4, '0')}")
        }

        if (l2capLen <= 0 || l2capLen > 4096) return null

        if (body.size >= l2capLen) {
            val pdu = body.copyOfRange(0, l2capLen)
            return parseL2capPdu(pdu, cid)
        }

        val assembly = L2capAssembly(
            expectedSize = l2capLen,
            cid = cid,
            buffer = ByteArray(l2capLen),
            filled = body.size
        )
        body.copyInto(assembly.buffer, 0, 0, body.size)
        l2capAssemblies[connHandle] = assembly
        return null
    }

    private fun handleContinuationAclFragment(connHandle: Int, payload: ByteArray): MediaInfo? {
        val assembly = l2capAssemblies[connHandle] ?: return null
        if (assembly.filled >= assembly.expectedSize) return null

        val remaining = assembly.expectedSize - assembly.filled
        val toCopy = minOf(remaining, payload.size)
        payload.copyInto(assembly.buffer, assembly.filled, 0, toCopy)
        assembly.filled += toCopy

        if (assembly.filled < assembly.expectedSize) return null

        l2capAssemblies.remove(connHandle)
        return parseL2capPdu(assembly.buffer, assembly.cid)
    }

    private fun looksLikeSmartSyncCarrier(l2capPayload: ByteArray): Boolean {
        if (l2capPayload.size < 6) return false
        val control = l2capPayload[1].toInt() and 0xFF
        // RFCOMM UIH frames use control 0xEF (P/F bit may vary in some captures).
        if ((control and 0xEF) != RFCOMM_UIH) return false
        return l2capPayload.indexOfSequence(byteArrayOf(0x9F.toByte(), 0x02)) >= 0
    }

    private fun parseL2capPdu(pdu: ByteArray, cid: Int): MediaInfo? {
        val rfcommPayload = extractRfcommPayload(pdu) ?: return null
        val smartSync = extractSmartSyncPayload(rfcommPayload) ?: return null

        val media = parseSmartSyncMedia(smartSync)
        if (media != null) {
            Log.d(TAG, "Classic Smart Sync media on cid=0x${cid.toString(16)}: $media")
        }
        return media
    }

    private fun extractRfcommPayload(l2capPayload: ByteArray): ByteArray? {
        if (l2capPayload.size < 5) return null

        val control = l2capPayload[1].toInt() and 0xFF
        if (control != RFCOMM_UIH) return null

        val lengthByte = l2capPayload[2].toInt() and 0xFF
        val hasOneByteLength = (lengthByte and 0x01) == 0x01

        val payloadLength: Int
        val headerSize: Int
        if (hasOneByteLength) {
            payloadLength = lengthByte shr 1
            headerSize = 3
        } else {
            if (l2capPayload.size < 6) return null
            val lengthByte2 = l2capPayload[3].toInt() and 0xFF
            payloadLength = (lengthByte shr 1) or (lengthByte2 shl 7)
            headerSize = 4
        }

        // RFCOMM UIH frame includes one-byte FCS trailer.
        if (payloadLength <= 0 || l2capPayload.size < headerSize + payloadLength + 1) return null
        return l2capPayload.copyOfRange(headerSize, headerSize + payloadLength)
    }

    private fun extractSmartSyncPayload(rfcommPayload: ByteArray): ByteArray? {
        val start = rfcommPayload.indexOfSequence(byteArrayOf(0x9F.toByte(), 0x02))
        if (start < 0) return null

        val end = rfcommPayload.indexOfSequence(byteArrayOf(0x9F.toByte(), 0x03), start + 2)
        if (end < 0) return null

        return rfcommPayload.copyOfRange(start, end + 2)
    }

    private fun parseSmartSyncMedia(payload: ByteArray): MediaInfo? {
        if (payload.size < 8) return null
        val messageType = payload[2].toInt() and 0xFF

        val strings = extractPrintableStrings(payload)
        val primaryText = strings.maxByOrNull { it.length }?.trim().orEmpty()
        if (primaryText.isEmpty()) {
            Log.v(
                TAG,
                "Smart Sync frame has no printable media text: type=0x${messageType.toString(16)} len=${payload.size}"
            )
            return null
        }

        val subtype = payload.getOrNull(6)?.toInt()?.and(0xFF)
        Log.d(
            TAG,
            "Smart Sync frame type=0x${messageType.toString(16)}" +
                " subtype=${subtype?.let { "0x${it.toString(16)}" } ?: "n/a"}" +
                " text='${primaryText.take(80)}'"
        )

        return when (messageType) {
            0x31 -> {
                when (subtype) {
                    0x00 -> radioStation = primaryText
                    0x01 -> radioText = primaryText
                    0x02 -> radioProgrammeType = primaryText
                    0x03 -> radioSignal = primaryText
                    else -> {
                        Log.v(TAG, "Unhandled radio subtype=${subtype?.let { "0x${it.toString(16)}" } ?: "n/a"}")
                        return null
                    }
                }
                Log.d(TAG, "Classified as radio detail update")
                MediaInfo.Radio(radioStation, radioText, radioProgrammeType, radioSignal)
            }
            0x32 -> {
                val subtypeValue = subtype ?: return null
                when (subtype) {
                    0x00 -> streamingTrack = primaryText
                    0x01 -> streamingArtist = primaryText
                    0x02 -> streamingAlbum = primaryText
                    0x03 -> {
                        streamingGenre = primaryText
                    }
                    else -> {
                        Log.v(
                            TAG,
                            "Unhandled Smart Sync 0x32 subtype=0x${subtypeValue.toString(16)} text='${primaryText.take(80)}'"
                        )
                        null
                    }
                }
                Log.d(
                    TAG,
                    "Classified as streaming detail update: artist='${streamingArtist.take(80)}' " +
                        "track='${streamingTrack.take(80)}'"
                )
                MediaInfo.Streaming(streamingArtist, streamingTrack, streamingAlbum, streamingGenre)
            }
            else -> {
                Log.v(TAG, "Unhandled Smart Sync message type=0x${messageType.toString(16)}")
                null
            }
        }
    }

    private fun extractPrintableStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.length >= 3) out.add(current.toString())
            current.clear()
        }

        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                flush()
            }
        }
        flush()
        return out
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray, startIndex: Int = 0): Int {
        if (sequence.isEmpty() || size < sequence.size) return -1
        for (i in startIndex..(size - sequence.size)) {
            var match = true
            for (j in sequence.indices) {
                if (this[i + j] != sequence[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}

private fun ByteArray.toMacString(): String =
    reversed().joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
