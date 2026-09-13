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
  wire       [17:0]   _zz_truncated_1;
  wire       [17:0]   _zz_truncated_2;
  wire       [12:0]   _zz_sliced;
  wire       [12:0]   _zz_sliced_1;
  wire       [17:0]   _zz__zz_muxResult;
  wire       [17:0]   _zz__zz_muxResult_1;
  wire       [15:0]   _zz_constantOK;
  wire       [17:0]   _zz_extensionOK;
  wire       [12:0]   _zz_subtractionOK;
  wire       [7:0]    _zz_wrappedAdd;
  wire       [7:0]    _zz_wrappedSub;
  wire       signed [7:0] _zz_signedOK;
  wire       [17:0]   _zz_muxResult;
  wire       [17:0]   _zz_state;
  reg        [11:0]   state;
  (* keep *) wire       [12:0]   protectedDifference;
  wire       [12:0]   vitalDifference;

  assign _zz_mixedSigned = {{2{signedA[7]}}, signedA};
  assign _zz_mixedSigned_1 = _zz_mixedSigned_2;
  assign _zz_mixedSigned_2 = {1'd0, unsignedWide};
  assign _zz_truncated = (_zz_truncated_1 + _zz_truncated_2);
  assign _zz_truncated_1 = {2'd0, word};
  assign _zz_truncated_2 = {2'd0, other};
  assign _zz_sliced = _zz_sliced_1;
  assign _zz_sliced_1 = (total - 13'h0001);
  assign _zz__zz_muxResult = {2'd0, word};
  assign _zz__zz_muxResult_1 = {2'd0, other};
  assign _zz_constantOK = 16'h0001;
  assign constantOK = (_zz_constantOK <= word);
  assign constantMux = (choose ? _zz_constantOK : other);
  assign _zz_extensionOK = {2'd0, word};
  assign extensionSum = (_zz_extensionOK + {2'd0, other});
  assign extensionOK = (_zz_extensionOK <= 18'h0ffff);
  assign _zz_subtractionOK = (total - 13'h0001);
  assign subtractionOK = (index == _zz_subtractionOK);
  assign subtractionMux = (choose ? _zz_subtractionOK : index);
  assign _zz_wrappedAdd = (narrow + 8'h01);
  assign wrappedAdd = _zz_wrappedAdd;
  assign widenedAdd = {1'd0, _zz_wrappedAdd};
  assign _zz_wrappedSub = (narrow - 8'h01);
  assign wrappedSub = _zz_wrappedSub;
  assign widenedSub = {1'd0, _zz_wrappedSub};
  assign _zz_signedOK = (signedA - 8'sh01);
  assign signedOK = (signedB < _zz_signedOK);
  assign signedExtended = {{4{_zz_signedOK[7]}}, _zz_signedOK};
  assign mixedSigned = (_zz_mixedSigned + _zz_mixedSigned_1);
  assign truncated = _zz_truncated[4:0];
  assign sliced = _zz_sliced[8 : 2];
  assign _zz_muxResult = (choose ? _zz__zz_muxResult : _zz__zz_muxResult_1);
  assign muxResult = (_zz_muxResult + 18'h00001);
  assign _zz_state = ({2'd0, word} + {2'd0, other});
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
