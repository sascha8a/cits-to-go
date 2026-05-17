#include "sdkconfig.h"

#include <stdbool.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "argtable3/argtable3.h"
#include "esp_check.h"
#include "esp_console.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "cmd_sniffer.h"
#include "config.h"
#include "events.h"
#include "serial_logger.h"

#define SNIFFER_PAYLOAD_FCS_LEN           4U
#define SNIFFER_PROCESS_PACKET_TIMEOUT_MS 100U

static const char TAG[] = "SNIFFER";

static bool is_running;
static uint32_t channel_mhz;
static uint32_t filter_mask;
static bool only_capture_broadcast;
static TaskHandle_t task;
static QueueHandle_t work_queue;
static SemaphoreHandle_t sem_task_over;

void phy_change_channel(int, int, int, int);
void phy_11p_set(int, int);

static esp_err_t sniffer_stop(void);

static void queue_packet(const void *recv_packet, sniffer_packet_info_t *packet_info)
{
    void *packet_to_queue = malloc(packet_info->length);
    if (!packet_to_queue) {
        ESP_LOGW(TAG, "dropped packet: no memory for %" PRIu32 " bytes", packet_info->length);
        return;
    }

    memcpy(packet_to_queue, recv_packet, packet_info->length);
    packet_info->payload = packet_to_queue;

    if (xQueueSend(work_queue, packet_info, 0) != pdTRUE) {
        ESP_LOGW(TAG, "dropped packet: sniffer work queue full");
        free(packet_to_queue);
    }
}

static void wifi_sniffer_cb(void *recv_buf, wifi_promiscuous_pkt_type_t type)
{
    if (type == WIFI_PKT_MISC || recv_buf == NULL || !work_queue) {
        return;
    }

    const wifi_promiscuous_pkt_t *packet = (const wifi_promiscuous_pkt_t *)recv_buf;
    if (packet->rx_ctrl.rx_state) {
        return;
    }

    sniffer_packet_info_t packet_info = {
        .seconds = packet->rx_ctrl.timestamp / 1000000U,
        .microseconds = packet->rx_ctrl.timestamp % 1000000U,
        .interface = SNIFFER_INTF_WLAN,
        .channel_mhz = channel_mhz,
        .rssi = packet->rx_ctrl.rssi,
    };

#if CONFIG_SOC_WIFI_HE_SUPPORT
    packet_info.length = packet->rx_ctrl.dump_len;
#else
    if (packet->rx_ctrl.sig_len <= SNIFFER_PAYLOAD_FCS_LEN) {
        return;
    }
    packet_info.length = packet->rx_ctrl.sig_len - SNIFFER_PAYLOAD_FCS_LEN;
#endif

    if (packet_info.length == 0) {
        return;
    }

    if (only_capture_broadcast &&
        (packet_info.length < 10 || memcmp(&packet->payload[4], "\xff\xff\xff\xff\xff\xff", 6) != 0)) {
        return;
    }

    queue_packet(packet->payload, &packet_info);
}

static void sniffer_task(void *arg)
{
    (void)arg;
    sniffer_packet_info_t packet_info;

    while (is_running) {
        if (xQueueReceive(work_queue, &packet_info, pdMS_TO_TICKS(SNIFFER_PROCESS_PACKET_TIMEOUT_MS)) != pdTRUE) {
            continue;
        }

        const bool logged_to_serial = serial_logger_handle_packet(&packet_info);
        if (logged_to_serial) {
            esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_RECEIVED_PACKET, NULL, 0, 0);
        }

        free(packet_info.payload);
    }

    xSemaphoreGive(sem_task_over);
    vTaskDelete(NULL);
}

static esp_err_t sniffer_stop(void)
{
    esp_err_t ret = ESP_OK;

    ESP_GOTO_ON_FALSE(is_running, ESP_ERR_INVALID_STATE, err, TAG, "sniffer is already stopped");

    ESP_GOTO_ON_ERROR(esp_wifi_set_promiscuous(false), err, TAG, "stop Wi-Fi promiscuous failed");
    ESP_LOGI(TAG, "stopped Wi-Fi promiscuous capture");

    is_running = false;
    xSemaphoreTake(sem_task_over, portMAX_DELAY);
    task = NULL;

    sniffer_packet_info_t packet_info;
    while (xQueueReceive(work_queue, &packet_info, 0) == pdTRUE) {
        free(packet_info.payload);
    }

    esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_STOPPED, NULL, 0, 0);

err:
    return ret;
}

