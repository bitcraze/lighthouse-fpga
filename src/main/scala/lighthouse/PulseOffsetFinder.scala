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
                when(io.pulseIn.valid) { goto(testDelta) }
            }
        }
        val testDelta = new State {
            whenIsActive {
                when ((io.pulseIn.payload.npoly =/= 0x3f) && (((pulseDelta >> 2) > 2048) || (io.pulseIn.payload.npoly =/= lastNPoly))) {
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
                lastNPoly := io.pulseIn.payload.npoly.resized
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
            dut.clockDomain.waitRisingEdge(1000000)
            simFailure("Timeout, something got stuck!")
        }

        dut.io.pulseIn.valid #= false
        dut.io.pulseOut.ready #= false
        dut.clockDomain.waitRisingEdge(10)

        dut.io.pulseIn.payload.pulse.timestamp #= 0x0C552F
        dut.io.pulseIn.payload.pulse.width #= 0xB3
        dut.io.pulseIn.payload.beamWord #= 0x1fe72
        dut.io.pulseIn.payload.npoly #= 0x3f
        dut.io.pulseIn.valid #= true
        dut.clockDomain.waitRisingEdge(1)

        while (!dut.io.pulseOut.valid.toBoolean) {
            dut.clockDomain.waitRisingEdge(1)
        }

        dut.io.pulseOut.ready #= true
        dut.clockDomain.waitRisingEdge(1)
        dut.io.pulseOut.ready #= false
        dut.io.pulseIn.valid #= false
        dut.clockDomain.waitRisingEdge(1)


        dut.io.pulseIn.payload.pulse.timestamp #= 0x0C5808
        dut.io.pulseIn.payload.pulse.width #= 0xAD
        dut.io.pulseIn.payload.beamWord #= 0x0bd25
        dut.io.pulseIn.payload.npoly #= 12
        dut.io.pulseIn.valid #= true
        dut.clockDomain.waitRisingEdge(1)

        dut.clockDomain.waitRisingEdge(78000)

        dut.io.pulseOut.ready #= true
        dut.clockDomain.waitRisingEdge(1)
        // dut.io.pulseOut.ready #= false

        dut.io.pulseIn.payload.pulse.timestamp #= 0x0C5839
        dut.io.pulseIn.payload.pulse.width #= 0xB5
        dut.io.pulseIn.payload.beamWord #= 0x05e2e
        dut.io.pulseIn.payload.npoly #= 12
        dut.io.pulseIn.valid #= true
        dut.clockDomain.waitRisingEdge(10)
        dut.io.pulseOut.ready #= true
        dut.clockDomain.waitRisingEdge(1)
        dut.io.pulseOut.ready #= false
        // dut.clockDomain.waitRisingEdge(1)
        dut.io.pulseIn.valid #= false
        dut.clockDomain.waitRisingEdge(10)

        dut.clockDomain.waitRisingEdge(100)

        simSuccess()
    }
  }
}
