#include "sdkconfig.h"

#include "esp_event.h"

#include "events.h"

ESP_EVENT_DEFINE_BASE(APP_EVENT_BASE);

ESP_EVENT_DEFINE_BASE(SNIFFER_EVENT_BASE);

ESP_EVENT_DEFINE_BASE(MQTT_EVENT_BASE);
