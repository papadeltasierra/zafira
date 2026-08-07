#include <ctype.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs_flash.h"
#include "store/config/ble_store_config.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

// Provided by the NimBLE config store component but not exposed in a public header.
void ble_store_config_init(void);

#define BLE_MSG_MAX_LEN 256
#define DISPLAY_TASK_CORE 1
#define DISPLAY_TASK_STACK_SIZE 4096

static const char *TAG = "zafira_ble";
static uint8_t s_addr_type;
static ble_uuid128_t s_service_uuid;
static ble_uuid128_t s_media_info_char_uuid;
static ble_uuid128_t s_time_sync_char_uuid;
static uint16_t s_time_sync_char_handle;
static QueueHandle_t s_display_queue;

typedef struct
{
    uint16_t attr_handle;
    uint16_t payload_len;
    uint8_t payload[BLE_MSG_MAX_LEN];
} display_message_t;

static void log_bonded_peer_count(void)
{
    ble_addr_t peers[CONFIG_BT_NIMBLE_MAX_BONDS];
    int peer_count = CONFIG_BT_NIMBLE_MAX_BONDS;
    int rc = ble_store_util_bonded_peers(peers, &peer_count, CONFIG_BT_NIMBLE_MAX_BONDS);
    if (rc != 0)
    {
        ESP_LOGW(TAG, "Failed to query bonded peer count, rc=%d", rc);
        return;
    }

    ESP_LOGI(TAG, "Bonded peer count in NVS: %d", peer_count);
}

typedef enum
{
    MSG_KIND_EMPTY = 0,
    MSG_KIND_JSON_TEXT,
    MSG_KIND_ASCII_TEXT,
    MSG_KIND_BINARY,
} message_kind_t;

static bool is_valid_uuid_format(const char *uuid)
{
    if (uuid == NULL || strlen(uuid) != 36)
    {
        return false;
    }

    for (size_t i = 0; i < 36; ++i)
    {
        if (i == 8 || i == 13 || i == 18 || i == 23)
        {
            if (uuid[i] != '-')
            {
                return false;
            }
            continue;
        }

        if (!isxdigit((unsigned char)uuid[i]))
        {
            return false;
        }
    }

    return true;
}

