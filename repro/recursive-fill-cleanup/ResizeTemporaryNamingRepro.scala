package roadmap

import spinal.core._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt

object ResizeTemporaryNamingRepro extends App {
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
      setDefinitionName("ResizeTemporaryNamingRepro")
      val depth: ElabInt = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
      val width: ElabInt = depth + 2
      val a, b = in UInt(width bits)
      val wide, namedWide, zzNamedWide = out UInt(18 bits)
      val roundTrip = out UInt(width bits)

      // No application-defined name for this subtraction result.
      wide := (a - b).resize(18)
      roundTrip := wide.resize(width)

      // Explicit user preservation and names are independent of cleanup.
      val kept = UInt(width bits)
      kept.setName("kept_difference")
      kept.dontSimplifyIt()
      kept := a - b
      namedWide := kept.resize(18)

      val zzKept = UInt(width bits)
      zzKept.setName("_zz_user_kept")
      zzKept.dontSimplifyIt()
      zzKept := a - b
      zzNamedWide := zzKept.resize(18)
    }
  }
}
