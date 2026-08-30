package spinal.lib

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import spinal.core._
import spinal.core.internals.DataAssignmentStatement

/** Weak key with native Counter object-identity semantics. */
private[lib] final class ExternalCounterIdentityRef(
    value: Counter,
    queue: ReferenceQueue[Counter]
) extends WeakReference[Counter](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalCounterIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Symbolic geometry retained beside one ordinary native full-range Counter. */
final case class ExternalParameterizedCounterMetadata(
    width: ElaborationIntegerExpression,
    sourceLocation: Option[String],
    private[spinal] nativeNextAssignments: Vector[DataAssignmentStatement]
)

/**
  * MorphHDL-owned sidecar for native Counter construction.
  *
  * The native Counter is elaborated first from the concrete witness. Only after
  * its unmodified algorithm has built the ordinary graph are its two state
  * leaves associated with the retained symbolic width by object identity. The
  * exact native next-value statement identities are retained so later caller
  * assignments cannot inherit the native Counter width exception.
  */
object ExternalParameterizedCounterRegistry {
  private val queue = new ReferenceQueue[Counter]()
  private val retained =
    mutable.HashMap.empty[
      ExternalCounterIdentityRef,
      ExternalParameterizedCounterMetadata
    ]

  private def reap(): Unit = synchronized {
    var reference = queue.poll().asInstanceOf[ExternalCounterIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalCounterIdentityRef]
    }
  }

  private def expressionOf(width: ParameterizedBitCount): ElaborationIntegerExpression =
    width.expression.orElse {
      width.parameter.map { parameter =>
        ElaborationIntegerExpression(
          verilog = parameter.name,
          default = parameter.default,
          minimum = parameter.minimum,
          maximum = parameter.maximum,
          parameters = Vector(parameter),
          sourceLocation = width.sourceLocation,
          parameterRoots = Vector(parameter.declarationRoot)
        )
      }
    }.getOrElse {
      ElaborationIntegerExpression(
        verilog = width.value.toString,
        default = BigInt(width.value),
        minimum = BigInt(width.value),
        maximum = BigInt(width.value),
        parameters = Vector.empty,
        sourceLocation = width.sourceLocation
      )
    }

  /** Construct the untouched native full-range Counter and retain its width. */
  def create(width: ParameterizedBitCount): Counter = {
    if (width == null)
      throw new IllegalArgumentException("symbolic Counter bit count must not be null")
    attach(spinal.lib.Counter(BitCount(width.value)), width)
  }

  /** Associate symbolic geometry with an already-created native full-range Counter. */
  def attach(counter: Counter, width: ParameterizedBitCount): Counter = {
    if (counter == null)
      throw new IllegalArgumentException("native Counter must not be null")
    if (width == null)
      throw new IllegalArgumentException("symbolic Counter bit count must not be null")
    if (width.value < 1)
      throw new IllegalArgumentException("symbolic Counter bit count must be positive")

    require(
      counter.direction == CounterDirection.Up &&
        counter.upper == BoundaryPolicy.Wrap &&
        counter.lower == BoundaryPolicy.Wrap && counter.handleOverflow,
      "external symbolic Counter retention supports the native Counter(BitCount) profile only"
    )

    val expectedEnd = (BigInt(1) << width.value) - 1
    require(
      counter.start == 0 && counter.end == expectedEnd,
      "external symbolic Counter retention requires the native complete unsigned range"
    )
    require(
      counter.value.getBitsWidth == width.value &&
        counter.valueNext.getBitsWidth == width.value,
      "native Counter witness width does not match the retained symbolic width default"
    )

    val nativeNextAssignments = mutable.ArrayBuffer.empty[DataAssignmentStatement]
    counter.valueNext.foreachStatements {
      case assignment: DataAssignmentStatement
          if assignment.target == counter.valueNext &&
            assignment.finalTarget == counter.valueNext =>
        nativeNextAssignments += assignment
      case _ =>
    }
    require(
      nativeNextAssignments.nonEmpty,
      "native Counter did not expose its expected next-value assignments"
    )

    val metadata = ExternalParameterizedCounterMetadata(
      expressionOf(width),
      width.sourceLocation,
      nativeNextAssignments.toVector
    )
    synchronized {
      reap()
      val key = new ExternalCounterIdentityRef(counter, null)
      if (retained.contains(key))
        throw new IllegalArgumentException("native Counter already carries external symbolic geometry")
      retained.update(new ExternalCounterIdentityRef(counter, queue), metadata)
    }

    ParameterizedWidth.attach(counter.valueNext, width)
    ParameterizedWidth.attach(counter.value, width)
    counter
  }

  def metadataOf(counter: Counter): Option[ExternalParameterizedCounterMetadata] = synchronized {
    if (counter == null) None
    else {
      reap()
      retained.get(new ExternalCounterIdentityRef(counter, null))
    }
  }

  /**
    * Return the retained width only when `assignment` is one of the exact
    * next-value statements created by the untouched native Counter constructor.
    * Statements added by callers after construction are deliberately excluded.
    */
  private[spinal] def nativeNextAssignmentWidthOf(
      data: BaseType,
      assignment: DataAssignmentStatement
  ): Option[ElaborationIntegerExpression] = synchronized {
    if (data == null || assignment == null) None
    else {
      reap()
      retained.iterator.collectFirst {
        case (reference, metadata) if {
              val counter = reference.get()
              counter != null && (counter.valueNext eq data) &&
                metadata.nativeNextAssignments.exists(_ eq assignment)
            } =>
          metadata.width
      }
    }
  }

  /**
    * Return the retained symbolic width only for the native registered state
    * whose full-range boundary comparison must remain width-polymorphic.
    * Ordinary symbolic-width signals and user-authored fixed literals are not
    * included in this provenance query.
    */
  private[spinal] def boundaryWidthOf(
      data: BaseType
  ): Option[ElaborationIntegerExpression] = synchronized {
    if (data == null) None
    else {
      reap()
      retained.iterator.collectFirst {
        case (reference, metadata) if {
              val counter = reference.get()
              counter != null && (counter.value eq data)
            } =>
          metadata.width
      }
    }
  }
}
