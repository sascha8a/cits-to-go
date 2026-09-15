#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "esp_wifi.h"

typedef struct {
    wifi_phy_rate_t rate;
    wifi_phy_mode_t phymode;
    bool ersu;
    bool dcm;
} cits_wifi_tx_rate_config_t;

/*
 * ESP-IDF does not expose an 802.11p raw-transmit API for ESP32-C5. This
 * adapter uses the same internal ebuf/HMAC path as the tx-enabled upstream
 * firmware. It is therefore tied to the ESP-IDF revision in this project.
 */
esp_err_t cits_wifi_80211_tx(
    wifi_interface_t ifx,
    const void *buffer,
    int len,
    bool en_sys_seq,
    const cits_wifi_tx_rate_config_t *tx_rate_config,
    wifi_band_t band,
    wifi_bandwidth_t bandwidth
);
