package lighthouse

import spinal.core._
import spinal.lib._
import spinal.lib.io.TriState

case class Pulse(timestampWidth: BitCount = 24 bits, widthWidth: BitCount = 16 bits) extends Bundle {
  val width = UInt(widthWidth)
  val timestamp = UInt(timestampWidth)
}

class PulseTimer extends Component {
  val io = new Bundle {
    val time = in UInt(24 bits)

    val e = in Bool

    val pulse = master Flow(Pulse())
  }

  val timestamp = Reg(UInt(io.pulse.timestampWidth));
  val width = Reg(UInt(io.pulse.widthWidth));

  io.pulse.payload.timestamp := timestamp;
  io.pulse.payload.width := width
  val valid = Bool
  valid := False
  io.pulse.valid := RegNext(valid)

  // Pulse begining
  when (io.e.fall()) {
    timestamp := io.time
  }

  // Pulse end
  when (io.e.rise()) {
    width := (io.time - timestamp).resized;
    valid := True
  }
}
