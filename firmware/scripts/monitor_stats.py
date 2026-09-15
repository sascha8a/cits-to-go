#!/usr/bin/env python3
"""Print CTG1 firmware statistics received over USB CDC."""

import argparse
import datetime
import struct
import time
import zlib

import serial


def cobs_decode(encoded: bytes) -> bytes:
    decoded = bytearray()
    offset = 0
    while offset < len(encoded):
        code = encoded[offset]
        offset += 1
        end = offset + code - 1
        if code == 0 or end > len(encoded):
            raise ValueError("malformed COBS record")
        decoded.extend(encoded[offset:end])
        offset = end
        if code < 0xFF and offset < len(encoded):
            decoded.append(0)
    return bytes(decoded)


def cobs_encode(decoded: bytes) -> bytes:
    encoded = bytearray(b"\x00")
    code_offset = 0
    code = 1
    for value in decoded:
        if value == 0:
            encoded[code_offset] = code
            code_offset = len(encoded)
            encoded.append(0)
            code = 1
        else:
            encoded.append(value)
            code += 1
            if code == 0xFF:
                encoded[code_offset] = code
                code_offset = len(encoded)
                encoded.append(0)
                code = 1
    encoded[code_offset] = code
    return bytes(encoded)


def first_pcap_packet(path: str) -> bytes:
    with open(path, "rb") as capture:
        header = capture.read(24)
        if header[:4] == b"\xd4\xc3\xb2\xa1":
            byte_order = "<"
        elif header[:4] == b"\xa1\xb2\xc3\xd4":
            byte_order = ">"
        else:
            raise ValueError("only classic PCAP input is supported")
        packet_header = capture.read(16)
        if len(packet_header) != 16:
            raise ValueError("PCAP contains no packets")
        captured_len = struct.unpack_from(byte_order + "I", packet_header, 8)[0]
        packet = capture.read(captured_len)
        if len(packet) != captured_len or not packet or len(packet) > 2352:
            raise ValueError("invalid PCAP packet length")
        return packet


def tx_request(request_id: int, packet: bytes) -> bytes:
    frame = bytearray(16 + len(packet) + 4)
    struct.pack_into("<4sBBHIHH", frame, 0, b"CTG1", 1, 2, 16, request_id, len(packet), 1)
    frame[16:-4] = packet
    struct.pack_into("<I", frame, len(frame) - 4, zlib.crc32(frame[:-4]))
    return cobs_encode(frame) + b"\x00"


def print_statistics(frame: bytes) -> None:
    if len(frame) != 116 or frame[:6] != b"CTG1\x01\x06":
        return
    if struct.unpack_from("<H", frame, 6)[0] != 112:
        return
    expected_crc = struct.unpack_from("<I", frame, 112)[0]
    if zlib.crc32(frame[:112]) != expected_crc:
        return

    values = struct.unpack_from("<21I8H2B", frame, 8)
    flags = values[20]
    stamp = datetime.datetime.now().astimezone().isoformat(timespec="milliseconds")
    print(
        f"{stamp} uptime={values[0]}ms sample={values[1]}ms "
        f"wifi={values[2]}pps capture={values[3]}pps "
        f"usb={values[4]}pps/{values[6]}Bps "
        f"ble={values[5]}pps/{values[7]}Bps/{values[8]}nps "
        f"ble_q={values[23]}/{values[24]} notify_fail={values[15]} "
        f"flags=0x{flags:02x} mtu={values[25]} "
        f"interval={values[26] * 1.25:g}ms latency={values[27]} "
        f"timeout={values[28] * 10}ms phy={values[29]}/{values[30]}",
        flush=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("device", nargs="?", default="/dev/ttyACM0")
    parser.add_argument("--duration", type=float, default=0, help="seconds; 0 runs until interrupted")
    parser.add_argument("--tx-pcap", help="repeatedly transmit the first PCAP packet")
    parser.add_argument("--tx-rate", type=float, default=10, help="transmit requests per second")
    args = parser.parse_args()

    deadline = time.monotonic() + args.duration if args.duration else None
    packet = first_pcap_packet(args.tx_pcap) if args.tx_pcap else None
    tx_interval = 1 / args.tx_rate
    next_tx = time.monotonic()
    request_id = 1
    record = bytearray()
    with serial.Serial(args.device, 115200, timeout=0.25) as port:
        while deadline is None or time.monotonic() < deadline:
            if packet is not None and time.monotonic() >= next_tx:
                port.write(tx_request(request_id, packet))
                request_id = (request_id + 1) & 0xFFFFFFFF
                next_tx += tx_interval
            for value in port.read(4096):
                if value != 0:
                    if len(record) < 8192:
                        record.append(value)
                    continue
                if record:
                    try:
                        print_statistics(cobs_decode(record))
                    except ValueError:
                        pass
                    record.clear()


if __name__ == "__main__":
    main()
