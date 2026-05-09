#include "sdkconfig.h"

#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <sys/time.h>
#include <sys/unistd.h>
#include <sys/fcntl.h>

#include "argtable3/argtable3.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "esp_app_trace.h"
#include "esp_check.h"
#include "esp_console.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "nvs.h"

#include "cmd_pcap.h"
#include "config.h"
#include "events.h"
#include "mqtt.h"
#include "serial_logger.h"

#include "cmd_sniffer.h"

#define SNIFFER_DEFAULT_CHANNEL             (1)
#define SNIFFER_PAYLOAD_FCS_LEN             (4)
#define SNIFFER_PROCESS_PACKET_TIMEOUT_MS   (100)
#define SNIFFER_RX_FCS_ERR                  (0X41)
#define SNIFFER_MAX_ETH_INTFS               (3)
#define SNIFFER_DECIMAL_NUM                 (10)


static const char TAG[] = "SNIFFER";

typedef struct {
    char *filter_name;
    uint32_t filter_val;
} wlan_filter_table_t;

static bool is_running;
static bool write_pcap;
static sniffer_intf_t interf;
static uint32_t interf_num;
static uint32_t channel;
static uint32_t filter;
static bool only_capture_broadcast;
static TaskHandle_t task;
static QueueHandle_t work_queue;
static SemaphoreHandle_t sem_task_over;
static esp_eth_handle_t eth_handles[SNIFFER_MAX_ETH_INTFS];

static esp_err_t sniffer_stop();

static void queue_packet(void *recv_packet, sniffer_packet_info_t *packet_info)
{
    /* Copy a packet from Link Layer driver and queue the copy to be processed by sniffer task */
    void *packet_to_queue = malloc(packet_info->length);
    if (packet_to_queue) {
        memcpy(packet_to_queue, recv_packet, packet_info->length);
        packet_info->payload = packet_to_queue;
        if (work_queue) {
            /* send packet_info */
            if (xQueueSend(work_queue, packet_info, pdMS_TO_TICKS(SNIFFER_PROCESS_PACKET_TIMEOUT_MS)) != pdTRUE) {
                ESP_LOGE(TAG, "sniffer work queue full");
                free(packet_info->payload);
            }
        }
    } else {
        ESP_LOGE(TAG, "No enough memory for promiscuous packet");
    }
}

static void wifi_sniffer_cb(void *recv_buf, wifi_promiscuous_pkt_type_t type)
{
    sniffer_packet_info_t packet_info;
    wifi_promiscuous_pkt_t *packet = (wifi_promiscuous_pkt_t *)recv_buf;
    /* prepare packet_info */
    packet_info.seconds = packet->rx_ctrl.timestamp / 1000000U;
    packet_info.microseconds = packet->rx_ctrl.timestamp % 1000000U;
    packet_info.interface = SNIFFER_INTF_WLAN;
    packet_info.channel_mhz = channel;
    packet_info.rssi = packet->rx_ctrl.rssi;

#if CONFIG_SOC_WIFI_HE_SUPPORT
    packet_info.length = packet->rx_ctrl.dump_len;
#else
    if (packet->rx_ctrl.sig_len <= SNIFFER_PAYLOAD_FCS_LEN) {
        return;
    }
    packet_info.length = packet->rx_ctrl.sig_len - SNIFFER_PAYLOAD_FCS_LEN;
#endif

    /* For now, the sniffer only dumps the length of the MISC type frame */
    if (type != WIFI_PKT_MISC && !packet->rx_ctrl.rx_state) {
        // Ignore non-broadcast frames
        if (only_capture_broadcast &&
                (packet_info.length < (4 + 6) || memcmp(&packet->payload[4], "\xFF\xFF\xFF\xFF\xFF\xFF", 6)))
            return;

        queue_packet(packet->payload, &packet_info);
    }
}

static esp_err_t eth_sniffer_cb(esp_eth_handle_t eth_handle, uint8_t *buffer, uint32_t length, void *priv)
{
    sniffer_packet_info_t packet_info;
    struct timeval tv_now;

    // ESP32 Ethernet MAC provides hardware time stamping for incoming frames in its Linked List Descriptors (see TMR, section 10.8.2).
    // However, this information is not currently accessible via Ethernet driver => do at least software time stamping
    gettimeofday(&tv_now, NULL);

    packet_info.seconds = tv_now.tv_sec;
    packet_info.microseconds = tv_now.tv_usec;
    packet_info.length = length;
    packet_info.interface = SNIFFER_INTF_ETH;
    packet_info.channel_mhz = 0;
    packet_info.rssi = 0;

    queue_packet(buffer, &packet_info);

    free(buffer);

    return ESP_OK;
}

