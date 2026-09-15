#include "cits_ble.h"
#include "sdkconfig.h"

#include <stdbool.h>
#include <string.h>
#include <stdatomic.h>

#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "host/ble_att.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#define CITS_BLE_DEVICE_NAME "CITS-to-go"
#define CITS_BLE_INVALID_CONN_HANDLE 0xffffu
#define CITS_BLE_MAX_BONDS 2
#define CITS_BLE_ENROLLMENT_WINDOW_US (30LL * 1000LL * 1000LL)
#define CITS_BLE_FRAME_HEADER_LEN 32u
#define CITS_BLE_FRAME_TRAILER_LEN 4u
#define CITS_BLE_COBS_OVERHEAD(len) (((len) / 254u) + 1u)
#define CITS_BLE_MAX_DECODED_LEN \
    (CITS_BLE_FRAME_HEADER_LEN + CONFIG_CITS_MAX_PACKET_BYTES + CITS_BLE_FRAME_TRAILER_LEN)
#define CITS_BLE_MAX_WRITE_LEN \
    (CITS_BLE_MAX_DECODED_LEN + CITS_BLE_COBS_OVERHEAD(CITS_BLE_MAX_DECODED_LEN) + 1u)
#define CITS_BLE_TX_QUEUE_DEPTH CONFIG_CITS_BLE_OUTPUT_SLOTS
#define CITS_BLE_CONTROL_RESERVE 2u
#define CITS_BLE_NOTIFY_MAX 512u

typedef struct {
    uint16_t conn_handle;
    uint32_t epoch;
    uint16_t len;
    bool control;
    uint8_t data[CITS_BLE_MAX_WRITE_LEN + 1];
} ble_tx_slot_t;

/* ESP-IDF's NimBLE examples expose the persistent-store initializer this way. */
void ble_store_config_init(void);

/* UUID strings on Android:
 * service:  6e400001-b5a3-f393-e0a9-e50e24dcca9e
 * RX/write: 6e400002-b5a3-f393-e0a9-e50e24dcca9e
 * TX/notify:6e400003-b5a3-f393-e0a9-e50e24dcca9e
 *
 * NimBLE's BLE_UUID128_INIT takes bytes in little-endian wire order.
 */
static const ble_uuid128_t service_uuid = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
    0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e);
static const ble_uuid128_t rx_uuid = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
    0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e);
static const ble_uuid128_t tx_uuid = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
    0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e);

static cits_ble_rx_callback_t rx_callback;
static QueueHandle_t tx_free_queue;
static QueueHandle_t tx_capture_queue;
static QueueHandle_t tx_control_queue;
static TaskHandle_t tx_task_handle;
static ble_tx_slot_t tx_slots[CITS_BLE_TX_QUEUE_DEPTH];
static uint16_t tx_value_handle;
static _Atomic uint16_t conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
static _Atomic bool notify_enabled;
static _Atomic bool link_secured;
static bool enrollment_armed;
static int64_t enrollment_deadline_us;
static uint16_t enrollment_conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
static uint8_t own_addr_type;

static void advertise(void);
static void enroll_on_host(struct ble_npl_event *event);
static struct ble_npl_event enroll_event;
static _Atomic uint32_t connection_epoch;
static _Atomic uint32_t tx_bytes_total;
static _Atomic uint32_t tx_notifications_total;
static _Atomic uint32_t tx_notify_failures_total;
static _Atomic uint32_t tx_capture_packets_total;
static _Atomic uint32_t current_mtu = 23;
static _Atomic uint32_t current_conn_interval;
static _Atomic uint32_t current_conn_latency;
static _Atomic uint32_t current_supervision_timeout;
static _Atomic uint32_t current_tx_phy;
static _Atomic uint32_t current_rx_phy;
void cits_rs_enroll_done(int32_t status);
void cits_platform_ble_disconnected(void);

static void refresh_link_stats(uint16_t handle)
{
    struct ble_gap_conn_desc desc;
    if (handle == CITS_BLE_INVALID_CONN_HANDLE) return;
    if (ble_gap_conn_find(handle, &desc) == 0) {
        atomic_store(&current_conn_interval, desc.conn_itvl);
        atomic_store(&current_conn_latency, desc.conn_latency);
        atomic_store(&current_supervision_timeout, desc.supervision_timeout);
    }
    /* Do not issue HCI LE Read PHY from GAP callbacks just for diagnostics.
     * On the ESP32-C5 this adds control traffic while Wi-Fi and a high-rate
     * notification stream are active and can contribute to supervision
     * timeouts. Android already reports the negotiated PHY. Keep the
     * firmware-side PHY fields at 0 (unknown) until we have a passive source. */
}

