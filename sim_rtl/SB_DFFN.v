module SB_DFFN (output Q, input C, D);
	reg Q = 0;
	always @(negedge C)
		Q <= D;
endmodule
