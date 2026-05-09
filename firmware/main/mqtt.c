#include "sdkconfig.h"

#include <math.h>
#include <stdbool.h>
#include <string.h>

#include "esp_app_desc.h"
#include "esp_console.h"
#include "esp_crt_bundle.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "mqtt_client.h"

#include "cmd_sniffer.h"
#include "config.h"
#include "events.h"
#include "temperature.h"

#include "mqtt.h"

static const char TAG[] = "MQTT";

static esp_mqtt_client_handle_t client;
static bool connected;

static char topic_prefix[96];
static char command_topic[128];
static char result_topic[128];
static char packet_topic[128];
static char status_topic[128];
static char stats_topic[128];
static char info_topic[128];

static esp_timer_handle_t stats_timer_handle;

static void mqtt_set_connected(bool new_connected)
{
    if (new_connected != connected)
    {
        connected = new_connected;
        esp_event_post(MQTT_EVENT_BASE, new_connected ? MQTT_CONNECTED : MQTT_DISCONNECTED, NULL, 0, 0);
    }
}

static void publish_node_info(void)
{
    char mac[6*2+5+1];

    {
        uint8_t eth_mac[6];
        ESP_ERROR_CHECK(esp_read_mac(eth_mac, ESP_MAC_ETH));

        // Espressif...
        eth_mac[0] |= 2;

        snprintf(mac, sizeof(mac), "%02hhx:%02hhx:%02hhx:%02hhx:%02hhx:%02hhx",
                 eth_mac[0], eth_mac[1], eth_mac[2], eth_mac[3], eth_mac[4], eth_mac[5]);
    }


    char info[128] = "{\"emac\":\"";
    char *info_ptr = info + sizeof("{\"emac\":\"") - 1;
    memcpy(info_ptr, mac, sizeof(mac) - 1);
    info_ptr += sizeof(mac) - 1;

    memcpy(info_ptr, "\",\"ver\":\"", sizeof("\",\"ver\":\"") - 1);
    info_ptr += sizeof("\",\"ver\":\"") - 1;

    const esp_app_desc_t *app_desc = esp_app_get_description();
    size_t ver_len = strlen(app_desc->version);
    memcpy(info_ptr, app_desc->version, ver_len);
    info_ptr += ver_len;

    memcpy(info_ptr, "\",\"hwv\":\"", sizeof("\",\"hwv\":\"") - 1);
    info_ptr += sizeof("\",\"hwv\":\"") - 1;
    memcpy(info_ptr, CONFIG_HW_VARIANT, sizeof(CONFIG_HW_VARIANT) - 1);
    info_ptr += sizeof(CONFIG_HW_VARIANT) - 1;

    memcpy(info_ptr, "\"}", sizeof("\"}") - 1);
    info_ptr += sizeof("\"}") - 1;

    esp_mqtt_client_publish(client, info_topic, info, info_ptr - info, 0, 0);
}

static void publish_stats(void *)
{
    char stats[128] = "{";
    char *stats_ptr = stats + sizeof("{") - 1;

#ifdef CONFIG_ENABLE_TEMPERATURE
    float temp_f = temperature_get();
    if (!isnanf(temp_f))
    {
        char temperature[5+1+1];
        int len = snprintf(temperature, sizeof(temperature), "%.1f", temp_f);
        memcpy(stats_ptr, "\"temp\":", sizeof("\"temp\":") - 1);
        stats_ptr += sizeof("\"temp\":") + len - 1;

        size_t temperature_len = strlen(temperature);
        memcpy(stats_ptr, temperature, temperature_len);
        stats_ptr += temperature_len;
    }
#endif // CONFIG_ENABLE_TEMPERATURE

    memcpy(stats_ptr, "}", sizeof("}") - 1);
    stats_ptr += sizeof("}") - 1;

    esp_mqtt_client_publish(client, stats_topic, stats, stats_ptr - stats
                            , 0, 0);
}

