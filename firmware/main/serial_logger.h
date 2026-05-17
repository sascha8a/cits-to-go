#pragma once

#include <stdbool.h>

#include "cmd_sniffer.h"

bool serial_logger_handle_packet(const sniffer_packet_info_t *packet);
void serial_logger_print_startup_info(void);
