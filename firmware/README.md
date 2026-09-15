# CITS-to-go — Rust / Embassy firmware

New implementation for the **Seeed Studio XIAO ESP32-C5**, alongside the original
`../firmware`. The existing CTG1 capture/transmit framing remains compatible; this
version additionally emits the optional type-6 firmware statistics record consumed
by the Android Debug page.

The packet pipeline, framing, parsers, buffer ownership and application scheduling
are Rust. Embassy runs five kinds of task: capture forwarding, USB input, BLE
input, statistics reporting and radio transmission. A small platform adapter and the retained NimBLE
and private-radio adapters are C. ESP-IDF still supplies the Wi-Fi/BLE binary
libraries, USB interrupt driver, persistent bond store and FreeRTOS.

This is deliberately an **Embassy-on-ESP-IDF implementation**, not a claim that
Espressif's private 802.11p interface has a public Rust HAL equivalent. It also
preserves the existing NVS-backed BLE owner-enrollment behavior. Embassy's custom
executor integration is described in its [official documentation](https://docs.embassy.dev/).

## Build

The source repository pins an ESP-IDF **6.1-dev container by digest** in
`../scripts/build-release.sh`. This firmware's Dockerfile uses that exact image.
The private HMAC structures cannot safely follow a floating IDF version.

From this folder:

```sh
docker build -t cits-to-go-rust .
docker run --rm -v "$PWD":/project -w /project cits-to-go-rust ./scripts/build.sh
```

This runs host tests, formatting and lint checks, builds the Rust RISC-V static
library and the ESP-IDF application, and merges bootloader, partition table and
application into `dist/cits-to-go-firmware-rs.bin`. Checksums are in
`dist/SHA256SUMS`. Docker may create build files owned by root on Linux.

With the **same IDF revision** installed locally:

```sh
# First activate that IDF environment using its export.sh.
rustup toolchain install 1.88.0 --profile minimal --component rustfmt,clippy
rustup target add --toolchain 1.88.0 riscv32imac-unknown-none-elf
./scripts/build.sh
```

The build script preserves an existing sdkconfig. For configuration changes use
`idf.py menuconfig`, followed by the script or `idf.py build`.

The target is `riscv32imac-unknown-none-elf`, with the C-compatible ILP32 ABI.
Rust compiles a `no_std` static library, which IDF links into its normal image.
This avoids depending on Rust `std` support for the ESP32-C5 target. Host tests
use `std`; the embedded application does not use Rust heap allocation. ESP-IDF
and NimBLE still allocate their own driver/stack memory during initialization
and radio operation.

`cargo build --release --features firmware --target riscv32imac-unknown-none-elf`
builds **only the Rust archive**, not a flashable firmware image. Use `idf.py`
or the build script for final linking. `cargo test --locked --features firmware` tests the
portable core and task storage bounds on your host. Dependencies are resolved in `Cargo.lock`.

## Nix (Linux)

A native flake is included for `x86_64-linux` and `aarch64-linux`. Enable Nix's
`nix-command` and `flakes` experimental features, then from `firmware-rs/`:

```sh
nix develop
cits-build
cits-flash /dev/ttyACM0
# Optional slower serial rate:
cits-flash /dev/ttyACM0 460800
```

Or run the same commands without staying in the development shell:

```sh
nix run .#build
nix run .#flash -- /dev/ttyACM0
```

`cits-build` runs the host checks and builds/merges the complete firmware.
`cits-flash` builds as needed and uses IDF's separate flash segments, preserving
NVS/BLE bonds. It does not flash the merged image. Select your actual serial
port and make sure your account can access it (usually the `dialout` or `uucp`
group on Linux). If automatic reset fails, enter the board's ROM download mode
using BOOT/RESET and retry. Close serial monitors before flashing.

The flake pins nixpkgs and rust-overlay to immutable commits, Rust to 1.88.0,
ESP-IDF to `f21b4c238152dc9e3a24fbad9afe33a3d15f6cfd` including submodules,
and the Espressif GCC archive by SHA-256. First invocation generates
`flake.lock`; commit it when adding these files to a Git repository. In a Git
checkout, stage the new flake and `nix/` files before invoking Nix so that they
are included in its source snapshot.

First shell entry requires network access and downloads the SDK/compiler plus
an isolated Python environment under `${XDG_CACHE_HOME:-~/.cache}/cits-to-go/`.
Every Python package version and accepted artifact hash is in
`nix/requirements.lock`. The source-only esptool package uses the separately
pinned setuptools bootstrap, with build isolation disabled to prevent floating
build dependencies. Later entries reuse this environment; Rust crates are
resolved from `Cargo.lock` and downloaded on the first build. No system Python,
Rust installation or Docker is needed.

This flake supplies a development shell and build/flash **apps**, not a pure
`sandboxed nix build` firmware derivation: Python and Cargo downloads happen in
the development environment. `nix build .#toolchain` only builds the patched
Espressif compiler package. Do not run the commands with `sudo`.

Nix uses `build-nix/` for IDF and embedded Rust artifacts, and `target/nix-host/`
for host tests. Existing Docker/local `build/` files do not conflict, and an IDF
clean does not remove the host-test cache. Both flows share the project's
`sdkconfig` and write final images to `dist/`. Configure the Nix build with:

```sh
nix develop
idf.py -B build-nix -D IDF_TARGET=esp32c5 menuconfig
cits-build
```

The build and flash scripts select ESP32-C5 with `-D IDF_TARGET=esp32c5`, which
creates a fresh configuration without invoking `set-target` and its implicit
`fullclean`. An existing `build-nix/host-target/` from the earlier scripts may
stay in place; there is no need to delete `build-nix/` to recover. The Nix apps
use an explicit `path:` flake reference and select the system's development
shell, instead of passing a bare source store path to `nix develop`.

After updating the files, leave an old `nix develop` shell and enter it again to
pick up the new host-cache location, or simply run `nix run .#build` from a
regular shell. Keep your existing `flake.lock`.

For script regression checks (no hardware required):

```sh
python3 -m unittest discover -s tests -p test_build_scripts.py
```

The supplied binary was built with the same pinned Rust/IDF/GCC versions before
the Nix addition. See `VALIDATION.md` for the limits of Nix validation here.

## Flash

```sh
docker run --rm --device /dev/ttyACM0 -v "$PWD":/project -w /project \
  cits-to-go-rust idf.py -p /dev/ttyACM0 -b 921600 flash
```

Or use the merged image with the Android flasher / ESP-IDF's esptool:

```sh
esptool --chip esp32c5 --port /dev/ttyACM0 --baud 921600 \
  write-flash 0x0 dist/cits-to-go-firmware-rs.bin
```

`idf.py flash` writes the separate bootloader/partition/application segments and
preserves NVS owner bonds. Writing the **merged image at 0x0** also writes the
padding over the NVS area, so use USB enrollment again afterward. The custom
2 MiB application partition retains the original NVS and PHY offsets. Fatal NVS
initialization errors retain the original erase-and-reinitialize recovery behavior.

## Feature parity

| Existing feature | Rust implementation |
|---|---|
| C5 802.11p capture at 5900 MHz by default | Same PHY setup and metadata extraction in `main/platform.c` |
| Broadcast-only filtering, optional unicast | Rust callback admission, same Kconfig switch |
| Full raw frame capture without FCS | Same C5 `dump_len` path; non-HE fallback subtracts four FCS bytes |
| RSSI, receive state/type, timestamp, sequence | Same CTG1 type 1 header and CRC-32/IEEE |
| USB Serial/JTAG stream | IDF interrupt-backed RX, dedicated bounded output worker |
| BLE UART service | Same service/RX/TX UUIDs and `CITS-to-go` name |
| BLE MTU / fast interval | MTU 517, 7.5–15 ms requested interval, Android controls PHY request |
| USB-authorized enrollment | Same types 4/5, one-shot 30-second window, USB-only response |
| Persistent owner bond | NimBLE Secure Connections, encrypted RX, encrypted/bonded notifications |
| Replacement owner / stale-key recovery | Existing owner replaced after successful pairing; explicit enrollment needed |
| Raw TX over either transport | Same type 2 request, fixed pool, 12 Mbit/s / 11a / BW20 radio adapter |
| Correlated TX results and echo | Same type 3; successful submission echoes the supplied frame |
| Configurable LED pulse | GPIO27, active low, 35 ms by default, one-shot hardware timer service |
| Android CAM / SREM / arbitrary packet support | Payload bytes pass through unchanged; generation stays in Android |

For exact field offsets see `../firmware/README.md`. Reserved bits and unknown
TX flags retain the original handling: only flag bit 0 selects system sequencing.
Bad CRC and size errors return the original ESP-IDF error codes. Unknown types
and malformed COBS records are ignored. Capture sequence wraps as a `u32`.
Receive timestamps retain the original Wi-Fi timestamp semantics, including the
hardware timestamp's rollover; they are not converted to Unix time.

A successful result means that the private HMAC API returned success. It is not
an over-the-air acknowledgement. The echo contains submitted bytes; hardware
sequence insertion/FCS generation can modify what goes on air. The new adapter
propagates HMAC's declared `esp_err_t` return, which the supplied C implementation
ignored. That private API and its ownership assumptions still require RF bench
validation against the pinned binary libraries.

## Efficiency and overload behavior

- Packet buffers are static and bounded. Atomic leases provide exclusive access;
  queues move a lease rather than a complete frame. RX callbacks perform only
  metadata checks, one payload copy and a nonblocking enqueue.
- Each transport has its own incremental parser, with in-place COBS decoding.
  There is no global parser mutex. A 1 KiB CRC lookup table replaces the original
  two-lookups-per-byte nibble table. Output encoding streams header, payload and
  CRC directly into COBS output, eliminating the decoded output staging buffer.
- Embassy polls only ready tasks and blocks on a counting FreeRTOS notification
  when idle. No application poll tick is used. USB RX blocks on the interrupt
  driver. USB backpressure waits on notification when Rust's input pool fills.
- A dedicated radio worker contains the potentially blocking proprietary Wi-Fi
  mutex acquisition. It never blocks Embassy or the NimBLE host.
- USB and BLE output are independent. USB has four static output slots. BLE
  has a separately configurable output pool (12 slots by default), so BLE burst
  capacity is no longer coupled to the Wi-Fi RX pool. Both transports reserve
  two free output slots for control; BLE also has distinct capture/control queues
  and selects control records first at record boundaries.
- USB has one writer, so record bytes never interleave. Partial writes use one
  deadline for the whole frame and insert a delimiter before the next record.
- BLE writes and notifications are chunks of the same stream. The BLE writer
  fills each ATT notification up to the negotiated payload size and can pack
  multiple small CTG1 records into one notification. Notifications wait on
  completion signals when NimBLE buffers are exhausted, checking at 250 ms
  intervals with a one-second no-progress limit. A failed partial notification
  stream is disconnected to resynchronize. Connection generations prevent
  queued output from being delivered to a later connection with a reused handle.
  Each BLE output record has a leading delimiter to recover after subscription
  toggles as well.
- Overlong input is discarded through its delimiter. BLE RX overflow rejects
  the chunk and discards through the next delimiter. Disconnect resets partial
  BLE input. A failed chunk cannot be spliced into a valid request.
- Cooperative yields occur after short bursts of capture/input work rather than
  after every item, preserving fairness without paying a wake/poll cycle per
  packet. Radio TX already suspends on its completion signal and needs no extra
  yield.
- The activity LED starts one timer per pulse instead of restarting its timer on
  every accepted packet, avoiding high-rate timer-queue churn.

The application does not enable automatic light sleep or radio power-saving:
continuous promiscuous capture needs the receiver running. Lower CPU overhead
is an architectural goal; speed, power and packet-rate improvements have not
been measured on hardware.

All storage is finite. Sustained overload can still drop capture frames and
control responses (including write-without-response BLE input). Every parsed
request produces a result or admission error, but delivery over a disconnected
or saturated transport is not guaranteed. Android should retain its request
timeout/retry policy. Debugger-visible counters include `CITS_RX_NO_BUFFER`,
`CITS_RX_TOO_LARGE`, `CITS_BLE_INPUT_DROPS`, `CITS_OUTPUT_DROPS`,
`CITS_USB_OUTPUT_DROPS`, `CITS_BLE_OUTPUT_DROPS` and the platform
`usb_partial_drops`. No textual logs are mixed into the CTG1 byte stream.

## Configuration and memory

Run `idf.py menuconfig` → **CITS-to-go Configuration**. The original channel,
pool sizes, packet limit, LED GPIO/polarity/pulse and USB write timeout remain
available. The old USB read timeout is retained in Kconfig for compatibility but
is unused: reads now wait indefinitely for driver input.

CMake passes the generated sdkconfig to `build.rs`; Rust buffer bounds and C
bounds come from the same configuration. Standalone host tests use defaults.
The Android encoder currently caps TX at 2352 bytes; raising the firmware limit
alone does not raise that Android cap.

Default application buffer/task storage is now about 87 KiB: 8 capture slots,
4 TX slots, two 4-slot input pools, a 16 KiB Embassy task arena, 4 USB output
slots and 12 BLE output slots. These defaults trade roughly 9.6 KiB of additional
static BLE burst buffering for fewer short-stall drops; increase
queue depths only after checking the memory map and minimum runtime free heap.
The NimBLE MSYS-2 pool uses 24 blocks. Driver buffers, task stacks, the Bluetooth /
Wi-Fi heaps, and IDF static data need additional memory. The IDF main-task stack
is 8 KiB. CMake selects a 32 KiB task arena when packet capacity exceeds 2352
bytes; the normal task layouts are checked by a host test. Larger pool/packet
settings consume more SRAM and must be checked against the linked map and
runtime free heap.
`build/cits-to-go-firmware-rs.map` is the authoritative link-time memory report.

## Validation

See `VALIDATION.md` for the checks actually run for this delivery and the
remaining hardware acceptance checks. Tests exercise the actual Rust code,
including independent wire-format/CRC expectations, maximum frame sizes,
every two-chunk split of a maximum TX request, malformed inputs, enrollment
routing and concurrent pool reuse.

No new over-the-air packet generator is introduced here; CAM and SREM generators
and their JVM/PCAP/Wireshark tests remain in the unchanged Android project.

## 1 Hz diagnostic statistics

The firmware emits CTG1 type 6 once per second as a control/diagnostic record. It reports eligible Wi-Fi RX and accepted-capture rates, USB/BLE capture-output rates, transport byte/notification rates, queue occupancy, cumulative drop/error counters, and current BLE MTU/connection/PHY parameters. The periodic ESP timer only wakes an Embassy task; framing and transport submission remain outside the timer callback. See `../docs/DEBUG_DIAGNOSTICS.md` for the wire layout and counter interpretation.
