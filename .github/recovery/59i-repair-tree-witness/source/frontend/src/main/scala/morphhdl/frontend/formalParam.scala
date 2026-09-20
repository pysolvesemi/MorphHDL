package morphhdl.frontend

/**
  * Explicit deterministic component-definition formal parameter constructor.
  *
  * A lower-case callable object deliberately preserves the public
  * `formalParam(actual, "WIDTH")` spelling while keeping the implementation
  * independent of the shared package object used by other parallel
  * provenance increments.
  */
object formalParam {
  private val DefaultFormalPackedWidthMaximum = BigInt(4096)

  /**
    * Declare one deterministic child-definition formal while retaining the
    * supplied expression as this child instance's parent-scope actual.
    *
    * The short form uses MorphHDL's portable default packed-width domain
    * `[1, 4096]`, matching the default SpinalConfig bit-vector limit. Use the
    * bounded overload when the child contract is intentionally narrower.
    */
  def apply(actual: HdlInt, name: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlInt =
    HdlInt.formal(
      actual,
      name,
      minimum = BigInt(1),
      maximum = DefaultFormalPackedWidthMaximum,
      origin = SourceOrigin.capture
    )

  /** Explicitly bounded child-definition formal parameter. */
  def apply(
      actual: HdlInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
    HdlInt.formal(actual, name, minimum, maximum, SourceOrigin.capture)
}
