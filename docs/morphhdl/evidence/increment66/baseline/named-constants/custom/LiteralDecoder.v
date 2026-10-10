// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : LiteralDecoder
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps

module LiteralDecoder (
  input  wire [7:0]    address,
  output wire          hit,
  output reg  [7:0]    decoded
);


  assign hit = (address == 8'h04);
  always @(*) begin
    decoded = 8'h0;
    case(address)
      8'h04 : begin
        decoded = 8'h01;
      end
      8'h08 : begin
        decoded = 8'h02;
      end
      default : begin
      end
    endcase
  end


endmodule
