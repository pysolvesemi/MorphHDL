package spinal.core

import scala.collection.mutable.ArrayBuffer

import spinal.core.internals._

/** Concrete witness plus bounded symbolic word-count expression for Mem. */
final case class ParameterizedMemoryDepth(
    value: Int,
    expression: ElaborationIntegerExpression,
    sourceLocation: Option[String] = None
)

object ParameterizedMemoryDepth {

  /** Build public native-memory metadata only through the typed exact-domain
    * validator. The case-class constructor remains source-compatible, while
    * every registry consumer independently revalidates even hand-built values.
    */
  def checked(
      depth: ElabInt,
      role: String = "symbolic native memory depth"
  ): ParameterizedMemoryDepth =
    ParameterizedMemory.depthOf(depth, role)
}

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
    val expression = depth.authoritativeProjectedExpression(
      role,
      failureCode = "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID",
      requireProjectedExactExtrema = true
    )
    if (expression.parameters.isEmpty) {
      if (
        expression.default != BigInt(depth.witness) ||
        expression.minimum != expression.default ||
        expression.maximum != expression.default ||
        expression.default < 1 || !expression.default.isValidInt
      ) {
        fail(
          "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID",
          s"$role '${expression.verilog}' must retain one positive Int-sized literal witness ${depth.witness}",
          expression.sourceLocation
        )
      }
      return ParameterizedMemoryDepth(
        value = depth.witness,
        expression = expression,
        sourceLocation = expression.sourceLocation
      )
    }
    val exact = expression.exactDomain.getOrElse {
      fail(
        "SPINAL-ELAB-MEMORY-DEPTH-EXACT-DOMAIN-MISSING",
        s"$role '${expression.verilog}' must retain complete exact-domain evidence before typed Mem construction",
        expression.sourceLocation
      )
    }
    val evaluations = ElabInt.activeDomainEvaluations(
      exact,
      role,
      expression.sourceLocation
    )
    val evaluatedDepths = evaluations.map(_._2)
    val exactRoot = expression.completedParameterRoots match {
      case Vector(root) => root eq exact.root
      case _            => false
    }
    if (
      expression.default != BigInt(depth.witness) ||
      expression.minimum < 1 ||
      expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue) ||
      !exactRoot || evaluatedDepths.isEmpty ||
      evaluatedDepths.exists(value => value < 1 || !value.isValidInt) ||
      evaluatedDepths.min != expression.minimum ||
      evaluatedDepths.max != expression.maximum ||
      !evaluatedDepths.contains(expression.default)
    ) {
      fail(
        "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID",
        s"$role '${expression.verilog}' must retain one exact public parameter root, a positive finite Int-sized domain, and witness ${depth.witness}",
        expression.sourceLocation
      )
    }
    ParameterizedMemoryDepth(
      value = depth.witness,
      expression = expression,
      sourceLocation = expression.sourceLocation
    )
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

    val leaves = memory.wordTypeLeaves
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
      memory.wordTypeLeaves,
      depth.sourceLocation.orElse(depth.expression.sourceLocation)
    )
    retainMetadata(
      memory,
      ParameterizedMemoryMetadata(
        depth.expression,
        elementWidth,
        depth.sourceLocation
          .orElse(depth.expression.sourceLocation)
          .orElse(elementWidth.sourceLocation)
      )
    )
    memory
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

  private def retainMetadata(
      memory: Mem[_],
      metadata: ParameterizedMemoryMetadata
  ): Unit = {
    if (memory.getTag(classOf[ParameterizedMemoryTag]).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries symbolic geometry metadata",
        metadata.sourceLocation
      )
    }
    memory.addTag(ParameterizedMemoryTag(metadata))
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

  /** Typed depth retained by one native Mem. Ordinary concrete memories keep
    * their existing Scala-Int construction and are represented by a literal.
    */
  private[core] def depthExpressionOf(memory: Mem[_]): ElabInt = {
    if (memory == null)
      throw new IllegalArgumentException("typed memory must not be null")
    metadataOf(memory)
      .map(metadata => ElabInt.fromExpression(metadata.depth))
      .getOrElse(ElabInt.literal(memory.wordCount))
  }

  /** Portable positive address geometry for a native Mem. */
  private[core] def addressWidthOf(memory: Mem[_]): ElabInt =
    metadataOf(memory) match {
      case Some(metadata) =>
        withRetainedDepthDomain(metadata) {
          ElabInt.fromExpression(metadata.depth).addressWidth
        }
      case None => depthExpressionOf(memory).addressWidth
    }

  /** Concrete width consumed only by ordinary native Mem port validation.
    * Symbolic metadata remains authoritative and is not replaced by this
    * reviewed elaboration witness.
    */
  private[core] def nativePortAddressWidthOf(memory: Mem[_]): Int =
    metadataOf(memory) match {
      case Some(metadata) =>
        withRetainedDepthDomain(metadata) {
          ElabInt.fromExpression(metadata.depth).addressWidth.witness
        }
      case None => memory.addressWidth
    }

  /** Parameter-preserving address geometry for one mixed-width native port.
    *
    * The ordinary Mem path deliberately returns None so its historical
    * getAddressWidth and normalization formulas remain byte-for-byte
    * authoritative.  A tagged Mem instead derives the port word count from
    * the retained depth expression before taking its positive address width;
    * adding independent logarithms is not equivalent for non-power-of-two
    * depths or aspect ratios.
    */
  private[core] def portAddressWidthOf(
      memory: Mem[_],
      aspectRatio: Int
  ): Option[ElabInt] =
    metadataOf(memory).map { metadata =>
      validateAspectRatio(metadata, aspectRatio)
      withRetainedDepthDomain(metadata) {
        portAddressWidthExpression(metadata, aspectRatio)
      }
    }

  /** Audited concrete witness for ordinary native Mem port normalization.
    * The witness is consumed before the retained owner domain is released.
    */
  private[core] def portAddressWidthWitnessOf(
      memory: Mem[_],
      aspectRatio: Int
  ): Option[Int] =
    metadataOf(memory).map { metadata =>
      validateAspectRatio(metadata, aspectRatio)
      withRetainedDepthDomain(metadata) {
        portAddressWidthExpression(metadata, aspectRatio).witness
      }
    }

  private def portAddressWidthExpression(
      metadata: ParameterizedMemoryMetadata,
      aspectRatio: Int
  ): ElabInt =
    (ElabInt.fromExpression(metadata.depth) *
      ElabInt.literal(aspectRatio)).addressWidth

  private def validateAspectRatio(
      metadata: ParameterizedMemoryMetadata,
      aspectRatio: Int
  ): Unit =
    if (aspectRatio < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ASPECT-RATIO-INVALID",
        s"native typed memory port aspect ratio $aspectRatio must be positive",
        metadata.sourceLocation
      )
    }

  private def withRetainedDepthDomain[T](
      metadata: ParameterizedMemoryMetadata
  )(body: => T): T =
    metadata.depth.exactDomain match {
      case Some(domain) =>
        ElaborationDomainContext.withAdmitted(
          domain.root,
          domain.evidenceValues,
          metadata.sourceLocation.orElse(metadata.depth.sourceLocation)
        )(body)
      case None => body
    }

  /** Discover symbolic element geometry from the leaves already instantiated
    * by one ordinary native Mem. ParameterizedWidth.HardType preserves the
    * retained leaf expressions through the native HardType clone path, so no
    * second identity registry or generator evaluation is required here.
    */
  private[core] def discover(component: Component): Unit = {
    allMemoriesOf(component).foreach { memory =>
      if (metadataOf(memory).isEmpty) {
        val expressions = instantiatedElementExpressionsOf(memory)
        if (expressions.exists(_.parameters.nonEmpty)) {
          val elementWidth = discoveredElementWidthOf(
            memory,
            expressions,
            sourceLocation = None
          )
          retainMetadata(
            memory,
            ParameterizedMemoryMetadata(
              depth = literal(memory.wordCount),
              elementWidth = elementWidth,
              sourceLocation = elementWidth.sourceLocation
            )
          )
        }
      }
    }
  }

  private[core] def memoriesOf(component: Component): Vector[Mem[_]] = {
    discover(component)
    allMemoriesOf(component).filter(memory => metadataOf(memory).nonEmpty)
  }

  private def allMemoriesOf(component: Component): Vector[Mem[_]] = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] => values += memory
      case _              =>
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

  private def instantiatedElementExpressionsOf(
      memory: Mem[_]
  ): Vector[ElaborationIntegerExpression] =
    memory.wordTypeLeaves.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
    }

  /** Revalidate discovered leaf geometry before it may influence published
    * memory dimensions. Inexact bounds cannot be promoted into authoritative
    * geometry merely because their concrete witnesses match the native Mem.
    */
  private def discoveredElementWidthOf(
      memory: Mem[_],
      expressions: Vector[ElaborationIntegerExpression],
      sourceLocation: Option[String]
  ): ElaborationIntegerExpression = {
    val concreteWidths = memory._widths
    if (concreteWidths.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        "native symbolic memory element type has no flattened data leaves",
        sourceLocation
      )
    }
    if (expressions.size != concreteWidths.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        s"native memory element shape changed from ${concreteWidths.size} concrete leaves to ${expressions.size} retained HardType leaves",
        sourceLocation
      )
    }
    val checkedExpressions = expressions.zipWithIndex.map {
      case (expression, index) =>
        if (expression.generateIndex.nonEmpty) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
            s"native memory element leaf $index cannot depend on a generate index",
            sourceLocation.orElse(expression.sourceLocation)
          )
        }
        validateCompleteFiniteExpression(
          expression,
          role = s"external native-memory element leaf $index",
          missingCode =
            "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-EXACT-DOMAIN-MISSING",
          invalidCode =
            "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID"
        )
    }
    checkedExpressions.zip(concreteWidths).zipWithIndex.foreach {
      case ((expression, concreteWidth), index)
          if expression.default != BigInt(concreteWidth) ||
            expression.minimum < 1 ||
            expression.maximum < expression.minimum =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
          s"native memory element leaf $index has concrete width $concreteWidth but retained expression '${expression.verilog}' has witness ${expression.default} in [${expression.minimum}, ${expression.maximum}]",
          sourceLocation.orElse(expression.sourceLocation)
        )
      case _ =>
    }
    val elementWidth = validateCompleteFiniteExpression(
      checkedExpressions.reduce(addDiscovered),
      role = "external native-memory aggregate element width",
      missingCode =
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-EXACT-DOMAIN-MISSING",
      invalidCode =
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID"
    )
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

  private def validateCompleteFiniteExpression(
      raw: ElaborationIntegerExpression,
      role: String,
      missingCode: String,
      invalidCode: String
  ): ElaborationIntegerExpression = {
    if (raw == null)
      throw new IllegalArgumentException(s"$role expression must not be null")
    ElabInt.validateExpression(raw, role)
    if (raw.parameters.nonEmpty && raw.exactDomain.isEmpty) {
      fail(
        missingCode,
        s"$role '${raw.verilog}' must retain authoritative exact single-root evidence",
        raw.sourceLocation
      )
    }

    val domain = ElabInt.requireAuthoritativeIntegerDomain(
      raw,
      role,
      invalidCode,
      requireExactExtrema = true
    )
    val evaluated = domain
      .map(_.evaluations.map(_._2))
      .getOrElse(Vector(raw.default))
    if (evaluated.exists(_ < 1)) {
      fail(
        invalidCode,
        s"$role '${raw.verilog}' must retain a positive finite Int-sized evaluation table",
        raw.sourceLocation.orElse(domain.flatMap(_.root.sourceLocation))
      )
    }
    raw
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

  private def addDiscovered(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    (ElabInt.fromExpression(left) + ElabInt.fromExpression(right))
      .projectedExpression("external memory element width")

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
