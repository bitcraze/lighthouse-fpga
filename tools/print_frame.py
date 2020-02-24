#!/usr/bin/env python3

import serial
import math


def calculateAE(firstBeam, secondBeam):
    azimuth = ((firstBeam + secondBeam) / 2) - math.pi
    p = math.radians(60)
    beta = (secondBeam - firstBeam) - math.radians(120)
    elevation = math.atan(math.sin(beta/2)/math.tan(p/2))
    return (azimuth, elevation)


PERIODS = [959000, 957000,
           953000, 949000,
           947000, 943000,
           941000, 939000,
           937000, 929000,
           919000, 911000,
           907000, 901000,
           893000, 887000]

if __name__ == "__main__":
    import sys
    import struct
    if len(sys.argv) < 2:
        print("Usage: {} <input.bin or /dev/tty...>".format(sys.argv[0]))
        exit(1)

    if sys.argv[1].startswith("/dev/"):
        src = serial.Serial(sys.argv[1], 2*115200)
    else:
        src = open(sys.argv[1], "rb")


    print("Waiting for sync ...")
    sync = [b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff', b'\xff']
    syncBuffer = [b'\x00'] * len(sync)
    while sync != syncBuffer:
        b = src.read(1)
        if len(b) < 1:
            sys.exit(1)
        syncBuffer.append(b)
        syncBuffer = syncBuffer[1:]

    print("Found sync!")

    lastMeasurements = [0,] * 16

    reading = src.read(12)
    while(len(reading) == 12):
        timestamp = struct.unpack("<I", reading[9:] + b'\x00')[0]
        state = struct.unpack("<I", reading[6:9] + b'\x00')[0]
        offset = struct.unpack("<I", reading[3:6] + b'\x00')[0]
        reading = struct.unpack("<I", reading[:3] + b'\x00')[0]


        sensor = reading & 0x03
        identity = (reading >> 2) & 0x1f
        mode = identity >> 1
        found = ((reading >> 7) & 0x01) == 0
        width = (reading >> 8) & 0xffff

        if state == 0xffffff:
            reading = src.read(12)
            continue

        if (found):
            print("Sensor: {}  TS:{:06x}  Width:{:4x}  Mode:{:2}({})  SyncTime:{:-6d}  BeamWord:{:05x}\t".format(sensor, timestamp, width, (identity >> 1) + 1,
                  identity & 1, offset, state), end='')
        else:
            print("Sensor: {}  TS:{:06x}  Width:{:4x}  Mode: None  SyncTime:{:-6d}  BeamWord:{:05x}\t".format(sensor, timestamp, width, offset, state),
                  end='')

        if offset != 0:
            if offset > (lastMeasurements[mode]+10000):
                firstBeam = ((lastMeasurements[mode] * 8.0) / PERIODS[identity >> 1]) * 2 * math.pi
                secondBeam = ((offset * 8.0) / PERIODS[identity >> 1]) * 2 * math.pi
                azimuth, elevation = calculateAE(firstBeam, secondBeam)
                print("Azimuth: {:3.3f}°, Elevation: {:3.3f}°".format(math.degrees(azimuth), math.degrees(elevation)), end='')

            lastMeasurements[mode] = offset
        
        print()

        reading = src.read(12)
