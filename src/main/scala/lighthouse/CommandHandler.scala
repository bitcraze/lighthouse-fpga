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

/**
  * Fixed UART command receiving state machine
  *
  * This could (should?) be made more generic to allow easy addition of more
  * commands in the future
  *
  */
class CommandHandler extends Component {
    val io = new Bundle {
        val input = slave Flow(Bits(8 bits))

        val ledCommand = master Flow(Bits(8 bits))
        val resetCommand = master Flow(Bits(8 bits))
    }

    val LedCommand = 0x01
    val ResetCommand = 0xBC

    val command = Reg(Bits(8 bits))
    val argument = Reg(Bits(8 bits))
    io.ledCommand.payload := argument
    io.resetCommand.payload := argument


    val ledCommandValid = False
    io.ledCommand.valid := RegNext(ledCommandValid)
    val resetCommandValid = False
    io.resetCommand.valid := RegNext(resetCommandValid)

    val fsm = new StateMachine {
        val idle : State = new State with EntryPoint {
            whenIsActive {
                when(io.input.fire) {
                    command := io.input.payload
                    when (io.input.payload === LedCommand || io.input.payload === ResetCommand) {
                        goto(receiveArg)
                    }
                }
            }
        }

        val receiveArg = new State {
            whenIsActive {
                when(io.input.fire) {
                    argument := io.input.payload
                    when(io.input.payload =/= 0xFF) {
                        when(command === LedCommand) {
                            ledCommandValid := True
                        }
                        when(command === ResetCommand) {
                            resetCommandValid := True
                        }
                    }

                    goto(idle)
                }
            }
        }
    }
}


import spinal.sim._
import spinal.core.sim._

object CommandHandlerSim {
  def main(args: Array[String]): Unit = {
    SimConfig.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new CommandHandler).doSim{ dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.input.valid #= false
      dut.io.input.payload #= 0
      dut.clockDomain.waitRisingEdge()

      dut.io.input.payload #= 0xff
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      dut.io.input.payload #= 0x01
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      dut.io.input.payload #= 0x18
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      dut.io.input.payload #= 0xbc
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      dut.io.input.payload #= 0xcf
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      dut.io.input.payload #= 0x01
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      dut.io.input.payload #= 0xff
      dut.io.input.valid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.input.valid #= false
      dut.clockDomain.waitRisingEdge(5)

      simSuccess()
    }
  }
}