static void clear_link_stats(void)
{
    atomic_store(&current_mtu, 23);
    atomic_store(&current_conn_interval, 0);
    atomic_store(&current_conn_latency, 0);
    atomic_store(&current_supervision_timeout, 0);
    atomic_store(&current_tx_phy, 0);
    atomic_store(&current_rx_phy, 0);
}

static void request_fast_connection(uint16_t handle)
{
    const struct ble_gap_upd_params params = {
        .itvl_min = 6,  /* 7.5 ms */
        .itvl_max = 12, /* 15 ms */
        .latency = 0,
        .supervision_timeout = 400,
        .min_ce_len = 0,
        .max_ce_len = 0,
    };
    (void)ble_gap_update_params(handle, &params);
}

static bool peer_is_bonded(const ble_addr_t *peer_id_addr)
{
    ble_addr_t peers[CITS_BLE_MAX_BONDS];
    int num_peers = 0;
    if (ble_store_util_bonded_peers(peers, &num_peers, CITS_BLE_MAX_BONDS) != 0) return false;
    for (int i = 0; i < num_peers; ++i) {
        if (ble_addr_cmp(&peers[i], peer_id_addr) == 0) return true;
    }
    return false;
}

static void delete_other_bonds(const ble_addr_t *retained_peer)
{
    ble_addr_t peers[CITS_BLE_MAX_BONDS];
    int num_peers = 0;
    if (ble_store_util_bonded_peers(peers, &num_peers, CITS_BLE_MAX_BONDS) != 0) return;
    for (int i = 0; i < num_peers; ++i) {
        if (ble_addr_cmp(&peers[i], retained_peer) != 0) {
            (void)ble_store_util_delete_peer(&peers[i]);
        }
    }
}

static int gatt_access(uint16_t connection_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)connection_handle;
    (void)attr_handle;
    (void)arg;

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR ||
        ble_uuid_cmp(ctxt->chr->uuid, &rx_uuid.u) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    const uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len == 0) return 0;

    uint8_t buffer[512];
    if (len > sizeof(buffer)) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;

    uint16_t copied = 0;
    if (ble_hs_mbuf_to_flat(ctxt->om, buffer, sizeof(buffer), &copied) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (!link_secured || connection_handle != conn_handle) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    if (rx_callback != NULL && copied > 0 && !rx_callback(buffer, copied))
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    return 0;
}

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &rx_uuid.u,
                .access_cb = gatt_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP |
                         BLE_GATT_CHR_F_WRITE_ENC,
            },
            {
                .uuid = &tx_uuid.u,
                .access_cb = gatt_access,
                .val_handle = &tx_value_handle,
                .flags = BLE_GATT_CHR_F_NOTIFY,
            },
            { 0 },
        },
    },
    { 0 },
};

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    struct ble_gap_conn_desc desc;
    int rc;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status != 0) {
            advertise();
            return 0;
        }

        conn_handle = event->connect.conn_handle;
        notify_enabled = false;
        link_secured = false;
        rc = ble_gap_conn_find(event->connect.conn_handle, &desc);
        if (rc != 0) {
            (void)ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
            return 0;
        }
        refresh_link_stats(event->connect.conn_handle);

        if (peer_is_bonded(&desc.peer_id_addr)) {
            /* Re-enrolling the existing owner is harmless and consumes the
             * temporary enrollment window without replacing its bond. */
            if (enrollment_armed && esp_timer_get_time() <= enrollment_deadline_us) {
                enrollment_armed = false;
                enrollment_deadline_us = 0;
                enrollment_conn_handle = event->connect.conn_handle;
                /* Android may have lost its side of this bond and be starting
                 * fresh pairing. Let it drive SMP; the encrypted GATT probe
                 * sent after enrollment verifies a still-valid bond. */
                return 0;
            }
            rc = ble_gap_security_initiate(event->connect.conn_handle);
            if (rc != 0) {
                (void)ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
            }
            return 0;
        }

        if (enrollment_armed && esp_timer_get_time() <= enrollment_deadline_us) {
            /* Enrollment is deliberately one-shot: the first unknown peer owns
             * this attempt. Android createBond() initiated this connection and
             * must remain the sole SMP initiator; initiating security here too
             * leaves Android waiting for pairing UI confirmation. */
            enrollment_armed = false;
            enrollment_deadline_us = 0;
            enrollment_conn_handle = event->connect.conn_handle;
            return 0;
        }

        enrollment_armed = false;
        enrollment_deadline_us = 0;
        /* Unknown peers are never allowed to start pairing during normal use. */
        (void)ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        return 0;

    case BLE_GAP_EVENT_ENC_CHANGE:
        if (event->enc_change.status != 0) {
            link_secured = false;
            enrollment_conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
            (void)ble_gap_terminate(event->enc_change.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
            return 0;
        }
        rc = ble_gap_conn_find(event->enc_change.conn_handle, &desc);
        if (rc != 0 || !desc.sec_state.encrypted || !desc.sec_state.bonded) {
            link_secured = false;
            (void)ble_gap_terminate(event->enc_change.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
            return 0;
        }
        link_secured = true;
        refresh_link_stats(event->enc_change.conn_handle);
        request_fast_connection(event->enc_change.conn_handle);
        if (enrollment_conn_handle == event->enc_change.conn_handle) {
            /* NimBLE can retain two bonds temporarily. Commit replacement only
             * after pairing succeeded, preserving the old owner on failure. */
            delete_other_bonds(&desc.peer_id_addr);
        }
        enrollment_conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
        return 0;

    case BLE_GAP_EVENT_CONN_UPDATE:
        if (event->conn_update.status == 0) refresh_link_stats(event->conn_update.conn_handle);
        return 0;

    case BLE_GAP_EVENT_MTU:
        if (event->mtu.conn_handle == conn_handle) atomic_store(&current_mtu, event->mtu.value);
        return 0;

    case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE:
        /* No active PHY query here; see refresh_link_stats(). */
        return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
        if (enrollment_conn_handle == event->repeat_pairing.conn_handle &&
            ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc) == 0) {
            /* Android can retain keys after firmware was reflashed or erased.
             * During this USB-authorized attempt only, remove the conflicting
             * firmware key and let NimBLE complete a fresh bond. */
            if (ble_store_util_delete_peer(&desc.peer_id_addr) == 0) {
                return BLE_GAP_REPEAT_PAIRING_RETRY;
            }
        }
        /* Never silently replace a stored identity during normal operation. */
        return BLE_GAP_REPEAT_PAIRING_IGNORE;

    case BLE_GAP_EVENT_DISCONNECT:
        atomic_fetch_add(&connection_epoch, 1);
        cits_platform_ble_disconnected();
        conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
        notify_enabled = false;
        link_secured = false;
        enrollment_conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
        clear_link_stats();
        if (tx_task_handle != NULL) xTaskNotifyGive(tx_task_handle);
        advertise();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        if (event->subscribe.attr_handle == tx_value_handle) {
            notify_enabled = event->subscribe.cur_notify != 0;
        }
        return 0;

    case BLE_GAP_EVENT_NOTIFY_TX:
        if (tx_task_handle != NULL) xTaskNotifyGive(tx_task_handle);
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;

    default:
        return 0;
    }
}

