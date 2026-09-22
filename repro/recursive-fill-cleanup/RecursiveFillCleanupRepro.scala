package roadmap

import spinal.core._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt

object RecursiveFillCleanupRepro extends App {
  require(args.length == 2, "Expected output-directory and passes-enabled")
  val config = MorphWireAssignmentPasses(
    SpinalConfig(
      targetDirectory = args(0),
      oneFilePerComponent = true,
      headerWithDate = false,
      headerWithRepoHash = true
    ),
    enabled = args(1).toBoolean
  )

  morphhdl.examples.NativeCleanupTrace.install(config)
  MorphVerilog(config) {
    new Component {
      setDefinitionName("RecursiveFillCleanupRepro")
      val depth: ElabInt = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
      val countBits: ElabInt = depth + 2
      val capacity: ElabInt = depth.pow2 + 1

      val bypass = in Bool()
      val upper, lower = in UInt(countBits bits)
      val writeFill, readFill = out UInt(countBits bits)

      // Preserve the real path: symbolic capacity in a fixed 18-bit carrier,
      // fixed-width selection, then a symbolic narrowing resize.
      val capacityValue = ElabValue.uintLike(
        capacity, U(0, 18 bits), "fifo_capacity")
      val upperWide = UInt(18 bits)
      upperWide := upper.resize(18)
      val selectedUpper = UInt(18 bits)
      selectedUpper := Mux(upper > capacityValue, capacityValue, upperWide)
      val clampedUpper = UInt(countBits bits)
      clampedUpper := selectedUpper.resize(countBits)
      val zeroFill = UInt(countBits bits)
      zeroFill := 0

      writeFill := Mux(bypass, zeroFill, clampedUpper)
      readFill := Mux(bypass || lower > capacityValue, zeroFill, lower)
    }
  }
}
