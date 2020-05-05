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
