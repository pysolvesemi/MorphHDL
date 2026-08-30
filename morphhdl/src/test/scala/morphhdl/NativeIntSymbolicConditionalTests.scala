package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend.{formalComponent, HdlInt, NativeIntShadow}

object NativeIntSymbolicConditionalSmoke {
  final class Sink extends Component {
    setDefinitionName("NativeIntConditionalSink")
    val din = in(Bits(8 bits))
    val observed = out(Bool())
    observed := din.orR
  }

  abstract class ConditionalLeaf(width: Int, definitionName: String)
      extends Component {
    setDefinitionName(definitionName)

    val din = in(Bits(width bits))
    val dout = out(Bits(width bits))
    val control = in(Bits(8 bits))
    dout := din

    protected final def attach(): Unit = {
      val sink = new Sink
      sink.din := control
    }
  }

  final class Leaf(width: Int)
      extends ConditionalLeaf(width, "NativeIntConditionalLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    @dontName val medium = root > 8

    if (root > 16) attach()
    else if (medium) attach()
    else attach()
  }

  final class OrdinaryLeaf(width: Int, chooseInverted: Boolean)
      extends ConditionalLeaf(width, "NativeIntOrdinaryConditionalLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (chooseInverted) attach() else attach()
  }

  final class PowerOfTwoLeaf(width: Int)
      extends ConditionalLeaf(width, "NativeIntPowerOfTwoConditionalLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (isPow2(root)) attach() else attach()
  }

  final class NestedLeaf(width: Int)
      extends ConditionalLeaf(width, "NativeIntNestedConditionalLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 16) {
      if (root > 24) attach() else attach()
    } else attach()
  }

  final class UnsupportedPredicateLeaf(width: Int)
      extends ConditionalLeaf(width, "NativeIntUnsupportedPredicateLeaf") {
    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    @dontName val wide = root > 8
    if (!wide) attach() else attach()
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("NativeIntConditionalTop")

    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))
    val control = in(Bits(8 bits))

    val left = formalComponent(
      leftWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(
      value => new Leaf(value)
    )(
      value => Vector(value.din, value.dout)
    )
    left.setName("left")

    val right = formalComponent(
      rightWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(
      value => new Leaf(value)
    )(
      value => Vector(value.din, value.dout)
    )
    right.setName("right")

    left.din := leftIn.resized
    left.control := control
    leftOut := left.dout.resized
    right.din := rightIn.resized
    right.control := control
    rightOut := right.dout.resized
  }

  final class SingleLeafTop(width: HdlInt, mode: String) extends Component {
    setDefinitionName("NativeIntSingleConditionalTop")

    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    val control = in(Bits(8 bits))

    val leaf = mode match {
      case "ordinary" =>
        formalComponent(width, "WIDTH", BigInt(1), BigInt(32))(
          value => new OrdinaryLeaf(value, chooseInverted = true)
        )(value => Vector(value.din, value.dout))
      case "power" =>
        formalComponent(width, "WIDTH", BigInt(1), BigInt(32))(
          value => new PowerOfTwoLeaf(value)
        )(value => Vector(value.din, value.dout))
      case "nested" =>
        formalComponent(width, "WIDTH", BigInt(1), BigInt(32))(
          value => new NestedLeaf(value)
        )(value => Vector(value.din, value.dout))
      case "unsupported" =>
        formalComponent(width, "WIDTH", BigInt(1), BigInt(32))(
          value => new UnsupportedPredicateLeaf(value)
        )(value => Vector(value.din, value.dout))
      case other => throw new IllegalArgumentException(other)
    }

    leaf.din := din.resized
    leaf.control := control
    dout := leaf.dout.resized
  }
}

class NativeIntSymbolicConditionalTests extends AnyFunSuite {
  import NativeIntSymbolicConditionalSmoke._

  test("native Int else-if emits one source-ordered canonical child chain") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      val verilog = emitMorph(directory, "native_int_conditionals.v") {
        val left = HdlInt.param("LEFT_WIDTH", default = 12, min = 1, max = 32)
        val right = HdlInt.param("RIGHT_WIDTH", default = 12, min = 4, max = 24)
        top = new Top(left, right)
        top
      }

      val compact = verilog.replaceAll("\\s+", "")
      assert(verilog.contains("module NativeIntConditionalLeaf #("))
      assert(occurrences(verilog, "module NativeIntConditionalLeaf #(") == 1)
      assert(verilog.contains(".WIDTH(LEFT_WIDTH)"))
      assert(verilog.contains(".WIDTH(RIGHT_WIDTH)"))
      assert(compact.contains("if((WIDTH>16))begin") || compact.contains("if(((WIDTH)>(16)))begin"))
      assert(compact.contains("endelseif((WIDTH>8))begin") || compact.contains("endelseif(((WIDTH)>(8)))begin"))
      assert(!compact.contains("if((LEFT_WIDTH>16))"))
      assert(!compact.contains("if((RIGHT_WIDTH>16))"))
      assert(occurrences(compact, "WIDTH>16") + occurrences(compact, "(WIDTH)>(16)") == 1)
      assert(occurrences(compact, "WIDTH>8") + occurrences(compact, "(WIDTH)>(8)") == 1)
      assert(instanceCount(verilog, "NativeIntConditionalSink") == 3)

