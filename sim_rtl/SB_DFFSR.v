module SB_DFFSR (output Q, input C, R, D);
	reg Q = 0;
	always @(posedge C)
		if (R)
			Q <= 0;
		else
			Q <= D;
endmodule
