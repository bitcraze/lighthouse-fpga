module SB_DFFR (output Q, input C, R, D);
	reg Q = 0;
	always @(posedge C, posedge R)
		if (R)
			Q <= 0;
		else
			Q <= D;
endmodule
