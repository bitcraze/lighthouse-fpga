package lighthouse

import spinal.core._
import spinal.lib._

class ts4231Configurator extends BlackBox{
    val io = new Bundle{
        val clk = in Bool

        val reconfigure = in Bool
        val configured = out Bool

        val d_in = in Bool
        val d_out = out Bool
        val d_oe = out Bool

        val e_in = in Bool
        val e_out = out Bool
        val e_oe = out Bool
    }

    noIoPrefix()

    addRTLPath("./rtl/ts4231Configurator.v")

    //Map the current clock domain to the io.clk pin
    mapClockDomain(clock=io.clk)
}
