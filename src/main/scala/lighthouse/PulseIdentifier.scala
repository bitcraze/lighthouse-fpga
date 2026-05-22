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

    val nPoly = RegInit(U(0x3f, 6 bits))
    io.pulseOut.npoly := nPoly.resized

    // Slow_clk is timing-critical (~0.5 ns slack on master) and the limiting
    // path is the 24-bit pulseDelta subtraction feeding the testDelta FSM
    // decision / PolyFinder control. Everything derived from pulseDelta is
    // resolved one cycle ahead in the idle state - where the input payload is
    // already stable (the stream holds it until we assert ready in sendResult)
    // - and registered, so the subtractor terminates at a flop instead of
    // running in series with the FSM branch.
    val maxTickReg    = RegInit(U(0, 10 bits))
    val deltaSmallReg = RegInit(False)
    polyFinder.io.maxTick := maxTickReg

    // Two sensors of the SAME sweep can be hit within a few ticks of each other
    // (the beam crosses neighbouring sensors almost together). The relative poly
    // search then has to identify the channel from only a step or two of LFSR
    // advance - and after k steps all 32 polynomials collapse onto at most 2^k
    // possible states, so a 1-2 step advance matches many polynomials at once and
    // PolyFinder returns a multi-hot / garbage result. (Issue #14: the s3->s0 pair
    // lands ~7-11 ticks apart, b=5051 -> b=20204 = two LFSR steps, and s0 gets a
    // garbage channel, producing three sync offsets in a block instead of one.)
    //
    // Detect "too close to identify" purely on the time gap - NOT on bit-identical
    // beam words, which a real near-simultaneous hit does not produce - and never
    // run the degenerate search for it. What we emit depends on the predecessor
    // (see testDelta):
    //  - identified   -> inherit its channel (same sweep => same channel)
    //  - unidentified -> clean 0x3f so the firmware back-fills the channel from a
    //                    later sensor in the block. A garbage multi-hot value would
    //                    instead look like a conflicting channel and make the firmware
    //                    drop the whole block - the failure when the close pair leads
    //                    the sweep (its predecessor is the previous, unrelated sweep).
    //
    // The threshold sits in the empty band between a sweep's genuine sensor-to-sensor
    // spacing (hundreds of ticks - reliably identifiable) and the few-tick skew of a
    // near-simultaneous hit, and well below the inter-sweep gap, so it never merges
    // two different sweeps.
    //
    // This handles the close pair when it arrives IN timestamp order. If the pair
    // arrives out of order (the arbiter is not time-sorted), the second pulse's
    // pulseDelta wraps hugely negative, falls through to 0x3f, and lands between two
    // identified pulses - the complementary half of the fix lives in PulseOffsetFinder
    // (a 0x3f must not reset the channel). See issue #14.
    val sameSweepMaxDelta = 64                                  // ticks (~16 LFSR steps)
    val tooClose          = pulseDelta < sameSweepMaxDelta
    val twinIdentified    = nPoly =/= 0x3f
    val tooCloseReg       = RegInit(False)
    val twinIdentifiedReg = RegInit(False)

    io.pulseOut.payload.pulse := io.pulseIn.payload.pulse
    io.pulseOut.payload.beamWord := io.pulseIn.payload.beamWord
    io.pulseOut.payload.id := io.pulseIn.payload.id

    val fsm = new StateMachine {
        io.pulseOut.valid := False
        io.pulseIn.ready := False
        polyFinder.io.start.valid := False

        val idle: State = new State with EntryPoint {
            whenIsActive{
                when(io.pulseIn.valid) {
                    // Latch the pulseDelta-derived control off the critical path.
                    maxTickReg := ((pulseDelta >> 2) + 2).resized
                    deltaSmallReg := (pulseDelta >> 2) < 1024
                    tooCloseReg := tooClose
                    twinIdentifiedReg := twinIdentified
                    goto(testDelta)
                }
            }
        }
        val testDelta = new State {
            whenIsActive {
                when (tooCloseReg) {
                    // Same sweep, too close to identify: skip the degenerate search.
                    // Inherit the predecessor's channel if it had one, otherwise emit
                    // a clean 0x3f for the firmware to back-fill.
                    when (!twinIdentifiedReg) {
                        nPoly := 0x3f
                    }
                    goto(sendResult)
                }.elsewhen (deltaSmallReg) {
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


object PulseIdentifierSim {
  // Unidentified channel marker emitted by PulseIdentifier (and expected by the
  // firmware, which back-fills it from a later identified sensor in the block).
  val UNIDENTIFIED = 0x3f

  // Firmware constant (pulse_processor_v2.c): a gap larger than this starts a new
  // sweep block / workspace. Used here only to group the capture into blocks.
  val MAX_TICKS_SENSOR_TO_SENSOR = 10000

  // ---------------------------------------------------------------------------
  // Ground-truth capture from issue #14 (one LH2 base station, bitstream V6):
  //   (sensor, timestamp, beamWord)
  // The FPGA decodes the beamWords correctly; the bug is purely in *channel
  // identification* of near-simultaneous hits. In every "s2,s3,s0,s1" sweep the
  // s3->s0 pair lands ~7-11 ticks apart (1-2 LFSR steps) - far too short for the
  // relative LFSR search to disambiguate the channel, so s0 gets a garbage
  // (multi-hot) channel. Downstream that turns into THREE sync offsets per block
  // where the firmware needs exactly one, and the block is discarded.
  // https://github.com/bitcraze/lighthouse-fpga/issues/14
  // ---------------------------------------------------------------------------
  val issue14: Seq[(Int, Long, Int)] = Seq(
    (2, 1542904, 30816), (3, 1543436,   5051), (0, 1543443,  20204), (1, 1543963, 73176),
    (0, 1888039, 22965), (2, 1888263,  76990), (1, 1888629,  26758), (3, 1888867,113005),
    (2, 2021403, 43684), (3, 2021936,  72301), (0, 2021946,  27060), (1, 2022462, 97120),
    (0, 2366539, 22965), (2, 2366763,  76990), (1, 2367129,  26758), (3, 2367367,113005),
    (2, 2499902, 21842), (3, 2500435,  72301), (0, 2500445,  27060), (1, 2500961, 97120),
    (0, 2845038, 91172), (2, 2845262, 113868), (1, 2845627,  92464), (3, 2845862, 22486),
    (2, 2978399, 30816), (3, 2978931,   5051), (0, 2978939,  20204), (1, 2979457, 73176),
    (0, 3323534, 91172), (2, 3323759, 113868), (1, 3324124,  92976), (3, 3324360, 22486),
    (2, 3456896, 30816), (3, 3457428,   5051), (0, 3457435,  20204), (1, 3457956, 73176),
    (0, 3802034, 45930), (2, 3802256,  76990), (1, 3802622,  26758), (3, 3802860,113005),
    (2, 3935394, 21842), (3, 3935926, 101686), (0, 3935937,  27060), (1, 3936454, 97120)
  )

  // ---------------------------------------------------------------------------
  // Hardware capture from the FIXED bitstream (single base station, "bad"
  // sensor orientation), in arbiter ARRIVAL order: (sensor, timestamp, beamData).
  // Here the close pair arrives OUT OF timestamp order - the later-arriving sensor
  // has the earlier timestamp - so its pulseDelta wraps to a huge value and it is
  // reported 0x3f. That 0x3f lands between two identified same-channel pulses and,
  // unless PulseOffsetFinder ignores it, makes the second one emit a spurious second
  // sync offset (the residual after the in-order fix). See issue #14.
  // ---------------------------------------------------------------------------
  val residualCapture: Seq[(Int, Long, Int)] = Seq(
    (2, 6749778, 93524), (0, 6750263, 78759), (1, 6884217, 44427), (0, 6884698, 36113),
    (3, 6884693, 18056), (2, 6885160, 107634), (3, 7228809, 128029), (1, 7229283, 111953),
    (2, 7229274, 93524), (0, 7229759, 78759), (1, 7363713, 44427), (0, 7364195, 72226),
    (3, 7364190, 18056), (2, 7364655, 84196), (3, 7708305, 116608), (1, 7708778, 115643),
    (2, 7708769, 94446), (0, 7709252, 128035), (1, 7843210, 42838), (0, 7843691, 8169),
    (3, 7843687, 69620), (2, 7844153, 116871), (3, 8187799, 116608), (1, 8188273, 57821),
    (2, 8188264, 47223), (0, 8188747, 128035), (1, 8322704, 86955), (0, 8323185, 8169),
    (3, 8323180, 69620), (2, 8323648, 116871), (3, 8667292, 116608), (1, 8667765, 57821),
    (2, 8667757, 47223), (0, 8668239, 128035), (1, 8802196, 42838), (0, 8802678, 8169),
    (3, 8802671, 34810), (2, 8803139, 58435), (3, 9146783, 116608), (1, 9147254, 57821),
    (2, 9147248, 47223), (0, 9147731, 128035), (1, 9281686, 86955), (0, 9282167, 8169),
    (3, 9282162, 34810), (2, 9282631, 116871), (3, 9626274, 64014), (1, 9626748, 111953),
    (2, 9626738, 46762), (0, 9627223, 78759), (1, 9761175, 87749), (0, 9761658, 36113),
    (3, 9761651, 9028), (2, 9762118, 107634), (3, 10105766, 64014), (1, 10106241, 111953),
    (2, 10106230, 46762), (0, 10106716, 78759), (1, 10240667, 87749), (3, 10241144, 9028),
    (0, 10241151, 36113), (2, 10241611, 107634), (3, 10585259, 64014), (1, 10585733, 111953)
  )

  def main(args: Array[String]): Unit = {
    SimConfig.allOptimisation
            .addSimulatorFlag("-I../../sim_rtl")
            .withWave
            .compile (new PulseIdentifier).doSim{ dut =>
        dut.clockDomain.forkStimulus(10)

        // Guard against a stuck FSM hanging the run.
        val timeout = fork {
            dut.clockDomain.waitRisingEdge(2000000)
            simFailure("Timeout, the PulseIdentifier FSM got stuck!")
        }

        dut.io.pulseIn.valid #= false
        dut.io.pulseOut.ready #= true
        dut.clockDomain.waitRisingEdge(10)

        // Push a pulse and return the npoly reported on its output beat.
        def pushPulse(timestamp: Long, width: Int, beamWord: Int): Int = {
            dut.io.pulseIn.payload.pulse.timestamp #= timestamp
            dut.io.pulseIn.payload.pulse.width #= width
            dut.io.pulseIn.payload.beamWord #= beamWord
            dut.io.pulseIn.valid #= true
            while (!dut.io.pulseOut.valid.toBoolean) {
                dut.clockDomain.waitRisingEdge()
            }
            val npoly = dut.io.pulseOut.npoly.toInt
            // Output fires this cycle (ready held high); release and advance.
            dut.clockDomain.waitRisingEdge()
            dut.io.pulseIn.valid #= false
            dut.clockDomain.waitRisingEdge(5)
            npoly
        }

        val failures = scala.collection.mutable.ArrayBuffer[String]()

        // Absolute 24-bit timestamp difference (timestamps wrap at 2^24 and can arrive
        // slightly out of order), matching the firmware's TS_ABS_DIFF_LARGER_THAN.
        def absTsDiff(a: Long, b: Long): Long = {
            var d = (a - b) & 0xFFFFFFL
            if (d > 0x800000L) d -= 0x1000000L
            if (d < 0) -d else d
        }

        // Group a (sensor, timestamp, npoly) stream into sweep blocks the way the
        // firmware does: a gap larger than MAX_TICKS_SENSOR_TO_SENSOR starts a new block.
        def groupBlocks(res: Seq[(Int, Long, Int)]): Seq[Seq[Int]] = {
            val bs = scala.collection.mutable.ArrayBuffer[Seq[Int]]()
            var cur = scala.collection.mutable.ArrayBuffer[Int]()
            for (i <- res.indices) {
                if (i > 0 && absTsDiff(res(i)._2, res(i - 1)._2) > MAX_TICKS_SENSOR_TO_SENSOR) {
                    bs += cur.toSeq; cur = scala.collection.mutable.ArrayBuffer[Int]()
                }
                cur += i
            }
            if (cur.nonEmpty) bs += cur.toSeq
            bs.toSeq
        }

        // Software model of PulseOffsetFinder's emit rule (kept in lock-step with
        // PulseOffsetFinder.scala): a sync offset is emitted for a pulse when it is
        // identified AND (a long time has elapsed since the last offset OR the channel
        // changed from the previous identified pulse). lastTimestamp only advances when
        // an offset is emitted. `resetOn3f` selects the buggy (true: a 0x3f pulse resets
        // the channel) vs fixed (false: 0x3f is ignored) behaviour, so the same model
        // reproduces and verifies the fix without the slow OffsetFinder ROM.
        def emitOffsets(res: Seq[(Int, Long, Int)], resetOn3f: Boolean): IndexedSeq[Boolean] = {
            var lastOffsetTs = 0L
            var lastNPoly    = 0
            res.toIndexedSeq.map { case (_, t, np) =>
                val delta = (t - lastOffsetTs) & 0xFFFFFFL
                val emit  = (np != UNIDENTIFIED) && (((delta >> 2) > 2048) || (np != lastNPoly))
                if (emit) lastOffsetTs = t
                if (resetOn3f || np != UNIDENTIFIED) lastNPoly = np
                emit
            }
        }

        // =====================================================================
        // Regression 1: replay the issue #14 capture through PulseIdentifier.
        // =====================================================================
        println("Regression 1: issue #14 capture")
        // (sensor, timestamp, reported npoly)
        val results = issue14.map { case (s, t, b) => (s, t, pushPulse(t, 0xB0, b)) }

        val emitsOffset = emitOffsets(results, resetOn3f = false)
        val blocks      = groupBlocks(results)

        blocks.zipWithIndex.foreach { case (idxs, bi) =>
            val rows = idxs.map { i =>
                val (s, t, np) = results(i)
                f"s$s t:$t%-8d npoly=${np}%2d (0x${np.toHexString}) ${if (emitsOffset(i)) "OFFSET" else ""}"
            }
            println(s"  block $bi: " + rows.mkString(" | "))

            val ids = idxs.map(results(_)._3)
            val identifiedChannels = ids.filter(_ != UNIDENTIFIED).toSet
            // Firmware channel rule: a block with two different channels is discarded.
            if (identifiedChannels.size > 1) {
                failures += s"block $bi reports conflicting channels ${identifiedChannels.map("0x"+_.toHexString)} - firmware would discard it"
            }
            // Issue #14 symptom: exactly one sync offset per block.
            val nOffsets = idxs.count(emitsOffset(_))
            if (nOffsets != 1) {
                failures += s"block $bi produced $nOffsets sync offsets, expected exactly 1 (issue #14)"
            }
        }

        // The capture is a single base station (per issue #14), whose two sweeps use two
        // polynomials - so at most two distinct channels should ever be identified. A
        // garbage (multi-hot) channel from the degenerate search shows up as a third value.
        val identifiedNpolys = results.collect { case (_, _, np) if np != UNIDENTIFIED => np }
        if (identifiedNpolys.isEmpty) {
            failures += "no pulse in the capture was identified at all"
        } else if (identifiedNpolys.distinct.size > 2) {
            failures += s"more than two channels identified for one base station: ${identifiedNpolys.distinct.sorted.map("0x"+_.toHexString)} (garbage channel?)"
        }

        // =====================================================================
        // Regression 2: a simultaneous pair that LEADS the sweep. The #14 capture
        // only shows the pair mid-sweep (predecessor identified); here the pair's
        // predecessor is the unrelated previous sweep, so it is unidentified. The
        // second hit must then emit a clean 0x3f for the firmware to back-fill, not
        // a garbage channel. Uses a realistic ~8-tick skew with non-identical beams
        // two LFSR steps apart, mirroring the #14 magnitudes.
        // =====================================================================
        println("Regression 2: simultaneous pair leading the sweep")
        val channel  = 12
        val poly     = lighthouse.constants.Polys(channel)
        val startOff = 1000
        def beamAt(off: Int): Int = SoftLfsr.getStateAtOffset(poly, startOff + off)
        def expect(label: String, got: Int, want: Int): Unit = {
            val tag = if (want == UNIDENTIFIED) "0x3f" else want.toString
            if (got != want) failures += s"$label: got npoly=$got (0x${got.toHexString}), expected $tag"
            println(f"  $label%-22s npoly=$got%2d (0x${got.toHexString}) expected $tag")
        }
        val base = 0x600000L // far from the previous timestamps -> first pulse unidentified
        val p1 = pushPulse(base,        0xB0, beamAt(0))   // first-of-sweep -> 0x3f
        val p2 = pushPulse(base + 8,    0xB0, beamAt(2))   // +8 ticks, +2 steps -> must be 0x3f
        val p3 = pushPulse(base + 600,  0xB0, beamAt(150)) // well separated -> identified
        val p4 = pushPulse(base + 1200, 0xB0, beamAt(300)) // well separated -> identified
        expect("lead.p1 first-of-sweep", p1, UNIDENTIFIED)
        expect("lead.p2 leading twin",   p2, UNIDENTIFIED)
        expect("lead.p3 identified",     p3, channel)
        expect("lead.p4 identified",     p4, channel)

        // =====================================================================
        // Regression 3: real out-of-order hardware capture end-to-end. Replay it
        // through PulseIdentifier (real npoly) and model the offset stage both ways.
        // The buggy model (a 0x3f resets the channel) reproduces the residual extra
        // offsets; the fixed model must give exactly one offset per sweep block.
        // =====================================================================
        println("Regression 3: real out-of-order capture")
        val cap        = residualCapture.map { case (s, t, b) => (s, t, pushPulse(t, 0xB0, b)) }
        val capBlocks  = groupBlocks(cap)
        val emitBuggy  = emitOffsets(cap, resetOn3f = true)
        val emitFixed  = emitOffsets(cap, resetOn3f = false)
        var buggyBad   = 0
        capBlocks.zipWithIndex.foreach { case (idxs, bi) =>
            if (idxs.length == 4) {
                val ids = idxs.map(cap(_)._3).filter(_ != UNIDENTIFIED).toSet
                if (ids.size > 1) {
                    failures += s"capture block $bi reports conflicting channels ${ids.map("0x"+_.toHexString)}"
                }
                val nFixed = idxs.count(emitFixed(_))
                val nBuggy = idxs.count(emitBuggy(_))
                if (nBuggy != 1) buggyBad += 1
                if (nFixed != 1) {
                    val rows = idxs.map { i => val (s,t,np)=cap(i); f"s$s t:$t np=0x${np.toHexString}" }
                    failures += s"capture block $bi produced $nFixed offsets with the fix (expected 1): ${rows.mkString(", ")}"
                }
            }
        }
        println(f"  ${capBlocks.count(_.length==4)} full blocks; buggy model glitches $buggyBad of them, fixed model 0")
        if (buggyBad == 0) {
            failures += "Regression 3 did not reproduce the residual under the buggy model - capture/test is not exercising it"
        }

        dut.clockDomain.waitRisingEdge(50)

        if (failures.nonEmpty) {
            simFailure("PulseIdentifier checks failed:\n  - " + failures.mkString("\n  - "))
        }
        simSuccess()
    }
  }
}
