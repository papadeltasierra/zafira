# Pioneer Media Bridge — Design Document

## 1. Overview

**Pioneer Media Bridge** is an Android application that monitors Bluetooth HCI traffic between an Android phone and a Pioneer AppLink (SDL) radio head unit, extracts currently-playing media metadata and call information, and forwards it in real time to a secondary BLE device using a custom GATT profile.

---

## 2. Target Platform

| Attribute | Value |
|---|---|
| Target SDK | 36 (Android 16) |
| Minimum SDK | 26 (Android 8.0 Oreo) |
| Language | Kotlin |
| Build system | Gradle 8.9 / AGP 8.7.0 |

Android 8 was chosen as the minimum because it provides stable BLE GATT APIs, robust `FileObserver`, and the `startForegroundService` requirement that makes service lifecycle predictable.

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  MonitorService  (foreground, type=connectedDevice)         │
│                                                             │
│  BtSnoopSocketReader (API 36+, primary)                     │
│  BtSnoopReader       (API < 36, fallback)  ◄─ auto-select  │
│       │  Flow<HciRecord>                                    │
│       ▼                                                     │
│  HciPacketDecoder  ← pioneerMacBytes                        │
│       │  Flow<AttPayload>  (only Pioneer connection)        │
│       ▼                                                     │
│  SdlFrameAssembler                                          │
│       │  Flow<SdlFrame>   (reassembled SDL messages)        │
│       ▼                                                     │
│  PioneerMediaParser                                         │
│       │  Flow<MediaInfo>                                    │
│       ▼                                                     │
│  BleWriterManager  ──────────────────────────► Output BLE   │
│       │  (also sends Unix time on connect + every 30 min)  │
└─────────────────────────────────────────────────────────────┘
         │ local broadcast
         ▼
  MainActivity (status display)
```

All processing uses Kotlin coroutines and `Flow`. The pipeline is cold: nothing runs until `MonitorService` collects from the source.

---

## 4. Components

### 4.1 HCI Snoop Input

`MonitorService` auto-selects the input source based on Android API level. No user choice is required; the socket name and fallback file path are configurable in Settings if defaults do not match the device.

#### Primary — `BtSnoopSocketReader` (Android 16 / API 36+)

Android 16 added the developer option **"Enable Bluetooth HCI snoop socket"** (Developer Options → Networking) which exposes the live Bluetooth HCI stream as an abstract Unix domain socket on the device. The app connects to this socket using `android.net.LocalSocket`.

**Advantages over file-watching:**

| Attribute | Local socket | Log file |
|---|---|---|
| File growth | None — stream only | Unbounded |
| New-data delivery | Blocking `socket.read()` — zero latency | `FileObserver` + seek loop |
| Permission needed | None beyond Bluetooth | `MANAGE_EXTERNAL_STORAGE` |
| ADB / external tools | Not required | Not required |
| Works standalone | Yes | Yes |

The socket is in the Linux **abstract namespace** — a zero-length name prefix rather than a filesystem path, so no file permissions apply. Default socket name: **`bthcisnoop`** (based on AOSP implementation). If your device uses a different name, update it in Settings.

The stream uses the identical btsnoop record format (16-byte file header + 24-byte record headers), so `HciPacketDecoder` and all downstream parsers are unchanged.

**Setup:**
1. Settings → About phone → tap **Build number** 7 times (enables Developer Options).
2. Settings → Developer Options → Networking → **Enable Bluetooth HCI snoop socket** → On.
3. Open Pioneer Media Bridge. The service connects to the socket automatically.

On connection loss the reader waits 3 seconds and reconnects automatically.

#### Fallback — `BtSnoopReader` (Android 8–15 / API 26–35)

Tails the btsnoop HCI log file written by the Bluetooth stack when **"Enable Bluetooth HCI snoop log"** is on in Developer Options. Uses `FileObserver` (inotify) for efficient new-data detection.

File path candidates tried in order:
1. Path configured in Settings (default `/sdcard/btsnoop_hci.log`)
2. `/storage/emulated/0/btsnoop_hci.log`
3. `/data/misc/bluetooth/logs/btsnoop_hci.log`

`MANAGE_EXTERNAL_STORAGE` is required to read from `/sdcard/` on Android 11+; the app guides the user to the system permission page. On Android 10 and below, `READ_EXTERNAL_STORAGE` (already in the manifest) is sufficient.

### 4.2 `HciPacketDecoder`

Converts raw `HciRecord` events into ATT payloads for the Pioneer connection only.

**Connection tracking:**
- Parses HCI **LE Connection Complete** events (event code `0x3E`, sub-event `0x01` and `0x0A`).
- Compares the 6-byte peer BD_ADDR (little-endian in the event) against the configured Pioneer MAC bytes.
- When matched, records the 12-bit connection handle.
- On **Disconnection Complete** (`0x05`), clears the handle.

**ACL → L2CAP → ATT:**
- Extracts ACL data packets (H4 type `0x02`).
- Handles L2CAP PDU reassembly from HCI ACL fragments (PB flag `0x02` = first, `0x01` = continuation).
- Selects L2CAP channel `0x0004` (ATT / LE-U fixed channel).
- Emits `AttPayload` structs for the following ATT opcodes:

| Opcode | Name | Direction |
|---|---|---|
| `0x52` | Write Command (Write Without Response) | Phone → HU |
| `0x12` | Write Request | Phone → HU |
| `0x1B` | Handle Value Notification | HU → Phone |
| `0x1D` | Handle Value Indication | HU → Phone |

**Note on GATT handle discovery:**
Rather than tracking GATT service/characteristic discovery (which adds significant complexity), the SDL frame heuristic in `SdlFrameAssembler` is used to identify SDL packets within ATT payloads. Full handle-to-UUID mapping can be added in a future version to improve precision.

### 4.3 `SdlFrameAssembler`

Identifies and reassembles SDL (SmartDeviceLink) protocol frames from raw ATT values.

**SDL BLE Frame Header (12 bytes):**

```
 0        1        2        3
