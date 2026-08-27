PROJ = lighthouse

VERSION ?= 7
SEED ?= 14

PIN_DEF = lighthouse4_revB.pcf
DEVICE = up5k
PACKAGE = sg48

all: generate_verilog bitstream

generate_verilog:
	for i in 1 2 3; do \
		sbt "runMain lighthouse.GenerateTopLevel" && exit 0; \
		echo "generate_verilog attempt $$i/3 failed, retrying..." >&2; \
		[ $$i -lt 3 ] && sleep 10; \
	done; \
	exit 1

bitstream: $(PROJ).bin

$(PROJ).json: LighthouseTopLevel.v
	yosys -p 'read_verilog LighthouseTopLevel.v; read_verilog  blackboxes.v; synth_ice40 -top LighthouseTopLevel; write_json $@'

%.asc: %.json $(PIN_DEF)
	nextpnr-ice40 --seed $(SEED) --up5k --package sg48 --json $< --asc $@ --pcf $(PIN_DEF) --freq 24
	python3 tools/update_bitstream_comment.py $@ "$(VERSION)"

%.bin: %.asc
	icepack $< $@
# Informational only - the '-' keeps a failure here from taking the bitstream
# down with it under .DELETE_ON_ERROR.
	-python3 tools/bitstream_id.py $@

%_tb: %_tb.v %.v
	iverilog -g2005-sv -o $@ $^ `yosys-config --datdir/ice40/cells_sim.v`

%_tb.vcd: %_tb
	vvp -N $< +vcd=$@

%_syn.v: %.v
	yosys -p 'read_verilog $<; chparam -set N_SENSORS $(N_SENSORS) top; chparam -set UART_BAUDRATE $(UART_BAUDRATE) top; synth_ice40 -top top; write_verilog $@'

%_syntb: %_tb.v %_syn.v
	iverilog -g2005-sv -o $@ $^ `yosys-config --datdir/ice40/cells_sim.v`

%_syntb.vcd: %_syntb
	vvp -N $< +vcd=$@

clean:
	rm -f $(PROJ).json $(PROJ).asc $(PROJ).rpt $(PROJ).bin $(PROJ)_timing.v *.vcd

.SECONDARY:
# nextpnr writes the .asc before it runs timing analysis, so a run that misses
# --freq leaves a complete-looking .asc behind. Without this, the next make sees
# it as newer than the .json, skips PnR and packs a bitstream from a placement
# that FAILED timing.
.DELETE_ON_ERROR:
.PHONY: all prog clean generate_verilog bitstream
