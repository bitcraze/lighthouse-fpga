PROJ = lighthouse

VERSION ?= ""

PIN_DEF = lighthouse8_revB.pcf
DEVICE = up5k
PACKAGE = sg48

N_SENSORS ?= 4
UART_BAUDRATE ?= 230400
# Warning: only force LEDs with the rev.D
FORCE_LED ?= 0

all: $(PROJ).rpt $(PROJ).bin

%.blif: %.v
	yosys -p 'read_verilog $<; chparam -set N_SENSORS $(N_SENSORS) top; chparam -set UART_BAUDRATE $(UART_BAUDRATE) top; chparam -set FORCE_LED $(FORCE_LED) top; synth_ice40 -top top -blif $@' 

%.asc: $(PIN_DEF) %.blif
	arachne-pnr -s 1 -d $(subst up,,$(subst hx,,$(subst lp,,$(DEVICE)))) -o $@ -p $^ -P $(PACKAGE)
	python3 tools/update_bitstream_comment.py $@ $(VERSION)

%.bin: %.asc
	icepack $< $@

%.rpt: %.asc
	icetime -d $(DEVICE) -p $(PIN_DEF) -o lighthouse_timing.v -mtr $@ $<

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

prog: $(PROJ).bin
	iceprog $<

run: $(PROJ).bin
	iceprog -S $<

clean:
	rm -f $(PROJ).blif $(PROJ).asc $(PROJ).rpt $(PROJ).bin

.SECONDARY:
.PHONY: all prog clean
