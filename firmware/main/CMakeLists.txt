idf_component_register(
    SRCS "platform.c" "cits_ble.c" "tx_custom.c"
    PRIV_REQUIRES esp_driver_gpio esp_driver_usb_serial_jtag bt esp_event esp_timer esp_wifi nvs_flash
    INCLUDE_DIRS "."
)

find_program(CARGO cargo REQUIRED)
get_filename_component(RUST_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
set(RUST_TARGET "riscv32imac-unknown-none-elf")
set(RUST_TARGET_DIR "${CMAKE_BINARY_DIR}/rust-target")
# Larger configured packets need larger async task storage. The default packet
# bound fits in 16 KiB, covered by the task-layout host test.
if(CONFIG_CITS_MAX_PACKET_BYTES GREATER 2352)
    set(TASK_ARENA_BYTES 32768)
else()
    set(TASK_ARENA_BYTES 16384)
endif()
set(RUST_ARCHIVE "${RUST_TARGET_DIR}/${RUST_TARGET}/release/libcits_to_go_firmware.a")
idf_build_get_property(SDKCONFIG SDKCONFIG)
file(GLOB_RECURSE RUST_SOURCES CONFIGURE_DEPENDS "${RUST_ROOT}/src/*.rs")
add_custom_command(
    OUTPUT "${RUST_ARCHIVE}"
    COMMAND ${CMAKE_COMMAND} -E env "CARGO_TARGET_DIR=${RUST_TARGET_DIR}"
            "CITS_SDKCONFIG=${SDKCONFIG}"
            "EMBASSY_EXECUTOR_TASK_ARENA_SIZE=${TASK_ARENA_BYTES}" ${CARGO} build --locked
            --manifest-path "${RUST_ROOT}/Cargo.toml" --release
            --target "${RUST_TARGET}" --features firmware
    DEPENDS ${RUST_SOURCES} "${RUST_ROOT}/Cargo.toml" "${RUST_ROOT}/Cargo.lock"
            "${RUST_ROOT}/build.rs" "${RUST_ROOT}/rust-toolchain.toml" "${SDKCONFIG}"
    WORKING_DIRECTORY "${RUST_ROOT}"
    VERBATIM
)
add_custom_target(cits_rust_build DEPENDS "${RUST_ARCHIVE}")
add_prebuilt_library(cits_rust "${RUST_ARCHIVE}")
add_dependencies(cits_rust cits_rust_build)
target_link_libraries(${COMPONENT_LIB} PRIVATE cits_rust)
