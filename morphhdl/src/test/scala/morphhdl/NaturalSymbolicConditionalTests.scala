package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend._

object NaturalSymbolicConditionalSmoke {
  final class Sink extends Component {
    setDefinitionName("NaturalConditionalSink")
    val din = in(spinal.core.Bits(8 bits))
    val observed = out(Bool())
    observed := din.orR
  }

  final class Top(width: HdlInt, enabled: HdlBool) extends Component {
    setDefinitionName("NaturalSymbolicConditionalTop")
    val din = in(spinal.core.Bits(8 bits))
    val alive = out(Bool())
    alive := din.orR

    if (enabled) attach(din) else attach(~din)

    if (width > 16) attach(din)
    else if (width > 8) attach(~din)
    else attach(din)

    private def attach(value: spinal.core.Bits): Unit = {
      val branch = spinal.core.Bits(8 bits)
      branch := value
      val sink = new Sink
      sink.din := branch
    }
  }

  def component(): Component = {
    val width = HdlInt.param("WIDTH", default = 12, min = 1, max = 32)
    val enabled = HdlBool.param("ENABLED", default = true)
    new Top(width, enabled)
  }

  def emit(): String = {
    val directory = Files.createTempDirectory("morphhdl-inc48-")
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = "natural_symbolic_conditionals.v"
    MorphVerilog(config)(component())
    new String(
      Files.readAllBytes(directory.resolve("natural_symbolic_conditionals.v")),
      StandardCharsets.UTF_8
    )
  }
}

class NaturalSymbolicConditionalTests extends AnyFunSuite {
  import NaturalSymbolicConditionalSmoke._

  test("natural HdlBool and HdlInt predicates retain parameter-controlled alternatives") {
    val verilog = emit()
    val compact = verilog.replaceAll("\\s+", "")
    assert(verilog.contains("parameter integer ENABLED"))
    assert(verilog.contains("parameter integer WIDTH"))
    assert(compact.contains("(ENABLED==1)"))
    assert(compact.contains("(WIDTH)>(16)"))
    assert(compact.contains("(WIDTH)>(8)"))
    assert("(?m)^\\s*if\\s*\\(".r.findAllMatchIn(verilog).size >= 3)
  }

  test("ordinary Scala Boolean conditionals remain ordinary Scala control flow") {
    def ordinary(value: Boolean): Int = if (value) 7 else 3
    assert(ordinary(true) == 7)
    assert(ordinary(false) == 3)
  }
}
