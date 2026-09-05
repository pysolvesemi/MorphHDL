package spinal.core.internals

import spinal.core._

/** Finite self-instantiation proof for exact typed BlackBox references.
  *
  * The explicit external definition name identifies the referenced module;
  * it never identifies an algorithm or supplies symbolic expression meaning.
  * All semantic evidence comes from the exact child, declaration root,
  * authoritative expression and final captured structural ownership.
  */
private[spinal] object BoundedRecursiveModuleValidation {
  private sealed trait IntegerBindingKind
  private case object DecreasingMetric extends IntegerBindingKind
  private case object PreservedBinding extends IntegerBindingKind
  private case object UnsupportedBinding extends IntegerBindingKind

  /** Returns only validated self-reference identities, not emitted modules. */
  def validate(components: Vector[Component]): Vector[BlackBox] = {
    require(components != null, "recursive validation requires a component graph")
    val owners = components.filter(component =>
      component != null && !component.isInstanceOf[BlackBox]
    )
    components.collect { case blackBox: BlackBox if blackBox.isBlackBox =>
      blackBox
    }.foreach { blackBox =>
      val targets = owners.filter(_.definitionName == blackBox.definitionName)
      if (targets.nonEmpty && !targets.exists(_ eq blackBox.parent))
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-NONLOCAL-REFERENCE-UNSUPPORTED",
          "a reference to an emitted module must be a direct self-reference; mutual and non-local recursion are not qualified",
          None
        )
    }
    owners.flatMap(validateOwner)
  }

  private def validateOwner(owner: Component): Vector[BlackBox] = {
    val ownerName = Option(owner.definitionName).getOrElse("")
    if (ownerName.isEmpty) return Vector.empty
    val selfReferences = owner.children.toVector.collect {
      case blackBox: BlackBox
          if blackBox.isBlackBox && blackBox.definitionName == ownerName => blackBox
    }
    if (selfReferences.isEmpty) return Vector.empty
    if (selfReferences.size != 1)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-SELF-REFERENCE-COUNT",
        s"module '$ownerName' requires exactly one direct self-reference, found ${selfReferences.size}",
        None
      )
    val selfReference = selfReferences.head
    if (selfReference.impl != null || selfReference.listRTLPath.nonEmpty)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-SEPARATE-IMPLEMENTATION",
        s"self-reference '$ownerName' must not carry inline or external RTL",
        None
      )
    if (selfReference.children.nonEmpty)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-SEPARATE-IMPLEMENTATION",
        s"self-reference '$ownerName' must not contain a child implementation",
        None
      )

    val records = ParameterizedBlackBoxGenericRegistry.recordsOf(selfReference)
    val integerBindings = records.collect {
      case value: ParameterizedBlackBoxIntegerGeneric
          if value.parameters.nonEmpty => value
    }
    if (integerBindings.isEmpty)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-MISSING",
        s"self-reference '$ownerName' needs a typed symbolic integer metric",
        None
      )
    val native = selfReference.genericElements.toVector
    if (
      records.size != integerBindings.size ||
      native.size != integerBindings.size ||
      native.map(_._1).distinct.size != native.size ||
      integerBindings.map(_.name).distinct.size != integerBindings.size ||
      !integerBindings.forall(binding =>
        native.exists { case (name, value) =>
          name == binding.name && value == binding.witness
        }
      )
    )
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-GENERIC-SCHEMA-UNSUPPORTED",
        s"self-reference '$ownerName' requires exactly one native association for each typed symbolic integer formal; implicit, untyped and Boolean bindings are not qualified",
        None
      )

    val classified = integerBindings.map { binding =>
      val role = s"recursive generic '${binding.name}' of '$ownerName'"
      val domain = ElabInt.requireAuthoritativeIntegerDomain(
        binding.expression,
        role,
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-EXACT-DOMAIN-REQUIRED",
        requireExactExtrema = false
      ).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-MISSING",
          s"$role lost its exact declaration root",
          binding.sourceLocation
        )
      }
      // A provisional expression table is insufficient: the surviving child
      // itself must be owned by the narrowing generate branch. This catches
      // a projected N-1 expression escaping to an unconditional self-instance.
      val evaluation = ParameterizedStructure.projectedChildEvaluationOf(
        owner,
        selfReference,
        binding.expression,
        role,
        binding.sourceLocation
      ).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-EXACT-DOMAIN-REQUIRED",
          s"$role has no exact final-owner evaluation",
          binding.sourceLocation
        )
      }
      (binding, domain, classify(binding, domain, evaluation))
    }
    classified.collectFirst {
      case (binding, _, UnsupportedBinding) => binding
    }.foreach { binding =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN",
        s"recursive generic '${binding.name}' is neither preserved nor non-negative and strictly decreasing over its exact active domain",
        binding.sourceLocation
      )
    }
    val metrics = classified.collect {
      case (binding, domain, DecreasingMetric) => binding -> domain
    }
    if (metrics.isEmpty)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-NONDECREASING",
        s"self-reference '$ownerName' has no strictly decreasing metric",
        integerBindings.head.sourceLocation
      )
    if (metrics.size != 1)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-AMBIGUOUS",
        s"self-reference '$ownerName' has ${metrics.size} decreasing metrics",
        metrics.head._1.sourceLocation
      )

    val formals = MorphHdlExternalParameterizedVerilog.componentParameters(owner)
    if (formals.map(_.name).toSet != integerBindings.map(_.name).toSet)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-GENERIC-SCHEMA-UNSUPPORTED",
        s"self-reference '$ownerName' must bind every owning module parameter explicitly",
        None
      )
    validatePorts(owner, selfReference, metrics.head._2.root)
    selfReferences
  }

  private def classify(
      binding: ParameterizedBlackBoxIntegerGeneric,
      domain: ElaborationExactDomain[BigInt],
      evaluation: ParameterizedStructure.ExactProjectedObjectEvaluation
  ): IntegerBindingKind = {
    val parameter = domain.parameter
    val evaluations = evaluation.results
    val sameFormal = binding.name == parameter.name
    if (sameFormal && evaluations.forall { case (rootValue, result) =>
      result == rootValue
    }) return PreservedBinding

    val positiveValues = domain.universe.filter(_ > 0)
    val provenMetric = sameFormal && evaluation.captured &&
      parameter.minimum == 0 && parameter.maximum >= 1 &&
      evaluations.map(_._1).toSet == positiveValues &&
      evaluations.forall { case (rootValue, result) =>
        rootValue > 0 && result >= 0 && result < rootValue
      }
    if (provenMetric) DecreasingMetric else UnsupportedBinding
  }

  /** Port spelling is the explicit module interface, never provenance.
    * The types and width expressions are checked using the exact native ports.
    * Recursion-dependent port geometry is intentionally outside this increment.
    */
  private def validatePorts(
      owner: Component,
      reference: BlackBox,
      metricRoot: ElaborationIntegerParameterRoot
  ): Unit = {
    val ports = owner.getOrdredNodeIo.toVector.filterNot(_.isSuffix)
    val referenced = reference.getOrdredNodeIo.toVector.filterNot(_.isSuffix)
    def names(values: Vector[BaseType]): Vector[String] = values.map(_.getName())
    if (
      ports.isEmpty || names(ports).distinct.size != ports.size ||
      names(referenced).distinct.size != referenced.size ||
      names(ports).toSet != names(referenced).toSet
    )
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PORT-SCHEMA-MISMATCH",
        "self-reference ports must match the complete owning module interface",
        None
      )
    ports.foreach { port =>
      val other = referenced.find(_.getName() == port.getName()).get
      val width = ParameterizedWidth.expressionOf(port)
        .getOrElse(ElabInt.literal(port.getBitsWidth).expression)
      val otherWidth = ParameterizedWidth.expressionOf(other)
        .getOrElse(ElabInt.literal(other.getBitsWidth).expression)
      if (
        port.isInOut || port.getTypeObject != other.getTypeObject ||
        port.getDirection != other.getDirection ||
        port.getBitsWidth != other.getBitsWidth ||
        width.completedParameterRoots.exists(_ eq metricRoot) ||
        otherWidth.completedParameterRoots.exists(_ eq metricRoot) ||
        !ElabInt.equivalentExpression(width, otherWidth)
      )
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PORT-SCHEMA-MISMATCH",
          s"recursive port '${port.getName()}' must retain its direction, type and recursion-independent width",
          width.sourceLocation.orElse(otherWidth.sourceLocation)
        )
    }
  }

  private def fail(code: String, detail: String, source: Option[String]): Nothing =
    ParameterizedVerilogException.fail(code, detail, source)
}
