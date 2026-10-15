// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : LiteralAxiLite
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps

module LiteralAxiLite (
  input  wire          _zz_factory_writeJoinEvent_valid,
  output wire          _zz_1,
  input  wire [7:0]    _zz_factory_writeAddressMasked,
  input  wire [2:0]    _zz_2,
  input  wire          _zz_factory_writeJoinEvent_valid_1,
  output wire          _zz_3,
  input  wire [31:0]   _zz_register,
  input  wire [3:0]    _zz_4,
  output wire          _zz_5,
  input  wire          _zz_factory_writeJoinEvent_translated_haltWhen_halfPipe_ready,
  output wire [1:0]    _zz_6,
  input  wire          _zz_7,
  output wire          _zz_8,
  input  wire [7:0]    _zz_factory_readDataStage_payload_addr,
  input  wire [2:0]    _zz_factory_readDataStage_payload_prot,
  output wire          _zz_factory_readOccur,
  input  wire          _zz_factory_readDataStage_haltWhen_translated_ready,
  output wire [31:0]   _zz_9,
  output wire [1:0]    _zz_10,
  output wire [31:0]   observed,
  output wire [7:0]    events,
  input  wire          clk,
  input  wire          reset
);

  wire                factory_readErrorFlag;
  wire                factory_writeErrorFlag;
  wire                factory_readHaltRequest;
  wire                factory_writeHaltRequest;
  wire                factory_writeJoinEvent_valid;
  wire                factory_writeJoinEvent_ready;
  wire                factory_writeOccur;
  reg        [1:0]    factory_writeRsp_resp;
  wire                factory_writeJoinEvent_translated_valid;
  wire                factory_writeJoinEvent_translated_ready;
  wire       [1:0]    factory_writeJoinEvent_translated_payload_resp;
  wire                _zz_factory_writeJoinEvent_translated_ready;
  wire                factory_writeJoinEvent_translated_haltWhen_valid;
  wire                factory_writeJoinEvent_translated_haltWhen_ready;
  wire       [1:0]    factory_writeJoinEvent_translated_haltWhen_payload_resp;
  wire                factory_writeJoinEvent_translated_haltWhen_halfPipe_valid;
  wire                factory_writeJoinEvent_translated_haltWhen_halfPipe_ready;
  wire       [1:0]    factory_writeJoinEvent_translated_haltWhen_halfPipe_payload_resp;
  reg                 factory_writeJoinEvent_translated_haltWhen_rValid;
  wire                factory_writeJoinEvent_translated_haltWhen_halfPipe_fire;
  reg        [1:0]    factory_writeJoinEvent_translated_haltWhen_rData_resp;
  wire                factory_readDataStage_valid;
  wire                factory_readDataStage_ready;
  wire       [7:0]    factory_readDataStage_payload_addr;
  wire       [2:0]    factory_readDataStage_payload_prot;
  reg                 _zz_factory_readDataStage_valid;
  wire                factory_readDataStage_fire;
  reg        [7:0]    _zz_factory_readDataStage_payload_addr_1;
  reg        [2:0]    _zz_factory_readDataStage_payload_prot_1;
  reg        [31:0]   factory_readRsp_data;
  reg        [1:0]    factory_readRsp_resp;
  wire                _zz_factory_readDataStage_ready;
  wire                factory_readDataStage_haltWhen_valid;
  wire                factory_readDataStage_haltWhen_ready;
  wire       [7:0]    factory_readDataStage_haltWhen_payload_addr;
  wire       [2:0]    factory_readDataStage_haltWhen_payload_prot;
  wire                factory_readDataStage_haltWhen_translated_valid;
  wire                factory_readDataStage_haltWhen_translated_ready;
  wire       [31:0]   factory_readDataStage_haltWhen_translated_payload_data;
  wire       [1:0]    factory_readDataStage_haltWhen_translated_payload_resp;
  wire       [7:0]    factory_readAddressMasked;
  wire       [7:0]    factory_writeAddressMasked;
  wire                factory_readOccur;
  reg        [31:0]   register_1;
  reg        [7:0]    count;

  assign factory_readErrorFlag = 1'b0;
  assign factory_writeErrorFlag = 1'b0;
  assign factory_readHaltRequest = 1'b0;
  assign factory_writeHaltRequest = 1'b0;
  assign factory_writeOccur = (factory_writeJoinEvent_valid && factory_writeJoinEvent_ready);
  assign factory_writeJoinEvent_valid = (_zz_factory_writeJoinEvent_valid && _zz_factory_writeJoinEvent_valid_1);
  assign _zz_1 = factory_writeOccur;
  assign _zz_3 = factory_writeOccur;
  assign factory_writeJoinEvent_translated_valid = factory_writeJoinEvent_valid;
  assign factory_writeJoinEvent_ready = factory_writeJoinEvent_translated_ready;
  assign factory_writeJoinEvent_translated_payload_resp = factory_writeRsp_resp;
  assign _zz_factory_writeJoinEvent_translated_ready = (! factory_writeHaltRequest);
  assign factory_writeJoinEvent_translated_haltWhen_valid = (factory_writeJoinEvent_translated_valid && _zz_factory_writeJoinEvent_translated_ready);
  assign factory_writeJoinEvent_translated_ready = (factory_writeJoinEvent_translated_haltWhen_ready && _zz_factory_writeJoinEvent_translated_ready);
  assign factory_writeJoinEvent_translated_haltWhen_payload_resp = factory_writeJoinEvent_translated_payload_resp;
  assign factory_writeJoinEvent_translated_haltWhen_halfPipe_fire = (factory_writeJoinEvent_translated_haltWhen_halfPipe_valid && factory_writeJoinEvent_translated_haltWhen_halfPipe_ready);
  assign factory_writeJoinEvent_translated_haltWhen_ready = (! factory_writeJoinEvent_translated_haltWhen_rValid);
  assign factory_writeJoinEvent_translated_haltWhen_halfPipe_valid = factory_writeJoinEvent_translated_haltWhen_rValid;
  assign factory_writeJoinEvent_translated_haltWhen_halfPipe_payload_resp = factory_writeJoinEvent_translated_haltWhen_rData_resp;
  assign _zz_5 = factory_writeJoinEvent_translated_haltWhen_halfPipe_valid;
  assign factory_writeJoinEvent_translated_haltWhen_halfPipe_ready = _zz_factory_writeJoinEvent_translated_haltWhen_halfPipe_ready;
  assign _zz_6 = factory_writeJoinEvent_translated_haltWhen_halfPipe_payload_resp;
  assign factory_readDataStage_fire = (factory_readDataStage_valid && factory_readDataStage_ready);
  assign _zz_8 = (! _zz_factory_readDataStage_valid);
  assign factory_readDataStage_valid = _zz_factory_readDataStage_valid;
  assign factory_readDataStage_payload_addr = _zz_factory_readDataStage_payload_addr_1;
  assign factory_readDataStage_payload_prot = _zz_factory_readDataStage_payload_prot_1;
  assign _zz_factory_readDataStage_ready = (! factory_readHaltRequest);
  assign factory_readDataStage_haltWhen_valid = (factory_readDataStage_valid && _zz_factory_readDataStage_ready);
  assign factory_readDataStage_ready = (factory_readDataStage_haltWhen_ready && _zz_factory_readDataStage_ready);
  assign factory_readDataStage_haltWhen_payload_addr = factory_readDataStage_payload_addr;
  assign factory_readDataStage_haltWhen_payload_prot = factory_readDataStage_payload_prot;
  assign factory_readDataStage_haltWhen_translated_valid = factory_readDataStage_haltWhen_valid;
  assign factory_readDataStage_haltWhen_ready = factory_readDataStage_haltWhen_translated_ready;
  assign factory_readDataStage_haltWhen_translated_payload_data = factory_readRsp_data;
  assign factory_readDataStage_haltWhen_translated_payload_resp = factory_readRsp_resp;
  assign _zz_factory_readOccur = factory_readDataStage_haltWhen_translated_valid;
  assign factory_readDataStage_haltWhen_translated_ready = _zz_factory_readDataStage_haltWhen_translated_ready;
  assign _zz_9 = factory_readDataStage_haltWhen_translated_payload_data;
  assign _zz_10 = factory_readDataStage_haltWhen_translated_payload_resp;
  always @(*) begin
    if(factory_writeErrorFlag) begin
      factory_writeRsp_resp = 2'b10;
    end else begin
      factory_writeRsp_resp = 2'b00;
    end
  end

  always @(*) begin
    if(factory_readErrorFlag) begin
      factory_readRsp_resp = 2'b10;
    end else begin
      factory_readRsp_resp = 2'b00;
    end
  end

  always @(*) begin
    factory_readRsp_data = 32'h0;
    case(factory_readAddressMasked)
      8'h04 : begin
        factory_readRsp_data[31 : 0] = register_1;
      end
      default : begin
      end
    endcase
  end

  assign factory_readAddressMasked = (factory_readDataStage_payload_addr & (~ 8'h03));
  assign factory_writeAddressMasked = (_zz_factory_writeAddressMasked & (~ 8'h03));
  assign factory_readOccur = (_zz_factory_readOccur && _zz_factory_readDataStage_haltWhen_translated_ready);
  assign observed = register_1;
  assign events = count;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      factory_writeJoinEvent_translated_haltWhen_rValid <= 1'b0;
      _zz_factory_readDataStage_valid <= 1'b0;
      register_1 <= 32'h0;
      count <= 8'h0;
    end else begin
      if(factory_writeJoinEvent_translated_haltWhen_valid) begin
        factory_writeJoinEvent_translated_haltWhen_rValid <= 1'b1;
      end
      if(factory_writeJoinEvent_translated_haltWhen_halfPipe_fire) begin
        factory_writeJoinEvent_translated_haltWhen_rValid <= 1'b0;
      end
      if(_zz_7) begin
        _zz_factory_readDataStage_valid <= 1'b1;
      end
      if(factory_readDataStage_fire) begin
        _zz_factory_readDataStage_valid <= 1'b0;
      end
      case(factory_writeAddressMasked)
        8'h04 : begin
          if(factory_writeOccur) begin
            register_1 <= _zz_register[31 : 0];
          end
        end
        8'h08 : begin
          if(factory_writeOccur) begin
            count <= (count + 8'h01);
          end
        end
        default : begin
        end
      endcase
      case(factory_readAddressMasked)
        8'h08 : begin
          if(factory_readOccur) begin
            count <= (count + 8'h01);
          end
        end
        default : begin
        end
      endcase
    end
  end

  always @(posedge clk) begin
    if(factory_writeJoinEvent_translated_haltWhen_ready) begin
      factory_writeJoinEvent_translated_haltWhen_rData_resp <= factory_writeJoinEvent_translated_haltWhen_payload_resp;
    end
    if(_zz_8) begin
      _zz_factory_readDataStage_payload_addr_1 <= _zz_factory_readDataStage_payload_addr;
      _zz_factory_readDataStage_payload_prot_1 <= _zz_factory_readDataStage_payload_prot;
    end
  end


endmodule
