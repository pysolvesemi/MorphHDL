`timescale 1ns/1ps

module axi_lite_passive_monitor (
  input logic        aclk,
  input logic        aresetn,

  input logic [11:0] awaddr,
  input logic [2:0]  awprot,
  input logic        awvalid,
  input logic        awready,
  input logic [31:0] wdata,
  input logic [3:0]  wstrb,
  input logic        wvalid,
  input logic        wready,
  input logic [1:0]  bresp,
  input logic        bvalid,
  input logic        bready,
  input logic [11:0] araddr,
  input logic [2:0]  arprot,
  input logic        arvalid,
  input logic        arready,
  input logic [31:0] rdata,
  input logic [1:0]  rresp,
  input logic        rvalid,
  input logic        rready,

  input logic        s_axis_tready,
  input logic [3:0]  m_axi_arid,
  input logic [31:0] m_axi_araddr,
  input logic [7:0]  m_axi_arlen,
  input logic [2:0]  m_axi_arsize,
  input logic [1:0]  m_axi_arburst,
  input logic        m_axi_arlock,
  input logic [3:0]  m_axi_arcache,
  input logic [2:0]  m_axi_arprot,
  input logic [3:0]  m_axi_arqos,
  input logic        m_axi_arvalid,
  input logic        m_axi_rready,
  input logic        dpi_pclk,
  input logic [7:0]  dpi_r,
  input logic [7:0]  dpi_g,
  input logic [7:0]  dpi_b,
  input logic        dpi_hsync,
  input logic        dpi_vsync,
  input logic        dpi_de,
  input logic        irq,

  output int unsigned aw_count,
  output int unsigned w_count,
  output int unsigned write_pair_count,
  output int unsigned b_count,
  output int unsigned ar_count,
  output int unsigned r_count,
  output int unsigned pending_write_count,
  output int unsigned pending_read_count
);
  int unsigned pending_aw;
  int unsigned pending_w;
  bit b_stalled;
  logic [1:0] bresp_held;
  bit r_stalled;
  logic [31:0] rdata_held;
  logic [1:0] rresp_held;

  initial begin
    aw_count = 0;
    w_count = 0;
    write_pair_count = 0;
    b_count = 0;
    ar_count = 0;
    r_count = 0;
    pending_aw = 0;
    pending_w = 0;
    pending_write_count = 0;
    pending_read_count = 0;
    b_stalled = 1'b0;
    r_stalled = 1'b0;
    bresp_held = '0;
    rdata_held = '0;
    rresp_held = '0;
  end

  always @(posedge aclk) begin
    #1ps;
    if (!aresetn) begin
      pending_aw = 0;
      pending_w = 0;
      pending_write_count = 0;
      pending_read_count = 0;
      b_stalled = 1'b0;
      r_stalled = 1'b0;
    end else begin
      if ($isunknown({awready, wready, bvalid, bresp, arready, rvalid, rdata, rresp}))
        $fatal(1, "AXI_LITE_MONITOR_XZ_RESPONSE");

      if (b_stalled && ((!bvalid) || (bresp !== bresp_held)))
        $fatal(1, "AXI_LITE_MONITOR_B_STABILITY");
      if (r_stalled && ((!rvalid) || (rdata !== rdata_held) || (rresp !== rresp_held)))
        $fatal(1, "AXI_LITE_MONITOR_R_STABILITY");

      b_stalled = bvalid && !bready;
      if (b_stalled)
        bresp_held = bresp;
      r_stalled = rvalid && !rready;
      if (r_stalled) begin
        rdata_held = rdata;
        rresp_held = rresp;
      end

      if (awvalid && awready) begin
        aw_count++;
        pending_aw++;
      end
      if (wvalid && wready) begin
        w_count++;
        pending_w++;
      end

      while ((pending_aw != 0) && (pending_w != 0)) begin
        pending_aw--;
        pending_w--;
        pending_write_count++;
        write_pair_count++;
      end

      if (bvalid && bready) begin
        if (pending_write_count == 0)
          $fatal(1, "AXI_LITE_MONITOR_UNEXPECTED_B");
        pending_write_count--;
        b_count++;
      end

      if (arvalid && arready) begin
        ar_count++;
        pending_read_count++;
      end

      if (rvalid && rready) begin
        if (pending_read_count == 0)
          $fatal(1, "AXI_LITE_MONITOR_UNEXPECTED_R");
        pending_read_count--;
        r_count++;
      end

      if ({s_axis_tready, m_axi_arid, m_axi_araddr, m_axi_arlen,
           m_axi_arsize, m_axi_arburst, m_axi_arlock, m_axi_arcache,
           m_axi_arprot, m_axi_arqos, m_axi_arvalid, m_axi_rready,
           dpi_r, dpi_g, dpi_b, dpi_hsync, dpi_vsync, dpi_de, irq} !== '0)
        $fatal(1, "SANITY_SHELL_NON_QUIESCENT_OUTPUT");

      if (dpi_pclk !== aclk)
        $fatal(1, "SANITY_SHELL_DPI_PCLK_MISMATCH");
    end
  end
endmodule
