// Simulation model for the TS4231. This model is obviously not synthesizable.
// Simulates the digital part of the chip. The testbench must provide envelope
// and data in the same format they will appear on the e and d pins when the
// chip is configured

// The chip is not extensively documented so there has been a lot of guess
// to write this modelisation. It is accurate enough to behave the same way as
// the real chip when initialized from the arduino code. This should be good
// enough to implement the FPGA part of a lighthouse receiver.

module ts4231(
  // Simulated diode
  input ir_e,
  input ir_d,

  // IO of the chip
  inout e,
  inout d
);


  localparam POWERED = 0;
  localparam UNCONFIGURED = 1;
  localparam CONFIG = 2;
  localparam CONFIGURED_SLEEP = 3;
  localparam WATCH = 4;
  localparam SLEEP = 5;
  integer state = POWERED;
  reg [255*8:0] state_str;

  always @(state) begin
    case (state)
      POWERED          : state_str = "powered";
      UNCONFIGURED     : state_str = "unconfigured";
      CONFIG           : state_str = "config";
      CONFIGURED_SLEEP : state_str = "configured_sleep";
      WATCH            : state_str = "watch";
      SLEEP            : state_str = "sleep";
    endcase
  end

  // pull and drive of the ios
  reg e_drive = 1'bz;
  reg e_pull = 1'b0;
  reg d_drive = 1'bz;
  reg d_pull = 1'b0;

  assign (pull0, pull1) e = e_pull;
  assign (strong0, strong1) e = e_drive;
  assign (pull0, pull1) d = d_pull;
  assign (strong0, strong1) d = d_drive;

  always @(*) begin
    if (state == POWERED) begin
      d_drive = (ir_e == 0)?1:1'bz;

      if (ir_e == 0) state <= UNCONFIGURED;
    end

    if (state == UNCONFIGURED) begin
      d_drive = (ir_e == 0)?1:1'bz;

      if (e == 1) state <= SLEEP;
    end

    if (state == SLEEP) begin
      d_pull <= 1;
      e_pull <= 1; // Assumed
    end

    if (state == WATCH) begin
      e_pull = 1;
      e_drive = (ir_e == 0)?0:1'bz;
      d_pull = 0;
      // This is an aproximation: the real chip likely drives the data pin with strong PP
      // and not with OC io like simulated. Simulating PP io would require to add
      // an ir_d_valid input to the module.
      d_drive = (ir_d == 1)?1:1'bz;

      // if (e == 0 && ir_e == 1) state <= SLEEP;
    end

    if (state == SLEEP) begin
      e_pull = 1;
      d_pull = 1;
      e_drive = 1'bz;
      d_drive = 1'bz;
    end
  end

  // Configuration register and state
  integer config_bit;
  
  localparam CONFIG_DIR = 0;
  localparam CONFIG_READ = 1;
  localparam CONFIG_WRITE = 2;
  integer config_state;

  reg [13:0] config_reg;

  always @(negedge d) begin
    if (state == SLEEP && e == 1) begin
      state = CONFIG;
      d_drive = 1'bz;
      e_drive = 1'bz;
      e_pull = 1'bz;
      d_pull = 0;
      config_state = CONFIG_DIR;
      config_bit = 13;
    end
    if (state == SLEEP && e == 0) state <= WATCH;
  end

  always @(posedge d) begin
    if (state == CONFIG && e == 1) state = SLEEP;
  end

  always @(negedge e) begin
    if (state == WATCH && ir_e == 1) state = SLEEP; // This is an aproximation.

    if (state == CONFIG && config_state == CONFIG_READ && config_bit >= 0) begin
      d_pull <= config_reg[config_bit];
      config_bit <= config_bit - 1;
    end
  end

  always @(posedge e) begin
    if (state == CONFIG) begin
      if (config_state == CONFIG_DIR) begin
        if (d == 0) config_state <= CONFIG_WRITE;
        else config_state <= CONFIG_READ;
      end else if (config_state == CONFIG_WRITE && config_bit >= 0) begin
        config_reg[config_bit] <= d;
        config_bit <= config_bit - 1;
      end
    end
  end

endmodule // ts4231