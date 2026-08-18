`timescale 1ns/1ps

package axi_lite_bfm_types_pkg;
  typedef enum int unsigned {
    WRITE_SAME_CYCLE,
    WRITE_AW_FIRST,
    WRITE_W_FIRST
  } write_order_e;

  typedef enum int unsigned {
    BFM_OK,
    BFM_RESET_CANCELLED,
    BFM_AW_TIMEOUT,
    BFM_W_TIMEOUT,
    BFM_B_TIMEOUT,
    BFM_BRESP_ERROR,
    BFM_AR_TIMEOUT,
    BFM_R_TIMEOUT,
    BFM_RRESP_ERROR,
    BFM_DATA_MISMATCH
  } bfm_result_t;
endpackage

module axi_lite_master_bfm (
  input  logic        aclk,
  input  logic        aresetn,

  output logic [11:0] awaddr,
  output logic [2:0]  awprot,
  output logic        awvalid,
  input  logic        awready,

  output logic [31:0] wdata,
  output logic [3:0]  wstrb,
  output logic        wvalid,
  input  logic        wready,

  input  logic [1:0]  bresp,
  input  logic        bvalid,
  output logic        bready,

  output logic [11:0] araddr,
  output logic [2:0]  arprot,
  output logic        arvalid,
  input  logic        arready,

  input  logic [31:0] rdata,
  input  logic [1:0]  rresp,
  input  logic        rvalid,
  output logic        rready
);
  import axi_lite_bfm_types_pkg::*;

  task automatic init_master();
    awaddr  = '0;
    awprot  = '0;
    awvalid = 1'b0;
    wdata   = '0;
    wstrb   = '0;
    wvalid  = 1'b0;
    bready  = 1'b0;
    araddr  = '0;
    arprot  = '0;
    arvalid = 1'b0;
    rready  = 1'b0;
  endtask

  task automatic axi_write32(
    input  logic [11:0] addr,
    input  logic [31:0] data,
    input  logic [3:0]  strb,
    input  write_order_e order,
    input  int unsigned timeout_cycles,
    output bfm_result_t result
  );
    bit aw_done;
    bit w_done;
    int unsigned aw_wait;
    int unsigned w_wait;
    int unsigned b_wait;
    bit second_channel_started;

    result = BFM_OK;
    aw_done = 1'b0;
    w_done = 1'b0;
    aw_wait = 0;
    w_wait = 0;
    b_wait = 0;
    second_channel_started = (order == WRITE_SAME_CYCLE);

    @(negedge aclk);
    if (!aresetn) begin
      init_master();
      result = BFM_RESET_CANCELLED;
      return;
    end

    awaddr = addr;
    awprot = 3'b000;
    wdata = data;
    wstrb = strb;

    case (order)
      WRITE_SAME_CYCLE: begin
        awvalid = 1'b1;
        wvalid = 1'b1;
      end
      WRITE_AW_FIRST: begin
        awvalid = 1'b1;
        wvalid = 1'b0;
      end
      WRITE_W_FIRST: begin
        awvalid = 1'b0;
        wvalid = 1'b1;
      end
      default: $fatal(1, "AXI_LITE_BFM_INVALID_WRITE_ORDER");
    endcase

    while (!(aw_done && w_done)) begin
      @(posedge aclk);
      if (!aresetn) begin
        init_master();
        result = BFM_RESET_CANCELLED;
        return;
      end

      if (awvalid && awready)
        aw_done = 1'b1;
      if (wvalid && wready)
        w_done = 1'b1;

      if (awvalid && !aw_done) begin
        aw_wait++;
        if (aw_wait >= timeout_cycles) begin
          init_master();
          result = BFM_AW_TIMEOUT;
          return;
        end
      end
      if (wvalid && !w_done) begin
        w_wait++;
        if (w_wait >= timeout_cycles) begin
          init_master();
          result = BFM_W_TIMEOUT;
          return;
        end
      end

      @(negedge aclk);
      if (!aresetn) begin
        init_master();
        result = BFM_RESET_CANCELLED;
        return;
      end

      if (aw_done)
        awvalid = 1'b0;
      if (w_done)
        wvalid = 1'b0;

      // Start the second independent channel after one full clock even when
      // the first channel has not handshaken yet. This supports both slaves
      // that accept AW/W independently and library implementations that join
      // the channels before asserting READY.
      if (!second_channel_started) begin
        case (order)
          WRITE_AW_FIRST: wvalid = 1'b1;
          WRITE_W_FIRST:  awvalid = 1'b1;
          default: ;
        endcase
        second_channel_started = 1'b1;
      end
    end

    bready = 1'b1;
    while (1) begin
      @(posedge aclk);
      if (!aresetn) begin
        init_master();
        result = BFM_RESET_CANCELLED;
        return;
      end

      if (bvalid && bready) begin
        result = (bresp == 2'b00) ? BFM_OK : BFM_BRESP_ERROR;
        @(negedge aclk);
        bready = 1'b0;
        awaddr = '0;
        wdata = '0;
        wstrb = '0;
        return;
      end

      b_wait++;
      if (b_wait >= timeout_cycles) begin
        init_master();
        result = BFM_B_TIMEOUT;
        return;
      end
    end
  endtask

  task automatic axi_read32(
    input  logic [11:0] addr,
    output logic [31:0] data,
    input  int unsigned timeout_cycles,
    output bfm_result_t result
  );
    int unsigned ar_wait;
    int unsigned r_wait;

    data = '0;
    result = BFM_OK;
    ar_wait = 0;
    r_wait = 0;

    @(negedge aclk);
    if (!aresetn) begin
      init_master();
      result = BFM_RESET_CANCELLED;
      return;
    end

    araddr = addr;
    arprot = 3'b000;
    arvalid = 1'b1;

    while (1) begin
      @(posedge aclk);
      if (!aresetn) begin
        init_master();
        result = BFM_RESET_CANCELLED;
        return;
      end

      if (arvalid && arready) begin
        @(negedge aclk);
        arvalid = 1'b0;
        rready = 1'b1;
        break;
      end

      ar_wait++;
      if (ar_wait >= timeout_cycles) begin
        init_master();
        result = BFM_AR_TIMEOUT;
        return;
      end
    end

    while (1) begin
      @(posedge aclk);
      if (!aresetn) begin
        init_master();
        result = BFM_RESET_CANCELLED;
        return;
      end

      if (rvalid && rready) begin
        data = rdata;
        result = (rresp == 2'b00) ? BFM_OK : BFM_RRESP_ERROR;
        @(negedge aclk);
        rready = 1'b0;
        araddr = '0;
        return;
      end

      r_wait++;
      if (r_wait >= timeout_cycles) begin
        init_master();
        result = BFM_R_TIMEOUT;
        return;
      end
    end
  endtask

  task automatic expect_read32(
    input  logic [11:0] addr,
    input  logic [31:0] expected,
    input  logic [31:0] mask,
    input  int unsigned timeout_cycles,
    output bfm_result_t result
  );
    logic [31:0] observed;

    axi_read32(addr, observed, timeout_cycles, result);
    if (result != BFM_OK)
      return;

    if ((observed & mask) !== (expected & mask)) begin
      $error(
        "AXI_LITE_READ_MISMATCH addr=0x%03h expected=0x%08h observed=0x%08h mask=0x%08h",
        addr, expected, observed, mask
      );
      result = BFM_DATA_MISMATCH;
    end
  endtask
endmodule
