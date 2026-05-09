#include "sdkconfig.h"

#include "esp_log.h"
#include "driver/spi_common.h"

#include "spi.h"

#ifdef CONFIG_ETHERNET_SPI_SUPPORT
#define PIN_NUM_MOSI CONFIG_ETHERNET_SPI_MOSI_GPIO
#define PIN_NUM_MISO CONFIG_ETHERNET_SPI_MISO_GPIO
#define PIN_NUM_CLK CONFIG_ETHERNET_SPI_SCLK_GPIO
#elifdef CONFIG_SNIFFER_SD_SPI_MODE
#define PIN_NUM_MISO CONFIG_SNIFFER_PIN_MISO
#define PIN_NUM_MOSI CONFIG_SNIFFER_PIN_MOSI
#define PIN_NUM_CLK  CONFIG_SNIFFER_PIN_CLK
#endif

static const char TAG[] = "SPI";

// Initialize SPI bus common to SD card and Ethernet
void initialize_spi(void)
{
#if CONFIG_SNIFFER_SD_SPI_MODE || CONFIG_ETHERNET_SPI_SUPPORT
    spi_bus_config_t bus_cfg = {
        .mosi_io_num = PIN_NUM_MOSI,
        .miso_io_num = PIN_NUM_MISO,
        .sclk_io_num = PIN_NUM_CLK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 4000,
    };

    // TODO: maybe make SPI host configurable
    int ret = spi_bus_initialize(1, &bus_cfg, SPI_DMA_CH_AUTO);
    if (ret != ESP_OK)
        ESP_LOGE(TAG, "Failed to initialize bus.");
#endif
}
