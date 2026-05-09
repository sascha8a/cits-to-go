# ESP32-C5 C-ITS USB serial logger

This firmware captures ITS-G5 / C-ITS packets with the original OpenTrafficMap ESP32-C5 802.11p receive path and exports accepted packets over USB Serial/JTAG for the Android bridge.

## USB packet protocol

By default, `CONFIG_CITS_USB_SERIAL_BINARY_FRAMING=y` uses compact binary frames. This roughly halves USB bandwidth compared with ASCII hex and reduces parsing CPU on Android.

Startup metadata remains text so it is easy to inspect in `idf.py monitor`:

```text
CITSMETA,<nodeid>,its/<nodeid>/packet,<hardware_variant>,<firmware_version>
CITSPROTO,binary-v1,header=28,crc32=1
```

Each binary packet frame is little-endian:

```text
0..3    magic = "CITS"
4       version = 1
5       flags, bit0 = truncated
6..7    header length, currently 28
8..11   seconds
12..15  microseconds
16..17  frequency MHz
18      RSSI dBm, int8
19      reserved
20..21  captured payload length
22..23  original packet length, saturated at 65535
24..27  CRC32/IEEE over captured payload, or 0 if disabled
28..    raw 802.11 MPDU bytes
```

For compatibility, disable `CONFIG_CITS_USB_SERIAL_BINARY_FRAMING` in `idf.py menuconfig` to restore the old text format:

```text
CITS,<seconds>,<microseconds>,<freq_mhz>,<rssi_dbm>,<caplen>,<truncated>,<hex_80211_mpdu>
```

The Android app accepts both formats.

## Build and flash

```bash
idf.py set-target esp32c5
idf.py build
idf.py -p /dev/ttyACM0 -b 921600 flash monitor
```
