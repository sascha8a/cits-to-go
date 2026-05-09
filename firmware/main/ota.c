#include "sdkconfig.h"

#include <stdbool.h>

#include "esp_crt_bundle.h"
#include "esp_console.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_http_client.h"
#include "esp_https_ota.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "argtable3/argtable3.h"

#define OTA_TASK_STACK_SIZE 2048
#define OTA_TASK_PRIORITY 20

static const char TAG[] = "OTA";

static struct {
    struct arg_str *url;
    struct arg_lit *reboot;
    struct arg_lit *confirm;
    struct arg_lit *rollback;
    struct arg_lit *switch_part;
    struct arg_lit *info;
    struct arg_end *end;
} ota_args;

static bool ota_task_running;
static TaskHandle_t ota_task_handle;

static char https_url[1024];
static bool reboot_after_update;

static esp_timer_handle_t invalidate_and_reboot_timer;

esp_http_client_config_t http_client_config = {
    .url = https_url,
    .crt_bundle_attach = esp_crt_bundle_attach
};

esp_https_ota_config_t ota_config = {
    .http_config = &http_client_config,
    .bulk_flash_erase = true,
    .buffer_caps = MALLOC_CAP_SPIRAM
};

static void ota_task(void *)
{
    esp_err_t res = esp_https_ota(&ota_config);
    if (res == ESP_OK)
    {
        if (reboot_after_update)
            esp_restart();
    }
    else
    {
        ESP_LOGE(TAG, "esp_https_ota failed: %s", esp_err_to_name(res));
    }

    ota_task_running = false;

    vTaskDelete(NULL);
}

static void ota_event_handler(void* arg, esp_event_base_t event_base,
                        int32_t event_id, void* event_data)
{
    if (event_base == ESP_HTTPS_OTA_EVENT) {
        switch (event_id) {
            case ESP_HTTPS_OTA_START:
                ESP_LOGI(TAG, "OTA started");
                break;
            case ESP_HTTPS_OTA_CONNECTED:
                ESP_LOGI(TAG, "Connected to server");
                break;
            case ESP_HTTPS_OTA_GET_IMG_DESC:
                ESP_LOGI(TAG, "Reading Image Description");
                break;
            case ESP_HTTPS_OTA_VERIFY_CHIP_ID:
                ESP_LOGI(TAG, "Verifying chip id of new image: %d", *(esp_chip_id_t *)event_data);
                break;
            case ESP_HTTPS_OTA_VERIFY_CHIP_REVISION:
                ESP_LOGI(TAG, "Verifying chip revision of new image: %d", *(uint16_t *)event_data);
                break;
            case ESP_HTTPS_OTA_DECRYPT_CB:
                ESP_LOGI(TAG, "Callback to decrypt function");
                break;
            case ESP_HTTPS_OTA_WRITE_FLASH:
                ESP_LOGD(TAG, "Writing to flash: %d written", *(int *)event_data);
                break;
            case ESP_HTTPS_OTA_UPDATE_BOOT_PARTITION:
                ESP_LOGI(TAG, "Boot partition updated. Next Partition: %d", *(esp_partition_subtype_t *)event_data);
                break;
            case ESP_HTTPS_OTA_FINISH:
                ESP_LOGI(TAG, "OTA finish");
                break;
            case ESP_HTTPS_OTA_ABORT:
                ESP_LOGI(TAG, "OTA abort");
                break;
        }
    }
}

