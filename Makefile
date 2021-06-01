PROJ = lighthouse

VERSION ?= 6
SEED ?= 42

PIN_DEF = lighthouse4_revB.pcf
DEVICE = up5k
PACKAGE = sg48

all: generate_verilog bitstream

generate_verilog:
	sbt "runMain lighthouse.GenerateTopLevel"

bitstream: $(PROJ).bin

$(PROJ).json: LighthouseTopLevel.v
	yosys -p 'read_verilog LighthouseTopLevel.v; read_verilog  blackboxes.v; synth_ice40 -top LighthouseTopLevel; write_json $@'

%.asc: %.json $(PIN_DEF)
	nextpnr-ice40 --seed $(SEED) --up5k --package sg48 --json $< --asc $@ --pcf $(PIN_DEF) --freq 24
	python3 tools/update_bitstream_comment.py $@ "$(VERSION)"

%.bin: %.asc
	icepack $< $@

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
.PHONY: all prog clean generate_verilog bitstream
