#!/usr/bin/env python3
# import pyftdi.serialext
import struct
import serial

from pulseDecoder import PulseDecoder

import sys

TIMER_FREQUENCY = 48e6

pulseDecoders = [PulseDecoder()] * 8

if len(sys.argv) > 1:
    port = open(sys.argv[1], "rb")
else:
    # port = pyftdi.serialext.serial_for_url('ftdi://ftdi:2232h/2', baudrate=230400)
    port = serial.Serial("/dev/ttyUSB1", 230400)

print("Waiting for sync ....")
buffer = [0] * 7
sync = False
while not sync:
    data = port.read(1)
    buffer = [data[0]] + buffer[:6]
    sync = all(b != 0 for b in buffer)

print("Reading pulses ...")

while True:
    data = port.read(7)
    if len(data) < 7:
        break
    if data[-1] != 0:
        # print("Sync!")
        continue
    data = data[:-1]
    timestamp, length = struct.unpack("<IH", bytes(data))
    sensor_id = (timestamp & 0xE0000000) >> 29
    timestamp = timestamp & 0x1FFFFFFF

    decoded = pulseDecoders[sensor_id].processPulse(timestamp, length/TIMER_FREQUENCY)

    # if decoded['pulseType'] == 'sweep':
    #     typestr = "sweep"
    # else:
    #     typestr = "sync {}".format(decoded['sync'])

    # print("{} - TS: 0x{:08x}, Length: {:4d}, δ: {:7.3f}ms -- {}".format(
    #     sensor_id, timestamp, length, 0, typestr))

    if 'baseStationInfo' in decoded:
        print("Decoded baseStationInfo data frame:", decoded['baseStationInfo'])
        # sys.exit(0)

    # if 'angleMeasurement' in decoded:
    #     print("{} - Angle measured:".format(sensor_id), decoded['angleMeasurement'])

    # print("{}: {:8x} {:4x}".format(sensor_id, timestamp, length))


port.close()