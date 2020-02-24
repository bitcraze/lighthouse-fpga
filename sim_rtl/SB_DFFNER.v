module SB_DFFNER (output Q, input C, E, R, D);
	reg Q = 0;
	always @(negedge C, posedge R)
		if (R)
			Q <= 0;
		else if (E)
			Q <= D;
endmodule
