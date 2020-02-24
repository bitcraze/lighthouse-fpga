module SB_DFFNESR (output Q, input C, E, R, D);
	reg Q = 0;
	always @(negedge C)
		if (E) begin
			if (R)
				Q <= 0;
			else
				Q <= D;
		end
endmodule
