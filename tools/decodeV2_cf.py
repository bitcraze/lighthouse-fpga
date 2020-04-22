#!/usr/bin/env python3

# This implementation is designed to be close to the implementation in the Crazyflie.
# The design is C-like to resemble the firmware as much as possible

import serial
import math
import copy

UART_FRAME_LENGTH = 12
PULSE_PROCESSOR_N_SWEEPS = 2
PULSE_PROCESSOR_N_BASE_STATIONS = 3
PULSE_PROCESSOR_N_SENSORS = 4
PULSE_PROCRSSOR_N_CONCURRENT_BLOCKS = 2
PULSE_PROCESSOR_N_WORKSPACE = PULSE_PROCESSOR_N_SENSORS * PULSE_PROCRSSOR_N_CONCURRENT_BLOCKS
MAX_TICKS_SENSOR_TO_SENSOR = 10000
MAX_TICKS_BETWEEN_SWEEP_STARTS_TWO_BLOCKS = 10
NO_CHANNEL = 0xff
NO_SENSOR = -1
NO_OFFSET = 0


# The cycle times from the Lighhouse base stations is expressed in a 48 MHz clock, we use 24 MHz, hence the / 2.
CYCLE_PERIODS = [
    959000 / 2, 957000 / 2,
    953000 / 2, 949000 / 2,
    947000 / 2, 943000 / 2,
    941000 / 2, 939000 / 2,
    937000 / 2, 929000 / 2,
    919000 / 2, 911000 / 2,
    907000 / 2, 901000 / 2,
    893000 / 2, 887000 / 2]


class pulseProcessorFrame_t:
    def __init__(self):
        self.sensor = 0
        self.timestamp = 0

        self.offset = 0
        # Channel is zero indexed (0-15) here, while it is one indexed in the base station config (1 - 16)
        self.channel = 0  # Valid if channelFound is true
        self.slowbit = 0  # Valid if channelFound is true
        channelFound = False

class lighthouseUartFrame_t:
    def __init__(self):
        self.isSyncFrame = False
        self.data = pulseProcessorFrame_t()

class pulseProcessorV2PulseWorkspace_t:
    def __init__(self):
        self.slots = []
        for i in range(PULSE_PROCESSOR_N_WORKSPACE):
            self.slots.append(pulseProcessorFrame_t())
        self.slotsUsed = 0
        self.latestTimestamp = 0

class pulseProcessor_t:
    def __init__(self):
        self. pulseWorkspace = pulseProcessorV2PulseWorkspace_t()

        # Refined data for multiple base stations
        self.blocksV2 = []
        for i in range(PULSE_PROCESSOR_N_BASE_STATIONS):
            self.blocksV2.append(pulseProcessorV2SweepBlock_t())

        self.tempBlocks = []
        for i in range(PULSE_PROCRSSOR_N_CONCURRENT_BLOCKS):
            self.tempBlocks.append(pulseProcessorV2SweepBlock_t())

class pulseProcessorBaseStationMeasuremnt_t:
    def __init__(self):
        self.angles = [0.0] * PULSE_PROCESSOR_N_SWEEPS
        correctedAngles = [0.0] * PULSE_PROCESSOR_N_SWEEPS
        validCount = 0

class pulseProcessorSensorMeasurement_t:
    def __init__(self):
        self.baseStationMeasurements = []
        for i in range(PULSE_PROCESSOR_N_BASE_STATIONS):
            self.baseStationMeasurements.append(pulseProcessorBaseStationMeasuremnt_t())

class pulseProcessorResult_t:
    def __init__(self):
        self.sensorMeasurements = []
        for i in range(PULSE_PROCESSOR_N_SENSORS):
            self.sensorMeasurements.append(pulseProcessorSensorMeasurement_t())

class pulseProcessorV2SweepBlock_t:
    def __init__(self):
        self.offset = [0] * PULSE_PROCESSOR_N_SENSORS
        self.timestamp = None  # Timestamp of offset start, that is when the rotor is starting a new revolution
        self.channel = None
        self.slowbit = None

def TS_DIFF(a, b):
    return (a - b) & 0x00ffffff

# We want to check if
# abs(a - b) < limit
# but timestamps are unsigned 24 bit unsigned integers
def TS_ABS_DIFF_LARGER_THAN(a, b, limit):
    return TS_DIFF(a + limit, b) > limit * 2

