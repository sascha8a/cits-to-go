#include "sdkconfig.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/uart.h"
#include "driver/usb_serial_jtag.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "nvs_flash.h"

#define TAG "CITS"

#define CITS_FRAME_MAGIC_0 'C'
#define CITS_FRAME_MAGIC_1 'T'
#define CITS_FRAME_MAGIC_2 'G'
#define CITS_FRAME_MAGIC_3 '1'
#define CITS_PROTOCOL_VERSION 1
#define CITS_FRAME_TYPE_PACKET 1
#define CITS_PACKET_HEADER_LEN 32
#define CITS_FRAME_TRAILER_LEN 4
#define CITS_PAYLOAD_FCS_LEN 4
#define CITS_COBS_OVERHEAD(len) (((len) / 254u) + 1u)
#define CITS_DECODED_MAX_LEN (CITS_PACKET_HEADER_LEN + CONFIG_CITS_MAX_PACKET_BYTES + CITS_FRAME_TRAILER_LEN)
#define CITS_ENCODED_MAX_LEN (CITS_DECODED_MAX_LEN + CITS_COBS_OVERHEAD(CITS_DECODED_MAX_LEN) + 1u)

#define CITS_FLAG_BROADCAST 0x0001u
#define CITS_FLAG_TRUNCATED 0x0002u

typedef struct {
    uint16_t len;
    uint16_t orig_len;
    uint64_t timestamp_us;
    int8_t rssi;
    uint8_t wifi_type;
    uint8_t rx_state;
    uint16_t flags;
    uint8_t payload[CONFIG_CITS_MAX_PACKET_BYTES];
} packet_slot_t;

static QueueHandle_t free_queue;
static QueueHandle_t capture_queue;
static packet_slot_t packet_slots[CONFIG_CITS_PACKET_POOL_SIZE];
static uint32_t sequence;
static volatile uint32_t dropped_no_buffer;
static volatile uint32_t dropped_too_large;
static esp_timer_handle_t led_timer;

void phy_change_channel(int channel, int arg1, int arg2, int ht_mode);
void phy_11p_set(int enable, int arg);

static void put_u16(uint8_t *buf, uint16_t value)
{
    buf[0] = (uint8_t)value;
    buf[1] = (uint8_t)(value >> 8);
}

static void put_u32(uint8_t *buf, uint32_t value)
{
    buf[0] = (uint8_t)value;
    buf[1] = (uint8_t)(value >> 8);
    buf[2] = (uint8_t)(value >> 16);
    buf[3] = (uint8_t)(value >> 24);
}

static void put_u64(uint8_t *buf, uint64_t value)
{
    put_u32(buf, (uint32_t)value);
    put_u32(buf + 4, (uint32_t)(value >> 32));
}

static uint32_t crc32_ieee(const uint8_t *data, size_t len)
{
    static const uint32_t table[16] = {
        0x00000000u, 0x1db71064u, 0x3b6e20c8u, 0x26d930acu,
        0x76dc4190u, 0x6b6b51f4u, 0x4db26158u, 0x5005713cu,
        0xedb88320u, 0xf00f9344u, 0xd6d6a3e8u, 0xcb61b38cu,
        0x9b64c2b0u, 0x86d3d2d4u, 0xa00ae278u, 0xbdbdf21cu,
    };
    uint32_t crc = 0xffffffffu;

    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        crc = (crc >> 4) ^ table[crc & 0x0fu];
        crc = (crc >> 4) ^ table[crc & 0x0fu];
    }

    return ~crc;
}

static size_t cobs_encode_to(const uint8_t *input, size_t input_len, uint8_t *output)
{
    uint8_t *start = output;
    uint8_t *code_ptr = output++;
    uint8_t code = 1;

    for (size_t i = 0; i < input_len; ++i) {
        if (input[i] == 0) {
            *code_ptr = code;
            code_ptr = output++;
            code = 1;
        } else {
            *output++ = input[i];
            ++code;
            if (code == 0xff) {
                *code_ptr = code;
                code_ptr = output++;
                code = 1;
            }
        }
    }

    *code_ptr = code;
    return (size_t)(output - start);
}

