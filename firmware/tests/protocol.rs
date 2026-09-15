use cits_to_go_firmware::protocol::*;

// Independent wire oracle follows Android's CtgFrameEncoder: build a decoded
// little-endian record, append CRC-32, then COBS encode. It never generates or
// modifies an over-the-air 802.11 packet.
fn bitwise_crc(bytes: &[u8]) -> u32 {
    let mut crc = !0u32;
    for &byte in bytes {
        crc ^= byte as u32;
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xedb88320 & 0u32.wrapping_sub(crc & 1));
        }
    }
    !crc
}
fn frame(mut raw: Vec<u8>) -> Vec<u8> {
    raw.extend(bitwise_crc(&raw).to_le_bytes());
    cobs(&raw)
}
fn cobs(raw: &[u8]) -> Vec<u8> {
    let mut out = vec![0];
    let mut code_pos = 0;
    let mut code = 1;
    for &b in raw {
        if b == 0 {
            out[code_pos] = code;
            code_pos = out.len();
            out.push(0);
            code = 1;
        } else {
            out.push(b);
            code += 1;
            if code == 255 {
                out[code_pos] = code;
                code_pos = out.len();
                out.push(0);
                code = 1;
            }
        }
    }
    out[code_pos] = code;
    out.push(0);
    out
}
fn tx_raw(id: u32, bytes: &[u8]) -> Vec<u8> {
    let mut raw = b"CTG1\x01\x02\x10\x00".to_vec();
    raw.extend(id.to_le_bytes());
    raw.extend((bytes.len() as u16).to_le_bytes());
    raw.extend(1u16.to_le_bytes());
    raw.extend(bytes);
    raw
}
fn decode(bytes: &[u8]) -> Vec<u8> {
    let mut d = Decoder::new();
    let mut records = Vec::new();
    for &b in bytes {
        if let Some(r) = d.push(b) {
            records.push(r.to_vec());
        }
    }
    assert_eq!(records.len(), 1);
    records.remove(0)
}
#[test]
fn standard_crc_vector() {
    assert_eq!(crc32(b"123456789"), 0xcbf43926);
}
#[test]
fn request_survives_every_fragment_boundary() {
    let packet: Vec<_> = (0..MAX_PACKET).map(|i| i as u8).collect();
    let wire = frame(tx_raw(0x78563412, &packet));
    for split in 0..wire.len() {
        let mut d = Decoder::new();
        let mut count = 0;
        for chunk in [&wire[..split], &wire[split..]] {
            for &b in chunk {
                if let Some(r) = d.push(b) {
                    assert_eq!(
                        parse_record(r, true),
                        Request::Transmit {
                            id: 0x78563412,
                            flags: 1,
                            packet: &packet
                        }
                    );
                    count += 1;
                }
            }
        }
        assert_eq!(count, 1);
    }
}
#[test]
fn crc_and_size_errors_are_correlated() {
    let mut r = decode(&frame(tx_raw(7, &[1, 2, 3])));
    r[16] ^= 1;
    assert_eq!(
        parse_record(&r, true),
        Request::Reject {
            id: 7,
            len: 3,
            status: INVALID_CRC
        }
    );
    for packet in [vec![], vec![1; MAX_PACKET + 1]] {
        let mut raw = tx_raw(8, &packet);
        raw.extend(bitwise_crc(&raw).to_le_bytes());
        assert_eq!(
            parse_record(&raw, true),
            Request::Reject {
                id: 8,
                len: packet.len() as u16,
                status: INVALID_SIZE
            }
        );
    }
    let mut raw = tx_raw(9, &[1, 2]);
    raw[12] = 10;
    let decoded = decode(&frame(raw));
    assert_eq!(
        parse_record(&decoded, true),
        Request::Reject {
            id: 9,
            len: 10,
            status: INVALID_SIZE
        }
    );
}
#[test]
fn enrollment_is_usb_only() {
    let raw = decode(&frame(b"CTG1\x01\x04\x0c\x00\x01\x00\x00\x00".to_vec()));
    assert_eq!(parse_record(&raw, true), Request::Enroll);
    assert_eq!(parse_record(&raw, false), Request::Ignore);
}
#[test]
fn capture_matches_legacy_layout_and_crc() {
    let mut packet = vec![0x08, 0, 0, 0];
    packet.extend([255; 6]);
    packet.extend([0; 32]);
    let mut out = [0; MAX_ENCODED];
    let n = encode_capture(
        &mut out,
        0x12345678,
        5900,
        CaptureMeta {
            timestamp_us: 0x0102030405060708,
            rssi: -76,
            wifi_type: 2,
            rx_state: 0,
        },
        &packet,
    )
    .unwrap();
    let mut expected = b"CTG1\x01\x01\x20\x00\x01\x00".to_vec();
    expected.extend(0x12345678u32.to_le_bytes());
    expected.extend(0x0102030405060708u64.to_le_bytes());
    expected.extend(5900u16.to_le_bytes());
    expected.extend(42u16.to_le_bytes());
    expected.extend(42u16.to_le_bytes());
    expected.extend([(-76i8) as u8, 2, 0, 0]);
    expected.extend(packet);
    assert_eq!(&out[..n], frame(expected));
}
#[test]
fn results_echo_only_successful_payloads() {
    let mut out = [0; MAX_ENCODED];
    for status in [OK, NO_MEM, INVALID_CRC] {
        let n = encode_result(&mut out, 42, status, 3, &[0, 1, 255]).unwrap();
        let mut expected = b"CTG1\x01\x03\x14\x00".to_vec();
        expected.extend(42u32.to_le_bytes());
        expected.extend(status.to_le_bytes());
        expected.extend([3, 0, 0, 0]);
        if status == OK {
            expected.extend([0, 1, 255]);
        }
        assert_eq!(&out[..n], frame(expected));
    }
}
#[test]
fn enrollment_response_matches_android() {
    let mut out = [0; MAX_ENCODED];
    for status in [OK, NO_MEM] {
        let n = encode_enrollment(&mut out, status).unwrap();
        let mut expected = b"CTG1\x01\x05\x10\x00".to_vec();
        expected.extend(status.to_le_bytes());
        expected.extend([(status == OK) as u8, 0, 0, 0]);
        assert_eq!(&out[..n], frame(expected));
    }
}
#[test]
fn overlong_record_is_discarded_until_delimiter() {
    let valid = frame(tx_raw(1, &[5]));
    let mut d = Decoder::new();
    for _ in 0..MAX_ENCODED + 20 {
        assert!(d.push(1).is_none());
    }
    // A syntactically valid suffix must not be treated as a fresh record.
    for &b in &valid {
        assert!(d.push(b).is_none());
    }
    let mut records = 0;
    for &b in &valid {
        records += usize::from(d.push(b).is_some());
    }
    assert_eq!(records, 1);
}
#[test]
fn loss_and_reconnect_do_not_join_records() {
    let valid = frame(tx_raw(1, &[5, 6, 7]));
    let mut d = Decoder::new();
    for &b in &valid[..6] {
        assert!(d.push(b).is_none());
    }
    d.gap();
    for &b in &valid[6..] {
        assert!(d.push(b).is_none());
    }
    d.reset();
    assert!(valid.iter().any(|&b| d.push(b).is_some()));
}
#[test]
fn output_capacity_and_full_size_records() {
    for bytes in [
        vec![0; MAX_PACKET],
        vec![255; MAX_PACKET],
        (0..MAX_PACKET).map(|n| n as u8).collect(),
    ] {
        let mut out = [0; MAX_ENCODED];
        let n = encode_result(&mut out, 0, OK, MAX_PACKET as u16, &bytes).unwrap();
        let raw = decode(&out[..n]);
        assert_eq!(&raw[20..raw.len() - 4], bytes);
        assert_eq!(
            crc32(&raw[..raw.len() - 4]),
            u32::from_le_bytes(raw[raw.len() - 4..].try_into().unwrap())
        );
    }
    let needed = encode_enrollment(&mut [0; MAX_ENCODED], OK).unwrap();
    for len in 0..needed {
        assert!(encode_enrollment(&mut vec![0; len], OK).is_err());
    }
}
#[test]
fn arbitrary_malformed_streams_never_panic_and_resynchronize() {
    let mut d = Decoder::new();
    let mut state = 1234567u32;
    for _ in 0..100_000 {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        if let Some(raw) = d.push(state as u8) {
            let _ = parse_record(raw, true);
        }
    }
    d.push(0);
    let valid = frame(tx_raw(1, &[1]));
    assert!(valid.iter().any(|&b| d.push(b).is_some()));
}

