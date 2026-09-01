package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend.{formalComponent, HdlInt, NativeIntShadow, shadowInt}

object ExternalNativeIntShadowExpressionSmoke {
  private def addressWidth(value: Int): Int =
    math.max(1, (BigInt(value) - 1).bitLength)

  private def ceilLog2(value: Int): Int =
    (BigInt(value) - 1).bitLength

  private def log2Down(value: Int): Int =
    BigInt(value).bitLength - 1

  final class Leaf(width: Int) extends Component {
    setDefinitionName("ExternalNativeIntShadowExpressionLeaf")

    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    @dontName val selectedAlias = shadowInt(root, "selectedAlias")
    @dontName val alias = selectedAlias
    @dontName val plus = root + 2
    @dontName val minus = root - 3
    @dontName val times = root * 2
    @dontName val divide = root / 2
    @dontName val remainder = root % 3
    @dontName val minimum = math.min(root, 6)
    @dontName val maximum = math.max(root, 12)
    @dontName val negated = -root
    @dontName val address = addressWidth(root)
    @dontName val ceiling = ceilLog2(root)
    @dontName val up = log2Up(root)
    @dontName val down = log2Down(root)
    @dontName val compound = (root + 1) * 2
    @dontName val collectionReverse = Vector(root, root + 1).reverse.head

    @dontName val less = root < 12
    @dontName val lessEqual = root <= 8
    @dontName val greater = root > 4
    @dontName val greaterEqual = root >= 8
    @dontName val equal = root == 8
    @dontName val notEqual = root != 7
    @dontName val powerOfTwo = isPow2(root)

    val din = in(UInt(width bits))
    val dout = out(UInt(width bits))
    dout := din
  }

  final class Top(width: HdlInt) extends Component {
    setDefinitionName("ExternalNativeIntShadowExpressionTop")

    val din = in(morphhdl.frontend.UInt(width bits))
    val dout = out(morphhdl.frontend.UInt(width bits))
    val leaf = formalComponent(
      width,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(value => new Leaf(value))(value => Vector(value.din, value.dout))
    leaf.din := din
    dout := leaf.dout
  }

  final class FailureLeaf(width: Int, mode: String) extends Component {
    setDefinitionName("ExternalNativeIntShadowExpressionFailureLeaf")

    @dontName val root = NativeIntShadow.captureArgument(width, "root")

    mode match {
      case "zero-domain" =>
        @dontName val divisor = root - 8
        @dontName val broken = root / divisor
      case "overflow" =>
        @dontName val broken = root + 1
      case "unproven" =>
        @dontName val external = 3
        @dontName val broken = root + external
      case "unsupported" =>
        @dontName val broken = math.abs(root)
      case "unsupported-receiver" =>
        @dontName val broken = root.abs
      case "unsupported-derived-receiver" =>
        @dontName val broken = (root + 1).abs
      case "boxing" =>
        @dontName val broken = Option(root)
      case "mutable" =>
        @dontName var broken = root
      case other => throw new IllegalArgumentException(other)
    }

    val din = in(UInt(8 bits))
    val dout = out(UInt(8 bits))
    dout := din
  }

  final class FailureTop(
      width: HdlInt,
      minimum: BigInt,
      maximum: BigInt,
      mode: String
  ) extends Component {
    setDefinitionName("ExternalNativeIntShadowExpressionFailureTop")

    val din = in(UInt(8 bits))
    val dout = out(UInt(8 bits))
    val leaf = formalComponent(width, "WIDTH", minimum, maximum)(value => new FailureLeaf(value, mode))(value =>
      Vector(value.din, value.dout)
    )
    leaf.din := din
    dout := leaf.dout
  }
}

class ExternalNativeIntShadowExpressionTests extends AnyFunSuite {
  import ExternalNativeIntShadowExpressionSmoke._

  test("bounded native Int arithmetic retains witness, formal expression and parent actual") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      val verilog = emitMorph(directory, "native_int_expressions.v") {
        val width = HdlInt.param("TOP_WIDTH", default = 8, min = 2, max = 16)
        top = new Top(width)
        top
      }

