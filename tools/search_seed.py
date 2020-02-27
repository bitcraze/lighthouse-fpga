#!/usr/bin/env python3

import subprocess
import sys


def pnr(seed: int):
    result = subprocess.run(["nextpnr-ice40", "--seed",  str(seed),
                        "--up5k", "--json", "lighthouse.json", "--asc", 
                        "lighthouse.asc", "--pcf", "lighthouse4_revB.pcf"])
    if result.returncode == 0:
        print("Seed is {}".format(seed))
        sys.exit()


if __name__ == "__main__":
    for seed in range(1000):
        pnr(seed)
