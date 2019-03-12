module testbench();

  reg clk = 0;
  always #10 clk = ~clk;

  reg valid = 0;
  reg [7:0] data = 0;
  wire ready;

  wire tx;

  uart #(
    .CLKRATE(10),
    .BAUDRATE(5)
  ) uart (
    .clk(clk),

    .valid(valid),
    .data(data),
    .ready(ready),

    .tx(tx)
  );

  reg [4095:0] vcdfile;

  initial begin
    if ($value$plusargs("vcd=%s", vcdfile)) begin
      $dumpfile(vcdfile);
      $dumpvars(0, testbench);
    end

    #20;
    data = 8'haa;
    valid = 1;
    #20;
    valid = 0;
    data = 8'h55;
    @(posedge ready) #10;
    #20 valid = 1;
    #20 valid = 0;
    data = 8'h00;
    @(posedge ready) #10;
    #40 valid = 1;
    #20 valid = 0;

    #10000;

    $finish;
  end
endmodule