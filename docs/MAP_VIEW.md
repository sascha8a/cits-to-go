# Map view

The **Map** page shows the stations and DENM events the phone has received on an OpenFreeMap base map, together
with the phone's own position. It needs no TX approval and no CAM broadcast: a running capture or a PCAP replay is
enough.

## Architecture

```text
capture thread   CitsBridgeService.handlePacket() → ItsFrameExtractor → ItsPacket
                     → LiveMapStore.accept(packet, receivedAt, capturedAt)      (CamDecoder, DenmDecoder)
                     → StationDiscoveryStore.observe(...)                       (discovery table)
status broadcast publishStatus() → EXTRA_LIVE_MAP_SNAPSHOT and EXTRA_ROAD_SIDE_UNITS, at most once per second
activity         MainActivity keeps the last LiveMapSnapshot and road side units
Map page         every second while resumed: LiveMapModel.build(snapshot, now, station timeout),
                     with the history button on also RoadSideUnitHistory.build(road side units, now, days)
                     → LiveMapFeatures → GeoJsonSource "roadSideUnits" / "stations" / "denms" / "focus"
                     → SymbolLayer with MapIcons
```

- **Decoders** (`cam/CamDecoder.kt`, `denm/DenmDecoder.kt`) read CAM and DENM UPER up to the fields the map needs:
  position, station type, heading, speed and trailer of a CAM; the management container and the event type of a
  DENM. Only `protocolVersion` 2 is accepted; version 1 differs on the wire. A malformed message is dropped on its own
  and never disturbs capture, PCAP or MQTT.
- **Store** (`livemap/LiveMapStore.kt`) runs in the service and aggregates every packet, whether or not the page is
  open, so that the map is filled as soon as it opens. Each capture and each replay starts with an empty store.
- **Snapshot** (`LiveMapSnapshot`) is a `Serializable` copy of the store in transmitted units. The service attaches it
  to the status broadcast at most once per second.
- **Model** (`livemap/LiveMapModel.kt`) is plain Kotlin: from a snapshot, the current time and the station timeout
  it computes what is visible, in display units, with labels (`StationTypes`, `DenmCauses`) and icon names.
- **Layers** (`livemap/LiveMapLayers.kt`) turn the model into GeoJSON features (`LiveMapFeatures`, covered by JVM
  tests) and add four sources and five symbol layers to the style, from the bottom: road side units from history, the
  heading pointers of the stations with their trailers, the station circles, DENMs, and the pin of a station opened
  from the Discovered Stations page. MapLibre's own position stays on top. Just below it, a sixth symbol layer draws
  the trailer of the own vehicle on MapLibre's own position source, so that the trailer moves with the animated
  position instead of jumping ahead of it. `livemap/MapIcons.kt` draws the icons with an Android `Canvas` for the
  screen density.
- **Page** (`MapPage.kt`) hosts a MapLibre `MapView` in an `AndroidView` and forwards the activity lifecycle to it.

The own position comes from the location listener of `MainActivity`, the same one Intersection View uses. It runs
while Intersection View or Map is shown and the app is in the foreground, and asks for the location permission when
needed. The page passes each position to MapLibre's `LocationComponent` with `forceLocationUpdate()`; MapLibre's
own location engine stays off. Without location permission the map works without the own position.

### Pausing

- While another page is shown there is no `MapView`: no rendering and no tile requests. The camera position and the
  follow switch are kept.
- When the app goes to the background, the one-second refresh stops on pause, rendering stops with `onStop()`, and
  the location updates stop as before.
- The service keeps aggregating, so the map is up to date when it is opened again.

## Store rules

**Stations**, keyed by the station ID of the CAM:

- The last CAM of each station is kept with its reception time on the phone.
- A CAM older than the stored one is dropped. The comparison uses `generationDeltaTime` modulo 65,536 ms, which
  catches reordered frames in captures; after 30 s without a CAM any CAM counts as newer.
- Stations are removed after the longest possible timeout (300 s), so the store does not depend on the setting. At
  most 500 stations are kept; the oldest goes first.

**DENM events**, keyed by the actionID (originating station ID and sequence number), following EN 302 637-3 V1.3.1
§8.4.2 and TS 103 831:

- An event that has already expired on arrival is dropped.
- An unknown actionID creates an event, unless the DENM is a termination.
- A DENM with an older reference time or detection time than the stored one is dropped.
- A DENM with the same reference time, detection time and termination is a repetition and only updates the
  reception time.
- Any other DENM replaces the event; position, cause and validity may change.
- A termination hides the event at once, but the entry stays until both the termination and the original event
  have expired, so that late repetitions of the old DENM do not bring it back.
- At most 200 events are kept; the one that expires first goes first.

An event expires at `detectionTime + validityDuration` (600 s when the DENM leaves it out). In a replay the capture
time of the frame shifts this, so an event stays as long after its reception as it was valid after its capture.
With captures that store whole-second timestamps, replayed expiry is accurate to about ±2 s.

