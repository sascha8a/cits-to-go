#pragma once

#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    SNIFFER_INTF_UNKNOWN = 0,
    SNIFFER_INTF_WLAN,
} sniffer_intf_t;

typedef struct {
    void *payload;
    uint32_t length;
    uint32_t seconds;
    uint32_t microseconds;
    sniffer_intf_t interface;
    uint32_t channel_mhz;
    int8_t rssi;
} sniffer_packet_info_t;

void sniffer_init(void);
void register_sniffer_cmd(void);
void sniffer_autostart(void);

#ifdef __cplusplus
}
#endif
