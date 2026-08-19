#!/usr/bin/env bash
# Retry nextpnr-ice40 until both clock domains meet --freq (it exits 0 only
# then), then stamp and pack the bitstream.
#
# Why retry at all: this design sits at ~89% LC utilisation with very little
# timing margin, and nextpnr is NOT deterministic -- the same --seed on the same
# netlist gives different placements between runs, and sometimes fails to place
# at all. So a seed does not pin a result, it selects a distribution. Measured
# over 65 runs on one netlist, seeds 14 and 21 both close ~75% of the time and
# are statistically indistinguishable (p=1.0), so picking a "good" seed buys
# little; retrying is what makes a build reliable: ~75% for one attempt, ~94%
# for two, ~98.5% for three.
#
# Usage:
#   tools/pnr_until_close.sh          # random seed each attempt (explore)
#   SEED=29 tools/pnr_until_close.sh  # retry ONE seed (it may close on a rerun)
#   MAX=20 tools/pnr_until_close.sh   # cap the attempts (default 60)
set -u

# Stamp the same version the Makefile does. `?=` there lets the environment win,
# so honour an exported VERSION first and fall back to the Makefile default.
VERSION="${VERSION:-$(sed -nE 's/^VERSION[[:space:]]*\??=[[:space:]]*([^[:space:]#]+).*/\1/p' Makefile | head -1)}"
: "${VERSION:?could not determine VERSION from Makefile}"

max="${MAX:-60}"
pinned="${SEED:-}"

# nextpnr writes the .asc BEFORE it runs timing analysis, so a miss still
# leaves a complete-looking file. make would then see it as newer than the
# .json, skip PnR and pack a placement that failed timing - and the
# Makefile's .DELETE_ON_ERROR cannot help, since make did not produce it.
closed=0
trap '[ "$closed" = 0 ] && rm -f lighthouse.asc; exit 130' INT TERM

# Post-route Fmax per clock, with margin against the target nextpnr enforced.
timing_report() {
  echo "$1" | grep "Max frequency for clock" | tail -2 |
    sed -E "s/.*'([^']*)': *([0-9.]+) MHz \((PASS|FAIL) at ([0-9.]+) MHz\).*/\1 \2 \3 \4/" |
    awk '{printf "    %-22s %6.2f MHz  target %6.2f  margin %+6.2f  %s\n", $1, $2, $4, $2-$4, $3}'
}

for i in $(seq 1 "$max"); do
  # Choose the seed ourselves rather than using --randomize-seed, so a closing
  # run can report which seed produced it.
  seed="${pinned:-$(( (RANDOM << 15) | RANDOM ))}"
  out=$(nextpnr-ice40 --seed "$seed" --up5k --package sg48 --json lighthouse.json \
          --asc lighthouse.asc --pcf lighthouse4_revB.pcf --freq 24 2>&1)
  rc=$?
  if [ $rc -eq 0 ]; then
    closed=1
    echo "attempt $i: CLOSED with SEED=$seed"
    timing_report "$out"
    # Past this point every step must succeed for the build to be usable, so
    # let any failure abort with a non-zero exit instead of claiming success.
    set -e
    python3 tools/update_bitstream_comment.py lighthouse.asc "$VERSION"
    icepack lighthouse.asc lighthouse.bin
    echo "PACKED lighthouse.bin (version $VERSION, SEED=$seed)"
    python3 tools/bitstream_id.py lighthouse.bin
    echo
    echo "NOTE: nextpnr is non-deterministic, so SEED=$seed is not a guarantee."
    echo "      Before committing it to the Makefile, confirm it closes"
    echo "      repeatedly:  for i in \$(seq 6); do SEED=$seed MAX=1 $0; done"
    exit 0
  fi
  echo "attempt $i: miss (SEED=$seed)"
  timing_report "$out"
  rm -f lighthouse.asc
done
echo "no closing placement in $max attempts"
exit 1
