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

/** Internal AST marker installed by a parameter-aware bit-vector factory. */
private[core] final case class ParameterizedWidthTag(
    parameter: ElaborationIntegerParameter,
    sourceLocation: Option[String]
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  // Metadata must not change ordinary SpinalHDL simplification. Parameterized
  // designs retain named data-shape leaves; the tag is not a keep directive.
  override def canSymplifyHost: Boolean = true
}

/** Read-only access to symbolic-width metadata retained on a native AST. */
object ParameterizedWidth {
  /**
    * Construct a concrete bit vector while retaining its symbolic packed
    * width.  All supported bit-vector factories use this single path so the
    * metadata semantics cannot diverge between Bits, UInt and SInt.
    */
  private[core] def attach[T <: BitVector](
      data: T,
      width: ParameterizedBitCount
  ): T = {
    data.setWidth(width.value)
    width.parameter.foreach { parameter =>
      data.addTag(ParameterizedWidthTag(parameter, width.sourceLocation))
    }
    data
  }

  /** Preserve only symbolic-width metadata when a native leaf is cloned. */
  private[core] def copy(from: BaseType, to: BaseType): Unit =
    from.getTag(classOf[ParameterizedWidthTag]).foreach { tag =>
      to.addTag(ParameterizedWidthTag(tag.parameter, tag.sourceLocation))
    }

  def parameterOf(data: BaseType): Option[ElaborationIntegerParameter] =
    data.getTag(classOf[ParameterizedWidthTag]).map(_.parameter)

  def sourceLocationOf(data: BaseType): Option[String] =
    data.getTag(classOf[ParameterizedWidthTag]).flatMap(_.sourceLocation)

  /** Symbolically sized leaves in deterministic data-model order. */
  def leavesOf(data: Data): Vector[BaseType] =
    data.flatten.filter(parameterOf(_).nonEmpty).toVector

  /**
    * Canonical public parameter schemas referenced anywhere in a component.
    * This includes internal signals and registers as well as flattened ports.
    */
  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val leaves = scala.collection.mutable.ArrayBuffer.empty[BaseType]
    component.dslBody.walkLeafStatements {
      case baseType: BaseType if parameterOf(baseType).nonEmpty => leaves += baseType
      case _ =>
    }
    val tagged = leaves.flatMap { baseType =>
      parameterOf(baseType).map(parameter => baseType -> parameter)
    }
    val values = tagged.map(_._2)
    val conflicts = values.groupBy(_.name).collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }
    conflicts.foreach { name =>
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting declarations on component '${component.definitionName}'",
        tagged.find(_._2.name == name).flatMap { case (baseType, _) =>
          sourceLocationOf(baseType)
        }
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
