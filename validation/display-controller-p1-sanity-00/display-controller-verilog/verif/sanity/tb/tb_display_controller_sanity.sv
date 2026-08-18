timeunit 1ns;
timeprecision 1ps;

module tb_display_controller_sanity;
  import axi_lite_bfm_types_pkg::*;

  localparam int unsigned TASK_TIMEOUT_CYCLES = 1000;
  localparam time GLOBAL_TIMEOUT = 100us;

  localparam logic [11:0] CORE_VERSION_ADDR = 12'h000;
  localparam logic [11:0] CAPABILITIES_ADDR = 12'h004;
  localparam logic [11:0] SCRATCH_ADDR = 12'h008;
  localparam logic [11:0] CONTROL_ADDR = 12'h00c;
  localparam logic [11:0] STATUS_ADDR = 12'h010;

  logic aclk;
  logic aresetn;

  logic [11:0] s_axil_awaddr;
  logic [2:0]  s_axil_awprot;
  logic        s_axil_awvalid;
  logic        s_axil_awready;
  logic [31:0] s_axil_wdata;
  logic [3:0]  s_axil_wstrb;
  logic        s_axil_wvalid;
  logic        s_axil_wready;
  logic [1:0]  s_axil_bresp;
  logic        s_axil_bvalid;
  logic        s_axil_bready;
  logic [11:0] s_axil_araddr;
  logic [2:0]  s_axil_arprot;
  logic        s_axil_arvalid;
  logic        s_axil_arready;
  logic [31:0] s_axil_rdata;
  logic [1:0]  s_axil_rresp;
  logic        s_axil_rvalid;
  logic        s_axil_rready;

  logic [31:0] s_axis_tdata;
  logic        s_axis_tvalid;
  logic        s_axis_tready;
  logic        s_axis_tlast;
  logic        s_axis_tuser;

  logic [3:0]  m_axi_arid;
  logic [31:0] m_axi_araddr;
  logic [7:0]  m_axi_arlen;
  logic [2:0]  m_axi_arsize;
  logic [1:0]  m_axi_arburst;
  logic        m_axi_arlock;
  logic [3:0]  m_axi_arcache;
  logic [2:0]  m_axi_arprot;
  logic [3:0]  m_axi_arqos;
  logic        m_axi_arvalid;
  logic        m_axi_arready;
  logic [3:0]  m_axi_rid;
  logic [63:0] m_axi_rdata;
  logic [1:0]  m_axi_rresp;
  logic        m_axi_rlast;
  logic        m_axi_rvalid;
  logic        m_axi_rready;

  logic       dpi_pclk;
  logic [7:0] dpi_r;
  logic [7:0] dpi_g;
  logic [7:0] dpi_b;
  logic       dpi_hsync;
  logic       dpi_vsync;
  logic       dpi_de;
  logic       irq;

  int unsigned aw_count;
  int unsigned w_count;
  int unsigned write_pair_count;
  int unsigned b_count;
  int unsigned ar_count;
  int unsigned r_count;
  int unsigned pending_write_count;
  int unsigned pending_read_count;

  logic aux_aresetn;
  logic [11:0] aux_awaddr;
  logic [2:0]  aux_awprot;
  logic        aux_awvalid;
  logic [31:0] aux_wdata;
  logic [3:0]  aux_wstrb;
  logic        aux_wvalid;
  logic        aux_bready;
  logic [11:0] aux_araddr;
  logic [2:0]  aux_arprot;
  logic        aux_arvalid;
  logic        aux_rready;

  DisplayControllerSanityShell dut (
    .aclk(aclk),
    .aresetn(aresetn),
    .s_axil_awaddr(s_axil_awaddr),
    .s_axil_awprot(s_axil_awprot),
    .s_axil_awvalid(s_axil_awvalid),
    .s_axil_awready(s_axil_awready),
    .s_axil_wdata(s_axil_wdata),
    .s_axil_wstrb(s_axil_wstrb),
    .s_axil_wvalid(s_axil_wvalid),
    .s_axil_wready(s_axil_wready),
    .s_axil_bresp(s_axil_bresp),
    .s_axil_bvalid(s_axil_bvalid),
    .s_axil_bready(s_axil_bready),
    .s_axil_araddr(s_axil_araddr),
    .s_axil_arprot(s_axil_arprot),
    .s_axil_arvalid(s_axil_arvalid),
    .s_axil_arready(s_axil_arready),
    .s_axil_rdata(s_axil_rdata),
    .s_axil_rresp(s_axil_rresp),
    .s_axil_rvalid(s_axil_rvalid),
    .s_axil_rready(s_axil_rready),
    .s_axis_tdata(s_axis_tdata),
    .s_axis_tvalid(s_axis_tvalid),
    .s_axis_tready(s_axis_tready),
    .s_axis_tlast(s_axis_tlast),
    .s_axis_tuser(s_axis_tuser),
    .m_axi_arid(m_axi_arid),
    .m_axi_araddr(m_axi_araddr),
    .m_axi_arlen(m_axi_arlen),
    .m_axi_arsize(m_axi_arsize),
    .m_axi_arburst(m_axi_arburst),
    .m_axi_arlock(m_axi_arlock),
    .m_axi_arcache(m_axi_arcache),
    .m_axi_arprot(m_axi_arprot),
    .m_axi_arqos(m_axi_arqos),
    .m_axi_arvalid(m_axi_arvalid),
    .m_axi_arready(m_axi_arready),
    .m_axi_rid(m_axi_rid),
    .m_axi_rdata(m_axi_rdata),
    .m_axi_rresp(m_axi_rresp),
    .m_axi_rlast(m_axi_rlast),
    .m_axi_rvalid(m_axi_rvalid),
    .m_axi_rready(m_axi_rready),
    .dpi_pclk(dpi_pclk),
    .dpi_r(dpi_r),
    .dpi_g(dpi_g),
    .dpi_b(dpi_b),
    .dpi_hsync(dpi_hsync),
    .dpi_vsync(dpi_vsync),
    .dpi_de(dpi_de),
    .irq(irq)
  );

  axi_lite_master_bfm bfm (
    .aclk(aclk),
    .aresetn(aresetn),
    .awaddr(s_axil_awaddr),
    .awprot(s_axil_awprot),
    .awvalid(s_axil_awvalid),
    .awready(s_axil_awready),
    .wdata(s_axil_wdata),
    .wstrb(s_axil_wstrb),
    .wvalid(s_axil_wvalid),
    .wready(s_axil_wready),
    .bresp(s_axil_bresp),
    .bvalid(s_axil_bvalid),
    .bready(s_axil_bready),
    .araddr(s_axil_araddr),
    .arprot(s_axil_arprot),
    .arvalid(s_axil_arvalid),
    .arready(s_axil_arready),
    .rdata(s_axil_rdata),
    .rresp(s_axil_rresp),
    .rvalid(s_axil_rvalid),
    .rready(s_axil_rready)
  );

  axi_lite_master_bfm aux_bfm (
    .aclk(aclk),
    .aresetn(aux_aresetn),
    .awaddr(aux_awaddr),
    .awprot(aux_awprot),
    .awvalid(aux_awvalid),
    .awready(1'b0),
    .wdata(aux_wdata),
    .wstrb(aux_wstrb),
    .wvalid(aux_wvalid),
    .wready(1'b0),
    .bresp(2'b00),
    .bvalid(1'b0),
    .bready(aux_bready),
    .araddr(aux_araddr),
    .arprot(aux_arprot),
    .arvalid(aux_arvalid),
    .arready(1'b0),
    .rdata(32'h00000000),
    .rresp(2'b00),
    .rvalid(1'b0),
    .rready(aux_rready)
  );

  axi_lite_passive_monitor monitor (
    .aclk(aclk),
    .aresetn(aresetn),
    .awaddr(s_axil_awaddr),
    .awprot(s_axil_awprot),
    .awvalid(s_axil_awvalid),
    .awready(s_axil_awready),
    .wdata(s_axil_wdata),
    .wstrb(s_axil_wstrb),
    .wvalid(s_axil_wvalid),
    .wready(s_axil_wready),
    .bresp(s_axil_bresp),
    .bvalid(s_axil_bvalid),
    .bready(s_axil_bready),
    .araddr(s_axil_araddr),
    .arprot(s_axil_arprot),
    .arvalid(s_axil_arvalid),
    .arready(s_axil_arready),
    .rdata(s_axil_rdata),
    .rresp(s_axil_rresp),
    .rvalid(s_axil_rvalid),
    .rready(s_axil_rready),
    .s_axis_tready(s_axis_tready),
    .m_axi_arid(m_axi_arid),
    .m_axi_araddr(m_axi_araddr),
    .m_axi_arlen(m_axi_arlen),
    .m_axi_arsize(m_axi_arsize),
    .m_axi_arburst(m_axi_arburst),
    .m_axi_arlock(m_axi_arlock),
    .m_axi_arcache(m_axi_arcache),
    .m_axi_arprot(m_axi_arprot),
    .m_axi_arqos(m_axi_arqos),
    .m_axi_arvalid(m_axi_arvalid),
    .m_axi_rready(m_axi_rready),
    .dpi_pclk(dpi_pclk),
    .dpi_r(dpi_r),
    .dpi_g(dpi_g),
    .dpi_b(dpi_b),
    .dpi_hsync(dpi_hsync),
    .dpi_vsync(dpi_vsync),
    .dpi_de(dpi_de),
    .irq(irq),
    .aw_count(aw_count),
    .w_count(w_count),
    .write_pair_count(write_pair_count),
    .b_count(b_count),
    .ar_count(ar_count),
    .r_count(r_count),
    .pending_write_count(pending_write_count),
    .pending_read_count(pending_read_count)
  );

  initial begin
    aclk = 1'b0;
    forever #5ns aclk = ~aclk;
  end

  initial begin
    #GLOBAL_TIMEOUT;
    $fatal(1, "GLOBAL_TIMEOUT");
  end

  task automatic require_ok(input bfm_result_t result, input string operation);
    if (result != BFM_OK)
      $fatal(1, "%s failed with BFM result %0d", operation, result);
  endtask

  task automatic write32(
    input logic [11:0] addr,
    input logic [31:0] data,
    input logic [3:0] strb,
    input write_order_e order,
    input string operation
  );
    bfm_result_t result;
    bfm.axi_write32(addr, data, strb, order, TASK_TIMEOUT_CYCLES, result);
    require_ok(result, operation);
  endtask

  task automatic expect32(
    input logic [11:0] addr,
    input logic [31:0] expected,
    input logic [31:0] mask,
    input string operation
  );
    bfm_result_t result;
    bfm.expect_read32(addr, expected, mask, TASK_TIMEOUT_CYCLES, result);
    require_ok(result, operation);
  endtask

  task automatic run_reset_cancellation_probe();
    bfm_result_t result;
    logic [31:0] observed;

    aux_bfm.init_master();
    aux_aresetn = 1'b1;
    fork
      begin
        aux_bfm.axi_read32(12'h020, observed, TASK_TIMEOUT_CYCLES, result);
      end
      begin
        repeat (3) @(posedge aclk);
        @(negedge aclk);
        aux_aresetn = 1'b0;
      end
    join

    if (result != BFM_RESET_CANCELLED)
      $fatal(1, "RESET_CANCELLATION_PROBE result=%0d", result);

    repeat (2) @(posedge aclk);
    @(negedge aclk);
    aux_aresetn = 1'b1;
  endtask

  task automatic run_expected_timeout_fatal();
    bfm_result_t result;
    logic [31:0] observed;

    aux_bfm.init_master();
    aux_aresetn = 1'b1;
    aux_bfm.axi_read32(12'h024, observed, 4, result);
    if (result != BFM_AR_TIMEOUT)
      $fatal(1, "EXPECTED_TIMEOUT_WRONG_RESULT result=%0d", result);
    $fatal(1, "EXPECTED_TIMEOUT_FATAL BFM_AR_TIMEOUT");
  endtask

  task automatic check_quiescent_shell();
    #1ps;
    if ({s_axis_tready, m_axi_arid, m_axi_araddr, m_axi_arlen,
         m_axi_arsize, m_axi_arburst, m_axi_arlock, m_axi_arcache,
         m_axi_arprot, m_axi_arqos, m_axi_arvalid, m_axi_rready,
         dpi_r, dpi_g, dpi_b, dpi_hsync, dpi_vsync, dpi_de, irq} !== '0)
      $fatal(1, "QUIESCENT_SHELL_CHECK_FAILED");
    if (dpi_pclk !== aclk)
      $fatal(1, "DPI_PCLK_TRACKING_FAILED");

    @(negedge aclk);
    #1ps;
    if (dpi_pclk !== 1'b0)
      $fatal(1, "DPI_PCLK_NEGEDGE_TRACKING_FAILED");
    @(posedge aclk);
    #1ps;
    if (dpi_pclk !== 1'b1)
      $fatal(1, "DPI_PCLK_POSEDGE_TRACKING_FAILED");
  endtask

  initial begin : test_sequence
    aresetn = 1'b0;
    aux_aresetn = 1'b0;
    bfm.init_master();
    aux_bfm.init_master();

    s_axis_tdata = '0;
    s_axis_tvalid = 1'b0;
    s_axis_tlast = 1'b0;
    s_axis_tuser = 1'b0;

    m_axi_arready = 1'b0;
    m_axi_rid = '0;
    m_axi_rdata = '0;
    m_axi_rresp = '0;
    m_axi_rlast = 1'b0;
    m_axi_rvalid = 1'b0;

    if ($test$plusargs("EXPECT_TIMEOUT_FATAL")) begin
      repeat (2) @(posedge aclk);
      run_expected_timeout_fatal();
    end

    run_reset_cancellation_probe();

    // Step 1: initial reset and reset-value reads.
    @(negedge aclk);
    aresetn = 1'b0;
    repeat (8) @(posedge aclk);
    @(negedge aclk);
    aresetn = 1'b1;
    @(posedge aclk);

    expect32(CORE_VERSION_ADDR, 32'h00010000, 32'hffffffff, "CORE_VERSION reset");
    expect32(CAPABILITIES_ADDR, 32'h0000000f, 32'hffffffff, "CAPABILITIES reset");
    expect32(SCRATCH_ADDR, 32'h00000000, 32'hffffffff, "SCRATCH reset");
    expect32(CONTROL_ADDR, 32'h00000000, 32'hffffffff, "CONTROL reset");
    expect32(STATUS_ADDR, 32'h00000000, 32'hffffffff, "STATUS reset");

    // Step 2: same-cycle full-word write.
    write32(SCRATCH_ADDR, 32'ha5a55a5a, 4'hf, WRITE_SAME_CYCLE, "SCRATCH full write");
    expect32(SCRATCH_ADDR, 32'ha5a55a5a, 32'hffffffff, "SCRATCH full readback");

    // Step 3: address-first byte write.
    write32(SCRATCH_ADDR, 32'h0000cc00, 4'b0010, WRITE_AW_FIRST, "SCRATCH AW-first byte write");
    expect32(SCRATCH_ADDR, 32'ha5a5cc5a, 32'hffffffff, "SCRATCH AW-first readback");

    // Step 4: data-first byte write.
    write32(SCRATCH_ADDR, 32'h11000022, 4'b1001, WRITE_W_FIRST, "SCRATCH W-first byte write");
    expect32(SCRATCH_ADDR, 32'h11a5cc22, 32'hffffffff, "SCRATCH W-first readback");

    // Step 5: CONTROL-to-STATUS observability.
    write32(CONTROL_ADDR, 32'h00000001, 4'hf, WRITE_SAME_CYCLE, "CONTROL enable");
    expect32(CONTROL_ADDR, 32'h00000001, 32'hffffffff, "CONTROL readback");
    expect32(STATUS_ADDR, 32'h00000001, 32'hffffffff, "STATUS mirror");

    // Step 6: writes to read-only locations are ignored.
    write32(CORE_VERSION_ADDR, 32'hffffffff, 4'hf, WRITE_SAME_CYCLE, "CORE_VERSION RO write");
    write32(CAPABILITIES_ADDR, 32'hffffffff, 4'hf, WRITE_SAME_CYCLE, "CAPABILITIES RO write");
    write32(STATUS_ADDR, 32'hffffffff, 4'hf, WRITE_SAME_CYCLE, "STATUS RO write");
    expect32(CORE_VERSION_ADDR, 32'h00010000, 32'hffffffff, "CORE_VERSION unchanged");
    expect32(CAPABILITIES_ADDR, 32'h0000000f, 32'hffffffff, "CAPABILITIES unchanged");
    expect32(STATUS_ADDR, 32'h00000001, 32'hffffffff, "STATUS unchanged");

    // Step 7: second reset and reset recovery.
    @(negedge aclk);
    aresetn = 1'b0;
    repeat (4) @(posedge aclk);
    @(negedge aclk);
    aresetn = 1'b1;
    @(posedge aclk);

    expect32(CORE_VERSION_ADDR, 32'h00010000, 32'hffffffff, "CORE_VERSION after reset");
    expect32(CAPABILITIES_ADDR, 32'h0000000f, 32'hffffffff, "CAPABILITIES after reset");
    expect32(SCRATCH_ADDR, 32'h00000000, 32'hffffffff, "SCRATCH after reset");
    expect32(CONTROL_ADDR, 32'h00000000, 32'hffffffff, "CONTROL after reset");
    expect32(STATUS_ADDR, 32'h00000000, 32'hffffffff, "STATUS after reset");

    // Step 8: deterministic shell tie-offs and DPI clock visibility.
    check_quiescent_shell();

    // Step 9: exact passive-monitor accounting and deterministic finish.
    if ((aw_count != 7) || (w_count != 7) || (write_pair_count != 7) ||
        (b_count != 7) || (ar_count != 18) || (r_count != 18) ||
        (pending_write_count != 0) || (pending_read_count != 0))
      $fatal(
        1,
        "MONITOR_ACCOUNTING aw=%0d w=%0d pair=%0d b=%0d ar=%0d r=%0d pw=%0d pr=%0d",
        aw_count, w_count, write_pair_count, b_count, ar_count, r_count,
        pending_write_count, pending_read_count
      );

    $display("[PASS] DisplayControllerSanityShell AXI4-Lite smoke completed: writes=7 reads=18");
    repeat (2) @(posedge aclk);
    $finish;
  end
endmodule
