package spinal.core.internals

import spinal.core._

/** Exact sibling-definition references; ordinary calls are not recursion edges. */
private[internals] object NativeSharedModuleReference {
  def validate(reference: BlackBox, definition: Component): Unit = {
    def requireProof(value: Boolean, message: String): Unit = if (!value)
      ParameterizedVerilogException.fail("SPINAL-PARAMETERIZED-VERILOG-SHARED-REFERENCE-MISMATCH", message, None)
    requireProof(ElabModuleReference.definitionOf(reference).exists(_ eq definition) &&
      reference.definitionName == definition.definitionName && reference.isBlackBox &&
      reference.impl == null && reference.listRTLPath.isEmpty && reference.children.isEmpty,
      "shared reference must retain its exact emitted sibling and no separate implementation")
    val formals = MorphHdlExternalParameterizedVerilog.componentParameters(definition)
    val records = ParameterizedBlackBoxGenericRegistry.recordsOf(reference)
    val bindings = records.collect { case value: ParameterizedBlackBoxIntegerGeneric => value }
    val native = reference.genericElements.toVector
    requireProof(bindings.size == records.size && bindings.size == native.size &&
      bindings.map(_.name).distinct.size == bindings.size &&
      bindings.map(_.name).toSet == formals.map(_.name).toSet &&
      bindings.forall(b => native.exists { case (name, value) => name == b.name && value == b.witness }),
      "shared reference must bind each definition formal exactly once with a typed integer")
    val parent = reference.parent
    def actualProof(expression: ElaborationIntegerExpression) =
      ElaborationProductDomain.owner(expression, "shared reference actual", expression.sourceLocation) {
        (root, universe) => ParameterizedStructure.exactChildDomainOf(parent, reference, root, universe,
          "shared reference actual", expression.sourceLocation).values
      }.get
    val actuals = bindings.map(b => b.name -> actualProof(b.expression)).toMap
    formals.foreach { formal =>
      val proof = actuals(formal.name)
      requireProof(proof.minimum >= formal.minimum && proof.maximum <= formal.maximum,
        "shared reference actual escapes the definition formal domain")
    }
    val ports = definition.getOrdredNodeIo.toVector.filterNot(_.isSuffix)
    val others = reference.getOrdredNodeIo.toVector.filterNot(_.isSuffix)
    requireProof(ports.nonEmpty && ports.map(_.getName()).distinct.size == ports.size &&
      others.map(_.getName()).distinct.size == others.size &&
      ports.map(_.getName()).toSet == others.map(_.getName()).toSet,
      "shared reference must retain the complete named port interface")
    ports.foreach { port =>
      val other = others.find(_.getName() == port.getName()).get
      requireProof(!port.isInOut && port.getTypeObject == other.getTypeObject &&
        port.getDirection == other.getDirection, "shared port direction or scalar type differs")
      val expectedExpression = ParameterizedWidth.expressionOf(port).getOrElse(ElabInt.literal(port.getBitsWidth).expression)
      val expected = ElaborationProductDomain.owner(expectedExpression, "shared definition port", expectedExpression.sourceLocation) {
        (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(definition, port, root, universe,
          "shared definition port", expectedExpression.sourceLocation).values
      }.get
      requireProof(expected.schemas.forall(schema => formals.exists(_ eq schema)),
        "shared definition port depends on a non-formal declaration")
      val actual = actualProof(ParameterizedWidth.expressionOf(other).getOrElse(ElabInt.literal(other.getBitsWidth).expression))
      val proofs = actuals.values.toVector :+ actual
      val axes = proofs.flatMap(p => p.roots.zip(p.rootValues)).groupBy(_._1).toVector
      requireProof(axes.forall(_._2.map(_._2).distinct.size == 1), "shared actuals have incompatible owner domains")
      requireProof(axes.foldLeft(BigInt(1))((size, axis) => size * axis._2.head._2.size) <= ElaborationExactDomain.MaximumDomainSize,
        "shared port substitution exceeds the finite proof budget")
      def check(index: Int, values: Vector[(ElaborationIntegerParameterRoot, BigInt)]): Boolean = {
        if(index < axes.size) axes(index)._2.head._2.forall(v => check(index+1, values :+ (axes(index)._1 -> v)))
        else {
          val replacements = expected.roots.zip(expected.schemas).map { case (root, formal) =>
            root -> actuals(formal.name).evaluate(values).getOrElse(return false)
          }
          (expected.evaluate(replacements), actual.evaluate(values)) match {
            case (Some(a), Some(b)) => a > 0 && a == b
            case _ => false
          }
        }
      }
      requireProof(check(0, Vector.empty), "shared port has incorrect substituted geometry")
    }
  }
}
