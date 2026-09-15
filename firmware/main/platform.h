#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint32_t uptime_ms;
    uint32_t usb_bytes_total;
    uint32_t ble_bytes_total;
    uint32_t ble_notifications_total;
    uint32_t usb_partial_drops_total;
    uint32_t ble_notify_failures_total;
    uint32_t usb_capture_packets_total;
    uint32_t ble_capture_packets_total;
    uint32_t flags;
    uint32_t usb_queue_depth;
    uint32_t usb_queue_capacity;
    uint32_t ble_queue_depth;
    uint32_t ble_queue_capacity;
    uint32_t ble_mtu;
    uint32_t ble_conn_interval;
    uint32_t ble_conn_latency;
    uint32_t ble_supervision_timeout;
    uint32_t ble_tx_phy;
    uint32_t ble_rx_phy;
} cits_platform_stats_t;

void cits_rs_run(void) __attribute__((noreturn));
void cits_rs_capture(const uint8_t *, size_t, uint64_t, int8_t, uint8_t, uint8_t);
bool cits_rs_input(const uint8_t *, size_t, bool);
void cits_rs_ble_gap(void);
void cits_rs_ble_reset(void);
void cits_rs_tx_done(int32_t);
void cits_rs_statistics_tick(void);
void cits_platform_stats(cits_platform_stats_t *out);
