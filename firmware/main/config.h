#pragma once

#include "esp_err.h"

#define CONFIG_NODEID_BUFFER_SIZE 64
#define CONFIG_MQTT_URI_BUFFER_SIZE 256
#define CONFIG_IPV4_BUFFER_SIZE 16

void config_init(void);

typedef enum {
    CONFIG_INDEX_NODEID,
    CONFIG_INDEX_MQTT_URI,
    CONFIG_INDEX_ETH_IP,
    CONFIG_INDEX_ETH_NETMASK,
    CONFIG_INDEX_ETH_GATEWAY,
    CONFIG_INDEX_ETH_DNS0,
    CONFIG_INDEX_ETH_DNS1,
    CONFIG_INDEX_ETH_DNS2,
    CONFIG_INDEX_AUTOSTART_CHAN,
    CONFIG_INDEX_BROADCAST_ONLY,
    CONFIG_INDEX_LED_BRIGHTNESS,
} config_index_t;

esp_err_t config_get_u8(config_index_t index, uint8_t *out);
esp_err_t config_set_u8(config_index_t index, uint8_t value);
esp_err_t config_get_u16(config_index_t index, uint16_t *out);
esp_err_t config_set_u16(config_index_t index, uint16_t value);
esp_err_t config_get_u32(config_index_t index, uint32_t *out);
esp_err_t config_set_u32(config_index_t index, uint32_t value);
esp_err_t config_get_u64(config_index_t index, uint64_t *out);
esp_err_t config_set_u64(config_index_t index, uint64_t value);
esp_err_t config_get_i8(config_index_t index, int8_t *out);
esp_err_t config_set_i8(config_index_t index, int8_t value);
esp_err_t config_get_i16(config_index_t index, int16_t *out);
esp_err_t config_set_i16(config_index_t index, int16_t value);
esp_err_t config_get_i32(config_index_t index, int32_t *out);
esp_err_t config_set_i32(config_index_t index, int32_t value);
esp_err_t config_get_i64(config_index_t index, int64_t *out);
esp_err_t config_set_i64(config_index_t index, int64_t value);

esp_err_t config_get_str(config_index_t index, char *out, size_t *size);
esp_err_t config_set_str(config_index_t index, const char *value);



void config_register_commands(void);
