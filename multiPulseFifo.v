`include "pulseFifo.v"
// Pulse fifo with multiple input and one output
module multiPulseFifo #(
    parameter DEPTH = 7,
    parameter N_INPUT = 2
) (
  input clk,

  input [N_INPUT-1:0] in_valid,
  input [(N_INPUT*32)-1:0] in_ts,
  input [(N_INPUT*16)-1:0] in_length,
  output reg [N_INPUT-1:0] in_ready,

  output out_valid,
  output [31:0] out_ts,
  output [15:0] out_length,
  input out_ready,

  output [7:0] used,
  output [7:0] free
);

reg fifo_in_valid;
wire fifo_in_ready;
reg [31:0] fifo_in_ts;
reg [15:0] fifo_in_length;

pulseFifo #(
  .DEPTH(DEPTH),
  .PTR_BITS(8)
) fifo (
  .clk(clk),

  .in_valid(fifo_in_valid),
  .in_ready(fifo_in_ready),
  .in_ts(fifo_in_ts),
  .in_length(fifo_in_length),

  .out_valid(out_valid),
  .out_ts(out_ts),
  .out_length(out_length),
  .out_ready(out_ready),

  .free(free),
  .used(used)
);

integer i;

always @* begin
  fifo_in_valid = 0;
  fifo_in_ts = 0;
  fifo_in_length = 0;
  in_ready[1] = 0;
  in_ready[0] = 0;

  for(i=0; i<N_INPUT; i = i+1) begin
    in_ready[i] = 0;
    if(in_valid[i] && fifo_in_valid == 0) begin
      fifo_in_valid = 1;
      fifo_in_ts = in_ts[i*32 +: 32];
      fifo_in_length = in_length[i*16 +: 16];
      in_ready[i] = fifo_in_ready;
    end
  end

  // if (in_valid[1] == 1) begin
  //   fifo_in_valid = 1;
  //   fifo_in_ts = in_ts[1*32 +: 32];
  //   fifo_in_length = in_length[1*16 +: 16];
  //   in_ready[1] = fifo_in_ready;
  // end else if (in_valid[0] == 1) begin
  //   fifo_in_valid = 1;
  //   fifo_in_ts = in_ts[0*32 +: 32];
  //   fifo_in_length = in_length[0*16 +: 16];
  //   in_ready[0] = fifo_in_ready;
  // end else begin
  //   fifo_in_valid = 0;
  //   fifo_in_ts = 0;
  //   fifo_in_length = 0;
  //   in_ready[1] = 0;
  //   in_ready[0] = 0;
  // end
end

endmodule