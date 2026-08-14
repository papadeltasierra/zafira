#include <inttypes.h>
#include <stdlib.h>
#include <string.h>

#include "ota_service.h"

#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "psa/crypto.h"

#define OTA_TASK_STACK_SIZE 4096
// Core 1 keeps the slow flash writes off the core running the NimBLE host and controller.
#define OTA_TASK_CORE 1
#define OTA_QUEUE_DEPTH 32
#define OTA_MAX_CHUNK 512
#define OTA_COMMIT_FLUSH_MS 200

static const char *TAG = "zafira_ota";

typedef enum
{
    OTA_STATE_IDLE = 0,
    OTA_STATE_RECEIVING,
    OTA_STATE_COMMITTED,
} ota_state_t;

typedef enum
{
    OTA_MSG_START = 0,
    OTA_MSG_DATA,
    OTA_MSG_ABORT,
    OTA_MSG_COMMIT,
    OTA_MSG_DISCONNECT,
} ota_msg_kind_t;

typedef struct
{
    ota_msg_kind_t kind;
    uint16_t seq;
    uint16_t len;
    uint8_t *data;
    uint32_t image_size;
    uint8_t version[3];
    uint8_t sha256[32];
} ota_msg_t;

static ota_notify_fn s_notify;
static QueueHandle_t s_queue;
static uint32_t s_slot_size;
static volatile bool s_active;
static volatile bool s_overflow;
static volatile bool s_pending_verify;

// Task-owned session state; only touched by ota_task.
static ota_state_t s_state = OTA_STATE_IDLE;
static esp_ota_handle_t s_ota_handle;
static const esp_partition_t *s_target;
static uint32_t s_image_size;
static uint32_t s_bytes_received;
static uint16_t s_expected_seq;
static uint16_t s_since_ack;
static uint8_t s_expected_sha[32];
static psa_hash_operation_t s_hash_op;
static TickType_t s_last_activity;

static void notify_simple(uint8_t opcode)
{
    if (s_notify != NULL)
    {
        s_notify(&opcode, 1);
    }
}

static void notify_error(uint8_t reason)
{
    uint8_t payload[2] = {OTA_EVT_ERROR, reason};
    ESP_LOGW(TAG, "OTA error notified, reason=%u", reason);
    if (s_notify != NULL)
    {
        s_notify(payload, sizeof(payload));
    }
}

static void notify_ready(uint32_t accepted_size, uint16_t window)
{
    uint8_t payload[7] = {OTA_EVT_READY};
    memcpy(&payload[1], &accepted_size, sizeof(accepted_size));
    memcpy(&payload[5], &window, sizeof(window));
    if (s_notify != NULL)
    {
        s_notify(payload, sizeof(payload));
    }
}

static void notify_ack(uint32_t bytes_received)
{
    uint8_t payload[5] = {OTA_EVT_ACK};
    memcpy(&payload[1], &bytes_received, sizeof(bytes_received));
    if (s_notify != NULL)
    {
        s_notify(payload, sizeof(payload));
    }
}

static void session_reset(void)
{
    if (s_state == OTA_STATE_RECEIVING)
    {
        psa_hash_abort(&s_hash_op);
        esp_ota_abort(s_ota_handle);
    }
    s_state = OTA_STATE_IDLE;
    s_ota_handle = 0;
    s_target = NULL;
    s_image_size = 0;
    s_bytes_received = 0;
    s_expected_seq = 0;
    s_since_ack = 0;
    s_overflow = false;
    s_active = false;
}

static void session_fail(uint8_t reason)
{
    session_reset();
    notify_error(reason);
}

static void handle_start(const ota_msg_t *msg)
{
    if (s_state != OTA_STATE_IDLE)
    {
        notify_error(OTA_ERR_BUSY);
        return;
    }

    s_target = esp_ota_get_next_update_partition(NULL);
    if (s_target == NULL)
    {
        notify_error(OTA_ERR_NOT_PERMITTED);
        return;
    }

    if (msg->image_size == 0 || msg->image_size > s_target->size)
    {
        ESP_LOGW(TAG,
                 "Rejecting image of %" PRIu32 " bytes, slot '%s' holds %" PRIu32,
                 msg->image_size,
                 s_target->label,
                 s_target->size);
        notify_error(OTA_ERR_TOO_LARGE);
        return;
    }

    esp_err_t err = esp_ota_begin(s_target, msg->image_size, &s_ota_handle);
    if (err != ESP_OK)
    {
        ESP_LOGE(TAG, "esp_ota_begin failed: %s", esp_err_to_name(err));
        notify_error(OTA_ERR_FLASH_WRITE);
        return;
    }

    s_hash_op = psa_hash_operation_init();
    if (psa_hash_setup(&s_hash_op, PSA_ALG_SHA_256) != PSA_SUCCESS)
    {
        esp_ota_abort(s_ota_handle);
        notify_error(OTA_ERR_FLASH_WRITE);
        return;
    }

    memcpy(s_expected_sha, msg->sha256, sizeof(s_expected_sha));
    s_image_size = msg->image_size;
    s_bytes_received = 0;
    s_expected_seq = 0;
    s_since_ack = 0;
    s_overflow = false;
    s_state = OTA_STATE_RECEIVING;
    s_active = true;

    ESP_LOGI(TAG,
             "OTA started: %" PRIu32 " bytes -> '%s', version %u.%u.%u",
             s_image_size,
             s_target->label,
             msg->version[0],
             msg->version[1],
             msg->version[2]);
    notify_ready(s_image_size, CONFIG_ZAFIRA_OTA_ACK_WINDOW);
}

