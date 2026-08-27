package spinal.core

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog

private object StructuralAddressWidthIdentityFixture {
  sealed trait Mode
  case object ExactExcludingOne extends Mode
  case object ExactIncludingOne extends Mode
  case object ReorderedSuccessor extends Mode
  case object IntegerOverflowBoundary extends Mode

  final class Harness(mode: Mode) extends Component {
    setDefinitionName("StructuralAddressWidthIdentityHarness")

    private val (depthDefault, depthMinimum, depthMaximum) = mode match {
      case IntegerOverflowBoundary =>
        (
          BigInt(Int.MaxValue) - 1,
          BigInt(Int.MaxValue) - 1,
          BigInt(Int.MaxValue)
        )
      case _ => (BigInt(3), BigInt(1), BigInt(8))
    }
    private val depth = ElaborationIntegerParameter(
      name = "DEPTH",
      default = depthDefault,
      minimum = depthMinimum,
      maximum = depthMaximum
    )

    private val sourceWidth = ElaborationIntegerExpression(
      verilog =
        "(morphhdl_address_width(DEPTH) + ((((1'b1) && (((DEPTH > 0) && ((DEPTH & (DEPTH - 1)) == 0))))) ? 1 : 0))",
      default = if (mode == IntegerOverflowBoundary) BigInt(31) else BigInt(2),
      minimum = if (mode == IntegerOverflowBoundary) BigInt(31) else BigInt(2),
      maximum = if (mode == IntegerOverflowBoundary) BigInt(31) else BigInt(4),
      parameters = Vector(depth)
    )
    private val targetWidth = ElaborationIntegerExpression(
      verilog = mode match {
        case ReorderedSuccessor => "morphhdl_address_width((1 + DEPTH))"
        case _                  => "morphhdl_address_width((DEPTH + 1))"
      },
      default = if (mode == IntegerOverflowBoundary) BigInt(31) else BigInt(2),
      minimum = if (mode == IntegerOverflowBoundary) BigInt(31) else BigInt(1),
      maximum = if (mode == IntegerOverflowBoundary) BigInt(31) else BigInt(4),
      parameters = Vector(depth)
    )

    private def uint(width: ElaborationIntegerExpression): UInt =
      ParameterizedWidth.UInt(
        ParameterizedBitCount(
          value = width.default.toInt,
          parameter = None,
          expression = Some(width)
        )
      )

    val successor = in(uint(sourceWidth))
    val exact = in(uint(targetWidth))
    val observed = out(uint(targetWidth))

    private val whenTrue = ParameterizedStructure.captureBlock(
      this,
      Some("structural-address-width:true")
    ) {
      observed := successor
    }
    private val pending = ParameterizedStructure.beginPending(
      this,
      "structural-address-width",
      Some("structural-address-width")
    )
    private val whenFalse = ParameterizedStructure.captureBlock(
      this,
      Some("structural-address-width:false")
    ) {
      observed := exact
    }

    private val condition = ElaborationBooleanExpression(
      verilog = mode match {
        case ExactIncludingOne => "(DEPTH < 4)"
        case _                 => "(DEPTH > 1)"
      },
      default = true,
      parameters = Vector(depth)
    )
    private val root = new ParameterizedStructure.StructuralPredicateRoot(
      verilog = "DEPTH",
      default = depth.default,
      minimum = depth.minimum,
      maximum = depth.maximum,
      parameters = Vector(depth)
    )
    private val whenTrueValues = mode match {
      case ExactIncludingOne =>
        Vector(
          ParameterizedStructure.StructuralPredicateInterval(
            depth.minimum,
            BigInt(3).min(depth.maximum)
          )
        )
      case _ =>
        Vector(
          ParameterizedStructure.StructuralPredicateInterval(
            BigInt(2).max(depth.minimum),
            depth.maximum
          )
        )
    }
    private val predicateDomain =
      ParameterizedStructure.StructuralPredicateDomain(
        root = root,
        whenTrue = whenTrueValues
      )

    ParameterizedStructure.registerIf(
      pending = pending,
      condition = condition,
      whenTrueLabel = "g_successor",
      whenFalseLabel = "g_exact",
      whenTrue = whenTrue,
      whenFalse = whenFalse,
      sourceLocation = Some("structural-address-width"),
      predicateDomain = Some(predicateDomain)
    )
  }
}

final class StructuralAddressWidthIdentityTests extends AnyFunSuite {
  import StructuralAddressWidthIdentityFixture._

  test("address-width successor identity accepts the exact form outside one") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "structural_address_width_identity_positive.v"
      val report = MorphVerilog(config)(new Harness(ExactExcludingOne))
      assert(report.parameters.map(_.name) == Vector("DEPTH"))
      assert(
        Files.exists(
          directory.resolve("structural_address_width_identity_positive.v")
        )
      )
    }
  }

  test("address-width successor identity requires an active domain that excludes one") {
    val detail = failureDetail(ExactIncludingOne)
    assert(
      detail.contains("SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"),
      detail
    )
  }

  test("address-width successor identity rejects a reordered near-match") {
    val detail = failureDetail(ReorderedSuccessor)
    assert(
      detail.contains("SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"),
      detail
    )
  }

  test("address-width successor identity rejects signed integer overflow") {
    val detail = failureDetail(IntegerOverflowBoundary)
    assert(
      detail.contains("SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"),
      detail
    )
  }

  private def failureDetail(mode: Mode): String =
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "structural_address_width_identity.v"
      MorphVerilog.tryGenerate(config)(new Harness(mode)) match {
        case Left(failure) => failure.detail
        case Right(report) =>
          fail(s"Expected address-width identity rejection, received $report")
      }
    }

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-address-width-identity-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
}
