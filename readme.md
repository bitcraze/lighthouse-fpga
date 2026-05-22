# Lighthouse receiver [![CI](https://github.com/bitcraze/lighthouse-fpga/workflows/CI/badge.svg)](https://github.com/bitcraze/lighthouse-fpga/actions?query=workflow%3ACI)

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

The two data flow, received from the deck and sent to the deck, are independent.
The deck will send a continious flow of frame containing the received pulse
data. Commands can be sent to the deck to control LEDs and reset.

### Received from the deck

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
 - **nPoly**: The polynomial for this pulse, see note below for details.
 - **Sync Offset**: If not 0, offset from the basestation sync event and this pulse. Otherwise, no offset found.
 - **Beam Word**: Raw 17 bits data reveived on the beam
 - **Timestamp**: 24 Bits timestamp in the receiver's 24MHz clock. All sensor share the same clock.

Note on the nPoly:
 - nPoly is valid if the top bit is zero, ```(nPoly & 0x20) == 0```, otherwise no polynomial was detected for this pulse.
 - The channel of the base station is ```(nPoly / 2) + 1```, channels range from 1 to 16. Channel is also called mode in the Basestation configuration.
 - Each V2 base station uses 2 polynomials to encode one bit of 'slow data' each turn (ie. each two received sweeps). The slow bit is ```nPoly & 0x01```

### Sent to the deck

The FPGA runs a simple state machine to receive commands form the serial ports.
All command contains one command and one argument byte. The Byte 0xFF is a NOP
command and an invalid argument so it can be used to reset the command receiver
and can be sent to make sure the FPGA is ready to accept commands.

#### Set LED (0x01)

Sets the deck LED state

```
    0                   1
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |    SET_LED    |0 0| G | Y | R |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```
 - **SET_LED**: 0x01, Set LED command
 - **G, Y, B**: Control value for the Green, Yellow, Red LED. Possible values are:
   - **0**: OFF
   - **1**: Slow blink (1 hz)
   - **2**: Fast blink (4 Hz)
   - **3**: ON

#### Reset to bootloader (0xBC)

Reset the FPGA and load the bootloader configuration. Allows to access the SPI
flash to re-flash a new bitstream or to access or write settings.

```
    0                   1
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |      RESET    | TO_BOOTLOADER |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```
- **RESET**: 0xBC, Reset command
- **TO_BOOTLOADER**: 0xCF, Value to send to reset to bootloader. Currently any
  other values are ignored.


## Building and programming