static int ota_cmd(int argc, char **argv)
{
    int nerrors = arg_parse(argc, argv, (void **)&ota_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, ota_args.end, argv[0]);
        return 1;
    }

    if (ota_args.url->count + ota_args.confirm->count + ota_args.rollback->count + ota_args.switch_part->count + ota_args.info->count != 1)
    {
        ESP_LOGE(TAG, "Please specify exactly one operation to perform");
        return 1;
    }

    if (ota_args.reboot->count && !ota_args.url->count)
    {
        ESP_LOGE(TAG, "reboot is only valid when performing update");
        return 1;
    }

    if (ota_args.confirm->count)
    {
        int res = esp_ota_mark_app_valid_cancel_rollback();
        if (res != ESP_OK)
        {
            ESP_LOGE(TAG, "esp_ota_mark_app_valid_cancel_rollback failed: %s", esp_err_to_name(res));
            return 1;
        }

        res = esp_timer_stop(invalidate_and_reboot_timer);
        if (res != ESP_OK)
        {
            ESP_LOGW(TAG, "esp_timer_stop failed: %s. Maybe this app partition was already marked valid?", esp_err_to_name(res));
        }
    }

    if (ota_args.rollback->count)
    {
        esp_err_t res = esp_ota_mark_app_invalid_rollback_and_reboot();
        if (res != ESP_OK)
        {
            ESP_LOGE(TAG, "esp_ota_mark_app_invalid_rollback_and_reboot failed: %s", esp_err_to_name(res));
            return 1;
        }
    }

    if (ota_args.switch_part->count)
    {
        const esp_partition_t* other_app = esp_ota_get_next_update_partition(NULL);
        if (other_app == NULL)
        {
            ESP_LOGE(TAG, "Could not get non-running partition");
            return 1;
        }

        esp_err_t res = esp_ota_set_boot_partition(other_app);
        if (res != ESP_OK)
        {
            ESP_LOGE(TAG, "esp_ota_set_boot_partition failed: %s", esp_err_to_name(res));
            return 1;
        }
    }

    if (ota_args.url->count)
    {
        if (strlen(ota_args.url->sval[0]) >= sizeof(https_url))
        {
            ESP_LOGE(TAG, "URL too long");
            return 1;
        }

        strcpy(https_url, ota_args.url->sval[0]);
        reboot_after_update = ota_args.reboot->count;

        const esp_partition_t* other_app = esp_ota_get_next_update_partition(NULL);
        if (other_app == NULL)
        {
            ESP_LOGE(TAG, "Could not get non-running partition");
            return 1;
        }

        ota_config.partition.staging = other_app;

        xTaskCreate(ota_task, "ota", OTA_TASK_STACK_SIZE,
                    NULL, CONFIG_SNIFFER_TASK_PRIORITY, &ota_task_handle);
    }

    if (ota_args.info->count)
    {
        const esp_partition_t *current = esp_ota_get_running_partition();

        for (esp_partition_iterator_t it = esp_partition_find(ESP_PARTITION_TYPE_APP, ESP_PARTITION_SUBTYPE_ANY, NULL); it != NULL; it = esp_partition_next(it))
        {
            const esp_partition_t *part = esp_partition_get(it);
            esp_ota_img_states_t state;
            esp_err_t res = esp_ota_get_state_partition(part, &state);
            const char *state_string;

            if (res == ESP_OK)
            {
                switch (state)
                {
                case ESP_OTA_IMG_NEW:
                    state_string = "new";
                    break;
                case ESP_OTA_IMG_PENDING_VERIFY:
                    state_string = "pending";
                    break;
                case ESP_OTA_IMG_VALID:
                    state_string = "valid";
                    break;
                case ESP_OTA_IMG_INVALID:
                    state_string = "invalid";
                    break;
                case ESP_OTA_IMG_ABORTED:
                    state_string = "aborted";
                    break;
                case ESP_OTA_IMG_UNDEFINED:
                    state_string = "undefined";
                    break;
                default:
                    __builtin_unreachable();
                }
            }
            else
            {
                state_string = "???";
            }

            ESP_LOGI(TAG, "%c %-9s %s %#08x",
                     part == current ? '*' : ' ',
                     state_string,
                     part->label,
                     part->address);
        }
    }

    return 0;
}


void register_ota_cmd(void)
{
    ota_args.url = arg_str0("u", NULL, "<url>", "update over HTTPS URL");
    ota_args.reboot = arg_lit0("r", NULL, "reboot into new app after update");
    ota_args.confirm = arg_lit0("C", NULL, "confirm currently running app partition");
    ota_args.rollback = arg_lit0("R", NULL, "rollback to old app and reboot");
    ota_args.switch_part = arg_lit0("S", NULL, "switch to other app partition and reboot");
    ota_args.info = arg_lit0("i", NULL, "print partition info");
    ota_args.end = arg_end(1);
    const esp_console_cmd_t cmd = {
        .command = "ota",
        .help = "perform OTA operation",
        .hint = NULL,
        .func = &ota_cmd,
        .argtable = &ota_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

static void invalidate_and_reboot(void *)
{
    esp_err_t res = esp_ota_mark_app_invalid_rollback_and_reboot();
    if (res != ESP_OK)
        ESP_LOGE(TAG, "esp_ota_mark_app_invalid_rollback_and_reboot failed: %s", esp_err_to_name(res));
}

static void init_invalidate_and_reboot_timer(void)
{
    esp_timer_create_args_t create_args = {
        .callback = invalidate_and_reboot,
        .arg = NULL,
        .name = "ota_inval_reboot"
    };

    ESP_ERROR_CHECK(esp_timer_create(&create_args, &invalidate_and_reboot_timer));

    const esp_partition_t* other_app = esp_ota_get_next_update_partition(NULL);
    if (other_app == NULL)
    {
        ESP_LOGE(TAG, "Could not get non-running partition");
        return;
    }
    esp_ota_img_states_t other_state;
    esp_err_t res = esp_ota_get_state_partition(other_app, &other_state);
    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "esp_ota_get_state_partition failed: %s", esp_err_to_name(res));
        return;
    }

    const esp_partition_t *current_app = esp_ota_get_running_partition();
    esp_ota_img_states_t state;
    res = esp_ota_get_state_partition(current_app, &state);
    if ((res != ESP_OK || state != ESP_OTA_IMG_VALID) && other_state == ESP_OTA_IMG_VALID)
    {
        ESP_LOGW(TAG, "Current app partition is not marked valid, will perform rollback in 30 minutes if not confirmed by then!");
        esp_timer_start_once(invalidate_and_reboot_timer, (uint64_t)30 * 60 * 1000 * 1000);
    }
}

void ota_init(void)
{
    ESP_ERROR_CHECK(esp_event_handler_register(ESP_HTTPS_OTA_EVENT, ESP_EVENT_ANY_ID, ota_event_handler, NULL));

    init_invalidate_and_reboot_timer();
}
