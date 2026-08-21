package morphhdl.frontend

import spinal.core.{
  Component,
  Data,
  ExternalNativeIntFormalizationRegistry,
  ExternalNativeIntFormalizationToken
}

/**
  * Explicit external boundary for one native `Int`-controlled Data region.
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
    val expression = HdlInt.nativeIntExpression(
      actual,
      "formalRegion native Int geometry",
      origin
    )
    val result = constructor(expression.default.toInt)
    ExternalNativeIntFormalizationRegistry.attachRegion(
      owner = owner,
      data = result,
      expression = expression,
      token = ExternalNativeIntFormalizationToken(
        callSite = origin.rendered,
        valueOrigin = actual.origin.rendered,
        role = "formalRegion"
      ),
      formalBinding = actual.formalBinding
    )
  }
}
