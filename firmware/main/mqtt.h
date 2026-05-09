#pragma once

#include "cmd_sniffer.h"

void mqtt_init(void);
void mqtt_handle_packet(sniffer_packet_info_t *packet);
