#include "sdkconfig.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/spi_slave.h"
#include "esp_check.h"
#include "esp_crt_bundle.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_heap_caps.h"
#include "esp_netif.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_tls.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "lwip/netdb.h"
#include "lwip/sockets.h"
#include "nvs_flash.h"

#define TAG "CITS_UPLINK"

#define CITS_FRAME_MAGIC_0 'C'
#define CITS_FRAME_MAGIC_1 'T'
#define CITS_FRAME_MAGIC_2 'G'
#define CITS_FRAME_MAGIC_3 '1'
#define CITS_PROTOCOL_VERSION 1
#define CITS_FRAME_TYPE_PACKET 1
#define CITS_PACKET_HEADER_LEN 32
#define CITS_FRAME_TRAILER_LEN 4
#define CITS_MAX_PACKET_BYTES 4096
#define CITS_COBS_OVERHEAD(len) (((len) / 254u) + 1u)
#define CITS_DECODED_MAX_LEN (CITS_PACKET_HEADER_LEN + CITS_MAX_PACKET_BYTES + CITS_FRAME_TRAILER_LEN)
#define CITS_ENCODED_MAX_LEN (CITS_DECODED_MAX_LEN + CITS_COBS_OVERHEAD(CITS_DECODED_MAX_LEN))

#define WIFI_CONNECTED_BIT BIT0
#define MQTT_KEEPALIVE_SECONDS 60
#define MQTT_TX_MAX_LEN (CITS_MAX_PACKET_BYTES + 256)

typedef struct {
    uint16_t captured_len;
    const uint8_t *payload;
} cits_packet_t;

typedef struct {
    bool tls;
    char host[128];
    int port;
} mqtt_uri_t;

static EventGroupHandle_t connection_events;
static char node_id[13];
static char topic_packet[64];
static char topic_status[64];
static char topic_info[64];
static char topic_stats[64];
static uint64_t packets_seen;
static uint64_t packets_published;
static uint64_t protocol_errors;
static uint64_t serial_bytes_seen;
static int64_t start_time_us;
static TickType_t last_uplink_loop_tick;
static TickType_t mqtt_disconnected_since_tick;
static TickType_t wifi_disconnected_since_tick;
static esp_tls_t *mqtt_tls;
static int mqtt_sock = -1;
static bool mqtt_connected;
static uint8_t *spi_rx_buf;
static uint8_t serial_encoded[CITS_ENCODED_MAX_LEN];
static uint8_t serial_decoded[CITS_DECODED_MAX_LEN];

static uint32_t elapsed_ms(TickType_t now, TickType_t since)
{
    return (uint32_t)((now - since) * portTICK_PERIOD_MS);
}

static void set_led(bool on)
{
    const int level = CONFIG_CITS_UPLINK_LED_ACTIVE_LOW ? !on : on;
    gpio_set_level(CONFIG_CITS_UPLINK_LED_GPIO, level);
}

static esp_err_t init_led(void)
{
    gpio_config_t io_conf = {
        .pin_bit_mask = 1ULL << CONFIG_CITS_UPLINK_LED_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&io_conf), TAG, "gpio_config");
    set_led(false);
    return ESP_OK;
}

