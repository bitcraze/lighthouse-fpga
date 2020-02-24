module SB_DFF (output Q, input C, D);
	reg Q = 0;
	always @(posedge C)
		Q <= D;
endmodule
