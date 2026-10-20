// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : NamedFactory
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module NamedFactory #(
  parameter integer BASE_WORD = 1
) (
  input  wire          bus_aw_valid,
  output reg           bus_aw_ready,
  input  wire [7:0]    bus_aw_payload_addr,
  input  wire [1:0]    bus_aw_payload_id,
  input  wire [3:0]    bus_aw_payload_region,
  input  wire [7:0]    bus_aw_payload_len,
  input  wire [2:0]    bus_aw_payload_size,
  input  wire [1:0]    bus_aw_payload_burst,
  input  wire [0:0]    bus_aw_payload_lock,
  input  wire [3:0]    bus_aw_payload_cache,
  input  wire [3:0]    bus_aw_payload_qos,
  input  wire [2:0]    bus_aw_payload_prot,
  input  wire          bus_w_valid,
  output wire          bus_w_ready,
  input  wire [31:0]   bus_w_payload_data,
  input  wire [3:0]    bus_w_payload_strb,
  input  wire          bus_w_payload_last,
  output wire          bus_b_valid,
  input  wire          bus_b_ready,
  output wire [1:0]    bus_b_payload_id,
  output wire [1:0]    bus_b_payload_resp,
  input  wire          bus_ar_valid,
  output reg           bus_ar_ready,
  input  wire [7:0]    bus_ar_payload_addr,
  input  wire [1:0]    bus_ar_payload_id,
  input  wire [3:0]    bus_ar_payload_region,
  input  wire [7:0]    bus_ar_payload_len,
  input  wire [2:0]    bus_ar_payload_size,
  input  wire [1:0]    bus_ar_payload_burst,
  input  wire [0:0]    bus_ar_payload_lock,
  input  wire [3:0]    bus_ar_payload_cache,
  input  wire [3:0]    bus_ar_payload_qos,
  input  wire [2:0]    bus_ar_payload_prot,
  output wire          bus_r_valid,
  input  wire          bus_r_ready,
  output wire [31:0]   bus_r_payload_data,
  output wire [1:0]    bus_r_payload_id,
  output wire [1:0]    bus_r_payload_resp,
  output wire          bus_r_payload_last,
  output wire [31:0]   observed,
  output wire [31:0]   observedHigh,
  output wire [7:0]    readEvents,
  output wire [7:0]    writeEvents,
  input  wire          clk,
  input  wire          reset
);
  localparam [7:0] ADDR_CONTROL = (BASE_WORD * 4);
  localparam [7:0] ADDR_EVENT = (ADDR_CONTROL + 4);
  localparam [7:0] ADDR_HIGH = 252;

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
  reg        [31:0]   high;
  reg        [7:0]    reads;
  reg        [7:0]    writes;
  wire                when_BusSlaveFactory_l1299;
  wire                when_BusSlaveFactory_l1299_1;
  wire                when_BusSlaveFactory_l1299_2;
  wire                when_BusSlaveFactory_l1299_3;
  wire                when_BusSlaveFactory_l1299_4;
  wire                when_BusSlaveFactory_l1299_5;
  wire                when_BusSlaveFactory_l1299_6;
  wire                when_BusSlaveFactory_l1299_7;

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
    bus_aw_ready = 1'b0;
    if(!unburstify_buffer_valid) begin
      bus_aw_ready = unburstify_result_ready;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_valid = 1'b1;
    end else begin
      unburstify_result_valid = bus_aw_valid;
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
      unburstify_result_payload_fragment_id = bus_aw_payload_id;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_region = unburstify_buffer_transaction_region;
    end else begin
      unburstify_result_payload_fragment_region = bus_aw_payload_region;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_size = unburstify_buffer_transaction_size;
    end else begin
      unburstify_result_payload_fragment_size = bus_aw_payload_size;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_burst = unburstify_buffer_transaction_burst;
    end else begin
      unburstify_result_payload_fragment_burst = bus_aw_payload_burst;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_lock = unburstify_buffer_transaction_lock;
    end else begin
      unburstify_result_payload_fragment_lock = bus_aw_payload_lock;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_cache = unburstify_buffer_transaction_cache;
    end else begin
      unburstify_result_payload_fragment_cache = bus_aw_payload_cache;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_qos = unburstify_buffer_transaction_qos;
    end else begin
      unburstify_result_payload_fragment_qos = bus_aw_payload_qos;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_prot = unburstify_buffer_transaction_prot;
    end else begin
      unburstify_result_payload_fragment_prot = bus_aw_payload_prot;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid) begin
      unburstify_result_payload_fragment_addr = Axi4Incr_result;
    end else begin
      unburstify_result_payload_fragment_addr = bus_aw_payload_addr;
    end
  end

  assign when_Axi4Channel_l337 = (bus_aw_payload_len != 8'h0);
  assign factory_writeOccur = (factory_writeJoinEvent_valid && factory_writeJoinEvent_ready);
  assign factory_writeJoinEvent_valid = (unburstify_result_valid && bus_w_valid);
  assign unburstify_result_ready = factory_writeOccur;
  assign bus_w_ready = factory_writeOccur;
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
  assign bus_b_valid = factory_writeRsp_stage_valid;
  assign factory_writeRsp_stage_ready = bus_b_ready;
  assign bus_b_payload_id = factory_writeRsp_stage_payload_id;
  assign bus_b_payload_resp = factory_writeRsp_stage_payload_resp;
  always @(*) begin
    if(bus_w_payload_last) begin
      factory_writeJoinEvent_ready = (factory_writeRsp_ready && (! factory_writeHaltRequest));
    end else begin
      factory_writeJoinEvent_ready = (! factory_writeHaltRequest);
    end
  end

  always @(*) begin
    if(bus_w_payload_last) begin
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
    bus_ar_ready = 1'b0;
    if(!unburstify_buffer_valid_1) begin
      bus_ar_ready = unburstify_result_ready_1;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_valid_1 = 1'b1;
    end else begin
      unburstify_result_valid_1 = bus_ar_valid;
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
      unburstify_result_payload_fragment_id_1 = bus_ar_payload_id;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_region_1 = unburstify_buffer_transaction_region_1;
    end else begin
      unburstify_result_payload_fragment_region_1 = bus_ar_payload_region;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_size_1 = unburstify_buffer_transaction_size_1;
    end else begin
      unburstify_result_payload_fragment_size_1 = bus_ar_payload_size;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_burst_1 = unburstify_buffer_transaction_burst_1;
    end else begin
      unburstify_result_payload_fragment_burst_1 = bus_ar_payload_burst;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_lock_1 = unburstify_buffer_transaction_lock_1;
    end else begin
      unburstify_result_payload_fragment_lock_1 = bus_ar_payload_lock;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_cache_1 = unburstify_buffer_transaction_cache_1;
    end else begin
      unburstify_result_payload_fragment_cache_1 = bus_ar_payload_cache;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_qos_1 = unburstify_buffer_transaction_qos_1;
    end else begin
      unburstify_result_payload_fragment_qos_1 = bus_ar_payload_qos;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_prot_1 = unburstify_buffer_transaction_prot_1;
    end else begin
      unburstify_result_payload_fragment_prot_1 = bus_ar_payload_prot;
    end
  end

  always @(*) begin
    if(unburstify_buffer_valid_1) begin
      unburstify_result_payload_fragment_addr_1 = Axi4Incr_result_1;
    end else begin
      unburstify_result_payload_fragment_addr_1 = bus_ar_payload_addr;
    end
  end

  assign when_Axi4Channel_l337_1 = (bus_ar_payload_len != 8'h0);
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
  assign bus_r_valid = factory_readDataStage_haltWhen_translated_valid;
  assign factory_readDataStage_haltWhen_translated_ready = bus_r_ready;
  assign bus_r_payload_data = factory_readDataStage_haltWhen_translated_payload_data;
  assign bus_r_payload_id = factory_readDataStage_haltWhen_translated_payload_id;
  assign bus_r_payload_resp = factory_readDataStage_haltWhen_translated_payload_resp;
  assign bus_r_payload_last = factory_readDataStage_haltWhen_translated_payload_last;
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
      ADDR_CONTROL : begin
        factory_readRsp_data[31 : 0] = register_1;
      end
      ADDR_HIGH : begin
        factory_readRsp_data[31 : 0] = high;
      end
      default : begin
      end
    endcase
  end

  assign factory_readRsp_last = factory_readDataStage_payload_last;
  assign factory_readRsp_id = factory_readDataStage_payload_fragment_id;
  assign factory_readOccur = (bus_r_valid && bus_r_ready);
  assign factory_readAddressMasked = (factory_readDataStage_payload_fragment_addr & (~ 8'h03));
  assign factory_writeAddressMasked = (unburstify_result_payload_fragment_addr & (~ 8'h03));
  assign observed = register_1;
  assign observedHigh = high;
  assign readEvents = reads;
  assign writeEvents = writes;
  assign when_BusSlaveFactory_l1299 = bus_w_payload_strb[0];
  assign when_BusSlaveFactory_l1299_1 = bus_w_payload_strb[1];
  assign when_BusSlaveFactory_l1299_2 = bus_w_payload_strb[2];
  assign when_BusSlaveFactory_l1299_3 = bus_w_payload_strb[3];
  assign when_BusSlaveFactory_l1299_4 = bus_w_payload_strb[0];
  assign when_BusSlaveFactory_l1299_5 = bus_w_payload_strb[1];
  assign when_BusSlaveFactory_l1299_6 = bus_w_payload_strb[2];
  assign when_BusSlaveFactory_l1299_7 = bus_w_payload_strb[3];
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      unburstify_buffer_valid <= 1'b0;
      factory_writeRsp_rValid <= 1'b0;
      unburstify_buffer_valid_1 <= 1'b0;
      unburstify_result_rValid <= 1'b0;
      register_1 <= 32'h0;
      high <= 32'h0;
      reads <= 8'h0;
      writes <= 8'h0;
    end else begin
      if(unburstify_result_ready) begin
        if(unburstify_buffer_last) begin
          unburstify_buffer_valid <= 1'b0;
        end
      end
      if(!unburstify_buffer_valid) begin
        if(when_Axi4Channel_l337) begin
          if(unburstify_result_ready) begin
            unburstify_buffer_valid <= bus_aw_valid;
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
            unburstify_buffer_valid_1 <= bus_ar_valid;
          end
        end
      end
      if(unburstify_result_ready_1) begin
        unburstify_result_rValid <= unburstify_result_valid_1;
      end
      case(factory_writeAddressMasked)
        ADDR_CONTROL : begin
          if(factory_writeOccur) begin
            if(when_BusSlaveFactory_l1299) begin
              register_1[7 : 0] <= bus_w_payload_data[7 : 0];
            end
            if(when_BusSlaveFactory_l1299_1) begin
              register_1[15 : 8] <= bus_w_payload_data[15 : 8];
            end
            if(when_BusSlaveFactory_l1299_2) begin
              register_1[23 : 16] <= bus_w_payload_data[23 : 16];
            end
            if(when_BusSlaveFactory_l1299_3) begin
              register_1[31 : 24] <= bus_w_payload_data[31 : 24];
            end
          end
        end
        ADDR_HIGH : begin
          if(factory_writeOccur) begin
            if(when_BusSlaveFactory_l1299_4) begin
              high[7 : 0] <= bus_w_payload_data[7 : 0];
            end
            if(when_BusSlaveFactory_l1299_5) begin
              high[15 : 8] <= bus_w_payload_data[15 : 8];
            end
            if(when_BusSlaveFactory_l1299_6) begin
              high[23 : 16] <= bus_w_payload_data[23 : 16];
            end
            if(when_BusSlaveFactory_l1299_7) begin
              high[31 : 24] <= bus_w_payload_data[31 : 24];
            end
          end
        end
        ADDR_EVENT : begin
          if(factory_writeOccur) begin
            writes <= (writes + 8'h01);
          end
        end
        default : begin
        end
      endcase
      case(factory_readAddressMasked)
        ADDR_EVENT : begin
          if(factory_readOccur) begin
            reads <= (reads + 8'h01);
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
          unburstify_buffer_transaction_addr <= bus_aw_payload_addr;
          unburstify_buffer_transaction_id <= bus_aw_payload_id;
          unburstify_buffer_transaction_region <= bus_aw_payload_region;
          unburstify_buffer_transaction_size <= bus_aw_payload_size;
          unburstify_buffer_transaction_burst <= bus_aw_payload_burst;
          unburstify_buffer_transaction_lock <= bus_aw_payload_lock;
          unburstify_buffer_transaction_cache <= bus_aw_payload_cache;
          unburstify_buffer_transaction_qos <= bus_aw_payload_qos;
          unburstify_buffer_transaction_prot <= bus_aw_payload_prot;
          unburstify_buffer_beat <= bus_aw_payload_len;
          unburstify_buffer_len <= bus_aw_payload_len;
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
          unburstify_buffer_transaction_addr_1 <= bus_ar_payload_addr;
          unburstify_buffer_transaction_id_1 <= bus_ar_payload_id;
          unburstify_buffer_transaction_region_1 <= bus_ar_payload_region;
          unburstify_buffer_transaction_size_1 <= bus_ar_payload_size;
          unburstify_buffer_transaction_burst_1 <= bus_ar_payload_burst;
          unburstify_buffer_transaction_lock_1 <= bus_ar_payload_lock;
          unburstify_buffer_transaction_cache_1 <= bus_ar_payload_cache;
          unburstify_buffer_transaction_qos_1 <= bus_ar_payload_qos;
          unburstify_buffer_transaction_prot_1 <= bus_ar_payload_prot;
          unburstify_buffer_beat_1 <= bus_ar_payload_len;
          unburstify_buffer_len_1 <= bus_ar_payload_len;
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