static void sniffer_task(void *)
{
    sniffer_packet_info_t packet_info;

    while (is_running) {
        /* receive packet info from queue */
        if (xQueueReceive(work_queue, &packet_info, pdMS_TO_TICKS(SNIFFER_PROCESS_PACKET_TIMEOUT_MS)) != pdTRUE) {
            continue;
        }
        if (write_pcap && packet_capture(packet_info.payload, packet_info.length, packet_info.seconds,
                                         packet_info.microseconds) != ESP_OK) {
            ESP_LOGW(TAG, "save captured packet failed");
        }

        bool logged_to_serial = serial_logger_handle_packet(&packet_info);

        mqtt_handle_packet(&packet_info);

#if CONFIG_CITS_USB_SERIAL_ONLY
        // In standalone USB logger mode, blink only for packets that were
        // accepted and emitted as CITS serial log lines. This keeps the LED in
        // sync with received C-ITS messages instead of all raw Wi-Fi frames.
        if (logged_to_serial) {
            esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_RECEIVED_PACKET, NULL, 0, 0);
        }
#else
        (void)logged_to_serial;
        esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_RECEIVED_PACKET, NULL, 0, 0);
#endif

        free(packet_info.payload);
    }
    /* notify that sniffer task is over */
    xSemaphoreGive(sem_task_over);
    vTaskDelete(NULL);
}

static esp_err_t sniffer_stop()
{
    bool eth_set_promiscuous;
    esp_err_t ret = ESP_OK;

    ESP_GOTO_ON_FALSE(is_running, ESP_ERR_INVALID_STATE, err, TAG, "sniffer is already stopped");

    switch (interf) {
    case SNIFFER_INTF_WLAN:
        /* Disable wifi promiscuous mode */
        ESP_GOTO_ON_ERROR(esp_wifi_set_promiscuous(false), err, TAG, "stop wifi promiscuous failed");
        break;
    case SNIFFER_INTF_ETH:
        /* Disable Ethernet Promiscuous Mode */
        eth_set_promiscuous = false;
        ESP_GOTO_ON_ERROR(esp_eth_ioctl(eth_handles[interf_num], ETH_CMD_S_PROMISCUOUS, &eth_set_promiscuous),
                          err, TAG, "stop Ethernet promiscuous failed");
        esp_eth_update_input_path(eth_handles[interf_num], NULL, NULL);
        break;
    default:
        ESP_GOTO_ON_FALSE(false, ESP_ERR_INVALID_ARG, err, TAG, "unsupported interface");
        break;
    }
    ESP_LOGI(TAG, "stop promiscuous ok");

    /* stop sniffer local task */
    is_running = false;
    /* wait for task over */
    xSemaphoreTake(sem_task_over, portMAX_DELAY);

    /* make sure to free all resources in the left items */
    UBaseType_t left_items = uxQueueMessagesWaiting(work_queue);

    sniffer_packet_info_t packet_info;
    while (left_items--) {
        xQueueReceive(work_queue, &packet_info, pdMS_TO_TICKS(SNIFFER_PROCESS_PACKET_TIMEOUT_MS));
        free(packet_info.payload);
    }

    /* stop pcap session */
    if (write_pcap)
        sniff_packet_stop();

    esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_STOPPED, NULL, 0, 0);

err:
    return ret;
}

void phy_change_channel(int,int,int,int);
void phy_11p_set(int,int);

