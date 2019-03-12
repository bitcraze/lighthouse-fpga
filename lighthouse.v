`include "pulseProcessor.v"
`include "spi.v"
`include "multiPulseFifo.v"
`include "uart.v"
`include "synchronizer.v"
`include "pll.v"

module top #(
  parameter N_SENSORS=2,
  parameter UART_BAUDRATE=115200,
  parameter FORCE_LED=0
)(
  input clk12,

  inout [N_SENSORS-1:0] e,
  inout [N_SENSORS-1:0] d,

  input uart_rx, output uart_tx,

  // Debug LEDs
  output [2:0] leds
);

  /////////////////////////////////////////////
  // PLL, clk is at 24MHz
  /////////////////////////////////////////////
  wire clk;
  pll pll (
    .clock_in(clk12),
    .clock_out(clk)
  );

  /////////////////////////////////////////////
  // Pulse acquisition and pulse FIFO
  /////////////////////////////////////////////
  reg configure = 0;

  wire [(N_SENSORS*32)-1:0] pp_timestamp;
  wire [(N_SENSORS*16)-1:0] pp_length;
  wire [N_SENSORS-1:0] pp_envelope_valid;
  wire [N_SENSORS-1:0] pp_envelope_ready;

  reg [31:0] time_ctr = 0;
  always @(posedge clk) begin
    time_ctr <= time_ctr + 1;
  end

  generate
    genvar i;
    for (i=0; i<N_SENSORS; i = i+1) begin
      pulseProcessor #(
        .SENSOR_ID(i)  
      ) pulseProcessor (
        .clk(clk),
        .reconfigure(configure),
        .e(e[i]),
        .d(d[i]),

        .timestamp(pp_timestamp[(i*32) +: 32]),
        .length(pp_length[(i*16) +: 16]),
        .envelope_valid(pp_envelope_valid[i]),
        .envelope_ready(pp_envelope_ready[i]),

        .time_ctr(time_ctr)
      );
    end
  endgenerate

  wire fifo_valid; // = pp_envelope_valid;
  wire fifo_ready;
  wire [31:0] fifo_timestamp; //  = pp_timestamp;
  wire [15:0] fifo_length; // = pp_lentgh;
  reg mesurement_in_buffer = 0;
  wire [7:0] fifo_usedSlots;
  reg data_in_buffer = 0;



  multiPulseFifo #(
    .DEPTH(254),
    .N_INPUT(N_SENSORS)
  ) pulseFifo (
    .clk(clk),
    .in_valid(pp_envelope_valid),
    .in_ts(pp_timestamp),
    .in_length(pp_length),
    .in_ready(pp_envelope_ready),

    .out_valid(fifo_valid),
    .out_ts(fifo_timestamp),
    .out_length(fifo_length),
    .out_ready(fifo_ready),

    .used(fifo_usedSlots)
  );


  /////////////////////////////////////////////
  // Automatic configuration enable at startup
  /////////////////////////////////////////////
  reg [3:0] configCtn = 0;

  always @(posedge clk) begin
    configCtn <= configCtn + 1;
    if (configCtn == 4'hf) configure <= 1;
  end


  /////////////////////////////////////////////
  // LED Debugging
  /////////////////////////////////////////////
  assign leds = FORCE_LED?3'b000:3'b111;

  // // Debug LEDs
  // reg led_r = 0, led_g = 0, led_b = 0;
  // SB_RGBA_DRV #(
  //   .CURRENT_MODE("0b1"),
  //   .RGB0_CURRENT("0b000001"),
  //   .RGB1_CURRENT("0b000001"),
  //   .RGB2_CURRENT("0b000001")
  // ) RGBA_DRIVER (
  //   .CURREN(1'b1),
  //   .RGBLEDEN(1'b1),
  //   .RGB0PWM(led_r),
  //   .RGB1PWM(led_y),
  //   .RGB2PWM(led_g),
  //   .RGB0(leds[0]),
  //   .RGB1(leds[1]),
  //   .RGB2(leds[2])
  // );

  // always @(posedge clk) begin
  //   led_g <= fifo_usedSlots != 0;

  //   led_y <= ~n_cs;

  //   if (spi_start) led_r <= ~led_r;
  // end

  /////////////////////////////////////////////
  // UART State machine
  /////////////////////////////////////////////

  wire sync_valid;
  wire [55:0] sync_data;

  synchronizer #(
    .PERIOD(6000000)  // Generate sync twice per seconds
  ) synchronizer (
    .clk(clk),

    .in_valid(fifo_valid),
    .in_data({fifo_length, fifo_timestamp}),
    .in_ready(fifo_ready),

    .out_valid(sync_valid),
    .out_data(sync_data),
    .out_ready(~data_in_buffer)
  );

  reg uart_valid = 0;
  wire uart_ready;
  wire [7:0] uart_data;

  uart #(
    .BAUDSEL(24000000 / (2*UART_BAUDRATE))
  ) uart (
    .clk(clk),
    .tx_valid(data_in_buffer),
    .tx_ready(uart_ready),
    .tx_data(uart_data),
    .tx(uart_tx)
  );

  reg [55:0] buffered_measurement = 0;
  reg [2:0] byte_ctr;
  assign uart_data = buffered_measurement[8*byte_ctr +: 8];

  reg [15:0] uart_ctr = 0;

  always @(posedge clk) begin
    if (sync_valid && !data_in_buffer) begin
      buffered_measurement <= sync_data;
      data_in_buffer <= 1;
      byte_ctr <= 0;
    end

    if (data_in_buffer && uart_ready) begin
      byte_ctr <= byte_ctr + 1;
    end

    if (byte_ctr == 7) begin
      data_in_buffer <= 0;
      byte_ctr <= 0;
    end
  end
endmodule