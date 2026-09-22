package morphhdl.examples

import java.util.IdentityHashMap

import scala.collection.mutable.ArrayBuffer
import scala.util.control.{NoStackTrace, NonFatal}

import morphhdl.ir.v1.{IntExpr, NameOrigin, ParameterId, RtlExpr, ScopeId, Signedness}
import morphhdl.passes.transform.AssignmentLowBitTruncationProof
import spinal.core._
import spinal.core.internals._

/** Final assignment-boundary lowering after the six canonical wire stages.
  *
  * Only the RHS of an existing full-object assignment is changed. A destination
  * may absorb its outer unsigned low slice when its authoritative width is that
  * slice's width over the complete legal domain. The input's original fixed
  * evaluation domain survives; every recursively substituted declaration gets
  * its own native packed fence. No scope, target, clock, reset, priority, name,
  * assignment kind or source program is changed.
  *
  * This is deliberately separate from arbitrary nested-selection inlining.
  * Generated/unnamed pure carriers are selected by genuine source provenance.
  * A shared carrier is removed only after a fresh global identity-use inventory
  * finds no remaining reference. Unsupported receivers keep the original wire.
  */
private[examples] final class AssignmentLowBitTruncationNativePhase(
    sourceIntent: NativeConditionSourceIntent
) extends Phase {
  private case object Unproved extends RuntimeException with NoStackTrace
  private final case class Boundary(input: Expression, receiver: ElaborationIntegerExpression,
      sourceWidth: Int, ranged: Boolean)
  private final case class Definition(alias: BaseType, driver: DataAssignmentStatement,
      source: Expression, width: Int, origin: NameOrigin)
  private final case class Plan(source: Expression, definitions: Vector[Definition],
      copies: Vector[(BaseType, Int, Int)])

  private var completed = false
  private var assignments = 0
  private var declarations = 0
  private var references = 0
  private val spentCopies = new IdentityHashMap[BaseType, java.lang.Integer]()
  private val spentNodes = new IdentityHashMap[BaseType, java.lang.Integer]()
  private val selected = ArrayBuffer.empty[Definition]

  def rewrittenAssignments: Int = assignments
  def removedDeclarations: Int = declarations
  def rewrittenReferences: Int = references
  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    require(!completed, "WIRE-TRUNC-01 phase executed twice")
    var progress = true
    var rounds = 0
    while (progress) {
      rounds += 1
      require(rounds <= 1024, "WIRE-TRUNC-01 assignment lowering did not converge")
      progress = false
      pc.components().foreach { component =>
        statementsOf(component).foreach {
          case assignment: DataAssignmentStatement =>
            prepare(pc, component, assignment).foreach { plan =>
              assignment.source = plan.source
              assignments += 1
              plan.copies.foreach { case (alias, count, nodes) =>
                spentCopies.put(alias, used(spentCopies, alias) + count)
                spentNodes.put(alias, used(spentNodes, alias) + nodes)
                references += count
              }
              plan.definitions.foreach { definition =>
                if (!selected.exists(_.alias eq definition.alias)) selected += definition
              }
              progress = true
            }
          case _ =>
        }
      }
      // Recompute across all components, not merely this rewritten receiver.
      // Reverse dependencies can become dead only after their outer carrier is
      // deleted, so repeat this bounded, selected-identity-only liveness sweep.
      var removed = true
      while (removed) {
        removed = false
        selected.reverseIterator.foreach { definition =>
          val alias = definition.alias
          if (alias.hasOnlyOneStatement && (alias.head eq definition.driver) &&
              !pc.components().exists(component => statementsOf(component).exists { statement =>
                var found = false
                statement.walkDrivingExpressions {
                  case value: BaseType if value eq alias => found = true
                  case _ =>
                }
                found
              }) &&
              new NamedWireAliasNativePhase(sourceIntent = Some(sourceIntent))
                .expressionRemovalBlocker(pc, alias.component, alias, definition.driver,
                  Vector.empty).isEmpty) {
            definition.driver.removeStatement()
            alias.removeStatement()
            declarations += 1
            removed = true
          }
        }
      }
    }
    completed = true
  }

  private def used(values: IdentityHashMap[BaseType, java.lang.Integer], alias: BaseType): Int = {
    val value = values.get(alias)
    if (value == null) 0 else value.intValue
  }

  private def unsigned(value: Expression): Boolean = value != null &&
    (value.getTypeObject == TypeUInt || value.getTypeObject == TypeBits)

  private def bitWidth(value: Expression): Int = value match {
    case _ if value != null && value.getTypeObject == TypeBool => 1
    case sized: WidthProvider => sized.getWidth
    case _ => -1
  }

  private def width(value: Expression): Option[ElaborationIntegerExpression] =
    NativeWidthProvenance.optionalWidthOf(value)
      .filter(result => result.minimum > 0 && result.default == bitWidth(value))

  private def equal(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): Boolean =
    try ElaborationWidthAuthority.equivalent(left, right)
    catch { case NonFatal(_) => false }

  private def unannotated(value: Expression): Boolean = value match {
    case tagged: SpinalTagReady => tagged.isEmptyOfTag
    case _ => true
  }

  /** Exact native ownership of a concrete declaration width. `BitVector` keeps
    * this field package-private to spinal.core; this downstream phase must not
    * broaden that API merely for an optimization. Reading the compiler's own
    * field is equivalent to BitVector.isFixedWidth and does not infer ownership
    * from the current driver width or emitted spelling. Any reflection failure
    * retains the original assignment.
    */
  private def hasFixedDeclaredWidth(value: BitVector): Boolean = {
    var current: Class[_] = value.getClass
    while (current != null) {
      try {
        val field = current.getDeclaredField("fixedWidth")
        field.setAccessible(true)
        return field.getInt(value) != -1
      } catch {
        case _: NoSuchFieldException => current = current.getSuperclass
        case NonFatal(_) => return false
      }
    }
    false
  }

  private def boundary(assignment: DataAssignmentStatement): Option[Boundary] = {
    val target = assignment.finalTarget
    if (!(assignment.target eq target) || !unsigned(target) || target.isAnalog ||
        target.isInputOrInOut || assignment.parentScope == null ||
        target.parentScope == null || !(target.parentScope eq target.rootScopeStatement) ||
        !target.getTags().forall(_ eq noBackendCombMerge) ||
        NativeWireAssignmentMetadata.retains(target)) return None
    // The receiver must own its width independently of the RHS being changed.
    // An inferred expression carrier can still be recursively substituted at
    // another stable receiver; its own width-defining driver is not widened.
    target match {
      case vector: BitVector if hasFixedDeclaredWidth(vector) ||
          ParameterizedWidth.expressionOf(vector).nonEmpty =>
      case _ => return None
    }
    val receiver = width(target).getOrElse(return None)
    val root = assignment.source
    if (root == null || !unsigned(root) || root.getTypeObject != target.getTypeObject ||
        !unannotated(root) || !width(root).exists(equal(_, receiver))) return None
    val (input, ranged) = root match {
      case resize: Resize if resize.isInstanceOf[ResizeUInt] || resize.isInstanceOf[ResizeBits] =>
        (resize.input: Expression, false)
      case access: BitVectorRangedAccessFixed if access.lo == 0 =>
        (access.source: Expression, true)
      case _ => return None
    }
    if (!unsigned(input) || input.getTypeObject != target.getTypeObject ||
        !NativeWireExpressionCodec.fixedWidthTree(input)) return None
    val source = width(input).getOrElse(return None)
    if (source.parameters.nonEmpty || source.minimum != source.maximum ||
        source.default != source.minimum || receiver.maximum > source.minimum ||
        receiver.maximum < receiver.minimum) return None
    Some(Boundary(input, receiver, bitWidth(input), ranged))
  }

  private def prepare(pc: PhaseContext, component: Component,
      assignment: DataAssignmentStatement): Option[Plan] = {
    if (!(assignment.finalTarget.component eq component)) return None
    val selectedBoundary = boundary(assignment).getOrElse(return None)
    val target = assignment.finalTarget
    val continuous = target.isComb && target.hasOnlyOneStatement &&
      (assignment.parentScope eq assignment.rootScopeStatement)
    val nonblocking = target.isReg && target.clockDomain != null
    if (!continuous && !nonblocking && !target.isComb) return None

    val definitions = ArrayBuffer.empty[Definition]
    val counts = new IdentityHashMap[BaseType, java.lang.Integer]()
    val costs = new IdentityHashMap[BaseType, java.lang.Integer]()
    val active = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val safety = new NamedWireAliasNativePhase(sourceIntent = Some(sourceIntent))
    var remaining = 256

    def candidate(alias: BaseType): Option[Definition] = {
      val origin = NativeWireNameProvenance.origin(alias).filter(value =>
        value == NameOrigin.Generated || value == NameOrigin.Unnamed).getOrElse(return None)
      if (!(alias.component eq component) || !alias.isComb || !alias.isDirectionLess ||
          !unsigned(alias) || alias.isAnalog || alias.isInOut ||
          !sourceIntent.permits(alias) || alias.parentScope == null ||
          !(alias.parentScope eq alias.rootScopeStatement) || !alias.hasOnlyOneStatement)
        return None
      val own = width(alias).getOrElse(return None)
      if (own.parameters.nonEmpty || own.minimum != own.maximum) return None
      alias.head match {
        case driver: DataAssignmentStatement if (driver.target eq alias) &&
            (driver.finalTarget eq alias) && (driver.parentScope eq alias.rootScopeStatement) &&
            driver.source != null && driver.source.getTypeObject == alias.getTypeObject &&
            width(driver.source).exists(source => source.parameters.isEmpty &&
              source.minimum == source.maximum && source.minimum >= own.maximum) &&
            NativeWireExpressionCodec.fixedWidthTree(driver.source) =>
          // An earlier round may already have lowered this fixed receiver's
          // low slice. Its wider RHS still evaluates in its own domain. The
          // declaration fence recreated below restores the exact packed value
          // at every substituted use, including such already-lowered drivers.
          // Blocking receivers are not asserted to be continuous: their
          // independent input/register-only sampling proof is checked below.
          val receivers = if (continuous || nonblocking) Vector[Statement](assignment) else Vector.empty[Statement]
          if (safety.expressionRemovalBlocker(pc, component, alias, driver, receivers,
              allowRegisterRhs = true).nonEmpty || NativePureExpressionCopy(driver.source).isEmpty) None
          else Some(Definition(alias, driver, driver.source, alias.getBitsWidth, origin))
        case _ => None
      }
    }

    def expand(value: Expression, depth: Int): Expression = {
      remaining -= 1
      if (remaining < 0 || depth > 32 || value == null) throw Unproved
      value match {
        case alias: BaseType =>
          if (alias.getTypeObject == TypeSInt || (alias.component ne component) ||
              ((alias eq target) && target.isComb)) throw Unproved
          candidate(alias) match {
            case None => alias
            case Some(definition) =>
              if (active.put(alias, java.lang.Boolean.TRUE) != null) throw Unproved
              val count = used(counts, alias) + 1
              val cost = used(costs, alias) + boundedSize(definition.source)
              if (used(spentCopies, alias) + count > 32 || used(spentNodes, alias) + cost > 256)
                throw Unproved
              counts.put(alias, count)
              costs.put(alias, cost)
              if (!definitions.exists(_.alias eq alias)) definitions += definition
              val copied = NativePureExpressionCopy(definition.source).getOrElse(throw Unproved)
              val result = NativeWireExpressionCodec.fenced(expand(copied, depth + 1), alias)
              active.remove(alias)
              result
          }
        case _ =>
          if (value.getTypeObject == TypeSInt || !unannotated(value)) throw Unproved
          val stable = new IdentityHashMap[Expression, java.lang.Boolean]()
          // The native remapper stabilizes each edge by visiting its result.
          // Mark only completed replacements, not their original BaseType:
          // repeated reads of one carrier must receive independently owned
          // operator trees, just like NativePureExpressionCopy's edge copies.
          value.remapDrivingExpressions { child =>
            if (stable.containsKey(child)) child
            else {
              val replacement = expand(child, depth + 1)
              stable.put(replacement, java.lang.Boolean.TRUE)
              replacement
            }
          }
          value
      }
    }

    try {
      val copied = NativePureExpressionCopy(selectedBoundary.input).getOrElse(return None)
      val replacement = expand(copied, 0)
      if (boundedSize(replacement) > 256) return None
      // Do not make an explicitly named/protected whole carrier newly eligible
      // merely by stripping its select. References inside an expanded pure
      // expression retain their original names and metadata without alteration.
      if (selectedBoundary.input.isInstanceOf[BaseType] && definitions.isEmpty) return None
      if (!continuous && !nonblocking && !independentBlockingInputs(replacement, component)) return None

      val scope = ScopeId.unsafe("scope.wire-trunc.assignment")
      val codec = new NativeWireExpressionCodec(scope, "wire-trunc")
      val beforeInput = codec.capture(selectedBoundary.input).getOrElse(return None)
      val capturedDefinitions = definitions.toVector.map { definition =>
        codec.capture(definition.alias).getOrElse(return None)
        val id = codec.capturedSources.find(_._1 eq definition.alias).get._2
        val value = codec.capture(definition.source).getOrElse(return None)
        AssignmentLowBitTruncationProof.Definition(id, definition.width, value, definition.origin)
      }
      val after = codec.capture(replacement).getOrElse(return None)
      val receiverWidth = if (selectedBoundary.receiver.parameters.isEmpty)
        IntExpr.Literal(selectedBoundary.receiver.default)
      else IntExpr.ParameterRef(ParameterId.unsafe("parameter.wire-trunc.receiver-width"))
      val before = if (selectedBoundary.ranged)
        RtlExpr.PartSelect(beforeInput, IntExpr.Literal(BigInt(0)), receiverWidth)
      else RtlExpr.Resize(beforeInput, receiverWidth, Signedness.Unsigned)
      if (AssignmentLowBitTruncationProof.prove(before, after, receiverWidth,
          selectedBoundary.sourceWidth, selectedBoundary.receiver.minimum,
          selectedBoundary.receiver.maximum, capturedDefinitions).isEmpty) return None
      Some(Plan(replacement, definitions.toVector, definitions.toVector.map { definition =>
        (definition.alias, used(counts, definition.alias), used(costs, definition.alias))
      }))
    } catch { case Unproved => None }
  }

  private def boundedSize(expression: Expression): Int = {
    val pending = ArrayBuffer(expression)
    var count = 0
    while (pending.nonEmpty && count <= 256) {
      val next = pending.remove(pending.size - 1)
      count += 1
      if (next == null) return 257
      next match {
        case _: BaseType =>
        case _ => next.foreachDrivingExpression(child => pending += child)
      }
    }
    if (pending.nonEmpty) 257 else count
  }

  /** No possibly coalesced blocking writer can modify an input or register
    * during this process. A retained combinational dependency is deliberately
    * insufficient evidence, even when a default-width simulation looks safe.
    * Nonempty dependencies also avoid creating an empty @* sensitivity list.
    */
  private def independentBlockingInputs(expression: Expression, component: Component): Boolean = {
    var dependencies = 0
    var valid = true
    def visit(value: Expression): Unit = value match {
      case base: BaseType =>
        dependencies += 1
        if ((base.component ne component) || (!base.isInput && !base.isReg) ||
            base.isAnalog || base.isInOut) valid = false
      case _ => value.foreachDrivingExpression(visit)
    }
    visit(expression)
    valid && dependencies > 0
  }

  private def statementsOf(component: Component): Vector[Statement] = {
    val result = Vector.newBuilder[Statement]
    component.dslBody.walkStatements(result += _)
    result.result()
  }
}
