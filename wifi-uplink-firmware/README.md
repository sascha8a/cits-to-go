# CITS-to-go Wi-Fi Uplink Firmware

Firmware for a second **Seeed Studio XIAO ESP32-C5** that receives CTG1 serial packet frames from the capture board and publishes the raw C-ITS packet payloads to MQTT over Wi-Fi.

## Defaults

- Wi-Fi SSID: `WillkommenimVOR-1`
- MQTT broker: `mqtts://cits1.opentrafficmap.org`
- MQTT packet topic: `its/<nodeid>/packet`
- MQTT packet payload: raw captured 802.11 frame bytes, matching the Android app
- UART: UART1, 921600 baud
- UART RX: D1 / GPIO0
- UART TX: D2 / GPIO25
- Partition table: 2 MB factory app slot, needed because Wi-Fi plus TLS nearly fills ESP-IDF's default 1 MB slot

The node ID defaults to the uplink board's Wi-Fi station MAC address as 12 lowercase hex digits. Override `CONFIG_CITS_UPLINK_NODE_ID` with `idf.py menuconfig` if you need a stable custom ID.

## Wiring

For the current D1-to-D1, D2-to-D2 wiring:

- Capture board D1/GPIO0 TX -> uplink board D1/GPIO0 RX
- Capture board D2/GPIO25 RX <- uplink board D2/GPIO25 TX
- GND -> GND

Only the capture-board TX to uplink-board RX path is required for packet forwarding.

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