static void set_led(bool on)
{
    const int level = CONFIG_CITS_LED_ACTIVE_LOW ? !on : on;
    gpio_set_level(CONFIG_CITS_LED_GPIO, level);
}

static void led_timer_cb(void *arg)
{
    (void)arg;
    set_led(false);
}

static void led_pulse(void)
{
    set_led(true);
    const int64_t timeout_us = (int64_t)CONFIG_CITS_LED_PULSE_MS * 1000;
    if (esp_timer_restart(led_timer, timeout_us) == ESP_ERR_INVALID_STATE) {
        ESP_ERROR_CHECK(esp_timer_start_once(led_timer, timeout_us));
    }
}

static esp_err_t init_led(void)
{
    gpio_config_t io_conf = {
        .pin_bit_mask = 1ULL << CONFIG_CITS_LED_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&io_conf), TAG, "gpio_config");
    set_led(false);

    const esp_timer_create_args_t timer_args = {
        .callback = led_timer_cb,
        .name = "cits_led",
    };
    return esp_timer_create(&timer_args, &led_timer);
}

static esp_err_t init_serial(void)
{
#if CONFIG_CITS_SERIAL_OUTPUT_USB || CONFIG_CITS_SERIAL_OUTPUT_USB_AND_UART
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = 256,
        .tx_buffer_size = CITS_ENCODED_MAX_LEN,
    };
    ESP_RETURN_ON_ERROR(usb_serial_jtag_driver_install(&cfg), TAG, "usb_serial_jtag_driver_install");
#endif

#if CONFIG_CITS_SERIAL_OUTPUT_UART || CONFIG_CITS_SERIAL_OUTPUT_USB_AND_UART
    const uart_port_t uart_port = (uart_port_t)CONFIG_CITS_UART_PORT_NUM;
    const uart_config_t uart_cfg = {
        .baud_rate = CONFIG_CITS_UART_BAUD_RATE,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    const int rx_gpio = CONFIG_CITS_UART_RX_GPIO >= 0 ? CONFIG_CITS_UART_RX_GPIO : UART_PIN_NO_CHANGE;

    ESP_RETURN_ON_ERROR(uart_driver_install(uart_port, 256, CITS_ENCODED_MAX_LEN, 0, NULL, 0),
                        TAG, "uart_driver_install");
    ESP_RETURN_ON_ERROR(uart_param_config(uart_port, &uart_cfg), TAG, "uart_param_config");
    ESP_RETURN_ON_ERROR(uart_set_pin(uart_port, CONFIG_CITS_UART_TX_GPIO, rx_gpio,
                                     UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE),
                        TAG, "uart_set_pin");
#endif

    return ESP_OK;
}

static void serial_write_all(const uint8_t *data, size_t len)
{
#if CONFIG_CITS_SERIAL_OUTPUT_USB || CONFIG_CITS_SERIAL_OUTPUT_USB_AND_UART
    if (usb_serial_jtag_is_connected()) {
        const uint8_t *usb_data = data;
        size_t usb_len = len;
        const int64_t usb_deadline_us = esp_timer_get_time() +
            (int64_t)CONFIG_CITS_SERIAL_WRITE_TIMEOUT_MS * 1000;

        while (usb_len > 0 && esp_timer_get_time() < usb_deadline_us) {
            int written = usb_serial_jtag_write_bytes(usb_data, usb_len,
                                                      pdMS_TO_TICKS(1));
            if (written <= 0) {
                vTaskDelay(1);
                continue;
            }
            usb_data += written;
            usb_len -= (size_t)written;
        }
    }
#endif

#if CONFIG_CITS_SERIAL_OUTPUT_UART || CONFIG_CITS_SERIAL_OUTPUT_USB_AND_UART
    const uint8_t *uart_data = data;
    size_t uart_len = len;
    const int64_t uart_deadline_us = esp_timer_get_time() +
        (int64_t)CONFIG_CITS_SERIAL_WRITE_TIMEOUT_MS * 1000;

    while (uart_len > 0 && esp_timer_get_time() < uart_deadline_us) {
        int written = uart_tx_chars((uart_port_t)CONFIG_CITS_UART_PORT_NUM,
                                    (const char *)uart_data, uart_len);
        if (written <= 0) {
            vTaskDelay(1);
            continue;
        }
        uart_data += written;
        uart_len -= (size_t)written;
    }
#endif
}

