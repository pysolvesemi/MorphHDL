package spinal.core.internals

import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Exact evidence from one invocation of the authoritative native algorithm.
  * Capturing a graph is NOT a certificate of callback purity, associativity,
  * width-generalization, or replay safety. Publication must establish those
  * obligations separately. No production replay backend is installed here.
  */
private[spinal] final case class UnvalidatedBalancedCallback(
    ordinal: Int,
    operands: Vector[Data],
    result: Data,
    declarations: Vector[BaseType],
    assignments: Vector[AssignmentStatement]
)

private[spinal] final case class UnvalidatedBalancedRow(
    level: Int,
    index: Int,
    operator: Option[UnvalidatedBalancedCallback],
    bridge: UnvalidatedBalancedCallback
)

private[spinal] final case class UnvalidatedBalancedReduction[T <: Data](
    vector: Vec[T],
    shape: ParameterizedVecShape,
    plan: TypedBalancedReductionPlan,
    result: T,
    rows: Vector[UnvalidatedBalancedRow]
) {
  /** A graph record alone cannot authorize parameterized publication. */
  def requireReplayCertificate(): Nothing =
    throw new IllegalArgumentException(
      "MORPH-REDUCE-BALANCED-REPLAY-UNVALIDATED: native callback capture is not a replay certificate"
    )
}

