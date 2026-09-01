package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Weak key with native Mem object-identity semantics. */
private[core] final class ExternalMemoryIdentityRef(
    value: Mem[_],
    queue: ReferenceQueue[Mem[_]]
) extends WeakReference[Mem[_]](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalMemoryIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak key with native HardType object-identity semantics. */
private[core] final class ExternalHardTypeIdentityRef(
    value: HardType[_],
    queue: ReferenceQueue[HardType[_]]
) extends WeakReference[HardType[_]](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalHardTypeIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Frontend-owned symbolic geometry retained beside an ordinary native
  * HardType. Capturing the leaf expressions at construction avoids evaluating
  * the HardType generator again after normal elaboration has completed.
  */
object ExternalParameterizedHardTypeRegistry {
  private val queue = new ReferenceQueue[HardType[_]]()
  private val retained =
    mutable.HashMap.empty[
      ExternalHardTypeIdentityRef,
      Vector[ElaborationIntegerExpression]
    ]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ExternalHardTypeIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalHardTypeIdentityRef]
    }
  }

  private def retain(
      dataType: HardType[_],
      expressions: Vector[ElaborationIntegerExpression]
  ): Unit = synchronized {
    reap()
    retained.update(
      new ExternalHardTypeIdentityRef(dataType, queue),
      expressions
    )
  }

  def create[T <: Data](dataType: => T): HardType[T] = {
    val template = dataType
    if (template == null)
      throw new IllegalArgumentException("native HardType template must not be null")
    val expressions = template.flatten.toVector.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
    }
    val hardType = ParameterizedWidth.HardType(template)
    if (expressions.exists(_.parameters.nonEmpty)) {
      retain(hardType, expressions)
    }
    hardType
  }

  private[core] def expressionsOf(
      dataType: HardType[_]
  ): Option[Vector[ElaborationIntegerExpression]] = synchronized {
    if (dataType == null) None
    else {
      reap()
      retained.get(new ExternalHardTypeIdentityRef(dataType, null))
    }
  }

  private def literal(value: Int): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = value.toString,
      default = BigInt(value),
      minimum = BigInt(value),
      maximum = BigInt(value),
      parameters = Vector.empty
    )
}

/** MorphHDL-owned native-memory geometry registry.
  *
  * Ordinary SpinalHDL Mem construction remains untouched. A MorphHDL depth
  * adapter records the bounded depth beside the concrete native Mem, while the
  * final external publication phase discovers symbolic element widths from the
  * frontend-owned HardType identity registry. Read/write ports, clocks,
  * enables and collision policies are always inspected from the native AST
  * itself.
  */
