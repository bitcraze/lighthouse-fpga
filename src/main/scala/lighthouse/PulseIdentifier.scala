package lighthouse

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

case class PulseWithData() extends Bundle {
    val id = Bits(2 bits)
    val pulse = Pulse()
    val beamWord = Bits(17 bits)
}

case class IdentPulseWithData() extends Bundle {
    val npoly = UInt(6 bits)
    val pulse = Pulse()
    val beamWord = Bits(17 bits)
    val id = Bits(2 bits)
}

class PulseIdentifier extends Component {
    val io = new Bundle {
        val pulseIn = slave Stream(PulseWithData())

        val pulseOut = master Stream(IdentPulseWithData())
    }

    val polyFinder = new PolyFinder
    polyFinder.io.done.ready := True

    val lastState = Reg(Bits(17 bits))
    polyFinder.io.startState := lastState
    polyFinder.io.targetState := io.pulseIn.payload.beamWord

    val lastTimestamp = Reg(UInt(24 bits))
    val pulseDelta = io.pulseIn.payload.pulse.timestamp - lastTimestamp
    polyFinder.io.maxTick := ((pulseDelta >> 2) + 2).resized

    val nPoly = Reg(UInt(6 bits))
    io.pulseOut.npoly := nPoly.resized

    io.pulseOut.payload.pulse := io.pulseIn.payload.pulse
    io.pulseOut.payload.beamWord := io.pulseIn.payload.beamWord
    io.pulseOut.payload.id := io.pulseIn.payload.id

    val fsm = new StateMachine {
        io.pulseOut.valid := False
        io.pulseIn.ready := False
        polyFinder.io.start.valid := False
        
        val idle: State = new State with EntryPoint {
            whenIsActive{
                when(io.pulseIn.valid) { goto(testDelta) }
            }
        }
        val testDelta = new State {
            whenIsActive {
                when ((pulseDelta >> 2) < 1024) {
                    polyFinder.io.start.valid := True
                    goto(waitFinder)
                }.otherwise {
                    nPoly := 0x3f
                    goto(sendResult)
                }
            }
        }
        val waitFinder = new State {
            onEntry {
                polyFinder.io.start.valid := True
            }
            whenIsActive {
                when(polyFinder.io.done.fire) {
                    when(polyFinder.io.found) {
                        nPoly := polyFinder.io.polyFound.resized
                    }.otherwise {
                        nPoly := 0x3f
                    }
                    goto(sendResult)
                }
            }
        }
        val sendResult = new State {
            whenIsActive {
                lastTimestamp := io.pulseIn.payload.pulse.timestamp
                lastState := io.pulseIn.payload.beamWord
                io.pulseOut.valid := True
                when(io.pulseOut.fire) { 
                    io.pulseIn.ready := True
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

object PulseIdentifierSim {
  def main(args: Array[String]): Unit = {
    SimConfig.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new PulseIdentifier).doSim{ dut =>
        dut.clockDomain.forkStimulus(10)
        

        dut.io.pulseIn.valid #= false
        dut.io.pulseOut.ready #= true
        dut.clockDomain.waitRisingEdge(10)
        
        dut.io.pulseIn.payload.pulse.timestamp #= 0x0C552F
        dut.io.pulseIn.payload.pulse.width #= 0xB3
        dut.io.pulseIn.payload.beamWord #= 0x1fe72
        dut.io.pulseIn.valid #= true
        dut.clockDomain.waitRisingEdge(1)
        dut.io.pulseIn.valid #= false
        dut.clockDomain.waitRisingEdge(10)

        dut.io.pulseIn.payload.pulse.timestamp #= 0x0C5808
        dut.io.pulseIn.payload.pulse.width #= 0xAD
        dut.io.pulseIn.payload.beamWord #= 0x0bd25
        dut.io.pulseIn.valid #= true
        dut.clockDomain.waitRisingEdge(1)
        dut.io.pulseIn.valid #= false
        dut.clockDomain.waitRisingEdge(10)


        dut.clockDomain.waitRisingEdge(200)

        simSuccess()
    }
  }
}