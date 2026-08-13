#include "sdkconfig.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/usb_serial_jtag.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "hal/modem_syscon_ll.h"
#include "nvs_flash.h"
#include "cits_ble.h"
#include "tx_custom.h"

#define TAG "CITS"

#define CITS_FRAME_MAGIC_0 'C'
#define CITS_FRAME_MAGIC_1 'T'
#define CITS_FRAME_MAGIC_2 'G'
#define CITS_FRAME_MAGIC_3 '1'
#define CITS_PROTOCOL_VERSION 1
#define CITS_FRAME_TYPE_PACKET 1
#define CITS_FRAME_TYPE_TX_REQUEST 2
#define CITS_FRAME_TYPE_TX_RESULT 3
#define CITS_FRAME_TYPE_BLE_ENROLL_REQUEST 4
#define CITS_FRAME_TYPE_BLE_ENROLL_RESULT 5
#define CITS_PACKET_HEADER_LEN 32
#define CITS_TX_REQUEST_HEADER_LEN 16
#define CITS_TX_RESULT_HEADER_LEN 20
#define CITS_BLE_ENROLL_REQUEST_HEADER_LEN 12
#define CITS_BLE_ENROLL_RESULT_HEADER_LEN 16
#define CITS_FRAME_TRAILER_LEN 4
#define CITS_PAYLOAD_FCS_LEN 4
#define CITS_COBS_OVERHEAD(len) (((len) / 254u) + 1u)
#define CITS_DECODED_MAX_LEN (CITS_PACKET_HEADER_LEN + CONFIG_CITS_MAX_PACKET_BYTES + CITS_FRAME_TRAILER_LEN)
#define CITS_ENCODED_MAX_LEN (CITS_DECODED_MAX_LEN + CITS_COBS_OVERHEAD(CITS_DECODED_MAX_LEN) + 1u)

#define CITS_FLAG_BROADCAST 0x0001u
#define CITS_FLAG_TRUNCATED 0x0002u
#define CITS_TX_FLAG_EN_SYS_SEQ 0x0001u

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

typedef struct {
    uint32_t request_id;
    uint16_t len;
    uint16_t flags;
    uint8_t payload[CONFIG_CITS_MAX_PACKET_BYTES];
} tx_slot_t;

static QueueHandle_t free_queue;
static QueueHandle_t capture_queue;
static QueueHandle_t tx_free_queue;
static QueueHandle_t tx_queue;
static SemaphoreHandle_t serial_write_mutex;
static packet_slot_t packet_slots[CONFIG_CITS_PACKET_POOL_SIZE];
static tx_slot_t tx_slots[CONFIG_CITS_TX_POOL_SIZE];
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

static uint16_t get_u16(const uint8_t *buf)
{
    return (uint16_t)buf[0] | ((uint16_t)buf[1] << 8);
}

static uint32_t get_u32(const uint8_t *buf)
{
    return (uint32_t)get_u16(buf) | ((uint32_t)get_u16(buf + 2) << 16);
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

static bool cobs_decode_to(const uint8_t *input, size_t input_len, uint8_t *output, size_t *output_len)
{
    size_t read = 0;
    size_t write = 0;

    while (read < input_len) {
        const uint8_t code = input[read++];
        if (code == 0 || read + (size_t)code - 1 > input_len) {
            return false;
        }
        for (uint8_t i = 1; i < code; ++i) {
            if (write >= CITS_DECODED_MAX_LEN) return false;
            output[write++] = input[read++];
        }
        if (code < 0xff && read < input_len) {
            if (write >= CITS_DECODED_MAX_LEN) return false;
            output[write++] = 0;
        }
    }
    *output_len = write;
    return true;
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
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = 256,
        .tx_buffer_size = CITS_ENCODED_MAX_LEN,
    };
    return usb_serial_jtag_driver_install(&cfg);
}

static void serial_write_all_locked(const uint8_t *data, size_t len)
{
    if (!usb_serial_jtag_is_connected()) return;
    while (len > 0) {
        int written = usb_serial_jtag_write_bytes(data, len, pdMS_TO_TICKS(CONFIG_CITS_SERIAL_WRITE_TIMEOUT_MS));
        if (written <= 0) break;
        data += written;
        len -= (size_t)written;
    }
}

