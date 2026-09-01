package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import spinal.core.internals.Resize

/** Weak exact-identity key for one native normalized Resize expression. */
private[core] final class ExternalParameterizedResizeIdentityRef(
    value: Resize,
    queue: ReferenceQueue[Resize]
) extends WeakReference[Resize](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalParameterizedResizeIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** MorphHDL-owned symbolic target-width provenance for untouched native
  * BitVector `resize` calls.
  *
  * Native SpinalHDL first materializes a weak-clone result driven by an exact
  * internal [[spinal.core.internals.Resize]] expression. Later normalization
  * may remove the weak-clone object while keeping that Resize node. Retaining
  * the symbolic width only on the clone therefore loses the explicit target
  * width. This registry binds the same reviewed expression to the Resize node
  * by JVM identity, without modifying the native graph or matching names or
  * concrete widths.
  */
object ExternalParameterizedResizeRegistry {
  private val queue = new ReferenceQueue[Resize]()
  private val retained = mutable.HashMap.empty[
    ExternalParameterizedResizeIdentityRef,
    ElaborationIntegerExpression
  ]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ExternalParameterizedResizeIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalParameterizedResizeIdentityRef]
    }
  }

  /** Attach one symbolic target expression to one exact native Resize node.
    *
    * This is a compiler-runtime sink, not a public metadata publication API.
    * The exact weak-clone target, its sole native assignment, the retained
    * expression object and the Resize source must all still be the identities
    * observed by the compiler hook. Equal widths or copied expression metadata
    * never recover that authority.
    */
  private[spinal] def attach(
      resize: Resize,
      target: BitVector,
      expression: ElaborationIntegerExpression
  ): Unit = synchronized {
    if (resize == null)
      throw new IllegalArgumentException("symbolic resize target must not be null")
    if (target == null)
      throw new IllegalArgumentException("symbolic resize result must not be null")
    if (expression == null)
      throw new IllegalArgumentException("symbolic resize expression must not be null")
    if (expression.parameters.isEmpty) return

    ElabInt.validateExpression(expression, "external native Resize expression")
    ParameterizedWidth.expressionOf(target) match {
      case Some(retained) if retained eq expression =>
      case Some(_) =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-EXPRESSION-IDENTITY-MISMATCH",
          "native Resize publication received copied or foreign retained-width metadata",
          expression.sourceLocation
        )
      case None =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-WIDTH-MISSING",
          "native Resize publication target has no exact retained symbolic width",
          expression.sourceLocation
        )
    }

    val exactAssignment =
      if (!target.hasOnlyOneStatement) false
      else
        target.head match {
          case assignment: spinal.core.internals.DataAssignmentStatement =>
            (assignment.target eq target) &&
            (assignment.finalTarget eq target) &&
            (assignment.source eq resize)
          case _ => false
        }
    if (!exactAssignment) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-IDENTITY-MISMATCH",
        "native Resize publication target is not driven solely by the exact retained Resize node",
        expression.sourceLocation
      )
    }
    if (
      expression.default != BigInt(resize.size) ||
      target.getBitsWidth != resize.size
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-PARAMETERIZED-VERILOG-RESIZE-WITNESS-MISMATCH",
        s"native Resize target ${resize.size}, result width ${target.getBitsWidth} and retained symbolic default ${expression.default} must agree",
        expression.sourceLocation
      )
    }

    reap()
    val lookup = new ExternalParameterizedResizeIdentityRef(resize, null)
    retained.get(lookup) match {
      case Some(existing) if existing eq expression =>
        ()
      case Some(existing) =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-PROVENANCE-CONFLICT",
          s"one exact native Resize target is associated with conflicting symbolic expressions '${existing.verilog}' and '${expression.verilog}'",
          expression.sourceLocation.orElse(existing.sourceLocation)
        )
      case None =>
        retained.update(
          new ExternalParameterizedResizeIdentityRef(resize, queue),
          expression
        )
    }
  }

  /** Look up symbolic target width only by exact native Resize identity. */
  def expressionOf(
      resize: Resize
  ): Option[ElaborationIntegerExpression] = synchronized {
    if (resize == null) None
    else {
      reap()
      retained.get(new ExternalParameterizedResizeIdentityRef(resize, null))
    }
  }
}
