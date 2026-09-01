package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core.internals.{BitVectorLiteral, DataAssignmentStatement, Expression}

/** Exact native UInt carrying one retained definition-side integer expression. */
final case class ExternalParameterizedValueRecord(
    expression: ElaborationIntegerExpression,
    witness: BigInt,
    sourceLocation: Option[String],
    assignmentRef: WeakReference[DataAssignmentStatement],
    witnessSourceRef: WeakReference[Expression]
) {
  private[spinal] def assignment: Option[DataAssignmentStatement] =
    Option(assignmentRef.get())

  private[spinal] def witnessSource: Option[Expression] =
    Option(witnessSourceRef.get())
}

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

  /** Validate the complete definition-side summary before any of its bounds
    * can authorize an unsigned carrier.  Parameter-free expressions are
    * constants, so their default and both bounds must be the same value.
    * Symbolic expressions require one exact domain tied to their sole
    * declaration-root identity. Its table must exhaust either the full domain
    * or the still-authorized private branch projection; public schemas or
    * coincident names are never sufficient evidence by themselves.
    */
  private def exactValueDomain(
      expression: ElaborationIntegerExpression
  ): Option[ElaborationExactDomain[BigInt]] =
    ElabInt.requireAuthoritativeIntegerDomain(
      expression,
      role = "retained UInt expression",
      failureCode = "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED",
      requireExactExtrema = true
    )

  /** Prove that one retained unsigned value fits its exact carrier geometry.
    *
    * Symbolic value functions require exhaustive single-root evidence.  A
    * carrier width may be correlated pointwise only when both exact domains
    * retain the same declaration-root identity and the same admitted keys;
    * equal parameter names or numeric universes never establish correlation.
    * Every other pairing uses the conservative value-maximum/carrier-minimum
    * cross product.
    */
  private[core] def validateCarrierDomain(
      value: UInt,
      expression: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): Unit = {
    if (value == null)
      throw new IllegalArgumentException("parameterized UInt value must not be null")
    if (expression == null)
      throw new IllegalArgumentException("parameterized UInt expression must not be null")

    val source = sourceLocation.orElse(expression.sourceLocation)
    val valueDomain = exactValueDomain(expression)
    if (expression.minimum < 0 || expression.maximum < expression.minimum) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-DOMAIN-UNSUPPORTED",
        s"retained UInt expression '${expression.verilog}' has invalid unsigned domain [${expression.minimum}, ${expression.maximum}]",
        source
      )
    }

    valueDomain.foreach { exact =>
      exact.evaluations
        .collectFirst {
          case (rootValue, result) if result < 0 => rootValue -> result
        }
        .foreach { case (rootValue, result) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VALUE-DOMAIN-UNSUPPORTED",
            s"retained UInt expression '${expression.verilog}' evaluates to negative value $result at root value $rootValue",
            source
          )
        }
    }

    val retainedWidth = ParameterizedWidth.expressionOf(value)
    val minimumWidth = retainedWidth
      .map(_.minimum)
      .getOrElse(BigInt(value.getBitsWidth))
    if (value.getBitsWidth < 1 || minimumWidth < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
        s"retained UInt expression '${expression.verilog}' requires a positive carrier width, but its minimum is $minimumWidth bits",
        source
      )
    }

    val pointwise = for {
      values <- valueDomain
      width <- retainedWidth
      widths <- width.exactDomain
      if (values.root eq widths.root) &&
        (values.parameter eq widths.parameter) &&
        values.evidenceValues == widths.evidenceValues
    } yield values -> widths

    pointwise match {
      case Some((values, widths)) =>
        values.evaluations
          .collectFirst {
            case (rootValue, result) if widths.byRootValue.get(rootValue).forall { width =>
                  width < 1 || BigInt(result.bitLength) > width
                } =>
              rootValue -> (result -> widths.byRootValue.get(rootValue))
          }
          .foreach { case (rootValue, (result, width)) =>
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
              s"retained UInt expression '${expression.verilog}' evaluates to $result at root value $rootValue, outside carrier width ${width.map(_.toString).getOrElse("<missing>")}",
              source
            )
          }
      case None =>
        val maximumValue = valueDomain
          .flatMap(_.evaluations.map(_._2).reduceOption(_ max _))
          .getOrElse(expression.maximum)
        val requiredWidth = BigInt(maximumValue.bitLength)
        if (requiredWidth > minimumWidth) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
            s"retained UInt expression '${expression.verilog}' reaches $maximumValue and requires $requiredWidth bits, outside carrier minimum width $minimumWidth bits",
            source
          )
        }
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
    if (expression.default != witness) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-WITNESS-MISMATCH",
        s"retained UInt expression '${expression.verilog}' has default ${expression.default}, but native witness is $witness",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    validateCarrierDomain(value, expression, sourceLocation)
    val retainedWidth = ParameterizedWidth.expressionOf(value)
    if (
      expression.parameters.isEmpty &&
      !retainedWidth.exists(_.parameters.nonEmpty)
    )
      throw new IllegalArgumentException(
        "a parameter-free retained UInt value requires an exact symbolic carrier width"
      )

    val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    value.foreachStatements {
      case statement: DataAssignmentStatement if (statement.finalTarget eq value) && (statement.target eq value) =>
        assignments += statement
      case _ =>
    }
    val exactWitnessAssignments = assignments.filter { statement =>
      statement.source match {
        case literal: BitVectorLiteral =>
          !literal.hasPoison() && literal.getValue() == witness
        case _ => false
      }
    }
    if (assignments.size != 1 || exactWitnessAssignments.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-LINEAGE-MISMATCH",
        s"retained UInt witness $witness has ${assignments.size} exact direct assignments and ${exactWitnessAssignments.size} matching literal assignments; exactly one shared identity is required",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    val assignment = exactWitnessAssignments.head

    reap()
    val key = new ExternalParameterizedValueIdentityRef(value, null)
    val incoming = ExternalParameterizedValueRecord(
      expression,
      witness,
      sourceLocation.orElse(expression.sourceLocation),
      new WeakReference[DataAssignmentStatement](assignment),
      new WeakReference[Expression](assignment.source)
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
      left.assignment.exists(value => right.assignment.exists(_ eq value)) &&
      left.witnessSource.exists(value => right.witnessSource.exists(_ eq value)) &&
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
