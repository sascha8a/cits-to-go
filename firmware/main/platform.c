/* ESP-IDF ABI boundary. Rust owns framing, input parsing and packet lifetime.
 * This file owns driver/RTOS objects and translates C bitfield metadata. */
#include "sdkconfig.h"
#include "platform.h"
#include "cits_ble.h"
#include "tx_custom.h"
#include <string.h>
#include <stdatomic.h>
#include "driver/gpio.h"
#include "driver/usb_serial_jtag.h"
#include "esp_check.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "esp_idf_version.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "hal/modem_syscon_ll.h"
#include "nvs_flash.h"

#if !CONFIG_IDF_TARGET_ESP32C5
#error "This radio adapter requires ESP32-C5"
#endif
#if ESP_IDF_VERSION_MAJOR != 6 || ESP_IDF_VERSION_MINOR != 1
#error "Use the repository's pinned ESP-IDF 6.1-dev image; private radio ABI is version-specific"
#endif

#ifndef CONFIG_CITS_LED_ACTIVE_LOW
#define CONFIG_CITS_LED_ACTIVE_LOW 0
#endif

#define TAG "cits-rs"
#define DECODED_MAX (32 + CONFIG_CITS_MAX_PACKET_BYTES + 4)
#define ENCODED_MAX (DECODED_MAX + DECODED_MAX / 254 + 2)
#define USB_SLOTS 4
#define CONTROL_RESERVE 2
#define CITS_EMIT_USB_DROPPED 0x01u
#define CITS_EMIT_BLE_DROPPED 0x02u
static TaskHandle_t executor_task, usb_reader_task, usb_writer_task;
static QueueHandle_t usb_free, usb_capture, usb_control, radio_queue;
static esp_timer_handle_t led_timer, stats_timer;
static _Atomic bool led_active;
static bool ble_discard;
static _Atomic uint32_t usb_partial_drops;
static _Atomic uint32_t usb_bytes_total;
static _Atomic uint32_t usb_capture_packets_total;
typedef struct { size_t len; bool control; uint8_t bytes[ENCODED_MAX]; } usb_slot_t;
static usb_slot_t usb_slots[USB_SLOTS];
typedef struct { const uint8_t *bytes; size_t len; bool seq; } radio_job_t;

void phy_change_channel(int channel, int arg1, int arg2, int ht_mode);
void phy_11p_set(int enable, int arg);

/* C5 is single-core. Save/restore the interrupt level, including nested calls.
 * These are the same primitives used by the IDF RISC-V critical sections. */
uint32_t cits_platform_enter(void) { return portSET_INTERRUPT_MASK_FROM_ISR(); }
void cits_platform_exit(uint32_t state) { portCLEAR_INTERRUPT_MASK_FROM_ISR(state); }
void cits_platform_wake(void) { if (executor_task) xTaskNotifyGive(executor_task); }
void cits_platform_wait(void) { (void)ulTaskNotifyTake(pdTRUE, portMAX_DELAY); }
void cits_platform_input_consumed(void) { xTaskNotifyGive(usb_reader_task); }

static void led_off(void *arg) {
    (void)arg;
    gpio_set_level(CONFIG_CITS_LED_GPIO, CONFIG_CITS_LED_ACTIVE_LOW ? 1 : 0);
    atomic_store_explicit(&led_active, false, memory_order_release);
}
void cits_platform_led(void) {
    /* At high packet rates, restarting an esp_timer for every frame adds
     * avoidable timer-queue traffic. One pulse already indicates activity;
     * ignore additional packets until that pulse expires. */
    if (atomic_exchange_explicit(&led_active, true, memory_order_acq_rel)) return;
    gpio_set_level(CONFIG_CITS_LED_GPIO, CONFIG_CITS_LED_ACTIVE_LOW ? 0 : 1);
    int64_t timeout = CONFIG_CITS_LED_PULSE_MS * 1000LL;
    if (esp_timer_start_once(led_timer, timeout) != ESP_OK) {
        atomic_store_explicit(&led_active, false, memory_order_release);
    }
}

