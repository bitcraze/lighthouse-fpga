"""
Object decoding envelope pulse from a lighthouse.
"""

import binascii
import struct
import numpy as np   # Required to convert the float16 fcal

DEBUG = False

TIMER_FREQUENCY = 48e6

if DEBUG:
    def _debug(*args):
        print(*args)
else:
    def _debug(*args):
        pass

class SyncFrameDecoder:
    """
    Decode data packet in base station sync pulses
    """
    # frame receiver state machine
    WAIT_PREAMBLE = "wait_preample"
    LENGTH0 = "length0"
    LENGTH1 = "length1"
    DATA = "data"

    def __init__(self):
        # Frame decoder
        self.frameState = self.WAIT_PREAMBLE
        self.frameZeroBits = 0
        self.frameBitCount = 0
        self.framePayloadLength = 0
        self.frameLength = 0
        self.frameByte = 0
        self.frameData = b""
        self.frameByteCount = 0

    def processSyncData(self, data: int):
        """
        Process sync pulse data bit

        Returns the data frame as a byte array when one full frame is
        decoded and verified
        """
        _debug(self.frameState, data, hex(self.frameByte), self.frameBitCount)
        # Always looking for the preamble
        # This will reset the receiving state machine if the preamble is
        # received again
        if data == 0:
            self.frameZeroBits += 1
        else:
            if self.frameZeroBits == 17:
                self.frameState = self.LENGTH0
                self.frameBitCount = 0
                self.frameByte = 0
                return None
            self.frameZeroBits = 0
                
        
        # If we are not waiting for the preamble, we are receiving data bits!
        if self.frameState != self.WAIT_PREAMBLE:
            # Add bit to current byte and verify if the sync bits are in place
            b = None
            if self.frameBitCount == 16:  # Sync bit
                if data != 1:
                    self.framseState = self.WAIT_PREAMBLE
                else:
                    self.frameBitCount = 0
                    _debug("----- Sync")
                    if self.frameByteCount == self.frameLength:
                        self.frameState = self.WAIT_PREAMBLE
                        if self._checkFrameCrc(self.frameData[:self.framePayloadLength],
                                               self.frameData[-4:]):
                            return self.frameData[:self.framePayloadLength]
                        else:
                            _debug("Bad data frame CRC, dropping frame!")
                            return None
                return None
            else:  # Data bit
                self.frameByte <<= 1
                self.frameByte |= data
                self.frameBitCount += 1

                # Check if one full byte has been received
                if self.frameBitCount % 8 == 0:
                    b = self.frameByte
                    self.frameByte = 0
                else:
                    return None

            _debug(hex(b))

            if self.frameState == self.LENGTH0:
                self.framePayloadLength = b
                self.frameState = self.LENGTH1
            elif self.frameState == self.LENGTH1:
                self.framePayloadLength += b * 256
                self.frameLength = self.framePayloadLength
                # Add Padding
                if self.framePayloadLength % 2 != 0:
                    self.frameLength += 1
                # Add CRC32
                self.frameLength += 4

                self.frameData = b""
                self.frameByteCount = 0
                self.frameState = self.DATA
            elif self.frameState == self.DATA:
                self.frameData += bytes((b,))
                self.frameByteCount += 1
    
    def _checkFrameCrc(self, payload, crc):
        crc32 = binascii.crc32(payload)
        return crc32 == struct.unpack("<L", crc)[0]