def processWorkspaceBlock(pulseWorkspace, blockBaseIndex, block):
    # Check that we have data for all sensors
    sensorMask = 0
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        slotNr = blockBaseIndex + i
        frame = pulseWorkspace.slots[slotNr]
        sensorMask |= 1 << frame.sensor
    if sensorMask != 0xf:
        # All sensors not present - discard
        return False

    # Channel - should all be the same or not set
    block.channel = NO_CHANNEL;
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        slotNr = blockBaseIndex + i
        sensor = pulseWorkspace.slots[slotNr]

        if sensor.channelFound:
            if block.channel == NO_CHANNEL:
                block.channel = sensor.channel
                block.slowbit = sensor.slowbit

            if not block.channel == sensor.channel:
                # Multiple channels in the block - discard
                return False;

    if block.channel == NO_CHANNEL:
        # Channel is missing - discard
        return False

    # Offset - should be offset on one and only one sensor
    indexWithOffset = NO_SENSOR
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        slotNr = blockBaseIndex + i
        sensor = pulseWorkspace.slots[slotNr]
        if not sensor.offset == NO_OFFSET:
            if indexWithOffset == NO_SENSOR:
                indexWithOffset = slotNr
            else:
                # Duplicate offsets - discard
                return False

    if indexWithOffset == NO_SENSOR:
        # No offset found - discard
        return False

    # Calculate offsets for all sensors
    baseSensor = pulseWorkspace.slots[indexWithOffset]
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        slotNr = blockBaseIndex + i
        sensor = pulseWorkspace.slots[slotNr]
        if slotNr == indexWithOffset:
            block.offset[i] = sensor.offset
        else:
            timestamp_delta = TS_DIFF(baseSensor.timestamp, sensor.timestamp)
            block.offset[i] = TS_DIFF(baseSensor.offset, timestamp_delta)

    block.timestamp = TS_DIFF(baseSensor.timestamp, baseSensor.offset)
    return True

def processWorkspace(pulseWorkspace, blocks):
    slotsUsed = pulseWorkspace.slotsUsed

    # Set channel for frames preceding frame with known channel.
    # The FPGA decoding process does not fill in this data since
    # it needs data from a second sensor to make sens of the first one
    for i in range(slotsUsed - 1):
        previousFrame = pulseWorkspace.slots[i]
        frame = pulseWorkspace.slots[i + 1]
        if (not previousFrame.channelFound):
            if frame.channelFound:
                previousFrame.channel = frame.channel
                previousFrame.slowbit = frame.slowbit
                previousFrame.channelFound = frame.channelFound

    # Handle missing channels
    # Sometimes the FPGA failes to decode the bitstream for a sensor, but we
    # can assume the timestamp is correct anyway. To know which channel it
    # has, we add the limitation that we only accept blocks with data from
    # all sensors. Further more we only accept workspaces with full consecutive
    # blocks

    # We must have at least one block
    if slotsUsed < PULSE_PROCESSOR_N_SENSORS:
        return 0

    # The number of slots used must be a multiple of the block size
    if not (slotsUsed % PULSE_PROCESSOR_N_SENSORS == 0):
        return 0

    # Process one block at a time in the workspace
    blocksInWorkspace = int(slotsUsed / PULSE_PROCESSOR_N_SENSORS)
    for blockNr in range(blocksInWorkspace):
        blockBaseIndex = blockNr * PULSE_PROCESSOR_N_SENSORS
        if not processWorkspaceBlock(pulseWorkspace, blockBaseIndex, blocks[blockNr]):
            print("Broken block")
            return 0

    return blocksInWorkspace;

def storePulse(frameData, pulseWorkspace):
    result = False
    if pulseWorkspace.slotsUsed < PULSE_PROCESSOR_N_WORKSPACE:
        slot = pulseWorkspace.slots[pulseWorkspace.slotsUsed]

        slot.sensor = frameData.sensor
        slot.timestamp = frameData.timestamp
        slot.offset = frameData.offset
        slot.channel = frameData.channel
        slot.slowbit = frameData.slowbit
        slot.channelFound = frameData.channelFound

        pulseWorkspace.slotsUsed += 1
        result = True

    return result;

def clearWorkspace(pulseWorkspace):
    pulseWorkspace.slotsUsed = 0

def processFrame(frameData, pulseWorkspace, blocks):
    nrOfBlocks = 0

    # Sensor timestamps may arrive in the wrong order, we need an abs() when checking the diff
    isFirstFrameInNewWorkspace = TS_ABS_DIFF_LARGER_THAN(frameData.timestamp, pulseWorkspace.latestTimestamp, MAX_TICKS_SENSOR_TO_SENSOR)
    if (isFirstFrameInNewWorkspace):
        nrOfBlocks = processWorkspace(pulseWorkspace, blocks);
        # print_workspace(pulseWorkspace)
        for i in range(nrOfBlocks):
            print_block(blocks[i])
        clearWorkspace(pulseWorkspace);

    pulseWorkspace.latestTimestamp = frameData.timestamp;

    if (not storePulse(frameData, pulseWorkspace)):
        print("Workspace overflow")
        clearWorkspace(pulseWorkspace)

    return nrOfBlocks, isFirstFrameInNewWorkspace;

def calculateAzimuthElevation(firstBeam, secondBeam, angles):
    a120 = math.pi * 120.0 / 180.0
    tan_p_2 = 0.5773502691896258   # tan(60 / 2)

    angles[0] = ((firstBeam + secondBeam) / 2.0) - math.pi
    beta = (secondBeam - firstBeam) - a120
    angles[1] = math.atan(math.sin(beta / 2.0) / tan_p_2)

