package spinal.core

import org.scalatest.funsuite.AnyFunSuite

class StructuralPredicateDomainTests extends AnyFunSuite {
  import ExternalNativeIntRelativeExpression.{Add, Literal, Root}
  import ExternalNativeIntRelativePredicate.{Comparison, Not, PowerOfTwo}
  import ParameterizedStructure.{
    StructuralIf,
    StructuralPredicateDomain,
    StructuralPredicateInterval,
    StructuralPredicateRoot,
    StructuralRegion
  }

  private val maximum = BigInt(Int.MaxValue) - 1
  private val parameter = ElaborationIntegerParameter(
    name = "NATIVE_INT",
    default = BigInt(8),
    minimum = BigInt(1),
    maximum = maximum
  )

  private def root(): StructuralPredicateRoot =
    new StructuralPredicateRoot(
      parameter.name,
      parameter.default,
      parameter.minimum,
      parameter.maximum,
      Vector(parameter)
    )

  private def domain(
      predicate: ExternalNativeIntRelativePredicate,
      predicateRoot: StructuralPredicateRoot
  ): StructuralPredicateDomain =
    ExternalNativeIntRelativePredicate
      .structuralDomain(predicate, predicateRoot)
      .getOrElse(fail(s"expected exact structural domain for $predicate"))

  private def region(
      predicateDomain: StructuralPredicateDomain,
      suffix: String
  ): StructuralIf = {
    val condition = ElaborationBooleanExpression(
      verilog = suffix,
      default = true,
      parameters = Vector(parameter),
      sourceLocation = Some(s"StructuralPredicateDomainTests.scala:$suffix")
    )
    StructuralIf(
      condition,
      s"${suffix}_true",
      s"${suffix}_false",
      ParameterizedStructuralSynthetic.emptyBlock(None),
      ParameterizedStructuralSynthetic.emptyBlock(None),
      Some(predicateDomain),
      None
    )
  }

  private def path(value: StructuralIf, branch: Int): Vector[(StructuralRegion, Int)] =
    Vector((value: StructuralRegion) -> branch)

  test("large canonical comparison domains prove independent disjoint alternatives") {
    val predicateRoot = root()
    val equalOne = domain(Comparison("==", Root, Literal(1)), predicateRoot)
    val greaterOne = domain(Comparison(">", Root, Literal(1)), predicateRoot)

    assert(equalOne.whenTrue == Vector(StructuralPredicateInterval(1, 1)))
    assert(
      greaterOne.whenTrue ==
        Vector(StructuralPredicateInterval(2, maximum))
    )
    assert(
      ParameterizedStructure.mutuallyExclusiveAlternatives(
        path(region(equalOne, "equal_one"), branch = 0),
        path(region(greaterOne, "greater_one"), branch = 0)
      )
    )
  }

  test("large canonical comparison domains do not authorize overlapping alternatives") {
    val predicateRoot = root()
    val atLeastOne = domain(Comparison(">=", Root, Literal(1)), predicateRoot)
    val greaterOne = domain(Comparison(">", Root, Literal(1)), predicateRoot)

    assert(
      !ParameterizedStructure.mutuallyExclusiveAlternatives(
        path(region(atLeastOne, "at_least_one"), branch = 0),
        path(region(greaterOne, "greater_one"), branch = 0)
      )
    )
  }

  test("predicate evidence is bound to one exact capture root identity") {
    val equalOne = domain(Comparison("==", Root, Literal(1)), root())
    val greaterOne = domain(Comparison(">", Root, Literal(1)), root())

    assert(equalOne.root ne greaterOne.root)
    assert(
      !ParameterizedStructure.mutuallyExclusiveAlternatives(
        path(region(equalOne, "equal_one"), branch = 0),
        path(region(greaterOne, "greater_one"), branch = 0)
      )
    )
  }

  test("large canonical power-of-two and complement domains are exactly disjoint") {
    val predicateRoot = root()
    val powerOfTwo = domain(PowerOfTwo(Root), predicateRoot)
    val notPowerOfTwo = domain(Not(PowerOfTwo(Root)), predicateRoot)

    assert(powerOfTwo.whenTrue.head == StructuralPredicateInterval(1, 2))
    assert(powerOfTwo.whenTrue.last == StructuralPredicateInterval(1073741824, 1073741824))
    assert(powerOfTwo.whenTrue.size == 30)
    assert(
      ParameterizedStructure.mutuallyExclusiveAlternatives(
        path(region(powerOfTwo, "power_of_two"), branch = 0),
        path(region(notPowerOfTwo, "not_power_of_two"), branch = 0)
      )
    )
  }

  test("large canonical power-of-two domains do not authorize overlapping alternatives") {
    val predicateRoot = root()
    val first = domain(PowerOfTwo(Root), predicateRoot)
    val second = domain(PowerOfTwo(Root), predicateRoot)

    assert(
      !ParameterizedStructure.mutuallyExclusiveAlternatives(
        path(region(first, "first_power_of_two"), branch = 0),
        path(region(second, "second_power_of_two"), branch = 0)
      )
    )
  }

  test("unsupported large-domain predicates retain no exclusivity evidence") {
    val predicateRoot = root()
    assert(
      ExternalNativeIntRelativePredicate
        .structuralDomain(
          PowerOfTwo(Add(Root, Literal(1))),
          predicateRoot
        )
        .isEmpty
    )
  }
}
