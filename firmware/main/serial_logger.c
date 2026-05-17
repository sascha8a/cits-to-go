#include "sdkconfig.h"

#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "cmd_sniffer.h"
#include "config.h"
#include "serial_logger.h"

#ifndef PROJECT_VER
#define PROJECT_VER "unknown"
#endif

#if CONFIG_CITS_ENABLE_USB_SERIAL_LOG

#if CONFIG_CITS_USB_SERIAL_BINARY_FRAMING && (defined(CONFIG_LIBC_STDOUT_LINE_ENDING_CRLF) || defined(CONFIG_NEWLIB_STDOUT_LINE_ENDING_CRLF) || defined(CONFIG_LIBC_STDOUT_LINE_ENDING_CR) || defined(CONFIG_NEWLIB_STDOUT_LINE_ENDING_CR))
#error "Binary CITS serial framing requires LF-only stdout line endings; CR/CRLF corrupt raw packet bytes"
#endif


#define CITS_BINARY_VERSION       1U
#define CITS_BINARY_HEADER_LEN    28U
#define CITS_BINARY_FLAG_TRUNC    0x01U
#define CITS_BINARY_FLUSH_PACKETS 16U

static uint32_t packets_since_flush;

static inline uint16_t read_le16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static inline uint16_t read_be16(const uint8_t *p)
{
    return ((uint16_t)p[0] << 8) | (uint16_t)p[1];
}

static inline void write_le16(uint8_t *p, uint16_t v)
{
    p[0] = (uint8_t)(v & 0xffU);
    p[1] = (uint8_t)((v >> 8) & 0xffU);
}

static inline void write_le32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xffU);
    p[1] = (uint8_t)((v >> 8) & 0xffU);
    p[2] = (uint8_t)((v >> 16) & 0xffU);
    p[3] = (uint8_t)((v >> 24) & 0xffU);
}

static uint32_t crc32_ieee(const uint8_t *data, uint32_t len)
{
    uint32_t crc = 0xffffffffU;
    for (uint32_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (uint8_t bit = 0; bit < 8; ++bit) {
            const uint32_t mask = 0U - (crc & 1U);
            crc = (crc >> 1) ^ (0xedb88320U & mask);
        }
    }
    return ~crc;
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

static void maybe_flush_stdout(void)
{
    packets_since_flush++;
    if (packets_since_flush >= CITS_BINARY_FLUSH_PACKETS) {
        fflush(stdout);
        packets_since_flush = 0;
    }
}

#if CONFIG_CITS_USB_SERIAL_BINARY_FRAMING
static bool serial_logger_write_binary_packet(const sniffer_packet_info_t *packet)
{
    bool truncated;
    const uint32_t caplen32 = packet_caplen(packet, &truncated);
    const uint16_t caplen = (uint16_t)caplen32;
    const uint16_t orig_len = packet->length > UINT16_MAX ? UINT16_MAX : (uint16_t)packet->length;
    const uint8_t *payload = (const uint8_t *)packet->payload;
    const uint32_t total_len = CITS_BINARY_HEADER_LEN + caplen;

    uint8_t *frame = (uint8_t *)malloc(total_len);
    if (!frame) {
        return false;
    }

    frame[0] = 'C';
    frame[1] = 'I';
    frame[2] = 'T';
    frame[3] = 'S';
    frame[4] = CITS_BINARY_VERSION;
    frame[5] = truncated ? CITS_BINARY_FLAG_TRUNC : 0;
    write_le16(&frame[6], CITS_BINARY_HEADER_LEN);
    write_le32(&frame[8], packet->seconds);
    write_le32(&frame[12], packet->microseconds);
    write_le16(&frame[16], packet->channel_mhz > UINT16_MAX ? UINT16_MAX : (uint16_t)packet->channel_mhz);
    frame[18] = (uint8_t)((int8_t)packet->rssi);
    frame[19] = 0;
    write_le16(&frame[20], caplen);
    write_le16(&frame[22], orig_len);
#if CONFIG_CITS_USB_SERIAL_BINARY_CRC32
    write_le32(&frame[24], crc32_ieee(payload, caplen));
#else
    write_le32(&frame[24], 0);
#endif
    memcpy(&frame[CITS_BINARY_HEADER_LEN], payload, caplen);

    const size_t written = fwrite(frame, 1, total_len, stdout);
    free(frame);

    if (written == total_len) {
        maybe_flush_stdout();
        return true;
    }
    return false;
}
#endif

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
#if CONFIG_CITS_USB_SERIAL_BINARY_FRAMING
    printf("CITSPROTO,binary-v1,header=%u,crc32=%u,stdout_line_endings=lf-required\n",
           CITS_BINARY_HEADER_LEN,
#if CONFIG_CITS_USB_SERIAL_BINARY_CRC32
           1
#else
           0
#endif
    );
#else
    printf("CITSPROTO,ascii-v1\n");
#endif
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

#if CONFIG_CITS_USB_SERIAL_BINARY_FRAMING
    return serial_logger_write_binary_packet(packet);
#else
    return serial_logger_write_ascii_packet(packet);
#endif
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