To build the project you need the [Icestorm](http://www.clifford.at/icestorm/#install) toolchain. Icestorm, Yosys and Arachne-PNR needs to be installed and in the path.

This project is mainly written in Scala using [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/).
You need to have [SBT installed](https://www.scala-sbt.org/download.html) and in the path.

When icestorm and SBT are installed:
```
make
```

This builds the FPGA bitstream. If the timing does not pass, change the nextpnr seed in the makefile until it passes.
The script ```tools/search_seed.py``` can be used to automatically search for a working seed.

Two make target exists to help development: ```make generate_verilog```
generates verilog from scala, ```make bitstream``` only builds the bitstream from
the verilog (useful if the generation was launched from an IDE).

### Building with Docker

If you do not want to install the toolchain locally, you can build with the same
`bitcraze/fpga-builder` image that the CI uses. It bundles SBT, Yosys, nextpnr
and IceStorm. From the repository root:

```
docker run --rm -v $(pwd):/module bitcraze/fpga-builder ./tools/build/build
```

This mounts the working copy at `/module` and runs `tools/build/build` (which
just calls `make`), producing `lighthouse.bin` in the repository root. The build
artifacts are owned by the container user; if you hit permission issues, append
`--user $(id -u):$(id -g)` to the `docker run` command.

> Timing closure is seed-sensitive. If the build fails timing, bump `SEED` in
> the `Makefile` (or run `tools/search_seed.py`) until both clock domains pass.

If you have your deck connected to a serial port (be careful to use a 3.0V serial port, 3.3V will damage the deck!),
you can use the [integrated bootloader](https://github.com/bitcraze/lighthouse-bootloader) to program the deck:
```
<path-to-lighthouse-bootloader>/scripts/uart_bootloader.py /dev/ttyUSB0 lighthouse.bin
```

### Flashing over the air through the Crazyflie (cfcli)

When the deck is mounted on a Crazyflie you can flash the bitstream wirelessly
through the Crazyflie, without a serial adapter. The Crazyflie firmware exposes
the deck's SPI flash as a deck-firmware update target named
`deck-bcLighthouse4-fw`, so the standard Crazyflie command-line flasher can write
`lighthouse.bin` to it:

```
cfcli bootload flash --bin lighthouse.bin
```

(The same can be done from the cfclient GUI Bootloader tab, or with
`python3 -m cfloader flash lighthouse.bin deck-bcLighthouse4-fw -w <uri>`.)

> **Custom bitstreams need developer-flash mode.** Normally the Crazyflie
> firmware only accepts the exact bitstream it was built against (it checks the
> size and a compiled-in CRC, `LIGHTHOUSE_BITSTREAM_CRC`) and reboots the deck by
> power-cycling. To iterate on your own bitstream over the air, build the
> Crazyflie firmware with `CONFIG_DECK_LIGHTHOUSE_DEV_FLASH=y`. This advertises
> on-demand bootloader entry (no power cycle) and bypasses the boot-time CRC
> check so any flashed bitstream boots. It boots unverified bitstreams and must
> not be enabled in production firmware.

## Simulation

The Scala sources contain SpinalHDL simulations (the `*Sim` objects, e.g.
`PulseIdentifierSim`, `PulseObjectFinderSim`, `PolyFinderSim`, `TopLevelSim`) that
exercise the design under [Verilator](https://www.veripool.org/verilator/). This is the
"software-in-the-loop" path: you can reproduce decoding behaviour and verify a
change against captured data offline, before building a bitstream or touching
hardware.

The `fpga-builder` image ships SBT but **not** Verilator (which SpinalHDL's
`doSim` needs), so `tools/run_sim.sh` derives a local image with Verilator added
(once) and runs a simulation in it:

```
tools/run_sim.sh lighthouse.PulseIdentifierSim
```

A run prints the simulation's own output and ends in `Simulation done` (success)
or a `SimFailure` (a failed assertion). Waveforms are written to
`simWorkspace/<DUT>/test.vcd`.

> If a run fails to compile with `Specified --top-module ... was not found`,
> delete a stale workspace left by an interrupted run: `rm -rf simWorkspace`
> (it may be owned by `root` if a previous run was containerised, so `sudo`
> may be needed). It is regenerated on the next run.

### PulseIdentifierSim — channel-identification regression

`PulseIdentifierSim` replays the captured pulse stream from
[issue #14](https://github.com/bitcraze/lighthouse-fpga/issues/14) through
`PulseIdentifier` and checks, per sweep block, that all identified sensors agree
on one channel and that exactly one sync offset is produced — the conditions the
Crazyflie firmware (`pulse_processor_v2.c`) requires to accept a block. It is the
regression for near-simultaneous multi-sensor hits: when two sensors of one sweep
are hit within a few ticks the LFSR has barely advanced, so the relative
polynomial search is ambiguous and must not be trusted (see the comments in
`PulseIdentifier.scala`).

> **Timing closure is non-deterministic** with the nextpnr in `fpga-builder`:
> the same `SEED` can pass or fail timing across runs, so a closing build is not
> guaranteed by the seed alone. Re-run `make` until both clock domains pass
> (`tools/search_seed.py` also helps, but expect variance).

## Tools

### print_frame.py

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

### reboot.py

```tools/reboot.py``` sends the reset to bootloader command to the FPGA to
reboot it to bootloader. Used together with the bootloader it allows to update
the bitstream when deveopping without having to touch the deck:

```
$ tools/reboot.py /dev/ttyUSB0 && ../lighthouse-bootloader/scripts/uart_bootloader.py /dev/ttyUSB0 lighthouse.bin
Reset command sent!
Bootloader version 2
flash ID: 0xEF 0x40 0x14 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 
Comparing first and last 256 bytes ...
Different bitstream, flashing the new one ...
Erasing 64K at 0x00020000
Erasing 64K at 0x00030000
Programming ...
Booting!
```

## Contribute
Go to the [contribute page](https://www.bitcraze.io/contribute/) on our website to learn more.

### Test code for contribution
Run the automated build locally to test your code

	./tools/build/build
