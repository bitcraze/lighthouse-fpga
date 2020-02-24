module SB_DFFES (output Q, input C, E, S, D);
	reg Q = 0;
	always @(posedge C, posedge S)
		if (S)
			Q <= 1;
		else if (E)
			Q <= D;
endmodule