#[test]
fn statistics_layout_and_crc_are_stable() {
    let stats = Statistics {
        uptime_ms: 123_456,
        sample_ms: 1_000,
        wifi_rx_pps: 77,
        capture_pps: 75,
        usb_capture_tx_pps: 74,
        ble_capture_tx_pps: 73,
        usb_bytes_per_sec: 45_000,
        ble_bytes_per_sec: 41_000,
        ble_notifications_per_sec: 92,
        rx_no_buffer_total: 2,
        rx_too_large_total: 3,
        ble_input_drops_total: 4,
        usb_output_drops_total: 5,
        ble_output_drops_total: 6,
        usb_partial_write_drops_total: 7,
        ble_notify_failures_total: 8,
        wifi_rx_packets_total: 9_000,
        capture_packets_total: 8_900,
        usb_capture_packets_total: 8_800,
        ble_capture_packets_total: 8_700,
        flags: STATS_USB_CONNECTED | STATS_BLE_CONNECTED | STATS_BLE_SECURED,
        usb_queue_depth: 1,
        usb_queue_capacity: 4,
        ble_queue_depth: 2,
        ble_queue_capacity: 12,
        ble_mtu: 517,
        ble_conn_interval: 6,
        ble_conn_latency: 0,
        ble_supervision_timeout: 400,
        ble_tx_phy: 2,
        ble_rx_phy: 2,
    };
    let mut out = [0; MAX_ENCODED];
    let n = encode_statistics(&mut out, stats).unwrap();
    let raw = decode(&out[..n]);
    assert_eq!(&raw[..8], b"CTG1\x01\x06\x70\x00");
    assert_eq!(raw.len(), STATISTICS_HEADER_LEN as usize + 4);
    assert_eq!(
        u32::from_le_bytes(raw[8..12].try_into().unwrap()),
        stats.uptime_ms
    );
    assert_eq!(
        u32::from_le_bytes(raw[16..20].try_into().unwrap()),
        stats.wifi_rx_pps
    );
    assert_eq!(
        u16::from_le_bytes(raw[100..102].try_into().unwrap()),
        stats.ble_mtu
    );
    assert_eq!(raw[108], stats.ble_tx_phy);
    assert_eq!(raw[109], stats.ble_rx_phy);
    assert_eq!(
        crc32(&raw[..raw.len() - 4]),
        u32::from_le_bytes(raw[raw.len() - 4..].try_into().unwrap())
    );
}
