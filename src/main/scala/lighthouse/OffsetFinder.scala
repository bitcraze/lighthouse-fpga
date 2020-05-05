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

import lighthouse.constants

/**
  * Finds the offset between the start of the LFSR-generated sequence and a
  * given state. The LFSR is initialized at 0x00001.
  *
  * The full search takes up to (2^17)/speedMultiplier cycles. When
  * speedMultipler > 1, multiple LFSR are running in parallel starting
  * from different point in the LFSR-generated sequence. The state for each
  * starting point is pre-calculated and stored in a memory (in the FPGA this
  * ends-up in 2 BRAMs/LFSR)
  */
class OffsetFinder(speedMultiplier: Int = 1) extends Component {
    val io = new Bundle {
        // Control
        val start = in Bool
        val done = out Bool
        val reset = in Bool

        // Input/setup
        val nPoly = in UInt(5 bits)
        val targetState = in Bits(17 bits)

        // Output
        val found = out Bool
        val offset = out UInt(17 bits)
    }

    assert(isPow2(speedMultiplier))

    // Preparing the Poly and start-state LUT memory
    var lfsrLut = new Array[Bits](constants.Polys.length * speedMultiplier)

    // For each poly
    for (i <- 0 until constants.Polys.length) {
        // First address is the poly coefficients
        lfsrLut(i * speedMultiplier) = B(constants.Polys(i), 17 bits)

        // The next addresses is the start state for counter>1
        for (j <- 1 until speedMultiplier) {
            val counterBits = 17 - log2Up(speedMultiplier)
            val startOffset = j * (1 << counterBits)
            val startState = SoftLfsr.getStateAtOffset(constants.Polys(i), startOffset)

            // println(s"$i $j Start offset: ${startOffset.toHexString}, start state: ${startState.toHexString}")
            lfsrLut((i * speedMultiplier) + j) = B(startState, 17 bits)
        }
    }

    val done = Bits(speedMultiplier bits)
    done := B(0).resized
    val found = RegInit(False)
    val offset = RegInit(U(0, 17 bits))

    io.done := done.orR
    io.found := found
    io.offset := offset

    for (i <- 0 until speedMultiplier) {
        val counterBits = 17 - log2Up(speedMultiplier) bits

        val lfsrMem = Mem(lfsrLut)
        val lfsrMemAddress = lfsrMem.addressType()
        val lfsrMemRead = lfsrMem.readSync(lfsrMemAddress)

        lfsrMemAddress := 0
        val fsm = new StateMachine {
            val poly = Reg(Bits(17 bits))
            val state = Reg(Bits(17 bits))
            val counter = Reg(UInt(counterBits))

            val idle: State = new State with EntryPoint {
                whenIsActive {
                    found := False
                    counter := 0
                    lfsrMemAddress := io.nPoly << log2Up(speedMultiplier)
                    when(io.start) {
                        goto(setupPoly)
                    }
                }
            }
            val setupPoly: State = new State {
                whenIsActive {
                    poly := lfsrMemRead
                    if (i == 0) {
                        state := 1
                        goto(search)
                    } else {
                        lfsrMemAddress := (io.nPoly << log2Up(speedMultiplier)) | i
                        goto(setupState)
                    }
                }
            }
            val setupState: State =  new State {
                whenIsActive {
                    state := lfsrMemRead
                    goto(search)
                }
            }
            val search: State = new State {
                whenIsActive {
                    when(state === io.targetState) {
                        found := True
                        offset := (B(i, log2Up(speedMultiplier) bits) ## counter).asUInt
                        goto(doneState)
                    }.elsewhen((counter === counter.maxValue) || found) {
                        goto(doneState)
                    }.otherwise {
                        counter := counter + 1
                        // Running LFSR
                        val b = (state & poly).xorR
                        state := state(0 until state.getBitsWidth-1) ## b.asBits
                    }
                }
            }
            val doneState: State = new State {
                whenIsActive {
                    lfsrMemAddress := io.nPoly << log2Up(speedMultiplier)
                    done(i) := True
                    when(io.start) {
                        found := False
                        goto(setupPoly)
                    }
                    when(io.reset) {
                        goto(idle)
                    }
                }
            }
        }
    }
}

import spinal.sim._
import spinal.core.sim._

// Sensor: 2, TS: 0C5808, Width: 00AD	0 	0bd25, d: 77627 (12.9ms) | 	0bd25, d: 27055 (4.51ms) |

object OffsetFinderSim {
  def main(args: Array[String]): Unit = {
    SimConfig //.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new OffsetFinder(4)).doSim{ dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.reset #= false
      dut.io.nPoly #= 12
      dut.io.targetState #= 0x0bd25

      dut.clockDomain.waitRisingEdge()
      dut.io.start #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.start #= false

      dut.clockDomain.waitRisingEdge(140000)

      simSuccess()
    }
  }
}