static int hex_nibble(char c)
{
    if (c >= '0' && c <= '9')
    {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f')
    {
        return 10 + (c - 'a');
    }
    if (c >= 'A' && c <= 'F')
    {
        return 10 + (c - 'A');
    }
    return -1;
}

static esp_err_t parse_uuid128_le(const char *uuid_str, uint8_t out_le[16])
{
    if (!is_valid_uuid_format(uuid_str))
    {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t be[16] = {0};
    int byte_idx = 0;

    for (size_t i = 0; i < 36;)
    {
        if (uuid_str[i] == '-')
        {
            ++i;
            continue;
        }

        int hi = hex_nibble(uuid_str[i]);
        int lo = hex_nibble(uuid_str[i + 1]);
        if (hi < 0 || lo < 0)
        {
            return ESP_ERR_INVALID_ARG;
        }

        be[byte_idx++] = (uint8_t)((hi << 4) | lo);
        i += 2;
    }

    for (int i = 0; i < 16; ++i)
    {
        out_le[i] = be[15 - i];
    }

    return ESP_OK;
}

static message_kind_t classify_message(const uint8_t *data, uint16_t len)
{
    if (len == 0)
    {
        return MSG_KIND_EMPTY;
    }

    bool ascii = true;
    for (uint16_t i = 0; i < len; ++i)
    {
        uint8_t c = data[i];
        if (!(isprint(c) || c == '\r' || c == '\n' || c == '\t'))
        {
            ascii = false;
            break;
        }
    }

    if (!ascii)
    {
        return MSG_KIND_BINARY;
    }

    uint16_t start = 0;
    uint16_t end = len;

    while (start < end && isspace(data[start]))
    {
        ++start;
    }
    while (end > start && isspace(data[end - 1]))
    {
        --end;
    }

    if (end > start)
    {
        uint8_t first = data[start];
        uint8_t last = data[end - 1];
        if ((first == '{' && last == '}') || (first == '[' && last == ']'))
        {
            return MSG_KIND_JSON_TEXT;
        }
    }

    return MSG_KIND_ASCII_TEXT;
}

static const char *message_kind_to_str(message_kind_t kind)
{
    switch (kind)
    {
    case MSG_KIND_EMPTY:
        return "empty";
    case MSG_KIND_JSON_TEXT:
        return "json-text";
    case MSG_KIND_ASCII_TEXT:
        return "ascii-text";
    default:
        return "binary";
    }
}

static bool read_media_field(const uint8_t *payload,
                             uint16_t payload_len,
                             uint16_t *offset,
                             char *field,
                             size_t field_size)
{
    if (*offset >= payload_len)
    {
        return false;
    }

    uint8_t field_len = payload[(*offset)++];
    if ((uint16_t)(payload_len - *offset) < field_len || field_len >= field_size)
    {
        return false;
    }

    memcpy(field, &payload[*offset], field_len);
    field[field_len] = '\0';
    *offset += field_len;
    return true;
}

static bool log_media_payload(const uint8_t *payload, uint16_t payload_len)
{
    if (payload_len == 0)
    {
        return false;
    }

    uint8_t type = payload[0];
    uint16_t offset = 1;
    char first[BLE_MSG_MAX_LEN + 1] = {0};
    char second[BLE_MSG_MAX_LEN + 1] = {0};

    switch (type)
    {
    case 0x00:
        if (!read_media_field(payload, payload_len, &offset, first, sizeof(first)))
        {
            return false;
        }
        ESP_LOGI(TAG, "Media radio: station=%s", first);
        return offset == payload_len;

    case 0x01:
        if (!read_media_field(payload, payload_len, &offset, first, sizeof(first)) ||
            !read_media_field(payload, payload_len, &offset, second, sizeof(second)))
        {
            return false;
        }
        ESP_LOGI(TAG, "Media streaming: artist=%s track=%s", first, second);
        return offset == payload_len;

    case 0x02:
    case 0x03:
        if (!read_media_field(payload, payload_len, &offset, first, sizeof(first)))
        {
            return false;
        }
        ESP_LOGI(TAG, "Phone %s: %s", type == 0x02 ? "outgoing" : "incoming", first);
        return offset == payload_len;

    case 0xff:
        if (payload_len == 1)
        {
            ESP_LOGI(TAG, "Media idle");
            return true;
        }
        return false;

    default:
        return false;
    }
}

static bool log_rds_clock_time(const uint8_t *payload, uint16_t payload_len)
{
    if (payload_len != 5 || (payload[4] & 0x3f) != 0)
    {
        return false;
    }

    uint32_t modified_julian_date = ((uint32_t)payload[0] << 9) |
                                    ((uint32_t)payload[1] << 1) |
                                    (payload[2] >> 7);
    uint8_t utc_hour = (payload[2] >> 2) & 0x1f;
    uint8_t utc_minute = ((payload[2] & 0x03) << 4) | (payload[3] >> 4);
    bool offset_is_negative = (payload[3] & 0x08) != 0;
    uint8_t offset_half_hours = ((payload[3] & 0x07) << 2) | (payload[4] >> 6);

    if (utc_hour > 23 || utc_minute > 59)
    {
        return false;
    }

    int offset_minutes = offset_half_hours * 30;
    if (offset_is_negative)
    {
        offset_minutes = -offset_minutes;
    }

    ESP_LOGI(TAG,
             "RDS clock time: MJD=%" PRIu32 " UTC=%02u:%02u local-offset=%+d minutes",
             modified_julian_date,
             utc_hour,
             utc_minute,
             offset_minutes);
    return true;
}

static void process_display_message(const display_message_t *message)
{
    if (message->attr_handle == s_time_sync_char_handle)
    {
        if (!log_rds_clock_time(message->payload, message->payload_len))
        {
            ESP_LOGW(TAG, "Invalid RDS clock-time payload: len=%u", message->payload_len);
        }
        return;
    }

    if (log_media_payload(message->payload, message->payload_len))
    {
        return;
    }

    message_kind_t kind = classify_message(message->payload, message->payload_len);
    ESP_LOGI(TAG, "BLE message received: kind=%s len=%u", message_kind_to_str(kind), message->payload_len);

    if (kind == MSG_KIND_ASCII_TEXT || kind == MSG_KIND_JSON_TEXT || kind == MSG_KIND_EMPTY)
    {
        char text[BLE_MSG_MAX_LEN + 1] = {0};
        memcpy(text, message->payload, message->payload_len);
        ESP_LOGI(TAG, "BLE message text: %s", text);
    }
    else
    {
        ESP_LOG_BUFFER_HEX_LEVEL(TAG, message->payload, message->payload_len, ESP_LOG_INFO);
    }
}

static void display_task(void *param)
{
    (void)param;
    display_message_t message;

    for (;;)
    {
        if (xQueueReceive(s_display_queue, &message, portMAX_DELAY) == pdPASS)
        {
            process_display_message(&message);
        }
    }
}

static int gatt_message_write(uint16_t conn_handle,
                              uint16_t attr_handle,
                              struct ble_gatt_access_ctxt *ctxt,
                              void *arg)
{
    (void)conn_handle;
    (void)arg;

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR)
    {
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint16_t msg_len = OS_MBUF_PKTLEN(ctxt->om);
    if (msg_len > BLE_MSG_MAX_LEN)
    {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    display_message_t message = {
        .attr_handle = attr_handle,
        .payload_len = msg_len,
    };
    uint16_t copied = 0;
    int rc = ble_hs_mbuf_to_flat(ctxt->om, message.payload, sizeof(message.payload), &copied);
    if (rc != 0 || copied != msg_len)
    {
        return BLE_ATT_ERR_UNLIKELY;
    }

    if (xQueueSend(s_display_queue, &message, 0) != pdPASS)
    {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    return 0;
}

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &s_service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &s_media_info_char_uuid.u,
                .access_cb = gatt_message_write,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &s_time_sync_char_uuid.u,
                .access_cb = gatt_message_write,
                .val_handle = &s_time_sync_char_handle,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {0},
        },
    },
    {0},
};

static void start_advertising(void);

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type)
    {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0)
        {
            ESP_LOGI(TAG, "Phone connected (handle=%d)", event->connect.conn_handle);
            ble_gattc_exchange_mtu(event->connect.conn_handle, NULL, NULL);
            ble_gap_security_initiate(event->connect.conn_handle);
        }
        else
        {
            ESP_LOGI(TAG, "Connection attempt failed (status=%d), restarting advertising", event->connect.status);
            start_advertising();
        }
        break;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU negotiated (handle=%d, mtu=%d)", event->mtu.conn_handle, event->mtu.value);
        break;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "Phone disconnected (reason=%d), restarting advertising", event->disconnect.reason);
        start_advertising();
        break;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        ESP_LOGI(TAG, "Advertising complete, restarting");
        start_advertising();
        break;

    case BLE_GAP_EVENT_ENC_CHANGE:
    {
        struct ble_gap_conn_desc desc;
        int rc = ble_gap_conn_find(event->enc_change.conn_handle, &desc);
        if (rc == 0)
        {
            ESP_LOGI(TAG,
                     "Encryption change (handle=%d, status=%d, encrypted=%d, bonded=%d, key_size=%d)",
                     event->enc_change.conn_handle,
                     event->enc_change.status,
                     desc.sec_state.encrypted,
                     desc.sec_state.bonded,
                     desc.sec_state.key_size);
        }
        else
        {
            ESP_LOGI(TAG,
                     "Encryption change (handle=%d, status=%d) (conn_desc unavailable rc=%d)",
                     event->enc_change.conn_handle,
                     event->enc_change.status,
                     rc);
        }
        break;
    }

    case BLE_GAP_EVENT_PARING_COMPLETE:
        ESP_LOGI(TAG,
                 "Pairing complete (handle=%d, status=%d)",
                 event->pairing_complete.conn_handle,
                 event->pairing_complete.status);
        log_bonded_peer_count();
        break;

    case BLE_GAP_EVENT_IDENTITY_RESOLVED:
        ESP_LOGI(TAG, "Identity resolved for peer after RPA/private address rotation");
        break;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
    {
        // Central has fresh keys; discard the stale bond and re-pair.
        struct ble_gap_conn_desc desc;
        int rc = ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc);
        assert(rc == 0);
        ESP_LOGW(TAG,
                 "Repeat pairing requested for peer; deleting stored keys and retrying (conn_handle=%d)",
                 event->repeat_pairing.conn_handle);
        ble_store_util_delete_peer(&desc.peer_id_addr);
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }

    default:
        break;
    }

    return 0;
}

