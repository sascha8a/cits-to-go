#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  echo "Usage: cits-flash PORT [BAUD] (default baud: 921600)"
  echo "Builds as needed and flashes separate segments, preserving NVS/BLE bonds."
  exit 0
fi
if (( $# < 1 || $# > 2 )); then
  echo "Usage: cits-flash PORT [BAUD]" >&2
  exit 2
fi
port="$1"
baud="${2:-921600}"
if [[ ! "$baud" =~ ^[1-9][0-9]*$ ]]; then
  echo "BAUD must be a positive integer." >&2
  exit 2
fi
cd "$(dirname "${BASH_SOURCE[0]}")/.."
build_dir="${CITS_BUILD_DIR:-build}"
# No erase-flash and no merged image: leave the NVS partition untouched.
# Explicit target selection supports flashing directly from a fresh checkout.
exec idf.py -B "$build_dir" -D IDF_TARGET=esp32c5 -p "$port" -b "$baud" flash
