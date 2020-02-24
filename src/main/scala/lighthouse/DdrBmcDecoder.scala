package lighthouse

import spinal.core._
import spinal.lib._
import lighthouse._

class DdrBmcDecoder(shortDelay: TimeNumber = 124 ns, 
                    unsyncDelay: TimeNumber = 235 ns) extends Component {
    val io = new Bundle {
        val signal = in(Ddr())
        val enable = in Bool

        val output = master Flow(Bool)
        val synchronized = out Bool
    }

    assert(shortDelay.toBigDecimal < unsyncDelay.toBigDecimal)

    // Threshold value calculation. In case of non-exact result, the threshold
    // is floored
    val clockPeriod = ClockDomain.current.frequency.getValue.toTime / 2
    val sampling_delay = (shortDelay / clockPeriod).toInt - 1
    val unsync_delay = (unsyncDelay / clockPeriod).toInt - 1

    val data = Flow(Bool)
    io.output << data
    val counter = new ShiftCounter(unsync_delay)
    val synchronized = Reg(Bool) init False
    val decodingOne = Reg(Bool) init False

    io.synchronized := synchronized
    data.valid := False
    data.payload := False

    val sample = Reg(counter.sr.clone()) init 0

    when(io.signal.edge(0)) { 
        counter.reset(1)
        sample := counter.sr
    }.elsewhen(io.signal.edge(1)) {
        counter.reset(0)
        sample := counter.sr.rotateLeft(1)
    }.otherwise {
        counter.increment(2)
    }

    // Handling samples one by one in a loop
    for (sample <- 0 to 1) {
        when(!io.enable) {
            synchronized := False
        }.elsewhen(!synchronized) {
            decodingOne := False
            when(io.signal.edge(sample)) {
                synchronized := True
            }
        }.otherwise {
            when (counter >= unsync_delay) {
                synchronized := False
                decodingOne := False
            }.otherwise {
                when (io.signal.edge(sample)) {
                    // Fast edge transition, decoding 1 half of the time
                    when(counter <= sampling_delay-sample) {
                        when(decodingOne) {
                            decodingOne := False
                            data.payload := True
                            data.valid := True
                        }
                        .otherwise {
                            decodingOne := True
                        }
                    }.otherwise { // slow edge transition, decoding 0
                        // data.payload := False
                        // data.valid := True
                        // decodingOne := False
                        // when(decodingOne) {
                        //     synchronized := False
                        // }.otherwise {
                            data.payload := False
                            data.valid := True
                            decodingOne := False
                        // }   
                    }
                }
            }
        }
    }
    
}

import spinal.sim._
import spinal.core.sim._

object DdrBmcDecoderSim {
  def main(args: Array[String]): Unit = {
    
    SimConfig.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new DdrBmcDecoder).doSim{ dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      sleep(300)

      dut.clockDomain.waitEdge(8)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitEdge(8)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitEdge(8)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitEdge(16)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitEdge(8)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitEdge(7)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitEdge(8)

      dut.clockDomain.waitRisingEdge(10)

      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(10)

      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(10)

      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(10)

      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= true
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= true
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(1)
      dut.io.signal.v(0) #= false
      dut.io.signal.v(1) #= false
      dut.clockDomain.waitRisingEdge(10)
      



      sleep(100)

      simSuccess()
    }
  }
}