      val leftRecord = oneRecord(top.left)
      val rightRecord = oneRecord(top.right)
      assert(leftRecord.predicates.size == 2)
      assert(rightRecord.predicates.size == 2)
      assert(leftRecord.predicates.forall(_.definitionExpression.verilog.contains("WIDTH")))
      assert(leftRecord.predicates.forall(_.actualExpression.verilog.contains("LEFT_WIDTH")))
      assert(rightRecord.predicates.forall(_.actualExpression.verilog.contains("RIGHT_WIDTH")))
      assertOneDefinitionRoot(top.left, leftRecord)
      assertOneDefinitionRoot(top.right, rightRecord)
    }
  }

  test("power-of-two native predicate captures both source alternatives") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(directory, "native_int_pow2_conditional.v") {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        new SingleLeafTop(width, "power")
      }
      val compact = verilog.replaceAll("\\s+", "")
      assert(compact.contains("WIDTH>0"))
      assert(compact.contains("WIDTH-1"))
      assert(instanceCount(verilog, "NativeIntConditionalSink") == 2)
    }
  }

  test("ordinary Scala Boolean conditional remains concrete in an eligible source unit") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(directory, "native_int_ordinary_conditional.v") {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        new SingleLeafTop(width, "ordinary")
      }
      assert(!verilog.contains("g_if_NativeIntSymbolicConditionalTests"))
      assert(instanceCount(verilog, "NativeIntConditionalSink") == 1)
    }
  }

  test("ordinary SpinalVerilog keeps only the witness-selected native branch") {
    withTemporaryDirectory { directory =>
      val verilog = emitConcrete(directory, "native_int_conditionals_concrete.v") {
        new Top(HdlInt.literal(12), HdlInt.literal(12))
      }
      assert(!verilog.contains("parameter integer"))
      assert(!verilog.contains("generate"))
      assert(instanceCount(verilog, "NativeIntConditionalSink") == 1)
    }
  }

  test("nested native symbolic conditionals lower recursively after Increment 52") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(directory, "native_int_nested_conditional.v") {
        val width = HdlInt.param("WIDTH", default = 20, min = 1, max = 32)
        new SingleLeafTop(width, "nested")
      }
      val compact = verilog.replaceAll("\\s+", "")
      assert(compact.contains("WIDTH>16") || compact.contains("(WIDTH)>(16)"))
      assert(compact.contains("WIDTH>24") || compact.contains("(WIDTH)>(24)"))
      assert(instanceCount(verilog, "NativeIntConditionalSink") == 3)
    }
  }

  test("unsupported compound native predicate is rejected instead of collapsing") {
    val failure = failureFor("unsupported")
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-PREDICATE-UNSUPPORTED"))
  }

  test("native symbolic branch replay is deterministic") {
    withTemporaryDirectory { first =>
      withTemporaryDirectory { second =>
        val firstVerilog = emitMorph(first, "native_int_conditional_replay.v") {
          val left = HdlInt.param("LEFT_WIDTH", default = 12, min = 1, max = 32)
          val right = HdlInt.param("RIGHT_WIDTH", default = 12, min = 4, max = 24)
          new Top(left, right)
        }
        val secondVerilog = emitMorph(second, "native_int_conditional_replay.v") {
          val left = HdlInt.param("LEFT_WIDTH", default = 12, min = 1, max = 32)
          val right = HdlInt.param("RIGHT_WIDTH", default = 12, min = 4, max = 24)
          new Top(left, right)
        }
        assert(firstVerilog == secondVerilog)
      }
    }
  }

  private def failureFor(mode: String): String = withTemporaryDirectory { directory =>
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = s"native_int_conditional_failure_$mode.v"
    MorphVerilog.tryGenerate(config) {
      val width = HdlInt.param("WIDTH", default = 12, min = 1, max = 32)
      new SingleLeafTop(width, mode)
    } match {
      case Left(failure) => failure.detail
      case Right(report) => fail(s"Expected failure, received $report")
    }
  }

  private def oneRecord(component: Component): ExternalNativeIntComponentShadowRecord = {
    val records = ExternalNativeIntShadowRegistry.componentRecordsOf(component)
    assert(records.size == 1, records)
    records.head
  }

  private def assertOneDefinitionRoot(
      component: ConditionalLeaf,
      record: ExternalNativeIntComponentShadowRecord
  ): Unit = {
    val widthExpressions = Vector(component.din, component.dout).map { value =>
      ParameterizedWidth.expressionOf(value).getOrElse {
        fail(s"missing retained WIDTH expression on ${value.getName()}")
      }
    }
    val definitionExpressions =
      widthExpressions ++ record.slots.map(_.definitionExpression)
    val predicateExpressions = record.predicates.map(_.definitionExpression)
    val roots =
      definitionExpressions.flatMap(ExternalNativeIntCompletedRootTestProbe(_)) ++
        predicateExpressions.flatMap(ExternalNativeIntCompletedRootTestProbe(_))
    assert(roots.nonEmpty)
    assert(roots.forall(_ eq roots.head), roots)
    val parameters =
      definitionExpressions.flatMap(_.parameters) ++
        predicateExpressions.flatMap(_.parameters)
    assert(
      parameters.forall(_ eq record.binding.formal)
    )
  }

  private def emitMorph(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String
  )(component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    SpinalVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

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

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-native-int-conditional-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val entries = Files.walk(path)
      try {
        entries
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(value => Files.deleteIfExists(value))
      } finally entries.close()
    }
  }
}
