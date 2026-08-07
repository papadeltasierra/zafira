package com.pioneermediabridge.parser

import android.util.Log
import com.pioneermediabridge.model.MediaInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses SDL RPC frames to extract Pioneer media state.
 *
 * SDL RPC payload layout (within an RPC-service SDL frame):
 *   Bytes 0–3: (rpcType:4 upper bits)(functionId:28 lower bits) — big-endian
 *   Bytes 4–7: correlationId (big-endian uint32)
 *   Bytes 8–11: jsonSize (big-endian uint32)
 *   Bytes 12–15: binaryHeaderSize (big-endian uint32, usually 0)
 *   Bytes 16 .. 16+jsonSize: JSON payload
 *
 * Key function IDs (SDL specification):
 *   0x0D (13)     = Show             — app displays text on HU
 *   0x0F (15)     = SetMediaClockTimer
 *   0x28 (40)     = DialNumber       — outgoing call request
 *   0x8000 (32768)= OnHMIStatus      — HMI state notification
 */
class PioneerMediaParser {

    companion object {
        private const val TAG = "PioneerMediaParser"

        private const val FN_SHOW = 0x0D
        private const val FN_DIAL_NUMBER = 0x28
        private const val FN_ON_HMI_STATUS = 0x8000

        private const val RPC_TYPE_REQUEST = 0
        private const val RPC_TYPE_RESPONSE = 1
        private const val RPC_TYPE_NOTIFICATION = 2

        private const val RPC_PAYLOAD_HDR = 16

        // Regex patterns for identifying radio station IDs in display fields
        private val STATION_REGEX = Regex(
            """^\d{2,3}[.,]\d\s*(FM|AM|DAB|MW|LW|SW)|^[A-Z]{2,10}\s*(FM|AM|DAB)|^\d+\s*(kHz|MHz)""",
            RegexOption.IGNORE_CASE
        )
    }

    // Track HMI context to help distinguish radio vs streaming
    private var currentHmiLevel = "NONE"
    private var currentSystemContext = "MAIN"
    private var currentAudioState = "NOT_AUDIBLE"

    // Last Show fields for context-aware classification
    private var lastMainField1 = ""
    private var lastMainField2 = ""

    fun parse(frames: Flow<SdlFrame>): Flow<MediaInfo> = flow {
        frames.collect { frame ->
            if (frame.serviceType != SdlServiceType.RPC) return@collect
            val media = parseRpcFrame(frame.payload) ?: return@collect
            Log.i(TAG, "Media detected: $media")
            emit(media)
        }
    }

    private fun parseRpcFrame(data: ByteArray): MediaInfo? {
        if (data.size < RPC_PAYLOAD_HDR) return null

        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val typeAndFn = bb.int
        val rpcType = (typeAndFn ushr 28) and 0x0F
        val functionId = typeAndFn and 0x0FFF_FFFF
        val correlationId = bb.int
        val jsonSize = bb.int
        bb.int // binaryHeaderSize

        val rpcTypeName = when (rpcType) { 0 -> "Req"; 1 -> "Rsp"; 2 -> "Ntf"; 3 -> "Err"; else -> rpcType.toString() }
        val fnName = when (functionId) {
            FN_SHOW -> "Show"; FN_DIAL_NUMBER -> "DialNumber"; FN_ON_HMI_STATUS -> "OnHMIStatus"; else -> "0x${functionId.toString(16)}"
        }
        Log.d(TAG, "SDL RPC $rpcTypeName $fnName corrId=$correlationId jsonSize=$jsonSize")

        if (jsonSize <= 0 || data.size < RPC_PAYLOAD_HDR + jsonSize) return null

        val jsonStr = String(data, RPC_PAYLOAD_HDR, jsonSize, Charsets.UTF_8)
        val json = try { JSONObject(jsonStr) } catch (e: Exception) {
            Log.w(TAG, "JSON parse error fn=$fnName: ${e.message} | raw=${jsonStr.take(120)}")
            return null
        }

        return when (functionId) {
            FN_SHOW -> handleShow(json)
            FN_DIAL_NUMBER -> handleDialNumber(json)
            FN_ON_HMI_STATUS -> { handleHmiStatus(json); null }
            else -> {
                Log.d(TAG, "Unhandled RPC fn=0x${functionId.toString(16)} – raw JSON: ${jsonStr.take(200)}")
                null
            }
        }
    }

    private fun handleShow(json: JSONObject): MediaInfo? {
        val field1 = json.optString("mainField1", "").trim()
        val field2 = json.optString("mainField2", "").trim()
        val mediaTrack = json.optString("mediaTrack", "").trim()
        Log.d(TAG, "Show: field1='$field1' field2='$field2' mediaTrack='$mediaTrack'")

        var artist = ""
        var title = ""
        var isRadio = false
        var stationId = ""

        val metaTags = json.optJSONObject("metadataTags")
        if (metaTags != null) {
            // metadataTags maps field name → array of metadata type strings
            val field1Types = metaTags.optJSONArray("mainField1")
            val field2Types = metaTags.optJSONArray("mainField2")
            for (i in 0 until (field1Types?.length() ?: 0)) {
                when (field1Types?.getString(i)) {
                    "mediaArtist" -> artist = field1
                    "mediaStation" -> { isRadio = true; stationId = field1 }
                }
            }
            for (i in 0 until (field2Types?.length() ?: 0)) {
                when (field2Types?.getString(i)) {
                    "mediaTitle" -> title = field2
                    "mediaStation" -> { isRadio = true; stationId = field2 }
                }
            }
        }

        // Fall back to heuristic classification if no metadata tags
        if (artist.isEmpty() && title.isEmpty() && !isRadio) {
            when {
                field1.matches(STATION_REGEX) -> {
                    isRadio = true
                    stationId = field1
                }
                field2.matches(STATION_REGEX) -> {
                    isRadio = true
                    stationId = field2
                }
                field1.isNotEmpty() && field2.isNotEmpty() -> {
                    artist = field1
                    title = if (mediaTrack.isNotEmpty()) mediaTrack else field2
                }
                mediaTrack.isNotEmpty() -> {
                    title = mediaTrack
                }
            }
        }

        lastMainField1 = field1
        lastMainField2 = field2

        val result = when {
            isRadio && stationId.isNotEmpty() -> MediaInfo.Radio(stationId)
            artist.isNotEmpty() || title.isNotEmpty() -> MediaInfo.Streaming(artist, title)
            else -> {
                if (field1.isNotEmpty() || field2.isNotEmpty())
                    Log.w(TAG, "Show: could not classify fields – field1='$field1' field2='$field2'; check metadataTags or station regex")
                null
            }
        }
        if (result != null) Log.d(TAG, "Show classified as: $result")
        return result
    }

    private fun handleDialNumber(json: JSONObject): MediaInfo? {
        val number = json.optString("number", "").trim()
        return if (number.isNotEmpty()) MediaInfo.CallOutgoing(number) else null
    }

    private fun handleHmiStatus(json: JSONObject) {
        currentHmiLevel = json.optString("hmiLevel", currentHmiLevel)
        currentSystemContext = json.optString("systemContext", currentSystemContext)
        currentAudioState = json.optString("audioStreamingState", currentAudioState)
        Log.d(TAG, "HMI: level=$currentHmiLevel ctx=$currentSystemContext audio=$currentAudioState")
    }
}
