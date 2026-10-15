# Discovered stations table

## What it shows

The Home screen's `Discovered` tile opens the *Discovered Stations* page: one
row per station the receiver has heard from, keyed by the IEEE 802.11 source
address of its frames, or, for a station heard only through forwarders, by the
MID of its GeoNetworking address. The columns are MAC address, last seen, first
seen, GeoNetworking position, one counter per decoded ITS message type (DENM,
CAM, SPATEM, MAPEM, IVIM, SREM, SSEM), last RSSI and best RSSI.

Every column sorts by tapping its header. Filtering is per column behind the funnel icon, and each
kind of column gets an editor that fits its values: the MAC address matches as text (contains, starts
with or exact, ignoring case); the packet-type counters take an open or closed minimum/maximum with
`Sent at least one` and `Sent none` shortcuts; the RSSI columns filter with a dBm range slider plus a
`without any RSSI` switch; and the timestamp columns pick a whole-day range in one material
`DateRangePicker` calendar - both ends selected in the same view - beside rolling `Today`,
`Last hour` and `Last 24 h` shortcuts that keep chasing a live capture. A filter never silently
drops a row whose value is missing where that state exists: the RSSI editors ask for it explicitly,
and a selection that holds no constraint removes the filter instead of hiding everything. The
GeoNetworking position cannot be filtered; it still sorts. Both filter and sort are stored in app
preferences, and `Reset` returns to the default view: last seen, newest first.

A double tap on a GeoNetworking position, shown in the accent colour, opens the [map](MAP_VIEW.md) at that
station; screen readers offer it as "Show on map". While a capture runs, the default sort by last seen keeps
moving active rows, so a double tap is easier with another sort or a stopped capture.

A row is the *radio*, not the logical station: the ITS station ID and station
type of the payload are deliberately not shown, because one MAP or SPAT feed can
be transmitted by several radios (and one radio can carry payloads authored
elsewhere), which made the table read as if several stations shared an identity.

## Column widths

A column is as wide as the widest value it currently shows, plus the room its
own header needs for the title, sort arrow, filter button and resize handle.
Cell text is single-line, so measuring it is enough; titles wrap onto two lines
when they contain a space, which is why only such titles may count as half
their width. The result is clamped to the column's own minimum and to
`StationColumn.MAX_WIDTH_DP`, so one very long value cannot push the rest of the
table away.

The grabber on the right edge of a header can be dragged to set a width by hand,
which is then kept instead of the measured one; tapping the grabber, tapping the
`fixed … dp` chip above the table, or pressing `Reset` hands the column back to
the measurement. Dragged widths are stored with the sort and filters.

## Where the values come from

`StationDiscoveryStore` folds every received packet into one record per source
address, and a forwarded packet's position also into the record of the station
that created it. It runs on the serial read thread and is guarded by a lock, so
live reception and PCAP replay use the same path.

- The message-type counters come from the `messageId` of the ITS-PDU header that
  `ItsFrameExtractor` decodes. Frames the receiver cannot decode still create a
  row, so a station is never missing from the table; its counters just stay at
  zero.
- The GeoNetworking position is the Source Position Vector from the
  GeoNetworking header, not a position inside the ITS container. Beacons, which
  carry nothing else, provide it as well. Positions outside the valid coordinate
  range, and the zeroed position vector most senders leave in place, are
  ignored.
- A forwarded packet keeps the Source Position Vector of the station that
  created it: a forwarder leaves it as the source set it and decrements the
  remaining hop limit (EN 302 636-4-1 V1.4.1, clause 10.3.11.3 step 9; clause
  10.3.9.3 for TSB), while the frame carries the forwarder's own MAC address.
  A packet whose remaining hop limit is below its maximum hop limit was
  forwarded. Its position goes to the row of the source's address, the MID
  of its GN_ADDR, which is added if the receiver has not heard that station
  itself. The forwarded packet also updates that row's first and last seen
  times. Message counts and RSSI stay with the radio that sent the frame, so a
  row that exists only this way has neither; it does not count as a discovered
  device on Home and raises no discovery notification.
- RSSI is the firmware's report for the frame; `Last RSSI` is the most recent,
  `Best RSSI` the strongest one seen. A replay fills these in too, provided the
  recording carries the signal strength, which the
  [PCAP format notes](PCAP_CAPTURE.md) describe. A recording made before
  radiotap was written has nothing to report, so those two columns stay empty.
- Timestamps use the frame's own wall-clock time when a PCAP is replayed, so a
  replayed capture reports when the original capture heard the station.

## Persistence

Two preference keys in the shared `cits_bridge` store back the page:

- `discovered_stations` holds the whole table as JSON written by the bridge
  service once per maintenance tick, and only when a packet changed something.
  `StationDiscoveryStore.encode`/`restore` are the counterpart pair; unknown
  message types and malformed records are skipped when reading.
- `station_table_state` holds the sort column, its direction, the active filters
  and any hand-made column widths as JSON, so the table looks the same on the
  next visit.

Both formats tolerate what older versions wrote: keys of columns that no longer
exist are dropped, and so is a stored sort direction whose column is gone, which
leaves the default order instead of an order nobody can see. Filters are stored
as JSON objects describing the dedicated editors' state; the free-text queries
saved by older versions are converted where the new editors can express them -
a text query stays a contains match, a comparison such as `>=-70` becomes the
equivalent bound, and the `—` dash becomes the missing-value request - while
text a dedicated editor cannot represent, and every stored filter on the
position column, are dropped. Records written before the station ID, station
type and packet total were dropped are read as usual, those fields are simply
ignored. The station type of a row is stored as `gnStationType`: older
versions used `type` for the station type of the ITS payload, which does not
describe the radio.

The store keeps the 500 addresses seen most recently and drops the longest
unseen one beyond that. Road side units, whose GeoNetworking address carries
station type 15, have a limit of their own of another 500, so that the
[map](MAP_VIEW.md) can show them from history however much traffic passed
since. Status broadcasts carry at most `DiscoveredStationList.BROADCAST_LIMIT`
rows, at most once per second, and with them every road side unit that has a
position.

That list travels as a `Serializable` extra, which `Parcel.writeSerializable`
writes with `ObjectOutputStream`, so `DiscoveredStation` and every type in it
must stay serializable — a row that is not takes the whole bridge service down
when it publishes its first status. `StationDiscoveryStoreTest` serializes a real
snapshot to catch that, and `publishStatus` reports a rejected broadcast as an
error instead of letting it kill the service.

Addresses discovered before per-station tracking existed are still in
`discovered_mac_addresses`. They are seeded into the store as rows without
timestamps, so that every address the Home counter includes also has a row.
`Clear list` empties both. Its dialog keeps the road side units, which the
[map](MAP_VIEW.md) shows from history, unless it is told to forget them too.
Kept road side units also stay in the Home counter.

## Tests

`StationDiscoveryStoreTest` covers aggregation, eviction with the separate limit
for road side units, position filtering, forwarded packets, the station type,
the JSON round trip including an older record layout, and seeding.
`StationTableStateTest` covers the column set and order, sorting, the text,
number-range and time-range filters including the rolling shortcuts, the stored
filter format and its migration from the old free-text queries, width clamping
and round trip, and dropping retired columns.
`ItsFrameExtractorTest` checks the GeoNetworking source position against a
synthetic frame and against the recorded captures in the repository root.