static void transport_write_all(const uint8_t *data, size_t len)
{
    xSemaphoreTake(serial_write_mutex, portMAX_DELAY);
    serial_write_all_locked(data, len);
    xSemaphoreGive(serial_write_mutex);
    cits_ble_write(data, len);
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
    transport_write_all(encoded, encoded_len + 1);
}

static void write_tx_result(uint32_t request_id, esp_err_t status, uint16_t packet_len, const uint8_t *payload)
{
    const size_t payload_len = (status == ESP_OK && payload != NULL) ? packet_len : 0;
    static uint8_t decoded[CITS_DECODED_MAX_LEN];
    static uint8_t encoded[CITS_ENCODED_MAX_LEN];

    xSemaphoreTake(serial_write_mutex, portMAX_DELAY);
    decoded[0] = CITS_FRAME_MAGIC_0;
    decoded[1] = CITS_FRAME_MAGIC_1;
    decoded[2] = CITS_FRAME_MAGIC_2;
    decoded[3] = CITS_FRAME_MAGIC_3;
    decoded[4] = CITS_PROTOCOL_VERSION;
    decoded[5] = CITS_FRAME_TYPE_TX_RESULT;
    put_u16(&decoded[6], CITS_TX_RESULT_HEADER_LEN);
    put_u32(&decoded[8], request_id);
    put_u32(&decoded[12], (uint32_t)status);
    put_u16(&decoded[16], packet_len);
    put_u16(&decoded[18], 0);
    if (payload_len > 0) {
        memcpy(&decoded[CITS_TX_RESULT_HEADER_LEN], payload, payload_len);
    }
    const size_t frame_without_crc_len = CITS_TX_RESULT_HEADER_LEN + payload_len;
    put_u32(&decoded[frame_without_crc_len], crc32_ieee(decoded, frame_without_crc_len));

    const size_t encoded_len = cobs_encode_to(decoded, frame_without_crc_len + CITS_FRAME_TRAILER_LEN, encoded);
    encoded[encoded_len] = 0;
    serial_write_all_locked(encoded, encoded_len + 1);
    xSemaphoreGive(serial_write_mutex);
    cits_ble_write(encoded, encoded_len + 1);
}

static void write_ble_enroll_result(esp_err_t status)
{
    uint8_t decoded[CITS_BLE_ENROLL_RESULT_HEADER_LEN + CITS_FRAME_TRAILER_LEN] = {0};
    uint8_t encoded[32];

    decoded[0] = CITS_FRAME_MAGIC_0;
    decoded[1] = CITS_FRAME_MAGIC_1;
    decoded[2] = CITS_FRAME_MAGIC_2;
    decoded[3] = CITS_FRAME_MAGIC_3;
    decoded[4] = CITS_PROTOCOL_VERSION;
    decoded[5] = CITS_FRAME_TYPE_BLE_ENROLL_RESULT;
    put_u16(&decoded[6], CITS_BLE_ENROLL_RESULT_HEADER_LEN);
    put_u32(&decoded[8], (uint32_t)status);
    decoded[12] = status == ESP_OK ? 1 : 0;
    put_u32(&decoded[CITS_BLE_ENROLL_RESULT_HEADER_LEN],
            crc32_ieee(decoded, CITS_BLE_ENROLL_RESULT_HEADER_LEN));

    const size_t encoded_len = cobs_encode_to(decoded, sizeof(decoded), encoded);
    encoded[encoded_len] = 0;
    xSemaphoreTake(serial_write_mutex, portMAX_DELAY);
    serial_write_all_locked(encoded, encoded_len + 1);
    xSemaphoreGive(serial_write_mutex);
}

