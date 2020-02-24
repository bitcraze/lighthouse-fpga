module ts4231Configurator (
  input clk,

  input reconfigure,
  output reg configured,

  input d_in,
  output reg d_out,
  output reg d_oe,

  input e_in,
  output reg e_out,
  output reg e_oe
);

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
  reg [14:0] config_value = {14'h392b, 1'b0};
  reg config_bit = 0;
  always @(posedge clk) begin
    if (config_enable) begin
      prev_reconfigure <= reconfigure;
      config_prev_d <= d_in;

      case (config_state)
        CONFIG_IDLE: if (prev_reconfigure == 0 && reconfigure == 1) config_state <= CONFIG_WAIT_PULSE;
        CONFIG_WAIT_PULSE: if (config_prev_d == 1 && d_in == 0) config_state <= CONFIG_START_CFG;
        CONFIG_START_CFG: config_state <= CONFIG_WRITE_START;
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
        e_out = 1;
        e_oe  = 1;
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
        d_out = 0;
        d_oe  = 0;
        e_out = 1;
        e_oe  = 0;
      end
    endcase
  end
endmodule