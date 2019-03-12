// Generates synchronization patterns in the timestamp flow
// Take as input timestamps, outputs timestamps postponed of a byte with value 0
// At regular interval, emmits all FF timestamp prepended with FF, this marks
// a synchronization pattern that cannot appear in the normal timestamp flow.
// This way, the synchronization pattern is "00 FF FF FF FF FF FF FF"
module synchronizer # (
  parameter PERIOD = 6000000
) (
  input clk,

  input in_valid,
  input [47:0] in_data,
  output in_ready,

  output out_valid,
  output [55:0] out_data,
  input out_ready
);

  reg [$clog2(PERIOD+1):0] ctr = 0;
  reg emmit_sync;

  always @(posedge clk) begin
    ctr <= ctr + 1;
    if (ctr == PERIOD) emmit_sync <= 1;
    else emmit_sync <= 0; 
  end

  assign out_data = emmit_sync?56'hFFFFFFFFFFFFFF:{8'h00, in_data};
  assign out_valid = emmit_sync?1:in_valid;
  assign in_ready = emmit_sync?0:out_ready;

endmodule