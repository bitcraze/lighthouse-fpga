module testbench;
    localparam integer PERIOD = 10;

    reg clk;
	always #5 clk = (clk === 1'b0);


	// reg clk = 0;
	// initial #10 forever #5 clk = ~clk;

    reg sck = 0;
    reg si = 0;
    wire so;
    wire so_oe;
    reg n_cs = 1;
    wire ready_tx;
    reg valid_tx = 0;
    reg [7:0] data_tx = 0;



    spi uut
    (
      .clk (clk),
      .si(si),
      .so(so),
      .so_oe(so_oe),
      .sck(sck),
      .n_cs(n_cs),
      .valid_tx(valid_tx),
      .ready_tx(ready_tx),
      .data_tx(data_tx)
    );

    task send_byte;
		input [7:0] c;
		integer i;
        reg [7:0] received;
		begin
			for (i = 0; i < 8; i = i+1) begin
				si <= c[7-i];
				sck <= 0;
                repeat (PERIOD/2) @(posedge clk);
                sck = 1;
                received <= {received[6:0], so};
                repeat (PERIOD/2) @(posedge clk);
			end
            $display("%02x", received);
            sck <= 0;
		end
	endtask

    initial begin
        $dumpfile("spi_tb.vcd");
        $dumpvars(0, testbench);

        repeat(10) @(posedge clk);


        n_cs <= 0;
        repeat(PERIOD/2) @(posedge clk);
        send_byte(8'h00);
        send_byte(8'h01);
        send_byte(8'h02);
        send_byte(8'h03);
        repeat(PERIOD/2) @(posedge clk);
        n_cs <= 1;

        repeat(10) @(posedge clk);

        n_cs <= 0;
        repeat(PERIOD/2) @(posedge clk);
        send_byte(8'hbc);
        send_byte(8'hcf);
        send_byte(8'h42);
        repeat(PERIOD/2) @(posedge clk);
        n_cs <= 1;

        repeat(10) @(posedge clk);

        
        $finish;
    end

    initial begin
        repeat (10) begin
            @(posedge ready_tx);
            data_tx <= 8'hbc; 
            valid_tx <= 1;
            @(negedge ready_tx);
            valid_tx <= 0;
            @(posedge ready_tx);
            data_tx <= 8'hcf; 
            valid_tx <= 1;
            @(negedge ready_tx);
            valid_tx <= 0;
        end
    end

endmodule
