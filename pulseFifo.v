module pulseFifo #(
  parameter DEPTH = 8,
  parameter PTR_BITS = 3
)(
  input clk,

  input in_valid,
  input [31:0] in_ts,
  input [15:0] in_length,
  output in_ready,

  output out_valid,
  output [31:0] out_ts,
  output [15:0] out_length,
  input out_ready,

  output reg [PTR_BITS-1:0] used,
  output reg [PTR_BITS-1:0] free
  );

  reg [47:0] memory [0:DEPTH];
  reg [PTR_BITS-1:0] wptr = 0, rptr = 0;

  reg [47:0] memory_out;
  reg [47:0] pass_out;
  reg use_pass_out;

  assign out_ts = use_pass_out ? pass_out[31:0] : memory_out[31:0];
  assign out_length = use_pass_out ? pass_out[47:32] : memory_out[47:32];

  wire do_shift_in = in_valid && |free;
	wire do_shift_out = out_ready && |used;
  assign in_ready = |free;
  assign out_valid = |used;

  // Initial value at chip initialization
  initial used = 0;
  initial free = DEPTH;

  always @(posedge clk) begin
    memory[wptr] <= {in_length, in_ts};
    wptr <= wptr + do_shift_in;

    memory_out <= memory[rptr + do_shift_out];
    rptr <= rptr + do_shift_out;

    use_pass_out <= wptr == rptr;
    pass_out <= {in_length, in_ts};

    if (do_shift_in && !do_shift_out) begin
      used <= used + 1;
      free <= free - 1;
    end

    if (!do_shift_in && do_shift_out) begin
      used <= used - 1;
      free <= free + 1;
    end
  end



endmodule
