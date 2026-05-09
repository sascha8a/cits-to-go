#pragma once

#include "esp_err.h"
#include "hal/eth_types.h"

void initialize_ethernet(void);

eth_speed_t ethernet_get_mgmt_if_link_speed(void);