static void mqtt_event_handler(void *handler_args, esp_event_base_t base, int32_t event_id, void *event_data) {
    ESP_LOGD(TAG, "Event dispatched from event loop base=%s, event_id=%d", base, event_id);

    esp_mqtt_event_handle_t event = event_data;
    esp_mqtt_client_handle_t client = event->client;
    int msg_id;
    // your_context_t *context = event->context;
    switch (event->event_id) {
        case MQTT_EVENT_CONNECTED:
            mqtt_set_connected(true);
            ESP_LOGI(TAG, "MQTT_EVENT_CONNECTED");
            msg_id = esp_mqtt_client_subscribe(client, command_topic, 0);
            ESP_LOGI(TAG, "sent subscribe successful, msg_id=%d", msg_id);

            esp_mqtt_client_publish(client, status_topic, "online", sizeof("online") - 1, 0, 1);

            publish_node_info();
            publish_stats(NULL);

            esp_timer_start_periodic(stats_timer_handle, 60000000);

            break;
        case MQTT_EVENT_DISCONNECTED:
            mqtt_set_connected(false);
            ESP_LOGI(TAG, "MQTT_EVENT_DISCONNECTED");
            break;
        case MQTT_EVENT_SUBSCRIBED:
            ESP_LOGI(TAG, "MQTT_EVENT_SUBSCRIBED, msg_id=%d", event->msg_id);
            break;
        case MQTT_EVENT_UNSUBSCRIBED:
            ESP_LOGI(TAG, "MQTT_EVENT_UNSUBSCRIBED, msg_id=%d", event->msg_id);
            break;
        case MQTT_EVENT_PUBLISHED:
            ESP_LOGI(TAG, "MQTT_EVENT_PUBLISHED, msg_id=%d", event->msg_id);
            break;
        case MQTT_EVENT_DATA:
            ESP_LOGI(TAG, "MQTT_EVENT_DATA");
            printf("TOPIC=%.*s\r\n", event->topic_len, event->topic);
            printf("DATA=%.*s\r\n", event->data_len, event->data);
            if (strlen(command_topic) == event->topic_len && !strncmp(event->topic, command_topic, event->topic_len))
            {
                char *cmd = strndup(event->data, event->data_len);
                ESP_LOGI(TAG, "Running command '%s'", event->data);

                int ret;
                int res = esp_console_run(cmd, &ret);
                free(cmd);
                char result_buf[128];
                if (res != ESP_OK)
                {
                    snprintf(result_buf, sizeof(result_buf), "esp_console_run failed: %s", esp_err_to_name(res));
                    ESP_LOGE(TAG, "%s", result_buf);
                    break;
                }
                else
                {
                    snprintf(result_buf, sizeof(result_buf), "%d", ret);
                }

                esp_mqtt_client_publish(client, result_topic, result_buf, strlen(result_buf), 0, 0);
            }
            break;
        case MQTT_EVENT_ERROR:
            mqtt_set_connected(false);
            ESP_LOGI(TAG, "MQTT_EVENT_ERROR");
            if (event->error_handle->error_type == MQTT_ERROR_TYPE_TCP_TRANSPORT) {
                ESP_LOGI(TAG, "Last error code reported from esp-tls: 0x%x", event->error_handle->esp_tls_last_esp_err);
                ESP_LOGI(TAG, "Last tls stack error number: 0x%x", event->error_handle->esp_tls_stack_err);
                ESP_LOGI(TAG, "Last captured errno : %d (%s)",  event->error_handle->esp_transport_sock_errno,
                                                                strerror(event->error_handle->esp_transport_sock_errno));
            } else if (event->error_handle->error_type == MQTT_ERROR_TYPE_CONNECTION_REFUSED) {
                ESP_LOGI(TAG, "Connection refused error: 0x%x", event->error_handle->connect_return_code);
            } else {
                ESP_LOGW(TAG, "Unknown error type: 0x%x", event->error_handle->error_type);
            }
            break;
        default:
            ESP_LOGI(TAG, "Other event id:%d", event->event_id);
            break;
    }
}

