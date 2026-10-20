// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : WorkaroundWithInput
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps 
module WorkaroundWithInput #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  output wire [7:0]    value,
  input  wire          unrelated,
  output wire          passthrough
);

  wire       [5:0]    _zz_value_10;
  wire       [5:0]    _zz_value_11;
  wire       [5:0]    _zz_value_12;
  wire       [5:0]    _zz_value_13;
  reg        [5:0]    _zz_value_14;
  wire       [2:0]    _zz_value_15;
  reg        [5:0]    _zz_value_16;
  wire       [2:0]    _zz_value_17;
  wire       [5:0]    _zz_value_18;
  reg        [5:0]    _zz_value_19;
  wire       [2:0]    _zz_value_20;
  reg        [5:0]    _zz_value_21;
  wire       [2:0]    _zz_value_22;
  wire       [5:0]    _zz_value_23;
  wire       [5:0]    _zz_value_24;
  reg        [5:0]    _zz_value_25;
  wire       [2:0]    _zz_value_26;
  reg        [5:0]    _zz_value_27;
  wire       [2:0]    _zz_value_28;
  wire       [5:0]    _zz_value_29;
  reg        [5:0]    _zz_value_30;
  wire       [2:0]    _zz_value_31;
  reg        [5:0]    _zz_value_32;
  wire       [2:0]    _zz_value_33;
  wire       [5:0]    _zz_value_34;
  wire       [5:0]    _zz_value_35;
  reg        [5:0]    _zz_value_36;
  wire       [2:0]    _zz_value_37;
  reg        [5:0]    _zz_value_38;
  wire       [2:0]    _zz_value_39;
  reg        [5:0]    _zz_value_40;
  wire       [2:0]    _zz_value_41;
  wire       [1:0]    _zz_value_42;
  wire       [(1 << (BUS_LOG2_BYTES))-1:0]    zero;
  wire       [(1 << (BUS_LOG2_BYTES))-1:0]    _zz_value;
  wire       [31:0]   _zz_value_1;
  wire       [5:0]    _zz_value_2;
  wire       [5:0]    _zz_value_3;
  wire       [5:0]    _zz_value_4;
  wire       [5:0]    _zz_value_5;
  wire       [5:0]    _zz_value_6;
  wire       [5:0]    _zz_value_7;
  wire       [5:0]    _zz_value_8;
  wire       [5:0]    _zz_value_9;

  assign _zz_value_10 = (_zz_value_11 + _zz_value_34);
  assign _zz_value_11 = (_zz_value_12 + _zz_value_23);
  assign _zz_value_12 = (_zz_value_13 + _zz_value_18);
  assign _zz_value_13 = (_zz_value_14 + _zz_value_16);
  assign _zz_value_18 = (_zz_value_19 + _zz_value_21);
  assign _zz_value_23 = (_zz_value_24 + _zz_value_29);
  assign _zz_value_24 = (_zz_value_25 + _zz_value_27);
  assign _zz_value_29 = (_zz_value_30 + _zz_value_32);
  assign _zz_value_34 = (_zz_value_35 + _zz_value_40);
  assign _zz_value_35 = (_zz_value_36 + _zz_value_38);
  assign _zz_value_42 = {_zz_value_1[31],_zz_value_1[30]};
  assign _zz_value_41 = {1'd0, _zz_value_42};
  assign _zz_value_15 = {_zz_value_1[2],{_zz_value_1[1],_zz_value_1[0]}};
  assign _zz_value_17 = {_zz_value_1[5],{_zz_value_1[4],_zz_value_1[3]}};
  assign _zz_value_20 = {_zz_value_1[8],{_zz_value_1[7],_zz_value_1[6]}};
  assign _zz_value_22 = {_zz_value_1[11],{_zz_value_1[10],_zz_value_1[9]}};
  assign _zz_value_26 = {_zz_value_1[14],{_zz_value_1[13],_zz_value_1[12]}};
  assign _zz_value_28 = {_zz_value_1[17],{_zz_value_1[16],_zz_value_1[15]}};
  assign _zz_value_31 = {_zz_value_1[20],{_zz_value_1[19],_zz_value_1[18]}};
  assign _zz_value_33 = {_zz_value_1[23],{_zz_value_1[22],_zz_value_1[21]}};
  assign _zz_value_37 = {_zz_value_1[26],{_zz_value_1[25],_zz_value_1[24]}};
  assign _zz_value_39 = {_zz_value_1[29],{_zz_value_1[28],_zz_value_1[27]}};
  always @(*) begin
    case(_zz_value_15)
      3'b000 : _zz_value_14 = _zz_value_2;
      3'b001 : _zz_value_14 = _zz_value_3;
      3'b010 : _zz_value_14 = _zz_value_4;
      3'b011 : _zz_value_14 = _zz_value_5;
      3'b100 : _zz_value_14 = _zz_value_6;
      3'b101 : _zz_value_14 = _zz_value_7;
      3'b110 : _zz_value_14 = _zz_value_8;
      default : _zz_value_14 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_17)
      3'b000 : _zz_value_16 = _zz_value_2;
      3'b001 : _zz_value_16 = _zz_value_3;
      3'b010 : _zz_value_16 = _zz_value_4;
      3'b011 : _zz_value_16 = _zz_value_5;
      3'b100 : _zz_value_16 = _zz_value_6;
      3'b101 : _zz_value_16 = _zz_value_7;
      3'b110 : _zz_value_16 = _zz_value_8;
      default : _zz_value_16 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_20)
      3'b000 : _zz_value_19 = _zz_value_2;
      3'b001 : _zz_value_19 = _zz_value_3;
      3'b010 : _zz_value_19 = _zz_value_4;
      3'b011 : _zz_value_19 = _zz_value_5;
      3'b100 : _zz_value_19 = _zz_value_6;
      3'b101 : _zz_value_19 = _zz_value_7;
      3'b110 : _zz_value_19 = _zz_value_8;
      default : _zz_value_19 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_22)
      3'b000 : _zz_value_21 = _zz_value_2;
      3'b001 : _zz_value_21 = _zz_value_3;
      3'b010 : _zz_value_21 = _zz_value_4;
      3'b011 : _zz_value_21 = _zz_value_5;
      3'b100 : _zz_value_21 = _zz_value_6;
      3'b101 : _zz_value_21 = _zz_value_7;
      3'b110 : _zz_value_21 = _zz_value_8;
      default : _zz_value_21 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_26)
      3'b000 : _zz_value_25 = _zz_value_2;
      3'b001 : _zz_value_25 = _zz_value_3;
      3'b010 : _zz_value_25 = _zz_value_4;
      3'b011 : _zz_value_25 = _zz_value_5;
      3'b100 : _zz_value_25 = _zz_value_6;
      3'b101 : _zz_value_25 = _zz_value_7;
      3'b110 : _zz_value_25 = _zz_value_8;
      default : _zz_value_25 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_28)
      3'b000 : _zz_value_27 = _zz_value_2;
      3'b001 : _zz_value_27 = _zz_value_3;
      3'b010 : _zz_value_27 = _zz_value_4;
      3'b011 : _zz_value_27 = _zz_value_5;
      3'b100 : _zz_value_27 = _zz_value_6;
      3'b101 : _zz_value_27 = _zz_value_7;
      3'b110 : _zz_value_27 = _zz_value_8;
      default : _zz_value_27 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_31)
      3'b000 : _zz_value_30 = _zz_value_2;
      3'b001 : _zz_value_30 = _zz_value_3;
      3'b010 : _zz_value_30 = _zz_value_4;
      3'b011 : _zz_value_30 = _zz_value_5;
      3'b100 : _zz_value_30 = _zz_value_6;
      3'b101 : _zz_value_30 = _zz_value_7;
      3'b110 : _zz_value_30 = _zz_value_8;
      default : _zz_value_30 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_33)
      3'b000 : _zz_value_32 = _zz_value_2;
      3'b001 : _zz_value_32 = _zz_value_3;
      3'b010 : _zz_value_32 = _zz_value_4;
      3'b011 : _zz_value_32 = _zz_value_5;
      3'b100 : _zz_value_32 = _zz_value_6;
      3'b101 : _zz_value_32 = _zz_value_7;
      3'b110 : _zz_value_32 = _zz_value_8;
      default : _zz_value_32 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_37)
      3'b000 : _zz_value_36 = _zz_value_2;
      3'b001 : _zz_value_36 = _zz_value_3;
      3'b010 : _zz_value_36 = _zz_value_4;
      3'b011 : _zz_value_36 = _zz_value_5;
      3'b100 : _zz_value_36 = _zz_value_6;
      3'b101 : _zz_value_36 = _zz_value_7;
      3'b110 : _zz_value_36 = _zz_value_8;
      default : _zz_value_36 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_39)
      3'b000 : _zz_value_38 = _zz_value_2;
      3'b001 : _zz_value_38 = _zz_value_3;
      3'b010 : _zz_value_38 = _zz_value_4;
      3'b011 : _zz_value_38 = _zz_value_5;
      3'b100 : _zz_value_38 = _zz_value_6;
      3'b101 : _zz_value_38 = _zz_value_7;
      3'b110 : _zz_value_38 = _zz_value_8;
      default : _zz_value_38 = _zz_value_9;
    endcase
  end

  always @(*) begin
    case(_zz_value_41)
      3'b000 : _zz_value_40 = _zz_value_2;
      3'b001 : _zz_value_40 = _zz_value_3;
      3'b010 : _zz_value_40 = _zz_value_4;
      3'b011 : _zz_value_40 = _zz_value_5;
      3'b100 : _zz_value_40 = _zz_value_6;
      3'b101 : _zz_value_40 = _zz_value_7;
      3'b110 : _zz_value_40 = _zz_value_8;
      default : _zz_value_40 = _zz_value_9;
    endcase
  end

  assign zero = {(1 << (BUS_LOG2_BYTES)){1'b0}};
  assign _zz_value = (~ zero);
  assign _zz_value_1 = {{(((32) > ((1 << (BUS_LOG2_BYTES)))) ? ((32) - ((1 << (BUS_LOG2_BYTES)))) : 0){1'b0}}, _zz_value[((1 << (BUS_LOG2_BYTES)))-1:0]};
  assign _zz_value_2 = 6'h0;
  assign _zz_value_3 = 6'h01;
  assign _zz_value_4 = 6'h01;
  assign _zz_value_5 = 6'h02;
  assign _zz_value_6 = 6'h01;
  assign _zz_value_7 = 6'h02;
  assign _zz_value_8 = 6'h02;
  assign _zz_value_9 = 6'h03;
  assign value = {2'd0, _zz_value_10};
  assign passthrough = unrelated;

endmodule
