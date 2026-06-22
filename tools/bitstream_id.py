#!/usr/bin/env python3
"""Print Crazyflie firmware identification constants for a Lighthouse bitstream.

The Crazyflie firmware validates the Lighthouse deck FPGA image with the
zlib-compatible CRC32 implementation in src/utils/src/crc32.c and the exact byte
length compiled into src/deck/drivers/interface/lighthouse.h.
"""
import argparse
from pathlib import Path
import sys
import zlib


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Print LIGHTHOUSE_BITSTREAM_* defines for a lighthouse.bin file."
    )
    parser.add_argument("bitstream", type=Path, help="Path to lighthouse.bin")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        data = args.bitstream.read_bytes()
    except OSError as exc:
        print(f"error: cannot read {args.bitstream}: {exc}", file=sys.stderr)
        return 1

    crc = zlib.crc32(data) & 0xFFFFFFFF
    print(f"#define LIGHTHOUSE_BITSTREAM_CRC 0x{crc:08x}")
    print(f"#define LIGHTHOUSE_BITSTREAM_SIZE {len(data)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