static void advertise(void)
{
    struct ble_hs_adv_fields adv_fields = {0};
    adv_fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    adv_fields.uuids128 = (ble_uuid128_t *)&service_uuid;
    adv_fields.num_uuids128 = 1;
    adv_fields.uuids128_is_complete = 1;
    if (ble_gap_adv_set_fields(&adv_fields) != 0) return;

    struct ble_hs_adv_fields rsp_fields = {0};
    const char *name = ble_svc_gap_device_name();
    rsp_fields.name = (uint8_t *)name;
    rsp_fields.name_len = strlen(name);
    rsp_fields.name_is_complete = 1;
    if (ble_gap_adv_rsp_set_fields(&rsp_fields) != 0) return;

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    (void)ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
}

static void on_sync(void)
{
    if (ble_hs_util_ensure_addr(0) != 0) return;
    if (ble_hs_id_infer_auto(0, &own_addr_type) != 0) return;
    advertise();
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static bool take_next_tx_slot(uint8_t *slot_index)
{
    if (xQueueReceive(tx_control_queue, slot_index, 0) == pdTRUE) return true;
    return xQueueReceive(tx_capture_queue, slot_index, 0) == pdTRUE;
}

static void release_tx_slot(uint8_t slot_index)
{
    (void)xQueueSend(tx_free_queue, &slot_index, portMAX_DELAY);
}

static bool slot_matches_link(const ble_tx_slot_t *slot)
{
    return conn_handle == slot->conn_handle && notify_enabled && link_secured &&
           slot->epoch == atomic_load(&connection_epoch);
}

static void tx_task(void *param)
{
    (void)param;
    uint8_t slot_index = 0;
    size_t slot_offset = 0;
    bool have_slot = false;
    uint8_t notify[CITS_BLE_NOTIFY_MAX];

    for (;;) {
        if (!have_slot) {
            if (!take_next_tx_slot(&slot_index)) {
                (void)ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
                continue;
            }
            slot_offset = 0;
            have_slot = true;
        }

        ble_tx_slot_t *slot = &tx_slots[slot_index];
        if (!slot_matches_link(slot)) {
            release_tx_slot(slot_index);
            have_slot = false;
            continue;
        }

        const uint16_t mtu = ble_att_mtu(slot->conn_handle);
        size_t chunk_max = mtu > 3 ? (size_t)(mtu - 3) : 20u;
        if (chunk_max > sizeof(notify)) chunk_max = sizeof(notify);

        size_t notify_len = 0;
        uint32_t completed_capture_records = 0;
        uint16_t notify_handle = slot->conn_handle;
        uint32_t notify_epoch = slot->epoch;

        /* CTG1 is a byte stream, so one ATT notification may contain several
         * complete frames (and may split a large frame). Filling the MTU this
         * way substantially reduces GATT/Android callback overhead for the
         * common case of small CAM/SPATEM packets. */
        while (notify_len < chunk_max && have_slot) {
            slot = &tx_slots[slot_index];
            if (slot->conn_handle != notify_handle || slot->epoch != notify_epoch ||
                !slot_matches_link(slot)) {
                release_tx_slot(slot_index);
                have_slot = false;
                break;
            }

            const size_t remaining = slot->len - slot_offset;
            const size_t available = chunk_max - notify_len;
            const size_t copy_len = remaining < available ? remaining : available;
            memcpy(notify + notify_len, slot->data + slot_offset, copy_len);
            notify_len += copy_len;
            slot_offset += copy_len;

            if (slot_offset == slot->len) {
                if (!slot->control) completed_capture_records++;
                release_tx_slot(slot_index);
                have_slot = false;
                slot_offset = 0;
                if (notify_len < chunk_max && take_next_tx_slot(&slot_index)) {
                    have_slot = true;
                }
            }
        }

        if (notify_len == 0) continue;

        bool sent = false;
        const int64_t retry_started = esp_timer_get_time();
        for (;;) {
            if (conn_handle != notify_handle || !notify_enabled || !link_secured ||
                notify_epoch != atomic_load(&connection_epoch)) break;

            struct os_mbuf *om = ble_hs_mbuf_from_flat(notify, notify_len);
            const int rc = om == NULL ? BLE_HS_ENOMEM :
                ble_gatts_notify_custom(notify_handle, tx_value_handle, om);
            if (rc == 0) {
                atomic_fetch_add(&tx_notifications_total, 1);
                atomic_fetch_add(&tx_bytes_total, (uint32_t)notify_len);
                if (completed_capture_records != 0) {
                    atomic_fetch_add(&tx_capture_packets_total, completed_capture_records);
                }
                sent = true;
                break;
            }
            if (rc != BLE_HS_ENOMEM) {
                (void)ble_gap_terminate(notify_handle, BLE_ERR_REM_USER_CONN_TERM);
                break;
            }

            /* A completion notification normally wakes us immediately when
             * NimBLE frees mbufs. New output also wakes this task, so retrying
             * is event-driven rather than polling. */
            if (esp_timer_get_time() - retry_started >= 1000000) {
                (void)ble_gap_terminate(notify_handle, BLE_ERR_REM_USER_CONN_TERM);
                break;
            }
            (void)ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(250));
        }

        if (!sent) atomic_fetch_add(&tx_notify_failures_total, 1);

        /* If a failed notification contained the first bytes of a large frame,
         * discard that remainder. Continuing it would create a syntactically
         * valid but corrupt stream if the link stays up long enough to dequeue
         * more data before termination completes. */
        if (!sent && have_slot && slot_offset != 0) {
            release_tx_slot(slot_index);
            have_slot = false;
            slot_offset = 0;
        }
    }
}

