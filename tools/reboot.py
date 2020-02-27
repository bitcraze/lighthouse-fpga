#!/usr/bin/env python3
# Reboots deck to bootloader
import sys
import serial

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: {} <serial_port>".format(sys.argv[0]))
        sys.exit(1)

    lhrx = serial.Serial(sys.argv[1], 230400)

    # Reset UART command receiver
    lhrx.write(b"\xff\xff")

    # Send reset command
    lhrx.write(b"\xBC\xCF")

    print("Reset command sent!")
