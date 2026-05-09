#include "sdkconfig.h"

#include <string.h>

#include "esp_eth.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "ethernet_init.h"
#include "nvs.h"
#include "lwip/netif.h"
#include "lwip/inet.h"

#include "config.h"
#include "cmd_sniffer.h"
#include "events.h"

#include "ethernet.h"

// TODO make configurable?
#define ETH_MANAGEMENT_INTERFACE 0

#define MAX_MTU 1420

static const char TAG[] = "ETHERNET";

static esp_netif_t *mgmt_netif;
static esp_eth_handle_t mgmt_eth;
static bool static_ip;

static void eth_config_dns(esp_netif_t *netif);

/** Event handler for Ethernet events */
static void eth_event_handler(void *arg, esp_event_base_t event_base,
                              int32_t event_id, void *event_data)
{
    uint8_t mac_addr[6] = {0};
    /* we can get the ethernet driver handle from event data */
    esp_eth_handle_t eth_handle = *(esp_eth_handle_t *)event_data;

    switch (event_id) {
    case ETHERNET_EVENT_CONNECTED:
        esp_eth_ioctl(eth_handle, ETH_CMD_G_MAC_ADDR, mac_addr);
        ESP_LOGI(TAG, "Ethernet link up, HW addr %02x:%02x:%02x:%02x:%02x:%02x",
                 mac_addr[0], mac_addr[1], mac_addr[2], mac_addr[3], mac_addr[4], mac_addr[5]);
        if (eth_handle == mgmt_eth)
        {
            esp_err_t post_res = esp_event_post(APP_EVENT_BASE, APP_ETHERNET_MGMT_INTERFACE_CONNECTED, NULL, 0, 0);
            if (post_res != ESP_OK)
            {
                ESP_LOGE(TAG, "esp_event_post failed: %s", esp_err_to_name(post_res));
            }
        }
        break;
    case ETHERNET_EVENT_DISCONNECTED:
        ESP_LOGI(TAG, "Ethernet link down");
        if (eth_handle == mgmt_eth)
        {
            esp_err_t post_res = esp_event_post(APP_EVENT_BASE, APP_ETHERNET_MGMT_INTERFACE_LOST_IP, NULL, 0, 0);
            if (post_res != ESP_OK)
            {
                ESP_LOGE(TAG, "esp_event_post failed: %s", esp_err_to_name(post_res));
            }

            post_res = esp_event_post(APP_EVENT_BASE, APP_ETHERNET_MGMT_INTERFACE_DISCONNECTED, NULL, 0, 0);
            if (post_res != ESP_OK)
            {
                ESP_LOGE(TAG, "esp_event_post failed: %s", esp_err_to_name(post_res));
            }
        }
        break;
    case ETHERNET_EVENT_START:
        ESP_LOGI(TAG, "Ethernet started");
        break;
    case ETHERNET_EVENT_STOP:
        ESP_LOGI(TAG, "Ethernet stopped");
        break;
    default:
        break;
    }
}

static void update_mtu(esp_netif_t *netif)
{
    uint16_t mtu;
    esp_err_t res = esp_netif_get_mtu(netif, &mtu);
    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "esp_netif_get_mtu failed: %s", esp_err_to_name(res));
        return;
    }

    if (mtu > MAX_MTU)
    {
        res = esp_netif_set_mtu(netif, MAX_MTU);
        if (res != ESP_OK)
            ESP_LOGE(TAG, "esp_netif_set_mtu failed: %s", esp_err_to_name(res));
    }
}

