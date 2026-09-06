package spinal.core.internals

import spinal.core._

/** Width authority at the final exact native declaration owner. Publication
  * runs after construction branches have ended, so it must use captured owner
  * domains rather than reopening a live elaboration-domain context.
  */
private[internals] object NativePublicationWidth {
  private final case class Evidence(
      roots: Vector[ElaborationIntegerParameterRoot],
      schemas: Vector[ElaborationIntegerParameter],
      rootValues: Vector[Vector[BigInt]],
      results: Map[Vector[BigInt], BigInt]
  )

  private def fail(role: String, detail: String,
                   sourceLocation: Option[String]): Nothing =
    ParameterizedVerilogException.fail(
      "SPINAL-PARAMETERIZED-VERILOG-NATIVE-WIDTH-OWNER-EVIDENCE-MISSING",
      s"$role $detail", sourceLocation)

  private def evidence(
      width: ElaborationIntegerExpression,
      component: Component,
      declaration: BaseType,
      role: String
  ): Evidence = {
    ElabInt.validateExpression(width, role)
    if (component == null || declaration == null || (declaration.component ne component))
      fail(role, "does not belong to the expected native component", width.sourceLocation)
    if (width.parameters.isEmpty) {
      ElaborationWidthAuthority.requireAuthoritative(width, role,
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-WIDTH-OWNER-EVIDENCE-MISSING")
      return Evidence(Vector.empty, Vector.empty, Vector.empty,
        Map(Vector.empty[BigInt] -> width.default))
    }
    val certified = ElaborationWidthAuthority.ownerEvaluation(width, role, width.sourceLocation) {
      (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
        component, declaration, root, universe, role, width.sourceLocation).values
    }
    certified match {
      case Some(value) =>
        Evidence(value.roots,
          value.roots.map(root => width.parameters.find(_.name == root.name).get),
          value.rootValues, value.results)
      case None =>
        val domain = width.exactDomain.getOrElse {
          fail(role, s"width '${width.verilog}' has no exact declaration authority", width.sourceLocation)
        }
        if (width.generateIndex.nonEmpty ||
            (width.parameters match {
              case Vector(schema) => (schema ne domain.parameter) ||
                !domain.root.isAuthoritativeSchema(schema)
              case _ => true
            }) ||
            (width.completedParameterRoots match {
              case Vector(root) => root ne domain.root
              case _ => true
            }))
          fail(role, s"width '${width.verilog}' lost its exact declaration root and schema", width.sourceLocation)
        val projection = ParameterizedStructure.projectedDeclarationEvaluationOf(
          component, declaration, width, role, width.sourceLocation).getOrElse {
          fail(role, s"width '${width.verilog}' has no exact owner projection", width.sourceLocation)
        }
        Evidence(Vector(domain.root), Vector(domain.parameter),
          Vector(projection.rootValues.toVector.sorted),
          projection.results.map { case (key, value) => Vector(key) -> value }.toMap)
    }
  }

  def validate(width: ElaborationIntegerExpression, component: Component,
               declaration: BaseType, role: String): Unit = {
    evidence(width, component, declaration, role)
    ()
  }

  def equivalentAtOwner(left: ElaborationIntegerExpression,
                        right: ElaborationIntegerExpression,
                        component: Component,
                        declaration: BaseType): Boolean = {
    val l = evidence(left, component, declaration, "native width freshness")
    if (left eq right) return true
    val r = evidence(right, component, declaration, "native width freshness")
    if (l.roots.size != r.roots.size) return false
    val indexes = l.roots.map(root => r.roots.indexWhere(_ eq root))
    if (indexes.exists(_ < 0)) return false
    if (indexes.zipWithIndex.exists { case (rightIndex, leftIndex) =>
        (l.schemas(leftIndex) ne r.schemas(rightIndex)) ||
          l.rootValues(leftIndex) != r.rootValues(rightIndex)
      }) return false
    l.results.forall { case (leftKey, value) =>
      val rightKey = r.roots.indices.map { rightIndex =>
        leftKey(indexes.indexOf(rightIndex))
      }.toVector
      r.results.get(rightKey).contains(value)
    }
  }
}