static esp_err_t sniffer_start()
{
    esp_err_t ret = ESP_OK;
    pcap_link_type_t link_type;
    wifi_promiscuous_filter_t wifi_filter;
    bool eth_set_promiscuous;

    ESP_GOTO_ON_FALSE(!is_running, ESP_ERR_INVALID_STATE, err, TAG, "sniffer is already running");

    switch (interf) {
    case SNIFFER_INTF_WLAN:
        link_type = PCAP_LINK_TYPE_802_11;
        break;
    case SNIFFER_INTF_ETH:
        link_type = PCAP_LINK_TYPE_ETHERNET;
        break;
    default:
        ESP_GOTO_ON_FALSE(false, ESP_ERR_INVALID_ARG, err, TAG, "unsupported interface");
        break;
    }

    /* init a pcap session */
    if (write_pcap)
        ESP_GOTO_ON_ERROR(sniff_packet_start(link_type), err, TAG, "init pcap session failed");

    is_running = true;
    ESP_GOTO_ON_FALSE(xTaskCreate(sniffer_task, "snifferT", CONFIG_SNIFFER_TASK_STACK_SIZE,
                                  NULL, CONFIG_SNIFFER_TASK_PRIORITY, &task), ESP_FAIL,
                      err_task, TAG, "create task failed");

    switch (interf) {
    case SNIFFER_INTF_WLAN:
        /* Start WiFi Promiscuous Mode */
        wifi_filter.filter_mask = filter;
        esp_wifi_set_promiscuous_filter(&wifi_filter);
        esp_wifi_set_promiscuous_rx_cb(wifi_sniffer_cb);
        ESP_GOTO_ON_ERROR(esp_wifi_set_promiscuous(true), err_start, TAG, "set promis failed");
        //ESP_GOTO_ON_ERROR(esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE), err_start, TAG, "set channel failed");
        // enable 802.11p mode (enable, unknown (must be 0))
        phy_11p_set(1, 0);
        // set a channel with a frequency close to our desired frequency (not sure if strictly needed)
        ESP_GOTO_ON_ERROR(esp_wifi_set_channel(140, WIFI_SECOND_CHAN_NONE), err_start, TAG, "set channel failed");
        // switch channel (channel, ignored, ignored, ht_mode?)
        phy_change_channel(channel, 1, 0, 0);
        ESP_LOGI(TAG, "start WiFi promiscuous ok");
        break;
    case SNIFFER_INTF_ETH:
        /* Start Ethernet Promiscuous Mode */
        eth_set_promiscuous = true;
        ESP_GOTO_ON_ERROR(esp_eth_ioctl(eth_handles[interf_num], ETH_CMD_S_PROMISCUOUS, &eth_set_promiscuous),
                          err_start, TAG, "start Ethernet promiscuous failed");
        esp_eth_update_input_path(eth_handles[interf_num], eth_sniffer_cb, NULL);
        ESP_LOGI(TAG, "start Ethernet promiscuous ok");
        break;
    default:
        break;
    }

    esp_event_post(SNIFFER_EVENT_BASE, SNIFFER_STARTED, NULL, 0, 0);

    return ret;
err_start:
    // task was already started, need to shut it down gracefully
    is_running = false;
    xSemaphoreTake(sem_task_over, portMAX_DELAY);
    task = NULL;
err_task:
    is_running = false;
    if (write_pcap)
        sniff_packet_stop();
err:
    return ret;
}

static struct {
    struct arg_str *interface;
    struct arg_lit *fcsfail;
    struct arg_int *channel;
    struct arg_lit *pcap;
    struct arg_lit *stop;
    struct arg_end *end;
} sniffer_args;

esp_err_t sniffer_reg_eth_intf(esp_eth_handle_t eth_handle)
{
    esp_err_t ret = ESP_OK;
    int32_t i = 0;
    while ((eth_handles[i] != NULL) && (i < SNIFFER_MAX_ETH_INTFS)) {
        i++;
    }
    ESP_GOTO_ON_FALSE(i < SNIFFER_MAX_ETH_INTFS, ESP_FAIL, err, TAG, "maximum num. of eth interfaces registered");
    eth_handles[i] = eth_handle;

err:
    return ret;
}

