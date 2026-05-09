#include "sdkconfig.h"

#if CONFIG_ENABLE_SD
#include <string.h>

#include "esp_console.h"
#include "esp_log.h"
#include "esp_vfs_fat.h"
#include "sdmmc_cmd.h"
#include "argtable3/argtable3.h"
#include "driver/sdmmc_host.h"
#include "driver/sdspi_host.h"
#include "driver/spi_common.h"

#include "sdcard.h"

#if CONFIG_SNIFFER_SD_SPI_MODE
#define PIN_NUM_MISO CONFIG_SNIFFER_PIN_MISO
#define PIN_NUM_MOSI CONFIG_SNIFFER_PIN_MOSI
#define PIN_NUM_CLK  CONFIG_SNIFFER_PIN_CLK
#define PIN_NUM_CS   CONFIG_SNIFFER_PIN_CS
#endif  // CONFIG_SNIFFER_SD_SPI_MODE

static const char TAG[] = "SDCARD";

static struct {
    struct arg_str *device;
    struct arg_end *end;
} mount_args;

static sdmmc_card_t *card = NULL;

/** 'mount' command */
static int mount(int argc, char **argv)
{
    esp_err_t ret;

    int nerrors = arg_parse(argc, argv, (void **)&mount_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, mount_args.end, argv[0]);
        return 1;
    }
    /* mount sd card */
    if (!strncmp(mount_args.device->sval[0], "sd", 2)) {
        ESP_LOGI(TAG, "Initializing SD card");
        esp_vfs_fat_sdmmc_mount_config_t mount_config = {
            .format_if_mount_failed = true,
            .max_files = 4,
            .allocation_unit_size = 16 * 1024
        };

        // initialize SD card and mount FAT filesystem.
#if CONFIG_SNIFFER_SD_SPI_MODE
        // This assumes that the SPI host has been initialized elsewhere.
        ESP_LOGI(TAG, "Using SPI peripheral");
        sdmmc_host_t host = SDSPI_HOST_DEFAULT();

        // This initializes the slot without card detect (CD) and write protect (WP) signals.
        // Modify slot_config.gpio_cd and slot_config.gpio_wp if your board has these signals.
        sdspi_device_config_t slot_config = SDSPI_DEVICE_CONFIG_DEFAULT();
        slot_config.gpio_cs = PIN_NUM_CS;
        slot_config.host_id = host.slot;

        ret = esp_vfs_fat_sdspi_mount(CONFIG_SNIFFER_MOUNT_POINT, &host, &slot_config, &mount_config, &card);
#else
        ESP_LOGI(TAG, "Using SDMMC peripheral");
        sdmmc_host_t host = SDMMC_HOST_DEFAULT();
        sdmmc_slot_config_t slot_config = SDMMC_SLOT_CONFIG_DEFAULT();

        gpio_set_pull_mode(15, GPIO_PULLUP_ONLY); // CMD, needed in 4- and 1-line modes
        gpio_set_pull_mode(2, GPIO_PULLUP_ONLY);  // D0, needed in 4- and 1-line modes
        gpio_set_pull_mode(4, GPIO_PULLUP_ONLY);  // D1, needed in 4-line mode only
        gpio_set_pull_mode(12, GPIO_PULLUP_ONLY); // D2, needed in 4-line mode only
        gpio_set_pull_mode(13, GPIO_PULLUP_ONLY); // D3, needed in 4- and 1-line modes

        ret = esp_vfs_fat_sdmmc_mount(CONFIG_SNIFFER_MOUNT_POINT, &host, &slot_config, &mount_config, &card);
#endif

        if (ret != ESP_OK) {
            if (ret == ESP_FAIL) {
                ESP_LOGE(TAG, "Failed to mount filesystem. "
                         "If you want the card to be formatted, set format_if_mount_failed = true.");
            } else {
                ESP_LOGE(TAG, "Failed to initialize the card (%s). "
                         "Make sure SD card lines have pull-up resistors in place.",
                         esp_err_to_name(ret));
            }
            return 1;
        }
        /* print card info if mount successfully */
        sdmmc_card_print_info(stdout, card);
    }
    return 0;
}

void register_mount(void)
{
    mount_args.device = arg_str1(NULL, NULL, "<sd>", "choose a proper device to mount/unmount");
    mount_args.end = arg_end(1);
    const esp_console_cmd_t cmd = {
        .command = "mount",
        .help = "mount the filesystem",
        .hint = NULL,
        .func = &mount,
        .argtable = &mount_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

static int unmount(int argc, char **argv)
{
    int nerrors = arg_parse(argc, argv, (void **)&mount_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, mount_args.end, argv[0]);
        return 1;
    }
    /* unmount sd card */
    if (!strncmp(mount_args.device->sval[0], "sd", 2)) {
        if (esp_vfs_fat_sdcard_unmount(CONFIG_SNIFFER_MOUNT_POINT, card) != ESP_OK) {
            ESP_LOGE(TAG, "Card unmount failed");
            return -1;
        }
        card = NULL;
        ESP_LOGI(TAG, "Card unmounted");
    }
    return 0;
}

void register_unmount(void)
{
    mount_args.device = arg_str1(NULL, NULL, "<sd>", "choose a proper device to mount/unmount");
    mount_args.end = arg_end(1);
    const esp_console_cmd_t cmd = {
        .command = "unmount",
        .help = "unmount the filesystem",
        .hint = NULL,
        .func = &unmount,
        .argtable = &mount_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

void sdcard_register_commands(void)
{
    register_mount();
    register_unmount();
}

#endif // CONFIG_ENABLE_SD
