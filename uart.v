module uart #(
    parameter BAUDSEL = 10
) (
    input clk,

    input rx,
    output tx,

    input tx_valid,
    input [7:0] tx_data,
    output tx_ready,

    output reg rx_valid,
    output reg [7:0] rx_data,
    input rx_ready,

    // Goes up one clock cycle when the break condition ends
    output reg rx_break
);

  // Receiver implementation
  localparam RX_IDLE = 0;
  localparam RX_START = 1;
  localparam RX_DATA = 2;
  localparam RX_STOP  = 3;
  localparam RX_BREAK = 4;
  localparam RX_ERROR = 5;

  reg [2:0] rx_state = RX_IDLE;
  reg [2:0] rx_bit_counter;
  reg [7:0] rx_buffer;
  
  // Baudrate counter
  reg [$clog2(3*BAUDSEL):0] rx_counter;

  // RX state machine
  always @(posedge clk) begin
    if (rx_state == RX_IDLE) begin
      if (rx == 0) begin
        // Synchronize on start
        rx_state <= RX_START;
        rx_counter <= BAUDSEL;
        rx_buffer <= 0;
      end
    end else begin
      if (rx_counter == BAUDSEL*2) begin
        rx_counter <= 0;

        // time to read a bit
        if (rx_state == RX_START) begin
          if (rx == 0) rx_state <= RX_DATA;
          else rx_state <= RX_IDLE; // Glitch
          rx_bit_counter <= 0;
        end

        if (rx_state == RX_DATA) begin
          rx_bit_counter <= rx_bit_counter + 1;
          if (rx_bit_counter == 7) rx_state <= RX_STOP;
          rx_buffer <= {rx, rx_buffer[7:1]};
        end

        if (rx_state == RX_STOP) begin
          if (rx == 1) rx_state <= RX_IDLE;
          else if (rx_buffer == 0) rx_state <= RX_BREAK;
          else rx_state <= RX_ERROR; // Framing error
        end

        if ((rx_state == RX_ERROR || rx_state == RX_BREAK) && rx == 1) begin
          rx_state <= RX_IDLE;
        end
      end else begin
        rx_counter <= rx_counter + 1;
      end
    end
  end

  initial rx_valid = 0;

  // module interface management
  reg [2:0] rx_prevState;
  always @(posedge clk) begin
    if (rx_prevState == RX_STOP && rx_state == RX_IDLE) begin
      // Transition from stop to idle means that we have received a byte
      rx_valid <= 1;
      rx_data <= rx_buffer;
    end

    if (rx_prevState == RX_BREAK && rx_state == RX_IDLE) begin
      // Transition from break to idle: we had a break condition
      rx_break <= 1;
    end else rx_break <= 0;

    if (rx_ready && rx_valid) begin
      rx_valid <= 0;
    end

    rx_prevState <= rx_state;
  end

  // Transmitter implementation
  reg [7:0] tx_buffer;
  localparam TX_IDLE = 0;
  localparam TX_START = 1;
  localparam TX_DATA = 2;
  localparam TX_STOP = 3;
  reg [1:0] tx_state = TX_IDLE;
  reg [$clog2(3*BAUDSEL):0] tx_counter;
  reg [2:0] tx_bitcount;

  assign tx_ready = tx_state == TX_IDLE;

  always @(posedge clk) begin
    if (tx_state == TX_IDLE) begin
      if (tx_valid) begin
        tx_state <= TX_START;
        tx_counter <= 0;
        tx_bitcount <= 0;
        tx_buffer <= tx_data;
      end
    end else if (tx_counter == 2*BAUDSEL) begin
      tx_counter <= 0;
      if (tx_state == TX_START) tx_state <= TX_DATA;
      else if (tx_state == TX_DATA) begin
        tx_bitcount <= tx_bitcount + 1;
        tx_buffer <= {1'b0, tx_buffer[7:1]};

        if (tx_bitcount == 7) tx_state <= TX_STOP;
      end else if (tx_state == TX_STOP) tx_state <= TX_IDLE;
    end else tx_counter <= tx_counter + 1;
  end

  assign tx = (tx_state == TX_START)?0:(tx_state == TX_DATA)?tx_buffer[0]:1;

endmodule