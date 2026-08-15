package spinal.core

/**
  * Elaboration metadata for one public integer parameter used directly as a
  * packed width.
  *
  * The concrete `default` remains the width used by the ordinary SpinalHDL
  * elaboration and validation phases.  The symbolic identity is retained only
  * as metadata for an explicitly enabled parameter-aware backend.
  */
final case class ElaborationIntegerParameter(
    name: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt
)

/** A concrete SpinalHDL bit count which may also retain a direct parameter. */
final case class ParameterizedBitCount(
    value: Int,
    parameter: Option[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None
)

object ParameterizedBitCount {
  /** Convenient parameter-retaining construction for native/frontend users. */
  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation = None)

  /** Parameter-retaining construction with a diagnostic source location. */
  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation)
}

/** Internal AST marker installed by the parameter-aware UInt factory. */
private[core] final case class ParameterizedWidthTag(
    parameter: ElaborationIntegerParameter,
    sourceLocation: Option[String]
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
}

/** Read-only access to symbolic-width metadata retained on a native AST. */
object ParameterizedWidth {
  def parameterOf(data: BaseType): Option[ElaborationIntegerParameter] =
    data.getTag(classOf[ParameterizedWidthTag]).map(_.parameter)

  def sourceLocationOf(data: BaseType): Option[String] =
    data.getTag(classOf[ParameterizedWidthTag]).flatMap(_.sourceLocation)

  /** Canonical public parameter schemas referenced by a component's ports. */
  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val values = component.getOrdredNodeIo.flatMap(parameterOf)
    val conflicts = values.groupBy(_.name).collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }
    conflicts.foreach { name =>
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting declarations on component '${component.definitionName}'"
      )
    }
    values.distinct.sortBy(_.name).toVector
  }
}

/**
  * Stable failure raised when the deliberately narrow native parameterized
  * Verilog surface is exceeded.
  */
final class ParameterizedVerilogException(
    val code: String,
    val detail: String,
    val sourceLocation: Option[String] = None
) extends IllegalArgumentException(
      s"[$code] ${sourceLocation.map(_ + ": ").getOrElse("")}$detail"
    )

private[core] object ParameterizedVerilogException {
  def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    throw new ParameterizedVerilogException(code, detail, sourceLocation)
}
