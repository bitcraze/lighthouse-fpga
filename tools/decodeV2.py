#!/usr/bin/env python3

import serial
import math


def calculateAE(firstBeam, secondBeam):
    azimuth = ((firstBeam + secondBeam) / 2) - math.pi
    p = math.radians(60)
    beta = (secondBeam - firstBeam) - math.radians(120)
    elevation = math.atan(math.sin(beta/2)/math.tan(p/2))
    return (azimuth, elevation)

def ts_sub(a, b):
    return (a - b) & 0x00ffffff

def ts_add(a, b):
    return (a + b) & 0x00ffffff


# The cycle times from the Lighhouse base stations is expressed in a 48 MHz clock, we use 24 MHz, hence the / 2.
PERIODS = [959000 / 2, 957000 / 2,
           953000 / 2, 949000 / 2,
           947000 / 2, 943000 / 2,
           941000 / 2, 939000 / 2,
           937000 / 2, 929000 / 2,
           919000 / 2, 911000 / 2,
           907000 / 2, 901000 / 2,
           893000 / 2, 887000 / 2]

class SweepData:
    def __init__(self, ts, width, offset, channel, slow_bit):
        self.ts = ts
        self.width = width
        self.offset = offset
        self.channel = channel
        self.slow_bit = slow_bit

    def dump(self, sensor_nr, mark=False):
        if self.channel == None:
            chan_s = ' -'
        else:
            chan_s = "{:2}".format(self.channel + 1)

        if self.slow_bit == None:
            slow_s = '-'
        else:
            slow_s = int(self.slow_bit)

        mark_s = ''
        if mark:
            mark_s = '<--'

        print("Sensor:{}  TS:{:06x}  Width:{:4}  Chan:{}({})  offset:{:-6d}  {}".format(sensor_nr, self.ts, self.width, chan_s, slow_s, self.offset, mark_s))


class SweepBlock:
    def __init__(self):
        self.sensors = [None, None, None, None]
        self.channel = None
        self.ts = None
        self.is_valid = False
        self.offset_sensor = None
        self.slow_bit = None

    def print_err(self, s):
        # Enable this print to see why a frame is discarded
        # print(s)
        # self.dump()
        pass

    def push(self, sensor, ts, width, offset, channel, slow_bit):
        if self.sensors[sensor] != None:
            return False
        self.sensors[sensor] = SweepData(ts, width, offset, channel, slow_bit)
        return True

    def process(self):
        # Check we have data for all sensors
        for sensor in self.sensors:
            if not sensor:
                self.print_err("Sensor missing - discard sweep")
                return False

        # Channel. Should all be the same except one that is None
        channel_count = 0
        for sensor in self.sensors:
            if sensor.channel != None:
                channel_count += 1

                if self.channel == None:
                    self.channel = sensor.channel
                    self.slow_bit = sensor.slow_bit

                if sensor.channel != self.channel:
                    self.print_err("Duplicate channels - discard sweep")
                    return False

        if channel_count != 3:
            self.print_err("Channel missing - discard sweep")
            return False

        # Set channel in all sensors
        for sensor in self.sensors:
            sensor.channel = self.channel
            sensor.slow_bit = self.slow_bit

        # offset. Should be offset on one and only one sensor
        self.offset_sensor = None
        for sensor in self.sensors:
            if sensor.offset:
                if self.offset_sensor:
                    self.print_err("Duplicate offset - discard sweep")
                    return False
                self.offset_sensor = sensor

        if not self.offset_sensor:
            self.print_err("No offset found - discard sweep")
            return False

        # Calculate other offsets
        for sensor in self.sensors:
            if not sensor.offset:
                ts_delta = ts_sub(sensor.ts, self.offset_sensor.ts)
                sensor.offset = ts_add(self.offset_sensor.offset, ts_delta)

        # Find fist time stamp
        for sensor in self.sensors:
            if not self.ts:
                self.ts = sensor.ts

            if sensor.ts < self.ts:
                self.ts = sensor.ts

        self.is_valid = True
        return True

    def dump(self):
        sensor_nr = 0
        for sensor in self.sensors:
            if sensor:
                mark = (sensor == self.offset_sensor)
                sensor.dump(sensor_nr, mark)
            else:
                print("Missing")

            sensor_nr += 1


