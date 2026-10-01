// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : LiteralAxi4
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps

module LiteralAxi4 (
  input  wire          _zz_unburstify_result_valid,
  output reg           _zz_1,
  input  wire [7:0]    _zz_unburstify_result_payload_fragment_addr,
  input  wire [1:0]    _zz_unburstify_result_payload_fragment_id,
  input  wire [3:0]    _zz_unburstify_result_payload_fragment_region,
  input  wire [7:0]    _zz_unburstify_buffer_len,
  input  wire [2:0]    _zz_unburstify_result_payload_fragment_size,
  input  wire [1:0]    _zz_unburstify_result_payload_fragment_burst,
  input  wire [0:0]    _zz_unburstify_result_payload_fragment_lock,
  input  wire [3:0]    _zz_unburstify_result_payload_fragment_cache,
  input  wire [3:0]    _zz_unburstify_result_payload_fragment_qos,
  input  wire [2:0]    _zz_unburstify_result_payload_fragment_prot,
  input  wire          _zz_factory_writeJoinEvent_valid,
  output wire          _zz_2,
  input  wire [31:0]   _zz_register,
  input  wire [3:0]    _zz_when_BusSlaveFactory_l1241,
  input  wire          _zz_3,
  output wire          _zz_4,
  input  wire          _zz_factory_writeRsp_stage_ready,
  output wire [1:0]    _zz_5,
  output wire [1:0]    _zz_6,
  input  wire          _zz_unburstify_result_valid_1,
  output reg           _zz_7,
  input  wire [7:0]    _zz_unburstify_result_payload_fragment_addr_1,
  input  wire [1:0]    _zz_unburstify_result_payload_fragment_id_1,
  input  wire [3:0]    _zz_unburstify_result_payload_fragment_region_1,
  input  wire [7:0]    _zz_unburstify_buffer_len_1,
  input  wire [2:0]    _zz_unburstify_result_payload_fragment_size_1,
  input  wire [1:0]    _zz_unburstify_result_payload_fragment_burst_1,
  input  wire [0:0]    _zz_unburstify_result_payload_fragment_lock_1,
  input  wire [3:0]    _zz_unburstify_result_payload_fragment_cache_1,
  input  wire [3:0]    _zz_unburstify_result_payload_fragment_qos_1,
  input  wire [2:0]    _zz_unburstify_result_payload_fragment_prot_1,
  output wire          _zz_factory_readOccur,
  input  wire          _zz_factory_readDataStage_haltWhen_translated_ready,
  output wire [31:0]   _zz_8,
  output wire [1:0]    _zz_9,
  output wire [1:0]    _zz_10,
  output wire          _zz_11,
  output wire [31:0]   observed,
  output wire [7:0]    events,
  input  wire          clk,
  input  wire          reset
);

  wire       [1:0]    _zz_Axi4Incr_alignMask;
  wire       [11:0]   _zz_Axi4Incr_base;
  wire       [7:0]    _zz_Axi4Incr_base_1;
  wire       [11:0]   _zz_Axi4Incr_baseIncr;
  wire       [2:0]    _zz_Axi4Incr_wrapCase_2;
  wire       [2:0]    _zz_Axi4Incr_wrapCase_3;
  wire       [11:0]   _zz_Axi4Incr_result;
  reg        [11:0]   _zz_Axi4Incr_result_1;
  wire       [10:0]   _zz_Axi4Incr_result_2;
  wire       [0:0]    _zz_Axi4Incr_result_3;
  wire       [9:0]    _zz_Axi4Incr_result_4;
  wire       [1:0]    _zz_Axi4Incr_result_5;
  wire       [8:0]    _zz_Axi4Incr_result_6;
  wire       [2:0]    _zz_Axi4Incr_result_7;
  wire       [7:0]    _zz_Axi4Incr_result_8;
  wire       [3:0]    _zz_Axi4Incr_result_9;
  wire       [6:0]    _zz_Axi4Incr_result_10;
  wire       [4:0]    _zz_Axi4Incr_result_11;
  wire       [5:0]    _zz_Axi4Incr_result_12;
  wire       [5:0]    _zz_Axi4Incr_result_13;
  wire       [11:0]   _zz_Axi4Incr_result_14;
  wire       [1:0]    _zz_Axi4Incr_alignMask_1;
  wire       [11:0]   _zz_Axi4Incr_base_1_1;
  wire       [7:0]    _zz_Axi4Incr_base_1_2;
  wire       [11:0]   _zz_Axi4Incr_baseIncr_1;
  wire       [2:0]    _zz_Axi4Incr_wrapCase_1_1;
  wire       [2:0]    _zz_Axi4Incr_wrapCase_1_2;
  wire       [11:0]   _zz_Axi4Incr_result_1_1;
  reg        [11:0]   _zz_Axi4Incr_result_1_2;
  wire       [10:0]   _zz_Axi4Incr_result_1_3;
  wire       [0:0]    _zz_Axi4Incr_result_1_4;
  wire       [9:0]    _zz_Axi4Incr_result_1_5;
  wire       [1:0]    _zz_Axi4Incr_result_1_6;
  wire       [8:0]    _zz_Axi4Incr_result_1_7;
  wire       [2:0]    _zz_Axi4Incr_result_1_8;
  wire       [7:0]    _zz_Axi4Incr_result_1_9;
  wire       [3:0]    _zz_Axi4Incr_result_1_10;
  wire       [6:0]    _zz_Axi4Incr_result_1_11;
  wire       [4:0]    _zz_Axi4Incr_result_1_12;
  wire       [5:0]    _zz_Axi4Incr_result_1_13;
  wire       [5:0]    _zz_Axi4Incr_result_1_14;
  wire       [11:0]   _zz_Axi4Incr_result_1_15;
  wire                factory_readErrorFlag;
  wire                factory_writeErrorFlag;
  wire                factory_readHaltRequest;
  wire                factory_writeHaltRequest;
  reg                 unburstify_result_valid;
  wire                unburstify_result_ready;
  reg                 unburstify_result_payload_last;
  reg        [7:0]    unburstify_result_payload_fragment_addr;
  reg        [1:0]    unburstify_result_payload_fragment_id;
  reg        [3:0]    unburstify_result_payload_fragment_region;
  reg        [2:0]    unburstify_result_payload_fragment_size;
  reg        [1:0]    unburstify_result_payload_fragment_burst;
  reg        [0:0]    unburstify_result_payload_fragment_lock;
  reg        [3:0]    unburstify_result_payload_fragment_cache;
  reg        [3:0]    unburstify_result_payload_fragment_qos;
  reg        [2:0]    unburstify_result_payload_fragment_prot;
  wire                unburstify_doResult;
  reg                 unburstify_buffer_valid;
  reg        [7:0]    unburstify_buffer_len;
  reg        [7:0]    unburstify_buffer_beat;
  reg        [7:0]    unburstify_buffer_transaction_addr;
  reg        [1:0]    unburstify_buffer_transaction_id;
  reg        [3:0]    unburstify_buffer_transaction_region;
  reg        [2:0]    unburstify_buffer_transaction_size;
  reg        [1:0]    unburstify_buffer_transaction_burst;
  reg        [0:0]    unburstify_buffer_transaction_lock;
  reg        [3:0]    unburstify_buffer_transaction_cache;
  reg        [3:0]    unburstify_buffer_transaction_qos;
  reg        [2:0]    unburstify_buffer_transaction_prot;
  wire                unburstify_buffer_last;
  wire       [1:0]    Axi4Incr_validSize;
  reg        [7:0]    Axi4Incr_result;
  wire       [2:0]    Axi4Incr_sizeValue;
  wire       [11:0]   Axi4Incr_alignMask;
  wire       [11:0]   Axi4Incr_base;
  wire       [11:0]   Axi4Incr_baseIncr;
  reg        [1:0]    _zz_Axi4Incr_wrapCase;
  wire       [2:0]    Axi4Incr_wrapCase;
  wire                when_Axi4Channel_l337;
  wire                factory_writeJoinEvent_valid;
  reg                 factory_writeJoinEvent_ready;
  wire                factory_writeOccur;
  reg                 factory_writeRsp_valid;
  reg                 factory_writeRsp_ready;
  wire       [1:0]    factory_writeRsp_payload_id;
  reg        [1:0]    factory_writeRsp_payload_resp;
  wire                factory_writeRsp_stage_valid;
  wire                factory_writeRsp_stage_ready;
  wire       [1:0]    factory_writeRsp_stage_payload_id;
  wire       [1:0]    factory_writeRsp_stage_payload_resp;
  reg                 factory_writeRsp_rValid;
  reg        [1:0]    factory_writeRsp_rData_id;
  reg        [1:0]    factory_writeRsp_rData_resp;
  wire                when_Stream_l682;
  reg                 unburstify_result_valid_1;
  reg                 unburstify_result_ready_1;
  reg                 unburstify_result_payload_last_1;
  reg        [7:0]    unburstify_result_payload_fragment_addr_1;
  reg        [1:0]    unburstify_result_payload_fragment_id_1;
  reg        [3:0]    unburstify_result_payload_fragment_region_1;
  reg        [2:0]    unburstify_result_payload_fragment_size_1;
  reg        [1:0]    unburstify_result_payload_fragment_burst_1;
  reg        [0:0]    unburstify_result_payload_fragment_lock_1;
  reg        [3:0]    unburstify_result_payload_fragment_cache_1;
  reg        [3:0]    unburstify_result_payload_fragment_qos_1;
  reg        [2:0]    unburstify_result_payload_fragment_prot_1;
  wire                unburstify_doResult_1;
  reg                 unburstify_buffer_valid_1;
  reg        [7:0]    unburstify_buffer_len_1;
  reg        [7:0]    unburstify_buffer_beat_1;
  reg        [7:0]    unburstify_buffer_transaction_addr_1;
  reg        [1:0]    unburstify_buffer_transaction_id_1;
  reg        [3:0]    unburstify_buffer_transaction_region_1;
  reg        [2:0]    unburstify_buffer_transaction_size_1;
  reg        [1:0]    unburstify_buffer_transaction_burst_1;
  reg        [0:0]    unburstify_buffer_transaction_lock_1;
  reg        [3:0]    unburstify_buffer_transaction_cache_1;
  reg        [3:0]    unburstify_buffer_transaction_qos_1;
  reg        [2:0]    unburstify_buffer_transaction_prot_1;
  wire                unburstify_buffer_last_1;
  wire       [1:0]    Axi4Incr_validSize_1;
  reg        [7:0]    Axi4Incr_result_1;
  wire       [2:0]    Axi4Incr_sizeValue_1;
  wire       [11:0]   Axi4Incr_alignMask_1;
  wire       [11:0]   Axi4Incr_base_1;
  wire       [11:0]   Axi4Incr_baseIncr_1;
  reg        [1:0]    _zz_Axi4Incr_wrapCase_1;
  wire       [2:0]    Axi4Incr_wrapCase_1;
  wire                when_Axi4Channel_l337_1;
  wire                factory_readDataStage_valid;
  wire                factory_readDataStage_ready;
  wire                factory_readDataStage_payload_last;
  wire       [7:0]    factory_readDataStage_payload_fragment_addr;
  wire       [1:0]    factory_readDataStage_payload_fragment_id;
  wire       [3:0]    factory_readDataStage_payload_fragment_region;
  wire       [2:0]    factory_readDataStage_payload_fragment_size;
  wire       [1:0]    factory_readDataStage_payload_fragment_burst;
  wire       [0:0]    factory_readDataStage_payload_fragment_lock;
  wire       [3:0]    factory_readDataStage_payload_fragment_cache;
  wire       [3:0]    factory_readDataStage_payload_fragment_qos;
  wire       [2:0]    factory_readDataStage_payload_fragment_prot;
  reg                 unburstify_result_rValid;
  reg                 unburstify_result_rData_last;
  reg        [7:0]    unburstify_result_rData_fragment_addr;
  reg        [1:0]    unburstify_result_rData_fragment_id;
  reg        [3:0]    unburstify_result_rData_fragment_region;
  reg        [2:0]    unburstify_result_rData_fragment_size;
  reg        [1:0]    unburstify_result_rData_fragment_burst;
  reg        [0:0]    unburstify_result_rData_fragment_lock;
  reg        [3:0]    unburstify_result_rData_fragment_cache;
  reg        [3:0]    unburstify_result_rData_fragment_qos;
  reg        [2:0]    unburstify_result_rData_fragment_prot;
  wire                when_Stream_l682_1;
  reg        [31:0]   factory_readRsp_data;
  wire       [1:0]    factory_readRsp_id;
  reg        [1:0]    factory_readRsp_resp;
  wire                factory_readRsp_last;
  wire                _zz_factory_readDataStage_ready;
  wire                factory_readDataStage_haltWhen_valid;
  wire                factory_readDataStage_haltWhen_ready;
  wire                factory_readDataStage_haltWhen_payload_last;
  wire       [7:0]    factory_readDataStage_haltWhen_payload_fragment_addr;
  wire       [1:0]    factory_readDataStage_haltWhen_payload_fragment_id;
  wire       [3:0]    factory_readDataStage_haltWhen_payload_fragment_region;
  wire       [2:0]    factory_readDataStage_haltWhen_payload_fragment_size;
  wire       [1:0]    factory_readDataStage_haltWhen_payload_fragment_burst;
  wire       [0:0]    factory_readDataStage_haltWhen_payload_fragment_lock;
  wire       [3:0]    factory_readDataStage_haltWhen_payload_fragment_cache;
  wire       [3:0]    factory_readDataStage_haltWhen_payload_fragment_qos;
  wire       [2:0]    factory_readDataStage_haltWhen_payload_fragment_prot;
  wire                factory_readDataStage_haltWhen_translated_valid;
  wire                factory_readDataStage_haltWhen_translated_ready;
  wire       [31:0]   factory_readDataStage_haltWhen_translated_payload_data;
  wire       [1:0]    factory_readDataStage_haltWhen_translated_payload_id;
  wire       [1:0]    factory_readDataStage_haltWhen_translated_payload_resp;
  wire                factory_readDataStage_haltWhen_translated_payload_last;
  wire                factory_readOccur;
  wire       [7:0]    factory_readAddressMasked;
  wire       [7:0]    factory_writeAddressMasked;
  reg        [31:0]   register_1;
  reg        [7:0]    count;
  wire                when_BusSlaveFactory_l1241;
  wire                when_BusSlaveFactory_l1241_1;
  wire                when_BusSlaveFactory_l1241_2;
  wire                when_BusSlaveFactory_l1241_3;

  assign _zz_Axi4Incr_alignMask = {(2'b01 < Axi4Incr_validSize),(2'b00 < Axi4Incr_validSize)};
  assign _zz_Axi4Incr_base_1 = unburstify_buffer_transaction_addr[7 : 0];
  assign _zz_Axi4Incr_base = {4'd0, _zz_Axi4Incr_base_1};
  assign _zz_Axi4Incr_baseIncr = {9'd0, Axi4Incr_sizeValue};
  assign _zz_Axi4Incr_wrapCase_2 = {1'd0, Axi4Incr_validSize};
  assign _zz_Axi4Incr_wrapCase_3 = {1'd0, _zz_Axi4Incr_wrapCase};
  assign _zz_Axi4Incr_result = _zz_Axi4Incr_result_1;
  assign _zz_Axi4Incr_result_14 = Axi4Incr_baseIncr;
  assign _zz_Axi4Incr_alignMask_1 = {(2'b01 < Axi4Incr_validSize_1),(2'b00 < Axi4Incr_validSize_1)};
  assign _zz_Axi4Incr_base_1_2 = unburstify_buffer_transaction_addr_1[7 : 0];
  assign _zz_Axi4Incr_base_1_1 = {4'd0, _zz_Axi4Incr_base_1_2};
  assign _zz_Axi4Incr_baseIncr_1 = {9'd0, Axi4Incr_sizeValue_1};
  assign _zz_Axi4Incr_wrapCase_1_1 = {1'd0, Axi4Incr_validSize_1};
  assign _zz_Axi4Incr_wrapCase_1_2 = {1'd0, _zz_Axi4Incr_wrapCase_1};
  assign _zz_Axi4Incr_result_1_1 = _zz_Axi4Incr_result_1_2;
  assign _zz_Axi4Incr_result_1_15 = Axi4Incr_baseIncr_1;
  assign _zz_Axi4Incr_result_2 = Axi4Incr_base[11 : 1];
  assign _zz_Axi4Incr_result_3 = Axi4Incr_baseIncr[0 : 0];
  assign _zz_Axi4Incr_result_4 = Axi4Incr_base[11 : 2];
  assign _zz_Axi4Incr_result_5 = Axi4Incr_baseIncr[1 : 0];
  assign _zz_Axi4Incr_result_6 = Axi4Incr_base[11 : 3];
  assign _zz_Axi4Incr_result_7 = Axi4Incr_baseIncr[2 : 0];
  assign _zz_Axi4Incr_result_8 = Axi4Incr_base[11 : 4];
  assign _zz_Axi4Incr_result_9 = Axi4Incr_baseIncr[3 : 0];
  assign _zz_Axi4Incr_result_10 = Axi4Incr_base[11 : 5];
  assign _zz_Axi4Incr_result_11 = Axi4Incr_baseIncr[4 : 0];
  assign _zz_Axi4Incr_result_12 = Axi4Incr_base[11 : 6];
  assign _zz_Axi4Incr_result_13 = Axi4Incr_baseIncr[5 : 0];
  assign _zz_Axi4Incr_result_1_3 = Axi4Incr_base_1[11 : 1];
  assign _zz_Axi4Incr_result_1_4 = Axi4Incr_baseIncr_1[0 : 0];
  assign _zz_Axi4Incr_result_1_5 = Axi4Incr_base_1[11 : 2];
  assign _zz_Axi4Incr_result_1_6 = Axi4Incr_baseIncr_1[1 : 0];
  assign _zz_Axi4Incr_result_1_7 = Axi4Incr_base_1[11 : 3];
  assign _zz_Axi4Incr_result_1_8 = Axi4Incr_baseIncr_1[2 : 0];
  assign _zz_Axi4Incr_result_1_9 = Axi4Incr_base_1[11 : 4];
  assign _zz_Axi4Incr_result_1_10 = Axi4Incr_baseIncr_1[3 : 0];
  assign _zz_Axi4Incr_result_1_11 = Axi4Incr_base_1[11 : 5];
  assign _zz_Axi4Incr_result_1_12 = Axi4Incr_baseIncr_1[4 : 0];
  assign _zz_Axi4Incr_result_1_13 = Axi4Incr_base_1[11 : 6];
  assign _zz_Axi4Incr_result_1_14 = Axi4Incr_baseIncr_1[5 : 0];
  always @(*) begin
    case(Axi4Incr_wrapCase)
      3'b000 : _zz_Axi4Incr_result_1 = {_zz_Axi4Incr_result_2,_zz_Axi4Incr_result_3};
      3'b001 : _zz_Axi4Incr_result_1 = {_zz_Axi4Incr_result_4,_zz_Axi4Incr_result_5};
      3'b010 : _zz_Axi4Incr_result_1 = {_zz_Axi4Incr_result_6,_zz_Axi4Incr_result_7};
      3'b011 : _zz_Axi4Incr_result_1 = {_zz_Axi4Incr_result_8,_zz_Axi4Incr_result_9};
      3'b100 : _zz_Axi4Incr_result_1 = {_zz_Axi4Incr_result_10,_zz_Axi4Incr_result_11};
      default : _zz_Axi4Incr_result_1 = {_zz_Axi4Incr_result_12,_zz_Axi4Incr_result_13};
    endcase
  end

  always @(*) begin
    case(Axi4Incr_wrapCase_1)
      3'b000 : _zz_Axi4Incr_result_1_2 = {_zz_Axi4Incr_result_1_3,_zz_Axi4Incr_result_1_4};
      3'b001 : _zz_Axi4Incr_result_1_2 = {_zz_Axi4Incr_result_1_5,_zz_Axi4Incr_result_1_6};
      3'b010 : _zz_Axi4Incr_result_1_2 = {_zz_Axi4Incr_result_1_7,_zz_Axi4Incr_result_1_8};
      3'b011 : _zz_Axi4Incr_result_1_2 = {_zz_Axi4Incr_result_1_9,_zz_Axi4Incr_result_1_10};
      3'b100 : _zz_Axi4Incr_result_1_2 = {_zz_Axi4Incr_result_1_11,_zz_Axi4Incr_result_1_12};
      default : _zz_Axi4Incr_result_1_2 = {_zz_Axi4Incr_result_1_13,_zz_Axi4Incr_result_1_14};
    endcase
  end

  assign factory_readErrorFlag = 1'b0;
  assign factory_writeErrorFlag = 1'b0;
  assign factory_readHaltRequest = 1'b0;
  assign factory_writeHaltRequest = 1'b0;
  assign unburstify_buffer_last = (unburstify_buffer_beat == 8'h01);
  assign Axi4Incr_validSize = unburstify_buffer_transaction_size[1 : 0];
  assign Axi4Incr_sizeValue = {(2'b10 == Axi4Incr_validSize),{(2'b01 == Axi4Incr_validSize),(2'b00 == Axi4Incr_validSize)}};
  assign Axi4Incr_alignMask = {10'd0, _zz_Axi4Incr_alignMask};
  assign Axi4Incr_base = (_zz_Axi4Incr_base & (~ Axi4Incr_alignMask));
  assign Axi4Incr_baseIncr = (Axi4Incr_base + _zz_Axi4Incr_baseIncr);
  always @(*) begin
    casez(unburstify_buffer_len)
      8'b????1??? : begin
        _zz_Axi4Incr_wrapCase = 2'b11;
      end
      8'b????01?? : begin
        _zz_Axi4Incr_wrapCase = 2'b10;
      end
      8'b????001? : begin
        _zz_Axi4Incr_wrapCase = 2'b01;
      end
      default : begin
        _zz_Axi4Incr_wrapCase = 2'b00;
      end
    endcase
  end

  assign Axi4Incr_wrapCase = (_zz_Axi4Incr_wrapCase_2 + _zz_Axi4Incr_wrapCase_3);
  always @(*) begin
    case(unburstify_buffer_transaction_burst)
      2'b00 : begin
        Axi4Incr_result = unburstify_buffer_transaction_addr;
      end
      2'b10 : begin
        Axi4Incr_result = _zz_Axi4Incr_result[7:0];
      end
      default : begin
        Axi4Incr_result = _zz_Axi4Incr_result_14[7:0];
      end
    endcase
  end

  always @(*) begin
    _zz_1 = 1'b0;
    if(!unburstify_buffer_valid) begin
      _zz_1 = unburstify_result_ready;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_valid = 1'b1;
    end else begin
      unburstify_result_valid = _zz_unburstify_result_valid;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_last = unburstify_buffer_last;
    end else begin
      unburstify_result_payload_last = 1'b1;
      if(when_Axi4Channel_l337) begin
        unburstify_result_payload_last = 1'b0;
      end
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_id = unburstify_buffer_transaction_id;
    end else begin
      unburstify_result_payload_fragment_id = _zz_unburstify_result_payload_fragment_id;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_region = unburstify_buffer_transaction_region;
    end else begin
      unburstify_result_payload_fragment_region = _zz_unburstify_result_payload_fragment_region;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_size = unburstify_buffer_transaction_size;
    end else begin
      unburstify_result_payload_fragment_size = _zz_unburstify_result_payload_fragment_size;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_burst = unburstify_buffer_transaction_burst;
    end else begin
      unburstify_result_payload_fragment_burst = _zz_unburstify_result_payload_fragment_burst;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_lock = unburstify_buffer_transaction_lock;
    end else begin
      unburstify_result_payload_fragment_lock = _zz_unburstify_result_payload_fragment_lock;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_cache = unburstify_buffer_transaction_cache;
    end else begin
      unburstify_result_payload_fragment_cache = _zz_unburstify_result_payload_fragment_cache;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_qos = unburstify_buffer_transaction_qos;
    end else begin
      unburstify_result_payload_fragment_qos = _zz_unburstify_result_payload_fragment_qos;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_prot = unburstify_buffer_transaction_prot;
    end else begin
      unburstify_result_payload_fragment_prot = _zz_unburstify_result_payload_fragment_prot;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_addr = Axi4Incr_result;
    end else begin
      unburstify_result_payload_fragment_addr = _zz_unburstify_result_payload_fragment_addr;
    end
  end

  assign when_Axi4Channel_l337 = (_zz_unburstify_buffer_len != 8'h0);
  assign factory_writeOccur = (factory_writeJoinEvent_valid && factory_writeJoinEvent_ready);
  assign factory_writeJoinEvent_valid = (unburstify_result_valid && _zz_factory_writeJoinEvent_valid);
  assign unburstify_result_ready = factory_writeOccur;
  assign _zz_2 = factory_writeOccur;
  always @(*) begin
    factory_writeRsp_ready = factory_writeRsp_stage_ready;
    if(when_Stream_l682) begin
      factory_writeRsp_ready = 1'b1;
    end
  end

  assign when_Stream_l682 = (! factory_writeRsp_stage_valid);
  assign factory_writeRsp_stage_valid = factory_writeRsp_rValid;
  assign factory_writeRsp_stage_payload_id = factory_writeRsp_rData_id;
  assign factory_writeRsp_stage_payload_resp = factory_writeRsp_rData_resp;
  assign _zz_4 = factory_writeRsp_stage_valid;
  assign factory_writeRsp_stage_ready = _zz_factory_writeRsp_stage_ready;
  assign _zz_5 = factory_writeRsp_stage_payload_id;
  assign _zz_6 = factory_writeRsp_stage_payload_resp;
  always @(*) begin
    if(_zz_3) begin
      factory_writeJoinEvent_ready = (factory_writeRsp_ready && (! factory_writeHaltRequest));
    end else begin
      factory_writeJoinEvent_ready = (! factory_writeHaltRequest);
    end
  end

  always @(*) begin
    if(_zz_3) begin
      factory_writeRsp_valid = factory_writeOccur;
    end else begin
      factory_writeRsp_valid = 1'b0;
    end
  end

  assign unburstify_buffer_last_1 = (unburstify_buffer_beat_1 == 8'h01);
  assign Axi4Incr_validSize_1 = unburstify_buffer_transaction_size_1[1 : 0];
  assign Axi4Incr_sizeValue_1 = {(2'b10 == Axi4Incr_validSize_1),{(2'b01 == Axi4Incr_validSize_1),(2'b00 == Axi4Incr_validSize_1)}};
  assign Axi4Incr_alignMask_1 = {10'd0, _zz_Axi4Incr_alignMask_1};
  assign Axi4Incr_base_1 = (_zz_Axi4Incr_base_1_1 & (~ Axi4Incr_alignMask_1));
  assign Axi4Incr_baseIncr_1 = (Axi4Incr_base_1 + _zz_Axi4Incr_baseIncr_1);
  always @(*) begin
    casez(unburstify_buffer_len_1)
      8'b????1??? : begin
        _zz_Axi4Incr_wrapCase_1 = 2'b11;
      end
      8'b????01?? : begin
        _zz_Axi4Incr_wrapCase_1 = 2'b10;
      end
      8'b????001? : begin
        _zz_Axi4Incr_wrapCase_1 = 2'b01;
      end
      default : begin
        _zz_Axi4Incr_wrapCase_1 = 2'b00;
      end
    endcase
  end

  assign Axi4Incr_wrapCase_1 = (_zz_Axi4Incr_wrapCase_1_1 + _zz_Axi4Incr_wrapCase_1_2);
  always @(*) begin
    case(unburstify_buffer_transaction_burst_1)
      2'b00 : begin
        Axi4Incr_result_1 = unburstify_buffer_transaction_addr_1;
      end
      2'b10 : begin
        Axi4Incr_result_1 = _zz_Axi4Incr_result_1_1[7:0];
      end
      default : begin
        Axi4Incr_result_1 = _zz_Axi4Incr_result_1_15[7:0];
      end
    endcase
  end

  always @(*) begin
    _zz_7 = 1'b0;
    if(!unburstify_buffer_valid_1) begin
      _zz_7 = unburstify_result_ready_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_valid_1 = 1'b1;
    end else begin
      unburstify_result_valid_1 = _zz_unburstify_result_valid_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_last_1 = unburstify_buffer_last_1;
    end else begin
      unburstify_result_payload_last_1 = 1'b1;
      if(when_Axi4Channel_l337_1) begin
        unburstify_result_payload_last_1 = 1'b0;
      end
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_id_1 = unburstify_buffer_transaction_id_1;
    end else begin
      unburstify_result_payload_fragment_id_1 = _zz_unburstify_result_payload_fragment_id_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_region_1 = unburstify_buffer_transaction_region_1;
    end else begin
      unburstify_result_payload_fragment_region_1 = _zz_unburstify_result_payload_fragment_region_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_size_1 = unburstify_buffer_transaction_size_1;
    end else begin
      unburstify_result_payload_fragment_size_1 = _zz_unburstify_result_payload_fragment_size_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_burst_1 = unburstify_buffer_transaction_burst_1;
    end else begin
      unburstify_result_payload_fragment_burst_1 = _zz_unburstify_result_payload_fragment_burst_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_lock_1 = unburstify_buffer_transaction_lock_1;
    end else begin
      unburstify_result_payload_fragment_lock_1 = _zz_unburstify_result_payload_fragment_lock_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_cache_1 = unburstify_buffer_transaction_cache_1;
    end else begin
      unburstify_result_payload_fragment_cache_1 = _zz_unburstify_result_payload_fragment_cache_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_qos_1 = unburstify_buffer_transaction_qos_1;
    end else begin
      unburstify_result_payload_fragment_qos_1 = _zz_unburstify_result_payload_fragment_qos_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_prot_1 = unburstify_buffer_transaction_prot_1;
    end else begin
      unburstify_result_payload_fragment_prot_1 = _zz_unburstify_result_payload_fragment_prot_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_addr_1 = Axi4Incr_result_1;
    end else begin
      unburstify_result_payload_fragment_addr_1 = _zz_unburstify_result_payload_fragment_addr_1;
    end
  end

  assign when_Axi4Channel_l337_1 = (_zz_unburstify_buffer_len_1 != 8'h0);
  always @(*) begin
    unburstify_result_ready_1 = factory_readDataStage_ready;
    if(when_Stream_l682_1) begin
      unburstify_result_ready_1 = 1'b1;
    end
  end

  assign when_Stream_l682_1 = (! factory_readDataStage_valid);
  assign factory_readDataStage_valid = unburstify_result_rValid;
  assign factory_readDataStage_payload_last = unburstify_result_rData_last;
  assign factory_readDataStage_payload_fragment_addr = unburstify_result_rData_fragment_addr;
  assign factory_readDataStage_payload_fragment_id = unburstify_result_rData_fragment_id;
  assign factory_readDataStage_payload_fragment_region = unburstify_result_rData_fragment_region;
  assign factory_readDataStage_payload_fragment_size = unburstify_result_rData_fragment_size;
  assign factory_readDataStage_payload_fragment_burst = unburstify_result_rData_fragment_burst;
  assign factory_readDataStage_payload_fragment_lock = unburstify_result_rData_fragment_lock;
  assign factory_readDataStage_payload_fragment_cache = unburstify_result_rData_fragment_cache;
  assign factory_readDataStage_payload_fragment_qos = unburstify_result_rData_fragment_qos;
  assign factory_readDataStage_payload_fragment_prot = unburstify_result_rData_fragment_prot;
  assign _zz_factory_readDataStage_ready = (! factory_readHaltRequest);
  assign factory_readDataStage_haltWhen_valid = (factory_readDataStage_valid && _zz_factory_readDataStage_ready);
  assign factory_readDataStage_ready = (factory_readDataStage_haltWhen_ready && _zz_factory_readDataStage_ready);
  assign factory_readDataStage_haltWhen_payload_last = factory_readDataStage_payload_last;
  assign factory_readDataStage_haltWhen_payload_fragment_addr = factory_readDataStage_payload_fragment_addr;
  assign factory_readDataStage_haltWhen_payload_fragment_id = factory_readDataStage_payload_fragment_id;
  assign factory_readDataStage_haltWhen_payload_fragment_region = factory_readDataStage_payload_fragment_region;
  assign factory_readDataStage_haltWhen_payload_fragment_size = factory_readDataStage_payload_fragment_size;
  assign factory_readDataStage_haltWhen_payload_fragment_burst = factory_readDataStage_payload_fragment_burst;
  assign factory_readDataStage_haltWhen_payload_fragment_lock = factory_readDataStage_payload_fragment_lock;
  assign factory_readDataStage_haltWhen_payload_fragment_cache = factory_readDataStage_payload_fragment_cache;
  assign factory_readDataStage_haltWhen_payload_fragment_qos = factory_readDataStage_payload_fragment_qos;
  assign factory_readDataStage_haltWhen_payload_fragment_prot = factory_readDataStage_payload_fragment_prot;
  assign factory_readDataStage_haltWhen_translated_valid = factory_readDataStage_haltWhen_valid;
  assign factory_readDataStage_haltWhen_ready = factory_readDataStage_haltWhen_translated_ready;
  assign factory_readDataStage_haltWhen_translated_payload_data = factory_readRsp_data;
  assign factory_readDataStage_haltWhen_translated_payload_id = factory_readRsp_id;
  assign factory_readDataStage_haltWhen_translated_payload_resp = factory_readRsp_resp;
  assign factory_readDataStage_haltWhen_translated_payload_last = factory_readRsp_last;
  assign _zz_factory_readOccur = factory_readDataStage_haltWhen_translated_valid;
  assign factory_readDataStage_haltWhen_translated_ready = _zz_factory_readDataStage_haltWhen_translated_ready;
  assign _zz_8 = factory_readDataStage_haltWhen_translated_payload_data;
  assign _zz_9 = factory_readDataStage_haltWhen_translated_payload_id;
  assign _zz_10 = factory_readDataStage_haltWhen_translated_payload_resp;
  assign _zz_11 = factory_readDataStage_haltWhen_translated_payload_last;
  assign factory_writeRsp_payload_id = unburstify_result_payload_fragment_id;
  always @(*) begin
    if(factory_writeErrorFlag) begin
      factory_writeRsp_payload_resp = 2'b10;
    end else begin
      factory_writeRsp_payload_resp = 2'b00;
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

  assign factory_readRsp_last = factory_readDataStage_payload_last;
  assign factory_readRsp_id = factory_readDataStage_payload_fragment_id;
  assign factory_readOccur = (_zz_factory_readOccur && _zz_factory_readDataStage_haltWhen_translated_ready);
  assign factory_readAddressMasked = (factory_readDataStage_payload_fragment_addr & (~ 8'h03));
  assign factory_writeAddressMasked = (unburstify_result_payload_fragment_addr & (~ 8'h03));
  assign observed = register_1;
  assign events = count;
  assign when_BusSlaveFactory_l1241 = _zz_when_BusSlaveFactory_l1241[0];
  assign when_BusSlaveFactory_l1241_1 = _zz_when_BusSlaveFactory_l1241[1];
  assign when_BusSlaveFactory_l1241_2 = _zz_when_BusSlaveFactory_l1241[2];
  assign when_BusSlaveFactory_l1241_3 = _zz_when_BusSlaveFactory_l1241[3];
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      unburstify_buffer_valid <= 1'b0;
      factory_writeRsp_rValid <= 1'b0;
      unburstify_buffer_valid_1 <= 1'b0;
      unburstify_result_rValid <= 1'b0;
      register_1 <= 32'h0;
      count <= 8'h0;
    end else begin
      if(unburstify_result_ready) begin
        if(unburstify_buffer_last) begin
          unburstify_buffer_valid <= 1'b0;
        end
      end
      if(!unburstify_buffer_valid) begin
        if(when_Axi4Channel_l337) begin
          if(unburstify_result_ready) begin
            unburstify_buffer_valid <= _zz_unburstify_result_valid;
          end
        end
      end
      if(factory_writeRsp_ready) begin
        factory_writeRsp_rValid <= factory_writeRsp_valid;
      end
      if(unburstify_result_ready_1) begin
        if(unburstify_buffer_last_1) begin
          unburstify_buffer_valid_1 <= 1'b0;
        end
      end
      if(!unburstify_buffer_valid_1) begin
        if(when_Axi4Channel_l337_1) begin
          if(unburstify_result_ready_1) begin
            unburstify_buffer_valid_1 <= _zz_unburstify_result_valid_1;
          end
        end
      end
      if(unburstify_result_ready_1) begin
        unburstify_result_rValid <= unburstify_result_valid_1;
      end
      case(factory_writeAddressMasked)
        8'h04 : begin
          if(factory_writeOccur) begin
            if(when_BusSlaveFactory_l1241) begin
              register_1[7 : 0] <= _zz_register[7 : 0];
            end
            if(when_BusSlaveFactory_l1241_1) begin
              register_1[15 : 8] <= _zz_register[15 : 8];
            end
            if(when_BusSlaveFactory_l1241_2) begin
              register_1[23 : 16] <= _zz_register[23 : 16];
            end
            if(when_BusSlaveFactory_l1241_3) begin
              register_1[31 : 24] <= _zz_register[31 : 24];
            end
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
    if(unburstify_result_ready) begin
      unburstify_buffer_beat <= (unburstify_buffer_beat - 8'h01);
      unburstify_buffer_transaction_addr[7 : 0] <= Axi4Incr_result[7 : 0];
    end
    if(!unburstify_buffer_valid) begin
      if(when_Axi4Channel_l337) begin
        if(unburstify_result_ready) begin
          unburstify_buffer_transaction_addr <= _zz_unburstify_result_payload_fragment_addr;
          unburstify_buffer_transaction_id <= _zz_unburstify_result_payload_fragment_id;
          unburstify_buffer_transaction_region <= _zz_unburstify_result_payload_fragment_region;
          unburstify_buffer_transaction_size <= _zz_unburstify_result_payload_fragment_size;
          unburstify_buffer_transaction_burst <= _zz_unburstify_result_payload_fragment_burst;
          unburstify_buffer_transaction_lock <= _zz_unburstify_result_payload_fragment_lock;
          unburstify_buffer_transaction_cache <= _zz_unburstify_result_payload_fragment_cache;
          unburstify_buffer_transaction_qos <= _zz_unburstify_result_payload_fragment_qos;
          unburstify_buffer_transaction_prot <= _zz_unburstify_result_payload_fragment_prot;
          unburstify_buffer_beat <= _zz_unburstify_buffer_len;
          unburstify_buffer_len <= _zz_unburstify_buffer_len;
        end
      end
    end
    if(factory_writeRsp_ready) begin
      factory_writeRsp_rData_id <= factory_writeRsp_payload_id;
      factory_writeRsp_rData_resp <= factory_writeRsp_payload_resp;
    end
    if(unburstify_result_ready_1) begin
      unburstify_buffer_beat_1 <= (unburstify_buffer_beat_1 - 8'h01);
      unburstify_buffer_transaction_addr_1[7 : 0] <= Axi4Incr_result_1[7 : 0];
    end
    if(!unburstify_buffer_valid_1) begin
      if(when_Axi4Channel_l337_1) begin
        if(unburstify_result_ready_1) begin
          unburstify_buffer_transaction_addr_1 <= _zz_unburstify_result_payload_fragment_addr_1;
          unburstify_buffer_transaction_id_1 <= _zz_unburstify_result_payload_fragment_id_1;
          unburstify_buffer_transaction_region_1 <= _zz_unburstify_result_payload_fragment_region_1;
          unburstify_buffer_transaction_size_1 <= _zz_unburstify_result_payload_fragment_size_1;
          unburstify_buffer_transaction_burst_1 <= _zz_unburstify_result_payload_fragment_burst_1;
          unburstify_buffer_transaction_lock_1 <= _zz_unburstify_result_payload_fragment_lock_1;
          unburstify_buffer_transaction_cache_1 <= _zz_unburstify_result_payload_fragment_cache_1;
          unburstify_buffer_transaction_qos_1 <= _zz_unburstify_result_payload_fragment_qos_1;
          unburstify_buffer_transaction_prot_1 <= _zz_unburstify_result_payload_fragment_prot_1;
          unburstify_buffer_beat_1 <= _zz_unburstify_buffer_len_1;
          unburstify_buffer_len_1 <= _zz_unburstify_buffer_len_1;
        end
      end
    end
    if(unburstify_result_ready_1) begin
      unburstify_result_rData_last <= unburstify_result_payload_last_1;
      unburstify_result_rData_fragment_addr <= unburstify_result_payload_fragment_addr_1;
      unburstify_result_rData_fragment_id <= unburstify_result_payload_fragment_id_1;
      unburstify_result_rData_fragment_region <= unburstify_result_payload_fragment_region_1;
      unburstify_result_rData_fragment_size <= unburstify_result_payload_fragment_size_1;
      unburstify_result_rData_fragment_burst <= unburstify_result_payload_fragment_burst_1;
      unburstify_result_rData_fragment_lock <= unburstify_result_payload_fragment_lock_1;
      unburstify_result_rData_fragment_cache <= unburstify_result_payload_fragment_cache_1;
      unburstify_result_rData_fragment_qos <= unburstify_result_payload_fragment_qos_1;
      unburstify_result_rData_fragment_prot <= unburstify_result_payload_fragment_prot_1;
    end
  end


endmodule
