package spinal.core

import scala.collection.mutable.ArrayBuffer

import spinal.core.internals._

/** Concrete witness plus bounded symbolic word-count expression for Mem. */
final case class ParameterizedMemoryDepth(
    value: Int,
    expression: ElaborationIntegerExpression,
    sourceLocation: Option[String] = None
)

/** Internal immutable contract retained on one ordinary Spinal Mem. */
private[core] final case class ParameterizedMemoryMetadata(
    depth: ElaborationIntegerExpression,
    elementWidth: ElaborationIntegerExpression,
    sourceLocation: Option[String]
)

private[core] final case class ParameterizedMemoryTag(
    metadata: ParameterizedMemoryMetadata
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  override def canSymplifyHost: Boolean = true
}

/** Native symbolic-memory metadata and schema discovery. */
object ParameterizedMemory {
  private[core] def attach[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
    if (depth.value < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-NOT-POSITIVE",
        s"native memory depth witness ${depth.value} must be positive",
        depth.sourceLocation
      )
    }
    if (depth.expression.generateIndex.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-GENERATE-DEPENDENT",
        "native memory depth cannot depend on a generate index",
        depth.sourceLocation
      )
    }
    if (
      depth.expression.default != BigInt(depth.value) ||
      depth.expression.minimum < 1 ||
      depth.expression.maximum < depth.expression.minimum ||
      depth.expression.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"native memory depth '${depth.expression.verilog}' must have witness ${depth.value} and a finite positive Int-sized domain",
        depth.sourceLocation.orElse(depth.expression.sourceLocation)
      )
    }

    val leaves = memory.wordType().flatten.toVector
    if (leaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        "native symbolic memory element type has no flattened data leaves",
        depth.sourceLocation
      )
    }
    val elementWidth = leaves.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
    }.reduce(add)
    if (
      elementWidth.default != BigInt(memory.getWidth) ||
      elementWidth.minimum < 1 ||
      elementWidth.maximum < elementWidth.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"native memory concrete element width ${memory.getWidth} does not match retained expression '${elementWidth.verilog}' in [${elementWidth.minimum}, ${elementWidth.maximum}]",
        depth.sourceLocation.orElse(elementWidth.sourceLocation)
      )
    }
    if (memory.getTag(classOf[ParameterizedMemoryTag]).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries symbolic-depth metadata",
        depth.sourceLocation
      )
    }
    memory.addTag(
      ParameterizedMemoryTag(
        ParameterizedMemoryMetadata(
          depth.expression,
          elementWidth,
          depth.sourceLocation.orElse(depth.expression.sourceLocation)
        )
      )
    )
    memory
  }

  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] =
    memory.getTag(classOf[ParameterizedMemoryTag]).map(_.metadata)

  private[core] def memoriesOf(component: Component): Vector[Mem[_]] = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] if metadataOf(memory).nonEmpty => values += memory
      case _ =>
    }
    values.distinct.toVector
  }

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val referenced = memoriesOf(component).flatMap { memory =>
      val metadata = metadataOf(memory).get
      metadata.depth.parameters ++ metadata.elementWidth.parameters
    }
    val grouped = referenced.groupBy(_.name)
    grouped.collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }.foreach { name =>
      val source = memoriesOf(component).iterator
        .flatMap(metadataOf)
        .find(metadata =>
          (metadata.depth.parameters ++ metadata.elementWidth.parameters)
            .exists(_.name == name)
        )
        .flatMap(_.sourceLocation)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting native-memory declarations on component '${component.definitionName}'",
        source
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def literal(value: Int): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = value.toString,
      default = BigInt(value),
      minimum = BigInt(value),
      maximum = BigInt(value),
      parameters = Vector.empty
    )

  private def add(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = s"(${left.verilog} + ${right.verilog})",
      default = left.default + right.default,
      minimum = left.minimum + right.minimum,
      maximum = left.maximum + right.maximum,
      parameters = (left.parameters ++ right.parameters).distinct.sortBy(_.name),
      sourceLocation = left.sourceLocation.orElse(right.sourceLocation)
    )

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