private[spinal] object TypedBalancedReductionCapture {
  private final case class Snapshot(
      declarations: Vector[BaseType],
      assignments: Vector[(AssignmentStatement, Expression, Expression, BaseType)],
      children: Vector[Component]
  )

  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-$code: $detail")

  private def snapshot(owner: Component): Snapshot = {
    val declarations = ArrayBuffer.empty[BaseType]
    val assignments = ArrayBuffer.empty[(AssignmentStatement, Expression, Expression, BaseType)]
    owner.dslBody.walkStatements {
      case value: BaseType => declarations += value
      case value: AssignmentStatement =>
        assignments += ((value, value.source, value.target, value.finalTarget))
      case _ =>
    }
    Snapshot(declarations.toVector, assignments.toVector, owner.children.toVector)
  }

  /** Retain the original four-argument internal entry point. */
  def apply[T <: Data](
      vector: Vec[T], op: (T, T) => T, levelBridge: (T, Int) => T,
      native: ElabBalancedReduction.Native[T]
  ): UnvalidatedBalancedReduction[T] =
    apply(vector, op, levelBridge, native, (_: UnvalidatedBalancedCallback) => ())

  /** Observe a completed callback before the native helper invokes another.
    * The observer must not construct RTL or replay the Scala callback. This
    * seam lets the closed-graph validator freeze mutable expression children
    * as well as the shallow assignment identities retained below.
    */
  def apply[T <: Data](
      vector: Vec[T],
      op: (T, T) => T,
      levelBridge: (T, Int) => T,
      native: ElabBalancedReduction.Native[T],
      onCallback: UnvalidatedBalancedCallback => Unit
  ): UnvalidatedBalancedReduction[T] = {
    if (vector == null || op == null || levelBridge == null || native == null || onCallback == null)
      fail("CAPTURE-NULL", "vector, native callbacks and observer must be non-null")
    val shape = ParameterizedVec.shapeOf(vector).getOrElse {
      fail("CAPTURE-SHAPE-MISSING", "the exact Vec receiver has no retained typed shape")
    }
    val plan = TypedBalancedReductionPlan.forVec(vector).get
    val owner = Component.current
    if (owner == null || (vector.component ne owner))
      fail("CAPTURE-OWNER", "capture must run inside the Vec's exact owning component")
    if (vector.vec.size != shape.carrierCapacity)
      fail("CAPTURE-CAPACITY", "the retained native carrier capacity has changed")

    def validateInputShape(): Unit = vector.vec.foreach { element =>
      val leaves = element.flatten.toVector
      val paths = element.flattenLocalName.toVector
      if (leaves.size != shape.elementLeaves.size || paths.size != leaves.size)
        fail("CAPTURE-SHAPE-CHANGED", "a carrier element has changed its leaf layout")
      leaves.zip(paths).zip(shape.elementLeaves).foreach { case ((leaf, path), expected) =>
        val width = ParameterizedWidth.expressionOf(leaf)
          .getOrElse(ElabInt.literal(leaf.getBitsWidth).expression)
        if ((leaf.component ne owner) ||
            (leaf.getTypeObject.asInstanceOf[AnyRef] ne expected.typeObject) ||
            Option(path).getOrElse("") != expected.path ||
            BigInt(leaf.getBitsWidth) != expected.width.default ||
            !ElabInt.equivalentExactFunction(width, expected.width))
          fail("CAPTURE-SHAPE-CHANGED", "a carrier leaf lost its exact owner, type, path or width authority")
      }
    }
    validateInputShape()
    val rows = ArrayBuffer.empty[UnvalidatedBalancedRow]
    var ordinal = 0
    var pending: Option[UnvalidatedBalancedCallback] = None

    def invoke(operands: Vector[Data])(body: => T): UnvalidatedBalancedCallback = {
      val before = snapshot(owner)
      val result = body
      if (result == null || result.flatten.isEmpty ||
          result.flatten.exists(_.component ne owner) || (Component.current ne owner))
        fail("CALLBACK-RESULT", "callback result must have nonempty leaves in the same component")
      val after = snapshot(owner)
      if (before.children.size != after.children.size ||
          before.children.zip(after.children).exists { case (a, b) => a ne b })
        fail("CALLBACK-HIERARCHY", "a callback must not create or replace child components")
      if (before.declarations.exists(value => !after.declarations.exists(_ eq value)))
        fail("CALLBACK-MUTATION", "a callback removed an existing native declaration")
      before.assignments.foreach { case (statement, source, targetExpression, target) =>
        if (!after.assignments.exists(_._1 eq statement) ||
            (statement.source ne source) || (statement.target ne targetExpression) ||
            (statement.finalTarget ne target))
          fail("CALLBACK-MUTATION", "a callback changed or removed an existing native assignment")
      }
      val declarations = after.declarations.filterNot(value => before.declarations.exists(_ eq value))
      val assignments = after.assignments.map(_._1)
        .filterNot(value => before.assignments.exists(_._1 eq value))
      if (assignments.exists(statement => !declarations.exists(_ eq statement.finalTarget)))
        fail("CALLBACK-EXTERNAL-WRITE", "a callback assigned an input or another pre-existing signal")
      validateInputShape()
      val captured = UnvalidatedBalancedCallback(ordinal, operands, result, declarations, assignments)
      onCallback(captured)
      ordinal += 1
      captured
    }

    val wrappedOp: (T, T) => T = (a, b) => {
      if (pending.nonEmpty)
        fail("NATIVE-ORDER", "native operator was invoked twice without its level bridge")
      val captured = invoke(Vector(a, b))(op(a, b))
      pending = Some(captured)
      captured.result.asInstanceOf[T]
    }
    val wrappedBridge: (T, Int) => T = (value, level) => {
      pending.foreach { operation =>
        if (operation.result ne value)
          fail("NATIVE-ORDER", "the bridge did not consume the exact preceding operator result")
      }
      val captured = invoke(Vector(value))(levelBridge(value, level))
      val index = rows.count(_.level == level)
      rows += UnvalidatedBalancedRow(level, index, pending, captured)
      pending = None
      captured.result.asInstanceOf[T]
    }
    val result = native(vector.vec, wrappedOp, wrappedBridge)
    if (pending.nonEmpty)
      fail("NATIVE-ORDER", "native reduction returned before bridging an operator result")

    // Check evidence by identity; do not evaluate or reimplement the operator.
    var prior = vector.vec.map(_.asInstanceOf[Data])
    var consumed = 0
    plan.stages.foreach { stage =>
      val stageRows = rows.filter(_.level == stage.level).toVector
      val expected = (prior.size + 1) / 2
      if (stageRows.size != expected ||
          rows.slice(consumed, consumed + expected).toVector != stageRows)
        fail("NATIVE-TOPOLOGY", "native bridge stages differ from the typed balanced schedule")
      stageRows.zipWithIndex.foreach { case (row, index) =>
        val left = prior(index * 2)
        if (index * 2 + 1 < prior.size) {
          val operation = row.operator.getOrElse {
            fail("NATIVE-TOPOLOGY", "a complete pair has no native operator record")
          }
          if (operation.operands.size != 2 || (operation.operands(0) ne left) ||
              (operation.operands(1) ne prior(index * 2 + 1)) ||
              (row.bridge.operands.head ne operation.result))
            fail("NATIVE-TOPOLOGY", "native pair order or result identity changed")
        } else if (row.operator.nonEmpty || (row.bridge.operands.head ne left)) {
          fail("NATIVE-TOPOLOGY", "odd tail must bypass the operator and traverse the bridge")
        }
      }
      consumed += stageRows.size
      prior = stageRows.map(_.bridge.result)
    }
    if (consumed != rows.size || prior.size != 1 || (prior.head ne result))
      fail("NATIVE-TOPOLOGY", "native result is not the exact terminal stage or singleton identity")
    UnvalidatedBalancedReduction(vector, shape, plan, result, rows.toVector)
  }
}
