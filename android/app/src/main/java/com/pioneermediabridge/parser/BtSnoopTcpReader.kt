package com.pioneermediabridge.parser

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a live btsnoop stream from a TCP connection.
 *
 * **Client mode** ([host] non-empty): connects to [host]:[port].
 * **Server mode** ([host] empty):     listens on [port] and accepts one source at a time.
 *
 * Advantages over file-watching:
 * - The blocking socket read naturally suspends the coroutine; no FileObserver/polling needed.
 * - The sender streams only live traffic so no log file grows on-device.
 * - Works across devices (e.g. a PC running btmon or Wireshark piping to the phone).
 *
 * The stream uses the identical btsnoop record format as the on-disk file so
 * [HciPacketDecoder] and all downstream parsers are unchanged.
 *
 * Typical setup (rooted device, btmon available):
 *   adb shell "btmon -w - | nc 127.0.0.1 8872"   # pipe to app in server mode
 * Or with the app as TCP client connecting to a PC-side btmon instance:
 *   PC:     btmon -w - | nc -l 8872
 *   Phone:  adb reverse tcp:8872 tcp:8872         # forward PC port to phone
 *
 * On disconnect the reader waits [RECONNECT_DELAY_MS] then reconnects automatically.
 */
class BtSnoopTcpReader(
    private val host: String,   // empty = server (listen) mode
    private val port: Int
) {
    companion object {
        private const val TAG = "BtSnoopTcpReader"
        private const val RECONNECT_DELAY_MS = 5_000L
        private val MAGIC = "btsnoop\u0000".toByteArray(Charsets.US_ASCII)
        private const val FILE_HDR_SIZE = 16
        private const val REC_HDR_SIZE = 24
    }

    fun records(): Flow<HciRecord> = flow {
        while (currentCoroutineContext().isActive) {
            var serverSock: ServerSocket? = null
            var clientSock: Socket? = null
            try {
                if (host.isNotEmpty()) {
                    Log.i(TAG, "Connecting to $host:$port")
                    clientSock = Socket(host, port)
                } else {
                    Log.i(TAG, "Listening on port $port")
                    serverSock = ServerSocket(port)
                    clientSock = serverSock.accept()
                    Log.i(TAG, "Source connected from ${clientSock.inetAddress}")
                }

                val stream = clientSock.getInputStream()
                validateHeader(stream)

                val recBuf = ByteArray(REC_HDR_SIZE)
                while (currentCoroutineContext().isActive) {
                    stream.readFully(recBuf)
                    val bb = ByteBuffer.wrap(recBuf).order(ByteOrder.BIG_ENDIAN)
                    bb.int                          // origLen (ignored)
                    val inclLen = bb.int
                    val flags = bb.int
                    bb.int                          // cumulative drops (ignored)
                    val ts = bb.long

                    if (inclLen <= 0 || inclLen > 65_536)
                        throw IOException("Implausible record length $inclLen")

                    val payload = ByteArray(inclLen)
                    stream.readFully(payload)
                    emit(HciRecord(ts, flags, payload))
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) return@flow
                Log.w(TAG, "Connection lost: ${e.message} – retrying in ${RECONNECT_DELAY_MS}ms")
                delay(RECONNECT_DELAY_MS)
            } finally {
                clientSock?.runCatching { close() }
                serverSock?.runCatching { close() }
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
            if (n < 0) throw IOException("Stream ended unexpectedly")
            offset += n
        }
    }
}
