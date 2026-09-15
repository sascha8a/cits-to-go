use std::{env, fs, path::PathBuf};
fn main() {
    println!("cargo:rerun-if-env-changed=CITS_SDKCONFIG");
    let config = env::var("CITS_SDKCONFIG")
        .ok()
        .map(|p| {
            println!("cargo:rerun-if-changed={p}");
            fs::read_to_string(p).expect("read ESP-IDF sdkconfig")
        })
        .unwrap_or_default();
    let value = |name: &str, default: usize| -> usize {
        config
            .lines()
            .find_map(|l| l.strip_prefix(&format!("CONFIG_{name}=")))
            .map(|v| v.parse().expect("numeric Kconfig option"))
            .unwrap_or(default)
    };
    let max_packet = value("CITS_MAX_PACKET_BYTES", 2352);
    let rx = value("CITS_PACKET_POOL_SIZE", 8);
    let tx = value("CITS_TX_POOL_SIZE", 4);
    let freq = value("CITS_RX_FREQUENCY_MHZ", 5900);
    let broadcast = !config
        .lines()
        .any(|l| l == "# CONFIG_CITS_BROADCAST_ONLY is not set");
    assert!((512..=4096).contains(&max_packet));
    assert!((4..=64).contains(&rx) && (2..=32).contains(&tx));
    assert!((5860..=5900).contains(&freq));
    let out = format!("pub const MAX_PACKET: usize = {max_packet};\npub const RX_POOL: usize = {rx};\npub const TX_POOL: usize = {tx};\npub const FREQUENCY: u16 = {freq};\npub const BROADCAST_ONLY: bool = {broadcast};\n");
    fs::write(
        PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("config.rs"),
        out,
    )
    .unwrap();
}
