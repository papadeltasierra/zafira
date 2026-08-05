# BLE Protocol Contract

This document captures the implemented contract between the Android app and the ESP32-S3 receiver.

## Scope

- Source side: Android app (`com.pioneermediabridge`)
- Sink side: ESP32-S3 NimBLE endpoint (`esp32s3/main/ble_endpoint.c`)
- Data domains:
- Media state derived from Pioneer MVH-S420DAB traffic
- Time sync signal for receiver clock updates

## Pipeline Overview

1. Android obtains Bluetooth HCI snoop records.
2. `HciPacketDecoder` extracts ATT payloads for the Pioneer LE connection.
3. `SdlFrameAssembler` reassembles SDL RPC payloads.
4. `PioneerMediaParser` converts SDL RPCs into `MediaInfo` events.
5. `BleWriterManager` writes `MediaInfo` and periodic time sync to ESP32 over GATT.

Primary Android flow entrypoint:

- `android/app/src/main/java/com/pioneermediabridge/service/MonitorService.kt`

## Snoop Input Modes

- Android API >= 36: local socket stream via `BtSnoopSocketReader`.
- Android API < 36: tailed btsnoop file via `BtSnoopReader`.

Configured paths/names are user settings with defaults:

- Socket default: `bthcisnoop`
- File candidates include `/sdcard/btsnoop_hci.log`

## BLE GATT Contract

Android UUID constants (must match receiver):

- Service UUID: `A1234567-1234-1234-1234-A12345678901`
- Media characteristic UUID: `A1234567-1234-1234-1234-A12345678902`
- Time characteristic UUID: `A1234567-1234-1234-1234-A12345678903`

ESP32 service/characteristic derivation:

- Service UUID comes from `CONFIG_ZAFIRA_BLE_PROFILE_UUID`.
- Media/time characteristic UUIDs are derived by replacing low byte with `0x02` and `0x03`.

Current configured value:

- `esp32s3/sdkconfig` sets `CONFIG_ZAFIRA_BLE_PROFILE_UUID="A1234567-1234-1234-1234-A12345678901"`.

Write behavior:

- Android writes with `WRITE_TYPE_NO_RESPONSE`.
- Writes are serialized with a queue and `WRITE_SETTLE_MS = 50` ms.
- ESP32 accepts write and write-no-response on both characteristics.
- ESP32 preferred ATT MTU is 128 bytes; the Android client requests 128 and uses the negotiated result.
- Streaming artist and track fields are each limited to 61 UTF-8 bytes plus a one-byte length.
- Oversized text fields are UTF-8 safely truncated and end with ASCII `...`.
- ESP32 max application payload length is `BLE_MSG_MAX_LEN = 256` bytes.

## Media Payload Wire Format

Defined by `MediaInfo.toBytes()`:

- `0x00`: Radio -> `stationIdLength, stationId`
- `0x01`: Streaming -> `artistLength, artist, trackLength, track`
- `0x02`: CallOutgoing -> `numberLength, number, nameLength, name`
- `0x03`: CallIncoming -> `numberLength, number, nameLength, name`
- `0xFF`: Idle (single byte)

Encoding details:

- Strings are UTF-8.
- Each string has a one-byte length measured in UTF-8 bytes.
- The length does not include a terminator; fields are not NUL-terminated on the wire.

## Time Sync Payload

Defined by `BleWriterManager.sendTimeNow()`:

- Payload length: 5 bytes (34 significant bits, with six zero reserved bits)
- Format: RDS Clock-Time fields, packed most-significant bit first:

| Byte | Bits | Field |
|---|---|---|
| 0-1 and byte 2 bit 7 | 17 | Modified Julian Date, calculated from the UTC calendar date since 1858-11-17 |
| Byte 2 bits 6-2 | 5 | UTC hour |
| Byte 2 bits 1-0 and byte 3 bits 7-4 | 6 | UTC minute |
| Byte 3 bit 3 | 1 | Local offset sign (`0` positive, `1` negative) |
| Byte 3 bits 2-0 and byte 4 bits 7-6 | 5 | Absolute local UTC offset in 30-minute increments |
| Byte 4 bits 5-0 | 6 | Reserved, zero |

- UTC date and time are calculated from the send instant.
- The local offset is calculated for the Android system timezone at that same instant, including daylight-saving rules.
- RDS only represents half-hour offsets; the Android sender does not send a value if its current timezone offset is not representable.

Schedule:

- Sent immediately when BLE is ready.
- Re-sent every `TIME_SYNC_INTERVAL_MS = 30 minutes`.

## Parser Semantics (Android)

Media extraction is based on SDL RPC frames:

- `Show` (`functionId 0x0D`) -> radio/streaming classification
- `DialNumber` (`0x28`) -> outgoing call
- `OnHMIStatus` (`0x8000`) -> context tracking only

Radio vs streaming classification:

- Prefer `metadataTags` in JSON (`mediaStation`, `mediaArtist`, `mediaTitle`).
- Fall back to station regex heuristics and field combinations.

## Current Limitations And Risks

- `HciPacketDecoder` ACL continuation reassembly is marked as simplified and not fully implemented for very large SDL frames.
- ESP32 validates and logs decoded media and RDS clock-time payloads; it does not yet retain them as structured state.

## Verification Checklist

- Confirm Android app can connect to ESP32 and discover bridge service.
- Validate media payload bytes on ESP32 logs for each media type.
- Validate the decoded RDS MJD, UTC hour/minute, and local offset on initial connect and at the 30-minute interval.
- Confirm MTU negotiation does not exceed receiver payload constraints.
