# Discovered stations table

## What it shows

The Home screen's `Discovered` tile opens the *Discovered Stations* page: one
row per station the receiver has heard from, keyed by the IEEE 802.11 source
address of its frames. The columns are MAC address, last seen, first seen,
GeoNetworking position, one counter per decoded ITS message type (DENM, CAM,
SPATEM, MAPEM, IVIM, SREM, SSEM), last RSSI and best RSSI.

Every column sorts by tapping its header, and every column has its own filter
behind the funnel icon. Sorting is remembered per tap, both filter and sort are
stored in app preferences, and `Reset` returns to the default view: last seen,
newest first.

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
address. It runs on the serial read thread and is guarded by a lock, so live
reception and PCAP replay use the same path.

- The message-type counters come from the `messageId` of the ITS-PDU header that
  `ItsFrameExtractor` decodes. Frames the receiver cannot decode still create a
  row, so a station is never missing from the table; its counters just stay at
  zero.
- The GeoNetworking position is the source position vector of the transmitting
  radio, from the GeoNetworking header, not a position inside the ITS container.
  Positions outside the valid coordinate range, and the zeroed position vector
  most senders leave in place, are ignored.
- RSSI is the firmware's report for the frame; `Last RSSI` is the most recent,
  `Best RSSI` the strongest one seen.
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
leaves the default order instead of an order nobody can see. Records written
before the station ID, station type and packet total were dropped are read as
usual, those fields are simply ignored.

The store keeps the 500 addresses seen most recently and drops the longest
unseen one beyond that. Status broadcasts carry at most
`DiscoveredStationList.BROADCAST_LIMIT` rows, at most once per second.

That list travels as a `Serializable` extra, which `Parcel.writeSerializable`
writes with `ObjectOutputStream`, so `DiscoveredStation` and every type in it
must stay serializable — a row that is not takes the whole bridge service down
when it publishes its first status. `StationDiscoveryStoreTest` serializes a real
snapshot to catch that, and `publishStatus` reports a rejected broadcast as an
error instead of letting it kill the service.

Addresses discovered before per-station tracking existed are still in
`discovered_mac_addresses`. They are seeded into the store as rows without
timestamps, so the Home counter and the table agree; `Clear list` empties both.

## Tests

`StationDiscoveryStoreTest` covers aggregation, eviction, position filtering,
the JSON round trip including an older record layout, and seeding.
`StationTableStateTest` covers the column set and order, sorting, the filter
grammar, width clamping and round trip, and dropping retired columns.
`ItsFrameExtractorTest` checks the GeoNetworking source position against a
synthetic frame and against the recorded captures in the repository root.
