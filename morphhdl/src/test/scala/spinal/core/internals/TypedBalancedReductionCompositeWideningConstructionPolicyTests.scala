package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

private object ForbiddenWideningHostAllocation {
  def combine(a: BalancedCompositeWideningValue,
      b: BalancedCompositeWideningValue): BalancedCompositeWideningValue = {
    val host = new java.lang.StringBuilder()
    host.append("not hardware")
    CompositeWideningPublicationHelpers.combine(a, b)
  }
}

private final class ForbiddenWideningHostAllocation(width: HdlInt,
    count: HdlInt) extends Component {
  private val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedCompositeWideningValue(initial, initial, initial, initial), count))
  val reduced = values.reduceBalancedTree(
    (a: BalancedCompositeWideningValue, b: BalancedCompositeWideningValue) =>
      ForbiddenWideningHostAllocation.combine(a, b))
}

private object ForbiddenWideningRegistryRead {
  private def typedWidth(value: BaseType): ElabInt =
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))

  def combine(a: BalancedCompositeWideningValue,
      b: BalancedCompositeWideningValue): BalancedCompositeWideningValue = {
    val unsignedSum = a.unsignedSum +^ b.unsignedSum
    val result = BalancedCompositeWideningValue(typedWidth(unsignedSum),
      typedWidth(a.unsignedProduct), typedWidth(a.signedSum), typedWidth(a.signedProduct))
    result.unsignedSum := unsignedSum
    result.unsignedProduct := a.unsignedProduct
    result.signedSum := a.signedSum
    result.signedProduct := a.signedProduct
    result
  }
}

private final class ForbiddenWideningRegistryRead(width: HdlInt,
    count: HdlInt) extends Component {
  private val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedCompositeWideningValue(initial, initial, initial, initial), count))
  val reduced = values.reduceBalancedTree(
    (a: BalancedCompositeWideningValue, b: BalancedCompositeWideningValue) =>
      ForbiddenWideningRegistryRead.combine(a, b))
}

class TypedBalancedReductionCompositeWideningConstructionPolicyTests extends AnyFunSuite {
  private def rejection(component: => Component, fileName: String): Unit = {
    val directory = Files.createTempDirectory("widening-forbidden-")
    val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      bitVectorWidthMax = 65536)
    config.netlistFileName = fileName
    val failure = intercept[Exception] {
      MorphVerilog(config) {
        component
      }
    }
    val messages = scala.collection.mutable.ArrayBuffer.empty[String]
    var error: Throwable = failure
    while (error != null) {
      messages += Option(error.getMessage).getOrElse("")
      error = error.getCause
    }
    assert(messages.mkString("\n").contains("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED"))
    assert(!Files.exists(directory.resolve(fileName)))
  }

  test("audited Bundle construction does not admit arbitrary host allocation") {
    rejection(new ForbiddenWideningHostAllocation(HdlInt.param("WIDTH", 5, 1, 16),
      HdlInt.param("COUNT", 3, 1, 5)), "forbidden-allocation.v")
  }

  test("exact typed width query does not admit direct registry access") {
    rejection(new ForbiddenWideningRegistryRead(HdlInt.param("WIDTH", 5, 1, 16),
      HdlInt.param("COUNT", 3, 1, 5)), "forbidden-registry.v")
  }
}
