module SB_IO_OD (
	inout  PACKAGEPIN,
	input  LATCHINPUTVALUE,
	input  CLOCKENABLE,
	input  INPUTCLK,
	input  OUTPUTCLK,
	input  OUTPUTENABLE,
	input  DOUT1,
	input  DOUT0,
	output DIN1,
	output DIN0
);
	parameter [5:0] PIN_TYPE = 6'b000000;
	parameter [0:0] NEG_TRIGGER = 1'b0;

`ifndef BLACKBOX
	reg dout, din_0, din_1;
	reg din_q_0, din_q_1;
	reg dout_q_0, dout_q_1;
	reg outena_q;

	generate if (!NEG_TRIGGER) begin
		always @(posedge INPUTCLK)  if (CLOCKENABLE) din_q_0  <= PACKAGEPIN;
		always @(negedge INPUTCLK)  if (CLOCKENABLE) din_q_1  <= PACKAGEPIN;
		always @(posedge OUTPUTCLK) if (CLOCKENABLE) dout_q_0 <= DOUT0;
		always @(negedge OUTPUTCLK) if (CLOCKENABLE) dout_q_1 <= DOUT1;
		always @(posedge OUTPUTCLK) if (CLOCKENABLE) outena_q <= OUTPUTENABLE;
	end else begin
		always @(negedge INPUTCLK)  if (CLOCKENABLE) din_q_0  <= PACKAGEPIN;
		always @(posedge INPUTCLK)  if (CLOCKENABLE) din_q_1  <= PACKAGEPIN;
		always @(negedge OUTPUTCLK) if (CLOCKENABLE) dout_q_0 <= DOUT0;
		always @(posedge OUTPUTCLK) if (CLOCKENABLE) dout_q_1 <= DOUT1;
		always @(negedge OUTPUTCLK) if (CLOCKENABLE) outena_q <= OUTPUTENABLE;
	end endgenerate

	always @* begin
		if (!PIN_TYPE[1] || !LATCHINPUTVALUE)
			din_0 = PIN_TYPE[0] ? PACKAGEPIN : din_q_0;
		din_1 = din_q_1;
	end

	// work around simulation glitches on dout in DDR mode
	reg outclk_delayed_1;
	reg outclk_delayed_2;
	always @* outclk_delayed_1 <= OUTPUTCLK;
	always @* outclk_delayed_2 <= outclk_delayed_1;

	always @* begin
		if (PIN_TYPE[3])
			dout = PIN_TYPE[2] ? !dout_q_0 : DOUT0;
		else
			dout = (outclk_delayed_2 ^ NEG_TRIGGER) || PIN_TYPE[2] ? dout_q_0 : dout_q_1;
	end

	assign DIN0 = din_0, DIN1 = din_1;

	generate
		if (PIN_TYPE[5:4] == 2'b01) assign PACKAGEPIN = dout ? 1'bz : 1'b0;
		if (PIN_TYPE[5:4] == 2'b10) assign PACKAGEPIN = OUTPUTENABLE ? (dout ? 1'bz : 1'b0) : 1'bz;
		if (PIN_TYPE[5:4] == 2'b11) assign PACKAGEPIN = outena_q ? (dout ? 1'bz : 1'b0) : 1'bz;
	endgenerate
`endif
endmodule
