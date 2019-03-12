`timescale 1ns/ 1ps
`include "ts4231.v"
`include "lighthouseTestPulseGenerator.v"

module testbench();

  // Run the clock at 24MHz
  reg clk;
  always #20832ps clk = (clk === 1'b0);

  wire e;
  wire d;
  wire ir_e;
  wire ir_d;

  lighthousePulseGenerator pulseGenerator (
    .ir_e(ir_e),
    .ir_d(ir_d)
  );

  ts4231 converter(
    .ir_e(ir_e),
    .ir_d(ir_d),
    .e(e),
    .d(d)
  );

  reg reconfigure = 0;
  wire envelope_valid;
  reg envelope_ready = 0;

  pulseProcessor uut(
    .clk(clk),

    .e(e),
    .d(d),

    .reconfigure(reconfigure),

    .envelope_valid(envelope_valid),
    .envelope_ready(envelope_ready)
  );

  reg [4095:0] vcdfile;

  initial begin
    if ($value$plusargs("vcd=%s", vcdfile)) begin
      $dumpfile(vcdfile);
      $dumpvars(0, testbench);
    end

    #50us;

    reconfigure = 1;

    #2ms;
    $finish;
  end

  // always @(posedge envelope_valid) begin
  //   // The fifo is busy, wait a bit before accepring the data
  //   repeat (10) @(posedge clk);
  //   // assert ready
  //   envelope_ready = 1;
  //   repeat (2) @(posedge clk);
  //   envelope_ready = 0;
  // end

endmodule // testbench