object ExternalParameterizedMemoryRegistry {
  private val queue = new ReferenceQueue[Mem[_]]()
  private val retained =
    mutable.HashMap.empty[ExternalMemoryIdentityRef, ParameterizedMemoryMetadata]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ExternalMemoryIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalMemoryIdentityRef]
    }
  }

  private def externalMetadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = synchronized {
    reap()
    retained.get(new ExternalMemoryIdentityRef(memory, null))
  }

  private def retain(
      memory: Mem[_],
      metadata: ParameterizedMemoryMetadata
  ): Unit = synchronized {
    reap()
    if (retained.contains(new ExternalMemoryIdentityRef(memory, null))) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries external symbolic geometry metadata",
        metadata.sourceLocation
      )
    }
    retained.update(new ExternalMemoryIdentityRef(memory, queue), metadata)
  }

  /** Native Mem factory followed only by external metadata association. */
  def create[T <: Data](
      wordType: HardType[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
    if (wordType == null)
      throw new IllegalArgumentException("native memory word type must not be null")
    val checkedDepth = validateDepthExpression(depth)
    attachValidated(
      spinal.core.Mem(wordType, depth.value),
      depth,
      checkedDepth
    )
  }

  /** Associate a bounded depth with an already-created ordinary native Mem. */
  def attach[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth
  ): Mem[T] = {
    if (memory == null)
      throw new IllegalArgumentException("native memory must not be null")
    val checkedDepth = validateDepth(memory, depth)
    attachValidated(
      memory,
      depth,
      checkedDepth
    )
  }

  private def attachValidated[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth,
      checkedDepth: ElaborationIntegerExpression
  ): Mem[T] = {
    if (metadataOf(memory).nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-DUPLICATE",
        "native memory already carries symbolic geometry metadata",
        depth.sourceLocation
      )
    }
    val elementWidth = elementWidthOf(
      memory,
      depth.sourceLocation.orElse(depth.expression.sourceLocation)
    )
    retain(
      memory,
      ParameterizedMemoryMetadata(
        depth = checkedDepth,
        elementWidth = elementWidth,
        sourceLocation = depth.sourceLocation
          .orElse(checkedDepth.sourceLocation)
          .orElse(elementWidth.sourceLocation)
      )
    )
    memory
  }

  /** Discover symbolic element geometry after normal elaboration and inherited
    * validation. This records no hardware statement and changes no native Mem,
    * port or algorithm.
    */
  private[core] def discover(component: Component): Unit = {
    allMemoriesOf(component).foreach { memory =>
      if (metadataOf(memory).isEmpty) {
        val symbolic = elementExpressionsOf(memory, sourceLocation = None)
          .exists(_.exists(_.parameters.nonEmpty))
        if (symbolic) {
          val elementWidth = elementWidthOf(memory, sourceLocation = None)
          retain(
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

  private[core] def metadataOf(
      memory: Mem[_]
  ): Option[ParameterizedMemoryMetadata] = {
    val external = externalMetadataOf(memory)
    val library = ParameterizedMemory.metadataOf(memory)
    (external, library) match {
      case (Some(left), Some(right))
          if !ElabInt.equivalentExpression(left.depth, right.depth) ||
            !ElabInt.equivalentExpression(left.elementWidth, right.elementWidth) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-METADATA-CONFLICT",
          "native memory carries conflicting external and library symbolic geometry",
          left.sourceLocation.orElse(right.sourceLocation)
        )
      case (Some(left), Some(right)) =>
        val normalizedDepth = left.depth.preserveExactAuthorityOn(
          left.depth.preserveProjectionOn(
            left.depth.copy(
              sourceLocation = left.depth.sourceLocation.orElse(right.depth.sourceLocation)
            ),
            "external memory depth normalization"
          ),
          "external memory depth normalization"
        )
        Some(
          left.copy(
            depth = normalizedDepth,
            sourceLocation = left.sourceLocation.orElse(right.sourceLocation)
          )
        )
      case (Some(value), None) => Some(value)
      case (None, Some(value)) => Some(value)
      case _                   => None
    }
  }

  private[core] def memoriesOf(component: Component): Vector[Mem[_]] = {
    discover(component)
    allMemoriesOf(component).filter(memory => metadataOf(memory).nonEmpty)
  }

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val memories = memoriesOf(component)
    val expressions = memories.flatMap { memory =>
      val metadata = metadataOf(memory).get
      Vector(metadata.depth, metadata.elementWidth)
    }
    ElabInt.validateParameterRootInventory(
      s"external native-memory component '${component.definitionName}'",
      expressions
    )
    val referenced = expressions.flatMap(_.parameters)
    val grouped = referenced.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, schemas) if schemas.distinct.size != 1 => name
      }
      .foreach { name =>
        val source = memories.iterator
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

  private def allMemoriesOf(component: Component): Vector[Mem[_]] = {
    val values = ArrayBuffer.empty[Mem[_]]
    component.dslBody.walkDeclarations {
      case memory: Mem[_] => values += memory
      case _              =>
    }
    values.toVector
  }

  private def validateDepth(
      memory: Mem[_],
      depth: ParameterizedMemoryDepth
  ): ElaborationIntegerExpression = {
    val expression = validateDepthExpression(depth)
    if (memory.wordCount != depth.value) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"native memory depth '${expression.verilog}' must have witness ${memory.wordCount}, not ${depth.value}",
        depth.sourceLocation.orElse(expression.sourceLocation)
      )
    }
    expression
  }

  private def validateDepthExpression(
      depth: ParameterizedMemoryDepth
  ): ElaborationIntegerExpression = {
    if (depth == null)
      throw new IllegalArgumentException("symbolic native memory depth must not be null")
    if (depth.value < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-NOT-POSITIVE",
        s"native memory depth witness ${depth.value} must be positive",
        depth.sourceLocation
      )
    }
    if (depth.expression == null)
      throw new IllegalArgumentException("symbolic native memory depth expression must not be null")
    val expression = validateCompleteFiniteExpression(
      depth.expression,
      role = "external native-memory depth",
      missingCode = "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-EXACT-DOMAIN-MISSING",
      invalidCode = "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID"
    )
    if (expression.generateIndex.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-GENERATE-DEPENDENT",
        "native memory depth cannot depend on a generate index",
        depth.sourceLocation
      )
    }
    if (
      expression.default != BigInt(depth.value) ||
      expression.minimum < 1 ||
      expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"native memory depth '${expression.verilog}' must have witness ${depth.value} and a finite positive Int-sized domain",
        depth.sourceLocation.orElse(expression.sourceLocation)
      )
    }
    expression
  }

  /** Require one concrete literal or one identity-rooted symbolic evaluator
    * before external metadata may influence emitted memory geometry. Its table
    * must exactly cover the full domain or the still-authorized private branch
    * projection. An inexact carrier cannot be upgraded here: public bounds,
    * Verilog text and legacy native-Int shadow evidence are never sufficient.
    */
  private def validateCompleteFiniteExpression(
      raw: ElaborationIntegerExpression,
      role: String,
      missingCode: String,
      invalidCode: String
  ): ElaborationIntegerExpression = {
    if (raw == null)
      throw new IllegalArgumentException(s"$role expression must not be null")
    val expression = raw
    ElabInt.validateExpression(expression, role)
    if (expression.parameters.nonEmpty && expression.exactDomain.isEmpty) {
      fail(
        missingCode,
        s"$role '${expression.verilog}' must retain authoritative exact single-root evidence",
        expression.sourceLocation
      )
    }

    val domain = ElabInt.requireAuthoritativeIntegerDomain(
      expression,
      role,
      invalidCode,
      requireExactExtrema = true
    )

    val evaluated = domain
      .map(_.evaluations.map(_._2))
      .getOrElse(Vector(expression.default))
    if (evaluated.exists(_ < 1)) {
      fail(
        invalidCode,
        s"$role '${expression.verilog}' must retain a positive finite Int-sized evaluation table",
        expression.sourceLocation.orElse(domain.flatMap(_.root.sourceLocation))
      )
    }
    expression
  }

  private def elementWidthOf(
      memory: Mem[_],
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
    val expressions = elementExpressionsOf(memory, sourceLocation)
      .getOrElse(concreteWidths.map(literal))
    if (expressions.size != concreteWidths.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-TYPE-UNSUPPORTED",
        s"native memory element shape changed from ${concreteWidths.size} concrete leaves to ${expressions.size} retained HardType leaves",
        sourceLocation
      )
    }
    val checkedExpressions = expressions.zipWithIndex.map { case (expression, index) =>
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
        missingCode = "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-EXACT-DOMAIN-MISSING",
        invalidCode = "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID"
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
      checkedExpressions.reduce(add),
      role = "external native-memory aggregate element width",
      missingCode = "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-EXACT-DOMAIN-MISSING",
      invalidCode = "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID"
    )
    if (
      elementWidth.default != BigInt(memory.getWidth) ||
      elementWidth.minimum < 1 || elementWidth.maximum < elementWidth.minimum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"native memory concrete element width ${memory.getWidth} does not match retained expression '${elementWidth.verilog}' in [${elementWidth.minimum}, ${elementWidth.maximum}]",
        sourceLocation.orElse(elementWidth.sourceLocation)
      )
    }
    elementWidth
  }

  /** Resolve the exact leaf expressions already instantiated by Mem.
    *
    * A frontend-created HardType also retains the same expressions in its
    * external identity registry. Direct native HardType construction has no
    * such entry, so its one authoritative Mem evaluation is recovered from
    * `wordTypeLeaves` without invoking the generator again. If both sources
    * exist they must describe the same retained leaf functions.
    */
  private def elementExpressionsOf(
      memory: Mem[_],
      sourceLocation: Option[String]
  ): Option[Vector[ElaborationIntegerExpression]] = {
    val leafExpressions = memory.wordTypeLeaves.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
    }
    val symbolicLeaves = leafExpressions.exists(_.parameters.nonEmpty)
    ExternalParameterizedHardTypeRegistry.expressionsOf(memory.wordType) match {
      case Some(retained) if symbolicLeaves =>
        if (
          retained.size != leafExpressions.size ||
          !retained.zip(leafExpressions).forall { case (left, right) =>
            ElabInt.equivalentExpression(left, right)
          }
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-METADATA-CONFLICT",
            "native memory retained HardType geometry conflicts with its instantiated leaf geometry",
            sourceLocation
              .orElse(
                retained.iterator.flatMap(_.sourceLocation).toSeq.headOption
              )
              .orElse(
                leafExpressions.iterator.flatMap(_.sourceLocation).toSeq.headOption
              )
          )
        }
        Some(retained)
      case Some(retained)         => Some(retained)
      case None if symbolicLeaves => Some(leafExpressions)
      case None                   => None
    }
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
      .projectedExpression("external memory element width")

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
