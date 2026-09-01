package spinal.core

import morphhdl.frontend.AnalyzedFrontendInteger

/** Opaque completed native-Int constructor capture. Only the analyzer-sealed
  * publisher below can construct one; callers may observe the untouched native
  * result but cannot replace its owner, expressions, boundary kind or shadow
  * provenance before final publication.
  */
final class ExternalAnalyzedNativeIntFormalCapture[A] private[core] (
    val result: A,
    private[core] val kind: ExternalAnalyzedNativeIntFormalizationPublisher.Kind,
    private[core] val shadow: ExternalNativeIntShadowCapture[A],
    private[core] val formalBinding: Option[ExternalFormalParameterBinding],
    private[core] val formal: Option[ElaborationIntegerParameter]
) {
  private[this] var published = false

  private[core] def publish[B](
      expectedKind: ExternalAnalyzedNativeIntFormalizationPublisher.Kind
  )(body: => B): B = synchronized {
    if (published || !(kind eq expectedKind)) {
      ParameterizedVerilogException.fail(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-PUBLICATION-MISMATCH",
        "native Int formalization capture was consumed or presented to a foreign publication kind",
        Option(shadow.token.callSite).filter(_.nonEmpty)
      )
    }
    val result = body
    published = true
    result
  }
}

/** The sole bridge from one analyzer-sealed HdlInt expression to the package-
  * internal native formalization and shadow registries. No entry point accepts
  * a raw EIE and turns it into a boundary capability.
  */
object ExternalAnalyzedNativeIntFormalizationPublisher {
  private[core] sealed abstract class Kind
  private[core] case object Region extends Kind
  private[core] case object ComponentGeometry extends Kind
  private[core] case object ComponentParameter extends Kind

  def captureRegion[A <: Data](
      analyzed: AnalyzedFrontendInteger,
      owner: Component,
      formalBinding: Option[ExternalFormalParameterBinding],
      callSite: String,
      valueOrigin: String,
      argumentName: String
  )(body: => A): ExternalAnalyzedNativeIntFormalCapture[A] = {
    requireReference(owner, "formalRegion owner", callSite)
    requireBindingOption(formalBinding, callSite)
    val expression = authoritativeExpression(analyzed, callSite)
    val token = ExternalNativeIntFormalizationToken.region(
      owner,
      expression,
      callSite,
      valueOrigin,
      role = "formalRegion"
    )
    val shadow = ExternalNativeIntShadowRegistry.capture(
      owner,
      expression,
      token,
      argumentName
    )(body)
    new ExternalAnalyzedNativeIntFormalCapture[A](
      result = shadow.result,
      kind = Region,
      shadow = shadow,
      formalBinding = formalBinding,
      formal = None
    )
  }

  def publishRegion[A <: Data](
      capture: ExternalAnalyzedNativeIntFormalCapture[A]
  ): A = {
    requireReference(capture, "formalRegion capture", None)
    capture.publish(Region) {
      if (capture.result == null) {
        ParameterizedVerilogException.fail(
          "MORPH-FRONTEND-FORMAL-REGION-RESULT-NULL",
          "formalRegion constructor returned null",
          Option(capture.shadow.token.callSite).filter(_.nonEmpty)
        )
      }
      ExternalNativeIntFormalizationRegistry.attachRegionAtomically(
        owner = capture.shadow.owner,
        data = capture.result,
        expression = capture.shadow.expression,
        formalBinding = capture.formalBinding,
        capture = capture.shadow
      )
    }
  }

  def captureComponent[C <: Component](
      analyzed: AnalyzedFrontendInteger,
      parent: Component,
      formal: ElaborationIntegerParameter,
      geometry: Boolean,
      callSite: String,
      valueOrigin: String
  )(body: => C): ExternalAnalyzedNativeIntFormalCapture[C] = {
    requireReference(parent, "formalComponent parent", callSite)
    requireReference(formal, "formalComponent declaration", callSite)
    val actual = authoritativeExpression(analyzed, callSite)
    val definition = ElabInt
      .directParameter(formal, Some(callSite))
      .projectedExpression(s"formalComponent '${formal.name}' definition boundary")
    val kind = if (geometry) ComponentGeometry else ComponentParameter
    val token =
      if (geometry)
        ExternalNativeIntFormalizationToken.componentGeometry(
          parent,
          actual,
          definition,
          callSite,
          valueOrigin,
          s"formalComponent(${formal.name})"
        )
      else
        ExternalNativeIntFormalizationToken.componentParameter(
          parent,
          actual,
          definition,
          callSite,
          valueOrigin,
          s"formalComponent.parameter(${formal.name})"
        )
    val shadow = ExternalNativeIntShadowRegistry.captureWithDefinition(
      parent,
      actual,
      definition,
      token,
      formal.name
    )(body)
    new ExternalAnalyzedNativeIntFormalCapture[C](
      result = shadow.result,
      kind = kind,
      shadow = shadow,
      formalBinding = None,
      formal = Some(formal)
    )
  }

