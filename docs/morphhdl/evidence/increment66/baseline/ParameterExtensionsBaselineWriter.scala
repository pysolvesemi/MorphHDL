package morphhdl.examples

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.{HdlInt, formalParam}
import spinal.core._

/** Historical reproductions for Increment 66. A recorded rejection is not a
  * passing generation. The writer retains each exact status next to its source.
  */
object ParameterExtensionsBaselineWriter {
  class ConditionalLaneLoop extends Component {
    val previousData = in Bits(120 bits)
    val previousMask = in Bits(4 bits)
    val clear = in Bool()
    val selected = in UInt(3 bits)
    val pixel = in Bits(30 bits)
    val assembled = out Bits(120 bits)
    val mask = out Bits(4 bits)
    assembled := previousData
    mask := previousMask
    when(clear) { assembled := 0; mask := 0 }
    for (lane <- 0 until 4) {
      when(selected === U(lane, 3 bits)) {
        assembled(lane * 30 + 29 downto lane * 30) := pixel
        mask(lane) := True
      }
    }
  }
  class SymbolicResizeProbe(width: ElabInt) extends Component {
    val keep = in UInt(width bits)
    val wide = out UInt((width + 1) bits)
    wide := keep.resize(width + 1)
  }
  class SymbolicValueWorkaround(width: ElabInt) extends Component {
    val value = out UInt(8 bits)
    val zero = UInt(width bits)
    zero := 0
    value := spinal.lib.CountOne((~zero).resize(32)).resize(8)
  }
  class ExistingSymbolicValue(width: ElabInt) extends Component {
    val value = out UInt(8 bits)
    value := ElabValue.uintLike(width, value, "busBytesValue")
  }
  class ExistingValueWithInput(width: ElabInt) extends ExistingSymbolicValue(width) {
    val unrelated = in Bool()
    val passthrough = out Bool()
    passthrough := unrelated
  }
  class WorkaroundWithInput(width: ElabInt) extends SymbolicValueWorkaround(width) {
    val unrelated = in Bool()
    val passthrough = out Bool()
    passthrough := unrelated
  }
  class ScalarChild(actual: HdlInt, zero: Boolean) extends Component {
    @dontName private val scalar = if (zero) formalParam(actual, "PPC4", 0, 1)
      else formalParam(actual, "BUS_LOG2_BYTES", 2, 5)
    @dontName private val width = if (zero) scalar.asElabInt * 3 + 1 else scalar.asElabInt.pow2 * 8
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    dout := din
  }
  class ScalarTop(zero: Boolean) extends Component {
    @dontName private val actual = HdlInt.param(if (zero) "PARENT_PPC4" else "PARENT_LOG_BYTES",
      if (zero) 0 else 3, if (zero) 0 else 2, if (zero) 1 else 5)
    private val width = if (zero) actual.asElabInt * 3 + 1 else actual.asElabInt.pow2 * 8
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    val child = new ScalarChild(actual, zero)
    child.din := din
    dout := child.dout
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    def probe(name: String)(body: => Component): Unit = {
      val dir = root.resolve(name)
      Files.createDirectories(dir)
      try {
        MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = dir.toString,
          oneFilePerComponent = true, headerWithDate = false), enabled = false))(body)
        Files.write(dir.resolve("status.txt"), "EMITTED\n".getBytes(UTF_8))
        println(s"PROBE $name EMITTED")
      } catch { case error: Exception =>
        val detail = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
          .map(value => String.valueOf(value.getMessage)).mkString("\n")
        Files.write(dir.resolve("status.txt"), ("REJECTED\n" + detail + "\n").getBytes(UTF_8))
        println(s"PROBE $name REJECTED: ${error.getMessage}")
      }
    }
    probe("ordinary-loop")(new ConditionalLaneLoop)
    SpinalVerilog(SpinalConfig(targetDirectory = root.resolve("ordinary-loop-native").toString,
      headerWithDate = false))(new ConditionalLaneLoop)
    probe("resize-direct")(new SymbolicResizeProbe(HdlInt.param("WIDTH", 8, 1, 32).asElabInt))
    probe("resize-derived")(new SymbolicResizeProbe(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2 * 8 / 8))
    probe("value-workaround")(new SymbolicValueWorkaround(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2))
    probe("existing-value-api")(new ExistingSymbolicValue(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2))
    probe("existing-value-with-input")(new ExistingValueWithInput(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2))
    probe("workaround-with-input")(new WorkaroundWithInput(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2))
    probe("scalar-formal")(new ScalarTop(false))
    probe("zero-formal")(new ScalarTop(true))
  }
}