static void start_advertising(void)
{
    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof(fields));

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;

    fields.name = (uint8_t *)CONFIG_ZAFIRA_BLE_DEVICE_NAME;
    fields.name_len = strlen(CONFIG_ZAFIRA_BLE_DEVICE_NAME);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0)
    {
        ESP_LOGE(TAG, "ble_gap_adv_set_fields failed, rc=%d", rc);
        return;
    }

    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER, &adv_params, gap_event, NULL);
    if (rc != 0)
    {
        ESP_LOGE(TAG, "ble_gap_adv_start failed, rc=%d", rc);
        return;
    }

    ESP_LOGI(TAG, "Advertising as '%s'", CONFIG_ZAFIRA_BLE_DEVICE_NAME);
}

static void ble_app_on_sync(void)
{
    int rc = ble_hs_id_infer_auto(0, &s_addr_type);
    if (rc != 0)
    {
        ESP_LOGE(TAG, "ble_hs_id_infer_auto failed, rc=%d", rc);
        return;
    }

    ble_att_set_preferred_mtu(CONFIG_ZAFIRA_BLE_MTU);

    uint8_t addr_val[6] = {0};
    rc = ble_hs_id_copy_addr(s_addr_type, addr_val, NULL);
    if (rc == 0)
    {
        ESP_LOGI(TAG,
                 "BLE address: %02x:%02x:%02x:%02x:%02x:%02x",
                 addr_val[5],
                 addr_val[4],
                 addr_val[3],
                 addr_val[2],
                 addr_val[1],
                 addr_val[0]);
    }

    start_advertising();
}

