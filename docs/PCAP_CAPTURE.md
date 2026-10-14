# PCAP capture format

## What a recording holds

The app records what the receiver heard into a classic PCAP file (magic
`0xA1B2C3D4`, microsecond resolution) of link type **127**, "IEEE 802.11 radio":
each packet is a radiotap header followed by the IEEE 802.11 frame, which is
where the 802.11 addressing, the SNAP/LLC enclosure, the GeoNetworking header,
BTP and the ITS-PDU live. Wireshark dissects the whole stack, so a recording can
be read as an air-interface trace and replayed by this app.

Radiotap exists precisely because none of the radio conditions are inside an
802.11 frame: signal strength, channel and noise are properties of the
reception, so they are recorded alongside the frame instead of in it.

## Fields this app writes

`Radiotap.header` emits the fixed part (revision, pad, header length, present
bitmap) followed by only the two fields the receiver can report:

| Field | Radiotap bit | From |
| --- | --- | --- |
| Channel | 3 | `CitsPacket.frequencyMhz`, as MHz plus the 5 GHz OFDM channel flags `0x0140` (ITS-G5 and ITS-G5B transmit there) |
| dBm Antenna Signal | 5 | `CitsPacket.rssiDbm` |

A value the firmware did not measure arrives as zero, and zero is recorded by
**leaving the field out of the bitmap** rather than by writing a zero field, so a
reader never mistakes "unknown" for "measured as 0 dBm". A frame whose radio
conditions were never known — anything this app transmitted, recorded through
`writeRawPacket` — gets an eight byte header with an empty bitmap.

`original_length` in the record header holds the length the frame had on the air,
so a frame trimmed by the receiver replays as the truncated frame it was; that is
what `FLAG_TRUNCATED` is derived from on replay.

## Reading files back

`PcapReader` accepts link type 105 (plain IEEE 802.11, no radio information: the
files this app wrote before radiotap, and captures from tools that discard radio
metadata) and link type 127. Anything else is refused with the two supported
numbers in the message.

Reading a radiotap header means walking the field bitmap: fields appear in
ascending field number, each aligned to a multiple of its own width, and bit 31
of a bitmap word announces another bitmap word behind it. `Radiotap.split` steps
over fields this reader does not use — a monitor-mode capture usually carries a
timestamp, rate, noise floor, antenna and flags as well — and stops at the first
field numbered beyond its table, which cannot hide anything this app reads
because those fields are numbered low. The frame always starts at the header
length, so the frame itself is never misplaced. Radiotap is little-endian by
definition, whatever byte order the enclosing PCAP uses.

Replayed frames carry `rssiDbm` and `frequencyMhz` into `handlePacket`, so a
replay fills the `Last RSSI` and `Best RSSI` columns of the
[discovered stations table](DISCOVERED_STATIONS.md) and shows the channel in the
packet summary. Recordings made before this format existed have no radio
information and stay empty there; re-capturing is the only way to recover signal
strength for them.

## Tests

`PcapWriterTest` checks the link type, the bitmap, the placement of the two
fields, the omission of unmeasured values and the recorded original length.
`PcapReplayTest` round-trips signal and channel, reads a hand-built monitor-mode
header whose seven fields force the alignment walk, reads through a field newer
than the reader's table, and rejects a foreign link type and a header whose
revision is not zero.

`WiresharkPacketValidationTest.aRecordedCaptureKeepsSignalStrengthAndChannelForWireshark`
writes a generated CAM frame through `PcapWriter` and asks `tshark` to dissect
it, asserting that the frame stack contains `radiotap`, that the header is 13
bytes, that `radiotap.dbm_antsignal` and `radiotap.channel.freq` match what was
recorded, that the ITS message ID and BTP port still decode behind the radio
header, and that `_ws.malformed` does not match the packet.
