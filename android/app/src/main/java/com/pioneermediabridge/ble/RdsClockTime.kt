package com.pioneermediabridge.ble

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Encodes the RDS Clock-Time date, UTC time, and local-time offset fields. */
internal object RdsClockTime {
    const val PAYLOAD_LENGTH = 5

    private val MJD_EPOCH: LocalDate = LocalDate.of(1858, 11, 17)

    fun encode(instant: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): ByteArray {
        val utc = instant.atZone(ZoneOffset.UTC)
        val modifiedJulianDate = ChronoUnit.DAYS.between(MJD_EPOCH, utc.toLocalDate())
        require(modifiedJulianDate in 0L..0x1FFFFL) { "MJD is outside the RDS 17-bit range" }
        val mjd = modifiedJulianDate.toInt()

        val offsetMinutes = instant.atZone(zoneId).offset.totalSeconds / 60
        require(offsetMinutes % 30 == 0) {
            "RDS cannot represent a local UTC offset not divisible by 30 minutes"
        }
        val offsetHalfHours = abs(offsetMinutes) / 30
        require(offsetHalfHours <= 0x1F) { "UTC offset is outside the RDS 5-bit range" }

        val offsetSign = if (offsetMinutes < 0) 1 else 0
        return byteArrayOf(
            (mjd shr 9).toByte(),
            (mjd shr 1).toByte(),
            (((mjd and 0x01) shl 7) or (utc.hour shl 2) or (utc.minute shr 4)).toByte(),
            (((utc.minute and 0x0F) shl 4) or (offsetSign shl 3) or (offsetHalfHours shr 2)).toByte(),
            ((offsetHalfHours and 0x03) shl 6).toByte(),
        )
    }
}