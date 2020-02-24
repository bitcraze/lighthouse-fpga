module SB_DFFNR (output Q, input C, R, D);
	reg Q = 0;
	always @(negedge C, posedge R)
		if (R)
			Q <= 0;
		else
			Q <= D;
endmodule
