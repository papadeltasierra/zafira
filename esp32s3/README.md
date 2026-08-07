# Zafira ESP32-S3 BLE Endpoint (ESP-IDF)

This project is an ESP-IDF application for ESP32-S3 that exposes a BLE GATT endpoint.
It advertises with a configurable name, accepts BLE write messages from a phone, classifies
incoming payloads, and logs them to the serial console.

## Features

- BLE peripheral endpoint for phone connections.
- One custom GATT service (profile UUID configurable at build time).
- One writable characteristic for receiving phone messages.
- Message classification and console logging:
	- Empty payload
	- ASCII text
	- JSON-like text
	- Binary payload (hex dump)

## Build-Time Configuration

Configuration options are defined in `main/Kconfig.projbuild` and available through:

```bash
idf.py menuconfig
```

Path in menu:

- `Zafira BLE Endpoint` -> `BLE advertised device name`
- `Zafira BLE Endpoint` -> `BLE profile UUID (128-bit canonical format)`

Defaults:

- Advertised name: `Zafira`
- Profile UUID: `99999999-9999-9999-9999-999999999999`

## Build and Flash

From an ESP-IDF command prompt:

```bash
idf.py set-target esp32s3
idf.py build
idf.py -p <PORT> flash monitor
```

## BLE Behavior

- Device advertises as configured name (default `Zafira`).
- Phone can connect and write data to the custom writable characteristic.
- Incoming messages are classified and logged.

## Notes

- The characteristic UUID is derived from the configured service UUID at runtime.
- UUID format must be canonical 128-bit string format:
	`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.