package morphhdl.examples

import java.nio.file.Files
import scala.collection.JavaConverters._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.{IntExpr, RtlBinaryOperator, RtlExpr, ScopeId, Signedness}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals._

class NativeWireExpressionCodecTests extends AnyFunSuite {
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
}
