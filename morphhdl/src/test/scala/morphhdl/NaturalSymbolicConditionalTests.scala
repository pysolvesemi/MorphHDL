package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern

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

  final class NestedTop(width: HdlInt, enabled: HdlBool) extends Component {
    setDefinitionName("NaturalNestedSymbolicConditionalTop")
    val din = in(spinal.core.Bits(8 bits))
    val alive = out(Bool())
    alive := din.orR

    if (enabled) {
      if (width > 16) attach(din)
      else if (width > 8) attach(~din)
      else attach(din)
    } else {
      if (width > 4) attach(~din)
      else attach(din)
    }

    private def attach(value: spinal.core.Bits): Unit = {
      val branch = spinal.core.Bits(8 bits)
      branch := value
      val sink = new Sink
      sink.din := branch
    }
  }

  final class NamedNestedTop(width: HdlInt, enabled: HdlBool) extends Component {
    setDefinitionName("NaturalNamedNestedSymbolicConditionalTop")
    val din = in(spinal.core.Bits(8 bits))
    val alive = out(Bool())
    alive := din.orR

    if (enabled.named("g_feature_enabled", "g_feature_disabled")) {
      if ((width > 16).named("g_width_wide")) attach(din)
      else if ((width > 8).named("g_width_medium", "g_width_narrow")) attach(~din)
      else attach(din)
    } else {
      if ((width > 4).named("g_disabled_large", "g_disabled_small")) attach(~din)
      else attach(din)
    }

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

  def namedNestedComponent(): Component = {
    val width = HdlInt.param("WIDTH", default = 12, min = 1, max = 32)
    val enabled = HdlBool.param("ENABLED", default = true)
    new NamedNestedTop(width, enabled)
  }

  def nestedComponent(): Component = {
    val width = HdlInt.param("WIDTH", default = 12, min = 1, max = 32)
    val enabled = HdlBool.param("ENABLED", default = true)
    new NestedTop(width, enabled)
  }

  def emit(): String =
    emitComponent(
      "morphhdl-inc48-",
      "natural_symbolic_conditionals.v",
      component()
    )

  def emitNested(): String =
    emitComponent(
      "morphhdl-inc48-nested-",
      "natural_nested_symbolic_conditionals.v",
      nestedComponent()
    )

  def emitNamedNested(): String =
    emitComponent(
      "morphhdl-inc48-named-nested-",
      "natural_named_nested_symbolic_conditionals.v",
      namedNestedComponent()
    )

  private def emitComponent(
      prefix: String,
      filename: String,
      component: => Component
  ): String = {
    val directory = Files.createTempDirectory(prefix)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    new String(
      Files.readAllBytes(directory.resolve(filename)),
      StandardCharsets.UTF_8
    )
  }
}

class NaturalSymbolicConditionalTests extends AnyFunSuite {
  import NaturalSymbolicConditionalSmoke._

  test("natural else-if emits one source-ordered structural chain") {
    val verilog = emit()
    val compact = verilog.replaceAll("\\s+", "")
    assert(verilog.contains("parameter integer ENABLED"))
    assert(verilog.contains("parameter integer WIDTH"))
    assert(compact.contains("if((ENABLED==1))begin"))
    assert(compact.contains("if(((WIDTH)>(16)))begin"))
    assert(compact.contains("endelseif(((WIDTH)>(8)))begin"))
    assert(occurrences(compact, "(WIDTH)>(16)") == 1)
    assert(occurrences(compact, "(WIDTH)>(8)") == 1)
    assert(generateCount(verilog) == 2)
    assert(instanceCount(verilog, "NaturalConditionalSink") == 5)
  }

  test("nested explicit symbolic conditionals retain legal nested generate structure") {
    val verilog = emitNested()
    val compact = verilog.replaceAll("\\s+", "")
    assert(compact.contains("if((ENABLED==1))begin"))
    assert(compact.contains("if(((WIDTH)>(16)))begin"))
    assert(compact.contains("endelseif(((WIDTH)>(8)))begin"))
    assert(compact.contains("if(((WIDTH)>(4)))begin"))
    assert(occurrences(compact, "(ENABLED==1)") == 1)
    assert(occurrences(compact, "(WIDTH)>(16)") == 1)
    assert(occurrences(compact, "(WIDTH)>(8)") == 1)
    assert(occurrences(compact, "(WIDTH)>(4)") == 1)
    assert(generateCount(verilog) == 1)
    assert(instanceCount(verilog, "NaturalConditionalSink") == 5)
  }

  test("natural symbolic conditionals allow custom generate block labels") {
    val verilog = emitNamedNested()
    val compact = verilog.replaceAll("\\s+", "")
    Vector(
      "g_feature_enabled",
      "g_feature_disabled",
      "g_width_wide",
      "g_width_medium",
      "g_width_narrow",
      "g_disabled_large",
      "g_disabled_small"
    ).foreach { label =>
      assert(verilog.contains(s"begin : $label"), s"missing custom label $label")
    }
    assert(compact.contains("endelseif(((WIDTH)>(8)))begin:g_width_medium"))
    assert(compact.contains("endelsebegin:g_feature_disabled"))
    assert(!verilog.contains("morphhdl_else_if_"))
    assert(generateCount(verilog) == 1)
    assert(instanceCount(verilog, "NaturalConditionalSink") == 5)
  }

  test("non-final chained predicates reject a false-label override") {
    val width = HdlInt.param("WIDTH", default = 12, min = 1, max = 32)
    val error = intercept[FrontendException] {
      NaturalSymbolicConditional.selectSymbolicChain[Int](
        Seq(
          (
            (width > 16).named("g_wide", "g_not_wide"),
            () => 1,
            "NamedConditional.scala",
            10
          ),
          (
            (width > 8).named("g_medium", "g_narrow"),
            () => 2,
            "NamedConditional.scala",
            11
          )
        ),
        () => 3,
        "NamedConditional.scala",
        12
      )
    }
    assert(
      error.code ==
        "MORPH-FRONTEND-SYMBOLIC-CONDITIONAL-NONTERMINAL-FALSE-LABEL"
    )
  }

  test("ordinary Scala Boolean conditionals remain ordinary Scala control flow") {
    def ordinary(value: Boolean): Int = if (value) 7 else 3
    assert(ordinary(true) == 7)
    assert(ordinary(false) == 3)
  }

  private def generateCount(verilog: String): Int =
    "(?m)^\\s*generate\\s*$".r.findAllMatchIn(verilog).size

  private def instanceCount(verilog: String, moduleName: String): Int =
    ("(?m)^\\s+" + Pattern.quote(moduleName) + "\\b").r
      .findAllMatchIn(verilog)
      .size

  private def occurrences(value: String, needle: String): Int = {
    var count = 0
    var from = 0
    var found = value.indexOf(needle, from)
    while (found >= 0) {
      count += 1
      from = found + needle.length
      found = value.indexOf(needle, from)
    }
    count
  }
}
