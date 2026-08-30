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

/** A shared native library primitive may replace only the concrete witness
  * depth retained by its ordinary Mem after normal elaboration.
  */
private[core] final case class ParameterizedMemoryDepthOverrideTag(
    depth: ElaborationIntegerExpression,
    sourceLocation: Option[String]
) extends SpinalTag {
  override def allowMultipleInstance: Boolean = false
  override def canSymplifyHost: Boolean = true
}

/** Native symbolic-memory metadata and schema discovery. */
object ParameterizedMemory {

  /** Validate and retain one typed native memory depth. */
  private[core] def depthOf(
      depth: ElabInt,
      role: String
  ): ParameterizedMemoryDepth = {
    if (depth == null)
      throw new IllegalArgumentException("typed memory depth must not be null")
    val expression = depth.projectedExpression(role)
    if (
      expression.default != BigInt(depth.witness) ||
      expression.minimum < 1 ||
      expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID",
        s"$role '${expression.verilog}' must have a finite positive Int-sized domain and witness ${depth.witness}",
        expression.sourceLocation
      )
    }
    ParameterizedMemoryDepth(
      value = depth.witness,
      expression = expression,
      sourceLocation = expression.sourceLocation
    )
  }

  /** Retain a symbolic element shape on an ordinary fixed-depth Mem.
    *
    * Most Spinal library memories, including StreamFifo storage, intentionally
    * keep their depth as a Scala Int.  Their payload HardType can nevertheless
    * carry a public packed-width expression.  Tag only those memories whose
    * element width actually references a parameter so ordinary concrete Mem
    * construction remains indistinguishable from the upstream path.
    */
  private[core] def attachStatic[T <: Data](memory: Mem[T]): Mem[T] = {
    val leaves = memory.wordType().flatten.toVector
    val hasSymbolicElement = leaves.exists { leaf =>
      ParameterizedWidth.expressionOf(leaf).exists(_.parameters.nonEmpty)
    }
    if (!hasSymbolicElement) memory
    else {
      val elementWidth = elementWidthOf(
        memory,
        leaves,
        sourceLocation = None
      )
      attachMetadata(
        memory,
        ParameterizedMemoryMetadata(
          depth = literal(memory.wordCount),
          elementWidth = elementWidth,
          sourceLocation = elementWidth.sourceLocation
        )
      )
    }
  }

  /** Retain one bounded depth on the single native Mem owned by a library
    * component while leaving that component's ordinary algorithm authoritative.
    */
  private[spinal] def retainSingleDepth(
      component: Component,
      depth: ParameterizedMemoryDepth
  ): Unit = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] => values += memory
      case _              =>
    }
    val memories = values.distinct.toVector
    if (memories.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-LIBRARY-MEMORY-COUNT",
        s"parameterized library component '${component.definitionName}' must own exactly one native memory, found ${memories.size}",
        depth.sourceLocation
      )
    }
    retainDepth(memories.head, depth)
  }

  /** Overlay a bounded symbolic depth on an existing ordinary native Mem. */
  private[spinal] def retainDepth(
      memory: Mem[_],
      depth: ParameterizedMemoryDepth
  ): Unit = {
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
    if (memory.getTag(classOf[ParameterizedMemoryDepthOverrideTag]).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-OVERRIDE-DUPLICATE",
        "native memory already carries a retained library depth override",
        depth.sourceLocation
      )
    }

    val leaves = memory.wordType().asInstanceOf[Data].flatten.toVector
    if (leaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        "native symbolic memory element type has no flattened data leaves",
        depth.sourceLocation
      )
    }
    val elementWidth = leaves
      .map { leaf =>
        ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
      }
      .reduce(add)
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

    if (memory.getTag(classOf[ParameterizedMemoryTag]).isEmpty) {
      memory.addTag(
        ParameterizedMemoryTag(
          ParameterizedMemoryMetadata(
            depth = literal(memory.wordCount),
            elementWidth = elementWidth,
            sourceLocation = depth.sourceLocation.orElse(elementWidth.sourceLocation)
          )
        )
      )
    }
    memory.addTag(
      ParameterizedMemoryDepthOverrideTag(
        depth.expression,
        depth.sourceLocation.orElse(depth.expression.sourceLocation)
      )
    )
  }

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

    val elementWidth = elementWidthOf(
      memory,
      memory.wordType().flatten.toVector,
      depth.sourceLocation.orElse(depth.expression.sourceLocation)
    )
    attachMetadata(
      memory,
      ParameterizedMemoryMetadata(
        depth.expression,
        elementWidth,
        depth.sourceLocation
          .orElse(depth.expression.sourceLocation)
          .orElse(elementWidth.sourceLocation)
      )
    )
  }

  private def elementWidthOf[T <: Data](
      memory: Mem[T],
      leaves: Vector[BaseType],
      sourceLocation: Option[String]
  ): ElaborationIntegerExpression = {
    if (leaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        "native symbolic memory element type has no flattened data leaves",
        sourceLocation
      )
    }
    val elementWidth = leaves
      .map { leaf =>
        ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
      }
      .reduce(add)
    if (
      elementWidth.default != BigInt(memory.getWidth) ||
      elementWidth.minimum < 1 ||
      elementWidth.maximum < elementWidth.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"native memory concrete element width ${memory.getWidth} does not match retained expression '${elementWidth.verilog}' in [${elementWidth.minimum}, ${elementWidth.maximum}]",
        sourceLocation.orElse(elementWidth.sourceLocation)
      )
    }
    elementWidth
  }

  private def attachMetadata[T <: Data](
      memory: Mem[T],
      metadata: ParameterizedMemoryMetadata
  ): Mem[T] = {
    if (memory.getTag(classOf[ParameterizedMemoryTag]).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries symbolic geometry metadata",
        metadata.sourceLocation
      )
    }
    memory.addTag(ParameterizedMemoryTag(metadata))
    memory
  }

  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = {
    val base = memory.getTag(classOf[ParameterizedMemoryTag]).map(_.metadata)
    memory.getTag(classOf[ParameterizedMemoryDepthOverrideTag]) match {
      case Some(tag) =>
        base.map { metadata =>
          metadata.copy(
            depth = tag.depth,
            sourceLocation = tag.sourceLocation.orElse(metadata.sourceLocation)
          )
        }
      case None => base
    }
  }

  private[core] def memoriesOf(component: Component): Vector[Mem[_]] = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] if metadataOf(memory).nonEmpty => values += memory
      case _                                             =>
    }
    values.distinct.toVector
  }

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val expressions = memoriesOf(component).flatMap { memory =>
      val metadata = metadataOf(memory).get
      Vector(metadata.depth, metadata.elementWidth)
    }
    ElabInt.validateParameterRootInventory(
      s"native-memory component '${component.definitionName}'",
      expressions
    )
    val referenced = expressions.flatMap(_.parameters)
    val grouped = referenced.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, schemas) if schemas.distinct.size != 1 => name
      }
      .foreach { name =>
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
    (ElabInt.fromExpression(left) + ElabInt.fromExpression(right))
      .projectedExpression("typed memory element width")

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
