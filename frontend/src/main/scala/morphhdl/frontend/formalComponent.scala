package morphhdl.frontend

import spinal.core.{Component, Data, ExternalAnalyzedNativeIntFormalizationPublisher}

/** Explicit external boundary for a native component constructor whose
  * geometry argument remains an ordinary Scala `Int`.
  *
  * The constructor receives only the checked concrete witness. The caller then
  * selects the exact child IO Data regions controlled by that argument. Those
  * regions retain one definition-side formal plus this instance's symbolic
  * parent-side actual by object identity. Unselected equal-width objects remain
  * concrete; width equality is validation only and is never a discovery key.
  *
  * This adapter establishes identity and lifetime only. It does not recover
  * Scala branches discarded by the concrete witness.
  */
object formalComponent {
  private val DefaultFormalNativeIntMaximum = BigInt(4096)

  /** Use MorphHDL's portable default positive packed-geometry domain. */
  def apply[C <: Component](
      actual: HdlInt,
      name: String
  )(constructor: Int => C)(geometry: C => Iterable[Data])(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): C =
    apply(
      actual,
      name,
      minimum = BigInt(1),
      maximum = DefaultFormalNativeIntMaximum
    )(constructor)(geometry)

  /** Use an explicitly bounded native-`Int` formal domain. */
  def apply[C <: Component](
      actual: HdlInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(constructor: Int => C)(geometry: C => Iterable[Data])(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): C =
    build(
      actual,
      name,
      minimum,
      maximum,
      constructor,
      Some(geometry)
    )

  /** Retain one exact component-level formal whose native `Int` controls
    * storage or structural choices but is not itself a packed child-port width.
    * Internal symbolic metadata must still prove the definition dependency;
    * this method supplies only the exact formal-to-actual hierarchy binding.
    */
  def parameter[C <: Component](
      actual: HdlInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(constructor: Int => C)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): C =
    build(
      actual,
      name,
      minimum,
      maximum,
      constructor,
      None
    )

  private def build[C <: Component](
      actual: HdlInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt,
      constructor: Int => C,
      geometry: Option[C => Iterable[Data]]
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): C = {
    val origin = SourceOrigin.capture
    if (constructor eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-COMPONENT-CONSTRUCTOR-NULL",
        s"formalComponent slot '$name' requires one non-null native constructor",
        origin
      )
    }
    geometry.foreach { selector =>
      if (selector eq null) {
        FrontendException.failAt(
          "MORPH-FRONTEND-FORMAL-COMPONENT-SELECTOR-NULL",
          s"formalComponent slot '$name' requires one non-null exact-geometry selector",
          origin
        )
      }
    }

    val parent = Option(Component.current).getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISSING",
        s"formalComponent slot '$name' must execute inside one active parent Component",
        origin
      )
    }
    val analyzed = StructuralExpressionBridge.analyzedWidth(
      actual,
      s"formalComponent '$name' native constructor argument",
      sourceLocation = Some(origin.rendered)
    )
    val definitionExpression = HdlInt.provisionalFormalExpression(
      actual = analyzed.expression,
      name = name,
      minimum = minimum,
      maximum = maximum,
      origin = origin
    )
    val provisionalFormal = definitionExpression.parameters match {
      case Vector(formal)
          if definitionExpression.verilog == formal.name &&
            definitionExpression.default == formal.default &&
            definitionExpression.minimum == formal.minimum &&
            definitionExpression.maximum == formal.maximum =>
        formal
      case _ =>
        FrontendException.failAt(
          "MORPH-FRONTEND-FORMAL-PARAMETER-PROVISIONAL-SCHEMA-MISMATCH",
          s"formalComponent slot '$name' did not retain one direct provisional formal",
          origin
        )
    }
    val capture = ExternalAnalyzedNativeIntFormalizationPublisher.captureComponent(
      analyzed = analyzed,
      parent = parent,
      formal = provisionalFormal,
      geometry = geometry.isDefined,
      callSite = origin.rendered,
      valueOrigin = actual.origin.rendered
    ) {
      constructor(analyzed.expression.default.toInt)
    }
    val component = capture.result
    if (component == null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        s"formalComponent slot '$name' constructor returned null",
        origin
      )
    }
    if (actual.formalBinding.nonEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-PARAMETER-NESTED",
        s"formal parameter '$name' cannot use another component-definition formal as its instance actual",
        origin
      )
    }
    geometry match {
      case Some(selector) =>
        ExternalAnalyzedNativeIntFormalizationPublisher.publishComponent(
          capture,
          selector(component)
        )
      case None =>
        ExternalAnalyzedNativeIntFormalizationPublisher.publishComponentParameter(
          capture
        )
    }
  }
}