static void handle_data(const ota_msg_t *msg)
{
    if (s_state != OTA_STATE_RECEIVING)
    {
        notify_error(OTA_ERR_NOT_PERMITTED);
        return;
    }

    if (msg->seq != s_expected_seq)
    {
        ESP_LOGW(TAG, "Chunk out of order: got %u expected %u", msg->seq, s_expected_seq);
        session_fail(OTA_ERR_BAD_SEQUENCE);
        return;
    }

    if ((uint32_t)msg->len > s_image_size - s_bytes_received)
    {
        session_fail(OTA_ERR_TOO_LARGE);
        return;
    }

    esp_err_t err = esp_ota_write(s_ota_handle, msg->data, msg->len);
    if (err != ESP_OK)
    {
        ESP_LOGE(TAG, "esp_ota_write failed: %s", esp_err_to_name(err));
        session_fail(OTA_ERR_FLASH_WRITE);
        return;
    }

    if (psa_hash_update(&s_hash_op, msg->data, msg->len) != PSA_SUCCESS)
    {
        session_fail(OTA_ERR_FLASH_WRITE);
        return;
    }

    s_bytes_received += msg->len;
    s_expected_seq++;
    s_since_ack++;

    if (s_since_ack >= CONFIG_ZAFIRA_OTA_ACK_WINDOW || s_bytes_received == s_image_size)
    {
        s_since_ack = 0;
        notify_ack(s_bytes_received);
    }
}

static void handle_commit(void)
{
    if (s_state != OTA_STATE_RECEIVING)
    {
        notify_error(OTA_ERR_NOT_PERMITTED);
        return;
    }

    if (s_bytes_received != s_image_size)
    {
        ESP_LOGW(TAG,
                 "Commit with %" PRIu32 " of %" PRIu32 " bytes received",
                 s_bytes_received,
                 s_image_size);
        session_fail(OTA_ERR_INVALID_IMAGE);
        return;
    }

    uint8_t digest[32] = {0};
    size_t digest_len = 0;
    psa_status_t hash_status = psa_hash_finish(&s_hash_op, digest, sizeof(digest), &digest_len);
    if (hash_status != PSA_SUCCESS || digest_len != sizeof(digest) ||
        memcmp(digest, s_expected_sha, sizeof(digest)) != 0)
    {
        ESP_LOGE(TAG, "Image hash mismatch, discarding");
        esp_ota_abort(s_ota_handle);
        s_state = OTA_STATE_IDLE;
        session_reset();
        notify_error(OTA_ERR_HASH_MISMATCH);
        return;
    }
    // esp_ota_end validates the image header and checksum.
    esp_err_t err = esp_ota_end(s_ota_handle);
    s_state = OTA_STATE_IDLE;
    if (err != ESP_OK)
    {
        ESP_LOGE(TAG, "esp_ota_end failed: %s", esp_err_to_name(err));
        session_reset();
        notify_error(err == ESP_ERR_OTA_VALIDATE_FAILED ? OTA_ERR_INVALID_IMAGE : OTA_ERR_FLASH_WRITE);
        return;
    }

    err = esp_ota_set_boot_partition(s_target);
    if (err != ESP_OK)
    {
        ESP_LOGE(TAG, "esp_ota_set_boot_partition failed: %s", esp_err_to_name(err));
        session_reset();
        notify_error(OTA_ERR_FLASH_WRITE);
        return;
    }

    s_state = OTA_STATE_COMMITTED;
    ESP_LOGI(TAG, "OTA committed to '%s', rebooting", s_target->label);
    notify_simple(OTA_EVT_COMMITTED);
    vTaskDelay(pdMS_TO_TICKS(OTA_COMMIT_FLUSH_MS));
    esp_restart();
}

static void ota_task(void *param)
{
    (void)param;
    ota_msg_t msg;

    for (;;)
    {
        if (xQueueReceive(s_queue, &msg, pdMS_TO_TICKS(1000)) == pdPASS)
        {
            s_last_activity = xTaskGetTickCount();

            switch (msg.kind)
            {
            case OTA_MSG_START:
                handle_start(&msg);
                break;
            case OTA_MSG_DATA:
                handle_data(&msg);
                free(msg.data);
                break;
            case OTA_MSG_COMMIT:
                handle_commit();
                break;
            case OTA_MSG_ABORT:
                ESP_LOGI(TAG, "OTA aborted by host");
                session_reset();
                break;
            case OTA_MSG_DISCONNECT:
                if (s_state == OTA_STATE_RECEIVING)
                {
                    ESP_LOGW(TAG, "Link lost mid-transfer, discarding partial image");
                    session_reset();
                }
                break;
            }
            continue;
        }

        if (s_state != OTA_STATE_RECEIVING)
        {
            continue;
        }

        if (s_overflow)
        {
            ESP_LOGW(TAG, "Chunk queue overflowed, aborting transfer");
            session_fail(OTA_ERR_OVERFLOW);
            continue;
        }

        if (xTaskGetTickCount() - s_last_activity > pdMS_TO_TICKS(CONFIG_ZAFIRA_OTA_TIMEOUT_S * 1000))
        {
            ESP_LOGW(TAG, "OTA inactivity timeout");
            session_fail(OTA_ERR_TIMEOUT);
        }
    }
}

