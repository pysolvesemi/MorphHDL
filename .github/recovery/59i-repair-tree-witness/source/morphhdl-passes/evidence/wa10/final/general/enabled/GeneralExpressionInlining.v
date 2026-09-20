module GeneralExpressionInlining #(
  parameter integer PPC4 = 0
) (
  input  wire [15:0]   word,
  input  wire [15:0]   other,
  input  wire [12:0]   total,
  input  wire [12:0]   index,
  input  wire [7:0]    narrow,
  input  wire [8:0]    unsignedWide,
  input  wire signed [7:0] signedA,
  input  wire signed [7:0] signedB,
  input  wire          choose,
  input  wire          load,
  input  wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0]    laneMaskIn,
  output wire          constantOK,
  output wire          extensionOK,
  output wire          subtractionOK,
  output wire          signedOK,
  output wire [15:0]   constantMux,
  output wire [17:0]   extensionSum,
  output wire [17:0]   muxResult,
  output wire [12:0]   subtractionMux,
  output wire [7:0]    wrappedAdd,
  output wire [7:0]    wrappedSub,
  output wire [8:0]    widenedAdd,
  output wire [8:0]    widenedSub,
  output wire signed [11:0] signedExtended,
  output wire signed [9:0] mixedSigned,
  output wire [4:0]    truncated,
  output wire [6:0]    sliced,
  output wire [11:0]   registerResult,
  output wire [12:0]   keptResult,
  output wire [12:0]   guardedResult,
  output wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0]    laneMaskOut,
  input  wire          clk,
  input  wire          reset
);

  wire       signed [9:0] _zz_mixedSigned;
  wire       signed [9:0] _zz_mixedSigned_1;
  wire       [9:0]    _zz_mixedSigned_2;
  wire       [17:0]   _zz_truncated;
  wire       [12:0]   _zz_sliced;
  wire       [12:0]   _zz_sliced_1;
  wire       [17:0]   _zz_state;
  wire       signed [7:0] _zz_signedOK;
  reg        [11:0]   state;
  (* keep *) wire       [12:0]   protectedDifference;
  wire       [12:0]   vitalDifference;

  assign _zz_mixedSigned = {{2{signedA[7]}}, signedA};
  assign _zz_mixedSigned_1 = _zz_mixedSigned_2;
  assign _zz_mixedSigned_2 = {1'd0, unsignedWide};
  assign _zz_truncated = ({2'd0, word} + {2'd0, other});
  assign _zz_sliced = _zz_sliced_1;
  assign _zz_sliced_1 = (total - 13'h0001);
  assign _zz_state = ({2'd0, word} + {2'd0, other});
  assign constantOK = (16'h0001 <= word);
  assign constantMux = (choose ? 16'h0001 : other);
  assign extensionSum = ({2'd0, word} + {2'd0, other});
  assign extensionOK = ({2'd0, word} <= 18'h0ffff);
  assign subtractionOK = (index == (total - 13'h0001));
  assign subtractionMux = (choose ? (total - 13'h0001) : index);
  assign wrappedAdd = (narrow + 8'h01);
  assign widenedAdd = {1'd0, (narrow + 8'h01)};
  assign wrappedSub = (narrow - 8'h01);
  assign widenedSub = {1'd0, (narrow - 8'h01)};
  assign _zz_signedOK = (signedA - 8'sh01);
  assign signedOK = (signedB < _zz_signedOK);
  assign signedExtended = {{4{_zz_signedOK[7]}}, _zz_signedOK};
  assign mixedSigned = (_zz_mixedSigned + _zz_mixedSigned_1);
  assign truncated = _zz_truncated[4:0];
  assign sliced = _zz_sliced[8 : 2];
  assign muxResult = ((choose ? {2'd0, word} : {2'd0, other}) + 18'h00001);
  assign registerResult = state;
  assign protectedDifference = (total - 13'h0001);
  assign keptResult = protectedDifference;
  assign vitalDifference = (total - 13'h0001);
  assign guardedResult = vitalDifference;
  assign laneMaskOut = laneMaskIn;
  always @(posedge clk) begin
    if(reset) begin
      state <= 12'h0;
    end else begin
      if(load) begin
        state <= _zz_state[11:0];
      end
    end
  end


endmodule
