# Lighthouse receiver [![Build Status](https://travis-ci.org/bitcraze/lighthouse-fpga.svg)](https://travis-ci.org/bitcraze/lighthouse-fpga)

This project contains a FPGA implementation for the [Bicraze Lighthouse deck](https://www.bitcraze.io/lighthouse-positioning-deck/) FPGA.
It is using the TS4231 light to digital converter and targets a Lattice ice40up5k FPGA.

## Architecture

This design measure pulse timestamp and width from the light receivers.

The pulses are measured in a 48MHz clock and transmited over serial port.

## Building and programming

To build the project you need the [Icestorm](http://www.clifford.at/icestorm/#install) toolchain. Icestorm, Yosys and Arachne-PNR needs to be installed and in the path.

When icestorm is installed:
```
make N_SENSORS=4 UART_BAUDRATE=230400 VERSION="1"
make prog
```

This builds the FPGA bitstream with all 4 sensor activated and setting the baudrate for the UART and the version of the bitstream is set to "1".

Sensors from 0 to N_SENSORS-1 will be activated and pulses measured on them transmitted over UART.

```N_SENSORS=4 UART_BAUDRATE=230400``` are the default value if make is run without any argument.

## UART Protocol

Measurements are transmitted on UART0 of the lighthouse deck, the UART going to the Crazyflie deck port.

The Uart protocol is framed as follow:
 - The data is sent in packet or 6 Bytes followed by one byte of value 0x00. So a frame is 7 bytes long.
 - Twice a second 7 bytes with value 0xff are send, this allows to synchronize the receiver
 - The data are little-endian

The frame format is:
 - UINT32 containing Sensor ID and timestamp
   - Bit 31 to 29 is sensor ID, from 0 to 7
   - Bit 28 to 0 is the timestamp of the pulse start
 - UINT16 containing the pulse length
 - One byte at 0x00

## Tools

The tools folder contains a couple of useful tools.

__tools/readUartPulses.py__ reads pulse data from the FPGA and
decodes and displays them. If the script is runned without argument it reads from UART, otherwise it reads from a dump of UART data. Exemple:
```
$ ./readUartPulses.py 
Waiting for sync ....
Reading pulses ...
5:  4b7a019   fe
2:  4b7a154  19c
0:  4b7a1fe  1a7
4:  4b7a343  16a
3:  4b7a3bb  19a
1:  4b7a454  1ab
6:  4b7a61e  10a
5:  4b94864  5fa
3:  4b94863  600
1:  4b94862  602
2:  4b94863  601
0:  4b94863  605
4:  4b94862  600
6:  4b94863  601
4 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 3.9608999999999894}
4:  4bad094   d6
0 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 3.890699999999994}
0:  4bad046  13e
1 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 3.9591000000000003}
1:  4bad092  141
5 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 4.546799999999995}
5:  4bad31f   55
6 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 4.605300000000003}
6:  4bad360   e4
2 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 4.981499999999999}
2:  4bad502  13f
3 - Angle measured: {'station': 0, 'axis': 'X', 'angle': 5.051699999999994}
3:  4bad550  141
```

__tools/recordUart.py__ records 80KiB of UART data in the file given as argument.