      assert(verilog.contains("module ExternalNativeIntShadowExpressionLeaf #("))
      assert(verilog.contains(".WIDTH(TOP_WIDTH)"))

      val record = oneRecord(top.leaf)
      assertExpression(record, "root", 8, "WIDTH", "TOP_WIDTH", 1, 32, 2, 16)
      assertExpression(record, "selectedAlias", 8, "WIDTH", "TOP_WIDTH", 1, 32, 2, 16)
      assertExpression(record, "alias", 8, "WIDTH", "TOP_WIDTH", 1, 32, 2, 16)

      assertExpression(record, "plus", 10, "(WIDTH + 2)", "(TOP_WIDTH + 2)", 3, 34, 4, 18)
      assertExpression(record, "minus", 5, "(WIDTH - 3)", "(TOP_WIDTH - 3)", -2, 29, -1, 13)
      assertExpression(record, "times", 16, "(WIDTH * 2)", "(TOP_WIDTH * 2)", 2, 64, 4, 32)
      assertExpression(record, "divide", 4, "(WIDTH / 2)", "(TOP_WIDTH / 2)", 0, 16, 1, 8)
      assertExpression(record, "remainder", 2, "(WIDTH % 3)", "(TOP_WIDTH % 3)", 0, 2, 0, 2)
      assertExpression(
        record,
        "minimum",
        6,
        "((WIDTH) < (6) ? (WIDTH) : (6))",
        "((TOP_WIDTH) < (6) ? (TOP_WIDTH) : (6))",
        1,
        6,
        2,
        6
      )
      assertExpression(
        record,
        "maximum",
        12,
        "((WIDTH) > (12) ? (WIDTH) : (12))",
        "((TOP_WIDTH) > (12) ? (TOP_WIDTH) : (12))",
        12,
        32,
        12,
        16
      )
      assertExpression(record, "negated", -8, "(-WIDTH)", "(-TOP_WIDTH)", -32, -1, -16, -2)
      assertExpression(
        record,
        "address",
        3,
        "morphhdl_address_width(WIDTH)",
        "morphhdl_address_width(TOP_WIDTH)",
        1,
        5,
        1,
        4
      )
      assertExpression(
        record,
        "ceiling",
        3,
        "morphhdl_ceil_log2(WIDTH)",
        "morphhdl_ceil_log2(TOP_WIDTH)",
        0,
        5,
        1,
        4
      )
      assertExpression(
        record,
        "up",
        3,
        "morphhdl_ceil_log2(WIDTH)",
        "morphhdl_ceil_log2(TOP_WIDTH)",
        0,
        5,
        1,
        4
      )
      assertExpression(
        record,
        "down",
        3,
        "morphhdl_log2_down(WIDTH)",
        "morphhdl_log2_down(TOP_WIDTH)",
        0,
        5,
        1,
        4
      )
      assertExpression(
        record,
        "compound",
        18,
        "((WIDTH + 1) * 2)",
        "((TOP_WIDTH + 1) * 2)",
        4,
        66,
        6,
        34
      )