static int do_sniffer_cmd(int argc, char **argv)
{
    int nerrors = arg_parse(argc, argv, (void **)&sniffer_args);

    if (nerrors != 0) {
        arg_print_errors(stderr, sniffer_args.end, argv[0]);
        return 0;
    }

    /* Check whether or not to stop sniffer: "--stop" option */
    if (sniffer_args.stop->count) {
        /* stop sniffer */
        sniffer_stop();
        return 0;
    }

    /* Check interface: "-i" option */
    if (sniffer_args.interface->count) {
        if (!strncmp(sniffer_args.interface->sval[0], "wlan", 4)) {
            interf = SNIFFER_INTF_WLAN;
        } else if (!strncmp(sniffer_args.interface->sval[0], "eth", 3)
                   && strlen(sniffer_args.interface->sval[0]) >= 4) {
            char *end_ptr = NULL;
            const char *eth_num_str_start = sniffer_args.interface->sval[0] + 3;
            int32_t eth_intf_num = strtol(eth_num_str_start, &end_ptr, SNIFFER_DECIMAL_NUM);

            if ((eth_intf_num >= 0) && (eth_intf_num < SNIFFER_MAX_ETH_INTFS)
                    && (eth_num_str_start != end_ptr) && (eth_handles[eth_intf_num] != NULL)) {
                interf = SNIFFER_INTF_ETH;
                interf_num = eth_intf_num;
            } else {
                ESP_LOGE(TAG, "interface %s not found", sniffer_args.interface->sval[0]);
                return 1;
            }
        } else {
            ESP_LOGE(TAG, "interface %s not found", sniffer_args.interface->sval[0]);
            return 1;
        }
    } else {
        interf = SNIFFER_INTF_WLAN;
        ESP_LOGI(TAG, "sniffing interface set to wlan by default");
    }

    /* Check channel: "-c" option */
    switch (interf) {
    case SNIFFER_INTF_WLAN:
        channel = SNIFFER_DEFAULT_CHANNEL;
        if (sniffer_args.channel->count) {
            channel = sniffer_args.channel->ival[0];
        }
        break;
    case SNIFFER_INTF_ETH:
        if (sniffer_args.channel->count) {
            ESP_LOGW(TAG, "'channel' option is not available for Ethernet");
        }
        break;
    default:
        break;
    }

    /* Check filter setting: "-F" option */
    switch (interf) {
    case SNIFFER_INTF_WLAN:
            filter = WIFI_PROMIS_FILTER_MASK_ALL;
            if (!sniffer_args.fcsfail->count)
                filter &= ~WIFI_PROMIS_FILTER_MASK_FCSFAIL;
        break;
    case SNIFFER_INTF_ETH:
        if (sniffer_args.fcsfail->count) {
            ESP_LOGW(TAG, "'fcsfail' option is not available for Ethernet");
        }
    default:
        break;
    }

    if (sniffer_args.pcap->count) {
        write_pcap = true;
    } else {
        write_pcap = false;
    }

    uint8_t broadcast_only;
    esp_err_t ret = config_get_u8(CONFIG_INDEX_BROADCAST_ONLY, &broadcast_only);
    if (ret != ESP_OK)
    {
        ESP_LOGE(TAG, "config_get_u8 failed: %s", ret);
        return 1;
    }

    only_capture_broadcast = broadcast_only;

    /* start sniffer */
    sniffer_start();
    return 0;
}

void register_sniffer_cmd(void)
{
    sniffer_args.interface = arg_str0("i", "interface", "wlan|eth0|eth1|...",
                                      "which interface to capture packet");
    sniffer_args.fcsfail = arg_lit0("F", "fcsfail", "include corrupted packets with wrong FCS");
    sniffer_args.channel = arg_int0("c", "channel", "<channel freq>", "frequency of communication channel to use (G5CC = 5900, G5SC2 = 5890, G5SC1 = 5880, G5SC3 = 5870, G5SC4 = 5860)");
    sniffer_args.pcap = arg_lit0("P", "pcap", "write pcap to configured backend");
    sniffer_args.stop = arg_lit0(NULL, "stop", "stop running sniffer");
    sniffer_args.end = arg_end(1);
    const esp_console_cmd_t sniffer_cmd = {
        .command = "sniffer",
        .help = "Capture specific packet and store in pcap format",
        .hint = NULL,
        .func = &do_sniffer_cmd,
        .argtable = &sniffer_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&sniffer_cmd));
}

void sniffer_init(void)
{
    esp_err_t ret = ESP_OK;

    sem_task_over = xSemaphoreCreateBinary();
    ESP_GOTO_ON_FALSE(sem_task_over, ESP_FAIL, err, TAG, "create semaphore failed");

    work_queue = xQueueCreate(CONFIG_SNIFFER_WORK_QUEUE_LEN, sizeof(sniffer_packet_info_t));
    ESP_GOTO_ON_FALSE(work_queue, ESP_FAIL, err, TAG, "create work queue failed");

err:
    ESP_ERROR_CHECK(ret);
}

void sniffer_autostart(void)
{
    uint32_t conf_channel;
    esp_err_t ret = config_get_u32(CONFIG_INDEX_AUTOSTART_CHAN, &conf_channel);
    if (ret != ESP_OK)
    {
        if (ret != ESP_ERR_NVS_NOT_FOUND)
            ESP_LOGE(TAG, "config_get_u32 failed: %s", ret);
        return;
    }

    uint8_t broadcast_only;
    ret = config_get_u8(CONFIG_INDEX_BROADCAST_ONLY, &broadcast_only);
    if (ret != ESP_OK)
    {
        ESP_LOGE(TAG, "config_get_u8 failed: %s", ret);
        return;
    }

    if (conf_channel >= 5800 && conf_channel <= 5900)
    {
        interf = SNIFFER_INTF_WLAN;
        channel = conf_channel;
        filter = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL;
        only_capture_broadcast = broadcast_only;
        sniffer_start();
    }
}