static int make_topic_prefix()
{
    size_t size = sizeof(topic_prefix);
    strcpy(topic_prefix, "its/");
    size -= 4;

    esp_err_t res = config_get_str(CONFIG_INDEX_NODEID, topic_prefix + 4, &size);
    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "Could not get node ID from config: %s", esp_err_to_name(res));
        return res;
    }

    topic_prefix[4 + size - 1] = '/';
    topic_prefix[4 + size] = '\0';

    return ESP_OK;
}

void mqtt_start(void)
{
    if (client)
    {
        ESP_LOGW(TAG, "MQTT already started");
        return;
    }

    if (make_topic_prefix() != ESP_OK)
    {
        ESP_LOGW(TAG, "make_topic_prefix failed");
        return;
    }

    strcpy(command_topic, topic_prefix);
    strcat(command_topic, "command");

    strcpy(result_topic, topic_prefix);
    strcat(result_topic, "command_result");

    strcpy(packet_topic, topic_prefix);
    strcat(packet_topic, "packet");

    strcpy(status_topic, topic_prefix);
    strcat(status_topic, "status");

    strcpy(info_topic, topic_prefix);
    strcat(info_topic, "info");

    strcpy(stats_topic, topic_prefix);
    strcat(stats_topic, "stats");

    esp_mqtt_client_config_t mqtt_cfg = {0};

    char mqtt_uri[CONFIG_MQTT_URI_BUFFER_SIZE];
    size_t size = sizeof(mqtt_uri);
    esp_err_t res = config_get_str(CONFIG_INDEX_MQTT_URI, mqtt_uri, &size);
    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "Could not get MQTT URI from config: %s", esp_err_to_name(res));
        return;
    }

    mqtt_cfg.broker.address.uri = mqtt_uri;
    mqtt_cfg.broker.verification.crt_bundle_attach = esp_crt_bundle_attach;
    struct last_will_t last_will = {
        .topic = status_topic,
        .msg = "offline",
        .msg_len = sizeof("offline") - 1,
        .retain = 1
    };
    mqtt_cfg.session.last_will = last_will;

    esp_mqtt_client_handle_t client_ = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(client_, ESP_EVENT_ANY_ID, mqtt_event_handler, client_);
    esp_mqtt_client_start(client_);

    client = client_;
    ESP_LOGI(TAG, "MQTT started");
}

void mqtt_stop(void)
{
    if (!client)
    {
        ESP_LOGW(TAG, "Attempted to stop MQTT while not running");
        return;
    }

    esp_timer_stop_blocking(stats_timer_handle, 1000);

    esp_mqtt_client_stop(client);
    mqtt_set_connected(false);

    esp_mqtt_client_handle_t client_ = client;
    client = NULL;
    esp_mqtt_client_destroy(client_);

    ESP_LOGI(TAG, "MQTT stopped");
}

static void app_event_handler(void *handler_args, esp_event_base_t base, int32_t event_id, void *event_data)
{
    switch (event_id)
    {
    case APP_ETHERNET_MGMT_INTERFACE_GOT_IP:
        mqtt_start();
        break;
    case APP_ETHERNET_MGMT_INTERFACE_LOST_IP:
        mqtt_stop();
        break;
    }
}

void mqtt_handle_packet(sniffer_packet_info_t *packet)
{
    if (!client || !connected)
        return;

    esp_mqtt_client_publish(client, packet_topic, (const char *)packet->payload, packet->length, 0, 0);
}

void mqtt_init(void)
{
    esp_event_handler_register(APP_EVENT_BASE, ESP_EVENT_ANY_ID, app_event_handler, NULL);

    esp_timer_create_args_t create_args = {
        .callback = publish_stats,
        .arg = NULL,
        .name = "mqtt_stats"
    };
    ESP_ERROR_CHECK(esp_timer_create(&create_args, &stats_timer_handle));
}