static void set_spi_ready(bool ready)
{
    gpio_set_level(CONFIG_CITS_UPLINK_SPI_READY_GPIO, ready ? 1 : 0);
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

static esp_err_t cobs_decode_to(const uint8_t *input, size_t input_len, uint8_t *output, size_t *output_len)
{
    size_t read = 0;
    size_t write = 0;

    while (read < input_len) {
        uint8_t code = input[read++];
        if (code == 0) {
            return ESP_ERR_INVALID_ARG;
        }
        for (uint8_t i = 1; i < code; ++i) {
            if (read >= input_len || write >= CITS_DECODED_MAX_LEN) {
                return ESP_ERR_INVALID_SIZE;
            }
            output[write++] = input[read++];
        }
        if (code != 0xff && read < input_len) {
            if (write >= CITS_DECODED_MAX_LEN) {
                return ESP_ERR_INVALID_SIZE;
            }
            output[write++] = 0;
        }
    }

    *output_len = write;
    return ESP_OK;
}

static esp_err_t decode_ctg_packet(const uint8_t *encoded, size_t encoded_len,
                                   uint8_t *decoded, cits_packet_t *packet)
{
    size_t decoded_len = 0;
    ESP_RETURN_ON_ERROR(cobs_decode_to(encoded, encoded_len, decoded, &decoded_len), TAG, "cobs_decode_to");
    ESP_RETURN_ON_FALSE(decoded_len >= CITS_PACKET_HEADER_LEN + CITS_FRAME_TRAILER_LEN,
                        ESP_ERR_INVALID_SIZE, TAG, "frame too short");
    ESP_RETURN_ON_FALSE(decoded[0] == CITS_FRAME_MAGIC_0 &&
                        decoded[1] == CITS_FRAME_MAGIC_1 &&
                        decoded[2] == CITS_FRAME_MAGIC_2 &&
                        decoded[3] == CITS_FRAME_MAGIC_3,
                        ESP_ERR_INVALID_RESPONSE, TAG, "bad CTG1 magic");
    ESP_RETURN_ON_FALSE(decoded[4] == CITS_PROTOCOL_VERSION,
                        ESP_ERR_NOT_SUPPORTED, TAG, "unsupported CTG version");
    ESP_RETURN_ON_FALSE(decoded[5] == CITS_FRAME_TYPE_PACKET,
                        ESP_ERR_NOT_SUPPORTED, TAG, "unsupported CTG frame type");

    const uint16_t header_len = get_u16(&decoded[6]);
    const uint16_t captured_len = get_u16(&decoded[26]);
    const size_t total_len = (size_t)header_len + captured_len + CITS_FRAME_TRAILER_LEN;
    ESP_RETURN_ON_FALSE(header_len == CITS_PACKET_HEADER_LEN, ESP_ERR_INVALID_RESPONSE, TAG, "bad header len");
    ESP_RETURN_ON_FALSE(captured_len <= CITS_MAX_PACKET_BYTES, ESP_ERR_INVALID_SIZE, TAG, "packet too large");
    ESP_RETURN_ON_FALSE(decoded_len == total_len, ESP_ERR_INVALID_SIZE, TAG, "length mismatch");

    const uint32_t expected_crc = get_u32(&decoded[total_len - CITS_FRAME_TRAILER_LEN]);
    const uint32_t actual_crc = crc32_ieee(decoded, total_len - CITS_FRAME_TRAILER_LEN);
    ESP_RETURN_ON_FALSE(expected_crc == actual_crc, ESP_ERR_INVALID_CRC, TAG, "CRC mismatch");

    packet->captured_len = captured_len;
    packet->payload = &decoded[header_len];
    return ESP_OK;
}

static esp_err_t parse_mqtt_uri(const char *raw, mqtt_uri_t *out)
{
    const char *host = NULL;
    const char *port_text = NULL;
    const char *path = NULL;

    memset(out, 0, sizeof(*out));
    if (strncmp(raw, "mqtts://", 8) == 0 || strncmp(raw, "ssl://", 6) == 0) {
        out->tls = true;
        out->port = 8883;
        host = raw + (raw[0] == 's' ? 6 : 8);
    } else if (strncmp(raw, "mqtt://", 7) == 0) {
        out->tls = false;
        out->port = 1883;
        host = raw + 7;
    } else {
        out->tls = false;
        out->port = 1883;
        host = raw;
    }

    path = strchr(host, '/');
    port_text = strchr(host, ':');
    size_t host_len = path ? (size_t)(path - host) : strlen(host);
    if (port_text && (!path || port_text < path)) {
        host_len = (size_t)(port_text - host);
        out->port = atoi(port_text + 1);
    }

    ESP_RETURN_ON_FALSE(host_len > 0 && host_len < sizeof(out->host), ESP_ERR_INVALID_ARG, TAG, "bad MQTT host");
    memcpy(out->host, host, host_len);
    out->host[host_len] = 0;
    return ESP_OK;
}

static void mqtt_close(void)
{
    if (mqtt_connected) {
        uint8_t disconnect[] = {0xe0, 0x00};
        if (mqtt_tls) {
            esp_tls_conn_write(mqtt_tls, disconnect, sizeof(disconnect));
        } else if (mqtt_sock >= 0) {
            send(mqtt_sock, disconnect, sizeof(disconnect), 0);
        }
    }
    if (mqtt_tls) {
        esp_tls_conn_destroy(mqtt_tls);
        mqtt_tls = NULL;
    }
    if (mqtt_sock >= 0) {
        close(mqtt_sock);
        mqtt_sock = -1;
    }
    mqtt_connected = false;
}

static ssize_t mqtt_write_raw(const uint8_t *data, size_t len)
{
    if (mqtt_tls) {
        return esp_tls_conn_write(mqtt_tls, data, len);
    }
    if (mqtt_sock >= 0) {
        return send(mqtt_sock, data, len, 0);
    }
    return -1;
}

static ssize_t mqtt_read_raw(uint8_t *data, size_t len)
{
    if (mqtt_tls) {
        return esp_tls_conn_read(mqtt_tls, data, len);
    }
    if (mqtt_sock >= 0) {
        return recv(mqtt_sock, data, len, 0);
    }
    return -1;
}

static esp_err_t mqtt_write_all(const uint8_t *data, size_t len)
{
    while (len > 0) {
        ssize_t written = mqtt_write_raw(data, len);
        if (written <= 0) {
            return ESP_FAIL;
        }
        data += written;
        len -= (size_t)written;
    }
    return ESP_OK;
}

static size_t mqtt_put_u16(uint8_t *buf, uint16_t value)
{
    buf[0] = (uint8_t)(value >> 8);
    buf[1] = (uint8_t)value;
    return 2;
}

static size_t mqtt_put_utf8(uint8_t *buf, const char *value)
{
    size_t len = strlen(value);
    mqtt_put_u16(buf, (uint16_t)len);
    memcpy(buf + 2, value, len);
    return len + 2;
}

static size_t mqtt_put_remaining_length(uint8_t *buf, size_t value)
{
    size_t written = 0;
    do {
        uint8_t encoded = value % 128;
        value /= 128;
        if (value > 0) {
            encoded |= 128;
        }
        buf[written++] = encoded;
    } while (value > 0);
    return written;
}

static esp_err_t mqtt_send_packet(uint8_t fixed_header, const uint8_t *body, size_t body_len)
{
    uint8_t header[5];
    header[0] = fixed_header;
    size_t header_len = 1 + mqtt_put_remaining_length(header + 1, body_len);
    ESP_RETURN_ON_ERROR(mqtt_write_all(header, header_len), TAG, "mqtt header write");
    return mqtt_write_all(body, body_len);
}

static esp_err_t mqtt_publish(const char *topic, const uint8_t *payload, size_t payload_len, bool retain)
{
    uint8_t body[MQTT_TX_MAX_LEN];
    size_t topic_len = strlen(topic);
    ESP_RETURN_ON_FALSE(topic_len + payload_len + 2 <= sizeof(body), ESP_ERR_INVALID_SIZE, TAG, "MQTT packet too large");
    size_t off = mqtt_put_utf8(body, topic);
    memcpy(body + off, payload, payload_len);
    off += payload_len;
    return mqtt_send_packet(retain ? 0x31 : 0x30, body, off);
}

static esp_err_t mqtt_read_connack(void)
{
    uint8_t connack[4];
    ssize_t got = mqtt_read_raw(connack, sizeof(connack));
    ESP_RETURN_ON_FALSE(got == sizeof(connack), ESP_FAIL, TAG, "CONNACK read failed");
    ESP_RETURN_ON_FALSE(connack[0] == 0x20 && connack[1] == 0x02 && connack[3] == 0x00,
                        ESP_FAIL, TAG, "CONNACK refused");
    return ESP_OK;
}

static esp_err_t mqtt_open_plain(const mqtt_uri_t *uri)
{
    struct addrinfo hints = {
        .ai_family = AF_INET,
        .ai_socktype = SOCK_STREAM,
    };
    struct addrinfo *res = NULL;
    char port[8];
    snprintf(port, sizeof(port), "%d", uri->port);
    ESP_RETURN_ON_FALSE(getaddrinfo(uri->host, port, &hints, &res) == 0 && res != NULL,
                        ESP_FAIL, TAG, "MQTT DNS failed");

    mqtt_sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (mqtt_sock >= 0 && connect(mqtt_sock, res->ai_addr, res->ai_addrlen) != 0) {
        close(mqtt_sock);
        mqtt_sock = -1;
    }
    freeaddrinfo(res);
    return mqtt_sock >= 0 ? ESP_OK : ESP_FAIL;
}

static esp_err_t mqtt_connect(void)
{
    mqtt_uri_t uri;
    uint8_t body[256];
    char client_id[64];
    size_t off = 0;

    mqtt_close();
    ESP_RETURN_ON_ERROR(parse_mqtt_uri(CONFIG_CITS_UPLINK_MQTT_URI, &uri), TAG, "parse MQTT URI");
    ESP_LOGI(TAG, "connecting MQTT %s:%d tls=%d", uri.host, uri.port, uri.tls);

    if (uri.tls) {
        esp_tls_cfg_t cfg = {
            .crt_bundle_attach = esp_crt_bundle_attach,
            .timeout_ms = 15000,
        };
        mqtt_tls = esp_tls_init();
        ESP_RETURN_ON_FALSE(mqtt_tls != NULL, ESP_ERR_NO_MEM, TAG, "esp_tls_init");
        int ret = esp_tls_conn_new_sync(uri.host, strlen(uri.host), uri.port, &cfg, mqtt_tls);
        ESP_RETURN_ON_FALSE(ret == 1, ESP_FAIL, TAG, "TLS connect failed");
    } else {
        ESP_RETURN_ON_ERROR(mqtt_open_plain(&uri), TAG, "plain MQTT connect");
    }

    snprintf(client_id, sizeof(client_id), "cits-wifi-%s", node_id);
    off += mqtt_put_utf8(body + off, "MQTT");
    body[off++] = 4;
    body[off++] = 0x02 | 0x04 | 0x20;
    off += mqtt_put_u16(body + off, MQTT_KEEPALIVE_SECONDS);
    off += mqtt_put_utf8(body + off, client_id);
    off += mqtt_put_utf8(body + off, topic_status);
    off += mqtt_put_u16(body + off, 7);
    memcpy(body + off, "offline", 7);
    off += 7;

    ESP_RETURN_ON_ERROR(mqtt_send_packet(0x10, body, off), TAG, "MQTT CONNECT");
    ESP_RETURN_ON_ERROR(mqtt_read_connack(), TAG, "MQTT CONNACK");
    mqtt_connected = true;
    mqtt_disconnected_since_tick = 0;
    ESP_RETURN_ON_ERROR(mqtt_publish(topic_status, (const uint8_t *)"online", 6, true), TAG, "MQTT online");
    return ESP_OK;
}

static void derive_node_id(void)
{
    if (strlen(CONFIG_CITS_UPLINK_NODE_ID) > 0) {
        strlcpy(node_id, CONFIG_CITS_UPLINK_NODE_ID, sizeof(node_id));
        return;
    }

    uint8_t mac[6];
    ESP_ERROR_CHECK(esp_read_mac(mac, ESP_MAC_WIFI_STA));
    snprintf(node_id, sizeof(node_id), "%02x%02x%02x%02x%02x%02x",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

static void init_topics(void)
{
    snprintf(topic_packet, sizeof(topic_packet), "its/%s/packet", node_id);
    snprintf(topic_status, sizeof(topic_status), "its/%s/status", node_id);
    snprintf(topic_info, sizeof(topic_info), "its/%s/info", node_id);
    snprintf(topic_stats, sizeof(topic_stats), "its/%s/stats", node_id);
}

static esp_err_t publish_info(void)
{
    char payload[128];
    char emac[18];

    if (strlen(node_id) == 12) {
        snprintf(emac, sizeof(emac), "%c%c:%c%c:%c%c:%c%c:%c%c:%c%c",
                 node_id[0], node_id[1], node_id[2], node_id[3], node_id[4], node_id[5],
                 node_id[6], node_id[7], node_id[8], node_id[9], node_id[10], node_id[11]);
    } else {
        strlcpy(emac, node_id, sizeof(emac));
    }

    snprintf(payload, sizeof(payload),
             "{\"emac\":\"%s\",\"ver\":\"1\",\"hwv\":\"xiao-esp32c5-wifi-uplink\"}", emac);
    return mqtt_publish(topic_info, (const uint8_t *)payload, strlen(payload), false);
}

static esp_err_t publish_stats(void)
{
    char payload[64];
    const int64_t uptime = (esp_timer_get_time() - start_time_us) / 1000000;
    snprintf(payload, sizeof(payload), "{\"rbt\":%lld}", (long long)uptime);
    return mqtt_publish(topic_stats, (const uint8_t *)payload, strlen(payload), false);
}

static esp_err_t mqtt_ping(void)
{
    uint8_t ping[] = {0xc0, 0x00};
    return mqtt_write_all(ping, sizeof(ping));
}

static void wifi_event_handler(void *arg, esp_event_base_t event_base, int32_t event_id, void *event_data)
{
    (void)arg;
    (void)event_data;

    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        set_led(false);
        wifi_disconnected_since_tick = xTaskGetTickCount();
        esp_wifi_connect();
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        set_led(false);
        xEventGroupClearBits(connection_events, WIFI_CONNECTED_BIT);
        if (wifi_disconnected_since_tick == 0) {
            wifi_disconnected_since_tick = xTaskGetTickCount();
        }
        mqtt_close();
        esp_wifi_connect();
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        set_led(true);
        wifi_disconnected_since_tick = 0;
        xEventGroupSetBits(connection_events, WIFI_CONNECTED_BIT);
    }
}

static esp_err_t init_wifi(void)
{
    ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "esp_netif_init");
    ESP_RETURN_ON_ERROR(esp_event_loop_create_default(), TAG, "esp_event_loop_create_default");
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&cfg), TAG, "esp_wifi_init");
    ESP_RETURN_ON_ERROR(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event_handler, NULL),
                        TAG, "wifi handler");
    ESP_RETURN_ON_ERROR(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event_handler, NULL),
                        TAG, "ip handler");

    wifi_config_t wifi_config = {
        .sta = {
            .ssid = CONFIG_CITS_UPLINK_WIFI_SSID,
            .password = CONFIG_CITS_UPLINK_WIFI_PASSWORD,
            .threshold.authmode = WIFI_AUTH_WPA2_PSK,
        },
    };

    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_STA), TAG, "esp_wifi_set_mode");
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_STA, &wifi_config), TAG, "esp_wifi_set_config");
    return esp_wifi_start();
}