static void handle_record(const uint8_t *encoded, size_t encoded_len, bool from_usb)
{
    static uint8_t decoded[CITS_DECODED_MAX_LEN];
    size_t decoded_len = 0;
    if (!cobs_decode_to(encoded, encoded_len, decoded, &decoded_len) ||
        decoded_len < 8 + CITS_FRAME_TRAILER_LEN ||
        decoded[0] != CITS_FRAME_MAGIC_0 || decoded[1] != CITS_FRAME_MAGIC_1 ||
        decoded[2] != CITS_FRAME_MAGIC_2 || decoded[3] != CITS_FRAME_MAGIC_3 ||
        decoded[4] != CITS_PROTOCOL_VERSION) {
        return;
    }

    const uint32_t expected_crc = get_u32(&decoded[decoded_len - CITS_FRAME_TRAILER_LEN]);
    if (crc32_ieee(decoded, decoded_len - CITS_FRAME_TRAILER_LEN) != expected_crc) {
        if (decoded[5] == CITS_FRAME_TYPE_TX_REQUEST &&
            decoded_len >= CITS_TX_REQUEST_HEADER_LEN + CITS_FRAME_TRAILER_LEN &&
            get_u16(&decoded[6]) == CITS_TX_REQUEST_HEADER_LEN) {
            write_tx_result(get_u32(&decoded[8]), ESP_ERR_INVALID_CRC, get_u16(&decoded[12]), NULL);
        }
        return;
    }

    if (decoded[5] == CITS_FRAME_TYPE_BLE_ENROLL_REQUEST) {
        /* Enrollment is intentionally USB-only. A BLE peer can never grant
         * itself permission to replace/create a bond. */
        if (!from_usb || get_u16(&decoded[6]) != CITS_BLE_ENROLL_REQUEST_HEADER_LEN ||
            decoded_len != CITS_BLE_ENROLL_REQUEST_HEADER_LEN + CITS_FRAME_TRAILER_LEN ||
            decoded[8] != 1) {
            return;
        }
        write_ble_enroll_result(cits_ble_begin_enrollment());
        return;
    }

    if (decoded[5] != CITS_FRAME_TYPE_TX_REQUEST ||
        get_u16(&decoded[6]) != CITS_TX_REQUEST_HEADER_LEN ||
        decoded_len < CITS_TX_REQUEST_HEADER_LEN + CITS_FRAME_TRAILER_LEN) {
        return;
    }

    const uint32_t request_id = get_u32(&decoded[8]);
    const uint16_t packet_len = get_u16(&decoded[12]);
    const uint16_t flags = get_u16(&decoded[14]);
    const size_t expected_len = CITS_TX_REQUEST_HEADER_LEN + packet_len + CITS_FRAME_TRAILER_LEN;
    if (packet_len == 0 || packet_len > CONFIG_CITS_MAX_PACKET_BYTES || decoded_len != expected_len) {
        write_tx_result(request_id, ESP_ERR_INVALID_SIZE, packet_len, NULL);
        return;
    }

    uint8_t slot_index;
    if (xQueueReceive(tx_free_queue, &slot_index, 0) != pdTRUE) {
        write_tx_result(request_id, ESP_ERR_NO_MEM, packet_len, NULL);
        return;
    }
    tx_slot_t *slot = &tx_slots[slot_index];
    slot->request_id = request_id;
    slot->len = packet_len;
    slot->flags = flags;
    memcpy(slot->payload, &decoded[CITS_TX_REQUEST_HEADER_LEN], packet_len);
    if (xQueueSend(tx_queue, &slot_index, 0) != pdTRUE) {
        (void)xQueueSend(tx_free_queue, &slot_index, 0);
        write_tx_result(request_id, ESP_ERR_NO_MEM, packet_len, NULL);
    }
}

static void ble_receive_chunk(const uint8_t *data, size_t len)
{
    static uint8_t encoded[CITS_ENCODED_MAX_LEN];
    static size_t encoded_len;

    for (size_t i = 0; i < len; ++i) {
        if (data[i] == 0) {
            if (encoded_len > 0) handle_record(encoded, encoded_len, false);
            encoded_len = 0;
        } else if (encoded_len < sizeof(encoded)) {
            encoded[encoded_len++] = data[i];
        } else {
            encoded_len = 0;
        }
    }
}

