package displaycontroller.sanity

import spinal.core._
import spinal.lib.bus.amba4.axilite._

/**
  * Bring-up-only Phase 1 shell. Only the AXI4-Lite CSR slice is functional;
  * all video, DDR-read, DPI, and interrupt datapaths are deliberately quiescent.
  */
case class DisplayControllerSanityShell(config: DisplayControllerSanityShellConfig)
    extends Component {
  config.validate()
  setDefinitionName(config.moduleName)

  val io = new Bundle {
    val aclk = in Bool()
    val aresetn = in Bool()

    val s_axil_awaddr = in UInt (config.axilAddressWidth bits)
    val s_axil_awprot = in Bits (3 bits)
    val s_axil_awvalid = in Bool()
    val s_axil_awready = out Bool()
    val s_axil_wdata = in Bits (config.axilDataWidth bits)
    val s_axil_wstrb = in Bits (config.axilDataWidth / 8 bits)
    val s_axil_wvalid = in Bool()
    val s_axil_wready = out Bool()
    val s_axil_bresp = out Bits (2 bits)
    val s_axil_bvalid = out Bool()
    val s_axil_bready = in Bool()
    val s_axil_araddr = in UInt (config.axilAddressWidth bits)
    val s_axil_arprot = in Bits (3 bits)
    val s_axil_arvalid = in Bool()
    val s_axil_arready = out Bool()
    val s_axil_rdata = out Bits (config.axilDataWidth bits)
    val s_axil_rresp = out Bits (2 bits)
    val s_axil_rvalid = out Bool()
    val s_axil_rready = in Bool()

    val s_axis_tdata = in Bits (config.axisDataWidth bits)
    val s_axis_tvalid = in Bool()
    val s_axis_tready = out Bool()
    val s_axis_tlast = in Bool()
    val s_axis_tuser = in Bits (config.axisUserWidth bits)

    val m_axi_arid = out Bits (config.axiIdWidth bits)
    val m_axi_araddr = out UInt (config.axiAddressWidth bits)
    val m_axi_arlen = out Bits (8 bits)
    val m_axi_arsize = out Bits (3 bits)
    val m_axi_arburst = out Bits (2 bits)
    val m_axi_arlock = out Bool()
    val m_axi_arcache = out Bits (4 bits)
    val m_axi_arprot = out Bits (3 bits)
    val m_axi_arqos = out Bits (4 bits)
    val m_axi_arvalid = out Bool()
    val m_axi_arready = in Bool()
    val m_axi_rid = in Bits (config.axiIdWidth bits)
    val m_axi_rdata = in Bits (config.axiDataWidth bits)
    val m_axi_rresp = in Bits (2 bits)
    val m_axi_rlast = in Bool()
    val m_axi_rvalid = in Bool()
    val m_axi_rready = out Bool()

    val dpi_pclk = out Bool()
    val dpi_r = out Bits (config.dpiComponentWidth bits)
    val dpi_g = out Bits (config.dpiComponentWidth bits)
    val dpi_b = out Bits (config.dpiComponentWidth bits)
    val dpi_hsync = out Bool()
    val dpi_vsync = out Bool()
    val dpi_de = out Bool()

    val irq = out Bool()
  }
  noIoPrefix()

  // Bring-up-only quiescent behavior for all unimplemented datapaths.
  io.s_axis_tready := False

  io.m_axi_arid := 0
  io.m_axi_araddr := 0
  io.m_axi_arlen := 0
  io.m_axi_arsize := 0
  io.m_axi_arburst := 0
  io.m_axi_arlock := False
  io.m_axi_arcache := 0
  io.m_axi_arprot := 0
  io.m_axi_arqos := 0
  io.m_axi_arvalid := False
  io.m_axi_rready := False

  io.dpi_pclk := io.aclk
  io.dpi_r := 0
  io.dpi_g := 0
  io.dpi_b := 0
  io.dpi_hsync := False
  io.dpi_vsync := False
  io.dpi_de := False
  io.irq := False

  val sanityClockDomain = ClockDomain(
    clock = io.aclk,
    reset = io.aresetn,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val csrArea = new ClockingArea(sanityClockDomain) {
    val axil = AxiLite4(config.axilAddressWidth, config.axilDataWidth)

    // Behavior-free flat-port adaptation to the library AXI4-Lite bundle.
    axil.aw.valid := io.s_axil_awvalid
    axil.aw.addr := io.s_axil_awaddr
    axil.aw.prot := io.s_axil_awprot
    io.s_axil_awready := axil.aw.ready

    axil.w.valid := io.s_axil_wvalid
    axil.w.data := io.s_axil_wdata
    axil.w.strb := io.s_axil_wstrb
    io.s_axil_wready := axil.w.ready

    io.s_axil_bvalid := axil.b.valid
    io.s_axil_bresp := axil.b.resp
    axil.b.ready := io.s_axil_bready

    axil.ar.valid := io.s_axil_arvalid
    axil.ar.addr := io.s_axil_araddr
    axil.ar.prot := io.s_axil_arprot
    io.s_axil_arready := axil.ar.ready

    io.s_axil_rvalid := axil.r.valid
    io.s_axil_rdata := axil.r.data
    io.s_axil_rresp := axil.r.resp
    axil.r.ready := io.s_axil_rready

    // The library factory owns AW/W joining, response generation, decode, and WSTRB.
    val factory = new AxiLite4SlaveFactory(axil, useWriteStrobes = true)

    factory.read(B(BigInt("00010000", 16), config.axilDataWidth bits), 0x000)
    factory.read(B(BigInt("0000000f", 16), config.axilDataWidth bits), 0x004)

    val scratch = factory.createReadAndWrite(Bits(config.axilDataWidth bits), 0x008)
    scratch.init(0)

    val sanityEnable = factory.createReadAndWrite(Bool(), 0x00c, 0)
    sanityEnable.init(False)

    factory.read(sanityEnable, 0x010, 0)
  }
}
