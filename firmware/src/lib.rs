#![cfg_attr(target_os = "none", no_std)]
#![deny(unsafe_op_in_unsafe_fn)]

pub mod config {
    include!(concat!(env!("OUT_DIR"), "/config.rs"));
}
pub mod pool;
pub mod protocol;

#[cfg(feature = "firmware")]
mod runtime;

// This static library links into ESP-IDF; libc provides memcpy/memset and abort.
// Host tests deliberately use the standard test harness instead.
#[cfg(all(not(test), target_os = "none"))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    unsafe extern "C" {
        fn abort() -> !;
    }
    unsafe { abort() }
}
