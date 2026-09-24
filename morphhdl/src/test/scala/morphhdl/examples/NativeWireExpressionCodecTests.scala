package morphhdl.examples

import java.nio.file.Files
import scala.collection.JavaConverters._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.{IntExpr, RtlBinaryOperator, RtlExpr, RtlUnaryOperator, ScopeId, Signedness}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals._

class NativeWireExpressionCodecTests extends AnyFunSuite {
  private final case class EnumObservation(
      definition: SpinalEnum,
      encoding: SpinalEnumEncoding,
      source: SpinalEnumCraft[_],
      peer: SpinalEnumCraft[_],
      equalLiteral: Operator.Enum.Equal,
      notEqualLiteral: Operator.Enum.NotEqual,
      equalPeer: Operator.Enum.Equal
  )

  private def withResolvedEnumObservation(
      encoding: SpinalEnumEncoding
  )(check: EnumObservation => Unit): Unit = {
    val directory = Files.createTempDirectory("native-wire-enum-codec-")
    var observation: EnumObservation = null
    val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
    config.phasesInserters += { phases =>
      val inference = phases.indexWhere(_.isInstanceOf[PhaseInferEnumEncodings])
      require(inference >= 0)
      phases.insert(inference + 1, new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = check(observation)
      })
    }
    try SpinalVerilog(config) {
      new Component {
        setDefinitionName("NativeWireEnumCodecFixture")
        val enumDefinition = new SpinalEnum(encoding)
        val idle = enumDefinition.newElement("IDLE")
        enumDefinition.newElement("ACTIVE")
        val source = in(enumDefinition())
        val peer = in(enumDefinition())
        val equalLiteral = source === idle
        val notEqualLiteral = source =/= idle
        val equalPeer = source === peer
        def operator[T <: Expression](value: Bool): T = {
          var result: Expression = null
          value.foreachStatements {
            case assignment: DataAssignmentStatement => result = assignment.source
            case _ =>
          }
          require(result != null)
          result.asInstanceOf[T]
        }
        observation = EnumObservation(
          enumDefinition,
          encoding,
          source,
          peer,
          operator[Operator.Enum.Equal](equalLiteral),
          operator[Operator.Enum.NotEqual](notEqualLiteral),
          operator[Operator.Enum.Equal](equalPeer)
        )
        val result = out Bits(3 bits)
        result(0) := equalLiteral
        result(1) := notEqualLiteral
        result(2) := equalPeer
      }
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
  private def inNativeContext(check: => Unit): Unit = {
    val directory = Files.createTempDirectory("native-wire-expression-codec-")
    try {
      val width = HdlInt.param("WIDTH", default = 2, min = 1, max = 4)
      MorphVerilog(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)) {
        new Component {
          setDefinitionName("NativeWireExpressionCodecFixture")
          val source = in Bits(width bits)
          val result = out Bits(width bits)
          result := source
          check
        }
      }
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def codec = new NativeWireExpressionCodec(ScopeId.unsafe("scope.codec"), "codec-test")

  test("an exact direct receiver supplies its fence with the historical native emitter") {
    val directory = Files.createTempDirectory("native-expression-legacy-emitter-")
    try {
      val phase = new NamedWireExpressionNativePhase
      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
      NamedWireExpressionWitnessPhasePlan.install(config, Some(phase))
      val report = SpinalVerilog(config) {
        new Component {
          setDefinitionName("DirectExpressionLegacyEmitter")
          val a, b = in Bits(8 bits)
          val result = out Bits(8 bits)
          val carrier = Bits(8 bits)
          a.setName("a")
          b.setName("b")
          result.setName("result")
          carrier.setName("carrier")
          carrier := a ^ b
          result := carrier
        }
      }
      val generated = new String(Files.readAllBytes(java.nio.file.Paths.get(
        report.generatedSourcesPaths.head)), java.nio.charset.StandardCharsets.UTF_8)
      assert(phase.report.eliminatedCount == 1, phase.report)
      assert(generated.contains("assign result = (a ^ b);"), generated)
      assert(!generated.contains("carrier"), generated)
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  test("fixed resize and subtraction preserve authoritative native widths in capture") {
    inNativeContext {
      val extension = new ResizeUInt
      extension.input = UIntLiteral(255, 8)
      extension.size = 18
      assert(codec.capture(extension).contains(RtlExpr.Resize(
        RtlExpr.Literal(BigInt(255), 8), IntExpr.Literal(BigInt(18)), Signedness.Unsigned)))
      val subtraction = new Operator.UInt.Sub
      subtraction.left = UIntLiteral(0, 13)
      subtraction.right = UIntLiteral(1, 13)
      assert(codec.capture(subtraction).contains(RtlExpr.Resize(
        RtlExpr.Binary(RtlBinaryOperator.Subtract,
          RtlExpr.Literal(BigInt(0), 13), RtlExpr.Literal(BigInt(1), 13)),
        IntExpr.Literal(BigInt(13)), Signedness.Unsigned)))
    }
  }

  test("arithmetic right shift and case equality are not mislabeled as logical operators") {
    inNativeContext {
      val arithmetic = new Operator.SInt.ShiftRightByInt(1)
      arithmetic.source = SIntLiteral(-1, 8)
      assert(codec.capture(arithmetic).isEmpty)
      val dynamic = new Operator.SInt.ShiftRightByUInt
      dynamic.left = SIntLiteral(-1, 8)
      dynamic.right = UIntLiteral(1, 3)
      assert(codec.capture(dynamic).isEmpty)
      val nested = new Operator.SInt.Smaller
      nested.left = arithmetic
      nested.right = SIntLiteral(0, 7)
      assert(codec.capture(nested).isEmpty)
      val equality = new Operator.UInt.EqualSim
      equality.left = UIntLiteral(0, 8)
      equality.right = UIntLiteral(0, 8)
      assert(codec.capture(equality).isEmpty)
      val logical = new Operator.UInt.ShiftRightByInt(1)
      logical.source = UIntLiteral(255, 8)
      assert(codec.capture(logical).nonEmpty)
    }
  }

  test("binary and explicit enum comparisons project exact encoded literals") {
    for ((encoding, code, width) <- Vector[(SpinalEnumEncoding, BigInt, Int)](
        (binarySequential, BigInt(0), 1),
        (SpinalEnumEncoding("codecCustom", index => if (index == 0) 1 else 5), BigInt(1), 3)
      )) {
      withResolvedEnumObservation(encoding) { observation =>
        val valueCodec = codec
        val equal = valueCodec.capture(observation.equalLiteral).get
        val notEqual = valueCodec.capture(observation.notEqualLiteral).get
        equal match {
          case RtlExpr.Binary(RtlBinaryOperator.Equal, _: RtlExpr.Ref,
              RtlExpr.Literal(value, literalWidth, _)) =>
            assert(value == code && literalWidth == width)
          case other => fail(s"unexpected enum equality projection: $other")
        }
        notEqual match {
          case RtlExpr.Binary(RtlBinaryOperator.NotEqual, _: RtlExpr.Ref,
              RtlExpr.Literal(value, literalWidth, _)) =>
            assert(value == code && literalWidth == width)
          case other => fail(s"unexpected enum inequality projection: $other")
        }
        assert(valueCodec.capturedEnumAuthorities.size == 1)
        val authority = valueCodec.capturedEnumAuthorities.head
        assert(authority.definition eq observation.definition)
        assert(authority.encoding eq encoding)
        assert(authority.width == width)
      }
    }
  }

  test("one-hot literal and peer observations preserve native invalid-state semantics") {
    withResolvedEnumObservation(binaryOneHot) { observation =>
      val valueCodec = codec
      valueCodec.capture(observation.equalLiteral).get match {
        case RtlExpr.BitSelect(_: RtlExpr.Ref, RtlExpr.Literal(bit, width, _)) =>
          assert(bit == 0 && width == 1)
        case other => fail(s"unexpected one-hot equality projection: $other")
      }
      valueCodec.capture(observation.notEqualLiteral).get match {
        case RtlExpr.Unary(RtlUnaryOperator.LogicalNot,
            RtlExpr.BitSelect(_: RtlExpr.Ref, RtlExpr.Literal(bit, width, _))) =>
          assert(bit == 0 && width == 1)
        case other => fail(s"unexpected one-hot inequality projection: $other")
      }
      valueCodec.capture(observation.equalPeer).get match {
        case RtlExpr.Binary(RtlBinaryOperator.NotEqual,
            RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd,
              _: RtlExpr.Ref, _: RtlExpr.Ref),
            RtlExpr.Literal(zero, width, _)) =>
          assert(zero == 0 && width == 2)
        case other => fail(s"unexpected one-hot peer projection: $other")
      }
    }
  }

  test("canonical side authority distinguishes enum definitions with overlapping codes") {
    val first = SpinalEnumEncoding("codecOverlapA", index => if (index == 0) 1 else 5)
    val second = SpinalEnumEncoding("codecOverlapB", index => if (index == 0) 1 else 5)
    var firstObservation: EnumObservation = null
    withResolvedEnumObservation(first) { observation => firstObservation = observation }
    withResolvedEnumObservation(second) { observation =>
      val valueCodec = codec
      assert(valueCodec.capture(firstObservation.equalLiteral).nonEmpty)
      assert(valueCodec.capture(observation.equalLiteral).nonEmpty)
      val authorities = valueCodec.capturedEnumAuthorities
      assert(authorities.size == 2)
      assert(authorities.exists(_.definition eq firstObservation.definition))
      assert(authorities.exists(_.definition eq observation.definition))
      assert(!authorities.head.exactlyMatches(authorities.last))
    }
  }
}