┌────────┬────────┬────────┬────────┐
│Ver(4)  │Service │FrameInfo│Session │
│Enc(1)  │ Type   │         │  ID    │
│FType(3)│        │         │        │
├────────┴────────┴────────┴────────┤
│          Data Size (32-bit BE)    │
├───────────────────────────────────┤
│          Message ID (32-bit BE)   │
└───────────────────────────────────┘
```

**Heuristic SDL detection:**
- Byte 0 upper nibble must be 1–5 (SDL protocol version).
- Byte 0 lower 3 bits must be a valid frame type (0–3).
- Byte 1 must be a known service type: `0x00` Control, `0x07` RPC, `0x0A` Audio, `0x0B` Video, `0x0F` Hybrid.

**Frame types:**

| Value | Name | Action |
|---|---|---|
| 0 | Control | Pass through |
| 1 | Single | Emit immediately |
| 2 | First | Start reassembly; `dataSize` = total payload |
| 3 | Consecutive | Append to buffer; emit when complete |

Multi-frame messages are buffered per `(sessionId, messageId, direction)`.

**SDL BLE service UUIDs (SmartDeviceLink BLE specification):**

| UUID | Purpose |
|---|---|
| `0000923A-0000-1000-8000-00805F9B34FB` | SDL BLE Service |
| `00009245-0000-1000-8000-00805F9B34FB` | Write characteristic (phone → HU) |
| `00009246-0000-1000-8000-00805F9B34FB` | Read / notify characteristic (HU → phone) |
| `00009247-0000-1000-8000-00805F9B34FB` | Control point |

### 4.4 `PioneerMediaParser`

Parses SDL RPC service frames (service type `0x07`) to extract media metadata.

**SDL RPC Payload Header (16 bytes, within SDL frame payload):**

```
 0      1      2      3
