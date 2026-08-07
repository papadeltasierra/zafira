package com.pioneermediabridge.model

data class AppSettings(
    val pioneerMac: String = "",
    val pioneerName: String = "",
    val outputBleMac: String = "",
    val outputBleName: String = "",
    val snoopFilePath: String = DEFAULT_SNOOP_PATH
) {
    companion object {
        const val DEFAULT_SNOOP_PATH = "/sdcard/btsnoop_hci.log"

        val CANDIDATE_PATHS = listOf(
            "/sdcard/btsnoop_hci.log",
            "/storage/emulated/0/btsnoop_hci.log",
            "/data/misc/bluetooth/logs/btsnoop_hci.log"
        )
    }

    val isConfigured: Boolean
        get() = (pioneerMac.isNotBlank() || pioneerName.isNotBlank()) &&
                (outputBleMac.isNotBlank() || outputBleName.isNotBlank())

    /** Pioneer BD_ADDR as 6-byte little-endian array for HCI event matching. */
    val pioneerMacBytes: ByteArray?
        get() {
            if (pioneerMac.isBlank()) return null
            val parts = pioneerMac.trim().split(":")
            if (parts.size != 6) return null
            return try {
                parts.map { it.toInt(16).toByte() }.reversed().toByteArray()
            } catch (_: NumberFormatException) {
                null
            }
        }
}
