package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

class ExternalParameterizedWidthDomainProofTests extends AnyFunSuite {
  private def addressWidth(value: BigInt): BigInt =
    BigInt(math.max(1, (value - 1).bitLength))

  test("equivalent parameter-derived widths pass without component knowledge") {
    assert(
      ExternalParameterizedWidthDomainProof.equivalent(4, 16)(
        value => Some(addressWidth(value) + 1),
        value => Some(addressWidth(value * 2))
      )
    )
  }

  test("a non-equivalent width and an undefined evaluator fail closed") {
    assert(
      !ExternalParameterizedWidthDomainProof.equivalent(4, 16)(
        value => Some(addressWidth(value) + 1),
        value => Some(addressWidth(value * 2) + (if (value == 11) 1 else 0))
      )
    )
    assert(
      !ExternalParameterizedWidthDomainProof.equivalent(4, 16)(
        value => if (value == 9) None else Some(value),
        value => Some(value)
      )
    )
  }
}
