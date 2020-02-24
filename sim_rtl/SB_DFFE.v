module SB_DFFE (output Q, input C, E, D);
	reg Q = 0;
	always @(posedge C)
		if (E)
			Q <= D;
endmodule
