package spinal.core.internals

import spinal.core._

/** Fail-closed validation for finite parameter-controlled self-instantiation.
  *
  * A BlackBox whose exact Verilog definition name equals its direct non-BlackBox
  * parent is interpreted as a reference to that parent's emitted module. Such a
  * reference is legal only when one typed integer generic is proven to be a
  * non-negative, strictly decreasing recursion metric over exactly the positive
  * values of its bounded parameter domain. Other symbolic integer generics may
  * only preserve their own value exactly.
  *
  * This validator never infers symbolic meaning from concrete witnesses or
  * emitted text. It consumes the exact object-owned typed BlackBox records from
  * Increment 59 and their authenticated finite evaluation tables.
  */
private[spinal] object BoundedRecursiveModuleValidation {
  private sealed trait IntegerBindingKind
  private case object DecreasingMetric extends IntegerBindingKind
  private case object PreservedBinding extends IntegerBindingKind
  private case object UnsupportedBinding extends IntegerBindingKind

  def validate(components: Vector[Component]): Unit = {
    if (components == null)
      throw new IllegalArgumentException(
        "bounded recursive module validation requires a non-null component graph"
      )

    components.iterator
      .filter(component => component != null && !component.isInstanceOf[BlackBox])
      .foreach(validateOwner)
  }

  private def validateOwner(owner: Component): Unit = {
    val ownerName = Option(owner.definitionName).getOrElse("")
    if (ownerName.isEmpty) return

    val selfReferences = owner.children.toVector.collect {
      case blackBox: BlackBox
          if blackBox.isBlackBox && blackBox.definitionName == ownerName =>
        blackBox
    }
    if (selfReferences.isEmpty) return

    if (selfReferences.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-SELF-REFERENCE-COUNT",
        s"module '$ownerName' must contain exactly one direct recursive self-reference, found ${selfReferences.size}",
        None
      )
    }

    val selfReference = selfReferences.head
    if (selfReference.impl != null || selfReference.listRTLPath.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-SEPARATE-IMPLEMENTATION",
        s"recursive self-reference '$ownerName' must identify the owning emitted module and must not carry inline or external RTL",
        None
      )
    }

    val integerBindings =
      ParameterizedBlackBoxGenericRegistry.recordsOf(selfReference).collect {
        case value: ParameterizedBlackBoxIntegerGeneric
            if value.parameters.nonEmpty => value
      }

    if (integerBindings.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-MISSING",
        s"recursive self-reference '$ownerName' requires one typed symbolic integer generic as its decreasing metric",
        None
      )
    }

    val classified = integerBindings.map { binding =>
      val domain = ElabInt
        .requireAuthoritativeIntegerDomain(
          binding.expression,
          s"recursive generic '${binding.name}' of '$ownerName'",
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-EXACT-DOMAIN-REQUIRED",
          requireExactExtrema = false
        )
        .getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-CONCRETE",
            s"recursive generic '${binding.name}' of '$ownerName' lost its symbolic declaration root",
            binding.sourceLocation
          )
        }
      binding -> classify(binding, domain)
    }

    classified.collectFirst {
      case (binding, UnsupportedBinding) => binding
    }.foreach { binding =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN",
        s"recursive generic '${binding.name}' of '$ownerName' is neither one exact preserved binding nor a proven non-negative strictly decreasing metric",
        binding.sourceLocation
      )
    }

    val metrics = classified.collect {
      case (binding, DecreasingMetric) => binding
    }
    if (metrics.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-NONDECREASING",
        s"recursive self-reference '$ownerName' has no proven strictly decreasing metric",
        integerBindings.headOption.flatMap(_.sourceLocation)
      )
    }
    if (metrics.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-AMBIGUOUS",
        s"recursive self-reference '$ownerName' has ${metrics.size} decreasing metrics; exactly one is required",
        metrics.headOption.flatMap(_.sourceLocation)
      )
    }
  }

  private def classify(
      binding: ParameterizedBlackBoxIntegerGeneric,
      domain: ElaborationExactDomain[BigInt]
  ): IntegerBindingKind = {
    val parameter = domain.parameter
    val evaluations = domain.evaluations
    val sameFormal = binding.name == parameter.name

    if (
      sameFormal && evaluations.nonEmpty &&
      evaluations.forall { case (rootValue, result) => result == rootValue }
    ) return PreservedBinding

    val positiveValues = boundedValues(BigInt(1), parameter.maximum)
    val provenMetric =
      sameFormal &&
        parameter.minimum == 0 &&
        parameter.maximum >= 1 &&
        evaluations.map(_._1).toSet == positiveValues &&
        evaluations.forall { case (rootValue, result) =>
          rootValue > 0 && result >= 0 && result < rootValue
        }

    if (provenMetric) DecreasingMetric else UnsupportedBinding
  }

  private def boundedValues(minimum: BigInt, maximum: BigInt): Set[BigInt] = {
    if (maximum < minimum) return Set.empty
    val builder = Set.newBuilder[BigInt]
    var value = minimum
    while (value <= maximum) {
      builder += value
      value += 1
    }
    builder.result()
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
