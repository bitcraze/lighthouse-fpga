module SB_DFFER (output Q, input C, E, R, D);
	reg Q = 0;
	always @(posedge C, posedge R)
		if (R)
			Q <= 0;
		else if (E)
			Q <= D;
endmodule
