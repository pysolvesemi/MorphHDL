package morphhdl.examples

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import morphhdl.ir.v1.{IntExpr, NameOrigin, ParameterId, RtlExpr, ScopeId, Signedness}
import morphhdl.passes.transform.AssignmentLowBitTruncationProof
import spinal.core._
import spinal.core.internals._

/** Final WIRE-TRUNC-01 receiver forwarding after the assignment-boundary phase.
  *
  * A captured symbolic resize can survive native normalization as two compiler
  * carriers even after its exact resize ownership has been consumed:
  *
  *   fixedCarrier := fixedExpression
  *   symbolicCarrier := fixedCarrier
  *   receiver := symbolicCarrier
  *
  * A process-scoped receiver can also keep the captured symbolic resize owner
  * fresh, in which case the live owner assignment still contains its Resize.
  * In both forms this phase replays the same canonical low-projection proof from
  * the exact captured fixed source identity and substitutes only whole-RHS reads
  * whose destination owns the same symbolic packed width; carrier declarations themselves are
  * deliberately retained here, so preservation/publication ownership is never
  * weakened or bypassed. Only the existing receiver RHS is replaced; assignment
  * kind, target, scope, clock/reset and priority remain untouched.
  */
private[examples] final class AssignmentLowBitTruncationReceiverPhase(
    sourceIntent: NativeConditionSourceIntent
) extends Phase {
  private var completed = false
  private var rewritten = 0

  def rewrittenReceivers: Int = rewritten
  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    require(!completed, "WIRE-TRUNC-01 receiver forwarding executed twice")
    pc.components().foreach { component =>
      val statements = statementsOf(component)
      statements.foreach {
        case driver: DataAssignmentStatement =>
          candidate(component, driver, statements).foreach { plan =>
            plan.foreach { case (receiver, replacement) =>
              if (!(receiver.source eq driver.finalTarget))
                throw new IllegalStateException(
                  "WIRE-TRUNC-01 receiver changed after exact forwarding proof")
              if (!ExternalParameterizedNativeResize.beginLowBitTruncationReceiverForwarding(
                  component, driver, receiver))
                throw new IllegalStateException(
                  "WIRE-TRUNC-01 receiver has no exact native-resize owner")
              receiver.source = replacement
              ExternalParameterizedNativeResize.completeLowBitTruncationReceiverForwarding(
                component, receiver, replacement)
              rewritten += 1
            }
          }
        case _ =>
      }
    }
    completed = true
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

  private def sameBoundary(left: BaseType, right: BaseType): Boolean = {
    val component = left.component
    component != null && (right.component eq component) &&
      left.getTypeObject == right.getTypeObject && left.getBitsWidth == right.getBitsWidth &&
      WireTruncationNativeAccess.equivalentSymbolicWidth(component, left, right)
  }

  private def unannotated(value: Expression): Boolean = value match {
    case tagged: SpinalTagReady => tagged.isEmptyOfTag
    case _ => true
  }

  private def references(expression: Expression, target: BaseType): Boolean = {
    if (expression == null) false
    else if (expression eq target) true
    else {
      var found = false
      expression.walkDrivingExpressions {
        case value: BaseType if value eq target => found = true
        case _ =>
      }
      found
    }
  }

  private def nodeCount(expression: Expression): Int = {
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

  private def independentBlockingInputs(expression: Expression, component: Component): Boolean = {
    val pending = ArrayBuffer(expression)
    var nodes = 0
    var leaves = 0
    while (pending.nonEmpty) {
      val next = pending.remove(pending.size - 1)
      nodes += 1
      if (next == null || nodes > 256) return false
      next match {
        case base: BaseType =>
          leaves += 1
          if ((base.component ne component) || (!base.isInput && !base.isReg) ||
              base.isAnalog || base.isInOut) return false
        case _ => next.foreachDrivingExpression(child => pending += child)
      }
    }
    leaves > 0
  }

  private def receiverAllowed(
      component: Component,
      symbolicCarrier: BaseType,
      receiver: DataAssignmentStatement,
      replacement: Expression
  ): Boolean = {
    val target = receiver.finalTarget
    if (!(receiver.source eq symbolicCarrier) || !(receiver.target eq target) ||
        (target eq symbolicCarrier) || (target.component ne component) ||
        target.isAnalog || target.isInputOrInOut || receiver.parentScope == null ||
        !sameBoundary(target, symbolicCarrier)) return false

    if (target.isReg)
      target.clockDomain != null && independentBlockingInputs(replacement, component)
    else if (target.isComb && (receiver.parentScope eq receiver.rootScopeStatement) &&
        target.hasOnlyOneStatement) true
    else if (target.isComb)
      independentBlockingInputs(replacement, component)
    else false
  }

  private def eligibleFixedCarrier(
      component: Component,
      symbolicCarrier: BaseType,
      value: BaseType
  ): Boolean =
    value != null && (value.component eq component) && value.isComb &&
      value.isDirectionLess && !value.isAnalog && !value.isInOut &&
      unsigned(value) && value.getTypeObject == symbolicCarrier.getTypeObject &&
      value.parentScope != null && (value.parentScope eq value.rootScopeStatement) &&
      value.hasOnlyOneStatement && WireTruncationNativeAccess.protectedCarrier(value) &&
      NativeWireNameProvenance.origin(value).exists(origin =>
        origin == NameOrigin.Generated || origin == NameOrigin.Unnamed) &&
      sourceIntent.permits(value)

  private def candidate(
      component: Component,
      driver: DataAssignmentStatement,
      statements: Vector[Statement]
  ): Option[Vector[(DataAssignmentStatement, Expression)]] = {
    val symbolicCarrier = driver.finalTarget
    if (!(driver.target eq symbolicCarrier) || (symbolicCarrier.component ne component) ||
        !symbolicCarrier.isComb || !symbolicCarrier.isDirectionLess ||
        symbolicCarrier.isAnalog || symbolicCarrier.isInOut ||
        driver.parentScope == null ||
        !(driver.parentScope eq symbolicCarrier.rootScopeStatement) ||
        !symbolicCarrier.hasOnlyOneStatement || !unsigned(symbolicCarrier) ||
        !WireTruncationNativeAccess.protectedCarrier(symbolicCarrier) ||
        !NativeWireNameProvenance.origin(symbolicCarrier).exists(value =>
          value == NameOrigin.Generated || value == NameOrigin.Unnamed) ||
        !sourceIntent.permits(symbolicCarrier)) return None

    val receiverWidth = WireTruncationNativeAccess
      .symbolicResizeTargetWidth(component, symbolicCarrier)
      .getOrElse(return None)
    val fixedCarrier = driver.source match {
      case value: BaseType if eligibleFixedCarrier(component, symbolicCarrier, value) => value
      // A process-scoped use can make the symbolic resize carrier vital before
      // WIRE-TRUNC runs, so its original Resize remains in the owner assignment.
      // Recover only the exact captured fixed source identity; the native resize
      // registry independently revalidates this owner during receiver handoff.
      case _ => WireTruncationNativeAccess
        .lowBitTruncationSource(component, symbolicCarrier)
        .filter(eligibleFixedCarrier(component, symbolicCarrier, _))
        .getOrElse(return None)
    }
    val fixedWidth = width(fixedCarrier).filter(value => value.parameters.isEmpty &&
      value.minimum == value.maximum && value.default == value.minimum)
      .getOrElse(return None)
    if (receiverWidth.maximum > fixedWidth.minimum ||
        receiverWidth.minimum >= fixedWidth.minimum) return None

    val fixedDriver = fixedCarrier.head match {
      case value: DataAssignmentStatement if (value.target eq fixedCarrier) &&
          (value.finalTarget eq fixedCarrier) &&
          (value.parentScope eq fixedCarrier.rootScopeStatement) &&
          value.source != null && unsigned(value.source) &&
          value.source.getTypeObject == fixedCarrier.getTypeObject &&
          unannotated(value.source) && NativeWireExpressionCodec.fixedWidthTree(value.source) => value
      case _ => return None
    }
    val sourceExpression = fixedDriver.source
    val sourceWidth = width(sourceExpression).filter(value => value.parameters.isEmpty &&
      value.minimum == value.maximum && value.default == fixedWidth.default)
      .getOrElse(return None)
    if (sourceWidth.minimum != fixedWidth.minimum ||
        references(sourceExpression, symbolicCarrier) || references(sourceExpression, fixedCarrier) ||
        NativePureExpressionCopy(sourceExpression).isEmpty) return None

    // Reconstruct the exact low-projection certificate from live identities.
    // This is the same canonical proof used by the preceding WIRE phase; no
    // emitted spelling or concrete parameter witness is used as authority.
    val scope = ScopeId.unsafe("scope.wire-trunc.receiver-forward")
    val codec = new NativeWireExpressionCodec(scope, "wire-trunc-receiver")
    val beforeInput = codec.capture(fixedCarrier).getOrElse(return None)
    codec.capture(fixedCarrier).getOrElse(return None)
    val fixedId = codec.capturedSources.find(_._1 eq fixedCarrier).map(_._2).getOrElse(return None)
    val definitionValue = codec.capture(sourceExpression).getOrElse(return None)
    val copied = NativePureExpressionCopy(sourceExpression).getOrElse(return None)
    val after = codec.capture(copied).getOrElse(return None)
    val receiverParameter = IntExpr.ParameterRef(
      ParameterId.unsafe("parameter.wire-trunc.receiver-forward-width"))
    val definition = AssignmentLowBitTruncationProof.Definition(
      fixedId, fixedCarrier.getBitsWidth, definitionValue,
      NativeWireNameProvenance.origin(fixedCarrier).get)
    val before = RtlExpr.Resize(beforeInput, receiverParameter, Signedness.Unsigned)
    if (AssignmentLowBitTruncationProof.prove(
        before, after, receiverParameter, fixedCarrier.getBitsWidth,
        receiverWidth.minimum, receiverWidth.maximum, Vector(definition)).isEmpty) return None

    val receiverStatements = statements.collect {
      case value: DataAssignmentStatement if (value ne driver) &&
          (value.source eq symbolicCarrier) => value
    }
    if (receiverStatements.isEmpty || receiverStatements.size > 32) return None
    val size = nodeCount(sourceExpression)
    if (size > 256 || size.toLong * receiverStatements.size > 256) return None

    val result = receiverStatements.flatMap { receiver =>
      val replacement = NativePureExpressionCopy(sourceExpression).getOrElse(return None)
      if (receiverAllowed(component, symbolicCarrier, receiver, replacement))
        Some(receiver -> replacement)
      else None
    }
    if (result.isEmpty) None else Some(result)
  }

  private def statementsOf(component: Component): Vector[Statement] = {
    val result = Vector.newBuilder[Statement]
    component.dslBody.walkStatements(result += _)
    result.result()
  }
}
