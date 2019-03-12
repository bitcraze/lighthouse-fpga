module spi(
  input clk,

  input si,
  input n_cs,
  input sck,
  output so,
  output so_oe,

  output start,
  output reg [7:0] data_rx,
  output reg valid_rx,
  input [7:0] data_tx,
  input valid_tx,
  output reg ready_tx
);

(* keep *) reg [7:0] shift_reg=0;
reg sampled_bit;
reg [2:0] count;
reg [7:0] buffer;

reg prev_n_cs = 1;
reg prev_sck = 0;

assign start = prev_n_cs && ~n_cs;

assign so = shift_reg[7];
assign so_oe = ~n_cs;

always @(posedge clk) begin
  prev_n_cs <= n_cs;
  prev_sck <= sck;
  valid_rx <= 0;

  if (start) begin
    count <= 0;
    ready_tx <= 1;
  end

  // Outputing new data on sck falling edge
  if (~n_cs && prev_sck == 1 && sck == 0) begin
    if (count == 0) shift_reg <= buffer;
    else shift_reg <= {shift_reg[6:0], sampled_bit};
  end

  // Aquiring data and counting on sck rising edge
  if (~n_cs && prev_sck == 0 && sck == 1) begin
    count <= count + 1;
    sampled_bit <= si;
    if (count == 7) begin
      data_rx <= {shift_reg[6:0], si};
      valid_rx <= 1;
      ready_tx <= 1;
    end
  end

  // TX buffer handling
  if (valid_tx && ready_tx) begin
    buffer <= data_tx;
    ready_tx <= 0;
  end
end


endmodule // spi