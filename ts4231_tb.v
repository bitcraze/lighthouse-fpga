// Testbench for the TS4231 simulation model
// Config sequence follows the sequence used in the TriadSemi TS4231 arduino lib
// https://github.com/TriadSemi/TS4231/tree/fe58ec009725358f528a6675e4180c7dd13092c6

`timescale 1ns/1ps
`include "lighthouseTestPulseGenerator.v"
module testbench();

  wire e;
  wire d;
  wire ir_e;
  wire ir_d;

  ts4231 uut(
    .ir_e(ir_e),
    .ir_d(ir_d),
    .e(e),
    .d(d)
  );

  lighthousePulseGenerator pulseGenerator (
    .ir_e(ir_e),
    .ir_d(ir_d)
  );

  // Since e and d are bidirectingional, creating a register to drive them
  reg e_drive = 1'bz;
  reg d_drive = 1'bz;
  assign e = e_drive;
  assign d = d_drive;

  localparam BUS_DRV_DELAY = 1us;
  localparam BUS_CHECK_DELAY = 500us;
  localparam BUS_SLEEP_RECOVERY = 100us;

  task write_config;
    input [14:0] config_val;
    integer i;
    begin
      e_drive = 1;
      d_drive = 1;
      #BUS_DRV_DELAY d_drive = 0;
      #BUS_DRV_DELAY e_drive = 0;
      #BUS_DRV_DELAY;
      for (i = 14; i >= 0; i = i-1) begin
        d_drive = config_val[i];
        #BUS_DRV_DELAY e_drive = 1;
        #BUS_DRV_DELAY e_drive = 0;
        #BUS_DRV_DELAY;
      end
      d_drive = 0;
      #BUS_DRV_DELAY e_drive = 1;
      #BUS_DRV_DELAY d_drive = 1;
      #BUS_DRV_DELAY;
      e_drive = 1'bz;
      d_drive = 1'bz;
    end
  endtask

  task read_config;
    output [13:0]config_val;
    integer i;
    begin
      e_drive = 1;
      d_drive = 1;
      #BUS_DRV_DELAY d_drive = 0;
      #BUS_DRV_DELAY e_drive = 0;
      #BUS_DRV_DELAY d_drive = 1;
      #BUS_DRV_DELAY e_drive = 1;
      #BUS_DRV_DELAY d_drive = 1'bz;
      #BUS_DRV_DELAY e_drive = 0;
      #BUS_DRV_DELAY;
      for (i = 13; i >= 0; i = i-1) begin
        e_drive = 1;
        #BUS_DRV_DELAY;
        config_val[i] = d;
        #1 e_drive = 0;
        #BUS_DRV_DELAY;
      end
      d_drive = 0;
      #BUS_DRV_DELAY e_drive = 1;
      #BUS_DRV_DELAY d_drive = 1;
      #BUS_DRV_DELAY;
      d_drive = 1'bz;
      e_drive = 1'bz;
    end
  endtask

  task go_to_watch;
    begin
      if ({e,d} == {1'b1,1'b1}) begin
        #1 e_drive = 1;
        #1 d_drive = 1;
        #1 e_drive = 0;
        #1 d_drive = 0;
        #1 d_drive = 1'bz;
        #1 e_drive = 1;
        #1 e_drive = 1'bz;
        #BUS_SLEEP_RECOVERY;
      end else begin
        $display("Starting watch from wrong state!");
        $stop;
      end
    end
  endtask

  task config_device;
    input [14:0] config_val;
    reg [14:0] readback_config;
    begin
    #BUS_DRV_DELAY e_drive = 0;
    #BUS_DRV_DELAY e_drive = 1;
    #BUS_DRV_DELAY e_drive = 0;
    #BUS_DRV_DELAY e_drive = 1;
    #BUS_DRV_DELAY d_drive = 0;
    #BUS_DRV_DELAY d_drive = 1;
    #BUS_DRV_DELAY;
    d_drive = 1'bz;
    e_drive = 1'bz;
    // test for S3
    write_config(config_val);
    read_config(readback_config);
    // Test if config_val == readback_config
    go_to_watch();
    end
  endtask
  

  reg [4095:0] vcdfile;

  initial begin
    if ($value$plusargs("vcd=%s", vcdfile)) begin
      $dumpfile(vcdfile);
      $dumpvars(0, testbench);
    end

    #1; // Skip the beginning ...

    // Waiting for a light pulse
    @(negedge d);

    // Configure device
    config_device(14'h392b);

    #1ms;
    $finish;
  end

endmodule // testbench