static esp_err_t sniffer_start(void)
{
    esp_err_t ret = ESP_OK;
    wifi_promiscuous_filter_t wifi_filter = {
        .filter_mask = filter_mask,
    };

    ESP_GOTO_ON_FALSE(!is_running, ESP_ERR_INVALID_STATE, err, TAG, "sniffer is already running");
    ESP_GOTO_ON_FALSE(channel_mhz >= 5800 && channel_mhz <= 5900,
                      ESP_ERR_INVALID_ARG, err, TAG,
                      "frequency must be in the ITS-G5 range 5800..5900 MHz");

    is_running = true;
    ESP_GOTO_ON_FALSE(xTaskCreate(sniffer_task, "snifferT", CONFIG_SNIFFER_TASK_STACK_SIZE,
                                  NULL, CONFIG_SNIFFER_TASK_PRIORITY, &task),
                      ESP_FAIL, err_task, TAG, "create sniffer task failed");

    esp_wifi_set_promiscuous_filter(&wifi_filter);
    esp_wifi_set_promiscuous_rx_cb(wifi_sniffer_cb);
    ESP_GOTO_ON_ERROR(esp_wifi_set_promiscuous(true), err_start, TAG, "enable promiscuous failed");

    // Enable 802.11p mode and tune via the private PHY hooks used by the
    // original OpenTrafficMap ESP32-C5 receiver firmware.
    phy_11p_set(1, 0);
    ESP_GOTO_ON_ERROR(esp_wifi_set_channel(140, WIFI_SECOND_CHAN_NONE), err_start, TAG, "set helper channel failed");
    phy_change_channel((int)channel_mhz, 1, 0, 0);

    ESP_LOGI(TAG, "started ITS-G5 Wi-Fi capture at %" PRIu32 " MHz", channel_mhz);
    esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_STARTED, NULL, 0, 0);
    return ESP_OK;

err_start:
    is_running = false;
    xSemaphoreTake(sem_task_over, portMAX_DELAY);
    task = NULL;
err_task:
    is_running = false;
err:
    return ret;
}

static struct {
    struct arg_str *interface;
    struct arg_lit *fcsfail;
    struct arg_int *channel;
    struct arg_lit *stop;
    struct arg_end *end;
} sniffer_args;

static int do_sniffer_cmd(int argc, char **argv)
{
    const int nerrors = arg_parse(argc, argv, (void **)&sniffer_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, sniffer_args.end, argv[0]);
        return 1;
    }

    if (sniffer_args.stop->count) {
        esp_err_t err = sniffer_stop();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "stop failed: %s", esp_err_to_name(err));
        }
        return 0;
    }

    if (sniffer_args.interface->count && strncmp(sniffer_args.interface->sval[0], "wlan", 4) != 0) {
        ESP_LOGE(TAG, "unsupported interface '%s'; CITS-to-go firmware supports wlan only",
                 sniffer_args.interface->sval[0]);
        return 1;
    }

    if (sniffer_args.channel->count) {
        channel_mhz = (uint32_t)sniffer_args.channel->ival[0];
    } else {
        channel_mhz = CONFIG_CITS_DEFAULT_FREQUENCY_MHZ;
    }

    filter_mask = WIFI_PROMIS_FILTER_MASK_ALL;
    if (!sniffer_args.fcsfail->count) {
        filter_mask &= ~WIFI_PROMIS_FILTER_MASK_FCSFAIL;
    }

    uint8_t broadcast_only = 1;
    esp_err_t err = config_get_u8(CONFIG_INDEX_BROADCAST_ONLY, &broadcast_only);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "config_get_u8(broadcastonly) failed: %s", esp_err_to_name(err));
        return 1;
    }
    only_capture_broadcast = broadcast_only != 0;

    err = sniffer_start();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "start failed: %s", esp_err_to_name(err));
        return 1;
    }
    return 0;
}

void register_sniffer_cmd(void)
{
    sniffer_args.interface = arg_str0("i", "interface", "wlan", "capture interface; only wlan is supported");
    sniffer_args.fcsfail = arg_lit0("F", "fcsfail", "include corrupted packets with wrong FCS");
    sniffer_args.channel = arg_int0("c", "channel", "<freq-mhz>",
                                    "ITS-G5 frequency in MHz, e.g. 5900, 5890, 5880, 5870, 5860");
    sniffer_args.stop = arg_lit0(NULL, "stop", "stop running sniffer");
    sniffer_args.end = arg_end(1);

    const esp_console_cmd_t sniffer_cmd = {
        .command = "sniffer",
        .help = "start/stop ITS-G5 Wi-Fi capture",
        .hint = NULL,
        .func = &do_sniffer_cmd,
        .argtable = &sniffer_args,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&sniffer_cmd));
}

void sniffer_init(void)
{
    sem_task_over = xSemaphoreCreateBinary();
    ESP_ERROR_CHECK(sem_task_over ? ESP_OK : ESP_ERR_NO_MEM);

    work_queue = xQueueCreate(CONFIG_SNIFFER_WORK_QUEUE_LEN, sizeof(sniffer_packet_info_t));
    ESP_ERROR_CHECK(work_queue ? ESP_OK : ESP_ERR_NO_MEM);
}

void sniffer_autostart(void)
{
    uint32_t conf_channel = CONFIG_CITS_DEFAULT_FREQUENCY_MHZ;
    esp_err_t err = config_get_u32(CONFIG_INDEX_AUTOSTART_CHAN, &conf_channel);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "using default frequency; config_get_u32 failed: %s", esp_err_to_name(err));
    }

    uint8_t broadcast_only = 1;
    err = config_get_u8(CONFIG_INDEX_BROADCAST_ONLY, &broadcast_only);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "using broadcast-only mode; config_get_u8 failed: %s", esp_err_to_name(err));
    }

    channel_mhz = conf_channel;
    filter_mask = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL;
    only_capture_broadcast = broadcast_only != 0;

    err = sniffer_start();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "autostart failed: %s", esp_err_to_name(err));
    }
}
