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

  /** Returns validated reference-only identities, not emitted definitions. */
  def validate(components: Vector[Component]): Vector[BlackBox] = {
    require(components != null, "recursive validation requires a component graph")
    val owners = components.filter(component =>
      component != null && !component.isInstanceOf[BlackBox]
    )
    components.collect { case blackBox: BlackBox if blackBox.isBlackBox =>
      blackBox
    }.foreach { blackBox =>
      ElabRecursiveComponent.validateIdentity(blackBox,
        MorphHdlExternalParameterizedVerilog.componentParameters(blackBox.parent))
      val targets = owners.filter(_.definitionName == blackBox.definitionName)
      val shared = ElabModuleReference.definitionOf(blackBox)
      shared.foreach { definition =>
        if (!owners.exists(_ eq definition))
          fail("SPINAL-PARAMETERIZED-VERILOG-SHARED-REFERENCE-MISMATCH",
            "shared reference definition is absent from the exact native graph", None)
        NativeSharedModuleReference.validate(blackBox, definition)
      }
      if (shared.isEmpty && targets.nonEmpty && !targets.exists(_ eq blackBox.parent))
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-NONLOCAL-REFERENCE-UNSUPPORTED",
          "a reference to an emitted module must be a direct self-reference; mutual and non-local recursion are not qualified",
          None
        )
    }
    owners.flatMap(validateOwner) ++ components.collect {
      case reference: BlackBox if ElabModuleReference.definitionOf(reference).nonEmpty => reference
    }
  }

  private def validateOwner(owner: Component): Vector[BlackBox] = {
    val ownerName = Option(owner.definitionName).getOrElse("")
    if (ownerName.isEmpty) return Vector.empty
    ParameterizedStructure.regionsOf(owner)
      .flatMap(ParameterizedStructure.allBlocks)
      .flatMap(_.children)
      .collect { case reference: BlackBox if !ParameterizedStructure.isFiniteExternalChild(owner, reference) &&
          ElabModuleReference.definitionOf(reference).isEmpty => reference }
      .foreach { reference =>
        if (
          !owner.children.exists(_ eq reference) ||
          (reference.parent ne owner) || !reference.isBlackBox ||
          reference.definitionName != ownerName
        )
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RECURSION-REFERENCE-IDENTITY-MISMATCH",
            "captured recursive reference no longer identifies its exact direct owning module",
            None
          )
      }
    val selfReferences = owner.children.toVector.collect {
      case blackBox: BlackBox
          if blackBox.isBlackBox && blackBox.definitionName == ownerName => blackBox
    }
    if (selfReferences.isEmpty) return Vector.empty
    selfReferences.foreach(reference => validateReference(owner, reference))
    selfReferences
  }

  private def validateReference(owner: Component, selfReference: BlackBox): Unit = {
    val ownerName = owner.definitionName
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

    // Compact scalar domains are not recursion metrics. Admit only unchanged
    // exact definition formals on authenticated native self-edges, without
    // inventing an enumerated domain for the finite metric/port validator.
    val (compactBindings, finiteBindings) = integerBindings.partition(binding =>
      binding.expression.parameters.exists(p => ElaborationProductDomain.hasCompactParameter(binding.expression, p)))
    compactBindings.foreach { binding =>
      val role = s"preserved compact recursive generic '${binding.name}'"
      val formal = MorphHdlExternalParameterizedVerilog.componentParameters(owner)
        .find(_.name == binding.name).getOrElse(fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN", role + " has no formal", binding.sourceLocation))
      ElaborationProductDomain.ownerWithCompact(binding.expression, role, binding.sourceLocation)(
        (root, universe) => ParameterizedStructure.exactChildDomainOf(owner, selfReference,
          root, universe, role, binding.sourceLocation).values,
        (root, schema) => ParameterizedStructure.requireCompactChildOwner(owner, selfReference,
          root, schema, role, binding.sourceLocation))
      if (!ElabRecursiveComponent.authenticatesReference(selfReference, owner) ||
          !ElabInt.equivalentExpression(binding.expression, ElabInt.directParameter(formal, binding.sourceLocation).expression))
        fail("SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN",
          role + " must preserve its exact formal unchanged", binding.sourceLocation)
    }
    val classified = finiteBindings.map { binding =>
      val role = s"recursive generic '${binding.name}' of '$ownerName'"
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
      val retainedDomain = binding.expression.exactDomain.get
      val domain = ElaborationDomainContext.withAdmitted(
        retainedDomain.root,
        evaluation.rootValues,
        binding.sourceLocation
      ) {
        ElabInt.requireAuthoritativeIntegerDomain(
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
    validatePorts(owner, selfReference, classified.map { case (binding, domain, _) => binding -> domain })
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

    val positiveValues = domain.universe.filter(_ > parameter.minimum)
    val provenMetric = sameFormal && evaluation.captured &&
      parameter.minimum >= 0 && parameter.maximum > parameter.minimum &&
      evaluations.map(_._1).toSet == positiveValues &&
      evaluations.forall { case (rootValue, result) =>
        rootValue > parameter.minimum && result >= parameter.minimum &&
          result <= parameter.maximum && result < rootValue
      }
    if (provenMetric) DecreasingMetric else UnsupportedBinding
  }

  /** Port spelling is the explicit module interface, never provenance.
    * The types and width expressions are checked using the exact native ports.
    * Widths are compared after exact formal/actual substitution, never by
    * a matching witness or rendered expression.
    */
  private def validatePorts(
      owner: Component,
      reference: BlackBox,
      bindings: Vector[(ParameterizedBlackBoxIntegerGeneric, ElaborationExactDomain[BigInt])]
  ): Unit = {
    // Discover aggregate surfaces from exact retained Vec and leaf identities.
    // Flattened native carrier counts are construction capacity, not interface
    // geometry. Compare each symbolic dimension after formal substitution.
    final case class Surface(name: String, leaf: BaseType,
        geometry: Vector[ElaborationIntegerExpression], layout: Option[ParameterizedVecLayoutNode])
    def surfaces(component: Component): Vector[Surface] = {
      val ports = component.getOrdredNodeIo.toVector.filterNot(_.isSuffix)
      val vectors = ParameterizedVec.retainedVectorsOf(component).filter { vector =>
        val leaves = vector.asInstanceOf[MultiData].flatten.toVector
        leaves.nonEmpty && leaves.forall(leaf => ports.exists(_ eq leaf))
      }
      val roots = vectors.filterNot(vector => vectors.exists(parent =>
        (parent ne vector) && ParameterizedVec.descendantVectorsOf(parent).exists(_ eq vector)))
      val covered = scala.collection.mutable.ArrayBuffer.empty[BaseType]
      val aggregates = roots.map { vector =>
        val shape = ParameterizedVec.shapeOf(vector).get
        val leaves = vector.asInstanceOf[MultiData].flatten.toVector
        val elementLeaves = vector.vec.map(_.asInstanceOf[Data].flatten.toVector).toVector
        if (leaves.exists(leaf => leaf.isInOut ||
              leaf.getDirection != leaves.head.getDirection || covered.exists(_ eq leaf)) ||
            elementLeaves.exists(element => element.size != shape.elementLeaves.size ||
              element.zip(shape.elementLeaves).exists { case (leaf, field) =>
                leaf.getTypeObject != field.typeObject
              }))
          fail("SPINAL-PARAMETERIZED-VERILOG-RECURSION-PORT-SCHEMA-MISMATCH",
            "recursive Vec interface lost its disjoint exact leaf types or uniform port direction", shape.sourceLocation)
        covered ++= leaves
        Surface(vector.getName(), leaves.head, shape.geometryExpressions, Some(shape.fieldLayout))
      }
      aggregates ++ ports.filterNot(port => covered.exists(_ eq port)).map { port =>
        Surface(port.getName(), port, Vector(ParameterizedWidth.expressionOf(port)
          .getOrElse(ElabInt.literal(port.getBitsWidth).expression)), None)
      }
    }
    // Compare recursive packing topology and scalar types independently of
    // finite carrier capacities. Geometry is proved separately at every axis.
    def sameLayout(left: ParameterizedVecLayoutNode, right: ParameterizedVecLayoutNode): Boolean =
      (left, right) match {
        case (a: ParameterizedVecLayoutScalar, b: ParameterizedVecLayoutScalar) =>
          a.path == b.path && a.typeObject == b.typeObject
        case (a: ParameterizedVecLayoutArray, b: ParameterizedVecLayoutArray) =>
          sameLayout(a.element, b.element)
        case (a: ParameterizedVecLayoutRecord, b: ParameterizedVecLayoutRecord) =>
          a.children.size == b.children.size && a.children.zip(b.children).forall {
            case (x, y) => sameLayout(x, y)
          }
        case _ => false
      }
    val ports = surfaces(owner)
    val referenced = surfaces(reference)
    if (ports.isEmpty || ports.map(_.name).distinct.size != ports.size ||
        referenced.map(_.name).distinct.size != referenced.size ||
        ports.map(_.name).toSet != referenced.map(_.name).toSet)
      fail("SPINAL-PARAMETERIZED-VERILOG-RECURSION-PORT-SCHEMA-MISMATCH",
        "self-reference ports must match the complete owning module interface", None)
    ports.foreach { port =>
      val other = referenced.find(_.name == port.name).get
      val layoutMatches = (port.layout, other.layout) match {
        case (Some(left), Some(right)) => sameLayout(left, right)
        case (None, None) => true
        case _ => false
      }
      if (!layoutMatches || port.leaf.isInOut ||
          port.leaf.getTypeObject != other.leaf.getTypeObject ||
          port.leaf.getDirection != other.leaf.getDirection ||
          port.geometry.size != other.geometry.size ||
          !port.geometry.zip(other.geometry).forall { case (expected, actual) =>
            substitutedWidthEqual(owner, reference, port.leaf, expected, actual, bindings)
          })
        fail("SPINAL-PARAMETERIZED-VERILOG-RECURSION-PORT-SCHEMA-MISMATCH",
          s"recursive port '${port.name}' must retain its direction, type and authenticated substituted geometry",
          port.geometry.flatMap(_.sourceLocation).headOption)
    }
  }

  private def substitutedWidthEqual(
      owner: Component, reference: BlackBox, port: BaseType,
      expected: ElaborationIntegerExpression,
      actual: ElaborationIntegerExpression,
      bindings: Vector[(ParameterizedBlackBoxIntegerGeneric, ElaborationExactDomain[BigInt])]
  ): Boolean = {
    val expectedProof = ElaborationProductDomain.owner(expected, "recursive definition port", expected.sourceLocation) {
      (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(owner, port, root, universe,
        "recursive definition port", expected.sourceLocation).values
    }.get
    val actualProof = ElaborationProductDomain.owner(actual, "recursive child port", actual.sourceLocation) {
      (root, universe) => ParameterizedStructure.exactChildDomainOf(owner, reference, root, universe,
        "recursive child port", actual.sourceLocation).values
    }.get
    val roots = (expected.completedParameterRoots ++ actual.completedParameterRoots).distinct
    if (!roots.forall(root => bindings.count(_._2.root eq root) == 1)) return false
    val axes = roots.map { root =>
      val (binding, domain) = bindings.find(_._2.root eq root).get
      // Each binding has already been authenticated against this exact child.
      (root, domain, binding.expression.exactDomain.get.evidenceValues.toVector.sorted)
    }
    if (axes.foldLeft(BigInt(1))((size, axis) => size * axis._3.size) > ElaborationExactDomain.MaximumDomainSize)
      fail("SPINAL-PARAMETERIZED-VERILOG-RECURSION-PORT-DOMAIN-TOO-LARGE",
        "recursive port substitution exceeds the finite proof budget", expected.sourceLocation)
    def check(index: Int, values: Vector[(ElaborationIntegerParameterRoot, BigInt)]): Boolean = {
      if (index < axes.size) axes(index)._3.forall(value => check(index + 1, values :+ (axes(index)._1 -> value)))
      else {
        val substituted = values.map { case (root, value) =>
          val (_, domain) = bindings.find(_._2.root eq root).get
          root -> domain.evaluate(value).getOrElse(return false)
        }
        (expectedProof.evaluate(substituted),
          actualProof.evaluate(values)) match {
          case (Some(a), Some(b)) => a > 0 && a == b
          case _ => false
        }
      }
    }
    check(0, Vector.empty)
  }

  private def fail(code: String, detail: String, source: Option[String]): Nothing =
    ParameterizedVerilogException.fail(code, detail, source)
}
