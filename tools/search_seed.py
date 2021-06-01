#!/usr/bin/env python3

import subprocess
import sys
import os


def build(seed: int):
    volume = f"{os.environ['PWD']}:/module"
    subprocess.run(["docker", "run", "--rm", "-v", volume, "bitcraze/fpga-builder", "make", "clean"])
    result = subprocess.run(["docker", "run", "--rm", "-v", volume, "bitcraze/fpga-builder:4", "make", f"SEED={seed}"])
    if result.returncode == 0:
        print("Seed is {}".format(seed))
        sys.exit()

if __name__ == "__main__":
    for seed in range(1000):
        build(seed)