## Timeouts and the setting

| | Value | Reason |
|---|---|---|
| Marker turns grey | 3 s, fixed | One lost CAM leaves a gap of 2 s; snapshot and refresh add up to 2 s |
| Marker disappears | **Settings → Map → Station timeout**, default 10 s | A stale marker is at most about 100–140 m off |
| Setting range | 5–300 s | 300 s matches the OpenTrafficMap web map, for logging use |
| Road side units | at least 30 s | They do not move, and no specification limits their CAM interval |

For this timeout, a station counts as a road side unit when its CAM has `rsuContainerHighFrequency`, whatever its
station type. The timeout is applied when the page draws the snapshot, so a change takes effect at once, also for a
stopped capture. After a pseudonym change the old marker stays as a duplicate until it times out.

DENM events have no timeout of their own: they show until they expire or are terminated.

## Display

- **Stations** are circles coloured by station type, with a white pictogram: car, truck or bus, tram, pedestrian for
  cyclists and other vulnerable road users, warning light for special vehicles, mast for road side units, question
  mark for unknown types. A pointer on the edge of the circle shows the heading of the last CAM; without a heading
  there is none. The pictogram stays upright, unlike an arrow that could be taken for the own position. Stale
  stations turn grey and translucent.
- **Trailers**: a vehicle whose CAM reports a trailer, with known or unknown length (VehicleLengthConfidenceIndication
  1 or 2), has a small trailer on a drawbar behind its circle, opposite the pointer. Like the pointer it needs a
  heading. A CAM that leaves the trailer unknown or unavailable (3 or 4) shows none.
- **DENM events** are warning signs at the event position, with icons for traffic jams (causes 1 and 27), accidents
  (2), roadworks (3), stationary vehicles (91–94) and emergencies (15 and 95), and a general hazard sign for all other
  causes. Road operators report the start of the hazard in driving direction as the event position.
- **Tapping** a marker opens a card with its details, which updates every second and closes when the marker
  disappears. Tapping the map elsewhere closes it. For DENMs of infrastructure senders (station types 9, 10 and 15)
  the card reads "Updated n s ago" instead of "Detected n s ago", because they set a new detection time with every
  update.
- While CAMs are broadcast, the **own position** shows the icon of the vehicle profile instead of the blue dot, at
  about the size of a station marker. Like the station icons it stays upright, so it has no heading arrow; a
  position that has gone stale still shows as the grey dot. When the CAMs report a trailer, that is when the vehicle
  profile has a trailer and a length (**Settings → Vehicle Type**), a trailer in the colours of the icon follows
  behind it along the direction of travel. A standing phone has no heading, so the trailer keeps the last direction
  of travel, for example at a red light, and shows only once the phone has moved since the map was opened; it
  disappears with a stale position and when the broadcast stops.
- **Follow** (on by default) keeps the own position in the centre. Moving the map turns it off, also before the
  first position fix. The button appears with the own position, turns follow on again and zooms in from an overview.
  Before the first position fix the map shows Austria.
- While the map is shown, swiping does not open the navigation drawer, so that swipes move the map; the menu button
  still opens the drawer.

## Road side units from history

The history button above the follow button (off by default; the app remembers it) adds the road side units of the
[discovery table](DISCOVERED_STATIONS.md) to the map, also when nothing is being received. They are the stations whose
GeoNetworking address carries station type 15, at the last position of their own Source Position Vector; unlike the
road side unit timeout of the live map, this does not depend on the CAM. A traffic light icon marks the units the phone
heard sending MAPEM, SPATEM or SSEM directly, a mast with radio waves all others: units that only send beacons, IVIMs
or DENMs, and units known only from packets that other stations forwarded. Both are translucent and lie below the live markers. Tapping one shows
its MAC address, when it was first and last heard, its message counts and its signal strength. A unit known only
from packets that other stations forwarded has no message counts and no signal strength.

A double tap on the GeoNetworking position of a row on the Discovered Stations page opens the map there: follow
turns off, the map centres on the position and the detail card of the station opens and keeps updating. A road
side unit turns the history on and is marked by its history marker; any other station, or a unit the history does
not show, gets a pin. Pin and card stay until the card is closed, the map or another marker is tapped, or the page
is left.

**Settings → Map → Road side units heard within** limits the history to units last heard within that many days.
Empty or 0, the default, shows all. Replayed captures count with their capture time.

The discovery table keeps up to 500 road side units apart from the 500 other stations, so that passing traffic
does not push them out. The service publishes them with the status broadcast at most once per second, in addition
to the rows the table shows. **Clear list** on the Discovered Stations page keeps them unless its dialog is told to
forget them as well.

## Map source and attribution

The base map is OpenFreeMap: `https://tiles.openfreemap.org/styles/liberty`, or `.../styles/dark` when the app uses
its dark theme. It needs no API key. Its attribution, "OpenFreeMap © OpenMapTiles Data from OpenStreetMap", is
always visible at the bottom of the map; the links open in the browser. The MapLibre logo and attribution button are
off.

