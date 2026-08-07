package com.pioneermediabridge.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the live btsnoop HCI stream from the Android TCP snoop logger endpoint
 * exposed when "Enable Bluetooth HCI snoop socket" is turned on (Android 16 / API 36+).
 *
 * This is the preferred ingestion method over file-watching because:
 *  - No log file grows on the device
 *  - A blocking socket read naturally suspends the coroutine; no FileObserver needed
 *  - No MANAGE_EXTERNAL_STORAGE permission required
 *  - Fully on-device: no ADB, no external tools, no other devices
 *
 * The endpoint defaults to localhost:8872 based on observed Pixel GD Bluetooth behavior.
 */
class BtSnoopSocketReader(
    private val host: String = DEFAULT_TCP_HOST,
    private val port: Int = DEFAULT_TCP_PORT
) {
    companion object {
        private const val TAG = "BtSnoopSocketReader"
        private const val DEFAULT_TCP_HOST = "127.0.0.1"
        private const val DEFAULT_TCP_PORT = 8872
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val RECONNECT_DELAY_MS = 3_000L
        private val MAGIC = "btsnoop\u0000".toByteArray(Charsets.US_ASCII)
        private const val FILE_HDR_SIZE = 16
        private const val REC_HDR_SIZE = 24
    }

    fun records(): Flow<HciRecord> = flow {
        while (currentCoroutineContext().isActive) {
            var tcpSocket: Socket? = null
            try {
                tcpSocket = Socket()
                tcpSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val activeStream = tcpSocket.getInputStream()
                Log.i(TAG, "Connected to btsnoop TCP socket $host:$port")
                validateHeader(activeStream)

                val recBuf = ByteArray(REC_HDR_SIZE)
                while (currentCoroutineContext().isActive) {
                    activeStream.readFully(recBuf)
                    val bb = ByteBuffer.wrap(recBuf).order(ByteOrder.BIG_ENDIAN)
                    bb.int                         // origLen
                    val inclLen = bb.int
                    val flags = bb.int
                    bb.int                         // drops
                    val ts = bb.long

                    if (inclLen <= 0 || inclLen > 65_536)
                        throw IOException("Implausible record length: $inclLen")

                    val payload = ByteArray(inclLen)
                    activeStream.readFully(payload)
                    emit(HciRecord(ts, flags, payload))
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) return@flow
                Log.w(TAG, "Socket 'tcp:$host:$port': ${e.message} – retrying in ${RECONNECT_DELAY_MS}ms")
                delay(RECONNECT_DELAY_MS)
            } finally {
                tcpSocket?.runCatching { close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun validateHeader(stream: InputStream) {
        val hdr = ByteArray(FILE_HDR_SIZE)
        stream.readFully(hdr)
        for (i in MAGIC.indices) {
            if (hdr[i] != MAGIC[i]) throw IOException("Not a btsnoop stream (bad magic)")
        }
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Socket closed")
            offset += n
        }
    }
}
