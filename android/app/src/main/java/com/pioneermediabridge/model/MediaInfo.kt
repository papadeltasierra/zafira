package com.pioneermediabridge.model

sealed class MediaInfo {
    data class Radio(val stationId: String) : MediaInfo()
    data class Streaming(val artist: String, val track: String) : MediaInfo()
    data class CallOutgoing(val number: String, val name: String = "") : MediaInfo()
    data class CallIncoming(val number: String, val name: String = "") : MediaInfo()
    data object Idle : MediaInfo()

    /**
     * Wire format: type byte + null-terminated UTF-8 strings.
     * 0x00 = Radio:     stationId\0
     * 0x01 = Streaming: artist\0 track\0
     * 0x02 = CallOut:   number\0 name\0
     * 0x03 = CallIn:    number\0 name\0
     * 0xFF = Idle
     */
    fun toBytes(): ByteArray = when (this) {
        is Radio -> byteArrayOf(0x00) + stationId.nul()
        is Streaming -> byteArrayOf(0x01) + artist.nul() + track.nul()
        is CallOutgoing -> byteArrayOf(0x02) + number.nul() + name.nul()
        is CallIncoming -> byteArrayOf(0x03) + number.nul() + name.nul()
        is Idle -> byteArrayOf(0xFF.toByte())
    }

    override fun toString(): String = when (this) {
        is Radio -> "Radio: $stationId"
        is Streaming -> "Streaming: $artist – $track"
        is CallOutgoing -> "Calling: $number${if (name.isNotEmpty()) " ($name)" else ""}"
        is CallIncoming -> "Incoming: $number${if (name.isNotEmpty()) " ($name)" else ""}"
        is Idle -> "Idle"
    }

    private fun String.nul() = toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
}