Tiles load over the network and are kept in MapLibre's disk cache according to their HTTP cache headers. There is
no prefetching (MapLibre's `prefetchZoomDelta` is 0) and no offline region; without a data connection only cached
tiles show. If the style cannot be loaded, the page says so until MapLibre's own retries succeed; opening the page
again also retries.

**About → Open-source licences** lists MapLibre and the libraries it brings along, the map data sources, and the
licence texts that binary distributions must reproduce (`res/raw/`).

## Testing with trimmed captures

PCAP replay runs in real time, so test with short excerpts of a capture. The app reads classic pcap with link type
105 (802.11) or 127 (802.11 with radiotap, as its own recordings), not pcapng, so write the excerpt with `-F pcap`:

```bash
# -A and -B take Unix seconds or "YYYY-MM-DD hh:mm:ss"
editcap -F pcap -A 1791564649 -B 1791565549 capture.pcap excerpt.pcap
adb push excerpt.pcap /sdcard/Download/
```

Then open **Home → Replay PCAP**, choose the file and switch to **Map**. Check:

- stations appear with the pictogram of their type and a pointer along their heading (none without one), which stays
  right when the map is rotated or tilted; they turn grey after 3 s without a CAM and disappear after the station
  timeout,
- a station whose CAM reports a trailer has one behind its circle, opposite the pointer, also on a rotated or tilted
  map; field captures rarely contain one, but a recording of a second phone that broadcasts CAMs with a trailer in
  its vehicle profile does,
- DENM events disappear when they expire,
- follow, tapping markers, and the detail card,
- the map stays where it was after switching pages, and switches to the dark style with the app theme,
- nothing renders or refreshes while the app is in the background,
- a double tap on a position on the Discovered Stations page opens the map there with a pin (or, for a road side
  unit, with the history on) and the card of the station; closing the card removes the pin,
- with the history button on, the road side units of the capture appear and stay after the replay ends; tapping one
  shows its card; a days limit in the settings hides older ones; turning the button off removes them and closes
  their card; the button keeps its state after a restart.

The own vehicle needs a real drive with a CAM broadcast. With a trailer and a length in the vehicle profile, the own
icon pulls a trailer once it moves, which turns with the direction of travel and moves with the icon without jumping
ahead of it. When standing still it keeps the last direction; it disappears when the broadcast stops, with a stale
position, and after switching to a profile without a trailer.

## Updating MapLibre

1. Use `org.maplibre.gl:android-sdk-opengl`. The plain `android-sdk` artifact renders with Vulkan and requires
   Vulkan 1.3.
2. Change the version in `app/build.gradle`.
3. List the runtime libraries with
   `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` and update the list in `AboutPage.kt`.
4. Replace the licence texts in `app/src/main/res/raw/` with the files of the new release:
   `platform/android/LICENSE.md` of the `android-v<version>` tag of maplibre-native, and `LICENSE.md` of the
   maplibre-gestures-android version it uses. `platform/android/LICENSE.md` leaves out some libraries compiled
   into `libmaplibre.so`; `maplibre_native_bundled_licenses.md` has their texts at the vendored versions: the
   HarfBuzz section of `LICENSES.core.md`, `vendor/icu/LICENSE`, and the licence files of the PMTiles,
   unordered_dense, jni.hpp (in `vendor/maplibre-native-base`) and maplibre-tile-spec submodules. Compare both
   files with the libraries that `CMakeLists.txt` and `platform/android/android.cmake` link into `mbgl-core`.
5. Check the merged manifest (`app/build/intermediates/merged_manifest/debug/`) for new permissions or features.
   `ACCESS_WIFI_STATE` is removed in `AndroidManifest.xml`.
6. Compare the APK size. The native libraries are packaged compressed (`useLegacyPackaging`), which adds about
   16 MB to the APK instead of 41 MB.

## Known limitations

- The base map needs a network connection; only cached tiles work offline.
- Without a data connection and no cached style, the page shows no stations or DENMs either, until the style has
  loaded.
- Only stations the phone receives itself are shown; there is no OpenTrafficMap backend.
- MAPEM, SPATEM and IVIM are not on the map; intersections stay in Intersection View.
- DENM events are points; traces, event zones and closed lanes are not drawn yet.
- Trailers have a fixed size, whatever length the CAM carries. They are drawn opposite the heading, which is the
  direction of travel, so a reversing vehicle shows its trailer on the wrong side.
- Replay runs in real time only.
- Infrastructure DENMs without a centrally assigned actionID can show twice when several road side units send them.
- Road side units from history show where they last were and are not updated by the live map; a unit that also
  sends CAMs appears twice while it is live.
- Rows stored by app versions before 2.56.0 carry no GeoNetworking station type, so such a road side unit shows in
  history only after it has been heard again, live or in a replay.