esp_err_t cits_ble_init(cits_ble_rx_callback_t callback)
{
    rx_callback = callback;
    tx_free_queue = xQueueCreate(CITS_BLE_TX_QUEUE_DEPTH, sizeof(uint8_t));
    tx_capture_queue = xQueueCreate(CITS_BLE_TX_QUEUE_DEPTH, sizeof(uint8_t));
    tx_control_queue = xQueueCreate(CITS_BLE_TX_QUEUE_DEPTH, sizeof(uint8_t));
    if (tx_free_queue == NULL || tx_capture_queue == NULL || tx_control_queue == NULL) {
        return ESP_ERR_NO_MEM;
    }
    for (uint8_t i = 0; i < CITS_BLE_TX_QUEUE_DEPTH; ++i) {
        if (xQueueSend(tx_free_queue, &i, 0) != pdTRUE) return ESP_FAIL;
    }

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) return err;
    ble_npl_event_init(&enroll_event, enroll_on_host, NULL);

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0; /* USB-controlled enrollment provides physical authorization. */
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_svc_gap_device_name_set(CITS_BLE_DEVICE_NAME);
    if (rc != 0) return ESP_FAIL;
    rc = ble_gatts_count_cfg(services);
    if (rc != 0) return ESP_FAIL;
    rc = ble_gatts_add_svcs(services);
    if (rc != 0) return ESP_FAIL;

    ble_store_config_init();
    if (xTaskCreate(tx_task, "cits_ble_tx", 4096, NULL, 3, &tx_task_handle) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    nimble_port_freertos_init(host_task);
    return ESP_OK;
}

