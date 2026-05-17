#include "sdkconfig.h"

#include <stdbool.h>
#include <inttypes.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "argtable3/argtable3.h"
#include "esp_check.h"
#include "esp_console.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "nvs.h"
#include "nvs_flash.h"

#include "config.h"

static const char TAG[] = "CONFIG";
static nvs_handle_t handle;

typedef enum {
    CONFIG_TYPE_U8,
    CONFIG_TYPE_U32,
    CONFIG_TYPE_STR,
} config_type_t;

typedef struct {
    const char *name;
    config_type_t type;
    size_t buffer_size;
} config_key_t;

static const config_key_t config_keys[CONFIG_INDEX_MAX] = {
    [CONFIG_INDEX_NODEID]         = { "nodeid",        CONFIG_TYPE_STR, CONFIG_NODEID_BUFFER_SIZE },
    [CONFIG_INDEX_AUTOSTART_CHAN] = { "autostartchan", CONFIG_TYPE_U32, 0 },
    [CONFIG_INDEX_BROADCAST_ONLY] = { "broadcastonly", CONFIG_TYPE_U8,  0 },
};

static const config_key_t *key_for_index(config_index_t index)
{
    if (index < 0 || index >= CONFIG_INDEX_MAX) {
        return NULL;
    }
    return &config_keys[index];
}

static const config_key_t *key_find(const char *name, config_index_t *index_out)
{
    for (config_index_t i = 0; i < CONFIG_INDEX_MAX; ++i) {
        if (strcmp(name, config_keys[i].name) == 0) {
            if (index_out) {
                *index_out = i;
            }
            return &config_keys[i];
        }
    }
    return NULL;
}

void config_init(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(nvs_open("its", NVS_READWRITE, &handle));
}

static esp_err_t default_nodeid(char *out, size_t *size)
{
    uint8_t mac[6] = {0};
    esp_err_t err = esp_read_mac(mac, ESP_MAC_WIFI_STA);
    if (err != ESP_OK) {
        err = esp_read_mac(mac, ESP_MAC_BASE);
    }
    if (err != ESP_OK) {
        return err;
    }

    const int written = snprintf(out, *size, "%02x%02x%02x%02x%02x%02x",
                                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    if (written < 0) {
        return ESP_ERR_INVALID_STATE;
    }
    if ((size_t)written >= *size) {
        return ESP_ERR_INVALID_SIZE;
    }

    *size = (size_t)written + 1;
    return ESP_OK;
}

esp_err_t config_get_u8(config_index_t index, uint8_t *out)
{
    const config_key_t *key = key_for_index(index);
    if (!key || !out || key->type != CONFIG_TYPE_U8) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = nvs_get_u8(handle, key->name, out);
    if (err == ESP_ERR_NVS_NOT_FOUND && index == CONFIG_INDEX_BROADCAST_ONLY) {
#ifdef CONFIG_CITS_BROADCAST_ONLY
        *out = 1;
#else
        *out = 0;
#endif
        return ESP_OK;
    }
    return err;
}

esp_err_t config_set_u8(config_index_t index, uint8_t value)
{
    const config_key_t *key = key_for_index(index);
    if (!key || key->type != CONFIG_TYPE_U8) {
        return ESP_ERR_INVALID_ARG;
    }

    ESP_RETURN_ON_ERROR(nvs_set_u8(handle, key->name, value), TAG, "nvs_set_u8 failed");
    return nvs_commit(handle);
}

esp_err_t config_get_u32(config_index_t index, uint32_t *out)
{
    const config_key_t *key = key_for_index(index);
    if (!key || !out || key->type != CONFIG_TYPE_U32) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = nvs_get_u32(handle, key->name, out);
    if (err == ESP_ERR_NVS_NOT_FOUND && index == CONFIG_INDEX_AUTOSTART_CHAN) {
        *out = CONFIG_CITS_DEFAULT_FREQUENCY_MHZ;
        return ESP_OK;
    }
    return err;
}

esp_err_t config_set_u32(config_index_t index, uint32_t value)
{
    const config_key_t *key = key_for_index(index);
    if (!key || key->type != CONFIG_TYPE_U32) {
        return ESP_ERR_INVALID_ARG;
    }

    ESP_RETURN_ON_ERROR(nvs_set_u32(handle, key->name, value), TAG, "nvs_set_u32 failed");
    return nvs_commit(handle);
}

esp_err_t config_get_str(config_index_t index, char *out, size_t *size)
{
    const config_key_t *key = key_for_index(index);
    if (!key || !out || !size || key->type != CONFIG_TYPE_STR) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = nvs_get_str(handle, key->name, out, size);
    if (err == ESP_ERR_NVS_NOT_FOUND && index == CONFIG_INDEX_NODEID) {
        return default_nodeid(out, size);
    }
    return err;
}

esp_err_t config_set_str(config_index_t index, const char *value)
{
    const config_key_t *key = key_for_index(index);
    if (!key || !value || key->type != CONFIG_TYPE_STR) {
        return ESP_ERR_INVALID_ARG;
    }
    if (strlen(value) >= key->buffer_size) {
        return ESP_ERR_INVALID_SIZE;
    }

    ESP_RETURN_ON_ERROR(nvs_set_str(handle, key->name, value), TAG, "nvs_set_str failed");
    return nvs_commit(handle);
}

static struct {
    arg_str_t *key;
    arg_str_t *value;
    arg_end_t *end;
} config_set_args;

static int cmd_config_set(int argc, char **argv)
{
    const int nerrors = arg_parse(argc, argv, (void **)&config_set_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, config_set_args.end, argv[0]);
        return 1;
    }

    config_index_t index;
    const config_key_t *key = key_find(config_set_args.key->sval[0], &index);
    if (!key) {
        ESP_LOGE(TAG, "unknown key: %s", config_set_args.key->sval[0]);
        return 1;
    }

    esp_err_t err;
    switch (key->type) {
    case CONFIG_TYPE_U8: {
        const unsigned long value = strtoul(config_set_args.value->sval[0], NULL, 0);
        if (value > UINT8_MAX) {
            ESP_LOGE(TAG, "value out of range for u8");
            return 1;
        }
        err = config_set_u8(index, (uint8_t)value);
        break;
    }
    case CONFIG_TYPE_U32: {
        const unsigned long value = strtoul(config_set_args.value->sval[0], NULL, 0);
        if (value > UINT32_MAX) {
            ESP_LOGE(TAG, "value out of range for u32");
            return 1;
        }
        err = config_set_u32(index, (uint32_t)value);
        break;
    }
    case CONFIG_TYPE_STR:
        err = config_set_str(index, config_set_args.value->sval[0]);
        break;
    default:
        err = ESP_ERR_INVALID_ARG;
        break;
    }

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "failed to set %s: %s", key->name, esp_err_to_name(err));
        return 1;
    }

    printf("%s set\n", key->name);
    return 0;
}

