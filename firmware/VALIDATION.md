# Validation — 2026-09-01

> **2026-09-03 transport/debug note:** The full-link measurements below are the
> 2026-09-01 baseline and predate the USB/BLE throughput changes and type-6
> statistics telemetry. Current defaults use 12 BLE output slots. The current
> workspace passed the build-script regressions plus standalone Kotlin protocol/
> statistics decode-dispatch checks; a complete Android Gradle build and updated
> ESP-IDF/Rust firmware link were not possible in this sandbox because their
> pinned toolchains are unavailable offline. See `../docs/TRANSPORT_PERFORMANCE_REFACTOR.md`
> and `../docs/DEBUG_DIAGNOSTICS.md`.

## Completed

- **14 host tests passed** with the normal 2352-byte packet limit, including
  actual Embassy task-storage sizing, concurrent buffer-pool reuse, CRC/COBS,
  CTG1 layouts, enrollment routing, bad lengths/CRC, all two-chunk splits of a
  maximum request, stream resynchronization and 100,000 malformed input bytes.
- **14 host tests passed** with the configurable 4096-byte packet limit and
  the larger task arena.
- **Rustfmt check passed.** Clippy checked the firmware and all host test targets
  with warnings denied. Shell syntax validation passed for `scripts/build.sh`.
- **Full ESP32-C5 application and bootloader compiled and linked**, including
  Rust/Embassy, the new platform adapter, NimBLE adapter and private radio adapter.
- **ESP-IDF image/partition size checks passed.** A merged image was created with
  esptool and its application/partition segments checked against build outputs.
- At the time of this 2026-09-01 baseline, the original Android and C firmware
  files were unchanged; later 2026-09-03 transport/debug work modifies Android
  and the Rust firmware platform adapter as documented above.

Build versions:

| Component | Version |
|---|---|
| ESP-IDF | v6.1-dev, `f21b4c238152dc9e3a24fbad9afe33a3d15f6cfd` |
| C toolchain | Espressif GCC 15.2.0, `esp-15.2.0_20250929` |
| Rust | 1.88.0 |
| Target | `riscv32imac-unknown-none-elf` |
| Embassy executor / sync | 0.7.0 / 0.6.2 |
| esptool used for this artifact | 5.3.1 |

The full build used the tagged IDF source and its pinned submodules. The Docker
recipe uses the original repository's immutable IDF image digest; the Docker
recipe itself was not executed here. This environment lacks `/proc`, so local
compiler launch wrappers were needed, and optional IDF component-manager process
inspection was disabled. No application dependency requires managed components.
The installed compiler backends and project source were compiled normally.

## Nix addition

- Added native Linux x86-64/ARM64 development shells and `nix run .#build` /
  `nix run .#flash` apps, with a separately patched Espressif GCC derivation.
- SDK, nixpkgs, rust-overlay and compiler archive pins are explicit; Python
  versions and artifact hashes were taken from PyPI release metadata for the
  environment that built the supplied firmware.
- Both `.nix` files passed the tree-sitter Nix syntax parser. Bash syntax checks
  passed for build, flash and environment activation scripts.
- Pip resolved the entire Python lock successfully using `--require-hashes`,
  `--no-build-isolation` and the same binary/source policy as shell activation.
- Fresh Python environment activation completed with the actual bootstrap and
  lock files on x86-64/Python 3.12; `pip check` reported no broken requirements.
- The build wrapper passed a recording-tool check with a custom directory
  containing spaces, the three host checks, merging and checksum creation.
- Exercised the flash wrapper using a recording IDF stand-in: verified build
  directory, a port containing whitespace, baud selection, and rejected missing,
  extra or malformed arguments. The emitted action is `flash`, with no mass
  erase or merged-image write.
- A normal Nix evaluation/build **could not be run** in this workspace: Nix
  aborts at startup because `/proc/self/exe` is unavailable. Nix store builds,
  the ARM64 shell, and physical USB flashing still need validation on a Linux
  machine with Nix. The existing full firmware compilation results above do
  not constitute a Nix build test. No `flake.lock` was fabricated: immutable
  input URLs pin first use, and Nix generates the lock file on first invocation.

