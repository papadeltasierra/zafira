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
- ESP32 max payload length is `BLE_MSG_MAX_LEN = 256` bytes.

## Media Payload Wire Format

Defined by `MediaInfo.toBytes()`:

- `0x00`: Radio -> `stationId\0`
- `0x01`: Streaming -> `artist\0track\0`
- `0x02`: CallOutgoing -> `number\0name\0`
- `0x03`: CallIncoming -> `number\0name\0`
- `0xFF`: Idle (single byte)

Encoding details:

- Strings are UTF-8.
- String fields are null-terminated.
- No explicit length prefixes beyond terminators.

## Time Sync Payload

Defined by `BleWriterManager.sendTimeNow()`:

- Payload length: 8 bytes
- Endianness: big-endian
- Value: Unix epoch seconds (`System.currentTimeMillis() / 1000`)
- Time base: UTC epoch (timezone-independent instant)

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
- ESP32 currently classifies/logs received payloads; it does not yet decode media/time payload bytes into structured state.

## Verification Checklist

- Confirm Android app can connect to ESP32 and discover bridge service.
- Validate media payload bytes on ESP32 logs for each media type.
- Validate 8-byte epoch value on initial connect and at 30-minute interval.
- Confirm MTU negotiation does not exceed receiver payload constraints.