static struct {
    arg_str_t *key;
    arg_end_t *end;
} config_get_args;

static int cmd_config_get(int argc, char **argv)
{
    const int nerrors = arg_parse(argc, argv, (void **)&config_get_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, config_get_args.end, argv[0]);
        return 1;
    }

    config_index_t index;
    const config_key_t *key = key_find(config_get_args.key->sval[0], &index);
    if (!key) {
        ESP_LOGE(TAG, "unknown key: %s", config_get_args.key->sval[0]);
        return 1;
    }

    esp_err_t err;
    switch (key->type) {
    case CONFIG_TYPE_U8: {
        uint8_t value = 0;
        err = config_get_u8(index, &value);
        if (err == ESP_OK) {
            printf("%u\n", value);
        }
        break;
    }
    case CONFIG_TYPE_U32: {
        uint32_t value = 0;
        err = config_get_u32(index, &value);
        if (err == ESP_OK) {
            printf("%" PRIu32 "\n", value);
        }
        break;
    }
    case CONFIG_TYPE_STR: {
        char value[CONFIG_NODEID_BUFFER_SIZE];
        size_t size = sizeof(value);
        err = config_get_str(index, value, &size);
        if (err == ESP_OK) {
            printf("%s\n", value);
        }
        break;
    }
    default:
        err = ESP_ERR_INVALID_ARG;
        break;
    }

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "failed to get %s: %s", key->name, esp_err_to_name(err));
        return 1;
    }

    return 0;
}

static int cmd_config_list(int argc, char **argv)
{
    (void)argc;
    (void)argv;

    for (config_index_t i = 0; i < CONFIG_INDEX_MAX; ++i) {
        printf("%s\n", config_keys[i].name);
    }
    return 0;
}

void config_register_commands(void)
{
    config_set_args.key = arg_str1(NULL, NULL, "key", "key to set");
    config_set_args.value = arg_str1(NULL, NULL, "value", "value to store");
    config_set_args.end = arg_end(1);

    const esp_console_cmd_t set_cmd = {
        .command = "config-set",
        .help = "set CITS-to-go config value in NVS",
        .hint = NULL,
        .func = &cmd_config_set,
        .argtable = &config_set_args,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&set_cmd));

    config_get_args.key = arg_str1(NULL, NULL, "key", "key to read");
    config_get_args.end = arg_end(1);

    const esp_console_cmd_t get_cmd = {
        .command = "config-get",
        .help = "read CITS-to-go config value from NVS",
        .hint = NULL,
        .func = &cmd_config_get,
        .argtable = &config_get_args,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&get_cmd));

    const esp_console_cmd_t list_cmd = {
        .command = "config-list",
        .help = "list available CITS-to-go config keys",
        .hint = NULL,
        .func = &cmd_config_list,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&list_cmd));
}