  def publishComponent[C <: Component](
      capture: ExternalAnalyzedNativeIntFormalCapture[C],
      geometry: Iterable[Data]
  ): C = {
    requireReference(capture, "formalComponent capture", None)
    capture.publish(ComponentGeometry) {
      val component = requireComponentResult(capture)
      val binding = componentBinding(capture, component)
      ExternalNativeIntFormalizationRegistry.attachComponentAtomically(
        parent = capture.shadow.owner,
        component = component,
        geometry = geometry,
        binding = binding,
        capture = capture.shadow
      )
    }
  }

  def publishComponentParameter[C <: Component](
      capture: ExternalAnalyzedNativeIntFormalCapture[C]
  ): C = {
    requireReference(capture, "formalComponent.parameter capture", None)
    capture.publish(ComponentParameter) {
      val component = requireComponentResult(capture)
      val binding = componentBinding(capture, component)
      ExternalNativeIntFormalizationRegistry.attachComponentParameterAtomically(
        parent = capture.shadow.owner,
        component = component,
        binding = binding,
        capture = capture.shadow
      )
    }
  }

  private def authoritativeExpression(
      analyzed: AnalyzedFrontendInteger,
      callSite: String
  ): ElaborationIntegerExpression = {
    requireReference(analyzed, "analyzed native Int expression", callSite)
    analyzed.requireAnalyzerAuthentication()
    val authoritative = analyzed.singleRootEvaluations match {
      case Some(evaluations) =>
        ElabInt
          .fromSingleRootExpression(
            analyzed.expression,
            evaluations,
            ExternalAnalyzedFrontendPermitIssuer.singleRoot(analyzed)
          )
      case None if analyzed.expression.parameters.isEmpty =>
        val expression = analyzed.expression
        if (
          expression.generateIndex.nonEmpty ||
          expression.minimum != expression.default ||
          expression.maximum != expression.default
        ) {
          ParameterizedVerilogException.fail(
            "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-EXACT-DOMAIN-MISSING",
            s"analyzed native Int expression '${expression.verilog}' has no exact finite symbolic domain",
            expression.sourceLocation.orElse(Option(callSite).filter(_.nonEmpty))
          )
        }
        ElabInt.fromBigInt(expression.default)
      case None =>
        ParameterizedVerilogException.fail(
          "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-EXACT-DOMAIN-MISSING",
          s"analyzed native Int expression '${analyzed.expression.verilog}' has no exhaustive single-root evaluation table",
          analyzed.expression.sourceLocation.orElse(Option(callSite).filter(_.nonEmpty))
        )
    }
    authoritative.projectedExpression(
      "analyzed native Int formalization boundary"
    )
  }

  private def componentBinding[C <: Component](
      capture: ExternalAnalyzedNativeIntFormalCapture[C],
      component: C
  ): ExternalFormalParameterBinding = {
    val formal = capture.formal.getOrElse {
      throw new IllegalArgumentException(
        "formalComponent capture lost its exact declaration"
      )
    }
    val ownerClassName = component.getClass.getName
    ExternalFormalParameterBinding(
      formal = formal,
      actual = capture.shadow.expression,
      declarationKey = s"external-native-int::$ownerClassName::${formal.name}",
      ownerClassName = ownerClassName,
      sourceLocation = Option(capture.shadow.token.callSite).filter(_.nonEmpty)
    )
  }

  private def requireComponentResult[C <: Component](
      capture: ExternalAnalyzedNativeIntFormalCapture[C]
  ): C = {
    val component = capture.result
    if (component == null) {
      ParameterizedVerilogException.fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        s"${capture.shadow.token.role} constructor returned null",
        Option(capture.shadow.token.callSite).filter(_.nonEmpty)
      )
    }
    component
  }

  private def requireBindingOption(
      binding: Option[ExternalFormalParameterBinding],
      callSite: String
  ): Unit =
    if (binding == null || binding.exists(_ == null))
      throw new IllegalArgumentException(
        s"formalRegion binding option must retain non-null values at $callSite"
      )

  private def requireReference(
      value: AnyRef,
      role: String,
      callSite: String
  ): Unit =
    requireReference(value, role, Option(callSite).filter(_.nonEmpty))

  private def requireReference(
      value: AnyRef,
      role: String,
      sourceLocation: Option[String]
  ): Unit =
    if (value eq null)
      ParameterizedVerilogException.fail(
        "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-TARGET-MISSING",
        s"$role must not be null",
        sourceLocation
      )
}
