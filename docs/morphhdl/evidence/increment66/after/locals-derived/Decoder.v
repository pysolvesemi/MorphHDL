// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : Decoder
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module Decoder #(
  parameter integer BASE_WORD = 1
) (
  input  wire [7:0]    address,
  input  wire          writeFire,
  input  wire [3:0]    strobes,
  output wire          hit,
  output reg  [7:0]    decoded,
  output wire [7:0]    summed,
  output wire [15:0]   concatenated,
  output wire [7:0]    shifted,
  output wire          less,
  output reg  [3:0]    selectedStrobes
);
  localparam [7:0] ADDR_CONTROL = (BASE_WORD * 4);
  localparam [7:0] ADDR_STATUS = (ADDR_CONTROL + 4);


  assign concatenated = {ADDR_STATUS,ADDR_CONTROL};
  assign shifted = (address <<< ADDR_STATUS);
  assign less = (address < ADDR_CONTROL);
  assign hit = (address == ADDR_CONTROL);
  assign summed = (address + ADDR_STATUS);
  always @(*) begin
    decoded = 8'h0;
    case(address)
      ADDR_CONTROL : begin
        decoded = 8'h01;
      end
      ADDR_STATUS : begin
        decoded = 8'h02;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    selectedStrobes = 4'b0000;
    if(writeFire) begin
      case(address)
        ADDR_CONTROL : begin
          selectedStrobes = strobes;
        end
        default : begin
        end
      endcase
    end
  end


endmodule
