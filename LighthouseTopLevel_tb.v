`timescale 1ns/ 1ps
// `include "LighthouseTopLevel.v"
`include "blackboxes.v"
`include "sim_rtl/ts4231.v"
`include "sim_rtl/lighthouseTestPulseGenerator.v"

module testbench();

  // Run the clock at 24MHz
  reg clk;
  // always #20832ps clk = (clk === 1'b0);
  always #10416ps clk = (clk === 1'b0);  // 48MHz


  wire [3:0] e;
  wire [3:0] d;
  reg ir_e;
  reg ir_d;

  lighthousePulseGenerator pulseGenerator (
    .ir_e(ir_e),
    .ir_d(ir_d)
  );

  generate
    genvar i;
    for (i = 0; i < 4; i = i +1) begin
      ts4231 converter(
        .ir_e(ir_e),
        .ir_d(ir_d),
        .e(e[i]),
        .d(d[i])
      );
    end
  endgenerate

  LighthouseTopLevel uut(
    .io_clk12MHz(clk),

    .io_e_0(e[0]),
    .io_d_0(d[0]),
    .io_e_1(e[1]),
    .io_d_1(d[1]),
    .io_e_2(e[2]),
    .io_d_2(d[2]),
    .io_e_3(e[3]),
    .io_d_3(d[3])
  );

  assign uut.Core_clk = clk;

  reg [4095:0] vcdfile;

  initial begin
    if ($value$plusargs("vcd=%s", vcdfile)) begin
      $dumpfile(vcdfile);
      $dumpvars(0, testbench);
    end

    #1ms;
    $finish;
  end
endmodule // testbench