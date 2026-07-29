# CAM implementation notes

## Stack

The Android generator produces this complete raw frame:

1. IEEE 802.11 QoS data frame, broadcast destination, TID 3, no FCS.
2. LLC/SNAP with GeoNetworking EtherType `0x8947`.
3. GeoNetworking Basic + Common + SHB headers.
4. BTP-B with destination port 2001 and port-info 0.
5. UPER-encoded CAM Release 1.

The CAM header uses protocol version 2 and CAM message ID 2. The stable random
station ID and locally administered MAC/MID are stored in app preferences.

## Data population

- `generationDeltaTime` is the TAI reference-position timestamp modulo
  65,536 ms from the ITS epoch (2004-01-01 UTC), including the five leap
  seconds introduced since that epoch.
- Latitude and longitude use 0.1 microdegree units.
- Altitude uses centimetres.
- Heading uses 0.1 degree and speed uses centimetres per second.
- Android horizontal, vertical, bearing, and speed accuracy values are mapped
  to the corresponding confidence fields.
- Sensor and vehicle-size values unavailable from Android are encoded using
  their standardized unavailable values.
- A position older than five seconds is not advertised; CAM broadcast waits
  until a fresh phone location is available.
- RSU uses `RSUContainerHighFrequency`; all other selectable station types use
  `BasicVehicleContainerHighFrequency`. The app UI only exposes pedestrian and
  bicycle station types. Release 1 does not define pedestrian/cyclist-specific
  high-frequency CAM containers, so these are experimental interoperability
  modes. Do not treat them as a substitute for VAM.

## Generation policy

The UI interval is constrained to 100–1000 ms and acts as the maximum
generation interval. The app checks every 100 ms and generates early when a
new location differs from the previous CAM by more than:

- 4 m in position;
- 4 degrees in heading; or
- 0.5 m/s in speed.

This implements the core CAM dynamics triggers. A production ITS station must
also integrate the local DCC state and certified security/authorization
services; an Android phone does not expose those facilities.

## Standards and upstream implementation

- ETSI EN 302 637-2 V1.4.1, CAM Basic Service:
  <https://www.etsi.org/deliver/etsi_en/302600_302699/30263702/01.04.01_30/en_30263702v010401v.pdf>
- ETSI TS 102 894-2 V1.2.1, Common Data Dictionary:
  <https://www.etsi.org/deliver/etsi_ts/102800_102899/10289402/01.02.01_60/ts_10289402v010201p.pdf>
- ETSI EN 302 636-4-1 V1.4.1, GeoNetworking:
  <https://www.etsi.org/deliver/etsi_en/302600_302699/3026360401/01.04.01_60/en_3026360401v010401p.pdf>
- ETSI EN 302 636-5-1 V1.2.1, BTP:
  <https://www.etsi.org/deliver/etsi_en/302600_302699/3026360501/01.02.01_60/en_3026360501v010201p.pdf>
- OpenTrafficMap tx-enabled ESP32-C5 firmware:
  <https://codeberg.org/opentrafficmap/its-g5-receiver-firmware_txenabled>

## RF safety

The firmware can emit arbitrary raw frames in the regulated 5.9 GHz ITS band.
Use it only where the operator has explicit authorization, preferably inside a
shielded test environment. A zero TX result means the ESP32 HMAC accepted the
frame; it is not proof of over-the-air delivery or legal authorization.
