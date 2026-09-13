package spinal.core.internals

import java.util.IdentityHashMap

import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** Internal opt-in for MorphHDL's typed Verilog expression-inlining policy.
  * Ordinary SpinalVerilog configurations never install this marker.
  */
object VerilogEmitterExpressionInlining {
  private object EnabledProperty extends ScopeProperty[Boolean]

  def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig = {
    if (config == null)
      throw new IllegalArgumentException("SpinalConfig must not be null")

    val properties = config.scopeProperties.clone()
    if (enabled) properties.update(EnabledProperty, true)
    else properties.remove(EnabledProperty)
    config.copy(scopeProperties = properties)
  }

  private[internals] def isEnabled(config: SpinalConfig): Boolean =
    config != null && config.scopeProperties.get(EnabledProperty).contains(true)

  private[internals] def isUnannotated(expression: Expression): Boolean =
    expression match {
      case tagged: SpinalTagReady => tagged.isEmptyOfTag
      case _                      => true
    }

  /** Prove which synthetic expression carriers can be omitted, before emission.
    *
    * Verilog propagates an assignment/arithmetic/comparison/mux context into
    * unsigned operands. Parentheses do not stop that propagation. Every such
    * edge below therefore requires the exact native width and unsigned type.
    * A widening resize emits a concatenation: its operand is self-determined,
    * so narrower arithmetic can be visited in its original modular domain.
    * A narrowing resize instead needs a reference as its select base in
    * Verilog-2001; that one carrier stays, while its children can still inline.
    *
    * This only plans emitter-created carriers. It never removes a BaseType,
    * assignment, register, scope or clock edge, and never reassociates algebra.
    * Signed, symbolic, annotated, shared and unknown expression boundaries
    * fail closed. The node budget also bounds work and textual expression size.
    */
  private[internals] def redundantWrappers(
      component: Component,
      config: SpinalConfig
  ): IdentityHashMap[Expression, java.lang.Boolean] = {
    val result = new IdentityHashMap[Expression, java.lang.Boolean]()
    if (!isEnabled(config)) return result

    val occurrences = new IdentityHashMap[Expression, java.lang.Integer]()
    component.dslBody.walkStatements { statement =>
      statement.walkDrivingExpressions { expression =>
        val previous = occurrences.get(expression)
        occurrences.put(expression, if (previous == null) 1 else previous.intValue + 1)
      }
    }

    def width(expression: Expression): Int = expression match {
      case _ if expression.getTypeObject == TypeBool => 1
      case sized: WidthProvider                     => sized.getWidth
      case _                                        => -1
    }

    def unsignedKind(expression: Expression): Boolean =
      expression.getTypeObject == TypeUInt || expression.getTypeObject == TypeBits

    def fixedTargetBoundary(target: BaseType): Boolean =
      (unsignedKind(target) || target.getTypeObject == TypeBool) &&
        width(target) > 0 && target.component == component &&
        target.isEmptyOfTag && ParameterizedWidth.expressionOf(target).isEmpty

    def eligibleTarget(target: BaseType): Boolean = {
      var dataAssignments = 0
      target.foreachStatements {
        case _: DataAssignmentStatement => dataAssignments += 1
        case _ =>
      }
      fixedTargetBoundary(target) && dataAssignments == 1
    }

    def fixedSelection(expression: Expression): Option[(Int, Int)] = expression match {
      case bit: BitAssignmentFixed => Some((bit.bitId, bit.bitId))
      case range: RangedAssignmentFixed => Some((range.lo, range.hi))
      case _ => None
    }

    def eligibleSelection(selection: AssignmentExpression): Boolean = {
      val target = selection.finalTarget
      if (!fixedTargetBoundary(target) || fixedSelection(selection).isEmpty) return false
      val ranges = ArrayBuffer[(Int, Int)]()
      var valid = true
      target.foreachStatements {
        case assignment: DataAssignmentStatement => fixedSelection(assignment.target) match {
          case Some((lo, hi)) if lo >= 0 && hi >= lo && hi < width(target) =>
            if (ranges.exists { case (otherLo, otherHi) => lo <= otherHi && otherLo <= hi })
              valid = false
            ranges += ((lo, hi))
          case _ => valid = false
        }
        case _ =>
      }
      valid && ranges.nonEmpty
    }

    def plan(root: Expression, contextWidth: Int): Unit = {
      val wrappers = ArrayBuffer[Expression]()
      val visiting = new IdentityHashMap[Expression, java.lang.Boolean]()
      var budget = 256

      def publish(nodes: scala.collection.Seq[Expression]): Unit = nodes.foreach { node =>
        val count = occurrences.get(node)
        if (count != null && count.intValue == 1)
          result.put(node, java.lang.Boolean.TRUE)
      }

      // Logical operands and a mux condition are self-determined. An unknown
      // sibling cannot change their evaluation width. Publish each successful
      // Boolean subtree independently, while keeping arithmetic/comparison/
      // mux-value proofs transactional across the complete sizing context.
      def independentBoolean(expression: Expression): Boolean = {
        val checkpoint = wrappers.size
        val proven = collect(expression, 1)
        if (proven) publish(wrappers.drop(checkpoint))
        else wrappers.trimEnd(wrappers.size - checkpoint)
        proven
      }

      def collect(expression: Expression, expectedWidth: Int, referenceRequired: Boolean = false): Boolean = {
        budget -= 1
        if (budget < 0 || expectedWidth <= 0 || width(expression) != expectedWidth ||
            visiting.containsKey(expression)) return false

        expression match {
          // Annotations on real leaves protect their declaration, which this
          // policy does not remove. Synthetic annotated nodes remain fenced.
          case leaf: BaseType =>
            return leaf.component == component &&
              (unsignedKind(leaf) || leaf.getTypeObject == TypeBool) &&
              ParameterizedWidth.expressionOf(leaf).isEmpty
          case _ if !isUnannotated(expression) => return false
          case _ =>
        }

        visiting.put(expression, java.lang.Boolean.TRUE)
        def binary(node: BinaryOperator, kind: Any): Boolean =
          node.left.getTypeObject == kind && node.right.getTypeObject == kind && {
            if (kind == TypeBool)
              independentBoolean(node.left) & independentBoolean(node.right)
            else collect(node.left, expectedWidth) && collect(node.right, expectedWidth)
          }

        def comparison(node: BinaryOperator): Boolean = {
          val operandWidth = width(node.left)
          expectedWidth == 1 && operandWidth > 0 &&
            node.left.getTypeObject == TypeUInt && node.right.getTypeObject == TypeUInt &&
            width(node.right) == operandWidth &&
            collect(node.left, operandWidth) && collect(node.right, operandWidth)
        }

        val proven = expression match {
          case _: BoolLiteral | _: UIntLiteral | _: BitsLiteral => true

          case node: Operator.UInt.Add => binary(node, TypeUInt)
          case node: Operator.UInt.Sub => binary(node, TypeUInt)
          case node: Operator.UInt.And => binary(node, TypeUInt)
          case node: Operator.UInt.Or  => binary(node, TypeUInt)
          case node: Operator.UInt.Xor => binary(node, TypeUInt)
          case node: Operator.UInt.Not =>
            node.source.getTypeObject == TypeUInt && collect(node.source, expectedWidth)

          case node: Operator.Bits.And => binary(node, TypeBits)
          case node: Operator.Bits.Or  => binary(node, TypeBits)
          case node: Operator.Bits.Xor => binary(node, TypeBits)
          case node: Operator.Bits.Not =>
            node.source.getTypeObject == TypeBits && collect(node.source, expectedWidth)

          case node: Operator.UInt.Equal          => comparison(node)
          case node: Operator.UInt.NotEqual       => comparison(node)
          case node: Operator.UInt.EqualSim       => comparison(node)
          case node: Operator.UInt.Smaller        => comparison(node)
          case node: Operator.UInt.SmallerOrEqual => comparison(node)

          case node: Operator.Bool.And      => binary(node, TypeBool)
          case node: Operator.Bool.Or       => binary(node, TypeBool)
          case node: Operator.Bool.Xor      => binary(node, TypeBool)
          case node: Operator.Bool.Equal    => binary(node, TypeBool)
          case node: Operator.Bool.NotEqual => binary(node, TypeBool)
          case node: Operator.Bool.EqualSim => binary(node, TypeBool)
          case node: Operator.Bool.Not      => independentBoolean(node.source)

          case node: BinaryMultiplexer
              if unsignedKind(node) || node.getTypeObject == TypeBool =>
            node.cond.getTypeObject == TypeBool &&
              node.whenTrue.getTypeObject == node.getTypeObject &&
              node.whenFalse.getTypeObject == node.getTypeObject &&
              {
                val condition = independentBoolean(node.cond)
                val values = if (node.getTypeObject == TypeBool)
                  independentBoolean(node.whenTrue) & independentBoolean(node.whenFalse)
                else collect(node.whenTrue, expectedWidth) && collect(node.whenFalse, expectedWidth)
                condition && values
              }

          case node: Resize
              if (node.isInstanceOf[ResizeUInt] || node.isInstanceOf[ResizeBits]) &&
                node.input.getTypeObject == node.getTypeObject &&
                ParameterizedWidth.resizeExpressionOf(node).isEmpty =>
            collect(node.input, node.input.getWidth,
              referenceRequired = node.size < node.input.getWidth)

          case node: CastUIntToBits => collect(node.input, expectedWidth)
          case node: CastBitsToUInt => collect(node.input, expectedWidth)

          case node: Operator.Bits.Cat =>
            unsignedKind(node.left) && unsignedKind(node.right) &&
              width(node.left) + width(node.right) == expectedWidth &&
              collect(node.left, width(node.left)) && collect(node.right, width(node.right))

          case _ => false
        }
        visiting.remove(expression)
        if (proven && !referenceRequired) wrappers += expression
        proven
      }

      if (collect(root, contextWidth)) publish(wrappers)
    }

    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement => assignment.target match {
        case target: BaseType if eligibleTarget(target) =>
          plan(assignment.source, width(target))
        // Static disjoint selections have an exact receiver width. Only the
        // pure RHS is planned; target selection, driver and procedural scope
        // remain untouched. Overlap, whole-object overrides and dynamic
        // selects retain the historical wrappers.
        case target: AssignmentExpression if eligibleSelection(target) =>
          plan(assignment.source, width(target))
        case _ =>
      }
      case conditional: WhenStatement => plan(conditional.cond, 1)
      case _ =>
    }
    result
  }
}
