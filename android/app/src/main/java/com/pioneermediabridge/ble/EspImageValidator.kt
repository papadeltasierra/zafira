package com.pioneermediabridge.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class FirmwareImage(
    val displayName: String,
    val bytes: ByteArray,
    val version: SemVer,
    val versionText: String,
    val projectName: String,
    val sha256: ByteArray,
    /** True when the file carried no parsable version and was defaulted to 0.0.0. */
    val versionAssumed: Boolean
) {
    val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface ImageValidation {
    data class Valid(val image: FirmwareImage) : ImageValidation
    data class Invalid(val reason: String) : ImageValidation
}

/**
 * Validates an ESP-IDF application image locally, before any BLE contact.
 * Layout: esp_image_header_t (24 B), esp_image_segment_header_t (8 B), esp_app_desc_t (256 B).
 */
object EspImageValidator {

    private const val IMAGE_MAGIC = 0xE9.toByte()
    private const val CHIP_ID_ESP32S3 = 9
    private const val APP_DESC_OFFSET = 0x20
    private const val APP_DESC_MAGIC = 0xABCD5432.toInt()
    private const val VERSION_OFFSET = APP_DESC_OFFSET + 0x10
    private const val PROJECT_NAME_OFFSET = APP_DESC_OFFSET + 0x30
    private const val STRING_FIELD_LEN = 32
    private const val HASH_APPENDED_OFFSET = 23
    private const val MIN_IMAGE_SIZE = 1024

    const val EXPECTED_PROJECT_NAME = "zafira_ble_endpoint"

    fun validate(
        displayName: String,
        bytes: ByteArray,
        maxSize: Int? = null
    ): ImageValidation {
        if (bytes.size < MIN_IMAGE_SIZE) {
            return ImageValidation.Invalid("File is only ${bytes.size} bytes; not a firmware image.")
        }
        if (maxSize != null && bytes.size > maxSize) {
            return ImageValidation.Invalid(
                "Image is ${bytes.size} bytes but the device OTA slot holds $maxSize bytes."
            )
        }
        if (bytes[0] != IMAGE_MAGIC) {
            return ImageValidation.Invalid("Missing ESP image magic byte 0xE9.")
        }

        val chipId = readU16(bytes, 12)
        if (chipId != CHIP_ID_ESP32S3) {
            return ImageValidation.Invalid("Image targets chip id $chipId, expected $CHIP_ID_ESP32S3 (ESP32-S3).")
        }

        if (readI32(bytes, APP_DESC_OFFSET) != APP_DESC_MAGIC) {
            return ImageValidation.Invalid("Application descriptor magic not found; file is not an app image.")
        }

        val projectName = readFixedString(bytes, PROJECT_NAME_OFFSET)
        if (projectName != EXPECTED_PROJECT_NAME) {
            return ImageValidation.Invalid(
                "Image is for project '$projectName', expected '$EXPECTED_PROJECT_NAME'."
            )
        }

        if (bytes[HASH_APPENDED_OFFSET].toInt() == 1) {
            if (bytes.size < 32) {
                return ImageValidation.Invalid("Image claims an appended hash but is too short.")
            }
            val embedded = bytes.copyOfRange(bytes.size - 32, bytes.size)
            val computed = sha256(bytes, 0, bytes.size - 32)
            if (!embedded.contentEquals(computed)) {
                return ImageValidation.Invalid("Appended SHA-256 does not match the image contents; file is corrupt.")
            }
        }

        val versionText = readFixedString(bytes, VERSION_OFFSET)
        val parsed = SemVer.parseOrNull(versionText)

        return ImageValidation.Valid(
            FirmwareImage(
                displayName = displayName,
                bytes = bytes,
                version = parsed ?: SemVer.ZERO,
                versionText = versionText,
                projectName = projectName,
                sha256 = sha256(bytes, 0, bytes.size),
                versionAssumed = parsed == null
            )
        )
    }

    private fun sha256(data: ByteArray, offset: Int, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(data, offset, length) }.digest()

    private fun readU16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun readI32(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readFixedString(data: ByteArray, offset: Int): String {
        val end = (offset until offset + STRING_FIELD_LEN).firstOrNull { data[it].toInt() == 0 }
            ?: (offset + STRING_FIELD_LEN)
        return String(data, offset, end - offset, Charsets.UTF_8)
    }
}
