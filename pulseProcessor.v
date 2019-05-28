// Lighthouse pulse processor using TS4231 light to digital convertor
module pulseProcessor #(
  parameter SENSOR_ID = 0
)(
  input clk,

  input reconfigure,
  output reg configured,
  output reg error,

  // Connection to the TS4231
  inout e,
  inout d,

  // Data output to fifo
  output reg [31:0] timestamp,
  output reg [15:0] length,
  output reg envelope_valid,
  input envelope_ready,
  output reg data,
  output reg data_valid,
  input data_ready,

  // Debug output
  output e_value,
  output d_value,

  input [31:0] time_ctr
);

  ///////
  // IOs
  ///////
  reg d_in, d_in_1, d_out, d_oe;
  SB_IO #(
    .PIN_TYPE(6'b1101_00)   // Ouptut and OE registered, input DDR
  ) d_io (
    .PACKAGE_PIN(d),

    .INPUT_CLK(clk),
    .OUTPUT_CLK(clk),
    .CLOCK_ENABLE(1'b1),

    .OUTPUT_ENABLE(d_oe),

    .D_OUT_0(d_out),
    .D_IN_0(d_in),
    .D_IN_1(d_in_1)
  );
  reg e_in, e_in_1, e_out, e_oe;
  SB_IO #(
    .PIN_TYPE(6'b1101_00)   // Ouptut and OE registered, input DDR
  ) e_io (
    .PACKAGE_PIN(e),

    .INPUT_CLK(clk),
    .OUTPUT_CLK(clk),
    .CLOCK_ENABLE(1'b1),

    .OUTPUT_ENABLE(e_oe),

    .D_OUT_0(e_out),
    .D_IN_0(e_in),
    .D_IN_1(e_in_1)
  );

  assign e_value = e_in;
  assign d_value = d_in;

  /////////////////
  // Configuration
  /////////////////

  reg config_enable = 0;
  reg [5:0] config_enable_crt = 0;
  always @(posedge clk) begin
    if (config_enable_crt == 23) begin
      config_enable_crt <= 0;
      config_enable <= 1;
    end else begin
      config_enable_crt <= config_enable_crt + 1;
      config_enable <= 0;
    end
  end

  // Configuration state machine
  initial configured = 0;
  initial error = 0;

  localparam CONFIG_IDLE = 0;
  localparam CONFIG_WAIT_PULSE = 1;
  localparam CONFIG_START_CFG = 2;
  localparam CONFIG_WRITE_START = 3;
  localparam CONFIG_WRITE_E_LOW = 4;
  localparam CONFIG_WRITE_BIT = 5;
  localparam CONFIG_WRITE_E_HIGH = 6;
  localparam CONFIG_WRITE_STOP = 7;
  localparam CONFIG_WATCH_ELOW = 8;
  localparam CONFIG_WATCH_DLOW = 9;
  localparam CONGIG_WATCH_EHIGH = 10;

  reg [3:0] config_state = CONFIG_IDLE;

  reg prev_reconfigure = 0;
  reg config_prev_d = 0;
  reg [5:0] config_bit_counter = 0;
  reg [15:0] config_value = {14'h392b, 1'b0};
  reg config_bit = 0;
  reg [4:0] config_wait_counter = 0;
  always @(posedge clk) begin
    if (config_enable) begin
      prev_reconfigure <= reconfigure;
      config_prev_d <= d_in;

      case (config_state)
        CONFIG_IDLE: if (prev_reconfigure == 0 && reconfigure == 1) config_state <= CONFIG_WAIT_PULSE;
        CONFIG_WAIT_PULSE: if (config_prev_d == 1 && d_in == 0) config_state <= CONFIG_START_CFG;
        CONFIG_START_CFG: begin
          if (config_wait_counter == 4) config_state <= CONFIG_WRITE_START;
          config_wait_counter = config_wait_counter + 1;
        end
        CONFIG_WRITE_START: begin
          config_bit_counter <= 0;
          config_state <= CONFIG_WRITE_E_LOW;
        end
        CONFIG_WRITE_E_LOW: begin
          config_state <= CONFIG_WRITE_BIT;
          config_bit <= config_value[15-config_bit_counter];
        end
        CONFIG_WRITE_BIT: begin
          config_state <= CONFIG_WRITE_E_HIGH;
        end
        CONFIG_WRITE_E_HIGH: begin
          config_bit_counter <= config_bit_counter + 1;
          if (config_bit_counter == 15) config_state <= CONFIG_WRITE_STOP;
          else config_state <= CONFIG_WRITE_E_LOW;
        end
        CONFIG_WRITE_STOP: config_state <= CONFIG_WATCH_ELOW;
        CONFIG_WATCH_ELOW: config_state <= CONFIG_WATCH_DLOW;
        CONFIG_WATCH_DLOW: config_state <= CONGIG_WATCH_EHIGH;
        CONGIG_WATCH_EHIGH: begin
          config_state <= CONFIG_IDLE;
          configured <= 1;
        end
      endcase
    end
  end

  always @* begin
    (* parallel_case *)
    case (config_state)
      CONFIG_START_CFG: begin
        e_out = (config_wait_counter == 4);
        e_oe  = (config_wait_counter == 4);
        d_out = 0;
        d_oe  = 0;
      end
      CONFIG_WRITE_START: begin
        e_out = 1;
        e_oe  = 1;
        d_out = 0;
        d_oe  = 1;
      end
      CONFIG_WRITE_E_LOW, CONFIG_WRITE_BIT: begin
        e_out = 0;
        e_oe  = 1;
        d_out = config_bit;
        d_oe  = 1;
      end
      CONFIG_WRITE_E_HIGH: begin
        e_out = 1;
        e_oe  = 1;
        d_out = config_bit;
        d_oe  = 1;
      end
      CONFIG_WRITE_STOP: begin
        e_out = 1;
        e_oe  = 1;
        d_out = 1;
        d_oe  = 1;
      end
      CONFIG_WATCH_ELOW: begin
        e_out = 0;
        e_oe  = 1;
        d_out = 1;
        d_oe  = 1;
      end
      CONFIG_WATCH_DLOW: begin
        e_out = 0;
        e_oe  = 1;
        d_out = 0;
        d_oe  = 1;
      end
      CONGIG_WATCH_EHIGH: begin
        e_out = 1;
        e_oe  = 1;
        d_out = 0;
        d_oe  = 1;
      end
      default: begin
        d_out = 1;
        d_oe  = 0;
        e_out = 0;
        e_oe  = 0;
      end
    endcase
  end

  // Pulse and data counters
  // reg [31:0] time_ctr = 0;
  // always @(posedge clk) begin
  //   time_ctr <= time_ctr + 1;
  // end

  reg prev_d = 0;
  reg prev_d_1 = 0;
  reg prev_e = 1;
  reg prev_e_1 = 0;
  always @(posedge clk) begin
    if (configured) begin
      prev_d <= d_in;
      prev_d_1 <= d_in_1;
      prev_e <= e_in;
      prev_e_1 <= e_in_1;

      // Envelope start
      if ({prev_e, prev_e_1} == 2'b11 && {e_in, e_in_1} != 2'b11) begin
        timestamp[28:0] <= {time_ctr[27:0], e_in};
        envelope_valid <= 0;  // The envelope info are not valid anymore!
      end else if ({prev_e, prev_e_1} == 2'b00 && {e_in, e_in_1} != 2'b00) begin // Envelope stop
        length <= {time_ctr[27:0], ~e_in} - timestamp[28:0];
        envelope_valid <= 1;
      end else if (envelope_ready && envelope_valid) envelope_valid <= 0;
    end

    timestamp[31:29] <= SENSOR_ID;
  end

  // Fifo interface management (no overflow handling!)
  initial envelope_valid = 0;
  always @(posedge clk) begin
    if (data_ready && data_valid) data_valid <= 0;
  end


endmodule // pulseProcessor