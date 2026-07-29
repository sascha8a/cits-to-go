#include "tx_custom.h"

#include <stddef.h>

#include "esp_private/wifi_os_adapter.h"

typedef struct {
    uint32_t flags;
    uint32_t field_4;
    uint32_t field_8;
    uint8_t rate;
    uint8_t field_d;
    uint8_t field_e;
    uint8_t field_f;
    uint32_t field_10;
    uint32_t field_14;
    uint32_t timestamp;
    void *sched;
    uint32_t field_20;
    uint32_t field_24;
    uint32_t field_28;
    union {
        uint32_t field_2c_32;
        struct {
            uint8_t field_2c;
            uint8_t field_2d;
            uint8_t field_2e;
            uint8_t field_2f;
        };
    };
    union {
        uint32_t field_30_32;
        struct {
            uint8_t field_30;
            uint8_t field_31;
            uint8_t field_32;
            uint8_t field_33;
        };
    };
    uint32_t field_34;
    uint32_t field_38;
    uint32_t field_3c;
    uint32_t field_40;
    uint32_t field_44;
} cits_eb_txdesc_t;

typedef struct {
    uint32_t field_40;
    uint8_t *buf;
    uint32_t field_48;
    uint32_t field_4c;
} cits_middle_data_t;

typedef struct {
    uint32_t field_0;
    cits_middle_data_t *ds_head;
    cits_middle_data_t *ds_tail;
    uint16_t field_c;
    uint16_t field_e;
    uint32_t extra_data_start;
    uint16_t header_length;
    uint32_t data_length;
    uint16_t field_1c;
    uint8_t alloc_type;
    uint8_t field_1f;
    uint32_t field_20;
    uint8_t field_24;
    uint8_t field_25;
    uint8_t field_26;
    uint8_t field_27;
    uint32_t field_28;
    uint8_t field_2c;
    uint8_t padding_2d[3];
    uint32_t field_30;
    uint32_t next_free;
    cits_eb_txdesc_t *txdesc;
    uint16_t field_3c;
    uint8_t field_3e;
    uint8_t field_3f;
} cits_ebuf_t;

_Static_assert(sizeof(cits_eb_txdesc_t) == 0x48, "ESP-IDF txdesc layout changed");
_Static_assert(sizeof(cits_middle_data_t) == 0x10, "ESP-IDF middle-data layout changed");
_Static_assert(sizeof(cits_ebuf_t) == 0x40, "ESP-IDF ebuf layout changed");

extern wifi_osi_funcs_t *g_osi_funcs_p;
extern void *g_wifi_global_lock;
extern void *ic_ebuf_alloc(const void *buffer, uint32_t type, uint32_t len);
extern void *ic_get_default_sched(void);
extern esp_err_t ieee80211_post_hmac_tx(void *eb);

esp_err_t cits_wifi_80211_tx(
    wifi_interface_t ifx,
    const void *buffer,
    int len,
    bool en_sys_seq,
    const cits_wifi_tx_rate_config_t *tx_rate_config,
    wifi_band_t band,
    wifi_bandwidth_t bandwidth
) {
    if (buffer == NULL || len <= 0 || tx_rate_config == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t result = ESP_OK;
    g_osi_funcs_p->_mutex_lock(g_wifi_global_lock);
    cits_ebuf_t *eb = (cits_ebuf_t *)ic_ebuf_alloc(buffer, 1, (uint32_t)len);
    if (eb == NULL) {
        result = ESP_ERR_NO_MEM;
    } else {
        cits_eb_txdesc_t *txdesc = eb->txdesc;
        eb->data_length = 0;
        eb->header_length = (uint16_t)len;
        txdesc->flags |= 0x4000;
        txdesc->sched = ic_get_default_sched();
        wifi_phy_rate_t rate = tx_rate_config->rate;
        if (rate) {
            txdesc->rate = (uint8_t)rate;
        } else if (band != WIFI_BAND_5G) {
            txdesc->rate = 0;
        } else {
            txdesc->rate = (uint8_t)WIFI_PHY_RATE_6M;
        }

        if (tx_rate_config->phymode == WIFI_PHY_MODE_HE20) {
            txdesc->flags |= 0x80000000;
            txdesc->field_2f =
                (uint8_t)((((uint32_t)tx_rate_config->ersu + 6) & 0xf) << 3) |
                (txdesc->field_2f & 0x87);
            if (tx_rate_config->dcm) txdesc->field_31 |= 0x80;
        } else if (tx_rate_config->phymode == WIFI_PHY_MODE_VHT20) {
            txdesc->flags |= 0x1000000;
        }
        const uint32_t bw_is_bw40 = bandwidth == WIFI_BW40;
        txdesc->field_8 = (bw_is_bw40 << 0xf) | (txdesc->field_8 & 0xffff7fff);
        if (en_sys_seq) txdesc->flags |= 0x01;
        txdesc->field_10 =
            (txdesc->field_10 & 0xfff3ffff) | (((uint32_t)ifx & WIFI_IF_MAX) << 0x12);
        txdesc->field_14 = 0x100;
        (void)ieee80211_post_hmac_tx(eb);
    }
    g_osi_funcs_p->_mutex_unlock(g_wifi_global_lock);
    return result;
}