static void statistics_tick(void *arg) {
    (void)arg;
    cits_rs_statistics_tick();
}

static void usb_read_task(void *arg) {
    (void)arg;
    uint8_t bytes[256];
    for (;;) {
        /* The IDF driver's USB interrupt wakes this blocked task. No polling. */
        int n = usb_serial_jtag_read_bytes(bytes, sizeof(bytes), portMAX_DELAY);
        if (n <= 0) continue;
        while (!cits_rs_input(bytes, (size_t)n, true))
            (void)ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    }
}
static bool write_all(const uint8_t *bytes, size_t len) {
    /* One deadline for the whole record, not one timeout per partial write. */
    const int64_t end = esp_timer_get_time() + CONFIG_CITS_SERIAL_WRITE_TIMEOUT_MS * 1000LL;
    while (len) {
        int64_t remaining = end - esp_timer_get_time();
        if (remaining <= 0 || !usb_serial_jtag_is_connected()) return false;
        TickType_t ticks = pdMS_TO_TICKS((remaining + 999) / 1000);
        if (!ticks) ticks = 1;
        int n = usb_serial_jtag_write_bytes(bytes, len, ticks);
        if (n <= 0) return false;
        bytes += n; len -= (size_t)n;
    }
    return true;
}
static void usb_write_task(void *arg) {
    (void)arg;
    bool needs_delimiter = true;
    for (;;) {
        uint8_t slot;
        if (xQueueReceive(usb_control, &slot, 0) != pdTRUE &&
            xQueueReceive(usb_capture, &slot, 0) != pdTRUE) {
            (void)ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
            continue;
        }
        if (usb_serial_jtag_is_connected()) {
            const uint8_t zero = 0;
            bool ready = !needs_delimiter || write_all(&zero, 1);
            bool ok = ready && write_all(usb_slots[slot].bytes, usb_slots[slot].len);
            needs_delimiter = !ok;
            if (ok) {
                atomic_fetch_add(&usb_bytes_total, (uint32_t)usb_slots[slot].len);
                if (!usb_slots[slot].control) atomic_fetch_add(&usb_capture_packets_total, 1);
            } else atomic_fetch_add(&usb_partial_drops, 1);
        } else needs_delimiter = true;
        (void)xQueueSend(usb_free, &slot, 0);
    }
}

uint32_t cits_platform_emit(const uint8_t *data, size_t len, bool control, bool usb_only) {
    uint32_t dropped = 0;
    if (len > ENCODED_MAX) return CITS_EMIT_USB_DROPPED | (usb_only ? 0u : CITS_EMIT_BLE_DROPPED);
    if (usb_serial_jtag_is_connected()) {
        uint8_t slot;
        /* Capture cannot consume the last two control-response buffers. Only
         * the single Embassy executor produces, so this capacity check is safe. */
        if ((!control && uxQueueMessagesWaiting(usb_free) <= CONTROL_RESERVE) ||
            xQueueReceive(usb_free, &slot, 0) != pdTRUE) dropped |= CITS_EMIT_USB_DROPPED;
        else {
            usb_slots[slot].len = len;
            usb_slots[slot].control = control;
            memcpy(usb_slots[slot].bytes, data, len);
            if (xQueueSend(control ? usb_control : usb_capture, &slot, 0) != pdTRUE) {
                (void)xQueueSend(usb_free, &slot, 0);
                dropped |= CITS_EMIT_USB_DROPPED;
            } else {
                xTaskNotifyGive(usb_writer_task);
            }
        }
    }
    if (!usb_only && !cits_ble_write(data, len, control)) dropped |= CITS_EMIT_BLE_DROPPED;
    return dropped;
}

