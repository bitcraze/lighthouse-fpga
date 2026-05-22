#!/usr/bin/env bash
# nextpnr-ice40 here is non-deterministic; a closing placement is still a valid
# bitstream. Retry PnR on the existing netlist until both clock domains meet
# --freq (nextpnr exits 0 only then), then pack the bitstream.
set -u
max=60
for i in $(seq 1 $max); do
  out=$(nextpnr-ice40 --randomize-seed --up5k --package sg48 --json lighthouse.json \
          --asc lighthouse.asc --pcf lighthouse4_revB.pcf --freq 24 2>&1)
  rc=$?
  core=$(echo "$out" | grep -o "'Core_clk': [0-9.]* MHz ([A-Z]*" | tail -1)
  slow=$(echo "$out" | grep -o "Slow_clk_\$glb_clk': [0-9.]* MHz ([A-Z]*" | tail -1)
  if [ $rc -eq 0 ]; then
    echo "attempt $i: CLOSED | $core) | $slow)"
    python3 tools/update_bitstream_comment.py lighthouse.asc "6"
    icepack lighthouse.asc lighthouse.bin && echo "PACKED lighthouse.bin"
    exit 0
  fi
  echo "attempt $i: miss   | $core) | $slow)"
done
echo "no closing placement in $max attempts"
exit 1
