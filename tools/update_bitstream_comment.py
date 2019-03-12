#!/usr/bin/env python3
import sys
import re

if len(sys.argv) < 3:
    sys.stderr.write("Usage: {} <bitstream.asc> <comment>\n".format(sys.argv[0]))
    sys.exit(1)

with open(sys.argv[1], "r") as asc_file:
    asc = asc_file.read()

asc = re.sub("^.comment.*", ".comment\n{}".format(sys.argv[2]), asc)

with open(sys.argv[1], "w") as asc_file:
    asc_file.write(asc)