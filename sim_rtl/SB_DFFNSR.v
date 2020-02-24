module SB_DFFNSR (output Q, input C, R, D);
	reg Q = 0;
	always @(negedge C)
		if (R)
			Q <= 0;
		else
			Q <= D;
endmodule