def calculateAngles(latestBlock, previousBlock, angles):
    channel = latestBlock.channel

    for i in range(PULSE_PROCESSOR_N_SENSORS):
        firstOffset = previousBlock.offset[i]
        secondOffset = latestBlock.offset[i]
        period = CYCLE_PERIODS[channel]

        firstBeam = firstOffset * 2 * math.pi / period
        secondBeam = secondOffset * 2 * math.pi / period

        calculateAzimuthElevation(firstBeam, secondBeam, angles.sensorMeasurements[i].baseStationMeasurements[channel].angles)
        angles.sensorMeasurements[i].baseStationMeasurements[channel].validCount = 2

def isBlockPairGood(latest, storage):
    if not latest.channel == storage.channel:
        return False

    if TS_ABS_DIFF_LARGER_THAN(latest.timestamp, storage.timestamp, MAX_TICKS_BETWEEN_SWEEP_STARTS_TWO_BLOCKS):
        return False

    return True

def pulseProcessorV2ProcessPulse(state, frameData, angles):
    baseStation = None;
    axis = None;
    anglesMeasured = False

    blocks = state.tempBlocks
    nrOfBlocks, isFirstFrameInNewWorkspace = processFrame(frameData, state.pulseWorkspace, blocks)
    for blockIndex in range(nrOfBlocks):
        block = blocks[blockIndex]
        channel = block.channel;
        if (channel < PULSE_PROCESSOR_N_BASE_STATIONS):
            previousBlock = state.blocksV2[channel]
            if (isBlockPairGood(block, previousBlock)):
                calculateAngles(block, previousBlock, angles)

                baseStation = block.channel;
                axis = 'sweepDirection_y';
                anglesMeasured = True;
                print("--- second sweep chan:", channel)
            else:
                state.blocksV2[channel] = copy.copy(block)
                print("... first sweep chan:", channel)

    if nrOfBlocks == 0:
        if isFirstFrameInNewWorkspace:
            print("... bad workspace")

    return anglesMeasured, baseStation, axis;

def clear_angles(angles):
    for sensor in range(PULSE_PROCESSOR_N_SENSORS):
        for bs in range(PULSE_PROCESSOR_N_BASE_STATIONS):
            a = angles.sensorMeasurements[sensor].baseStationMeasurements[bs].angles
            a[0] = 0
            a[1] = 0

def processUartFrame(appState, angles, frame):
    clear_angles(angles)
    resultOk, basestation, axis = pulseProcessorV2ProcessPulse(appState, frame.data, angles)
    if (resultOk):
        print_angles(angles)

def getUartFrameRaw(frame, data):
    syncCounter = 0

    for d in data:
        if d == 0xff:
            syncCounter += 1

    frame.isSyncFrame = (syncCounter == UART_FRAME_LENGTH);

    first_word = struct.unpack("<I", data[:3] + b'\x00')[0]
    frame.data.sensor = first_word & 0x03
    frame.data.channelFound = ((first_word >> 7) & 0x01) == 0

    identity = (first_word >> 2) & 0x1f
    frame.data.channel = identity >> 1
    frame.data.slowbit = identity & 1

    # Offset is expressed in a 6 MHz clock, while the timestamp uses a 24 MHz clock.
    # update offset to a 24 MHz clock
    offset_6 = struct.unpack("<I", data[3:6] + b'\x00')[0]
    frame.data.offset = offset_6 * 4

    frame.data.timestamp = struct.unpack("<I", data[9:] + b'\x00')[0]

    isPaddingZero = (((data[5] | data[8]) & 0xfe) == 0);
    isFrameValid = (isPaddingZero or frame.isSyncFrame);

    return isFrameValid;

def print_frame(data):
    if data.channelFound:
        chan_slow = "{:-2d}({})".format(data.channel, data.slowbit)
    else:
        chan_slow = " -(-)"

    if data.offset != 0:
        offset = "{:-6d}".format(data.offset)
    else:
        offset = "     -"

    print("Sensor:{}  TS:{:08d}  offset:{}  Chan:{}".format(data.sensor, data.timestamp, offset, chan_slow))

def print_workspace(pulseWorkspace):
    print("*** start workspace", pulseWorkspace.slotsUsed)
    for i in range(pulseWorkspace.slotsUsed):
        frame = pulseWorkspace.slots[i]
        print_frame(frame)
    print("*** end workspace")

def print_block(block):
    print("Block: ts:", block.timestamp, "chan:", block.channel, "sb:", block.slowbit, "offset:", block.offset)

def print_angles(angles):
    for sensor in range(PULSE_PROCESSOR_N_SENSORS):
        print("s:{} ".format(sensor), end='')
        for bs in range(PULSE_PROCESSOR_N_BASE_STATIONS):
            a = angles.sensorMeasurements[sensor].baseStationMeasurements[bs].angles
            print("[{:-6.3f}, {:-6.3f}]".format(a[0], a[1]), end='')
        print("  ", end='')
    print()



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

    state = pulseProcessor_t()
    angles = pulseProcessorResult_t()

    uartData = src.read(12)
    while(len(uartData) == 12):
        frame = lighthouseUartFrame_t()
        getUartFrameRaw(frame, uartData)
        if not frame.isSyncFrame:
            processUartFrame(state, angles, frame);
            # print_frame(frame.data)

        uartData = src.read(12)
