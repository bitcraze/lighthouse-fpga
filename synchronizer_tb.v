module testbench();

  reg clk = 0;

  always #5 clk <= !clk;

  reg in_valid = 1;

  synchronizer #(
    .PERIOD(100)
  ) uut (
    .clk(clk),

    .in_valid(in_valid),
    .in_data(48'hffffffffffff)
  );

  reg [4095:0] vcdfile;

  initial begin
    if ($value$plusargs("vcd=%s", vcdfile)) begin
      $dumpfile(vcdfile);
      $dumpvars(0, testbench);
    end

    #10000;

    in_valid = 0;

    #10000;

    $finish;
  end
endmodule