# CLAUDE.md — developing the Lighthouse deck FPGA

This is the **FPGA** for the Bitcraze Lighthouse deck (Lattice iCE40 up5k). It
receives IR sweeps from Lighthouse V1/V2 base stations via TS4231 light-to-digital
chips, decodes them, and streams per-pulse data to the Crazyflie over UART. Written
in **Scala / SpinalHDL 1.3.7** (not hand-written Verilog).

This file is the practical playbook for developing here: how to simulate, how to
build a bitstream, how to get ground-truth data off real hardware, and the
gotchas that will otherwise cost you an hour each.

---

## 1. The two processors — don't confuse them

- **This repo = the deck FPGA.** Source is `src/main/scala/lighthouse/*.scala`.
  Build output is `lighthouse.bin` (the iCE40 bitstream).
- **The Crazyflie STM32 firmware** (`~/dev/crazyflie-firmware`) consumes the deck's
  UART frames in `src/utils/src/lighthouse/pulse_processor_v2.c` and the deck driver
  `src/modules/src/lighthouse/lighthouse_core.c`. When a fix needs the firmware's
  point of view (or to capture data), you work there too — but it is a *separate*
  binary on a *separate* chip.

A change to FPGA decoding is only "done" when it satisfies what the firmware
expects (Section 5).

---

## 2. Signal pipeline (where the logic lives)

Per sensor: `PulseTimer` + BMC decoder produce `(timestamp, width, beamWord)`.
The four sensor streams are arbitrated into one stream, then:

```
beamsStream ──▶ PulseIdentifier ──▶ PulseOffsetFinder ──▶ UART frames
                (finds nPoly)         (finds sync offset)
```

- **`PulseIdentifier`** (`PulseIdentifier.scala`) identifies the channel
  *relatively*: it runs `PolyFinder` (`polyFinder.scala`) forward from the
  **previous** pulse's `beamWord` over the elapsed time (`pulseDelta >> 2` = LFSR
  steps; timestamps tick at 24 MHz and the LFSR advances once per 4 ticks ≈ 6 MHz)
  and finds which of the 32 polynomials
  reaches this pulse's `beamWord`. Output `nPoly`.
- **`PulseOffsetFinder`** (`PulseOffsetFinder.scala`) finds the LFSR offset (rotor
  angle) and emits a **sync offset** for the first identified pulse of a sweep.
- LFSR polynomials: `constants.Polys` in `utils.scala` — **32 polys = 16 channels ×
  2 sweeps**. `SoftLfsr` (in `Lighthouse.scala`) is a software model that matches the
  hardware `Lfsr` bit-for-bit; use it to generate self-consistent test beamWords.

### nPoly encoding (also in `readme.md`)
- `nPoly & 0x20 != 0` (i.e. `0x3f`) ⇒ **unidentified**. This is normal for the first
  pulse of a sweep (its predecessor is the previous, unrelated sweep).
- base-station channel = `nPoly / 2` (`nPoly >> 1`), 0–15; slow-data bit = `nPoly & 1`.
  So two different `nPoly` values can be the **same** base station (its two sweeps).

### Gotcha: pulses can arrive OUT OF timestamp order
The arbiter is not a time-sorted merge. Two near-simultaneous sensors (~7 ticks
apart) can arrive with the later-arriving one having the *earlier* timestamp, so
`pulseDelta = ts - lastTs` goes slightly **negative** and wraps to ~2^24. Any logic
keying off `pulseDelta` must treat that as "same sweep," not "huge gap." This is the
root of the issue-#14 residual (Section 6).

---

## 3. Software-in-the-loop simulation — your primary dev loop

**Always reproduce/verify in sim before building a bitstream or touching hardware.**
The `*Sim` objects are SpinalHDL `doSim` testbenches run under Verilator.

```bash
tools/run_sim.sh lighthouse.PulseIdentifierSim     # the channel-id regression
tools/run_sim.sh lighthouse.PulseObjectFinderSim   # the offset-stage regression
tools/run_sim.sh lighthouse.PolyFinderSim
tools/run_sim.sh lighthouse.TopLevelSim
```

`run_sim.sh` builds a one-off image `fpga-builder-sim:local` (= `bitcraze/fpga-builder`
+ Verilator, since the stock image has SBT but no Verilator) and runs
`sbt "runMain <Sim>"` with a persistent SBT cache volume. A pass ends in
`Simulation done`; a failed assertion raises `SimFailure`. Waves: `simWorkspace/<DUT>/test.vcd`.

