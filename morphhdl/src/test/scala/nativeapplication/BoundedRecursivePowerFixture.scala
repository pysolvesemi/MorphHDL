package nativeapplication

import morphhdl.frontend._
import spinal.core._

/** Ordinary typed SpinalHDL fixtures for Increment 59a.
  *
  * The recursive component is authored once. Its step branch contains a
  * same-name BlackBox only to refer to that exact emitted Verilog definition;
  * no external or inline RTL implementation is supplied.
  */
object BoundedRecursivePowerFixture {
  final val Width = 8
  final val DefaultExponent = 5
  final val MaximumExponent = 8
  final val Exponents = Vector(0, 1, 2, 3, 5, 8)

  private final class SelfReference(
      moduleName: String,
      nextExponent: ElabInt
  ) extends BlackBox {
    setBlackBoxName(moduleName)
    addGeneric("N", nextExponent)

    val x = in UInt (Width bits)
    val y = out UInt (Width bits)
    x.setName("x")
    y.setName("y")
  }

  final class ParameterizedPower(exponent: HdlInt) extends Component {
    setDefinitionName("BoundedRecursivePower")

    val x = in UInt (Width bits)
    val y = out UInt (Width bits)
    x.setName("x")
    y.setName("y")

    exponent.hdlEq(0).generateIf("g_base", "g_step") {
      y := U(1, Width bits)
    }.otherwise {
      val recursive =
        new SelfReference("BoundedRecursivePower", exponent - 1)
      recursive.setName("recursive")
      recursive.x := x
      y := (x * recursive.y).resize(Width)
    }
  }

  final class NonDecreasingPower(exponent: HdlInt) extends Component {
    setDefinitionName("BoundedRecursivePowerNonDecreasing")

    val x = in UInt (Width bits)
    val y = out UInt (Width bits)
    x.setName("x")
    y.setName("y")

    exponent.hdlEq(0).generateIf("g_base", "g_step") {
      y := U(1, Width bits)
    }.otherwise {
      val recursive =
        new SelfReference("BoundedRecursivePowerNonDecreasing", exponent)
      recursive.setName("recursive")
      recursive.x := x
      y := (x * recursive.y).resize(Width)
    }
  }

  final class NegativeDomainPower(exponent: HdlInt) extends Component {
    setDefinitionName("BoundedRecursivePowerNegativeDomain")

    val x = in UInt (Width bits)
    val y = out UInt (Width bits)
    x.setName("x")
    y.setName("y")

    exponent.hdlEq(0).generateIf("g_base", "g_step") {
      y := U(1, Width bits)
    }.otherwise {
      val recursive =
        new SelfReference("BoundedRecursivePowerNegativeDomain", exponent - 1)
      recursive.setName("recursive")
      recursive.x := x
      y := (x * recursive.y).resize(Width)
    }
  }

  /** Independent flat specialization oracle. It deliberately does not use the
    * self-reference, parameterized generate branch or recursive validator.
    */
  final class ConcretePower(exponent: Int) extends Component {
    require(exponent >= 0)
    setDefinitionName(s"BoundedRecursivePowerConcreteN$exponent")

    val x = in UInt (Width bits)
    val y = out UInt (Width bits)
    x.setName("x")
    y.setName("y")

    var result: UInt = U(1, Width bits)
    for (_ <- 0 until exponent) {
      result = (result * x).resize(Width)
    }
    y := result
  }

  def parameterized(): ParameterizedPower =
    new ParameterizedPower(
      HdlInt.param(
        "N",
        default = DefaultExponent,
        min = 0,
        max = MaximumExponent
      )
    )

  def nonDecreasing(): NonDecreasingPower =
    new NonDecreasingPower(
      HdlInt.param(
        "N",
        default = DefaultExponent,
        min = 0,
        max = MaximumExponent
      )
    )

  def negativeDomain(): NegativeDomainPower =
    new NegativeDomainPower(
      HdlInt.param("N", default = 2, min = -1, max = 4)
    )
}
