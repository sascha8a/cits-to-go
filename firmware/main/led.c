#include "sdkconfig.h"

#include <stdbool.h>
#include <stdint.h>

#include "driver/gpio.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"

#include "events.h"
#include "led.h"

static const char *TAG = "xiao_user_led";

static esp_timer_handle_t cits_led_timer_handle;

#ifdef CONFIG_CITS_USER_LED_ACTIVE_LOW
#define CITS_USER_LED_ON_LEVEL  0
#define CITS_USER_LED_OFF_LEVEL 1
#else
#define CITS_USER_LED_ON_LEVEL  1
#define CITS_USER_LED_OFF_LEVEL 0
#endif

static void xiao_user_led_set(bool on)
{
    gpio_set_level((gpio_num_t)CONFIG_CITS_USER_LED_GPIO,
                   on ? CITS_USER_LED_ON_LEVEL : CITS_USER_LED_OFF_LEVEL);
}

static void cits_led_timer_cb(void *arg)
{
    (void)arg;
    xiao_user_led_set(false);
}

static void pulse_xiao_user_led(void)
{
    const uint64_t pulse_us = (uint64_t)CONFIG_CITS_RX_LED_PULSE_MS * 1000ULL;

    xiao_user_led_set(true);

    esp_err_t err = esp_timer_restart(cits_led_timer_handle, pulse_us);
    if (err == ESP_ERR_INVALID_STATE) {
        err = esp_timer_start_once(cits_led_timer_handle, pulse_us);
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "failed to restart USER LED pulse timer: %s", esp_err_to_name(err));
    }
}

static void sniffer_event_handler(void *arg, esp_event_base_t event_base,
                                  int32_t event_id, void *event_data)
{
    (void)arg;
    (void)event_base;
    (void)event_data;

    switch (event_id) {
    case SNIFFER_RECEIVED_PACKET:
        pulse_xiao_user_led();
        break;
    case SNIFFER_STARTED:
    case SNIFFER_STOPPED:
        xiao_user_led_set(false);
        break;
    default:
        break;
    }
}

void led_init(void)
{
    gpio_config_t io_conf = {
        .pin_bit_mask = 1ULL << CONFIG_CITS_USER_LED_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&io_conf));
    xiao_user_led_set(false);

    const esp_timer_create_args_t create_args = {
        .callback = cits_led_timer_cb,
        .arg = NULL,
        .name = "xiao_rx_led",
    };
    ESP_ERROR_CHECK(esp_timer_create(&create_args, &cits_led_timer_handle));

    ESP_ERROR_CHECK(esp_event_handler_register(SNIFFER_EVENT_BASE,
                                               ESP_EVENT_ANY_ID,
                                               sniffer_event_handler,
                                               NULL));

    ESP_LOGI(TAG,
             "using Seeed Studio XIAO ESP32-C5 USER LED on GPIO%d, active-%s",
             CONFIG_CITS_USER_LED_GPIO,
#ifdef CONFIG_CITS_USER_LED_ACTIVE_LOW
             "low"
#else
             "high"
#endif
             );
}

void led_update(void)
{
    xiao_user_led_set(false);
}