**Gotcha — stale workspace:** if a run dies with
`Specified --top-module ... was not found in design`, an interrupted run left a
corrupt `simWorkspace/`. Fix: `rm -rf simWorkspace` (it may be **root-owned** from a
container run, so `sudo` may be needed) and re-run.

### How the regressions are structured (and the discipline that matters)
- Tests are **data-driven from real captures**, not hand-crafted ideal inputs. A
  hand-crafted test that only exercises the one case your fix handles is *worse than
  no test* — it manufactures false confidence. (We learned this the hard way: a
  first fix passed a synthetic twin sim but was a no-op on real hardware.)
- `PulseIdentifierSim` replays the **issue #14 capture** and asserts the firmware's
  real acceptance rule **per sweep block**: all identified sensors agree on one
  channel, and exactly **one** sync offset is produced. It also replays a real
  out-of-order hardware capture (`residualCapture`) end-to-end, modelling the offset
  stage both buggy and fixed to prove the test reproduces *and* the fix resolves it.
- A new bug → get a real capture (Section 6), paste the `(sensor, timestamp, beamData)`
  rows into a `Seq` in the sim, assert the per-block invariant, watch it fail, fix,
  watch it pass. Keep the capture in the test as a permanent regression.
- The offset stage's emit rule is mirrored in a small Scala model in
  `PulseIdentifierSim` (so we don't need the slow `OffsetFinder` ROM); keep it in
  lock-step with `PulseOffsetFinder.scala`.

---

## 4. Building the bitstream

```bash
docker run --rm -v "$(pwd)":/module -v fpga-sbt-cache:/root/.ivy2 \
  -v fpga-sbt-cache-sbt:/root/.sbt -w /module fpga-builder-sim:local make all
```

`make all` = `generate_verilog` (SBT → `LighthouseTopLevel.v`) → `yosys` (→ `.json`)
→ `nextpnr-ice40 --freq 24` (→ `.asc`) → `icepack` (→ `lighthouse.bin`). Only PnR
and icepack depend on the seed, so when iterating you can regenerate the `.json`
once and re-run only PnR.

### Gotcha — nextpnr timing is NON-DETERMINISTIC here
The `fpga-builder` nextpnr gives **different timing for the same seed across runs**
(verified: same seed, serial, 46 MHz vs 38 MHz on Core). The design is timing-
marginal (Core 48 MHz, Slow 24 MHz). So:
- `SEED` in the `Makefile` is *not* a closure guarantee. Don't trust a single result.
- nextpnr exits **non-zero when `--freq` timing is missed**, so `exit 0 ⇒ both
  domains closed`. A closing placement is a valid bitstream regardless of seed.
- **"Hammer" until it closes** rather than seed-hunting — `tools/pnr_until_close.sh`
  loops `nextpnr-ice40 --randomize-seed ...` against the existing `lighthouse.json`
  and, on the first `exit 0`, runs `tools/update_bitstream_comment.py` + `icepack` and
  stops (usually within ~10 tries). Run it inside the builder image after the netlist
  is generated:
  ```bash
  docker run --rm -v "$(pwd)":/module -w /module fpga-builder-sim:local \
    bash tools/pnr_until_close.sh
  ```
- The critical paths are in `ts4231Configurator`/`bufferCC` (clock-domain crossing)
  and the BMC/DDR decoders — **not** in the pulse-processing logic — so edits to
  `PulseIdentifier`/`PulseOffsetFinder` are timing-neutral.

---

## 5. What the firmware requires (the real spec for a block)

`pulse_processor_v2.c`:
- A **block** = the 4 sensors of one sweep (frames within
  `MAX_TICKS_SENSOR_TO_SENSOR = 10000` ticks; consecutive sweeps are ~130k apart).
- `augmentFramesInWorkspace()` walks the block backwards and **back-fills the channel
  of leading `0x3f` (unidentified) pulses** from a later identified sensor — so a
  leading `0x3f` is fine and expected.
- `processWorkspaceBlock()` **discards** the block if: not all 4 sensors present, two
  identified sensors report **different channels**, or there is **not exactly one**
  sync offset.

So the FPGA's job per block: at least one sensor identified, all identified ones
agreeing, and exactly one offset. The two failure modes we fixed both violated this:
a **garbage multi-hot channel** (conflicting channel) and a **spurious second offset**.

---

## 6. Getting ground-truth data off the hardware

No 3.0 V serial adapter? Capture the deck's per-pulse frames from the **Crazyflie
console** instead (this is where the issue-#14 `s: t: b: o: c:` lines come from).

1. **Enable the raw-frame dump.** It already exists in `crazyflie-firmware`
   `src/modules/src/lighthouse/lighthouse_core.c` (the `else if(!frame.isSyncFrame)`
   branch), gated behind a Kconfig option so it doesn't flood the console by default:
   ```
   CONFIG_DECK_LIGHTHOUSE_RAW_FRAME_DEBUG=y   # via `make menuconfig` (Expansion decks)
   ```
   It prints one line per pulse: `s:<sensor> t:<timestamp> b:<beamData> o:<offset>
   c:<channelFound>` — `c` is `channelFound` (0/1), **not** the channel value.
2. Build + flash the **STM32** firmware (Crazyflie 2.1 Brushless = `cf21bl`,
   binary `build/cf21bl.bin`). Use the `crazyflie-dev` skill / `crazyflie-agent-cli`:
   ```bash
   cd ~/dev/crazyflie-firmware && make -j$(nproc)
   crazyflie-agent-cli flash build/cf21bl.bin --uri radio://0/60/2M/F00D2BEFED
   ```
   (`status` takes no URI; `scan` only finds the default address — verify a custom
   address by `start`-ing a session and watching for `[status] connected`.)
3. Capture the console:
   ```bash
   crazyflie-agent-cli start <uri> > .scratch/capture.log 2>&1 &
   ```
   The deck must be running the **bitstream under test**, and put the sensors in the
   orientation that provokes the glitch. **Capture over USB if you can** — at ~400
   frames/s the radio console drops/fragments lines and breaks block grouping. The
   log is binary-ish; use `grep -a`.
4. Analyse with `tools/analyze_capture.py <capture.log>` — groups into blocks, flags glitch
   blocks (>1 offset), prints the offending close-pair gaps vs. legit spacing, and
   emits the `(sensor, timestamp, beamData)` rows ready to paste into a sim regression.
5. **Disable `CONFIG_DECK_LIGHTHOUSE_RAW_FRAME_DEBUG` again** when done — it floods
   the console. Note: the built-in `lighthouse.enLhRawStream` param is *not* a
   substitute for it — that one pre-aggregates (one offset/group) and drops `beamData`,
   so it masks the very bug and can't feed the sim.

### Flashing the deck FPGA (the bitstream, not the STM32)
Over-the-air via `deck-bcLighthouse4-fw` requires the firmware built with
`CONFIG_DECK_LIGHTHOUSE_DEV_FLASH=y` (bypasses the boot-time CRC):
```bash
cfloader flash lighthouse.bin deck-bcLighthouse4-fw -w <uri>   # or cfclient Bootloader tab
```

---

## 7. Conventions & gotchas cheat-sheet

- **Scratch dir:** put logs/throwaway scripts in `.scratch/` (gitignored). **Avoid
  `/tmp`** — it triggers an approval prompt in this environment.
- **Docker artifacts are root-owned** (`lighthouse.bin`, `simWorkspace/`, `tmp/`).
  Clean with a containerised `rm` or `sudo`.
- **Nested package:** `utils.scala` has a doubled `package lighthouse`, so `constants`,
  `Lfsr`, etc. live in `lighthouse.lighthouse`. Reference them as `lighthouse.constants`
  (as `polyFinder.scala` does), not bare `constants`.
- **Timestamps are 24-bit and wrap**; near-simultaneous pulses arrive out of order.
  Use absolute/signed 24-bit diffs (see `absTsDiff` in `PulseIdentifierSim`).
- The **two fixes** for near-simultaneous hits (issue #14), for reference:
  - `PulseIdentifier`: a pulse within `sameSweepMaxDelta` (64) ticks of its
    predecessor is the same sweep → inherit its channel (or pass `0x3f`), never run
    the degenerate relative search (in-order close pair).
  - `PulseOffsetFinder`: a `0x3f` pulse must **not** reset `lastNPoly`, else the next
    same-channel pulse looks like a channel change and emits a spurious second offset
    (out-of-order close pair).
