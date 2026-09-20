package spinal.core.internals

import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Contextual publication widths for surviving native `.resized` clones.
  * Native clone geometry belongs to the child definition until its exact
  * output/formal binding is instantiated in the parent. Never modify that
  * registry or treat an arbitrary foreign root as a parent declaration.
  */
private[internals] object ExternalParameterizedHierarchyResizeWidth {
  def sourceWidthOf(
      component: Component,
      source: BaseType
  ): Option[ElaborationIntegerExpression] = for {
    port <- ExternalParameterizedAutoResize.directChildOutputOfResizeSource(component, source)
    definition <- ParameterizedWidth.expressionOf(port)
    // Native pull/rework can publish a port after the child was constructed.
    // The exact definition root still selects its opaque component capability;
    // neither an absent leaf token nor an equal printed name grants authority.
    token <- ExternalFormalParameterRegistry.typedBindingsOf(port.component).filter { retained =>
      definition.completedParameterRoots.size == 1 &&
        (definition.completedParameterRoots.head eq retained.binding.formal.declarationRoot)
    } match {
      case Vector(value) => Some(value)
      case _ => None
    }
    if ExternalFormalParameterRegistry.typedBindingOf(port).forall { retained =>
      (retained.declarationToken eq token.declarationToken) &&
        (retained.binding eq token.binding)
    }
    if definition.parameters.size == 1 &&
      (definition.parameters.head eq token.binding.formal) &&
      definition.completedParameterRoots.size == 1 &&
      (definition.completedParameterRoots.head eq token.binding.formal.declarationRoot)
    bound <- instantiate(definition, token.binding, port)
  } yield bound

  private def instantiate(
      definition: ElaborationIntegerExpression,
      binding: ExternalFormalParameterBinding,
      port: BaseType
  ): Option[ElaborationIntegerExpression] = {
    val role = "native auto-resize child output width"
    val definitionDomain = ElabInt.requireAuthoritativeIntegerDomain(
      definition, role, "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-FORMAL-WIDTH-UNPROVEN",
      requireExactExtrema = false)
    val actual = binding.actual
    val actualDomain = ElabInt.requireAuthoritativeIntegerDomain(
      actual, role, "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-ACTUAL-WIDTH-UNPROVEN",
      requireExactExtrema = false)
    if (definitionDomain.isEmpty || !definitionDomain.get.hasCompleteCoverage ||
        actualDomain.exists(!_.hasCompleteCoverage) ||
        actual.default != binding.formal.default ||
        actual.minimum < binding.formal.minimum || actual.maximum > binding.formal.maximum)
      return None

    val childDomain = definitionDomain.get
    actualDomain match {
      case Some(parentDomain) =>
        val evaluated = parentDomain.evaluations.map { case (rootValue, formalValue) =>
          childDomain.evaluate(formalValue).map(rootValue -> _)
        }
        if (evaluated.exists(_.isEmpty)) return None
        val values = evaluated.map(_.get)
        val domain = ElaborationExactDomain.checked(
          parentDomain.root, parentDomain.parameter, values, actual.sourceLocation, role)
        val bound = ElaborationIntegerExpression(
          verilog = ExternalParameterizedVerilogHierarchy.renderResizeSourceWidth(definition, binding, port),
          default = definition.default,
          minimum = values.map(_._2).min,
          maximum = values.map(_._2).max,
          parameters = Vector(parentDomain.parameter),
          sourceLocation = actual.sourceLocation,
          parameterRoots = Vector(parentDomain.root),
          exactDomain = Some(domain)
        ).attachExactAuthority(domain, role)
        ElabInt.validateExpression(bound, role)
        Some(ElabInt.fromExpression(bound).projectedExpression(role))
      case None => childDomain.evaluate(actual.default).map { value =>
        ElaborationIntegerExpression(value.toString, value, value, value, Vector.empty)
      }
    }
  }

  def expressionOf(component: Component, value: BaseType): Option[ElaborationIntegerExpression] =
    sourceWidthOf(component, value).orElse(ParameterizedWidth.expressionOf(value))

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val widths = ArrayBuffer.empty[ElaborationIntegerExpression]
    component.dslBody.walkLeafStatements {
      case value: BaseType => expressionOf(component, value).foreach(widths += _)
      case _ =>
    }
    val values = widths.flatMap(_.parameters)
    values.groupBy(_.name).toVector.sortBy(_._1).collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting declarations on component '${component.definitionName}'",
        widths.find(_.parameters.exists(_.name == name)).flatMap(_.sourceLocation))
    }
    val roots = widths.flatMap(_.completedParameterRoots).foldLeft(
      Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root) => known :+ root
    }
    roots.groupBy(_.name).toVector.sortBy(_._1).collectFirst {
      case (name, declarations) if declarations.size > 1 => name
    }.foreach { name =>
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
        s"component '${component.definitionName}' combines independently sourced declarations for parameter '$name'",
        roots.find(_.name == name).flatMap(_.sourceLocation))
    }
    values.distinct.sortBy(_.name).toVector
  }
}
