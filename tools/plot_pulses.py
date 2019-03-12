#!/usr/bin/env python3
# Reads pulse measurements using the serial port and prints them on the console

# import pyftdi.serialext
import struct
from pulseDecoder import PulseDecoder
import serial

import matplotlib.pyplot as plt

TIMER_FREQUENCY = 24e6

# fpga = pyftdi.serialext.serial_for_url('ftdi://ftdi:2232h/2', baudrate=230400)
fpga = serial.Serial("/dev/ttyUSB1", 230400)

print("Waiting for sync ....")
buffer = [0] * 7
sync = False
while not sync:
    data = fpga.read(1)
    buffer = [data[0]] + buffer[:6]
    sync = all(b != 0 for b in buffer)

data = [0]*8

previous_timestamp = None
delta = 0

pulseDecoders = [PulseDecoder() for _ in range(8)]

x = [[0, ] * 8, ] * 2
y = [[0, ] * 8, ] * 2

print("Reading from the FPGA:")
for _ in range(1000):
    bulk = fpga.read(700)
    for _ in range(100):
        # data = fpga.read(7)
        data = bulk[:7]
        bulk = bulk[7:]
        if data[-1] != 0:
            continue
        data = data[:-1]

        timestamp, length = struct.unpack("<IH", bytes(data))

        sensor_id = (timestamp & 0xE0000000) >> 29

        decoded = pulseDecoders[sensor_id].processPulse(timestamp, length/TIMER_FREQUENCY)

        if 'angleMeasurement' in decoded:
            print("{} - Angle measured:".format(sensor_id), decoded['angleMeasurement'])
            angleMeasurement = decoded['angleMeasurement']
            if angleMeasurement['axis'] == 'X':
                x[angleMeasurement['station']][sensor_id] = angleMeasurement['angle']
            else:
                y[angleMeasurement['station']][sensor_id] = angleMeasurement['angle']

    plt.cla()
    plt.axis([-20, 0, -10, 10])
    for i in range(2):
        plt.scatter(x[i], y[i])

    plt.pause(0.001)

plt.show()
