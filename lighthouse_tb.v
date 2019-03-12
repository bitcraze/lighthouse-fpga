`timescale 1ns/ 1ps
`include "ts4231.v"
`include "lighthouseTestPulseGenerator.v"

module testbench();

  // Run the clock at 24MHz
  reg clk;
  always #20832ps clk = (clk === 1'b0);

  wire [1:0] e;
  wire [1:0] d;
  wire ir_e;
  wire ir_d;

  lighthousePulseGenerator pulseGenerator (
    .ir_e(ir_e),
    .ir_d(ir_d)
  );

 generate
    genvar i;
    for (i = 0; i < 2; i = i +1) begin
      ts4231 converter(
        .ir_e(ir_e),
        .ir_d(ir_d),
        .e(e[i]),
        .d(d[i])
      );
    end
  endgenerate

  wire so;
  reg si = 0;
  reg sck = 1;
  reg n_cs = 1;

  top uut(
    .clk12(clk),

    .e(e),
    .d(d)
  );

  assign uut.clk = clk;

  reg [4095:0] vcdfile;

  initial begin
    if ($value$plusargs("vcd=%s", vcdfile)) begin
      $dumpfile(vcdfile);
      $dumpvars(0, testbench);
    end

    #4ms;
    $finish;
  end

  initial begin
    // Waits for at least one measurement to be available
    #1000us;

    $finish;
  end
endmodule // testbench