class Angles:
    def __init__(self, channel):
        self.data = [None, None, None, None]
        self.channel = channel

    def set(self, sensor, azimuth, elevation):
        self.data[sensor] = (azimuth, elevation)

    def dump(self):
        sensor_nr = 0
        for d in self.data:
            print("Chan:{:2d} Sensor:{} azimuth:{:8.2f} elevation:{:8.2f}".format(self.channel + 1, sensor_nr, math.degrees(d[0]), math.degrees(d[1])))
            sensor_nr += 1


class BaseStation:
    def __init__(self, channel):
        self.channel = channel
        self.prev_block = None

    def push(self, block):
        result = None

        if block.channel != self.channel:
            print("Wrong channel!")
            return result

        if self.prev_block:
            if self.is_second_sweep(self.prev_block, block):
                result = self.process(self.prev_block, block)
                self.prev_block = None
            else:
                # print("Not second sweep, use as first sweep and wait for next sweep")
                self.prev_block = block
        else:
            self.prev_block = block

        return result

    def is_second_sweep(self, a, b):
        if a.sensors[0].offset > b.sensors[0].offset:
            return False

        dt = ts_sub(b.ts, a.ts)
        # 220000 ticks is around 180 degrees
        if dt > 220000:
            return False

        return True

    def process(self, a, b):
        # a.dump()
        # b.dump()

        result = Angles(self.channel)

        for i in range(4):
            offset0 = a.sensors[i].offset
            offset1 = b.sensors[i].offset
            period = PERIODS[self.channel]

            firstBeam = (offset0 / period) * 2 * math.pi
            secondBeam = (offset1 / period) * 2 * math.pi
            azimuth, elevation = calculateAE(firstBeam, secondBeam)

            result.set(i, azimuth, elevation)

        return result

class PulseProcessor:
    def __init__(self):
        self.block = None
        self.latest_pulse = 0

    def push(self, sensor, ts, width, offset, channel, slow_bit):
        result = None

        delta = ts_sub(ts, self.latest_pulse)
        if delta > 10000:
            if self.block:
                if self.block.process():
                    result = self.block
                self.block = None
        self.latest_pulse = ts

        if not self.block:
            self.block = SweepBlock()

        if not self.block.push(sensor, ts, width, offset, channel, slow_bit):
            print("Drop block")
            self.block = None

        return result


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

    pulse_processor = PulseProcessor()
    base_stations = []
    for i in range(16):
        base_stations.append(BaseStation(i))

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

    reading = src.read(12)

    frames_since_last_result = 0

    while(len(reading) == 12):
        timestamp = struct.unpack("<I", reading[9:] + b'\x00')[0]
        beam_word = struct.unpack("<I", reading[6:9] + b'\x00')[0]
        offset_6 = struct.unpack("<I", reading[3:6] + b'\x00')[0]
        first_word = struct.unpack("<I", reading[:3] + b'\x00')[0]

        # Offset is expressed in a 6 MHz clock, while the timestamp uses a 24 MHz clock.
        # update offset to a 24 MHz clock
        offset = offset_6 * 4

        sensor = first_word & 0x03
        width = (first_word >> 8) & 0xffff

        nPoly_ok = ((first_word >> 7) & 0x01) == 0
        if nPoly_ok:
            identity = (first_word >> 2) & 0x1f
            channel = identity >> 1
            slow_bit = identity & 1
        else:
            channel = None
            slow_bit = None

        # Sync frame, ignore it
        if offset_6 == 0xffffff:
            reading = src.read(12)
            continue

        block = pulse_processor.push(sensor, timestamp, width, offset, channel, slow_bit)
        if block:
            # print("Good")
            # block.dump()
            angles = base_stations[block.channel].push(block)
            if angles:
                angles.dump()

                print()

        reading = src.read(12)