void cits_platform_stats(cits_platform_stats_t *out) {
    if (out == NULL) return;
    memset(out, 0, sizeof(*out));
    out->uptime_ms = (uint32_t)(esp_timer_get_time() / 1000);
    out->usb_bytes_total = atomic_load(&usb_bytes_total);
    out->usb_partial_drops_total = atomic_load(&usb_partial_drops);
    out->usb_capture_packets_total = atomic_load(&usb_capture_packets_total);
    out->usb_queue_capacity = USB_SLOTS;
    if (usb_free != NULL) {
        const UBaseType_t free_slots = uxQueueMessagesWaiting(usb_free);
        out->usb_queue_depth = free_slots <= USB_SLOTS ? USB_SLOTS - (uint32_t)free_slots : 0;
    }
    if (usb_serial_jtag_is_connected()) out->flags |= 1u << 0;

    cits_ble_stats_t ble = {0};
    cits_ble_get_stats(&ble);
    out->ble_bytes_total = ble.bytes_total;
    out->ble_notifications_total = ble.notifications_total;
    out->ble_notify_failures_total = ble.notify_failures_total;
    out->ble_capture_packets_total = ble.capture_packets_total;
    out->ble_queue_depth = ble.queue_depth;
    out->ble_queue_capacity = ble.queue_capacity;
    out->ble_mtu = ble.mtu;
    out->ble_conn_interval = ble.conn_interval;
    out->ble_conn_latency = ble.conn_latency;
    out->ble_supervision_timeout = ble.supervision_timeout;
    out->ble_tx_phy = ble.tx_phy;
    out->ble_rx_phy = ble.rx_phy;
    if (ble.flags & CITS_BLE_STATS_CONNECTED) out->flags |= 1u << 1;
    if (ble.flags & CITS_BLE_STATS_NOTIFY_ENABLED) out->flags |= 1u << 2;
    if (ble.flags & CITS_BLE_STATS_SECURED) out->flags |= 1u << 3;
}

static void radio_worker(void *arg) {
    (void)arg;
    const cits_wifi_tx_rate_config_t rate = {
        .rate = WIFI_PHY_RATE_12M, .phymode = WIFI_PHY_MODE_11A,
        .ersu = false, .dcm = false,
    };
    radio_job_t job;
    for (;;) {
        if (xQueueReceive(radio_queue, &job, portMAX_DELAY) == pdTRUE) {
            esp_err_t status = cits_wifi_80211_tx(WIFI_IF_STA, job.bytes,
                (int)job.len, job.seq, &rate, WIFI_BAND_5G, WIFI_BW20);
            cits_rs_tx_done(status);
        }
    }
}
void cits_platform_tx(const uint8_t *bytes, size_t len, bool system_sequence) {
    const radio_job_t job = { bytes, len, system_sequence };
    if (xQueueSend(radio_queue, &job, 0) != pdTRUE) cits_rs_tx_done(ESP_ERR_NO_MEM);
}

/* NimBLE runs this on its host task. Backpressure on a BLE write must never
 * block that host. Drop through the next delimiter after a rejected chunk. */
static bool ble_rx(const uint8_t *bytes, size_t len) {
    if (ble_discard) {
        const uint8_t *end = memchr(bytes, 0, len);
        if (!end) return false;
        size_t used = (size_t)(end - bytes) + 1;
        bytes += used; len -= used; ble_discard = false;
    }
    if (!len || cits_rs_input(bytes, len, false)) return true;
    ble_discard = true;
    cits_rs_ble_gap();
    return false;
}
void cits_platform_ble_disconnected(void) {
    ble_discard = false;
    cits_rs_ble_reset();
}
void cits_platform_enroll(void) { cits_ble_begin_enrollment(); }

