module SB_DFFNSS (output Q, input C, S, D);
	reg Q = 0;
	always @(negedge C)
		if (S)
			Q <= 1;
		else
			Q <= D;
endmodule
