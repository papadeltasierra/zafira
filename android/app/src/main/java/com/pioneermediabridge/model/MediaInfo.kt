package com.pioneermediabridge.model

sealed class MediaInfo {
    data class Radio(val stationId: String) : MediaInfo()
    data class Streaming(val artist: String, val track: String) : MediaInfo()
    data class CallOutgoing(val number: String, val name: String = "") : MediaInfo()
    data class CallIncoming(val number: String, val name: String = "") : MediaInfo()
    data object Idle : MediaInfo()

    companion object {
        private const val MEDIA_FIELD_CONTENT_MAX_BYTES = 61
        private val TRUNCATION_MARKER = "...".toByteArray(Charsets.UTF_8)
    }

    /**
     * Wire format: type byte + length-prefixed UTF-8 strings.
     * Each length is a byte count and does not include a terminator.
     * 0x00 = Radio:     stationIdLength, stationId
     * 0x01 = Streaming: artistLength, artist, trackLength, track
     * 0x02 = CallOut:   partyLength, party
     * 0x03 = CallIn:    partyLength, party
     * 0xFF = Idle
     */
    fun toBytes(): ByteArray = when (this) {
        is Radio -> byteArrayOf(0x00) + stationId.mediaField()
        is Streaming -> byteArrayOf(0x01) + artist.mediaField() + track.mediaField()
        is CallOutgoing -> byteArrayOf(0x02) + name.ifBlank { number }.mediaField()
        is CallIncoming -> byteArrayOf(0x03) + name.ifBlank { number }.mediaField()
        is Idle -> byteArrayOf(0xFF.toByte())
    }

    override fun toString(): String = when (this) {
        is Radio -> "Radio: $stationId"
        is Streaming -> "Streaming: $artist - $track"
        is CallOutgoing -> "Calling: $number${if (name.isNotEmpty()) " ($name)" else ""}"
        is CallIncoming -> "Incoming: $number${if (name.isNotEmpty()) " ($name)" else ""}"
        is Idle -> "Idle"
    }

    private fun String.mediaField(): ByteArray {
        val bytes = toByteArray(Charsets.UTF_8)
        if (bytes.size <= MEDIA_FIELD_CONTENT_MAX_BYTES) {
            return byteArrayOf(bytes.size.toByte()) + bytes
        }

        val prefixLimit = MEDIA_FIELD_CONTENT_MAX_BYTES - TRUNCATION_MARKER.size
        val prefix = StringBuilder()
        var index = 0
        var prefixBytes = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val characterBytes = character.toByteArray(Charsets.UTF_8)
            if (prefixBytes + characterBytes.size > prefixLimit) break
            prefix.append(character)
            prefixBytes += characterBytes.size
            index += Character.charCount(codePoint)
        }

        val truncated = prefix.toString().toByteArray(Charsets.UTF_8) + TRUNCATION_MARKER
        return byteArrayOf(truncated.size.toByte()) + truncated
    }
}