static esp_err_t init_spi(void)
{
    const int miso_gpio = CONFIG_CITS_UPLINK_SPI_MISO_GPIO >= 0 ?
        CONFIG_CITS_UPLINK_SPI_MISO_GPIO : -1;
    const spi_bus_config_t buscfg = {
        .mosi_io_num = CONFIG_CITS_UPLINK_SPI_MOSI_GPIO,
        .miso_io_num = miso_gpio,
        .sclk_io_num = CONFIG_CITS_UPLINK_SPI_CLK_GPIO,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = CITS_ENCODED_MAX_LEN,
    };
    const spi_slave_interface_config_t slvcfg = {
        .mode = 0,
        .spics_io_num = CONFIG_CITS_UPLINK_SPI_CS_GPIO,
        .queue_size = 1,
    };
    gpio_config_t ready_cfg = {
        .pin_bit_mask = 1ULL << CONFIG_CITS_UPLINK_SPI_READY_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };

    spi_rx_buf = heap_caps_malloc(CITS_ENCODED_MAX_LEN, MALLOC_CAP_DMA);
    ESP_RETURN_ON_FALSE(spi_rx_buf != NULL, ESP_ERR_NO_MEM, TAG, "spi rx buffer");
    ESP_RETURN_ON_ERROR(gpio_config(&ready_cfg), TAG, "spi ready gpio_config");
    set_spi_ready(false);
    return spi_slave_initialize(SPI2_HOST, &buscfg, &slvcfg, SPI_DMA_CH_AUTO);
}

