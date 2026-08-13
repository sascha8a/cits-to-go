#pragma once

#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

typedef void (*cits_ble_rx_callback_t)(const uint8_t *data, size_t len);

esp_err_t cits_ble_init(cits_ble_rx_callback_t callback);
void cits_ble_write(const uint8_t *data, size_t len);

/* USB is the trust anchor. Calling this permits exactly one new BLE connection
 * to perform SMP pairing and bonding. The previous owner is removed only when
 * an unknown peer consumes the enrollment attempt.
 * The window is consumed as soon as the first unknown peer connects, whether
 * pairing succeeds or fails. */
esp_err_t cits_ble_begin_enrollment(void);
