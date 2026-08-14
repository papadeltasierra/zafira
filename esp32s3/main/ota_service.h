#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C"
{
#endif

// OTA Control opcodes (Android -> device).
#define OTA_CMD_START 0x01
#define OTA_CMD_ABORT 0x02
#define OTA_CMD_COMMIT 0x03

// OTA Control notifications (device -> Android).
#define OTA_EVT_READY 0x80
#define OTA_EVT_ACK 0x81
#define OTA_EVT_COMMITTED 0x82
#define OTA_EVT_ERROR 0xef

// OTA_EVT_ERROR reasons.
#define OTA_ERR_BUSY 1
#define OTA_ERR_TOO_LARGE 2
#define OTA_ERR_BAD_SEQUENCE 3
#define OTA_ERR_FLASH_WRITE 4
#define OTA_ERR_HASH_MISMATCH 5
#define OTA_ERR_INVALID_IMAGE 6
#define OTA_ERR_TIMEOUT 7
#define OTA_ERR_NOT_PERMITTED 8
#define OTA_ERR_OVERFLOW 9

#define OTA_START_PAYLOAD_LEN 40 // opcode + u32 size + 3 version bytes + 32 hash bytes
#define OTA_MAX_WRITE_LEN 514    // u16 sequence + maximum chunk payload

// Emits an OTA Control notification. Implemented by the GATT layer.
typedef void (*ota_notify_fn)(const uint8_t *data, uint16_t len);

esp_err_t ota_service_init(ota_notify_fn notify);

// Usable bytes of the inactive OTA slot, reported in Firmware Info.
uint32_t ota_service_slot_size(void);

// True while a transfer session is open, used to suppress media traffic.
bool ota_service_is_active(void);

// 0 = running image already validated, 1 = pending verify with rollback armed.
uint8_t ota_service_image_state(void);

// Confirms the running image after a successful post-OTA connection.
void ota_service_mark_valid(void);

void ota_service_on_disconnect(void);

// Both return 0 on success or a BLE_ATT_ERR_* code.
int ota_service_handle_control(const uint8_t *data, uint16_t len);
int ota_service_handle_data(const uint8_t *data, uint16_t len);

#ifdef __cplusplus
}
#endif
