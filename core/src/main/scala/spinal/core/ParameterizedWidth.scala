package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import spinal.core.internals.{DataAssignmentStatement, Resize}

/**
  * Elaboration metadata for one public integer parameter used directly as a
  * packed width.
  *
  * The concrete `default` remains the width used by ordinary SpinalHDL
  * elaboration and validation. MorphHDL retains the symbolic identity in an
  * external object-identity registry rather than modifying native data types.
  */
final case class ElaborationIntegerParameter(
    name: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt
)

/**
  * Identity-bearing provenance for one declaration of an elaboration-time
  * parameter. Two declarations can intentionally have the same public name
  * and schema; they are still independent roots until a caller explicitly
  * proves otherwise by carrying this exact object through derived expressions.
  */
final class ElaborationIntegerParameterRoot private (
    val name: String,
    val sourceLocation: Option[String]
) {
  override def toString: String = s"ElaborationIntegerParameterRoot($name)"
}

object ElaborationIntegerParameterRoot {
  /** Allocate provenance for one exact frontend parameter declaration. */
  def fresh(
      name: String,
      sourceLocation: Option[String] = None
  ): ElaborationIntegerParameterRoot = {
    require(name != null && name.nonEmpty, "parameter-root name must not be empty")
    new ElaborationIntegerParameterRoot(name, sourceLocation)
  }
}


/**
  * Backend-neutral integer expression retained during ordinary SpinalHDL
  * elaboration for symbolic widths, hierarchy, structure, processes and memory
  * geometry.
  *
  * `default` is the concrete witness used by the native SpinalHDL graph.
  * `minimum` and `maximum` describe the complete admitted parameter domain.
  */
final case class ElaborationIntegerExpression(
    verilog: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt,
    parameters: Vector[ElaborationIntegerParameter],
    generateIndex: Option[String] = None,
    sourceLocation: Option[String] = None,
    parameterRoots: Vector[ElaborationIntegerParameterRoot] = Vector.empty
)