static void write_packet_record(const packet_slot_t *slot)
{
    static uint8_t decoded[CITS_DECODED_MAX_LEN];
    static uint8_t encoded[CITS_ENCODED_MAX_LEN];

    decoded[0] = CITS_FRAME_MAGIC_0;
    decoded[1] = CITS_FRAME_MAGIC_1;
    decoded[2] = CITS_FRAME_MAGIC_2;
    decoded[3] = CITS_FRAME_MAGIC_3;
    decoded[4] = CITS_PROTOCOL_VERSION;
    decoded[5] = CITS_FRAME_TYPE_PACKET;
    put_u16(&decoded[6], CITS_PACKET_HEADER_LEN);
    put_u16(&decoded[8], slot->flags);
    put_u32(&decoded[10], sequence++);
    put_u64(&decoded[14], slot->timestamp_us);
    put_u16(&decoded[22], CONFIG_CITS_RX_FREQUENCY_MHZ);
    put_u16(&decoded[24], slot->orig_len);
    put_u16(&decoded[26], slot->len);
    decoded[28] = (uint8_t)slot->rssi;
    decoded[29] = slot->wifi_type;
    decoded[30] = slot->rx_state;
    decoded[31] = 0;
    memcpy(&decoded[CITS_PACKET_HEADER_LEN], slot->payload, slot->len);

    const size_t frame_without_crc_len = CITS_PACKET_HEADER_LEN + slot->len;
    const uint32_t crc = crc32_ieee(decoded, frame_without_crc_len);
    put_u32(&decoded[frame_without_crc_len], crc);

    const size_t encoded_len = cobs_encode_to(decoded, frame_without_crc_len + CITS_FRAME_TRAILER_LEN, encoded);
    encoded[encoded_len] = 0;
    serial_write_all(encoded, encoded_len + 1);
}

static bool is_broadcast_80211(const uint8_t *payload, uint16_t len)
{
    static const uint8_t broadcast[6] = { 0xff, 0xff, 0xff, 0xff, 0xff, 0xff };

    if (len < 10) {
        return false;
    }

    return memcmp(&payload[4], broadcast, sizeof(broadcast)) == 0;
}

static void wifi_sniffer_cb(void *recv_buf, wifi_promiscuous_pkt_type_t type)
{
    wifi_promiscuous_pkt_t *packet = (wifi_promiscuous_pkt_t *)recv_buf;

#if CONFIG_SOC_WIFI_HE_SUPPORT
    uint16_t orig_len = packet->rx_ctrl.dump_len;
#else
    if (packet->rx_ctrl.sig_len < CITS_PAYLOAD_FCS_LEN) {
        return;
    }
    uint16_t orig_len = packet->rx_ctrl.sig_len - CITS_PAYLOAD_FCS_LEN;
#endif

    if (type == WIFI_PKT_MISC || packet->rx_ctrl.rx_state != 0) {
        return;
    }

#if CONFIG_CITS_BROADCAST_ONLY
    if (!is_broadcast_80211(packet->payload, orig_len)) {
        return;
    }
#endif

    if (orig_len > CONFIG_CITS_MAX_PACKET_BYTES) {
        ++dropped_too_large;
        return;
    }

    uint8_t slot_index;
    if (xQueueReceive(free_queue, &slot_index, 0) != pdTRUE) {
        ++dropped_no_buffer;
        return;
    }

    packet_slot_t *slot = &packet_slots[slot_index];
    slot->len = orig_len;
    slot->orig_len = orig_len;
    slot->timestamp_us = packet->rx_ctrl.timestamp;
    slot->rssi = packet->rx_ctrl.rssi;
    slot->wifi_type = (uint8_t)type;
    slot->rx_state = packet->rx_ctrl.rx_state;
    slot->flags = is_broadcast_80211(packet->payload, orig_len) ? CITS_FLAG_BROADCAST : 0;
    memcpy(slot->payload, packet->payload, orig_len);

    if (xQueueSend(capture_queue, &slot_index, 0) != pdTRUE) {
        ++dropped_no_buffer;
        (void)xQueueSend(free_queue, &slot_index, 0);
    }
}

