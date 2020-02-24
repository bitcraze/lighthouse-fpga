# Lighthouse receiver

This project contains a FPGA implementation for the [Bicraze Lighthouse deck](https://www.bitcraze.io/lighthouse-positioning-deck/) FPGA.
It is using the TS4231 light to digital converter and targets a Lattice ice40up5k FPGA.

## Architecture

This project is designed to receive signals from lighthouse basestation V1 and V2. 

For each pulses detected on the light sensors:
 - Arrival timestamp and width is measured and the first 17 bits of data encoded in the beam carrier is received
 - If the time since the last received pulse is low enough, a search for the
   LFSR polynomial that could have generated the beam data is launched.
 - If the pulse polynomial has been identified and the previous identified pulse
   time is far enough, a search for the offset between the sync time and the
   pulse time is run. The result of this serch is the time in a 6MHz clock between
   the basestation sync event and the pulse.
 - Sensor ID, Timestamp, Width, Beamdata and Sync Offset are sent over the serial
   port to the Crazyflie

For Lightouse V1, only the timing information are interesting. For V2, the pulse
identity and sync offset are used.

## UART Protocol

Frames of 12 bytes are sent to the UART. The baudrate is 230400 baud.
Twice per second, an 'all-ones' frame of 12 bytes with value 0xFF is sent.
This is used as a synchronization frame to detect frame boundary.

The padding bits with value '0' shall be checked each time a frame is received:
if the padding bits are not '0', the receiver is not synchronized on frame
boundary anymore and should wait for the next 'all-ones' frame.

The frame represent one received IR pulse. All number are represented in
little-endian form. The frame format is:
```
    0                   1                   2       
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |SID|   nPoly   |          Width                |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |          Sync Offset            |0 0 0 0 0 0 0|
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |          Beam Word              |0 0 0 0 0 0 0|
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                 Timestamp                     |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

 - **SID**: Sensor ID, sensor on which the pulse was received
 - **Width**: 16 Bits width of the pulse in a 24MHz clock.
 - **nPoly**: if < 0x200, index of the polynomial detected for this pulse. Otherwise, no polynomial detected.
 - **Sync Offset**: If not 0, offset from the basestation sync event and this pulse. Otherwise, no offset found.
 - **Beam Word**: Raw 17 bits data reveived on the beam
 - **Timestamp**: 24 Bits timestamp in the receiver's 24MHz clock. All sensor share the same clock.

Note on the nPoly:
 - Each V2 basestation uses 2 polynomials to encode one bit of 'slow data' each turn (ie. each two received sweep)
 - The slow bit is ```nPoly & 0x01```
 - The mode of the basestation is ```(nPoly / 2) + 1```

## Building and programming

To build the project you need the [Icestorm](http://www.clifford.at/icestorm/#install) toolchain. Icestorm, Yosys and Arachne-PNR needs to be installed and in the path.

This project is mainly written in Scala using [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/).
You need to have [SBT installed](https://www.scala-sbt.org/download.html) and in the path.

When icestorm and SBT are installed:
```
make
```

This builds the FPGA bitstream. If the timing does not pass, change the nextpnr seed in the makefile until it passes.

When developping, ```make generate_verilog && make``` can be used to regenerate verilog from scala/spinalHDL.

If you have your deck connected to a serial port (be careful to use a 3.0V serial port, 3.3V will damage the deck!),
you can use the [integrated bootloader](https://github.com/bitcraze/lighthouse-bootloader) to program the deck:
```
<paht-to-lighthouse-bootloader>/scripts/uart_bootloader.py /dev/ttyUSB0 lighthouse.bin
```

## Tools

The ```print_frame.py``` script in the tools folder can print decoded frame:

```
$ ./tools/print_frame.py /dev/ttyUSB0
Waiting for sync ...
Found sync!
Sensor: 1  TS:5431ed  Width: 13d  Mode: None  SyncTime:     0  BeamWord:128aa
Sensor: 0  TS:543291  Width: 141  Mode: 1(0)  SyncTime: 28992  BeamWord:1a639
Sensor: 3  TS:5436c4  Width: 147  Mode: 1(0)  SyncTime:     0  BeamWord:1f5dc
Sensor: 2  TS:543769  Width: 140  Mode: 1(0)  SyncTime:     0  BeamWord:026f3
Sensor: 0  TS:56bcd9  Width: 120  Mode: None  SyncTime:     0  BeamWord:1b903
Sensor: 1  TS:56be78  Width: 110  Mode: 1(0)  SyncTime: 70714  BeamWord:175a5   Azimuth: -30.285°, Elevation: 4.576°
Sensor: 2  TS:56c094  Width: 113  Mode: 1(0)  SyncTime:     0  BeamWord:05879
Sensor: 3  TS:56c21a  Width: 12a  Mode: 1(0)  SyncTime:     0  BeamWord:1c094
Sensor: 1  TS:5b82f5  Width: 13f  Mode: None  SyncTime:     0  BeamWord:0debe
Sensor: 0  TS:5b8395  Width: 144  Mode: 1(1)  SyncTime: 28992  BeamWord:11c42
Sensor: 3  TS:5b87c8  Width: 146  Mode: 1(1)  SyncTime:     0  BeamWord:194c5
Sensor: 2  TS:5b8870  Width: 144  Mode: 1(1)  SyncTime:     0  BeamWord:19e30
Sensor: 0  TS:5e0dcf  Width: 118  Mode: None  SyncTime:     0  BeamWord:19864
Sensor: 1  TS:5e0f71  Width: 119  Mode: 1(1)  SyncTime: 70711  BeamWord:1511f   Azimuth: -30.290°, Elevation: 4.568°
Sensor: 2  TS:5e1189  Width: 113  Mode: 1(1)  SyncTime:     0  BeamWord:0d239
Sensor: 3  TS:5e1314  Width: 127  Mode: 1(1)  SyncTime:     0  BeamWord:08c31
(...)
```
