#pragma once

#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#define CONFIG_NODEID_BUFFER_SIZE 64

typedef enum {
    CONFIG_INDEX_NODEID,
    CONFIG_INDEX_AUTOSTART_CHAN,
    CONFIG_INDEX_BROADCAST_ONLY,
    CONFIG_INDEX_MAX,
} config_index_t;

void config_init(void);

esp_err_t config_get_u8(config_index_t index, uint8_t *out);
esp_err_t config_set_u8(config_index_t index, uint8_t value);
esp_err_t config_get_u32(config_index_t index, uint32_t *out);
esp_err_t config_set_u32(config_index_t index, uint32_t value);
esp_err_t config_get_str(config_index_t index, char *out, size_t *size);
esp_err_t config_set_str(config_index_t index, const char *value);

void config_register_commands(void);