## First-build and Nix app fixes

The reported first-build failures exposed two issues in the original Nix
addition: Cargo's host cache occupied `build-nix/` before IDF's `set-target`
triggered `fullclean`, and the app wrapper supplied a bare source store path to
`nix develop`.

- Build and flash now select the chip with `-D IDF_TARGET=esp32c5`, without a
  target-reset action. Host Cargo output now lives in `target/nix-host/`.
- Both Nix apps explicitly select
  `path:<source>#devShells.<system>.default`. This follows the flake output
  selection described in the [Nix develop manual](https://nix.dev/manual/nix/2.28/command-ref/new-cli/nix3-develop).
- **Real pinned ESP-IDF configuration passed** in a fresh project copy with no
  sdkconfig or CMake cache and a pre-existing `build-nix/host-target/` sentinel.
  IDF generated an ESP32-C5 sdkconfig and build files, retaining the sentinel.
  The check used `idf.py -D IDF_TARGET=esp32c5 reconfigure`; a second full firmware
  compilation was not needed to validate this initialization fix.
- **Three script regression tests passed**, covering first build and rebuild
  with the old Cargo-cache layout, first flash with a populated build directory
  and a spaced port name, and rejection of invalid flash arguments before IDF.
- Nix syntax and Bash syntax checks passed. Full Nix execution and physical
  flashing remain unverified here because of the workspace limitations above.

## Linked memory and image size

| Item | Bytes |
|---|---:|
| Application `.bin` | 1,029,824 |
| Factory application partition | 2,097,152 |
| HP SRAM used by linked sections | 221,326 |
| HP SRAM remaining before runtime allocations | 99,602 |
| Application `.bss` including IDF | 103,720 |
| HP SRAM instruction sections | 101,786 |
| HP SRAM initialized data | 15,820 |

These are linker results, **not measurements of runtime free heap**, packet
throughput, latency or energy use. Runtime stack/driver allocations still need
to fit in available SRAM. The defaults were reduced to 8 capture slots, 4 TX
slots, 4 USB output slots, 12 BLE output slots and a 16 KiB task arena to preserve
headroom. Capture and control transport queues are independently bounded.

`dist/cits-to-go-firmware-rs.bin` is the merged image for offset **0x0**.
`dist/SHA256SUMS` contains its checksum. Flashing that merged image covers the
NVS gap as well: perform USB BLE enrollment afterward. Use `idf.py flash` with
separate build segments when retaining existing owner bonds is important.

## Hardware acceptance still required

No XIAO ESP32-C5 was attached. These checks are not claimed as passed:

1. Boot, Wi-Fi/BLE initialization and continuous idle/capture operation. Inspect
   minimum free heap and task stack high-water marks after both radio stacks
   start, during saturated traffic and after reconnects; verify no watchdog reset.
2. Compare USB and BLE captures with the original firmware/reference receiver,
   including frame bytes, FCS removal, RSSI, channel, timestamps and filtering.
3. Exercise Android CAM, SREM and arbitrary-frame submission in the intended
   RF test setup. Verify actual on-air bytes/channel/rate with an independent
   receiver, both sequence flag values, and error/result correlation. HMAC
   submission success does not establish that a frame was received over the air.
4. Verify first enrollment, reboot with persistent bond, unknown-peer rejection,
   30-second expiry, failed replacement preserving the old owner, successful
   replacement and explicitly authorized stale-key recovery.
5. Stall USB reads while BLE is active, congest BLE while USB is active, flood
   requests, toggle notifications, and disconnect in the middle of records.
   Confirm framing recovers, memory remains bounded and the other link progresses.
6. Measure CPU time, packet drops, TX-result latency and power against the C
   firmware under identical traffic before claiming quantified improvements.

The private 802.11p PHY/HMAC entry points are inherited from the original
implementation. Their structure size assertions and successful final link check
layout and symbol availability; they do not replace radio validation.