/** Event handler for IP events */
static void ip_event_handler(void *arg, esp_event_base_t event_base,
                              int32_t event_id, void *event_data)
{
    ip_event_got_ip_t *event = (ip_event_got_ip_t *) event_data;
    esp_netif_t *netif = event->esp_netif;

    char ifname[NETIF_NAMESIZE] = {0};
    esp_err_t res = esp_netif_get_netif_impl_name(event->esp_netif, ifname);
    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "Failed to get netif name: %s", esp_err_to_name(res));
        strcpy(ifname, "unk");
    }

    switch (event_id) {
    case IP_EVENT_ETH_GOT_IP:
        {
            const esp_netif_ip_info_t *ip_info = &event->ip_info;
            ESP_LOGI(TAG, "Ethernet %s got IP: " IPSTR " netmask: " IPSTR " gw: " IPSTR, ifname, IP2STR(&ip_info->ip), IP2STR(&ip_info->netmask), IP2STR(&ip_info->gw));

            update_mtu(netif);

            // If this is the management netif, post an event to start MQTT etc.
            if (netif == mgmt_netif)
            {
                esp_err_t post_res = esp_event_post(APP_EVENT_BASE, APP_ETHERNET_MGMT_INTERFACE_GOT_IP, NULL, 0, 0);
                if (post_res != ESP_OK)
                    ESP_LOGE(TAG, "esp_event_post failed: %s", esp_err_to_name(post_res));
            }
            break;
        }
    case IP_EVENT_ETH_LOST_IP:
    {
        ESP_LOGI(TAG, "Ethernet %s lost IP", ifname);

        // If this is the management netif, post an event to stop MQTT etc.
        if (netif == mgmt_netif)
        {
            esp_err_t post_res = esp_event_post(APP_EVENT_BASE, APP_ETHERNET_MGMT_INTERFACE_LOST_IP, NULL, 0, 0);
            if (post_res != ESP_OK)
                ESP_LOGE(TAG, "esp_event_post failed: %s", esp_err_to_name(post_res));
        }
        break;
    }
    case IP_EVENT_NETIF_UP:
    {
        ESP_LOGI(TAG, "Ethernet %s up", ifname);

        // If this is the management netif and we have a static IP, configure some things and
        // fire an event to start MQTT etc.
        if (netif == mgmt_netif && static_ip)
        {
            eth_config_dns(netif);
            update_mtu(netif);

            esp_err_t post_res = esp_event_post(APP_EVENT_BASE, APP_ETHERNET_MGMT_INTERFACE_GOT_IP, NULL, 0, 0);
            if (post_res != ESP_OK)
            {
                ESP_LOGE(TAG, "esp_event_post failed: %s", esp_err_to_name(post_res));
            }
        }
        break;
    }

    default:
        break;
    }
}

static void eth_config_dns_server(esp_netif_t *netif, config_index_t config_index, esp_netif_dns_type_t dns_type)
{
    char dns_str[CONFIG_IPV4_BUFFER_SIZE] = {0};
    size_t dns_size = sizeof(dns_str);

    esp_err_t res = config_get_str(config_index, dns_str, &dns_size);
    if (res != ESP_OK && res != ESP_ERR_NVS_NOT_FOUND)
        ESP_LOGE(TAG, "config_get_str failed: %s", esp_err_to_name(res));

    ip4_addr_t dns = {0};
    if (dns_size == 0 || !inet_aton(dns_str, &dns))
    {
        dns = (ip4_addr_t){0};
    }

    esp_netif_dns_info_t dns_info = {0};
    dns_info.ip.u_addr.ip4.addr = dns.addr;
    dns_info.ip.type = ESP_IPADDR_TYPE_V4;

    res = esp_netif_set_dns_info(netif, dns_type, &dns_info);
    if (res != ESP_OK)
        ESP_LOGE(TAG, "esp_netif_set_dns_info failed: %s", esp_err_to_name(res));
}

static void eth_config_dns(esp_netif_t *netif)
{
    eth_config_dns_server(netif, CONFIG_INDEX_ETH_DNS0, ESP_NETIF_DNS_MAIN);
    eth_config_dns_server(netif, CONFIG_INDEX_ETH_DNS1, ESP_NETIF_DNS_BACKUP);
    eth_config_dns_server(netif, CONFIG_INDEX_ETH_DNS2, ESP_NETIF_DNS_FALLBACK);
}

static void set_hostname_from_config(esp_netif_t *netif)
{
    char nodeid[CONFIG_NODEID_BUFFER_SIZE];
    size_t size = sizeof(nodeid);
    esp_err_t res = config_get_str(CONFIG_INDEX_NODEID, nodeid, &size);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "getting nodeid from config failed: %s", esp_err_to_name(res));
        return;
    }

    char hostname[32 + 1] = "its_";
    strncpy(hostname + sizeof("its_") - 1, nodeid, sizeof(hostname) - sizeof("its_"));

    res = esp_netif_set_hostname(netif, hostname);
    if (res != ESP_OK)
        ESP_LOGW(TAG, "esp_netif_set_hostname failed: %s", esp_err_to_name(res));
}

