/**
 * ,---------,       ____  _ __
 * |  ,-^-,  |      / __ )(_) /_______________ _____  ___
 * | (  O  ) |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
 * | / ,--´  |    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
 *    +------`   /_____/_/\__/\___/_/   \__,_/ /___/\___/
 *
 * Lighhouse deck FPGA
 *
 * Copyright (C) 2020 Bitcraze AB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, in version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package lighthouse

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

case class PulseWithOffset() extends Bundle {
    val offset = UInt(17 bits)
    val npoly = UInt(6 bits)
    val pulse = Pulse()
    val beamWord = Bits(17 bits)
    val id = Bits(2 bits)
}

class PulseOffsetFinder(speedMultiplier: Int = 1) extends Component {
    val io = new Bundle {
        val pulseIn = slave Stream(IdentPulseWithData())
        val pulseOut = master Stream(PulseWithOffset())
    }

    val offsetFinder = new OffsetFinder(speedMultiplier)
    offsetFinder.io.nPoly := io.pulseIn.payload.npoly.resized
    offsetFinder.io.targetState := io.pulseIn.payload.beamWord

    val lastTimestamp = Reg(UInt(24 bits))
    val pulseDelta = io.pulseIn.payload.pulse.timestamp - lastTimestamp
    val lastNPoly = Reg(UInt(6 bits))

    // Slow_clk is timing-critical and the limiting path is the 24-bit pulseDelta
    // subtraction feeding the testDelta FSM decision. Resolve the delta-derived
    // decision one cycle ahead in idle - where the input payload is already stable
    // (the stream holds it until we assert ready in sendResult) - and register it, so
    // the subtractor terminates at a flop instead of running in series with the FSM
    // branch. Mirrors the same optimisation in PulseIdentifier.
    val deltaLargeReg     = RegInit(False)   // (pulseDelta >> 2) > 2048
    val npolyIdentReg     = RegInit(False)   // npoly identified (=/= 0x3f)
    val channelChangedReg = RegInit(False)   // npoly =/= lastNPoly

    io.pulseOut.payload.pulse := io.pulseIn.payload.pulse
    io.pulseOut.payload.beamWord := io.pulseIn.payload.beamWord
    io.pulseOut.payload.id := io.pulseIn.payload.id
    io.pulseOut.payload.npoly := io.pulseIn.payload.npoly
    io.pulseOut.payload.offset := 0
    when(offsetFinder.io.found) {
        io.pulseOut.payload.offset := offsetFinder.io.offset
    }

    io.pulseOut.valid := False
    io.pulseIn.ready := False
    offsetFinder.io.start := False
    offsetFinder.io.reset := False

    val fsm = new StateMachine {
        val idle: State = new State with EntryPoint {
            whenIsActive{
                when(io.pulseIn.valid) {
                    // Latch the pulseDelta-derived decision off the critical path.
                    deltaLargeReg     := (pulseDelta >> 2) > 2048
                    npolyIdentReg     := io.pulseIn.payload.npoly =/= 0x3f
                    channelChangedReg := io.pulseIn.payload.npoly =/= lastNPoly
                    goto(testDelta)
                }
            }
        }
        val testDelta = new State {
            whenIsActive {
                // An identified pulse starts a new offset search when a long time has
                // elapsed since the last offset OR the channel changed (same condition
                // as before, now from registered signals).
                when (npolyIdentReg && (deltaLargeReg || channelChangedReg)) {
                    offsetFinder.io.start := True
                    goto(waitFinder)
                }.otherwise {
                    offsetFinder.io.reset := True
                    goto(sendResult)
                }
            }
        }
        val waitFinder = new State {
            onEntry {
                offsetFinder.io.start := True
            }
            whenIsActive {
                when(offsetFinder.io.done) {
                    goto(sendResult)
                }
            }
        }
        val sendResult = new State {
            whenIsActive {
                when(offsetFinder.io.found) {
                    lastTimestamp := io.pulseIn.payload.pulse.timestamp
                }
                // Only remember the channel of an IDENTIFIED pulse. An unidentified
                // (0x3f) pulse carries no channel, so it must not overwrite lastNPoly -
                // otherwise the next same-channel pulse looks like a channel change and
                // emits a spurious second sync offset. This happens whenever a sweep's
                // two near-simultaneous sensors arrive out of timestamp order: the later-
                // arriving but earlier-timestamped one gets a (huge, wrapped) pulseDelta,
                // is reported 0x3f, and lands between the two identified pulses. See the
                // PulseIdentifier comments and issue #14.
                when (io.pulseIn.payload.npoly =/= 0x3f) {
                    lastNPoly := io.pulseIn.payload.npoly.resized
                }
                io.pulseOut.valid := True
                when(io.pulseOut.fire) {
                    io.pulseIn.ready := True
                    offsetFinder.io.reset := True
                    goto(idle)
                }
            }
        }
    }
}


import spinal.sim._
import spinal.core.sim._


// Sensor: 1, TS: 0C552F, Width: 00B3	0 	1fe72, d: 77445 (12.9ms) | 	1fe72, d: 55285 (9.21ms) |
// Sensor: 2, TS: 0C5808, Width: 00AD	0 	0bd25, d: 77627 (12.9ms) | 	0bd25, d: 27055 (4.51ms) |
// Sensor: 3, TS: 0C5839, Width: 00B5	0 	05e2e, d: 77639 (12.9ms) | 	05e2e, d: 10681 (1.78ms) |

object PulseObjectFinderSim {
  def main(args: Array[String]): Unit = {
    SimConfig //.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new PulseOffsetFinder(4)).doSim{ dut =>
        dut.clockDomain.forkStimulus(10)

        val timeout = fork {
            dut.clockDomain.waitRisingEdge(2000000)
            simFailure("Timeout, something got stuck!")
        }

        dut.io.pulseIn.valid #= false
        dut.io.pulseOut.ready #= true
        dut.clockDomain.waitRisingEdge(10)

        // Drive one identified pulse and return the sync offset reported for it.
        // (The OffsetFinder search can take tens of thousands of cycles.)
        def pushPulse(npoly: Int, beamWord: Int, timestamp: Long, width: Int): Long = {
            dut.io.pulseIn.payload.pulse.timestamp #= timestamp
            dut.io.pulseIn.payload.pulse.width #= width
            dut.io.pulseIn.payload.beamWord #= beamWord
            dut.io.pulseIn.payload.npoly #= npoly
            dut.io.pulseIn.payload.id #= 0
            dut.io.pulseIn.valid #= true
            var guard = 0
            while (!dut.io.pulseOut.valid.toBoolean) {
                dut.clockDomain.waitRisingEdge()
                guard += 1
                if (guard > 200000) simFailure("PulseOffsetFinder stalled waiting for output")
            }
            val offset = dut.io.pulseOut.payload.offset.toLong
            dut.clockDomain.waitRisingEdge() // output fires (ready held high)
            dut.io.pulseIn.valid #= false
            dut.clockDomain.waitRisingEdge(3)
            offset
        }

        // Regression for the out-of-order residual (issue #14): within one sweep an
        // unidentified (0x3f) pulse can land between two identified same-channel pulses
        // (because the sweep's two near-simultaneous sensors arrive out of timestamp
        // order). The 0x3f pulse must NOT reset the channel tracking, otherwise the
        // second identified pulse looks like a channel change and emits a SECOND sync
        // offset - and the firmware discards any block with more than one offset.
        val channel = 12
        val base    = 0x100000L

        // p1: first identified pulse of the sweep -> emits the one legitimate offset.
        val o1 = pushPulse(channel, 0x0bd25, base,      0xAD)
        // p2: the out-of-order twin, reported unidentified (0x3f). Must not emit and
        //     must not reset the channel. Timestamp is slightly earlier (out of order).
        val o2 = pushPulse(0x3f,    0x00000, base - 7,  0x00)
        // p3: another identified pulse of the SAME sweep/channel, small positive gap.
        //     Must NOT emit a second offset.
        val o3 = pushPulse(channel, 0x05e2e, base + 50, 0xB5)

        println(f"offsets: p1=0x${o1.toHexString} p2=0x${o2.toHexString} p3=0x${o3.toHexString}")

        val failures = scala.collection.mutable.ArrayBuffer[String]()
        if (o1 == 0)  failures += "p1 (first identified pulse) should have produced a sync offset"
        if (o2 != 0)  failures += s"p2 (0x3f) must not produce an offset, got 0x${o2.toHexString}"
        if (o3 != 0)  failures += s"p3 emitted a SECOND offset (0x${o3.toHexString}); a 0x3f pulse reset the channel tracking (issue #14 out-of-order residual)"

        dut.clockDomain.waitRisingEdge(50)
        if (failures.nonEmpty) {
            simFailure("PulseOffsetFinder offset-count check failed:\n  - " + failures.mkString("\n  - "))
        }
        simSuccess()
    }
  }
}