class PulseDecoder:
    def __init__(self):
        self.lastSyncTime = 0
        self.lastPulseType = ''
        self.lastSyncValue = {}

        # Used to detect and keep track of master and slave
        self.masterSyncTime = 0
        self.slaveSyncTime = 0
        self.synchronized = False

        self.syncFrameDecoder = [SyncFrameDecoder() for _ in range(2)]

    def processPulse(self, timestamp: int, length: float) -> dict:
        """
        Process a pulse and returns new information acquired from this pulse
        as a dictionary.

        This function should be called for _every_ pulse received by the diode
        in sequence.
        """
        result = {}

        type = self._classifyPulse(timestamp, length)
        result['pulseType'] = type

        if type.startswith('sync'):
            # Decode and save raw sync data
            skip, data, axis = self._syncPulseData(length)
            result['sync'] = {
                'station': 1 if type == 'sync1' else 0,
                'skip': skip,
                'data': data,
                'axis': axis
            }

            if not skip:
                self.lastPulseType = 'sync'
                self.lastSyncValue = result['sync']
                self.lastSyncTime = timestamp

            # Decode data frame
            if type != 'sync':
                frame = self.syncFrameDecoder[int(type[-1])].processSyncData(data)
                if frame:
                    result['syncFrame'] = frame
                    result['baseStationInfo'] = self._decodeBaseStationInfo(frame)
        elif type == 'sweep':
            delta = ((timestamp - self.lastSyncTime) & 0x1fffffff) / TIMER_FREQUENCY
            if self.lastPulseType == 'sync' and delta > 1e-3 and delta < 9e-3:
                angle = (delta - 4e-3) * 120 * 180
                result['angleMeasurement'] = {
                    'station': self.lastSyncValue['station'],
                    'axis': self.lastSyncValue['axis'],
                    'angle': angle,
                }

        return result
    
    def _classifyPulse(self, timestamp: int, length: float) -> str:
        if length < 50e-6:
            return 'sweep'
        elif not self.synchronized:
            # Delta since last candidate master
            delta = ((timestamp - self.masterSyncTime) & 0x1fffffff) / TIMER_FREQUENCY
            # print(delta)
            if delta > 0.3e-3 and delta < 0.5e-3:
                # If we just got a slave pulse, we are synchronized!
                self.slaveTime = timestamp
                self.synchronized = True
                return 'sync1'
            else:
                # Otherwise, this is a candidate master
                self.masterSyncTime = timestamp
                return 'sync'
        else:
            # Delta since last master
            delta = ((timestamp - self.masterSyncTime) & 0x1fffffff) / TIMER_FREQUENCY
            if delta > 8.2e-3 and delta < 8.4e-3:
                self.masterSyncTime = timestamp
                return 'sync0'
            elif delta > 0.3e-3 and delta < 0.5e-3:
                self.slaveSyncTime = timestamp
                return 'sync1'
            else:
                # If the pulse is not where we expected it, we are desynchronized!
                # TODO: This might be a bit harsh, we should be able to track for a bit
                # longer time, though OOTX should be fairly safe so it is good for now
                self.synchronized = False
                return 'sync'

    def _syncPulseData(self, length: float) -> tuple:
        ticklength48 = int(length*48e6)
        if ticklength48 > 6500:
            ticklength48 = 6500
        pulseData = int((ticklength48 - 2750) / 500)
        skip = (pulseData & 0x04) != 0
        data = 0 if (pulseData & 0x02) == 0 else 1
        axis = "X" if (pulseData & 0x01) == 0 else "Y"

        return (skip, data, axis)

    def _decodeBaseStationInfo(self, frame):
        # Format documented on https://github.com/nairol/LighthouseRedox/blob/master/docs/Base%20Station.md#base-station-info-block

        info = {}

        # Check protocol version
        info['protocol_version'] = frame[0] & 0x1F
        if info['protocol_version'] != 6:
            return info  # We can only decode frame from protocol version 6
        
        unpacked = struct.unpack("<HLHHHHBBHHBBBHHHHBB", frame)

        info['firmware_version'] = unpacked[0] >> 6
        info['ID'] = unpacked[1]
        info['fcal.0.phase'] = float(np.uint16(unpacked[2]).view(np.float16))
        info['fcal.1.phase'] = float(np.uint16(unpacked[3]).view(np.float16))
        info['fcal.0.tilt'] = float(np.uint16(unpacked[4]).view(np.float16))
        info['fcal.1.tilt'] = float(np.uint16(unpacked[5]).view(np.float16))
        info['sys.unlock_count'] = unpacked[6]
        info['hw_version'] = unpacked[7]
        info['fcal.0.curve'] = float(np.uint16(unpacked[8]).view(np.float16))
        info['fcal.1.curve'] = float(np.uint16(unpacked[9]).view(np.float16))
        info['accel.dir_x'] = unpacked[10]
        info['accel.dir_y'] = unpacked[11]
        info['accel.dir_z'] = unpacked[12]
        info['fcal.0.gibphase'] = float(np.uint16(unpacked[13]).view(np.float16))
        info['fcal.1.gibphase'] = float(np.uint16(unpacked[14]).view(np.float16))
        info['fcal.0.gibmag'] = float(np.uint16(unpacked[15]).view(np.float16))
        info['fcal.1.gibmag'] = float(np.uint16(unpacked[16]).view(np.float16))
        info['mode.current'] = ("A", "B", "C")[unpacked[17]]
        info['sys.fault'] = unpacked[18]

        return info


    
