idf_component_register(SRCS "ble_endpoint.c" "ota_service.c"
                    INCLUDE_DIRS "."
                    REQUIRES bt nvs_flash app_update esp_partition mbedtls opel_mid)