┌──────────────────────┐
│  RPC Type (4 bits)   │
│  Function ID (28 bits)  (big-endian)
├──────────────────────┤
│    Correlation ID    │  (big-endian uint32)
├──────────────────────┤
│      JSON Size       │  (big-endian uint32)
├──────────────────────┤
│   Binary Header Size │  (big-endian uint32, usually 0)
└──────────────────────┘
[JSON data: jsonSize bytes in UTF-8]
[Binary data: binaryHeaderSize bytes]
```

**RPC Types:**

| Value | Meaning |
|---|---|
| 0 | Request (phone → HU) |
| 1 | Response |
| 2 | Notification (HU → phone) |
| 3 | Error Response |

**Monitored function IDs (SDL specification):**

| ID (hex) | Name | Extracted data |
|---|---|---|
| `0x0D` | `Show` | `mainField1`, `mainField2`, `mediaTrack`, `metadataTags` |
| `0x28` | `DialNumber` | `number` (outgoing call) |
| `0x8000` | `OnHMIStatus` | `hmiLevel`, `systemContext`, `audioStreamingState` |

**Media classification logic for `Show` RPC:**
1. If `metadataTags` present: use explicit type hints (`mediaArtist`, `mediaTitle`, `mediaStation`).
2. Otherwise, apply heuristic regex to `mainField1`/`mainField2`:
   - Pattern `\d{2,3}[.,]\d\s*(FM|AM|DAB|MW|LW|SW)` → Radio station ID.
   - Both fields non-empty without station pattern → `artist` / `track`.
3. `DialNumber` request → `CallOutgoing`.

**Important caveat — Pioneer proprietary RPCs:**
When the Pioneer head unit's own radio tuner is active (FM/AM/DAB), the content does not originate from a phone SDL app. Pioneer likely uses proprietary SDL function IDs (in the range `0xF000`–`0xFFFF`) to push station metadata to the Pioneer companion app. These function IDs are not documented publicly and require empirical capture with a real head unit to reverse-engineer. The current implementation extracts what is available via the standard `Show` RPC, which covers streaming audio (Spotify, Tidal, etc.) reliably.

### 4.5 `BleWriterManager`

Manages the BLE GATT connection to the output device.

**Connection strategy:**
1. If a MAC address is configured: call `BluetoothAdapter.getRemoteDevice(mac)` + `connectGatt(..., autoConnect=true, ...)` — no scan required.
2. If only a name is configured: start a low-latency BLE scan, match by `ScanResult.device.name`, save the discovered MAC, then connect.
3. On disconnect: schedule reconnect after 5 seconds (indefinitely).

**After service discovery:**
- Looks for the custom bridge service (UUID `A1234567-…-901`).
- Caches handles for Media Info and Time Sync characteristics.
- Immediately sends the current Unix time (first connect action).
- Schedules a `coroutines.delay`-based time sync every 30 minutes.

**Write serialisation:**
Android BLE requires writes to be serialised (one at a time). `BleWriterManager` maintains an `ArrayDeque` and drains it in `onCharacteristicWrite`. A 50 ms settle delay prevents collisions with firmware debouncing.

**Android version handling for writes:**
- API ≥ 33 (Android 13): `BluetoothGatt.writeCharacteristic(char, data, WRITE_TYPE_NO_RESPONSE)`.
- API < 33: set `characteristic.value` and `characteristic.writeType = WRITE_TYPE_NO_RESPONSE`, then `gatt.writeCharacteristic(characteristic)`.

### 4.6 `MonitorService`

A `LifecycleService` (foreground, `connectedDevice` type) that ties the pipeline together.

- Started by the user via the `MainActivity` toggle or automatically at boot by `BootReceiver`.
- Shows a persistent notification with current media info and BLE state.
- Broadcasts `com.pioneermediabridge.STATUS` for the UI to update while the app is in the foreground.
- Resolves the btsnoop file path by trying the configured path, then the candidate list.
- All coroutines are scoped to `lifecycleScope`; cancellation on `onDestroy` stops all flows cleanly.

### 4.7 `BootReceiver`

Receives `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`. Reads the `serviceEnabled` flag from DataStore and starts `MonitorService` if true and the app is configured. Uses a `SupervisorJob` coroutine scope for the one-shot DataStore read.

---

## 5. Custom BLE Output Profile

The output BLE device must implement the following GATT server profile:

### 5.1 Service

| Attribute | Value |
|---|---|
| Service UUID | `A1234567-1234-1234-1234-A12345678901` |

### 5.2 Characteristics

#### Media Info (`A1234567-1234-1234-1234-A12345678902`)

- Properties: **Write Without Response** (`WRITE_TYPE_NO_RESPONSE`)
- Max value size: 512 bytes (BLE ATT maximum)
- Written on every media state change (deduplicated via `distinctUntilChanged`)

**Wire format:**

| Byte(s) | Content |
|---|---|
| 0 | Type: `0x00`=Radio, `0x01`=Streaming, `0x02`=CallOut, `0x03`=CallIn, `0xFF`=Idle |
| 1… | Null-terminated UTF-8 string(s) (see below) |

Type-specific payload:

```
Radio     (0x00): stationId\0
Streaming (0x01): artist\0 track\0
CallOut   (0x02): number\0 name\0   (name may be empty: number\0\0)
CallIn    (0x03): number\0 name\0
Idle      (0xFF): (no further bytes)
```

#### Time Sync (`A1234567-1234-1234-1234-A12345678903`)

- Properties: **Write Without Response**
- Value size: exactly 8 bytes
- Written on first connection and then every 30 minutes

**Wire format:**

| Bytes | Content |
|---|---|
| 0–7 | Unix epoch seconds, big-endian `int64` |

---

## 6. Permissions

| Permission | Purpose | API range |
|---|---|---|
| `BLUETOOTH` | Legacy Bluetooth | < API 31 |
| `BLUETOOTH_ADMIN` | Legacy BLE scan | < API 31 |
| `BLUETOOTH_SCAN` | BLE scanning | ≥ API 31 |
| `BLUETOOTH_CONNECT` | GATT connect | ≥ API 31 |
| `ACCESS_FINE_LOCATION` | Required for BLE scan | < API 31 |
| `READ_EXTERNAL_STORAGE` | btsnoop on external storage | < API 33 |
| `MANAGE_EXTERNAL_STORAGE` | Unrestricted file read | any (special grant) |
| `FOREGROUND_SERVICE` | Foreground service | any |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service type | ≥ API 34 |
| `RECEIVE_BOOT_COMPLETED` | Auto-start at boot | any |
| `WAKE_LOCK` | Prevent CPU sleep during parsing | any |

---

## 7. Settings (DataStore)

All settings are persisted with Jetpack DataStore (Preferences). They survive process death and are available immediately at boot via a synchronous `Flow.first()` read.

| Key | Type | Description |
|---|---|---|
| `pioneer_mac` | String | Pioneer BD_ADDR (e.g., `AA:BB:CC:DD:EE:FF`) |
| `pioneer_name` | String | Pioneer advertised BLE name (fallback identification) |
| `output_ble_mac` | String | Output device BD_ADDR |
| `output_ble_name` | String | Output device name (used when MAC is unknown) |
| `snoop_file_path` | String | Fallback btsnoop log file path (API < 36) |
| `snoop_socket_name` | String | Abstract Unix socket name for API 36+ (default `bthcisnoop`) |
| `service_enabled` | Boolean | Whether the service should run (persists across reboots) |

Pioneer is identified by **either** MAC **or** name; at least one must be provided. If both are given, MAC takes priority (exact, no false positives). If only the name is provided, the HCI LE Connection Complete event's resolved name is not available (only the BD_ADDR is in the event), so the name match relies on a GATT Read By Name or a prior BLE scan cache. In practice, configure the MAC for reliability.

---

## 8. One-Time Setup Flow

### Android 16 (recommended)

1. Settings → About phone → tap **Build number** 7 times → return to Settings → **Developer Options**.
2. Developer Options → Networking → turn on **"Enable Bluetooth HCI snoop socket"**.
3. Open Pioneer Media Bridge → tap ⋮ → **Settings**:
   - Enter Pioneer radio MAC address and/or BLE name.
   - Enter ESP32 BLE device MAC address and/or name.
   - Verify socket name is `bthcisnoop` (adjust only if the service fails to connect and logs show a different name).
4. Return to the main screen and toggle **"Enable monitoring service"**. No further interaction is needed.

### Android 8–15 (fallback)

1. Enable Developer Options as above, then turn on **"Bluetooth HCI snoop log"**.
2. Open Pioneer Media Bridge → tap ⋮ → **Settings**:
   - Enter Pioneer and ESP32 identifiers as above.
   - If prompted, grant **"Allow management of all files"** permission (needed to read `/sdcard/btsnoop_hci.log`).
   - Verify the fallback file path; tap **Use default path** to reset it.
3. Toggle the service on the main screen.

---

## 9. Pioneer AppLink / SDL Protocol Notes

Pioneer AppLink is Pioneer's brand of the **SmartDeviceLink (SDL)** open standard, governed by the SDL Consortium. The BLE transport layer follows the SDL BLE Transport Specification.

**Transport variants observed in Pioneer hardware:**

| Model generation | Transport |
|---|---|
| Older (pre-2019) | SDL over RFCOMM (SPP) via Bluetooth Classic — *not captured by BLE HCI snoop* |
| Current (2019+) | SDL BLE for control + Wi-Fi Direct for data (audio/video) |
| Some mid-range units | SDL entirely over BLE (metadata + control only, no audio) |

For units that use Wi-Fi Direct for the data plane, BLE carries only the session setup and control RPCs. In this case `Show` RPCs sent during streaming **will** appear in the BLE HCI snoop (they are small control messages), but audio data will not. The metadata extraction in `PioneerMediaParser` should still work for these units.

For units using RFCOMM/SPP, HCI snoop captures Classic Bluetooth ACL data on L2CAP channels, not ATT. A future version could add an L2CAP RFCOMM parser for SPP-transported SDL.

**SDL version compatibility:**
The frame parser accepts versions 1–5 in the version nibble. Pioneer radios typically use version 2 or 3. The RPC payload layout has been stable since SDL version 1.0.

---

## 10. Known Limitations and Future Work

1. **L2CAP fragment reassembly** — The current `HciPacketDecoder` handles the common case of complete ACL frames. Very large SDL frames split across three or more HCI ACL fragments may not reassemble correctly. A complete ACL defragmentation state machine (tracking fill position per handle) should be added for production use.

2. **Pioneer proprietary RPCs** — Radio station ID extraction depends on Pioneer using standard `Show` metadata or a recognisable string format. If Pioneer uses proprietary function IDs (>= `0xF000`), a capture session with a real head unit and Wireshark/nRF Sniffer is needed to identify and handle those IDs.

3. **Incoming call detection** — SDL does not have a standard notification for incoming calls. Detection currently relies on the `OnHMIStatus` context change. A robust solution would combine this with Android's telephony `PhoneStateListener` or `CallScreeningService` (separate permissions: `READ_PHONE_STATE`).

4. **GATT handle caching** — The SDL characteristic handles change if the head unit's GATT server is updated. Currently the heuristic byte-pattern approach avoids handle dependency entirely. An optional GATT discovery pass (parsing `ATT_READ_BY_TYPE_RSP` for UUID `0x2803`) would give exact handle-to-characteristic mapping.

5. **File rotation** — If Android's Bluetooth stack rotates the btsnoop file (new file at the same path), the `FileObserver` will stop seeing new events after the old inode is replaced. Watching the parent directory for `CREATE` events on the filename would handle this.

6. **Pioneer name-only identification** — If only the Pioneer name is configured (no MAC), the app cannot match the HCI LE Connection Complete event (which contains only BD_ADDR). A preliminary BLE scan on startup can resolve the name-to-MAC mapping; this should be added to `MonitorService.startMonitoring()`.

7. **Multiple simultaneous BLE connections** — The app currently tracks a single Pioneer connection handle. Units that re-connect with a different handle (e.g., after a quick toggle) will work, but two simultaneous Pioneer connections (unlikely) would need a set-based tracking structure.

---

## 11. Project Structure

```
app/src/main/
├── java/com/pioneermediabridge/
│   ├── MainActivity.kt              UI: status + service toggle
│   ├── SetupActivity.kt             UI: one-time configuration
│   ├── model/
│   │   ├── AppSettings.kt           Settings data class + BD_ADDR helpers
│   │   ├── MediaInfo.kt             Sealed class; also produces BLE wire bytes
│   │   └── SettingsRepository.kt    DataStore read/write wrapper
│   ├── ble/
│   │   ├── BleConstants.kt          All UUIDs and timing constants
│   │   └── BleWriterManager.kt      GATT client: scan/connect/write/time-sync
│   ├── parser/
│   │   ├── BtSnoopSocketReader.kt       LocalSocket → Flow<HciRecord>  (API 36+, primary)
│   │   ├── BtSnoopReader.kt             btsnoop file tail → Flow<HciRecord>  (API < 36, fallback)
│   │   ├── HciPacketDecoder.kt          HCI→ACL→L2CAP→ATT, pioneer-filtered
│   │   ├── SdlFrameAssembler.kt         ATT value → SDL frame reassembly
│   │   └── PioneerMediaParser.kt        SDL RPC → MediaInfo
│   ├── service/
│   │   ├── MonitorService.kt        Foreground service, pipeline orchestrator
│   │   └── BootReceiver.kt          Auto-start at boot
│   └── ui/
│       ├── MainViewModel.kt         State for MainActivity
│       └── SetupViewModel.kt        State for SetupActivity
└── res/
    ├── layout/
    │   ├── activity_main.xml
    │   └── activity_setup.xml
    ├── menu/main_menu.xml
    ├── drawable/ic_notification.xml
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml
    │   └── ic_launcher_round.xml
    └── values/
        ├── strings.xml
        ├── colors.xml
        └── themes.xml
```
