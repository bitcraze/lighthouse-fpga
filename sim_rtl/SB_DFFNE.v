module SB_DFFNE (output Q, input C, E, D);
	reg Q = 0;
	always @(negedge C)
		if (E)
			Q <= D;
endmodule
