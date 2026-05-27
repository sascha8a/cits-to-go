#include "sdkconfig.h"

#include <stdio.h>
#include <string.h>

#include "esp_console.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "nvs_flash.h"

#include "cmd_sniffer.h"
#include "config.h"
#include "led.h"
#include "serial_logger.h"

static const char TAG[] = "MAIN";
static esp_console_repl_t *repl;

static void initialize_wifi(void)
{
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL));
}

static void console_init(void)
{
    char nodeid[CONFIG_NODEID_BUFFER_SIZE];
    size_t size = sizeof(nodeid);
    if (config_get_str(CONFIG_INDEX_NODEID, nodeid, &size) != ESP_OK) {
        snprintf(nodeid, sizeof(nodeid), "cits-to-go");
    }

    static char prompt[CONFIG_NODEID_BUFFER_SIZE + 2];
    snprintf(prompt, sizeof(prompt), "%s>", nodeid);

    esp_console_repl_config_t repl_config = ESP_CONSOLE_REPL_CONFIG_DEFAULT();
    repl_config.prompt = prompt;

#if CONFIG_ESP_CONSOLE_UART
    esp_console_dev_uart_config_t uart_config = ESP_CONSOLE_DEV_UART_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_uart(&uart_config, &repl_config, &repl));
#elif CONFIG_ESP_CONSOLE_USB_CDC
    esp_console_dev_usb_cdc_config_t cdc_config = ESP_CONSOLE_DEV_CDC_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_cdc(&cdc_config, &repl_config, &repl));
#elif CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG
    esp_console_dev_usb_serial_jtag_config_t usbjtag_config = ESP_CONSOLE_DEV_USB_SERIAL_JTAG_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_serial_jtag(&usbjtag_config, &repl_config, &repl));
#else
#error "No ESP console device is enabled"
#endif
}

static int cmd_reboot(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    esp_restart();
    return 0;
}

static void register_reboot(void)
{
    const esp_console_cmd_t cmd = {
        .command = "reboot",
        .help = "reboot the device",
        .hint = NULL,
        .func = &cmd_reboot,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

void app_main(void)
{
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_timer_init();

    config_init();
    serial_logger_init();
    led_init();
    initialize_wifi();

    console_init();

    sniffer_init();
    serial_logger_print_startup_info();
    serial_logger_start_metadata_heartbeat();

    register_sniffer_cmd();
    config_register_commands();
    register_reboot();

    sniffer_autostart();

    ESP_LOGI(TAG, "CITS-to-go firmware initialized: USB serial, XIAO USER LED, ITS-G5 sniffer only");
    ESP_ERROR_CHECK(esp_console_start_repl(repl));

    led_update();
}
