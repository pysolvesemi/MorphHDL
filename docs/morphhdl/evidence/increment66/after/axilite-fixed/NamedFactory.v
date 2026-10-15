// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : NamedFactory
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module NamedFactory (
  input  wire          bus_aw_valid,
  output wire          bus_aw_ready,
  input  wire [7:0]    bus_aw_payload_addr,
  input  wire [2:0]    bus_aw_payload_prot,
  input  wire          bus_w_valid,
  output wire          bus_w_ready,
  input  wire [31:0]   bus_w_payload_data,
  input  wire [3:0]    bus_w_payload_strb,
  output wire          bus_b_valid,
  input  wire          bus_b_ready,
  output wire [1:0]    bus_b_payload_resp,
  input  wire          bus_ar_valid,
  output wire          bus_ar_ready,
  input  wire [7:0]    bus_ar_payload_addr,
  input  wire [2:0]    bus_ar_payload_prot,
  output wire          bus_r_valid,
  input  wire          bus_r_ready,
  output wire [31:0]   bus_r_payload_data,
  output wire [1:0]    bus_r_payload_resp,
  output wire [31:0]   observed,
  output wire [31:0]   observedHigh,
  output wire [7:0]    readEvents,
  output wire [7:0]    writeEvents,
  input  wire          clk,
  input  wire          reset
);
  localparam [7:0] ADDR_CONTROL = 4;
  localparam [7:0] ADDR_EVENT = (ADDR_CONTROL + 4);
  localparam [7:0] ADDR_HIGH = 252;

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
  reg                 bus_ar_rValid;
  wire                factory_readDataStage_fire;
  reg        [7:0]    bus_ar_rData_addr;
  reg        [2:0]    bus_ar_rData_prot;
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

  assign factory_readErrorFlag = 1'b0;
  assign factory_writeErrorFlag = 1'b0;
  assign factory_readHaltRequest = 1'b0;
  assign factory_writeHaltRequest = 1'b0;
  assign factory_writeOccur = (factory_writeJoinEvent_valid && factory_writeJoinEvent_ready);
  assign factory_writeJoinEvent_valid = (bus_aw_valid && bus_w_valid);
  assign bus_aw_ready = factory_writeOccur;
  assign bus_w_ready = factory_writeOccur;
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
  assign bus_b_valid = factory_writeJoinEvent_translated_haltWhen_halfPipe_valid;
  assign factory_writeJoinEvent_translated_haltWhen_halfPipe_ready = bus_b_ready;
  assign bus_b_payload_resp = factory_writeJoinEvent_translated_haltWhen_halfPipe_payload_resp;
  assign factory_readDataStage_fire = (factory_readDataStage_valid && factory_readDataStage_ready);
  assign bus_ar_ready = (! bus_ar_rValid);
  assign factory_readDataStage_valid = bus_ar_rValid;
  assign factory_readDataStage_payload_addr = bus_ar_rData_addr;
  assign factory_readDataStage_payload_prot = bus_ar_rData_prot;
  assign _zz_factory_readDataStage_ready = (! factory_readHaltRequest);
  assign factory_readDataStage_haltWhen_valid = (factory_readDataStage_valid && _zz_factory_readDataStage_ready);
  assign factory_readDataStage_ready = (factory_readDataStage_haltWhen_ready && _zz_factory_readDataStage_ready);
  assign factory_readDataStage_haltWhen_payload_addr = factory_readDataStage_payload_addr;
  assign factory_readDataStage_haltWhen_payload_prot = factory_readDataStage_payload_prot;
  assign factory_readDataStage_haltWhen_translated_valid = factory_readDataStage_haltWhen_valid;
  assign factory_readDataStage_haltWhen_ready = factory_readDataStage_haltWhen_translated_ready;
  assign factory_readDataStage_haltWhen_translated_payload_data = factory_readRsp_data;
  assign factory_readDataStage_haltWhen_translated_payload_resp = factory_readRsp_resp;
  assign bus_r_valid = factory_readDataStage_haltWhen_translated_valid;
  assign factory_readDataStage_haltWhen_translated_ready = bus_r_ready;
  assign bus_r_payload_data = factory_readDataStage_haltWhen_translated_payload_data;
  assign bus_r_payload_resp = factory_readDataStage_haltWhen_translated_payload_resp;
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

  assign factory_readAddressMasked = (factory_readDataStage_payload_addr & (~ 8'h03));
  assign factory_writeAddressMasked = (bus_aw_payload_addr & (~ 8'h03));
  assign factory_readOccur = (bus_r_valid && bus_r_ready);
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
      factory_writeJoinEvent_translated_haltWhen_rValid <= 1'b0;
      bus_ar_rValid <= 1'b0;
      register_1 <= 32'h0;
      high <= 32'h0;
      reads <= 8'h0;
      writes <= 8'h0;
    end else begin
      if(factory_writeJoinEvent_translated_haltWhen_valid) begin
        factory_writeJoinEvent_translated_haltWhen_rValid <= 1'b1;
      end
      if(factory_writeJoinEvent_translated_haltWhen_halfPipe_fire) begin
        factory_writeJoinEvent_translated_haltWhen_rValid <= 1'b0;
      end
      if(bus_ar_valid) begin
        bus_ar_rValid <= 1'b1;
      end
      if(factory_readDataStage_fire) begin
        bus_ar_rValid <= 1'b0;
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
    if(factory_writeJoinEvent_translated_haltWhen_ready) begin
      factory_writeJoinEvent_translated_haltWhen_rData_resp <= factory_writeJoinEvent_translated_haltWhen_payload_resp;
    end
    if(bus_ar_ready) begin
      bus_ar_rData_addr <= bus_ar_payload_addr;
      bus_ar_rData_prot <= bus_ar_payload_prot;
    end
  end


endmodule
