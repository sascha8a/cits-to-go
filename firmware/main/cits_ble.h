#pragma once

#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

typedef bool (*cits_ble_rx_callback_t)(const uint8_t *data, size_t len);

typedef struct {
    uint32_t bytes_total;
    uint32_t notifications_total;
    uint32_t notify_failures_total;
    uint32_t capture_packets_total;
    uint32_t flags;
    uint32_t queue_depth;
    uint32_t queue_capacity;
    uint32_t mtu;
    uint32_t conn_interval;
    uint32_t conn_latency;
    uint32_t supervision_timeout;
    uint32_t tx_phy;
    uint32_t rx_phy;
} cits_ble_stats_t;

enum {
    CITS_BLE_STATS_CONNECTED = 1u << 0,
    CITS_BLE_STATS_NOTIFY_ENABLED = 1u << 1,
    CITS_BLE_STATS_SECURED = 1u << 2,
};

esp_err_t cits_ble_init(cits_ble_rx_callback_t callback);
bool cits_ble_write(const uint8_t *data, size_t len, bool control);
void cits_ble_get_stats(cits_ble_stats_t *out);

/* USB is the trust anchor. Calling this permits exactly one new BLE connection
 * to perform SMP pairing and bonding. The previous owner is removed only when
 * a replacement peer completes secure pairing.
 * The window is consumed as soon as the first unknown peer connects, whether
 * pairing succeeds or fails. */
void cits_ble_begin_enrollment(void);
