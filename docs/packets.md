# Android <-> ESP32-S3 Packet Exchange

This document describes the BLE frames exchanged between the Android app and the ESP32-S3 app, using Mermaid packet diagrams.

## Transport and Direction

- Direction: Android app -> ESP32-S3 GATT server
- ATT operation: Write Command (no response), opcode 0x52
- Characteristics:
  - Media: A1234567-1234-1234-1234-A12345678902
  - Time: A1234567-1234-1234-1234-A12345678903

## Frame 1: ATT Write Command (Media characteristic)

This is the BLE ATT frame that carries media payload bytes.

```mermaid
packet
  title ATT Write Command to Media Characteristic
  0-7: "Opcode = 0x52 (Write Command)"
  8-15: "Attr Handle LSB"
  16-23: "Attr Handle MSB"
  24-151: "Value bytes (MediaInfo payload, variable length)"
```

### Media value payload format

First byte is the media type, followed by length-prefixed UTF-8 fields. Each length is a byte count and does not include a terminator.

- 0x00: Radio -> stationIdLength, stationId
- 0x01: Streaming -> artistLength, artist, trackLength, track
- 0x02: CallOutgoing -> numberLength, number, nameLength, name
- 0x03: CallIncoming -> numberLength, number, nameLength, name
- 0xFF: Idle (single byte)

Text fields are limited to 61 UTF-8 bytes. If a field is longer, it is truncated at a UTF-8 character boundary and ends with `...`.

Example (Streaming):

```mermaid
packet
  title MediaInfo Value Example (Streaming)
  0-7: "Type = 0x01"
  8-15: "artist length (UTF-8 byte count)"
  16-503: "artist UTF-8 bytes"
  504-511: "track length (UTF-8 byte count)"
  512-999: "track UTF-8 bytes"
```

## Frame 2: ATT Write Command (Time characteristic)

This is sent as a five-byte RDS Clock-Time value. It contains a 17-bit MJD for the UTC
date, UTC hour and minute, plus the signed local UTC offset in half-hour increments.

```mermaid
packet
  title ATT Write Command to Time Characteristic
  0-7: "Opcode = 0x52 (Write Command)"
  8-15: "Attr Handle LSB"
  16-23: "Attr Handle MSB"
  24-40: "Modified Julian Date (17 bits)"
  41-45: "UTC hour (5 bits)"
  46-51: "UTC minute (6 bits)"
  52-52: "Local offset sign"
  53-57: "Local offset in half-hours"
  58-63: "Reserved = 0"
```

## When Packets Are Sent

### Media frame timing

- Sent whenever parsed media state changes.
- Duplicate consecutive states are suppressed before sending.

### Time frame timing

- Sent immediately when BLE is READY (after service/characteristic discovery).
- Re-sent every 30 minutes.

### Write pacing

- Writes are serialized through a queue.
- A 50 ms settle gap is applied between writes.
- Write type is no-response.

## Source of Information

The diagrams and timing rules above are derived from these implementation points:

- Media payload serialization:
  - android/app/src/main/java/com/pioneermediabridge/model/MediaInfo.kt
  - MediaInfo.toBytes()

- Time payload serialization and periodic scheduling:
  - android/app/src/main/java/com/pioneermediabridge/ble/BleWriterManager.kt
  - BleWriterManager.sendTimeNow()
  - BleWriterManager.onReady()

- BLE write mode and queue behavior:
  - android/app/src/main/java/com/pioneermediabridge/ble/BleWriterManager.kt
  - drainQueue(), onCharacteristicWrite()

- Timing constants:
  - android/app/src/main/java/com/pioneermediabridge/ble/BleConstants.kt
  - TIME_SYNC_INTERVAL_MS, WRITE_SETTLE_MS

- Media send trigger and dedupe:
  - android/app/src/main/java/com/pioneermediabridge/service/MonitorService.kt
  - mediaFlow.distinctUntilChanged().collect { ... bleWriter.sendMediaInfo(info) ... }

- ESP32 receive path and accepted write ops:
  - esp32s3/main/ble_endpoint.c
  - gatt_message_write(...)
  - BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP for media/time characteristics
