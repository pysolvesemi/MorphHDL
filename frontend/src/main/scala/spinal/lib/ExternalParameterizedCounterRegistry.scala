package spinal.lib

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import spinal.core._

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
    sourceLocation: Option[String]
)

/**
  * MorphHDL-owned sidecar for native Counter construction.
  *
  * The native Counter is elaborated first from the concrete witness. Only after
  * its unmodified algorithm has built the ordinary graph are its two state
  * leaves associated with the retained symbolic width by object identity.
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
          sourceLocation = width.sourceLocation
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

    val metadata = ExternalParameterizedCounterMetadata(
      expressionOf(width),
      width.sourceLocation
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
}
