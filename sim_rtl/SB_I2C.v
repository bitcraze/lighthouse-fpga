/* verilator lint_off UNUSED */
/* verilator lint_off UNDRIVEN */
(* blackbox *)
module SB_I2C(
	input  SBCLKI,
	input  SBRWI,
	input  SBSTBI,
	input  SBADRI7,
	input  SBADRI6,
	input  SBADRI5,
	input  SBADRI4,
	input  SBADRI3,
	input  SBADRI2,
	input  SBADRI1,
	input  SBADRI0,
	input  SBDATI7,
	input  SBDATI6,
	input  SBDATI5,
	input  SBDATI4,
	input  SBDATI3,
	input  SBDATI2,
	input  SBDATI1,
	input  SBDATI0,
	input  SCLI,
	input  SDAI,
	output SBDATO7,
	output SBDATO6,
	output SBDATO5,
	output SBDATO4,
	output SBDATO3,
	output SBDATO2,
	output SBDATO1,
	output SBDATO0,
	output SBACKO,
	output I2CIRQ,
	output I2CWKUP,
	output SCLO, //inout in the SB verilog library, but output in the VHDL and PDF libs and seemingly in the HW itself
	output SCLOE,
	output SDAO,
	output SDAOE
);
parameter I2C_SLAVE_INIT_ADDR = "0b1111100001";
parameter BUS_ADDR74 = "0b0001";
endmodule
/* verilator lint_on UNUSED */
/* verilator lint_on UNDRIVEN */
