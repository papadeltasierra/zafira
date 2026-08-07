package com.pioneermediabridge.parser

import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HciRecord(
    val timestampUs: Long,  // microseconds since Jan 1 year 0000 UTC
    val flags: Int,         // bit0=direction(0=host→ctrl,1=ctrl→host), bit1=cmd/event
    val data: ByteArray     // H4 framed: first byte is packet type indicator
) {
    val isFromController get() = (flags and 0x01) != 0
}

/**
 * Reads a btsnoop HCI log file and emits records as they are appended.
 * Requires read access to [filePath]; on non-rooted devices the file must be
 * on external storage (e.g. /sdcard/btsnoop_hci.log) with the standard
 * Android developer-options "Enable Bluetooth HCI snoop log" setting active.
 */
class BtSnoopReader(private val filePath: String) {

    companion object {
        private const val TAG = "BtSnoopReader"
        private val MAGIC = "btsnoop\u0000".toByteArray(Charsets.US_ASCII)
        private const val FILE_HDR = 16
        private const val REC_HDR = 24  // origLen(4)+inclLen(4)+flags(4)+drops(4)+ts(8)
    }

    fun records(): Flow<HciRecord> = callbackFlow {
        val file = File(filePath)
        if (!file.canRead()) {
            Log.e(TAG, "Cannot read '$filePath' – check path and MANAGE_EXTERNAL_STORAGE permission")
            close(SecurityException("Cannot read $filePath – check permissions and path"))
            return@callbackFlow
        }

        val raf = RandomAccessFile(file, "r")

        // Validate magic + version
        val hdr = ByteArray(FILE_HDR)
        if (raf.read(hdr) != FILE_HDR || !hdr.startsWith(MAGIC)) {
            Log.e(TAG, "'$filePath' is not a valid btsnoop file (bad magic bytes)")
            raf.close()
            close(IllegalArgumentException("Not a btsnoop file: $filePath"))
            return@callbackFlow
        }
        Log.i(TAG, "Opened btsnoop file '$filePath' (${file.length()} bytes); tailing from end")

        // Start tailing from current end so we only emit new records
        raf.seek(raf.length())
        var recordCount = 0L

        fun readNew() {
            var emitted = false
            while (true) {
                val mark = raf.filePointer
                val rh = ByteArray(REC_HDR)
                if (raf.read(rh) < REC_HDR) { raf.seek(mark); return }
                val bb = ByteBuffer.wrap(rh).order(ByteOrder.BIG_ENDIAN)
                bb.int                      // origLen (ignored)
                val inclLen = bb.int
                val flags = bb.int
                bb.int                      // drops
                val ts = bb.long

                if (inclLen <= 0 || inclLen > 65_536) { raf.seek(mark); return }
                val payload = ByteArray(inclLen)
                if (raf.read(payload) < inclLen) { raf.seek(mark); return }

                trySend(HciRecord(ts, flags, payload))
                recordCount++
                emitted = true
                if (recordCount == 1L) Log.i(TAG, "First HCI record received from '$filePath'")
                if (recordCount % 1_000L == 0L) Log.d(TAG, "$recordCount HCI records received")
            }
            if (emitted) Log.v(TAG, "readNew: batch complete, total=$recordCount")
        }

        // Watch for MODIFY events using the appropriate FileObserver API
        val observer: FileObserver = if (android.os.Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(file, MODIFY or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) = readNew()
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(filePath, MODIFY or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) = readNew()
            }
        }

        observer.startWatching()
        readNew() // catch anything already written since we computed raf.length()

        awaitClose {
            observer.stopWatching()
            raf.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