      assert(top.leaf.root == 8)
      assert(top.leaf.plus == 10)
      assert(top.leaf.compound == 18)
      assert(top.leaf.collectionReverse == 9)
    }
  }

  test("native Int comparisons and power-of-two predicate retain symbolic predicates") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      emitMorph(directory, "native_int_predicates.v") {
        val width = HdlInt.param("TOP_WIDTH", default = 8, min = 2, max = 16)
        top = new Top(width)
        top
      }

      val record = oneRecord(top.leaf)
      assertPredicate(record, "less", witness = true, "(WIDTH < 12)", "(TOP_WIDTH < 12)")
      assertPredicate(record, "lessEqual", witness = true, "(WIDTH <= 8)", "(TOP_WIDTH <= 8)")
      assertPredicate(record, "greater", witness = true, "(WIDTH > 4)", "(TOP_WIDTH > 4)")
      assertPredicate(record, "greaterEqual", witness = true, "(WIDTH >= 8)", "(TOP_WIDTH >= 8)")
      assertPredicate(record, "equal", witness = true, "(WIDTH == 8)", "(TOP_WIDTH == 8)")
      assertPredicate(record, "notEqual", witness = true, "(WIDTH != 7)", "(TOP_WIDTH != 7)")
      assertPredicate(
        record,
        "powerOfTwo",
        witness = true,
        "((WIDTH > 0) && ((WIDTH & (WIDTH - 1)) == 0))",
        "((TOP_WIDTH > 0) && ((TOP_WIDTH & (TOP_WIDTH - 1)) == 0))"
      )
    }
  }

  test("ordinary SpinalVerilog preserves concrete native Int execution") {
    withTemporaryDirectory { directory =>
      var top: Top = null
      val verilog = emitConcrete(directory, "native_int_expressions_concrete.v") {
        top = new Top(HdlInt.literal(8))
        top
      }

      assert(!verilog.contains("parameter integer"))
      assert(verilog.contains("[7:0]"))
      assert(top.leaf.plus == 10)
      assert(top.leaf.divide == 4)
      assert(top.leaf.powerOfTwo)
    }
  }

  test("division rejects a divisor whose complete symbolic domain admits zero") {
    val failure = failureFor(
      default = 9,
      minimum = 4,
      maximum = 12,
      mode = "zero-domain"
    )
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DIVISOR-ZERO-DOMAIN"))
  }

  test("bounded native Int arithmetic rejects a complete-domain overflow") {
    val failure = failureFor(
      default = BigInt(Int.MaxValue - 1),
      minimum = BigInt(Int.MaxValue - 1),
      maximum = BigInt(Int.MaxValue),
      mode = "overflow"
    )
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DOMAIN-OVERFLOW"))
  }

  test("an unproven nonliteral operand is rejected instead of value-matched") {
    val failure = failureFor(8, 2, 16, "unproven")
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN"))
  }

  test("unsupported native Int calls fail closed") {
    val failure = failureFor(8, 2, 16, "unsupported")
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))

    val receiver = failureFor(8, 2, 16, "unsupported-receiver")
    assert(receiver.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))

    val derivedReceiver = failureFor(8, 2, 16, "unsupported-derived-receiver")
    assert(derivedReceiver.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))
  }

  test("boxing and mutable escape are rejected explicitly") {
    val boxing = failureFor(8, 2, 16, "boxing")
    assert(boxing.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-BOXING-UNSUPPORTED"))

    val mutable = failureFor(8, 2, 16, "mutable")
    assert(mutable.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-MUTABLE-ESCAPE"))
  }

  test("one exact result reference cannot alias conflicting symbolic expressions") {
    val source = Some("ExternalNativeIntShadowExpressionTests.scala:conflict")
    val parameter = ElaborationIntegerParameter("WIDTH", 8, 1, 16)
    val expression = ElaborationIntegerExpression(
      "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16,
      parameters = Vector(parameter),
      sourceLocation = source
    )
    val failure = intercept[ParameterizedVerilogException] {
      ExternalNativeIntFormalizationTestAccess.withDefinitionExpressionBoundary(
        expression,
        source.get,
        role = "conflict-proof"
      ) {
        ExternalNativeIntShadowRegistry.captureArgumentTracked(
          8,
          "root",
          "root-ref",
          source.get
        )
        ExternalNativeIntShadowRegistry.aliasTracked(
          8,
          "first",
          "root-ref",
          "same-result",
          source.get
        )
        ExternalNativeIntShadowRegistry.binaryTracked(
          "+",
          8,
          "root-ref",
          leftLiteral = false,
          1,
          "",
          rightLiteral = true,
          "plus-ref",
          "plus",
          source.get
        )
        ExternalNativeIntShadowRegistry.aliasTracked(
          9,
          "second",
          "plus-ref",
          "same-result",
          source.get
        )
      }
    }
    assert(failure.getMessage.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-ALIAS-CONFLICT"))
  }

  test("expression and predicate replay is deterministic across elaborations") {
    withTemporaryDirectory { first =>
      withTemporaryDirectory { second =>
        assert(signature(first) == signature(second))
      }
    }
  }

  private def failureFor(
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      mode: String
  ): String = withTemporaryDirectory { directory =>
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = s"native_int_expression_failure_$mode.v"
    MorphVerilog.tryGenerate(config) {
      val width = HdlInt.param("WIDTH", default, minimum, maximum)
      new FailureTop(width, minimum, maximum, mode)
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

  private def slot(
      record: ExternalNativeIntComponentShadowRecord,
      name: String
  ): ExternalNativeIntShadowSlot =
    record.slots.find(_.token.name == name).getOrElse {
      fail(s"Missing slot '$name' in ${record.slots.map(_.token.name)}")
    }

  private def assertExpression(
      record: ExternalNativeIntComponentShadowRecord,
      name: String,
      witness: Int,
      definition: String,
      actual: String,
      definitionMinimum: BigInt,
      definitionMaximum: BigInt,
      actualMinimum: BigInt,
      actualMaximum: BigInt
  ): Unit = {
    val value = slot(record, name)
    assert(value.witness == witness)
    assert(value.definitionExpression.verilog == definition)
    assert(value.actualExpression.verilog == actual)
    assert(value.definitionExpression.minimum == definitionMinimum)
    assert(value.definitionExpression.maximum == definitionMaximum)
    assert(value.actualExpression.minimum == actualMinimum)
    assert(value.actualExpression.maximum == actualMaximum)
    val root = slot(record, "root")
    assertSameRoots(value.definitionExpression, root.definitionExpression)
    assertSameRoots(value.actualExpression, root.actualExpression)
  }

  private def assertPredicate(
      record: ExternalNativeIntComponentShadowRecord,
      name: String,
      witness: Boolean,
      definition: String,
      actual: String
  ): Unit = {
    val value = record.predicates.find(_.token.name == name).getOrElse {
      fail(s"Missing predicate '$name' in ${record.predicates.map(_.token.name)}")
    }
    assert(value.witness == witness)
    assert(value.definitionExpression.verilog == definition)
    assert(value.actualExpression.verilog == actual)
    val root = slot(record, "root")
    assertSameRoots(value.definitionExpression, root.definitionExpression)
    assertSameRoots(value.actualExpression, root.actualExpression)
  }

  private def assertSameRoots(
      value: ElaborationIntegerExpression,
      root: ElaborationIntegerExpression
  ): Unit = {
    assert(value.parameterRoots.nonEmpty)
    assert(value.parameterRoots.size == root.parameterRoots.size)
    assert(
      value.parameterRoots.forall(candidate => root.parameterRoots.exists(_ eq candidate))
    )
  }

  private def assertSameRoots(
      value: ElaborationBooleanExpression,
      root: ElaborationIntegerExpression
  ): Unit = {
    assert(value.parameterRoots.nonEmpty)
    assert(value.parameterRoots.size == root.parameterRoots.size)
    assert(
      value.parameterRoots.forall(candidate => root.parameterRoots.exists(_ eq candidate))
    )
  }

  private def signature(directory: Path): Vector[String] = {
    var top: Top = null
    emitMorph(directory, "native_int_expression_replay.v") {
      val width = HdlInt.param("TOP_WIDTH", default = 8, min = 2, max = 16)
      top = new Top(width)
      top
    }
    val record = oneRecord(top.leaf)
    val slots = record.slots.map { value =>
      List(
        "slot",
        value.token.kind.label,
        value.token.name,
        value.witness.toString,
        value.definitionExpression.verilog,
        value.definitionExpression.minimum.toString,
        value.definitionExpression.maximum.toString,
        value.actualExpression.verilog,
        value.actualExpression.minimum.toString,
        value.actualExpression.maximum.toString
      ).mkString("|")
    }
    val predicates = record.predicates.map { value =>
      List(
        "predicate",
        value.token.name,
        value.witness.toString,
        value.definitionExpression.verilog,
        value.actualExpression.verilog
      ).mkString("|")
    }
    slots ++ predicates
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

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-native-int-expressions-")
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
