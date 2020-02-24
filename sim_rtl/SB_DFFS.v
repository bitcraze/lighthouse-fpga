module SB_DFFS (output Q, input C, S, D);
	reg Q = 0;
	always @(posedge C, posedge S)
		if (S)
			Q <= 1;
		else
			Q <= D;
endmodule
