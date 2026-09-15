#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
build_dir="${CITS_BUILD_DIR:-build}"
# Use an absolute path because merging runs from inside the build directory.
mkdir -p "$build_dir"
build_dir="$(cd "$build_dir" && pwd)"
cargo test --locked --features firmware
cargo fmt --check
cargo clippy --locked --all-targets --features firmware -- -D warnings
# Selecting the target through CMake also initializes a fresh sdkconfig, without
# set-target's implicit fullclean (which rejects a Cargo-only build directory).
idf.py -B "$build_dir" -D IDF_TARGET=esp32c5 build
mkdir -p dist
dist_dir="$PWD/dist"
(
  cd "$build_dir"
  esptool --chip esp32c5 merge-bin --format raw -o "$dist_dir/cits-to-go-firmware-rs.bin" @flash_args
)
sha256sum dist/cits-to-go-firmware-rs.bin > dist/SHA256SUMS
