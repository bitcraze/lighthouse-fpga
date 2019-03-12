module testbench ();

localparam N_INPUT = 2;

reg clk = 0;
reg [N_INPUT-1:0] in_valid = 0;
reg [(N_INPUT*32)-1:0] in_ts = 64'h2222222211111111;
reg [(N_INPUT*16)-1:0] in_length = 32'h22221111;
wire [N_INPUT-1:0] in_ready;

wire out_valid;
wire [31:0] out_ts;
wire [15:0] out_length;
reg out_ready = 0;

multiPulseFifo  #(
    .DEPTH(256),
    .N_INPUT(N_INPUT)
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

reg [4095:0] vcdfile;

initial begin
  if ($value$plusargs("vcd=%s", vcdfile)) begin
    $dumpfile(vcdfile);
    $dumpvars(0, testbench);
  end

  #10 in_valid[0] = 1;
  in_valid[1] = 1;
  #2 in_valid[1] = 0;
  #2 in_valid[0] = 0;

  #2 out_ready = 1;

  #4 in_valid[0] = 1;
  #2 in_valid[0] = 0;

  #4 out_ready = 0;

  #2 in_valid[0] = 1;
  #20 in_valid[0] = 0;

  #4 out_ready = 1;
  #20 out_ready = 0;


  #100 $finish;
end

endmodule
