#!/usr/bin/env python3
"""Convert CITS USB-serial log lines to a pcap containing raw IEEE 802.11 frames.

Input line format emitted by this firmware:
  CITS,<seconds>,<microseconds>,<freq_mhz>,<rssi_dbm>,<caplen>,<truncated>,<hex_80211_mpdu>

Usage:
  idf.py monitor | tee capture.log
  python3 tools/serial_to_pcap.py -i capture.log -o capture.pcap
"""

from __future__ import annotations

import argparse
import binascii
import struct
from pathlib import Path

DLT_IEEE802_11 = 105


def convert(input_path: Path, output_path: Path) -> tuple[int, int]:
    written = 0
    skipped = 0

    with input_path.open("r", encoding="utf-8", errors="replace") as inp, output_path.open("wb") as out:
        out.write(struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, DLT_IEEE802_11))

        for raw in inp:
            line = raw.strip()
            if not line.startswith("CITS,"):
                continue

            parts = line.split(",", 7)
            if len(parts) != 8:
                skipped += 1
                continue

            try:
                seconds = int(parts[1])
                micros = int(parts[2])
                caplen = int(parts[5])
                frame = binascii.unhexlify(parts[7])
            except Exception:
                skipped += 1
                continue

            if len(frame) != caplen:
                skipped += 1
                continue

            out.write(struct.pack("<IIII", seconds, micros, len(frame), len(frame)))
            out.write(frame)
            written += 1

    return written, skipped


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-i", "--input", required=True, type=Path)
    parser.add_argument("-o", "--output", required=True, type=Path)
    args = parser.parse_args()

    written, skipped = convert(args.input, args.output)
    print(f"wrote {written} packets to {args.output}")
    if skipped:
        print(f"skipped {skipped} malformed CITS lines")


if __name__ == "__main__":
    main()
