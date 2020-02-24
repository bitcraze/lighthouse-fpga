module SB_DFFNES (output Q, input C, E, S, D);
	reg Q = 0;
	always @(negedge C, posedge S)
		if (S)
			Q <= 1;
		else if (E)
			Q <= D;
endmodule