static void ble_host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND)
    {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    s_display_queue = xQueueCreate(CONFIG_ZAFIRA_DISPLAY_QUEUE_DEPTH, sizeof(display_message_t));
    if (s_display_queue == NULL)
    {
        ESP_LOGE(TAG, "Failed to create display queue");
        return;
    }

    if (xTaskCreatePinnedToCore(display_task,
                                "display_task",
                                DISPLAY_TASK_STACK_SIZE,
                                NULL,
                                tskIDLE_PRIORITY + 1,
                                NULL,
                                DISPLAY_TASK_CORE) != pdPASS)
    {
        ESP_LOGE(TAG, "Failed to create display task");
        vQueueDelete(s_display_queue);
        s_display_queue = NULL;
        return;
    }

    uint8_t service_uuid_le[16] = {0};
    err = parse_uuid128_le(CONFIG_ZAFIRA_BLE_PROFILE_UUID, service_uuid_le);
    if (err != ESP_OK)
    {
        ESP_LOGE(TAG,
                 "Invalid CONFIG_ZAFIRA_BLE_PROFILE_UUID format: %s. Expected canonical 128-bit UUID.",
                 CONFIG_ZAFIRA_BLE_PROFILE_UUID);
        return;
    }

    s_service_uuid.u.type = BLE_UUID_TYPE_128;
    memcpy(s_service_uuid.value, service_uuid_le, sizeof(service_uuid_le));

    // Characteristic UUIDs match BleConstants.MEDIA_INFO_CHAR_UUID (...8902) and TIME_SYNC_CHAR_UUID (...8903).
    s_media_info_char_uuid = s_service_uuid;
    s_media_info_char_uuid.value[0] = 0x02;
    s_time_sync_char_uuid = s_service_uuid;
    s_time_sync_char_uuid.value[0] = 0x03;

    nimble_port_init();

#if CONFIG_BT_NIMBLE_NVS_PERSIST
    ESP_LOGI(TAG, "NimBLE key persistence: ENABLED (NVS-backed)");
#else
    ESP_LOGW(TAG, "NimBLE key persistence: DISABLED (bond keys will not survive reboot)");
#endif

    ble_store_config_init();
    ESP_LOGI(TAG, "NimBLE key store initialized");
    log_bonded_peer_count();

    ble_hs_cfg.sync_cb = ble_app_on_sync;

    // Enable bonding so NimBLE persists the LTK to NVS across resets.
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_svc_gap_device_name_set(CONFIG_ZAFIRA_BLE_DEVICE_NAME);
    if (rc != 0)
    {
        ESP_LOGE(TAG, "ble_svc_gap_device_name_set failed, rc=%d", rc);
        return;
    }

    rc = ble_gatts_count_cfg(gatt_svcs);
    if (rc != 0)
    {
        ESP_LOGE(TAG, "ble_gatts_count_cfg failed, rc=%d", rc);
        return;
    }

    rc = ble_gatts_add_svcs(gatt_svcs);
    if (rc != 0)
    {
        ESP_LOGE(TAG, "ble_gatts_add_svcs failed, rc=%d", rc);
        return;
    }

    nimble_port_freertos_init(ble_host_task);
}
