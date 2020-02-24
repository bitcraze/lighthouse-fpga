module SB_DFFSS (output Q, input C, S, D);
	reg Q = 0;
	always @(posedge C)
		if (S)
			Q <= 1;
		else
			Q <= D;
endmodule
