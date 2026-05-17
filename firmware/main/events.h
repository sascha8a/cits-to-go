#pragma once

#include "esp_event.h"

#ifdef __cplusplus
extern "C" {
#endif

ESP_EVENT_DECLARE_BASE(SNIFFER_EVENT_BASE);

typedef enum {
    SNIFFER_STARTED = 1,
    SNIFFER_STOPPED,
    SNIFFER_RECEIVED_PACKET,
} sniffer_event_id_t;

#ifdef __cplusplus
}
#endif
