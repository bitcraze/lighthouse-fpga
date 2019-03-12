module testbench ();

reg clk = 0;
reg in_valid = 0;
reg [31:0] in_ts = 0;
reg [15:0] in_length = 0;
wire in_ready;

wire out_valid;
wire [31:0] out_ts;
wire [15:0] out_length;
reg out_ready = 0;

pulseFifo  #(
    .DEPTH(256),
    .PTR_BITS(8)
  ) uut (
  .clk(clk),

  .in_valid(in_valid),
  .in_ts(in_ts),
  .in_length(in_length),
  .in_ready(in_ready),

  .out_valid(out_valid),
  .out_ts(out_ts),
  .out_length(out_length),
  .out_ready(out_ready)
  );

always #1 clk = !clk;

initial begin
  $dumpfile("pulseFifo_tb.vcd");
  $dumpvars(0, testbench);

  #10 in_valid = 1;
  #2 in_valid = 0;

  #2 out_ready = 1;

  #4 in_valid = 1;
  #2 in_valid = 0;

  #4 out_ready = 0;

  #2 in_valid = 1;
  #20 in_valid = 0;

  #4 out_ready = 1;
  #20 out_ready = 0;


  #100 $finish;
end

endmodule
