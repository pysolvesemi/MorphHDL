package morphhdl.frontend

import spinal.core.{Component, Data, ExternalAnalyzedNativeIntFormalizationPublisher}

/** Explicit external boundary for one native `Int`-controlled Data region.
  *
  * The supplied constructor receives only the checked concrete `Int` witness.
  * After the untouched native constructor returns, MorphHDL attaches the full
  * symbolic expression to that exact returned Data object and its packed
  * leaves. No concrete-value or emitted-name lookup is performed.
  *
  * This adapter establishes geometry identity and lifetime only. The
  * constructor must not rely on unselected Scala control-flow alternatives;
  * native symbolic branch recovery is introduced by later roadmap increments.
  */
object formalRegion {
  def apply[T <: Data](actual: HdlInt)(constructor: Int => T)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): T = {
    val origin = SourceOrigin.capture
    if (constructor eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-REGION-CONSTRUCTOR-NULL",
        "formalRegion requires one non-null native Int constructor",
        origin
      )
    }

    val owner = Option(Component.current).getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-FORMAL-REGION-OWNER-MISSING",
        "formalRegion must execute inside one active Component definition",
        origin
      )
    }
    val analyzed = StructuralExpressionBridge.analyzedWidth(
      actual,
      "formalRegion native Int geometry",
      sourceLocation = Some(origin.rendered)
    )
    val capture = ExternalAnalyzedNativeIntFormalizationPublisher.captureRegion(
      analyzed = analyzed,
      owner = owner,
      formalBinding = actual.formalBinding,
      callSite = origin.rendered,
      valueOrigin = actual.origin.rendered,
      argumentName = "regionArgument"
    ) {
      constructor(analyzed.expression.default.toInt)
    }
    ExternalAnalyzedNativeIntFormalizationPublisher.publishRegion(capture)
  }
}