static void packet_writer_task(void *arg)
{
    (void)arg;
    uint8_t slot_index;

    for (;;) {
        if (xQueueReceive(capture_queue, &slot_index, portMAX_DELAY) == pdTRUE) {
            write_packet_record(&packet_slots[slot_index]);
            led_pulse();
            (void)xQueueSend(free_queue, &slot_index, portMAX_DELAY);
        }
    }
}

static esp_err_t init_queues(void)
{
    free_queue = xQueueCreate(CONFIG_CITS_PACKET_POOL_SIZE, sizeof(uint8_t));
    capture_queue = xQueueCreate(CONFIG_CITS_PACKET_POOL_SIZE, sizeof(uint8_t));
    ESP_RETURN_ON_FALSE(free_queue && capture_queue, ESP_ERR_NO_MEM, TAG, "xQueueCreate");

    for (uint8_t i = 0; i < CONFIG_CITS_PACKET_POOL_SIZE; ++i) {
        ESP_RETURN_ON_FALSE(xQueueSend(free_queue, &i, 0) == pdTRUE, ESP_FAIL, TAG, "free_queue init");
    }

    return ESP_OK;
}

static esp_err_t init_wifi_11p_sniffer(void)
{
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    wifi_promiscuous_filter_t filter = {
        .filter_mask = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL,
    };

    ESP_RETURN_ON_ERROR(esp_wifi_init(&cfg), TAG, "esp_wifi_init");
    ESP_RETURN_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), TAG, "esp_wifi_set_storage");
    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_NULL), TAG, "esp_wifi_set_mode");
    ESP_RETURN_ON_ERROR(esp_wifi_set_promiscuous_filter(&filter), TAG, "esp_wifi_set_promiscuous_filter");
    ESP_RETURN_ON_ERROR(esp_wifi_set_promiscuous_rx_cb(wifi_sniffer_cb), TAG, "esp_wifi_set_promiscuous_rx_cb");
    ESP_RETURN_ON_ERROR(esp_wifi_set_promiscuous(true), TAG, "esp_wifi_set_promiscuous");

    phy_11p_set(1, 0);
    ESP_RETURN_ON_ERROR(esp_wifi_set_channel(140, WIFI_SECOND_CHAN_NONE), TAG, "esp_wifi_set_channel");
    phy_change_channel(CONFIG_CITS_RX_FREQUENCY_MHZ, 1, 0, 0);

    return ESP_OK;
}

void app_main(void)
{
    esp_log_level_set("*", ESP_LOG_NONE);

    esp_err_t timer_err = esp_timer_init();
    ESP_ERROR_CHECK(timer_err == ESP_ERR_INVALID_STATE ? ESP_OK : timer_err);
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(init_serial());
    ESP_ERROR_CHECK(init_led());
    ESP_ERROR_CHECK(init_queues());
    ESP_ERROR_CHECK(xTaskCreate(packet_writer_task, "cits_serial", 4096, NULL, 3, NULL) == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(init_wifi_11p_sniffer());
}
