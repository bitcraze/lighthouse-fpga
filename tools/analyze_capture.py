#!/usr/bin/env python3
"""Parse a Crazyflie console capture of raw lighthouse frames and find residual
glitch blocks (a sweep block with more than one sync offset).

Line format (from CONFIG_DECK_LIGHTHOUSE_RAW_FRAME_DEBUG in lighthouse_core.c):
    [console] LH: s:<sensor> t:<timestamp> b:<beamData> o:<offset> c:<channelFound>

Usage: tools/analyze_capture.py <capture.log>
"""
import re, sys

MAX_TICKS_SENSOR_TO_SENSOR = 10000
MASK24 = 0xFFFFFF

line_re = re.compile(r"s:(\d+)\s+t:(\d+)\s+b:(\d+)\s+o:(\d+)\s+c:(\d+)")

def ts_diff(a, b):
    """signed 24-bit (a - b)"""
    d = (a - b) & MASK24
    if d > (1 << 23):
        d -= (1 << 24)
    return d

def main(path):
    pulses = []
    malformed = 0
    for ln in open(path):
        if " LH: s:" not in ln:
            continue
        m = line_re.search(ln)
        if not m:
            malformed += 1
            continue
        s, t, b, o, c = (int(x) for x in m.groups())
        pulses.append((s, t & MASK24, b, o, c))

    print(f"parsed {len(pulses)} pulses ({malformed} malformed/fragmented lines skipped)")
    if not pulses:
        return

    # Group into blocks the way the firmware does (gap > MAX_TICKS starts a new block).
    blocks, cur = [], [0]
    for i in range(1, len(pulses)):
        if abs(ts_diff(pulses[i][1], pulses[i-1][1])) > MAX_TICKS_SENSOR_TO_SENSOR:
            blocks.append(cur); cur = []
        cur.append(i)
    if cur:
        blocks.append(cur)

    # Four rows is not enough: the firmware validates a sensor MASK, so a block
    # with a duplicated sensor and another missing is rejected there and must not
    # count here either.
    full = [bl for bl in blocks
            if len(bl) == 4 and len({pulses[i][0] for i in bl}) == 4]
    print(f"{len(blocks)} blocks, {len(full)} with exactly 4 sensors")

    # A glitch block = more than one sensor carries a sync offset (firmware wants 1).
    glitch = []
    legit_gaps, glitch_pair_gaps = [], []
    for bl in full:
        rows = [pulses[i] for i in bl]
        n_off = sum(1 for r in rows if r[3] != 0)
        gaps = [abs(ts_diff(rows[j][1], rows[j-1][1])) for j in range(1, len(rows))]
        if n_off > 1:
            glitch.append(bl)
            # the offending close pair is the smallest intra-block gap
            glitch_pair_gaps.append(min(gaps))
        else:
            legit_gaps.extend(gaps)

    print(f"\nGLITCH blocks (>1 offset): {len(glitch)} / {len(full)}")
    if glitch_pair_gaps:
        gp = sorted(glitch_pair_gaps)
        print(f"  offending close-pair gaps (ticks): min={gp[0]} max={gp[-1]} "
              f"median={gp[len(gp)//2]}")
        print(f"  distribution: {gp}")
    if legit_gaps:
        lg = sorted(set(legit_gaps))
        print(f"  legit intra-block gaps (good blocks): min={lg[0]} max={lg[-1]}")

    # Show a few glitch blocks in full so we can replay them through the sim.
    print("\n--- sample glitch blocks (sensor, timestamp, beamData, offset, channelFound) ---")
    for bl in glitch[:8]:
        rows = [pulses[i] for i in bl]
        base = rows[0][1]
        for (s, t, b, o, c) in rows:
            print(f"  s{s} t:{t} (+{ts_diff(t, base):>4}) b:{b} o:{o} c:{c}")
        print()

    # Emit the glitch blocks as a Scala-ready table for the sim regression.
    print("--- glitch pulses as (sensor, timestamp, beamData) for the sim ---")
    for bl in glitch[:12]:
        row = ", ".join(f"({pulses[i][0]}, {pulses[i][1]}, {pulses[i][2]})" for i in bl)
        print(f"    {row},")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: tools/analyze_capture.py <capture.log>")
    main(sys.argv[1])
