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
