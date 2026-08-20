#!/usr/bin/env bash
# Run a SpinalHDL simulation under Verilator, using the Bitcraze fpga-builder image.
#
# Usage:
#   tools/run_sim.sh <fully.qualified.SimObject>
# Examples:
#   tools/run_sim.sh lighthouse.PulseIdentifierSim
#   tools/run_sim.sh lighthouse.PolyFinderSim
#
# The fpga-builder image ships SBT but NOT Verilator (which SpinalHDL's doSim
# needs), so on first use we derive a local image with Verilator added and then
# reuse it. SBT/ivy caches live in a Docker volume so dependencies download once.
set -euo pipefail

SIM="${1:?usage: tools/run_sim.sh <fully.qualified.SimObject>  (e.g. lighthouse.PulseIdentifierSim)}"
IMAGE=fpga-builder-sim:local

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Building $IMAGE (fpga-builder + Verilator), one time only ..."
  docker build -t "$IMAGE" - <<'DOCKERFILE'
FROM bitcraze/fpga-builder
# The image ships a stale bintray apt source that 404s; drop it before updating.
RUN rm -f /etc/apt/sources.list.d/*.list && \
    apt-get update && \
    apt-get install -y verilator && \
    rm -rf /var/lib/apt/lists/*
DOCKERFILE
fi

docker volume create fpga-sbt-cache >/dev/null
exec docker run --rm \
  -v "$(pwd)":/module \
  -v fpga-sbt-cache:/root/.ivy2 \
  -v fpga-sbt-cache-sbt:/root/.sbt \
  -w /module \
  "$IMAGE" \
  sbt "runMain $SIM"
