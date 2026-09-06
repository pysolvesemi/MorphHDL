package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import java.nio.file.Files
import scala.collection.JavaConverters._

class TypedBalancedReductionCompositeCallbackPolicyTests extends AnyFunSuite {
  private def reject(callback: AnyRef): Unit = {
    val before = BalancedCompositePolicyState.calls
    val error = intercept[IllegalArgumentException] {
      TypedBalancedReductionCallbackPolicy.requireSupportedOperator(callback)
    }
    assert(error.getMessage.contains("CALLBACK-UNSUPPORTED"), error.getMessage)
    assert(BalancedCompositePolicyState.calls == before, "host bytecode ran before rejection")
  }

  test("composite clone assignment and native record Mux have inspectable callbacks") {
    TypedBalancedReductionCallbackPolicy.requireSupportedOperator(
      (a: BalancedCompositeRgb, b: BalancedCompositeRgb) => {
        val result = cloneOf(a)
        result.red := a.red min b.red
        result.green := a.green max b.green
        result.blue := a.blue + b.blue
        result
      })
    TypedBalancedReductionCallbackPolicy.requireSupportedOperator(
      (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => Mux(a.key <= b.key, a, b))
    TypedBalancedReductionCallbackPolicy.requireSupportedOperator(
      (a: BalancedCompositeCountedRecord, b: BalancedCompositeCountedRecord) => Mux(a.key <= b.key, a, b))
    TypedBalancedReductionCallbackPolicy.requireSupportedOperator(
      (a: BalancedCompositeNested, b: BalancedCompositeNested) => {
        val result = cloneOf(a)
        result.lanes(0).unsigned := a.lanes(0).unsigned + b.lanes(0).unsigned
        result
      })
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(
      (value: BalancedCompositeRecord, _: Int) => RegNext(value))
  }

  test("ordinary composite register bridges assign the complete record and initialize scalar fields") {
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(
      (value: BalancedCompositeRecord, _: Int) => {
        val result = cloneOf(value)
        result.setAsReg()
        result := value
        result.key.init(U(0))
        result.tag.init(B(0))
        result.x.init(U(0))
        result.y.init(U(0))
        result
      })
  }

  test("a field-shaped method with host effects is not a Bundle accessor") {
    reject((a: BalancedCompositeUnsafeAccessor, b: BalancedCompositeUnsafeAccessor) => {
      val result = cloneOf(a)
      result.value := a.unsafeValue + b.value
      result
    })
  }

  test("reflective Bundle constructors cannot hide global host effects") {
    reject((a: BalancedCompositeUnsafeConstructor, b: BalancedCompositeUnsafeConstructor) => cloneOf(a))
  }

  test("native clone overrides cannot hide callback effects") {
    reject((a: BalancedCompositeUnsafeClone, b: BalancedCompositeUnsafeClone) => cloneOf(a))
  }

  test("native reflective post-construction interfaces cannot hide callback effects") {
    reject((a: BalancedCompositeUnsafePostInit, b: BalancedCompositeUnsafePostInit) => cloneOf(a))
  }

  test("a nested companion initializer cannot hide constructor effects") {
    reject((a: BalancedCompositeUnsafeCompanionContainer, b: BalancedCompositeUnsafeCompanionContainer) => cloneOf(a))
  }

  test("mutable Bundle fields are outside the composite construction contract") {
    reject((a: BalancedCompositeMutable, b: BalancedCompositeMutable) => cloneOf(a))
  }

  test("final constructor fields cannot carry opaque objects or unowned Data clone inputs") {
    reject((a: BalancedCompositeOpaqueObject, b: BalancedCompositeOpaqueObject) => cloneOf(a))
    reject((a: BalancedCompositeOpaqueData, b: BalancedCompositeOpaqueData) => cloneOf(a))
  }

  test("a final Function0 constructor parameter cannot run again through native Vec cloning") {
    val directory = Files.createTempDirectory("composite-callback-function-")
    try {
      SpinalConfig(targetDirectory = directory.toString).generateVerilog(new Component {
        val keep = out Bool()
        keep := False
        val value = BalancedCompositeOpaqueFactory(() => {
          BalancedCompositePolicyState.calls += 1
          UInt(4 bits)
        })
        value.values.foreach(_ := U(0))
        val before = BalancedCompositePolicyState.calls
        assert(before > 0, "the original native Vec must have invoked its factory")
        val error = intercept[IllegalArgumentException] {
          TypedBalancedReductionCallbackPolicy.requireSupported(
            (a: BalancedCompositeOpaqueFactory, _: BalancedCompositeOpaqueFactory) => cloneOf(a),
            (a: BalancedCompositeOpaqueFactory, _: Int) => a, Vector(value))
        }
        assert(error.getMessage.contains("immutable shape values"), error.getMessage)
        assert(BalancedCompositePolicyState.calls == before, "clone factory executed before rejection")
      })
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  test("an erased Bundle signature cannot hide an opaque runtime clone factory") {
    val directory = Files.createTempDirectory("composite-callback-policy-")
    try {
      SpinalConfig(targetDirectory = directory.toString).generateVerilog(new Component {
        val keep = out Bool()
        keep := False
        val value = BalancedCompositeSafeValue()
        value.value := U(0)
        value.hardtype = HardType {
          BalancedCompositePolicyState.calls += 1
          BalancedCompositeSafeValue()
        }
        val before = BalancedCompositePolicyState.calls
        val error = intercept[IllegalArgumentException] {
          TypedBalancedReductionCallbackPolicy.requireSupported(
            (a: Bundle, _: Bundle) => cloneOf(a), (a: Bundle, _: Int) => a, Vector(value))
        }
        assert(error.getMessage.contains("opaque native clone factory"), error.getMessage)
        assert(BalancedCompositePolicyState.calls == before)
      })
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
}

private[internals] object BalancedCompositePolicyState { var calls = 0 }

final case class BalancedCompositeUnsafeAccessor() extends Bundle {
  val value = UInt(4 bits)
  def unsafeValue: UInt = { BalancedCompositePolicyState.calls += 1; value }
}

final case class BalancedCompositeUnsafeConstructor() extends Bundle {
  BalancedCompositePolicyState.calls += 1
  val value = UInt(4 bits)
}

final case class BalancedCompositeUnsafeClone() extends Bundle {
  val value = UInt(4 bits)
  override def clone: Bundle = { BalancedCompositePolicyState.calls += 1; this }
}

final case class BalancedCompositeUnsafePostInit() extends Bundle with spinal.idslplugin.PostInitCallback {
  val value = UInt(4 bits)
  override def postInitCallback(): this.type = { BalancedCompositePolicyState.calls += 1; this }
}

final case class BalancedCompositeCompanionLeaf() extends Bundle { val value = UInt(4 bits) }
object BalancedCompositeCompanionLeaf {
  BalancedCompositePolicyState.calls += 1
  def create(): BalancedCompositeCompanionLeaf = new BalancedCompositeCompanionLeaf()
}
final case class BalancedCompositeUnsafeCompanionContainer() extends Bundle {
  val value = BalancedCompositeCompanionLeaf.create()
}

final case class BalancedCompositeMutable() extends Bundle { var value = UInt(4 bits) }
final case class BalancedCompositeSafeValue() extends Bundle { val value = UInt(4 bits) }
final case class BalancedCompositeOpaqueFactory(factory: () => UInt) extends Bundle {
  val values = Vec(factory(), 2)
}
final case class BalancedCompositeOpaqueObject(context: AnyRef) extends Bundle { val value = UInt(4 bits) }
final case class BalancedCompositeOpaqueData(unowned: Data) extends Bundle { val value = UInt(4 bits) }