void cits_ble_begin_enrollment(void)
{
    // Enrollment and all bond-store operations run on the NimBLE host task.
    ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &enroll_event);
}

static void enroll_on_host(struct ble_npl_event *event)
{
    (void)event;
    if (conn_handle != CITS_BLE_INVALID_CONN_HANDLE) {
        (void)ble_gap_terminate(conn_handle, BLE_ERR_REM_USER_CONN_TERM);
    }
    link_secured = false;
    notify_enabled = false;
    enrollment_conn_handle = CITS_BLE_INVALID_CONN_HANDLE;
    enrollment_deadline_us = esp_timer_get_time() + CITS_BLE_ENROLLMENT_WINDOW_US;
    enrollment_armed = true;
    cits_rs_enroll_done(ESP_OK);
}

bool cits_ble_write(const uint8_t *data, size_t len, bool control)
{
    const uint32_t epoch = atomic_load(&connection_epoch);
    const uint16_t handle = conn_handle;
    if (data == NULL || len == 0 || len > CITS_BLE_MAX_WRITE_LEN ||
        handle == CITS_BLE_INVALID_CONN_HANDLE || !notify_enabled || !link_secured) return true;

    uint8_t slot_index;
    if ((!control && uxQueueMessagesWaiting(tx_free_queue) <= CITS_BLE_CONTROL_RESERVE) ||
        xQueueReceive(tx_free_queue, &slot_index, 0) != pdTRUE) return false;

    ble_tx_slot_t *slot = &tx_slots[slot_index];
    slot->conn_handle = handle;
    slot->epoch = epoch;
    slot->control = control;
    // An empty record before every frame also repairs a subscribe-off/on
    // transition that abandoned a partly emitted record on this connection.
    slot->len = (uint16_t)(len + 1);
    slot->data[0] = 0;
    memcpy(slot->data + 1, data, len);
    QueueHandle_t queue = control ? tx_control_queue : tx_capture_queue;
    if (xQueueSend(queue, &slot_index, 0) != pdTRUE) {
        (void)xQueueSend(tx_free_queue, &slot_index, 0);
        return false;
    }
    if (tx_task_handle != NULL) xTaskNotifyGive(tx_task_handle);
    return true;
}

void cits_ble_get_stats(cits_ble_stats_t *out)
{
    if (out == NULL) return;
    memset(out, 0, sizeof(*out));
    out->bytes_total = atomic_load(&tx_bytes_total);
    out->notifications_total = atomic_load(&tx_notifications_total);
    out->notify_failures_total = atomic_load(&tx_notify_failures_total);
    out->capture_packets_total = atomic_load(&tx_capture_packets_total);
    out->queue_capacity = CITS_BLE_TX_QUEUE_DEPTH;
    if (tx_free_queue != NULL) {
        const UBaseType_t free_slots = uxQueueMessagesWaiting(tx_free_queue);
        out->queue_depth = free_slots <= CITS_BLE_TX_QUEUE_DEPTH
            ? CITS_BLE_TX_QUEUE_DEPTH - (uint32_t)free_slots : 0;
    }
    const uint16_t handle = conn_handle;
    if (handle != CITS_BLE_INVALID_CONN_HANDLE) out->flags |= CITS_BLE_STATS_CONNECTED;
    if (notify_enabled) out->flags |= CITS_BLE_STATS_NOTIFY_ENABLED;
    if (link_secured) out->flags |= CITS_BLE_STATS_SECURED;
    out->mtu = atomic_load(&current_mtu);
    out->conn_interval = atomic_load(&current_conn_interval);
    out->conn_latency = atomic_load(&current_conn_latency);
    out->supervision_timeout = atomic_load(&current_supervision_timeout);
    out->tx_phy = atomic_load(&current_tx_phy);
    out->rx_phy = atomic_load(&current_rx_phy);
}
