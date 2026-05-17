#include "sdkconfig.h"

#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "cmd_sniffer.h"
#include "config.h"
#include "serial_logger.h"

#ifndef PROJECT_VER
#define PROJECT_VER "unknown"
#endif

#if CONFIG_CITS_ENABLE_USB_SERIAL_LOG

static inline uint16_t read_le16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static inline uint16_t read_be16(const uint8_t *p)
{
    return ((uint16_t)p[0] << 8) | (uint16_t)p[1];
}

static bool is_geonetworking_80211_frame(const uint8_t *frame, uint32_t length)
{
    if (length < 24U + 8U) {
        return false;
    }

    const uint16_t fc = read_le16(frame);
    const uint8_t type = (fc >> 2) & 0x03;
    const uint8_t subtype = (fc >> 4) & 0x0f;

    if (type != 2) {
        return false;
    }

    const bool to_ds = (fc & (1U << 8)) != 0;
    const bool from_ds = (fc & (1U << 9)) != 0;
    const bool order = (fc & (1U << 15)) != 0;
    const bool qos = (subtype & 0x08) != 0;

    uint32_t hdr_len = 24;
    if (to_ds && from_ds) {
        hdr_len += 6;
    }
    if (qos) {
        hdr_len += 2;
    }
    if (order) {
        hdr_len += 4;
    }

    if (length < hdr_len + 8U) {
        return false;
    }

    const uint8_t *llc = frame + hdr_len;
    if (llc[0] != 0xaa || llc[1] != 0xaa || llc[2] != 0x03) {
        return false;
    }
    if (llc[3] != 0x00 || llc[4] != 0x00 || llc[5] != 0x00) {
        return false;
    }

    return read_be16(&llc[6]) == 0x8947;
}

static uint32_t packet_caplen(const sniffer_packet_info_t *packet, bool *truncated)
{
    uint32_t caplen = packet->length;
    *truncated = false;

    if (caplen > CONFIG_CITS_USB_SERIAL_LOG_MAX_LEN) {
        caplen = CONFIG_CITS_USB_SERIAL_LOG_MAX_LEN;
        *truncated = true;
    }
    if (caplen > UINT16_MAX) {
        caplen = UINT16_MAX;
        *truncated = true;
    }

    return caplen;
}

static bool serial_logger_write_ascii_packet(const sniffer_packet_info_t *packet)
{
    bool truncated;
    const uint32_t caplen = packet_caplen(packet, &truncated);
    const uint8_t *payload = (const uint8_t *)packet->payload;

    printf("CITS,%" PRIu32 ",%06" PRIu32 ",%" PRIu32 ",%d,%" PRIu32 ",%u,",
           packet->seconds,
           packet->microseconds,
           packet->channel_mhz,
           packet->rssi,
           caplen,
           truncated ? 1 : 0);

    for (uint32_t i = 0; i < caplen; ++i) {
        printf("%02x", payload[i]);
    }
    printf("\n");
    fflush(stdout);
    return true;
}

void serial_logger_print_startup_info(void)
{
    char nodeid[CONFIG_NODEID_BUFFER_SIZE];
    size_t nodeid_size = sizeof(nodeid);
    if (config_get_str(CONFIG_INDEX_NODEID, nodeid, &nodeid_size) != ESP_OK) {
        snprintf(nodeid, sizeof(nodeid), "unknown");
    }

    printf("CITSMETA,%s,its/%s/packet,%s,%s\n",
           nodeid,
           nodeid,
           CONFIG_HW_VARIANT,
           PROJECT_VER);
    printf("CITSPROTO,ascii-v1\n");
    fflush(stdout);
}

bool serial_logger_handle_packet(const sniffer_packet_info_t *packet)
{
    if (!packet || !packet->payload || packet->length == 0) {
        return false;
    }

#if CONFIG_CITS_USB_SERIAL_LOG_GEONET_ONLY
    if (packet->interface != SNIFFER_INTF_WLAN ||
        !is_geonetworking_80211_frame((const uint8_t *)packet->payload, packet->length)) {
        return false;
    }
#endif

    return serial_logger_write_ascii_packet(packet);
}

#else

bool serial_logger_handle_packet(const sniffer_packet_info_t *packet)
{
    (void)packet;
    return false;
}

void serial_logger_print_startup_info(void)
{
}

#endif