esp_err_t ota_service_init(ota_notify_fn notify)
{
    s_notify = notify;

    if (psa_crypto_init() != PSA_SUCCESS)
    {
        ESP_LOGE(TAG, "psa_crypto_init failed");
        return ESP_FAIL;
    }

    const esp_partition_t *next = esp_ota_get_next_update_partition(NULL);
    if (next == NULL)
    {
        ESP_LOGE(TAG, "No OTA slot available; check the partition table");
        return ESP_ERR_NOT_SUPPORTED;
    }
    s_slot_size = next->size;

    esp_ota_img_states_t img_state;
    const esp_partition_t *running = esp_ota_get_running_partition();
    if (running != NULL && esp_ota_get_state_partition(running, &img_state) == ESP_OK)
    {
        s_pending_verify = (img_state == ESP_OTA_IMG_PENDING_VERIFY);
    }
    ESP_LOGI(TAG,
             "OTA ready: running '%s', target '%s' (%" PRIu32 " bytes), pending_verify=%d",
             running != NULL ? running->label : "?",
             next->label,
             s_slot_size,
             (int)s_pending_verify);

    s_queue = xQueueCreate(OTA_QUEUE_DEPTH, sizeof(ota_msg_t));
    if (s_queue == NULL)
    {
        return ESP_ERR_NO_MEM;
    }

    if (xTaskCreatePinnedToCore(ota_task,
                                "ota_task",
                                OTA_TASK_STACK_SIZE,
                                NULL,
                                tskIDLE_PRIORITY + 2,
                                NULL,
                                OTA_TASK_CORE) != pdPASS)
    {
        vQueueDelete(s_queue);
        s_queue = NULL;
        return ESP_ERR_NO_MEM;
    }

    s_last_activity = xTaskGetTickCount();
    return ESP_OK;
}

uint32_t ota_service_slot_size(void)
{
    return s_slot_size;
}

bool ota_service_is_active(void)
{
    return s_active;
}

uint8_t ota_service_image_state(void)
{
    return s_pending_verify ? 1 : 0;
}

void ota_service_mark_valid(void)
{
    if (!s_pending_verify)
    {
        return;
    }

    esp_err_t err = esp_ota_mark_app_valid_cancel_rollback();
    if (err != ESP_OK)
    {
        ESP_LOGW(TAG, "Failed to confirm running image: %s", esp_err_to_name(err));
        return;
    }

    s_pending_verify = false;
    ESP_LOGI(TAG, "Running image confirmed, rollback cancelled");
}

void ota_service_on_disconnect(void)
{
    if (s_queue == NULL || !s_active)
    {
        return;
    }

    ota_msg_t msg = {.kind = OTA_MSG_DISCONNECT};
    xQueueSend(s_queue, &msg, 0);
}

int ota_service_handle_control(const uint8_t *data, uint16_t len)
{
    if (s_queue == NULL || len < 1)
    {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    ota_msg_t msg = {0};

    switch (data[0])
    {
    case OTA_CMD_START:
        if (len != OTA_START_PAYLOAD_LEN)
        {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        msg.kind = OTA_MSG_START;
        memcpy(&msg.image_size, &data[1], sizeof(msg.image_size));
        memcpy(msg.version, &data[5], sizeof(msg.version));
        memcpy(msg.sha256, &data[8], sizeof(msg.sha256));
        break;

    case OTA_CMD_ABORT:
        msg.kind = OTA_MSG_ABORT;
        break;

    case OTA_CMD_COMMIT:
        msg.kind = OTA_MSG_COMMIT;
        break;

    default:
        return BLE_ATT_ERR_REQ_NOT_SUPPORTED;
    }

    if (xQueueSend(s_queue, &msg, 0) != pdPASS)
    {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    return 0;
}

int ota_service_handle_data(const uint8_t *data, uint16_t len)
{
    if (s_queue == NULL || len < 2)
    {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    if (!s_active)
    {
        return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    }

    uint16_t payload_len = len - 2;
    if (payload_len > OTA_MAX_CHUNK)
    {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    ota_msg_t msg = {
        .kind = OTA_MSG_DATA,
        .seq = (uint16_t)(data[0] | ((uint16_t)data[1] << 8)),
        .len = payload_len,
        .data = malloc(payload_len),
    };
    if (msg.data == NULL)
    {
        s_overflow = true;
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    memcpy(msg.data, &data[2], payload_len);

    if (xQueueSend(s_queue, &msg, 0) != pdPASS)
    {
        free(msg.data);
        s_overflow = true;
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    return 0;
}
