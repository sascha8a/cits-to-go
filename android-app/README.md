# C-ITS Android USB Bridge

This Android app reads C-ITS packets from the ESP32-C5 USB logger, writes a classic `.pcap`, and republishes the raw packet bytes to MQTT using the original firmware topic format:

```text
its/<nodeid>/packet  -> raw binary 802.11 packet bytes
its/<nodeid>/status  -> online/offline
its/<nodeid>/info    -> firmware-style JSON
its/<nodeid>/stats   -> {}
```

## Optimized capture pipeline

The app now runs the complete bridge in a foreground service:

```text
USB reader -> binary/ASCII CITS parser -> buffered PCAP writer -> app-private MQTT spool -> interval MQTT publisher
```

Key properties:

- USB serial reading is independent of the Activity and continues while the phone is locked.
- The app accepts both the optimized binary USB framing and the older ASCII `CITS,...` lines.
- PCAP output is buffered and flushed periodically instead of on every packet.
- MQTT publishing is decoupled from USB reading. Packets are written to an app-private spool first.
- MQTT publishing drains the spool every 250 ms, up to 100 packets per burst, while keeping the original one-packet-per-`its/<nodeid>/packet` format.
- On `broken pipe` or any MQTT write error, the socket is closed, the failed packet remains in the spool, and reconnect uses exponential backoff.
- The service holds a partial wake lock and a high-performance Wi-Fi lock while running.

The user-visible PCAP file remains the primary capture artifact. The MQTT spool is an internal retry journal and is cleared only when you explicitly disconnect MQTT from the UI.

## Android battery settings

For long captures, install the app, grant notification permission, start USB/MQTT/PCAP, then keep the **C-ITS USB Bridge** foreground notification active. On heavily optimized Android builds, set the app battery mode to **Unrestricted**.

## Build

```bash
cd android-app
./gradlew assembleDebug
```