static void ensure_mqtt_connected(void)
{
    if ((xEventGroupGetBits(connection_events) & WIFI_CONNECTED_BIT) == 0 || mqtt_connected) {
        return;
    }
    if (mqtt_disconnected_since_tick == 0) {
        mqtt_disconnected_since_tick = xTaskGetTickCount();
    }
    if (mqtt_connect() == ESP_OK) {
        ESP_LOGI(TAG, "MQTT connected topic=%s", topic_packet);
        (void)publish_info();
        (void)publish_stats();
    } else {
        ESP_LOGW(TAG, "MQTT connect failed; retrying");
        mqtt_close();
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}

static void health_task(void *arg)
{
    (void)arg;
    TickType_t last_wifi_recovery_tick = 0;
    TickType_t last_mqtt_recovery_tick = 0;

    for (;;) {
        const TickType_t now = xTaskGetTickCount();
        const EventBits_t bits = xEventGroupGetBits(connection_events);
        const bool wifi_connected = (bits & WIFI_CONNECTED_BIT) != 0;

        if (!wifi_connected) {
            if (wifi_disconnected_since_tick == 0) {
                wifi_disconnected_since_tick = now;
            }
            if (last_wifi_recovery_tick == 0 ||
                elapsed_ms(now, last_wifi_recovery_tick) >= CONFIG_CITS_UPLINK_WIFI_RECOVERY_INTERVAL_MS) {
                last_wifi_recovery_tick = now;
                ESP_LOGW(TAG, "Wi-Fi disconnected; retrying connection");
                mqtt_close();
                (void)esp_wifi_connect();
            }
            if (elapsed_ms(now, wifi_disconnected_since_tick) >= CONFIG_CITS_UPLINK_WIFI_RESTART_AFTER_MS) {
                ESP_LOGE(TAG, "Wi-Fi down for %u ms; restarting",
                         elapsed_ms(now, wifi_disconnected_since_tick));
                esp_restart();
            }
        } else {
            last_wifi_recovery_tick = 0;
            wifi_disconnected_since_tick = 0;
        }

        if (last_uplink_loop_tick != 0 &&
            elapsed_ms(now, last_uplink_loop_tick) >= CONFIG_CITS_UPLINK_TASK_STALL_RESTART_MS) {
            ESP_LOGE(TAG, "SPI uplink task stalled for %u ms; restarting",
                     elapsed_ms(now, last_uplink_loop_tick));
            esp_restart();
        }

        if (wifi_connected && !mqtt_connected) {
            if (mqtt_disconnected_since_tick == 0) {
                mqtt_disconnected_since_tick = now;
            }
            if (elapsed_ms(now, mqtt_disconnected_since_tick) >= CONFIG_CITS_UPLINK_MQTT_RECOVERY_MS &&
                (last_mqtt_recovery_tick == 0 ||
                 elapsed_ms(now, last_mqtt_recovery_tick) >= CONFIG_CITS_UPLINK_MQTT_RECOVERY_MS)) {
                last_mqtt_recovery_tick = now;
                ESP_LOGW(TAG, "MQTT disconnected for %u ms; cycling Wi-Fi",
                         elapsed_ms(now, mqtt_disconnected_since_tick));
                mqtt_close();
                (void)esp_wifi_disconnect();
                (void)esp_wifi_connect();
            }
        } else {
            last_mqtt_recovery_tick = 0;
            if (mqtt_connected) {
                mqtt_disconnected_since_tick = 0;
            }
        }

        vTaskDelay(pdMS_TO_TICKS(CONFIG_CITS_UPLINK_HEALTH_CHECK_INTERVAL_MS));
    }
}

static void spi_uplink_task(void *arg)
{
    (void)arg;
    size_t encoded_len = 0;
    int64_t last_stats_us = esp_timer_get_time();
    int64_t last_ping_us = esp_timer_get_time();

    for (;;) {
        last_uplink_loop_tick = xTaskGetTickCount();
        ensure_mqtt_connected();

        size_t count = 0;
        spi_slave_transaction_t trans = {
            .length = CITS_ENCODED_MAX_LEN * 8,
            .rx_buffer = spi_rx_buf,
        };
        set_spi_ready(true);
        esp_err_t spi_err = spi_slave_transmit(SPI2_HOST, &trans, pdMS_TO_TICKS(100));
        set_spi_ready(false);
        if (spi_err == ESP_OK) {
            count = (trans.trans_len + 7) / 8;
            serial_bytes_seen += (uint64_t)count;
        }

        for (size_t i = 0; i < count; ++i) {
            const uint8_t b = spi_rx_buf[i];
            if (b == 0) {
                if (encoded_len > 0) {
                    cits_packet_t packet;
                    esp_err_t err = decode_ctg_packet(serial_encoded, encoded_len, serial_decoded, &packet);
                    if (err == ESP_OK) {
                        ++packets_seen;
                        if (mqtt_connected &&
                            mqtt_publish(topic_packet, packet.payload, packet.captured_len, false) == ESP_OK) {
                            ++packets_published;
                            mqtt_disconnected_since_tick = 0;
                        } else if (mqtt_connected) {
                            mqtt_close();
                        }
                    } else {
                        ++protocol_errors;
                    }
                    encoded_len = 0;
                }
            } else if (encoded_len < sizeof(serial_encoded)) {
                serial_encoded[encoded_len++] = b;
            } else {
                ++protocol_errors;
                encoded_len = 0;
            }
        }

        const int64_t now = esp_timer_get_time();
        if (mqtt_connected && now - last_ping_us >= (MQTT_KEEPALIVE_SECONDS * 500000LL)) {
            last_ping_us = now;
            if (mqtt_ping() != ESP_OK) {
                mqtt_close();
            }
        }
        if (now - last_stats_us >= (int64_t)CONFIG_CITS_UPLINK_STATUS_INTERVAL_MS * 1000) {
            last_stats_us = now;
            if (mqtt_connected && publish_stats() != ESP_OK) {
                mqtt_close();
            }
            ESP_LOGI(TAG, "rx_bytes=%llu pending=%u seen=%llu published=%llu protocol_errors=%llu",
                     (unsigned long long)serial_bytes_seen,
                     (unsigned)encoded_len,
                     (unsigned long long)packets_seen,
                     (unsigned long long)packets_published,
                     (unsigned long long)protocol_errors);
        }
    }
}

void app_main(void)
{
    start_time_us = esp_timer_get_time();
    connection_events = xEventGroupCreate();
    ESP_ERROR_CHECK(connection_events != NULL ? ESP_OK : ESP_ERR_NO_MEM);

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    derive_node_id();
    init_topics();
    ESP_LOGI(TAG, "node_id=%s packet_topic=%s", node_id, topic_packet);

    ESP_ERROR_CHECK(init_led());
    ESP_ERROR_CHECK(init_spi());
    ESP_ERROR_CHECK(init_wifi());
    ESP_ERROR_CHECK(xTaskCreate(spi_uplink_task, "spi_uplink", 16384, NULL, 5, NULL) == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(xTaskCreate(health_task, "health", 4096, NULL, 4, NULL) == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
}