/** Boolean counterpart used by retained parameter-controlled metadata. */
final case class ElaborationBooleanExpression(
    verilog: String,
    default: Boolean,
    parameters: Vector[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None,
    parameterRoots: Vector[ElaborationIntegerParameterRoot] = Vector.empty
)

/** A concrete witness bit count with an optional bounded symbolic expression. */
final case class ParameterizedBitCount(
    value: Int,
    parameter: Option[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None,
    expression: Option[ElaborationIntegerExpression] = None
)

object ParameterizedBitCount {
  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation = None)

  def apply(
      value: Int,
      parameter: ElaborationIntegerParameter,
      sourceLocation: Option[String]
  ): ParameterizedBitCount =
    new ParameterizedBitCount(value, Some(parameter), sourceLocation)
}

private[core] final case class RetainedWidth(
    directParameter: Option[ElaborationIntegerParameter],
    expression: Option[ElaborationIntegerExpression],
    sourceLocation: Option[String]
)

/** Weak key with identity, rather than hardware equality, semantics. */
private[core] final class RetainedWidthIdentityRef(
    value: BaseType,
    queue: ReferenceQueue[BaseType]
) extends WeakReference[BaseType](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: RetainedWidthIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one exact native Resize expression. */
private[core] final class RetainedResizeIdentityRef(
    value: Resize,
    queue: ReferenceQueue[Resize]
) extends WeakReference[Resize](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: RetainedResizeIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/**
  * MorphHDL-owned symbolic-width registry and native-factory adapters.
  *
  * Native `BaseType`, `Bits`, `UInt` and `SInt` source remains untouched. The
  * registry associates retained geometry with concrete native objects by
  * identity. Clone-sensitive APIs are wrapped externally and still delegate to
  * the ordinary SpinalHDL algorithms.
  */
object ParameterizedWidth {
  private val queue = new ReferenceQueue[BaseType]()
  private val retained = mutable.HashMap.empty[RetainedWidthIdentityRef, RetainedWidth]
  private val resizeQueue = new ReferenceQueue[Resize]()
  private val retainedResizes = mutable.HashMap.empty[
    RetainedResizeIdentityRef,
    ElaborationIntegerExpression
  ]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[RetainedWidthIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[RetainedWidthIdentityRef]
    }
  }

  private def reapResizes(): Unit = {
    var reference = resizeQueue.poll().asInstanceOf[RetainedResizeIdentityRef]
    while (reference != null) {
      retainedResizes.remove(reference)
      reference = resizeQueue.poll().asInstanceOf[RetainedResizeIdentityRef]
    }
  }

  private def retainResize(
      resize: Resize,
      expression: ElaborationIntegerExpression
  ): Unit = synchronized {
    reapResizes()
    val lookup = new RetainedResizeIdentityRef(resize, null)
    retainedResizes.get(lookup) match {
      case Some(existing) if existing == expression => ()
      case Some(existing) =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-PROVENANCE-CONFLICT",
          s"one exact native Resize target is associated with conflicting typed expressions '${existing.verilog}' and '${expression.verilog}'",
          expression.sourceLocation.orElse(existing.sourceLocation)
        )
      case None =>
        retainedResizes.update(
          new RetainedResizeIdentityRef(resize, resizeQueue),
          expression
        )
    }
  }

  private def metadataOf(data: BaseType): Option[RetainedWidth] = synchronized {
    reap()
    retained.get(new RetainedWidthIdentityRef(data, null))
  }

  private def retain(data: BaseType, metadata: RetainedWidth): Unit = synchronized {
    reap()
    retained.update(new RetainedWidthIdentityRef(data, queue), metadata)
  }

  private def retainedExpression(width: ParameterizedBitCount): Option[ElaborationIntegerExpression] =
    width.expression.orElse {
      width.parameter.map { parameter =>
        ElaborationIntegerExpression(
          verilog = parameter.name,
          default = parameter.default,
          minimum = parameter.minimum,
          maximum = parameter.maximum,
          parameters = Vector(parameter),
          sourceLocation = width.sourceLocation
        )
      }
    }

  /** Attach a symbolic width to one concrete native bit vector. */
  def attach[T <: BitVector](data: T, width: ParameterizedBitCount): T = {
    if (data == null) throw new IllegalArgumentException("symbolic-width target must not be null")
    if (width == null) throw new IllegalArgumentException("symbolic bit count must not be null")
    data.setWidth(width.value)
    val expression = retainedExpression(width)
    if (expression.exists(_.parameters.nonEmpty)) {
      retain(
        data,
        RetainedWidth(width.parameter, expression, width.sourceLocation)
      )
    }
    data
  }

  /**
    * Attach one typed target width to a native resize result and retain the
    * exact internal Resize node before weak-clone normalization can remove the
    * result object. The association is by JVM identity and is generic across
    * all native algorithms.
    */
  def attachResize[T <: BitVector](data: T, width: ElabInt): T = {
    if (width == null)
      throw new IllegalArgumentException("typed resize width must not be null")
    val result = attach(data, width.toParameterizedBitCount("typed resize"))
    val expression = width.expression
    if (expression.parameters.nonEmpty && result.hasOnlyOneStatement) {
      result.head match {
        case assignment: DataAssignmentStatement
            if (assignment.target eq result) &&
              (assignment.finalTarget eq result) =>
          assignment.source match {
            case resize: Resize if resize.size == result.getBitsWidth =>
              if (expression.default != BigInt(resize.size)) {
                ParameterizedVerilogException.fail(
                  "SPINAL-PARAMETERIZED-VERILOG-RESIZE-WITNESS-MISMATCH",
                  s"native Resize target ${resize.size} does not match typed width default ${expression.default}",
                  expression.sourceLocation
                )
              }
              retainResize(resize, expression)
            case _ =>
          }
        case _ =>
      }
    }
    result
  }

  /** Look up one typed target width only by exact native Resize identity. */
  def resizeExpressionOf(
      resize: Resize
  ): Option[ElaborationIntegerExpression] = synchronized {
    if (resize == null) None
    else {
      reapResizes()
      retainedResizes.get(new RetainedResizeIdentityRef(resize, null))
    }
  }

  /** MorphHDL shadow factories; each delegates to the untouched native factory. */
  def Bits(width: ParameterizedBitCount): spinal.core.Bits =
    attach(spinal.core.Bits(BitCount(width.value)), width)
  def Bits(width: BitCount): spinal.core.Bits = spinal.core.Bits(width)

  def UInt(width: ParameterizedBitCount): spinal.core.UInt =
    attach(spinal.core.UInt(BitCount(width.value)), width)
  def UInt(width: BitCount): spinal.core.UInt = spinal.core.UInt(width)

  def SInt(width: ParameterizedBitCount): spinal.core.SInt =
    attach(spinal.core.SInt(BitCount(width.value)), width)
  def SInt(width: BitCount): spinal.core.SInt = spinal.core.SInt(width)

  /** Copy registry ownership between already-created native leaves. */
  def copy(from: BaseType, to: BaseType): Unit = {
    if (from == null || to == null)
      throw new IllegalArgumentException("symbolic-width copy requires non-null leaves")
    metadataOf(from).foreach(retain(to, _))
  }

  /**
    * Copy concrete and symbolic leaf geometry in deterministic data-model order.
    * This is the external replacement for the former native `BaseType.clone`
    * hook.
    */
  def copyShape[T <: Data](from: T, to: T): T = {
    if (from == null || to == null)
      throw new IllegalArgumentException("symbolic shape copy requires non-null data")
    val sourceLeaves = from.flatten.toVector
    val targetLeaves = to.flatten.toVector
    if (sourceLeaves.size != targetLeaves.size) {
      throw new IllegalArgumentException(
        s"symbolic shape clone changed leaf count ${sourceLeaves.size} -> ${targetLeaves.size}"
      )
    }
    sourceLeaves.zip(targetLeaves).zipWithIndex.foreach {
      case ((source, target), index) =>
        if (source.getClass != target.getClass) {
          throw new IllegalArgumentException(
            s"symbolic shape clone changed leaf $index from ${source.getClass.getName} " +
              s"to ${target.getClass.getName}"
          )
        }
        (source, target) match {
          case (sourceVector: BitVector, targetVector: BitVector) =>
            targetVector.setWidth(sourceVector.getBitsWidth)
          case _ =>
        }
        copy(source, target)
    }
    to
  }

  /** Native clone algorithm plus external concrete/symbolic shape propagation. */
  def cloneOf[T <: Data](data: T): T =
    copyShape(data, spinal.core.cloneOf(data))

  /**
    * Native HardType algorithm supplied with an externally shape-preserving
    * generator. A stable template is cloned on every invocation.
    */
  def HardType[T <: Data](dataType: => T): spinal.core.HardType[T] = {
    val template = dataType
    new spinal.core.HardType[T](cloneOf(template))
  }

  /** Untouched native register algorithm driven by the retained HardType. */
  def Reg[T <: Data](dataType: => T): T = spinal.core.Reg(HardType(dataType))

  /** Untouched native Vec algorithm driven by the retained HardType. */
  def Vec[T <: Data](dataType: => T, size: Int): spinal.core.Vec[T] =
    spinal.core.Vec(HardType(dataType), size)

  def isRetained(data: BaseType): Boolean = metadataOf(data).nonEmpty

  def parameterOf(data: BaseType): Option[ElaborationIntegerParameter] =
    metadataOf(data).flatMap(_.directParameter)

  def expressionOf(data: BaseType): Option[ElaborationIntegerExpression] =
    metadataOf(data).flatMap(_.expression)

  def sourceLocationOf(data: BaseType): Option[String] =
    metadataOf(data).flatMap(_.sourceLocation)

  def leavesOf(data: Data): Vector[BaseType] =
    data.flatten.filter(expressionOf(_).exists(_.parameters.nonEmpty)).toVector

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val leaves = scala.collection.mutable.ArrayBuffer.empty[BaseType]
    component.dslBody.walkLeafStatements {
      case baseType: BaseType if expressionOf(baseType).exists(_.parameters.nonEmpty) =>
        leaves += baseType
      case _ =>
    }
    val associated = leaves.flatMap { baseType =>
      expressionOf(baseType).toVector.flatMap(
        _.parameters.map(parameter => baseType -> parameter)
      )
    }
    val values = associated.map(_._2)
    values.groupBy(_.name).collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }.foreach { name =>
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting declarations on component '${component.definitionName}'",
        associated.find(_._2.name == name).flatMap { case (baseType, _) =>
          sourceLocationOf(baseType)
        }
      )
    }
    values.distinct.sortBy(_.name).toVector
  }
}

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