static void configure_management_interface(esp_eth_handle_t *eth_handle, int idx)
{
    ESP_ERROR_CHECK(esp_netif_init());

    esp_netif_inherent_config_t esp_netif_config = ESP_NETIF_INHERENT_DEFAULT_ETH();
    esp_netif_config.route_prio -= idx * 5;
    esp_netif_config.mtu = MAX_MTU;

    char ip_str[CONFIG_IPV4_BUFFER_SIZE] = {0};
    char nm_str[CONFIG_IPV4_BUFFER_SIZE] = {0};
    char gw_str[CONFIG_IPV4_BUFFER_SIZE] = {0};

    size_t ip_size = sizeof(ip_str);
    size_t nm_size = sizeof(nm_str);
    size_t gw_size = sizeof(gw_str);

    ip4_addr_t ip, nm, gw;
    esp_netif_ip_info_t ip_info = {0};

    if (config_get_str(CONFIG_INDEX_ETH_IP, ip_str, &ip_size) == ESP_OK &&
        config_get_str(CONFIG_INDEX_ETH_NETMASK, nm_str, &nm_size) == ESP_OK &&
        config_get_str(CONFIG_INDEX_ETH_GATEWAY, gw_str, &gw_size) == ESP_OK &&
        ip_size && nm_size && gw_size &&
        inet_aton(ip_str, &ip) && inet_aton(nm_str, &nm) && inet_aton(gw_str, &gw))
    {
        ip_info.ip.addr = ip.addr;
        ip_info.netmask.addr = nm.addr;
        ip_info.gw.addr = gw.addr;
        esp_netif_config.ip_info = &ip_info;

        esp_netif_config.flags &= ~ESP_NETIF_DHCP_CLIENT;
        static_ip = true;
    }

    esp_netif_config_t cfg = ESP_NETIF_DEFAULT_ETH();
    cfg.base = &esp_netif_config;
    esp_netif_t *eth_netif = esp_netif_new(&cfg);

    set_hostname_from_config(eth_netif);

    mgmt_netif = eth_netif;
    mgmt_eth = eth_handle;

    ESP_ERROR_CHECK(esp_netif_attach(eth_netif, esp_eth_new_netif_glue(eth_handle)));
    ESP_ERROR_CHECK(esp_eth_start(eth_handle));
}

void initialize_ethernet(void)
{
    // Initialize Ethernet driver
    uint8_t eth_port_cnt = 0;
    esp_eth_handle_t *eth_handles;
    ESP_ERROR_CHECK(ethernet_init_all(&eth_handles, &eth_port_cnt));

    if (eth_port_cnt == 0)
        return;

    // Register user defined event handlers
    ESP_ERROR_CHECK(esp_event_handler_register(ETH_EVENT, ESP_EVENT_ANY_ID, &eth_event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, ESP_EVENT_ANY_ID, &ip_event_handler, NULL));

    for (uint32_t i = 0; i < eth_port_cnt; i++) {
        if (i != ETH_MANAGEMENT_INTERFACE)
        {
            /* start Ethernet driver state machine */
            ESP_ERROR_CHECK(esp_eth_start(eth_handles[i]));
            /* Register Ethernet interface to could be used by sniffer */
            ESP_ERROR_CHECK(sniffer_reg_eth_intf(eth_handles[i]));
        }
        else
        {
            // Start management ethernet interface
            ESP_LOGD(TAG, "Configuring management ethernet interface %d", i);
            configure_management_interface(eth_handles[i], i);
        }
    }
}

eth_speed_t ethernet_get_mgmt_if_link_speed(void)
{
    if (!mgmt_eth)
    {
        ESP_LOGW(TAG, "mgmt_eth is null, cannot get speed");
        return ETH_SPEED_10M;
    }

    eth_speed_t speed;
    esp_err_t result = esp_eth_ioctl(mgmt_eth, ETH_CMD_G_SPEED, &speed);
    if (result != ESP_OK)
    {
        ESP_LOGW(TAG, "esp_eth_ioctl failed: %s", esp_err_to_name(result));
        return ETH_SPEED_10M;
    }

    return speed;
}
