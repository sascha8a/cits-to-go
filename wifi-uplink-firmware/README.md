# CITS-to-go Wi-Fi Uplink Firmware

Firmware for a second **Seeed Studio XIAO ESP32-C5** that receives CTG1 packet frames from the capture board over GPIO SPI and publishes the raw C-ITS packet payloads to MQTT over Wi-Fi.

## Defaults

- Wi-Fi SSID: `WillkommenimVOR-1`
- MQTT broker: `mqtts://cits1.opentrafficmap.org`
- MQTT packet topic: `its/<nodeid>/packet`
- MQTT packet payload: raw captured 802.11 frame bytes, matching the Android app
- SPI role: slave
- SPI CS: D1 / GPIO0
- SPI READY: D2 / GPIO25
- SPI MOSI: D3 / GPIO7
- SPI MISO: D4 / GPIO23
- SPI SCLK: D5 / GPIO24
- Partition table: 2 MB factory app slot, needed because Wi-Fi plus TLS nearly fills ESP-IDF's default 1 MB slot

The node ID defaults to the uplink board's Wi-Fi station MAC address as 12 lowercase hex digits. Override `CONFIG_CITS_UPLINK_NODE_ID` with `idf.py menuconfig` if you need a stable custom ID.

## Wiring

For the current D1-to-D5 straight-through wiring:

- Capture board D1/GPIO0 CS -> uplink board D1/GPIO0 CS
- Capture board D2/GPIO25 READY <- uplink board D2/GPIO25 READY
- Capture board D3/GPIO7 MOSI -> uplink board D3/GPIO7 MOSI
- Capture board D4/GPIO23 MISO <- uplink board D4/GPIO23 MISO
- Capture board D5/GPIO24 SCLK -> uplink board D5/GPIO24 SCLK
- GND -> GND

MISO is configured by default because D4 is already wired, but packet forwarding currently only needs CS, READY, MOSI, SCLK, and GND.

## MQTT Compatibility

This firmware follows the Android bridge behavior:

```text
Topic:   its/<nodeid>/packet
Payload: raw binary packet bytes
QoS:     0
Retain:  false
```

It also publishes retained `its/<nodeid>/status` messages (`online`/`offline`), one-shot `its/<nodeid>/info`, and periodic `its/<nodeid>/stats`.

## Build and Flash

Run from this directory:

```bash
docker run --rm -it \
  --device /dev/ttyACM0 \
  -v "$PWD":/project \
  -w /project \
  -u "$UID" \
  -e HOME=/tmp \
  espressif/idf \
  idf.py set-target esp32c5 build flash monitor
```
