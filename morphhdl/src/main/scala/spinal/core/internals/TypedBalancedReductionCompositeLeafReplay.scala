package spinal.core.internals

import java.util.IdentityHashMap
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** A shape-changing composite callback is admitted only when every output
  * leaf is an independent closed scalar graph of the corresponding two input
  * leaves. Scalar replay remains the sole arithmetic and width authority.
  * Exhaustive independent conditional fields reuse the shared scalar graph
  * certificate, including its driver ordering and width transfer; no native
  * saturation or conditional algorithm is copied into composite replay.
  * Exact read-only runtime captures may participate as fixed graph roots;
  * shared locals and cross-field reads remain rejected rather than being
  * reconstructed as an aggregate-specific algorithm.
  */
private[spinal] object TypedBalancedReductionCompositeLeafReplay {
  type Width = ElaborationIntegerExpression

  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-COMPOSITE-WIDENING-$code: $detail")

  final class Proof private[TypedBalancedReductionCompositeLeafReplay] (
      val nativeResult: Data,
      val leaves: Vector[TypedBalancedReductionOperatorCertificate]
  ) {
    val resultWidths: Vector[Width] = leaves.map(_.resultWidth)
    val operationKey: Vector[(Any, Any)] = leaves.map(proof => proof.operationKey -> proof.transferKey)

    def validateFreshness(): Unit = leaves.foreach(_.validateFreshness())

    def resultWidthsFor(left: Vector[Width], right: Vector[Width]): Vector[Width] = {
      validateFreshness()
      if (left == null || right == null || left.size != leaves.size || right.size != leaves.size)
        fail("WIDTH-COUNT", "substituted composite widths must match every certified leaf")
      leaves.indices.toVector.map(index => leaves(index).resultWidthFor(left(index), right(index)))
    }

    def replayLeaves(left: Vector[BaseType], right: Vector[BaseType],
        leftWidths: Vector[Width], rightWidths: Vector[Width]): Vector[BaseType] = {
      validateFreshness()
      if (left == null || right == null || left.size != leaves.size || right.size != leaves.size)
        fail("LEAF-COUNT", "replayed composite values must preserve the certified leaf inventory")
      leaves.indices.toVector.map(index => leaves(index).replayWithWidths(
        left(index), right(index), leftWidths(index), rightWidths(index)))
    }
  }

  def certify(callback: UnvalidatedBalancedCallback,
      left: Vector[Evidence], right: Vector[Evidence],
      captures: Vector[Evidence] = Vector.empty,
      conditionalGraph: Boolean = false): Proof = {
    if (callback == null || left == null || right == null || captures == null ||
        callback.operands == null || callback.operands.size != 2 || callback.result == null)
      fail("ARITY", "widening certification needs one exact native pair callback")
    val outputs = callback.result.flatten.toVector
    if (left.isEmpty || left.size != right.size || outputs.size != left.size)
      fail("SHAPE", "widening callback must retain one output for each corresponding input leaf")

    val usedDeclarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val usedAssignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val usedStatements = new IdentityHashMap[Statement, java.lang.Boolean]()

    val proofs = outputs.indices.toVector.map { index =>
      val declarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
      val assignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
      val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
      val whens = new IdentityHashMap[WhenStatement, java.lang.Boolean]()
      val owner = left(index).owner

      // A field's source cone includes its controlling expressions and native
      // lexical owners, not merely the right-hand sides of its assignments.
      // The enclosing composite observer independently proves that all effects
      // are captured, live and lexical; this partition cannot grant scope or
      // effect permission to a graph rejected by that observer.
      def walkScope(scope: ScopeStatement): Unit = {
        if (scope == null) fail("SCOPE", "a field driver lost its native lexical owner")
        if (scope ne owner.dslBody) {
          val statement = scope.parentStatement match {
            case value: WhenStatement if conditionalGraph => value
            case _ => fail("SCOPE", "field partition requires certified native conditional scopes")
          }
          if ((scope ne statement.whenTrue) && (scope ne statement.whenFalse))
            fail("SCOPE", "field partition changed its exact native conditional arm")
          if (whens.put(statement, java.lang.Boolean.TRUE) == null) {
            walk(statement.cond)
            walkScope(statement.parentScope)
          }
        }
      }

      def walk(expression: Expression): Unit = {
        if (expression == null || visited.put(expression, java.lang.Boolean.TRUE) != null) return
        expression match {
          case leaf: BaseType if (leaf eq left(index).value) || (leaf eq right(index).value) =>
          case leaf: BaseType if callback.declarations.exists(_ eq leaf) =>
            declarations.put(leaf, java.lang.Boolean.TRUE)
            if (conditionalGraph) walkScope(leaf.parentScope)
            callback.assignments.filter(_.finalTarget eq leaf).foreach { assignment =>
              assignments.put(assignment, java.lang.Boolean.TRUE)
              if (conditionalGraph) walkScope(assignment.parentScope)
              walk(assignment.source)
            }
          case _: BaseType => // Scalar replay rejects any unaudited external read.
          case other => other.foreachExpression(walk)
        }
      }
      walk(outputs(index))

      val declarationList = callback.declarations.filter(declarations.containsKey)
      val assignmentList = callback.assignments.filter(assignments.containsKey)
      declarationList.foreach { declaration =>
        if (usedDeclarations.put(declaration, java.lang.Boolean.TRUE) != null)
          fail("SHARED-LOCAL", "independent widening leaves cannot share a mutable native local")
      }
      assignmentList.foreach { assignment =>
        if (usedAssignments.put(assignment, java.lang.Boolean.TRUE) != null)
          fail("SHARED-DRIVER", "independent widening leaves cannot share a native assignment")
      }

      // An unchanged field may carry either corresponding operand through a
      // widening record. Admit only an exact full-object alias chain here;
      // the scalar graph certificate still proves its width transfer, owner,
      // complete driver inventory and freshness. This does not broaden the
      // original scalar reduction operator contract or admit cross-field reads.
      val aliasPath = new IdentityHashMap[BaseType, java.lang.Boolean]()
      def operandAlias(expression: Expression): Boolean = expression match {
        case value: BaseType if (value eq left(index).value) || (value eq right(index).value) => true
        case value: BaseType if declarations.containsKey(value) &&
            aliasPath.put(value, java.lang.Boolean.TRUE) == null =>
          assignmentList.filter(_.finalTarget eq value) match {
            case Vector(assignment: DataAssignmentStatement) if assignment.target eq value =>
              operandAlias(assignment.source)
            case _ => false
          }
        case _ => false
      }
      val scalarGraph = conditionalGraph || operandAlias(outputs(index))
      val statementList = if (scalarGraph) callback.statements.filter {
        case value: BaseType => declarations.containsKey(value)
        case value: AssignmentStatement => assignments.containsKey(value)
        case value: WhenStatement => whens.containsKey(value)
        case _ => false
      } else Vector.empty
      statementList.foreach(value => usedStatements.put(value, java.lang.Boolean.TRUE))
      val partition = UnvalidatedBalancedCallback(callback.ordinal,
        Vector(left(index).value, right(index).value), outputs(index), declarationList,
        assignmentList, statementList)
      if (scalarGraph)
        TypedBalancedReductionScalarGraphReplay.certify(
          partition, Vector(left(index), right(index)), captures.map(_.value))
      else TypedBalancedReductionOperatorReplay.certify(
        partition, Vector(left(index), right(index)), captures)
    }

    if (usedDeclarations.size != callback.declarations.size ||
        usedAssignments.size != callback.assignments.size ||
        (conditionalGraph && usedStatements.size != callback.statements.size))
      fail("UNCONSUMED-EFFECT", "shape-changing callback contains effects outside independent leaf graphs")
    val proof = new Proof(callback.result, proofs)
    proof.validateFreshness()
    proof
  }
}
