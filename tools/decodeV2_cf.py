#!/usr/bin/env python3

# This implementation is designed to be close to the implementation in the Crazyflie.
# The design is C-like to resemble the firmware as much as possible

import serial
import math
import copy

UART_FRAME_LENGTH = 12
PULSE_PROCESSOR_N_SWEEPS = 2
PULSE_PROCESSOR_N_BASE_STATIONS = 2
PULSE_PROCESSOR_N_SENSORS = 4
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
        self.sensor = None
        self.timestamp = None

        self.offset = None
        # Channel is zero indexed (0-15) here, while it is one indexed in the base station config (1 - 16)
        self.channel = None  # Valid if channelFound is true
        self.slowbit = None  # Valid if channelFound is true
        channelFound = None

class lighthouseUartFrame_t:
    def __init__(self):
        self.isSyncFrame = False
        self.data = pulseProcessorFrame_t()

class pulseProcessorV2Pulse_t:
    def __init__(self):
        self.timestamp = 0
        self.offset = 0
        self.channel = 0
        self.slowbit = 0
        self.channelFound = False  # Indicates if channel and slowbit are valid
        self.isSet = False  # Indicates that the data in this struct has been set

class pulseProcessorV2PulseWorkspace_t:
    def __init__(self):
        self.sensors = []
        for i in range(PULSE_PROCESSOR_N_SENSORS):
            self.sensors.append(pulseProcessorV2Pulse_t())
        self.latestTimestamp = 0


class pulseProcessor_t:
    def __init__(self):
        self. pulseWorkspace = pulseProcessorV2PulseWorkspace_t()

        # Refined data for multiple base stations
        self.blocksV2 = []
        for i in range(PULSE_PROCESSOR_N_BASE_STATIONS):
            self.blocksV2.append(pulseProcessorV2SweepBlock_t())

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

def processBlock(pulseWorkspace, block):
    # Check we have data for all sensors
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        if not pulseWorkspace.sensors[i].isSet:
            # The sensor data is missing - discard
            return False

    # Channel - should all be the same except one that is not set
    channel_count = 0
    block.channel = NO_CHANNEL;
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        sensor = pulseWorkspace.sensors[i]

        if sensor.channelFound:
            channel_count += 1

            if block.channel == NO_CHANNEL:
                block.channel = sensor.channel
                block.slowbit = sensor.slowbit

            if not block.channel == sensor.channel:
                # Multiple channels in the block - discard
                return False;

            if not block.slowbit == sensor.slowbit:
                # Multiple slowbits in the block - discard
                return False

    if channel_count < 1:
        # Channel is missing - discard
        return False

    # Offset - should be offset on one and only one sensor
    indexWithOffset = NO_SENSOR
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        sensor = pulseWorkspace.sensors[i]
        if not sensor.offset == NO_OFFSET:
            if indexWithOffset == NO_SENSOR:
                indexWithOffset = i
            else:
                # Duplicate offsets - discard
                return False

    if indexWithOffset == NO_SENSOR:
        # No offset found - discard
        return False

    # Calculate offsets for all sensors
    baseSensor = pulseWorkspace.sensors[indexWithOffset]
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        sensor = pulseWorkspace.sensors[i]
        if i == indexWithOffset:
            block.offset[i] = sensor.offset
        else:
            timestamp_delta = TS_DIFF(baseSensor.timestamp, sensor.timestamp)
            block.offset[i] = TS_DIFF(baseSensor.offset, timestamp_delta)

    block.timestamp = TS_DIFF(baseSensor.timestamp, baseSensor.offset)

    return True;

def storePulse(frameData, pulseWorkspace):
    sensor = frameData.sensor
    storage = pulseWorkspace.sensors[sensor]

    result = False
    if not storage.isSet:
        storage.timestamp = frameData.timestamp
        storage.offset = frameData.offset
        storage.channel = frameData.channel
        storage.slowbit = frameData.slowbit
        storage.channelFound = frameData.channelFound

        storage.isSet = True
        result = True

    return result;

def clearWorkspace(pulseWorkspace):
    for i in range(PULSE_PROCESSOR_N_SENSORS):
        pulseWorkspace.sensors[i].isSet = False

def processFrame(frameData, pulseWorkspace, block):
    result = False

    delta = TS_DIFF(frameData.timestamp, pulseWorkspace.latestTimestamp)
    isEndOfPreviuosBlock = (delta > MAX_TICKS_SENSOR_TO_SENSOR)
    if (isEndOfPreviuosBlock):
        result = processBlock(pulseWorkspace, block);
        clearWorkspace(pulseWorkspace);

    pulseWorkspace.latestTimestamp = frameData.timestamp;

    if (not storePulse(frameData, pulseWorkspace)):
        clearWorkspace(pulseWorkspace)

    return result;

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

    if latest.offset[0] < storage.offset[0]:
        return False

    # We want to check if
    # abs(latest.timestamp - storage.timestamp) < MAX_TICKS_BETWEEN_SWEEP_STARTS_TWO_BLOCKS
    # but timestamps are unsigned 24 bit unsigned integers
    if TS_DIFF(latest.timestamp + MAX_TICKS_BETWEEN_SWEEP_STARTS_TWO_BLOCKS, storage.timestamp) > MAX_TICKS_BETWEEN_SWEEP_STARTS_TWO_BLOCKS * 2:
        return False

    return True

def pulseProcessorV2ProcessPulse(state, frameData, angles):
    baseStation = None;
    axis = None;
    anglesMeasured = False

    block = pulseProcessorV2SweepBlock_t()
    if (processFrame(frameData, state.pulseWorkspace, block)):
        channel = block.channel;
        if (channel < PULSE_PROCESSOR_N_BASE_STATIONS):
            previousBlock = state.blocksV2[channel]
            if (isBlockPairGood(block, previousBlock)):
                calculateAngles(block, previousBlock, angles)

                baseStation = block.channel;
                axis = 'sweepDirection_y';
                anglesMeasured = True;
            else:
                state.blocksV2[channel] = block

    return anglesMeasured, baseStation, axis;

def processUartFrame(appState, angles, frame):
    resultOk, basestation, axis = pulseProcessorV2ProcessPulse(appState, frame.data, angles)
    if (resultOk):
        # Print all angles
        for sensor in range(PULSE_PROCESSOR_N_SENSORS):
            print("s:{} ".format(sensor), end='')
            for bs in range(PULSE_PROCESSOR_N_BASE_STATIONS):
                a = angles.sensorMeasurements[sensor].baseStationMeasurements[bs].angles
                print("[{:-6.3f}, {:-6.3f}]".format(a[0], a[1]), end='')
            print("  ", end='')
        print()

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

def print_frame(frame):
    if frame.isSyncFrame:
        print("Sync")
    else:
        if frame.data.channelFound:
            chan_slow = "{:-2d}({})".format(frame.data.channel, frame.data.slowbit)
        else:
            chan_slow = " -(-)"

        if frame.data.offset != 0:
            offset = "{:-6d}".format(frame.data.offset)
        else:
            offset = "     -"

        print("Sensor:{}  TS:{:08d}  offset:{}  Chan:{}".format(frame.data.sensor, frame.data.timestamp, offset, chan_slow))



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
            # print_frame(frame)
            processUartFrame(state, angles, frame);

        uartData = src.read(12)
