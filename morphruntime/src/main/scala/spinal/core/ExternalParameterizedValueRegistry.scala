package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Exact native UInt carrying one retained definition-side integer expression. */
final case class ExternalParameterizedValueRecord(
    expression: ElaborationIntegerExpression,
    witness: BigInt,
    sourceLocation: Option[String]
)

private[core] final class ExternalParameterizedValueIdentityRef(
    value: UInt,
    queue: ReferenceQueue[UInt]
) extends WeakReference[UInt](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalParameterizedValueIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** MorphHDL-owned exact-object registry for symbolic integer values used by an
  * otherwise ordinary native hardware expression. It does not discover values
  * from concrete literals, names, ports or module identities.
  */
object ExternalParameterizedValueRegistry {
  private val queue = new ReferenceQueue[UInt]()
  private val retained = mutable.HashMap.empty[
    ExternalParameterizedValueIdentityRef,
    ExternalParameterizedValueRecord
  ]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ExternalParameterizedValueIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalParameterizedValueIdentityRef]
    }
  }

  def attach(
      value: UInt,
      expression: ElaborationIntegerExpression,
      witness: BigInt,
      sourceLocation: Option[String]
  ): UInt = synchronized {
    if (value == null)
      throw new IllegalArgumentException("parameterized UInt value must not be null")
    if (expression == null)
      throw new IllegalArgumentException("parameterized UInt expression must not be null")
    if (expression.parameters.isEmpty)
      throw new IllegalArgumentException("parameterized UInt expression must depend on a parameter")
    if (expression.default != witness) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WITNESS-MISMATCH",
        s"retained UInt expression '${expression.verilog}' has default ${expression.default}, but native witness is $witness",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    if (expression.minimum < 0 || expression.maximum < expression.minimum) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-DOMAIN-UNSUPPORTED",
        s"retained UInt expression '${expression.verilog}' has invalid unsigned domain [${expression.minimum}, ${expression.maximum}]",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    val witnessWidth = value.getBitsWidth
    val maximumWidth = ParameterizedWidth
      .expressionOf(value)
      .map(_.maximum)
      .getOrElse(BigInt(witnessWidth))
    if (
      witnessWidth < 1 || maximumWidth < 1 || !maximumWidth.isValidInt ||
      expression.maximum >= (BigInt(1) << maximumWidth.toInt)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
        s"retained UInt expression '${expression.verilog}' reaches ${expression.maximum}, outside carrier width domain ending at $maximumWidth bits",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }

    reap()
    val key = new ExternalParameterizedValueIdentityRef(value, null)
    val incoming = ExternalParameterizedValueRecord(
      expression,
      witness,
      sourceLocation.orElse(expression.sourceLocation)
    )
    retained.get(key) match {
      case Some(existing) if !equivalentRecord(existing, incoming) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-CONFLICT",
          "one exact UInt object received conflicting retained value expressions",
          incoming.sourceLocation
        )
      case Some(_) =>
      case None =>
        value.setAsVital()
        value.dontSimplifyIt()
        retained.update(
          new ExternalParameterizedValueIdentityRef(value, queue),
          incoming
        )
    }
    value
  }

  private def equivalentRecord(
      left: ExternalParameterizedValueRecord,
      right: ExternalParameterizedValueRecord
  ): Boolean =
    left.witness == right.witness &&
      left.sourceLocation == right.sourceLocation &&
      ElabInt.equivalentExpression(left.expression, right.expression)

  def recordOf(value: UInt): Option[ExternalParameterizedValueRecord] = synchronized {
    if (value == null) None
    else {
      reap()
      retained.get(new ExternalParameterizedValueIdentityRef(value, null))
    }
  }

  def valuesOf(component: Component): Vector[(UInt, ExternalParameterizedValueRecord)] = {
    val values = ArrayBuffer.empty[(UInt, ExternalParameterizedValueRecord)]
    component.dslBody.walkLeafStatements {
      case value: UInt => recordOf(value).foreach(record => values += value -> record)
      case _           =>
    }
    val seen = new IdentityHashMap[UInt, java.lang.Boolean]()
    values.toVector
      .filter { case (value, _) =>
        seen.put(value, java.lang.Boolean.TRUE) == null
      }
      .sortBy { case (value, record) =>
        (Option(value.getName()).getOrElse(""), record.expression.verilog)
      }
  }

  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val expressions = valuesOf(component).map(_._2.expression)
    ElabInt.validateParameterRootInventory(
      s"retained UInt values on component '${component.definitionName}'",
      expressions
    )
    val parameters = expressions.flatMap(_.parameters)
    val grouped = parameters.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, declarations) if declarations.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting retained UInt value declarations"
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    throw new ParameterizedVerilogException(code, detail, sourceLocation)
}