static void serial_reader_task(void *arg)
{
    (void)arg;
    static uint8_t encoded[CITS_ENCODED_MAX_LEN];
    static uint8_t input[256];
    size_t encoded_len = 0;

    for (;;) {
        const int count = usb_serial_jtag_read_bytes(
            input, sizeof(input), pdMS_TO_TICKS(CONFIG_CITS_SERIAL_READ_TIMEOUT_MS));
        for (int i = 0; i < count; ++i) {
            if (input[i] == 0) {
                if (encoded_len > 0) handle_record(encoded, encoded_len, true);
                encoded_len = 0;
            } else if (encoded_len < sizeof(encoded)) {
                encoded[encoded_len++] = input[i];
            } else {
                encoded_len = 0;
            }
        }
    }
}

static void radio_tx_task(void *arg)
{
    (void)arg;
    const cits_wifi_tx_rate_config_t rate = {
        .rate = WIFI_PHY_RATE_12M,
        .phymode = WIFI_PHY_MODE_11A,
        .ersu = false,
        .dcm = false,
    };
    uint8_t slot_index;

    for (;;) {
        if (xQueueReceive(tx_queue, &slot_index, portMAX_DELAY) == pdTRUE) {
            tx_slot_t *slot = &tx_slots[slot_index];
            const esp_err_t result = cits_wifi_80211_tx(
                WIFI_IF_STA,
                slot->payload,
                slot->len,
                (slot->flags & CITS_TX_FLAG_EN_SYS_SEQ) != 0,
                &rate,
                WIFI_BAND_5G,
                WIFI_BW20);
            write_tx_result(slot->request_id, result, slot->len, slot->payload);
            if (result == ESP_OK) led_pulse();
            (void)xQueueSend(tx_free_queue, &slot_index, portMAX_DELAY);
        }
    }
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
    tx_free_queue = xQueueCreate(CONFIG_CITS_TX_POOL_SIZE, sizeof(uint8_t));
    tx_queue = xQueueCreate(CONFIG_CITS_TX_POOL_SIZE, sizeof(uint8_t));
    serial_write_mutex = xSemaphoreCreateMutex();
    ESP_RETURN_ON_FALSE(
        free_queue && capture_queue && tx_free_queue && tx_queue && serial_write_mutex,
        ESP_ERR_NO_MEM, TAG, "queue/mutex create");

    for (uint8_t i = 0; i < CONFIG_CITS_PACKET_POOL_SIZE; ++i) {
        ESP_RETURN_ON_FALSE(xQueueSend(free_queue, &i, 0) == pdTRUE, ESP_FAIL, TAG, "free_queue init");
    }
    for (uint8_t i = 0; i < CONFIG_CITS_TX_POOL_SIZE; ++i) {
        ESP_RETURN_ON_FALSE(xQueueSend(tx_free_queue, &i, 0) == pdTRUE, ESP_FAIL, TAG, "tx_free_queue init");
    }

    return ESP_OK;
}

static esp_err_t init_wifi_11p_sniffer(void)
{
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    wifi_promiscuous_filter_t filter = {
        .filter_mask = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL,
    };

    modem_syscon_ll_enable_fe_40m_clock(&MODEM_SYSCON, 1);
    ESP_RETURN_ON_ERROR(esp_wifi_init(&cfg), TAG, "esp_wifi_init");
    ESP_RETURN_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), TAG, "esp_wifi_set_storage");
    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_STA), TAG, "esp_wifi_set_mode");
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "esp_wifi_start");
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
    ESP_ERROR_CHECK(cits_ble_init(ble_receive_chunk));
    ESP_ERROR_CHECK(xTaskCreate(packet_writer_task, "cits_serial", 4096, NULL, 3, NULL) == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(init_wifi_11p_sniffer());
    ESP_ERROR_CHECK(xTaskCreate(serial_reader_task, "cits_serial_rx", 4096, NULL, 4, NULL) == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(xTaskCreate(radio_tx_task, "cits_radio_tx", 4096, NULL, 4, NULL) == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
}