static void sniffer(void *buf, wifi_promiscuous_pkt_type_t type) {
    const wifi_promiscuous_pkt_t *packet = buf;
    if (!packet || type == WIFI_PKT_MISC || packet->rx_ctrl.rx_state != 0) return;
#if CONFIG_SOC_WIFI_HE_SUPPORT
    size_t len = packet->rx_ctrl.dump_len;
#else
    if (packet->rx_ctrl.sig_len < 4) return;
    size_t len = packet->rx_ctrl.sig_len - 4;
#endif
    cits_rs_capture(packet->payload, len, packet->rx_ctrl.timestamp,
                    packet->rx_ctrl.rssi, (uint8_t)type, packet->rx_ctrl.rx_state);
}
static esp_err_t wifi_start(void) {
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    wifi_promiscuous_filter_t filter = { .filter_mask = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL };
    modem_syscon_ll_enable_fe_40m_clock(&MODEM_SYSCON, 1);
    ESP_RETURN_ON_ERROR(esp_wifi_init(&cfg), TAG, "wifi init");
    ESP_RETURN_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), TAG, "storage");
    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_STA), TAG, "mode");
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "start");
    ESP_RETURN_ON_ERROR(esp_wifi_set_promiscuous_filter(&filter), TAG, "filter");
    ESP_RETURN_ON_ERROR(esp_wifi_set_promiscuous_rx_cb(sniffer), TAG, "callback");
    ESP_RETURN_ON_ERROR(esp_wifi_set_promiscuous(true), TAG, "promiscuous");
    phy_11p_set(1, 0);
    ESP_RETURN_ON_ERROR(esp_wifi_set_channel(140, WIFI_SECOND_CHAN_NONE), TAG, "channel");
    phy_change_channel(CONFIG_CITS_RX_FREQUENCY_MHZ, 1, 0, 0);
    return ESP_OK;
}
int32_t cits_platform_start(void) {
    usb_serial_jtag_driver_config_t usb = { .rx_buffer_size = 1024, .tx_buffer_size = ENCODED_MAX };
    ESP_RETURN_ON_ERROR(usb_serial_jtag_driver_install(&usb), TAG, "USB");
    const gpio_config_t led = { .pin_bit_mask = 1ULL << CONFIG_CITS_LED_GPIO, .mode = GPIO_MODE_OUTPUT };
    ESP_RETURN_ON_ERROR(gpio_config(&led), TAG, "LED");
    led_off(NULL);
    const esp_timer_create_args_t timer = { .callback = led_off, .name = "cits-led" };
    ESP_RETURN_ON_ERROR(esp_timer_create(&timer, &led_timer), TAG, "timer");
    const esp_timer_create_args_t stats = { .callback = statistics_tick, .name = "cits-stats" };
    ESP_RETURN_ON_ERROR(esp_timer_create(&stats, &stats_timer), TAG, "stats timer");
    usb_free = xQueueCreate(USB_SLOTS, sizeof(uint8_t));
    usb_capture = xQueueCreate(USB_SLOTS, sizeof(uint8_t));
    usb_control = xQueueCreate(USB_SLOTS, sizeof(uint8_t));
    radio_queue = xQueueCreate(1, sizeof(radio_job_t));
    if (!usb_free || !usb_capture || !usb_control || !radio_queue) return ESP_ERR_NO_MEM;
    for (uint8_t i = 0; i < USB_SLOTS; ++i) (void)xQueueSend(usb_free, &i, 0);
    if (xTaskCreate(usb_read_task, "usb-rx", 3072, NULL, 4, &usb_reader_task) != pdPASS ||
        xTaskCreate(usb_write_task, "usb-tx", 3072, NULL, 3, &usb_writer_task) != pdPASS ||
        xTaskCreate(radio_worker, "radio-tx", 4096, NULL, 4, NULL) != pdPASS) return ESP_ERR_NO_MEM;
    ESP_RETURN_ON_ERROR(cits_ble_init(ble_rx), TAG, "BLE");
    ESP_RETURN_ON_ERROR(esp_timer_start_periodic(stats_timer, 1000000), TAG, "stats start");
    return wifi_start();
}
void app_main(void) {
    executor_task = xTaskGetCurrentTaskHandle();
    esp_log_level_set("*", ESP_LOG_NONE);
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase()); err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    cits_rs_run();
}
