module SB_DFFNS (output Q, input C, S, D);
	reg Q = 0;
	always @(negedge C, posedge S)
		if (S)
			Q <= 1;
		else
			Q <= D;
endmodule
