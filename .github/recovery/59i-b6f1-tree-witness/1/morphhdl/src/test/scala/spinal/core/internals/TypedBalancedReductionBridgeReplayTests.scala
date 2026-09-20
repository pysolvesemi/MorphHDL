package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

/** Guard mutations are made on the captured native graph before normalization. */
class TypedBalancedReductionBridgeReplayTests extends AnyFunSuite {
  private def native[T <: BaseType]: ElabBalancedReduction.Native[T] =
    (values, op, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(op, bridge)

  private def withUInt(body: Vec[UInt] => Unit): Unit = {
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-bridge-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val words = Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 5))
      words.vec.foreach(_ := 0)
      body(words)
    })
  }

  private def capture(words: Vec[UInt], bridge: (UInt, Int) => UInt) =
    TypedBalancedReductionStageReplay.capture(words, (a: UInt, b: UInt) => a + b, bridge, native[UInt])

  private def rejects(code: String)(body: => Any): Unit = {
    val failure = intercept[IllegalArgumentException](body)
    assert(failure.getMessage.contains(code), failure.getMessage)
  }

  test("local native enables and nonzero aliases replay every pair and odd tail") {
    withUInt { words =>
      val certificate = capture(words, (value: UInt, level: Int) => {
        val alias = UInt(); alias := value
        if (level == 0) RegNextWhen(alias, !alias.msb && alias(0)) init U(3)
        else RegNext(RegNextWhen(alias, alias.msb) init U(2)) init U(1)
      })
      assert(certificate.hasLocalEnables)
      assert(certificate.stages.map(_.registerCountPerRow) == Vector(1, 2, 2))
      assert(certificate.latencyFor(1) == 0)
      assert(certificate.latencyFor(5) == 5)
      assert(certificate.replay(Vector(words.vec.head)) eq words.vec.head)
      for (count <- 2 to 5) assert(certificate.replay(words.vec.take(count).toVector).isReg)
    }
  }

  test("native enable condition identity and its expression children stay frozen") {
    withUInt { words =>
      val certificate = capture(words, (value: UInt, _: Int) => RegNextWhen(value, value(0)) init U(3))
      val bridge = certificate.captured.rows.head.bridge
      val statement = bridge.statements.collectFirst { case value: WhenStatement => value }.get
      val saved = statement.cond
      statement.cond = new BoolLiteral(false)
      rejects("GRAPH-") { certificate.requireFreshness() }
      statement.cond = saved
      val access = bridge.assignments.collectFirst {
        case assignment if assignment.source.isInstanceOf[UIntBitAccessFixed] =>
          assignment.source.asInstanceOf[UIntBitAccessFixed]
      }.get
      access.bitId = 1
      rejects("GRAPH-CHANGED") { certificate.requireFreshness() }
      access.bitId = 0
      certificate.requireFreshness()
    }
  }

  test("register clock replacement including polarity cannot reuse native evidence") {
    withUInt { words =>
      val certificate = capture(words, (value: UInt, _: Int) => RegNext(value) init U(3))
      val register = certificate.captured.rows.head.bridge.declarations.find(_.isReg).get
      val saved = register.clockDomain
      register.clockDomain = saved.copy(config = saved.config.copy(resetActiveLevel = LOW))
      rejects("GRAPH-CHANGED") { certificate.requireFreshness() }
      val replacementClock = Bool(); replacementClock := False
      register.clockDomain = saved.copy(clock = replacementClock)
      rejects("GRAPH-CHANGED") { certificate.requireFreshness() }
      register.clockDomain = saved
      certificate.requireFreshness()
    }
  }

  test("initializer value mutations invalidate the exact native certificate") {
    withUInt { words =>
      val certificate = capture(words, (value: UInt, _: Int) => RegNext(value) init U(3))
      val literal = certificate.captured.rows.head.bridge.assignments.collectFirst {
        case assignment if assignment.source.isInstanceOf[UIntLiteral] =>
          assignment.source.asInstanceOf[UIntLiteral]
      }.get
      literal.value = 2
      rejects("GRAPH-CHANGED") { certificate.requireFreshness() }
      literal.value = 3
      certificate.requireFreshness()
    }
  }

  test("partial register updates and false-arm update policies are rejected") {
    withUInt { words =>
      rejects("GRAPH-") {
        capture(words, (value: UInt, _: Int) => {
          val result = Reg(UInt(5 bits)) init U(3)
          val low = value(0)
          when(value.msb) { result(0) := low }
          result
        })
      }
      rejects("GRAPH-ASSIGNMENT-SHAPE") {
        capture(words, (value: UInt, _: Int) => {
          val result = Reg(UInt(5 bits)) init U(3)
          val alternate = U(2)
          when(value.msb) { result := value } otherwise { result := alternate }
          result
        })
      }
    }
  }

  test("external enables and unrelated local effects cannot acquire bridge authority") {
    withUInt { words =>
      val external = Bool(); external := False
      rejects("GRAPH-EXTERNAL-READ") {
        capture(words, (value: UInt, _: Int) => RegNextWhen(value, external) init U(3))
      }
      rejects("GRAPH-UNREACHABLE") {
        capture(words, (value: UInt, _: Int) => {
          val unused = !value.msb
          RegNextWhen(value, value(0)) init U(3)
        })
      }
    }
  }

  test("unqualified soft-reset and boot initialization domains reject explicitly") {
    withUInt { words =>
      val soft = Bool(); soft := False
      for (domain <- Vector(ClockDomain.current.copy(softReset = soft),
          ClockDomain.current.copy(reset = null, config = ClockDomain.current.config.copy(resetKind = BOOT)))) {
        rejects("BRIDGE-CLOCK") {
          val context = domain.push()
          try capture(words, (value: UInt, _: Int) => RegNext(value) init U(3))
          finally context.restore()
        }
      }
    }
  }

  test("signed nonzero initializers retain sign and reject a too-small symbolic domain") {
    val width = HdlInt.param("WIDTH", 5, 2, 8)
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-bridge-signed-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      def count = HdlInt.param("COUNT", 1, 1, 3)
      val words = Vec(SInt(5 bits), count); words.vec.foreach(_ := 0)
      val certificate = TypedBalancedReductionStageReplay.capture(words,
        (a: SInt, b: SInt) => a + b,
        (value: SInt, _: Int) => RegNextWhen(value, value.msb) init S(-3), native[SInt])
      val replayed = certificate.replay(words.vec.toVector)
      val initializers = scala.collection.mutable.ArrayBuffer.empty[InitAssignmentStatement]
      dslBody.walkStatements {
        case value: InitAssignmentStatement if value.finalTarget eq replayed => initializers += value
        case _ =>
      }
      assert(initializers.size == 1)
      assert(initializers.head.source.asInstanceOf[SIntLiteral].value == -3)
      val symbolic = Vec(SInt(width bits), count)
      symbolic.vec.foreach(_ := 0)
      rejects("BRIDGE-INITIALIZER-WIDTH") {
        TypedBalancedReductionStageReplay.capture(symbolic, (a: SInt, b: SInt) => a + b,
          (value: SInt, _: Int) => RegNext(value) init S(-3), native[SInt])
      }
    })
  }

  test("retained initializer publication extends signed values and preserves exact native write lineage") {
    val directory = Files.createTempDirectory("balanced-bridge-init-lineage-")
    val width = HdlInt.param("WIDTH", 5, 3, 32)
    val report = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      setDefinitionName("NativeInitializerLineage")
      val data = in(SInt(width bits))
      val clear = in(Bool())
      val negative = out(Reg(SInt(width bits))) init S(-3)
      val positive = out(Reg(UInt(width bits))) init U(3)
      val zero = out(Reg(UInt(width bits))) init U(0)
      val fixed = out(Reg(SInt(5 bits))) init S(-3)
      negative := data
      positive := data.asUInt
      zero := data.asUInt
      fixed := data.resize(5)
      when(clear) { negative := S(-3) }
    })
    val nativeRtl = new String(Files.readAllBytes(directory.resolve("NativeInitializerLineage.v")),
      StandardCharsets.UTF_8)
    val rewritten = ExternalParameterizedVerilogNativeFallback.rewriteRetainedConstantInitializers(
      report.toplevel, nativeRtl)
    assert(rewritten.linesIterator.count(_.contains("negative <= {{(WIDTH - 3){1'b1}}, 3'b101};")) == 2,
      rewritten)
    assert(rewritten.contains("positive <= {{(WIDTH - 2){1'b0}}, 2'b11};"), rewritten)
    assert(rewritten.contains("zero <= {WIDTH{1'b0}};"), rewritten)
    assert(rewritten.contains("fixed <= 5'h1d;"), rewritten)
    for (broken <- Vector(nativeRtl.replace("negative <= 5'h1d;", "negative <= 5'h1c;"),
        nativeRtl + "\n  negative <= 5'h1d;\n")) {
      val failure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedVerilogNativeFallback.rewriteRetainedConstantInitializers(
          report.toplevel, broken)
      }
      assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-CONSTANT-INIT-EMITTED-LINEAGE-MISMATCH",
        failure.getMessage)
    }
  }

  test("Bits and Bool native enable graphs retain their own scalar type and initializer") {
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-bridge-types-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      def count = HdlInt.param("COUNT", 1, 1, 3)
      val bits = Vec(Bits(5 bits), count); bits.vec.foreach(_ := 0)
      val flags = Vec(Bool(), count); flags.vec.foreach(_ := False)
      val packed = TypedBalancedReductionStageReplay.capture(bits,
        (a: Bits, b: Bits) => a ^ b,
        (value: Bits, _: Int) => RegNextWhen(value, value(0) || !value.msb) init B(21), native[Bits])
      val boolean = TypedBalancedReductionStageReplay.capture(flags,
        (a: Bool, b: Bool) => a ^ b,
        (value: Bool, _: Int) => RegNextWhen(value, !value) init True, native[Bool])
      assert(packed.hasLocalEnables && boolean.hasLocalEnables)
      assert(packed.replay(bits.vec.toVector).getTypeObject == TypeBits)
      assert(boolean.replay(flags.vec.toVector).getTypeObject == TypeBool)
      packed.requireFreshness()
      boolean.requireFreshness()
    })
  }

  test("legacy direct zero resets remain compatible without authorizing nonzero initialization") {
    val directory = Files.createTempDirectory("balanced-bridge-legacy-zero-")
    val parameter = ElaborationIntegerParameter("WIDTH", 5, 1, 32)
    val bitCount = ParameterizedBitCount(5, parameter)
    val report = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      setDefinitionName("LegacyZeroInitializerLineage")
      val data = in(ParameterizedWidth.Bits(bitCount))
      val zero = out(ParameterizedWidth.Reg(ParameterizedWidth.Bits(bitCount))) init B(0, 5 bits)
      zero := data
    })
    val rtl = new String(Files.readAllBytes(directory.resolve("LegacyZeroInitializerLineage.v")),
      StandardCharsets.UTF_8)
    val rewritten = ExternalParameterizedVerilogNativeFallback.rewriteRetainedConstantInitializers(
      report.toplevel, rtl)
    assert(rewritten.contains("zero <= {WIDTH{1'b0}};"), rewritten)
    val initializers = scala.collection.mutable.ArrayBuffer.empty[InitAssignmentStatement]
    report.toplevel.dslBody.walkLeafStatements {
      case statement: InitAssignmentStatement => initializers += statement
      case _ =>
    }
    assert(initializers.size == 1)
    val initializer = initializers.head
    val literal = initializer.source.asInstanceOf[BitsLiteral]
    literal.value = 1
    try {
      val failure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedVerilogNativeFallback.rewriteRetainedConstantInitializers(report.toplevel, rtl)
      }
      assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-NATIVE-WIDTH-OWNER-EVIDENCE-MISSING",
        failure.getMessage)
    } finally literal.value = 0
    assert(ExternalParameterizedVerilogNativeFallback.rewriteRetainedConstantInitializers(
      report.toplevel, rtl) == rewritten)
    val missing = rtl.replace("zero <= 5'h0;", "zero <= 5'h1;")
    assert(missing != rtl, rtl)
    val lineage = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback.rewriteRetainedConstantInitializers(report.toplevel, missing)
    }
    assert(lineage.code == "SPINAL-PARAMETERIZED-VERILOG-CONSTANT-INIT-EMITTED-LINEAGE-MISMATCH",
      lineage.getMessage)
  }

  test("legacy zero compatibility rejects copied widths foreign roots and erased typed evidence") {
    val directory = Files.createTempDirectory("balanced-bridge-legacy-authority-")
    val legacy = ElaborationIntegerParameter("LEGACY_WIDTH", 5, 1, 32)
    val bitCount = ParameterizedBitCount(5, legacy)
    val report = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val data = in(ParameterizedWidth.Bits(bitCount))
      val zero = out(ParameterizedWidth.Reg(ParameterizedWidth.Bits(bitCount))) init B(0, 5 bits)
      val typedData = in(UInt(HdlInt.param("TYPED_WIDTH", 5, 1, 32) bits))
      val typedZero = out(Reg(cloneOf(typedData))) init U(0, 5 bits)
      zero := data
      typedZero := typedData
    })
    val initializers = scala.collection.mutable.ArrayBuffer.empty[InitAssignmentStatement]
    report.toplevel.dslBody.walkLeafStatements {
      case statement: InitAssignmentStatement => initializers += statement
      case _ =>
    }
    assert(initializers.size == 2)
    val legacyInit = initializers.find(_.source.isInstanceOf[BitsLiteral]).get
    val target = legacyInit.finalTarget.asInstanceOf[BitVector]
    val literal = legacyInit.source.asInstanceOf[BitVectorLiteral]
    val width = ParameterizedWidth.expressionOf(target).get
    def accepts(candidate: ElaborationIntegerExpression): Boolean =
      ExternalParameterizedVerilogNativeFallback.isLegacyDirectZeroInitializer(target, candidate, literal)
    assert(accepts(width))
    assert(!accepts(width.copy()))
    assert(!accepts(width.copy(parameters = Vector(legacy.copy()))))
    assert(!accepts(width.copy(parameterRoots = Vector(ElaborationIntegerParameterRoot.fresh(legacy.name)))))
    assert(!accepts(width.copy(verilog = "(LEGACY_WIDTH + 0)")))
    assert(!accepts(width.copy(generateIndex = Some("i"))))
    val typedInit = initializers.find(_.source.isInstanceOf[UIntLiteral]).get
    val typedTarget = typedInit.finalTarget.asInstanceOf[BitVector]
    val typedLiteral = typedInit.source.asInstanceOf[BitVectorLiteral]
    val typedWidth = ParameterizedWidth.expressionOf(typedTarget).get
    assert(typedWidth.exactDomain.nonEmpty)
    assert(!ExternalParameterizedVerilogNativeFallback.isLegacyDirectZeroInitializer(
      typedTarget, typedWidth, typedLiteral))
    val erased = typedWidth.copy(exactDomain = None)
    assert(!ExternalParameterizedVerilogNativeFallback.isLegacyDirectZeroInitializer(
      typedTarget, erased, typedLiteral))
    val failure = intercept[ParameterizedVerilogException] {
      NativePublicationWidth.validate(erased, report.toplevel, typedTarget, "erased typed zero initializer")
    }
    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-NATIVE-WIDTH-OWNER-EVIDENCE-MISSING",
      failure.getMessage)
  }
}
