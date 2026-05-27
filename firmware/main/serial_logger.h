#pragma once

#include <stdbool.h>

#include "cmd_sniffer.h"

void serial_logger_init(void);
void serial_logger_start_metadata_heartbeat(void);
void serial_logger_print_startup_info(void);
bool serial_logger_handle_packet(const sniffer_packet_info_t *packet);
