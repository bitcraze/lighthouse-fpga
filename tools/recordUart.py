#!/usr/bin/env python3
# import pyftdi.serialext
import serial
import sys

# port = pyftdi.serialext.serial_for_url('ftdi://ftdi:2232h/2', baudrate=230400)
port = serial.Serial("/dev/ttyUSB1", 230400)

port.flushInput()

# Reading 80KiB of data
data = port.read(80*1024)

with open(sys.argv[1], "wb") as fd:
    fd.write(data)

port.close()
