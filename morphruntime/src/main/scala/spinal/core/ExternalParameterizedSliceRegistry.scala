package spinal.core

import scala.collection.mutable.ArrayBuffer

/**
  * Exact native ranged-access provenance retained outside SpinalHDL sources.
  *
  * The ordinary native graph keeps its concrete witness range. MorphHDL records
  * the exact source/result object identities together with the symbolic offset
  * and width, then rewrites only the published Verilog-2001 artifact.
  */
final case class ExternalParameterizedSliceRecord(
    source: BitVector,
    result: BitVector,
    offset: ElaborationIntegerExpression,
    width: ElaborationIntegerExpression,
    sourceLocation: Option[String]
)

object ExternalParameterizedSliceRegistry {
  private object StorageKey

  private final class Storage {
    val slices = ArrayBuffer.empty[ExternalParameterizedSliceRecord]
  }

  def attach[T <: BitVector](
      source: T,
      result: T,
      offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): T = {
    if (source == null)
      throw new IllegalArgumentException("parameterized slice source must not be null")
    if (result == null)
      throw new IllegalArgumentException("parameterized slice result must not be null")
    if (offset == null)
      throw new IllegalArgumentException("parameterized slice offset must not be null")
    if (width == null)
      throw new IllegalArgumentException("parameterized slice width must not be null")

    val location = sourceLocation.orElse(offset.sourceLocation).orElse(width.sourceLocation)
    validateExpression(offset, "slice offset", location)
    validateExpression(width, "slice width", location)
    mergeParameters(offset.parameters ++ width.parameters, location)

    if (offset.parameters.isEmpty && width.parameters.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-NOT-SYMBOLIC",
        "a retained native slice must depend on at least one public parameter",
        location
      )
    }
    if (offset.minimum < 0 || width.minimum < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-INVALID",
        s"slice offset '${offset.verilog}' and width '${width.verilog}' require complete domains with offset >= 0 and width >= 1",
        location
      )
    }
    if (
      offset.default < 0 || width.default < 1 ||
      offset.default + width.default > BigInt(source.getBitsWidth)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-WITNESS-OUT-OF-RANGE",
        s"slice witness [${offset.default} +: ${width.default}] is outside the ${source.getBitsWidth}-bit native source",
        location
      )
    }
    if (width.default != BigInt(result.getBitsWidth)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-WITNESS-WIDTH-MISMATCH",
        s"slice width default ${width.default} disagrees with native result width ${result.getBitsWidth}",
        location
      )
    }

    val owner = ownerOf(source, result, location)
    val direct = width.parameters match {
      case Vector(parameter) if width.verilog.trim == parameter.name => Some(parameter)
      case _                                                         => None
    }
    ParameterizedWidth.attach(
      result,
      ParameterizedBitCount(
        value = result.getBitsWidth,
        parameter = direct,
        sourceLocation = location,
        expression = Some(width)
      )
    )

    val incoming = ExternalParameterizedSliceRecord(
      source,
      result,
      offset,
      width,
      location
    )
    val storage = storageOf(owner)
    storage.slices.find(record => record.result eq result) match {
      case Some(existing) if !equivalent(existing, incoming) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SLICE-RESULT-CONFLICT",
          "one exact native ranged-access result received conflicting symbolic ranges",
          location
        )
      case Some(_) =>
      case None    => storage.slices += incoming
    }
    result
  }

  def slicesOf(component: Component): Vector[ExternalParameterizedSliceRecord] =
    storageOption(component)
      .toVector
      .flatMap(_.slices)
      .toVector
      .sortBy { record =>
        (
          Option(record.source.getName()).getOrElse(""),
          record.offset.verilog,
          record.width.verilog,
          record.sourceLocation.getOrElse("")
        )
      }

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val parameters = slicesOf(component).flatMap { record =>
      record.offset.parameters ++ record.width.parameters
    }
    mergeParameters(parameters, None)
  }

  def hasSlices(component: Component): Boolean = slicesOf(component).nonEmpty

  private def ownerOf(
      source: BitVector,
      result: BitVector,
      sourceLocation: Option[String]
  ): Component = {
    val current = Option(Component.current)
    val sourceOwner = Option(source.component)
    val resultOwner = Option(result.component)
    val owner = sourceOwner.orElse(resultOwner).orElse(current).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-OWNER-MISSING",
        "a retained native slice has no owning Component",
        sourceLocation
      )
    }
    sourceOwner.filterNot(_ eq owner).foreach { _ =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-OWNER-CONFLICT",
        "slice source and result belong to different Components",
        sourceLocation
      )
    }
    resultOwner.filterNot(_ eq owner).foreach { _ =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-OWNER-CONFLICT",
        "slice source and result belong to different Components",
        sourceLocation
      )
    }
    owner
  }

  private def equivalent(
      left: ExternalParameterizedSliceRecord,
      right: ExternalParameterizedSliceRecord
  ): Boolean =
    (left.source eq right.source) &&
      (left.result eq right.result) &&
      left.offset == right.offset &&
      left.width == right.width &&
      left.sourceLocation == right.sourceLocation

  private def validateExpression(
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    if (expression.verilog == null || expression.verilog.trim.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-EXPRESSION-INVALID",
        s"$role has no portable Verilog expression",
        sourceLocation
      )
    }
    if (
      expression.minimum > expression.maximum ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-INVALID",
        s"$role '${expression.verilog}' has default ${expression.default} outside [${expression.minimum}, ${expression.maximum}]",
        sourceLocation
      )
    }
  }

  private def mergeParameters(
      parameters: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Vector[ElaborationIntegerParameter] = {
    val grouped = parameters.groupBy(_.name)
    grouped.collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting declarations in retained native slices",
        sourceLocation
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def storageOf(component: Component): Storage =
    component.userCache
      .getOrElseUpdate(StorageKey, new Storage)
      .asInstanceOf[Storage]

  private def storageOption(component: Component): Option[Storage] =
    if (component == null) None
    else component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
