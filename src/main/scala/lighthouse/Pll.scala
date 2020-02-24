package lighthouse

import spinal.core._
import spinal.lib._

class pll extends BlackBox{
    val io = new Bundle{
        val clock_in    = in Bool
        val clock_out   = out Bool
        val locked = out Bool
    }

    noIoPrefix()

    addRTLPath("./rtl/pll.v")
}
