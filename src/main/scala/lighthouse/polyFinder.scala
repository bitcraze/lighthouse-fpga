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

import lighthouse.Lfsr
import lighthouse.constants

class PolyFinder extends Component {

    val io = new Bundle {
        val startState = in Bits(17 bits)
        val targetState = in Bits(17 bits)
        val maxTick = in UInt(10 bits)

        val start = slave Event

        val found = out Bool
        val polyFound = out UInt(5 bits)
        val done = master Event
    }

    val searching = RegInit(False)
    val found = RegInit(B(0, constants.Polys.length bits))

    io.polyFound := OHToUInt(found)
    io.done.valid := searching.fall()
    io.found := found.orR
    io.start.ready := True

    val counter = RegInit(U(0, io.maxTick.getBitsWidth bits))
    when (searching) {
        counter := counter - 1

        when (counter === 0) {
            searching := False
            found := 0
        }
    }.otherwise {
        counter := io.maxTick
    }

    when (io.start.fire) {
        searching := True
        found := 0
    }

    val states = Vec(Bits(17 bits), constants.Polys.length)

    for (i <- 0 to constants.Polys.length - 1) {
        val lfsr = new Lfsr(constants.Polys(i), 17)
        states(i) := lfsr.state

        when (io.start.fire) {
            lfsr.state := io.startState
        }

        when (searching) {
            lfsr.iterate()

            when ((counter(2 to counter.getBitsWidth-1) === 0) && (lfsr.state === io.targetState)) {
                found(i) := True
                searching := False
            }
        }
    }
}


import spinal.sim._
import spinal.core.sim._

object PolyFinderSim {
  def main(args: Array[String]): Unit = {
    SimConfig.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new PolyFinder).doSim{ dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.startState #= 0x1fe72
      dut.io.targetState #= 0x0bd25
      dut.io.maxTick #= 183
      dut.io.start.valid #= false

      dut.clockDomain.waitRisingEdge()
      dut.io.start.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.start.valid #= false

      dut.clockDomain.waitRisingEdge(200)

      simSuccess()
    }
  }
}
