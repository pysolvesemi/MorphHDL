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

  /** A condition may be printed once per split process even when the native
    * expression has only one use. Keep this distinct from arbitrary expression
    * sharing: the ordinary complete sizing proof must already accept the exact
    * condition identity, and the complete subtree supplies a conservative
    * upper bound on the number of emitted process copies. This only declines
    * the condition-sharing request; other mandatory wrapping reasons survive.
    */
  private[internals] def redundantSharedCondition(
      component: Component,
      config: SpinalConfig,
      expression: Expression,
      approved: IdentityHashMap[Expression, java.lang.Boolean]
  ): Boolean = {
    if (!isEnabled(config) || !approved.containsKey(expression) ||
        expression.getTypeObject != TypeBool) return false
    val pending = ArrayBuffer(expression)
    var nodes = 0
    while (pending.nonEmpty) {
      val next = pending.remove(pending.size - 1)
      nodes += 1
      if (next == null || nodes > 64) return false
      next match {
        case _: BaseType =>
        case _ => next.foreachDrivingExpression(child => pending += child)
      }
    }
    var nativeConditions = 0
    var leaves = 0
    component.dslBody.walkStatements {
      case when: WhenStatement if when.cond eq expression =>
        nativeConditions += 1
        when.whenTrue.walkLeafStatements(_ => leaves += 1)
        when.whenFalse.walkLeafStatements(_ => leaves += 1)
      case _ =>
    }
    nativeConditions == 1 && leaves >= 1 && leaves <= 32 &&
      leaves.toLong * nodes <= 256
  }

  /** A late identity-sized unsigned resize can itself be a legal select base:
    * its printer emits only the existing declaration's reference. This is not
    * expression slicing or arithmetic reassociation. Every link must retain
    * the exact native type and fixed width; annotations and symbolic resizing
    * fail closed. The real source (including its name/protection) is untouched.
    */
  private[internals] def directSelectBase(
      component: Component,
      expression: Expression
  ): Option[BaseType] = {
    def follow(node: Expression, depth: Int): Option[BaseType] = {
      if (node == null || depth > 32) return None
      node match {
        case base: BaseType if (base.component eq component) &&
            (base.getTypeObject == TypeUInt || base.getTypeObject == TypeBits) &&
            base.getBitsWidth > 0 && !base.isAnalog && !base.isInOut &&
            ParameterizedWidth.expressionOf(base).isEmpty => Some(base)
        case resize: Resize if
            (resize.isInstanceOf[ResizeUInt] || resize.isInstanceOf[ResizeBits]) &&
            isUnannotated(resize) && resize.input != null && resize.size > 0 &&
            resize.size == resize.input.getWidth &&
            resize.getTypeObject == resize.input.getTypeObject &&
            ParameterizedWidth.resizeExpressionOf(resize).isEmpty =>
          follow(resize.input, depth + 1)
        case _ => None
      }
    }
    follow(expression, 0)
  }

  /** Prove which synthetic expression carriers can be omitted, before emission.
    *
    * Verilog propagates an assignment/arithmetic/comparison/mux context into
    * unsigned operands. Parentheses do not stop that propagation. Every such
    * edge below therefore requires the exact native width and unsigned type.
    * A widening resize emits a concatenation: its operand is self-determined,
    * so narrower arithmetic can be visited in its original modular domain.
    * A narrowing resize uses a pure Verilog-2001 function when its source is
    * an expression, preserving both its input evaluation width and exact slice.
    *
    * This only plans emitter-created carriers. It never removes a BaseType,
    * assignment, register, scope or clock edge, and never reassociates algebra.
    * Signed, annotated and unknown expression boundaries fail closed. Symbolic
    * widths require native typed provenance and whole-domain width equality.
    * Shared nodes require proof at every occurrence and bounded duplication.
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

    val widths = new IdentityHashMap[Expression, Option[ElaborationIntegerExpression]]()
    def logicalWidth(expression: Expression): Option[ElaborationIntegerExpression] = {
      if (!widths.containsKey(expression)) widths.put(expression,
        NativeWidthProvenance.widthOf(expression).filter(value => value.minimum > 0 &&
          value.default == width(expression)))
      widths.get(expression)
    }
    def sameWidth(left: Option[ElaborationIntegerExpression],
                  right: Option[ElaborationIntegerExpression]): Boolean = (left, right) match {
      case (Some(a), Some(b)) => ElaborationWidthAuthority.equivalent(a, b)
      case _ => false
    }
    def fixedWidth(value: Int): Option[ElaborationIntegerExpression] =
      Some(ElabInt.literal(value).expression)
    val approvals = new IdentityHashMap[Expression, java.lang.Integer]()

    def unsignedKind(expression: Expression): Boolean =
      expression.getTypeObject == TypeUInt || expression.getTypeObject == TypeBits

    def fixedTargetBoundary(target: BaseType): Boolean =
      (unsignedKind(target) || target.getTypeObject == TypeBool) &&
        width(target) > 0 && target.component == component &&
        // This layout-only flag protects process separation. The planner
        // changes neither the target nor its driver/scope, so retain the flag
        // while proving its pure RHS. All other target metadata still fences
        // optimization; use singleton identity, never custom tag equality.
        target.getTags().forall(_ eq noBackendCombMerge) &&
        logicalWidth(target).nonEmpty

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

    def plan(root: Expression, contextWidth: Option[ElaborationIntegerExpression]): Unit = {
      val wrappers = ArrayBuffer[(Expression, Int)]()
      val published = scala.collection.mutable.HashSet[Int]()
      var occurrenceId = 0
      val visiting = new IdentityHashMap[Expression, java.lang.Boolean]()
      var budget = 256

      def publish(nodes: scala.collection.Seq[(Expression, Int)]): Unit = nodes.foreach { case (node, id) =>
        if (published.add(id)) {
          val count = approvals.get(node)
          approvals.put(node, if (count == null) 1 else count.intValue + 1)
        }
      }

      // Logical operands and a mux condition are self-determined. An unknown
      // sibling cannot change their evaluation width. Publish each successful
      // Boolean subtree independently, while keeping arithmetic/comparison/
      // mux-value proofs transactional across the complete sizing context.
      def independentBoolean(expression: Expression): Boolean = {
        val checkpoint = wrappers.size
        val proven = collect(expression, fixedWidth(1))
        if (proven) publish(wrappers.drop(checkpoint))
        else wrappers.trimEnd(wrappers.size - checkpoint)
        proven
      }

      def collect(expression: Expression, expectedWidth: Option[ElaborationIntegerExpression]): Boolean = {
        budget -= 1
        occurrenceId += 1
        val id = occurrenceId
        if (budget < 0 || !sameWidth(logicalWidth(expression), expectedWidth) ||
            visiting.containsKey(expression)) return false

        expression match {
          // Annotations on real leaves protect their declaration, which this
          // policy does not remove. Synthetic annotated nodes remain fenced.
          case leaf: BaseType =>
            return leaf.component == component &&
              (unsignedKind(leaf) || leaf.getTypeObject == TypeBool) &&
              logicalWidth(leaf).nonEmpty
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
          val operandWidth = logicalWidth(node.left)
          sameWidth(expectedWidth, fixedWidth(1)) && operandWidth.nonEmpty &&
            unsignedKind(node.left) && node.right.getTypeObject == node.left.getTypeObject &&
            sameWidth(logicalWidth(node.right), operandWidth) &&
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
          case node: Operator.Bits.Equal          => comparison(node)
          case node: Operator.Bits.NotEqual       => comparison(node)
          case node: Operator.Bits.EqualSim       => comparison(node)

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
            logicalWidth(node.input).exists { source =>
              // Only invariant fixed padding or an exact low slice has a
              // printer here. A resize whose parameter domain crosses the
              // destination width remains owned by its existing publisher.
              val fixedSource = source.parameters.isEmpty
              (fixedSource || (source.minimum >= node.size && source.default > node.size)) &&
                collect(node.input, Some(source))
            }

          case node: BitVectorRangedAccessFixed if unsignedKind(node) && unsignedKind(node.source) =>
            logicalWidth(node.source).exists { source =>
              node.lo >= 0 && node.hi >= node.lo && source.minimum > node.hi &&
                collect(node.source, Some(source))
            }

          case node: BitVectorBitAccessFixed if unsignedKind(node.source) =>
            logicalWidth(node.source).exists { source =>
              node.bitId >= 0 && source.minimum > node.bitId &&
                collect(node.source, Some(source))
            }

          case node: Operator.BitVector.ShiftRightByUInt if unsignedKind(node) =>
            node.left.getTypeObject == node.getTypeObject && node.right.getTypeObject == TypeUInt &&
              collect(node.left, expectedWidth) && collect(node.right, logicalWidth(node.right))
          case node: Operator.BitVector.ShiftRightByIntFixedWidth if unsignedKind(node) =>
            node.source.getTypeObject == node.getTypeObject && collect(node.source, expectedWidth)
          case node: Operator.BitVector.ShiftLeftByIntFixedWidth if unsignedKind(node) =>
            node.source.getTypeObject == node.getTypeObject && collect(node.source, expectedWidth)
          case node: Operator.BitVector.ShiftLeftByUIntFixedWidth if unsignedKind(node) =>
            node.left.getTypeObject == node.getTypeObject && node.right.getTypeObject == TypeUInt &&
              collect(node.left, expectedWidth) && collect(node.right, logicalWidth(node.right))

          case node: CastUIntToBits => collect(node.input, expectedWidth)
          case node: CastBitsToUInt => collect(node.input, expectedWidth)

          case node: Operator.Bits.Cat =>
            unsignedKind(node.left) && unsignedKind(node.right) &&
              collect(node.left, logicalWidth(node.left)) && collect(node.right, logicalWidth(node.right))

          case _ => false
        }
        visiting.remove(expression)
        if (proven)
          wrappers += ((expression, id))
        proven
      }

      if (collect(root, contextWidth)) publish(wrappers)
    }

    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement => assignment.target match {
        case target: BaseType if eligibleTarget(target) =>
          plan(assignment.source, logicalWidth(target))
        // Whole-register assignments each establish their own sizing context.
        // Prove the existing pure RHS at that exact location; initialization,
        // priority, clock/reset ownership and blocking/nonblocking order stay
        // untouched. This does not inline a register or move any assignment.
        case target: BaseType if target.isReg && fixedTargetBoundary(target) =>
          plan(assignment.source, logicalWidth(target))
        // A no-op unsigned carrier which prints an existing reference has no
        // arithmetic or assignment-context obligation. This narrow fallback
        // handles repeated/nested register updates without enabling arbitrary
        // expression inlining across multiple assignments or priority trees.
        case target: BaseType if fixedTargetBoundary(target) =>
          assignment.source.walkExpression {
            case resize: Resize if directSelectBase(component, resize).nonEmpty =>
              result.put(resize, java.lang.Boolean.TRUE)
            case _ =>
          }
        // Static disjoint selections have an exact receiver width. Only the
        // pure RHS is planned; target selection, driver and procedural scope
        // remain untouched. Overlap, whole-object overrides and dynamic
        // selects retain the historical wrappers.
        case target: AssignmentExpression if eligibleSelection(target) =>
          plan(assignment.source, fixedWidth(width(target)))
        case _ =>
      }
      case conditional: WhenStatement => plan(conditional.cond, fixedWidth(1))
      case _ =>
    }
    // Each shared use must independently pass its receiving sizing context.
    // Approving one sibling must not release a carrier used by an unsupported
    // sibling. Expanded walk counts and per-root budgets bound text growth.
    val subtreeSizes = new IdentityHashMap[Expression, java.lang.Integer]()
    val sizing = new IdentityHashMap[Expression, java.lang.Boolean]()
    def expandedSize(node: Expression): Int = {
      if (node == null || sizing.containsKey(node)) return 257
      val cached = subtreeSizes.get(node)
      if (cached != null) return cached.intValue
      sizing.put(node, java.lang.Boolean.TRUE)
      var size = 1
      node match {
        case _: BaseType =>
        case _ => node.foreachDrivingExpression { child =>
          if (size <= 256) size = math.min(257, size + expandedSize(child))
        }
      }
      sizing.remove(node)
      subtreeSizes.put(node, size)
      size
    }
    val approved = approvals.entrySet().iterator()
    while (approved.hasNext) {
      val entry = approved.next()
      val count = occurrences.get(entry.getKey)
      if (count != null && entry.getValue.intValue == count.intValue && count.intValue <= 32 &&
          expandedSize(entry.getKey).toLong * count.intValue <= 256)
        result.put(entry.getKey, java.lang.Boolean.TRUE)
    }
    result
  }
}
