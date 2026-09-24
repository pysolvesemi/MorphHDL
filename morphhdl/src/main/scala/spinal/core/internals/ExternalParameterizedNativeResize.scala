package spinal.core.internals

import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** A native resize with a symbolic source or target needs explicit symbolic
  * truncation and extension across overrides. Capture protects the existing
  * native result net; publication sizes both its payload and extension exactly.
  */
object ExternalParameterizedNativeResize {
  private object StorageKey
  private final case class Record(
      assignment: DataAssignmentStatement,
      target: BitVector,
      resize: Resize,
      source: BitVector,
      sourceWidth: ElaborationIntegerExpression,
      targetWidth: ElaborationIntegerExpression,
      witnessSourceWidth: Int,
      witnessTargetWidth: Int,
      assignmentScope: NativePublicationScope,
      targetScope: NativePublicationScope,
      sourceScope: NativePublicationScope
  )

  private final case class ReceiverRecord(
      owner: Record,
      assignment: DataAssignmentStatement,
      target: BitVector,
      targetWidth: ElaborationIntegerExpression,
      assignmentScope: NativePublicationScope,
      targetScope: NativePublicationScope
  )

  private def fail(detail: String): Nothing = throw new ParameterizedVerilogException(
    "SPINAL-PARAMETERIZED-VERILOG-NATIVE-RESIZE-LINEAGE-MISMATCH", detail)

  private final class Storage(val records: Vector[Record]) {
    val byResize = new java.util.IdentityHashMap[Resize, Record]()
    val byTarget = new java.util.IdentityHashMap[BaseType, Record]()
    val byAssignment = new java.util.IdentityHashMap[DataAssignmentStatement, Record]()
    // WIRE-TRUNC-01 may consume one captured resize only after its complete
    // assignment proof succeeds. A null value is a deliberately fail-closed
    // pending state between begin and completion; a non-null expression binds
    // the exact replacement identity that publication must subsequently see.
    val wireTruncationReplacement = new java.util.IdentityHashMap[Record, Expression]()
    // Final receiver forwarding is a second, strictly subordinate handoff.
    // It never creates resize authority of its own: every record remains bound
    // to one captured low-projection owner and one exact receiver assignment.
    val wireTruncationReceivers =
      new java.util.IdentityHashMap[DataAssignmentStatement, ReceiverRecord]()
    val wireTruncationReceiverReplacement =
      new java.util.IdentityHashMap[ReceiverRecord, Expression]()
    records.foreach { record =>
      if (byResize.put(record.resize, record) != null ||
          byTarget.put(record.target, record) != null ||
          byAssignment.put(record.assignment, record) != null)
        fail("native resize capture has duplicate expression, declaration or assignment identities")
    }
  }

  private def storage(component: Component): Option[Storage] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])

  private def records(component: Component): Vector[Record] =
    storage(component).map(_.records).getOrElse(Vector.empty)

  private def receiverRecords(value: Storage): Vector[ReceiverRecord] = {
    val result = Vector.newBuilder[ReceiverRecord]
    val iterator = value.wireTruncationReceivers.values().iterator()
    while (iterator.hasNext) result += iterator.next()
    result.result()
  }

  private def consumed(value: Storage, record: Record): Boolean =
    value.wireTruncationReplacement.containsKey(record)

  private def lowBitProjection(record: Record): Boolean =
    record.targetWidth.parameters.nonEmpty && record.sourceWidth.parameters.isEmpty &&
      record.sourceWidth.minimum == record.sourceWidth.maximum &&
      record.sourceWidth.default == record.sourceWidth.minimum &&
      record.targetWidth.maximum <= record.sourceWidth.minimum &&
      record.targetWidth.minimum < record.sourceWidth.minimum

  /** A forwarded receiver may be subordinate to either the exact still-fresh
    * native resize owner retained by another use, or to that same owner after
    * WIRE-TRUNC has completed its first consumption handoff. No other captured
    * resize can authorize receiver forwarding.
    */
  private def validReceiverOwnerFresh(
      component: Component,
      value: Storage,
      record: Record
  ): Boolean = lowBitProjection(record) && {
    if (consumed(value, record))
      value.wireTruncationReplacement.get(record) != null &&
        validConsumedFresh(component, value, record)
    else validFresh(component, record)
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

  private val publicationValidation = new ThreadLocal[
    java.util.IdentityHashMap[Record, java.lang.Boolean]]()

  /** Limit validation reuse to one pure final publication call. Recheck every
    * captured record before returning its text so mutation cannot cross the
    * publication boundary with a cached proof. A WIRE-TRUNC-01 consumption is
    * not a validation bypass: it has its own exact post-rewrite identity and
    * width checks and remains revalidated on both sides of publication.
    */
  private[internals] def withPublicationValidation[A](component: Component)(body: => A): A = {
    val capturedStorage = storage(component)
    val captured = capturedStorage.map(_.records).getOrElse(Vector.empty)
    def revalidate(): Unit = {
      if (storage(component).orNull ne capturedStorage.orNull)
        fail("native resize capture ownership changed during publication")
      captured.foreach { record =>
        if (!validForPublication(component, record))
          fail("retained native resize assignment changed during publication")
      }
      capturedStorage.foreach { value =>
        receiverRecords(value).foreach { record =>
          if (!validReceiverFresh(component, value, record))
            fail("WIRE-TRUNC-01 forwarded receiver changed during publication")
        }
      }
    }
    revalidate()
    val previous = publicationValidation.get()
    val current = new java.util.IdentityHashMap[Record, java.lang.Boolean]()
    captured.foreach(record => current.put(record, java.lang.Boolean.TRUE))
    publicationValidation.set(current)
    try {
      val result = body
      revalidate()
      result
    } finally {
      if (previous == null) publicationValidation.remove()
      else publicationValidation.set(previous)
    }
  }

  def install(phases: ArrayBuffer[Phase]): Unit = {
    val boundary = phases.indexWhere(_.isInstanceOf[PhaseRemoveIntermediateUnnameds])
    require(boundary >= 0, "native resize capture requires the unnamed-intermediate boundary")
    phases.insert(boundary, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = pc.walkComponents { component =>
        val captured = ArrayBuffer.empty[Record]
        // Vec.asBits owns the exact witness wrapper around its finite-capacity
        // carrier. Its publisher revalidates both identities and restores the
        // logical packed width. Treating that wrapper as an ordinary scalar
        // resize would give two publishers incompatible meanings for its
        // source width. Reserve only the original recorded wrapper assignments;
        // unrelated resizes, including same-width carriers, remain scalar edges.
        val packedReadWrappers = new java.util.IdentityHashMap[
          DataAssignmentStatement, java.lang.Boolean]()
        ParameterizedVec.retainedVectorsOf(component).foreach { vector =>
          ParameterizedVec.operationsOf(vector).foreach {
            case read: ParameterizedVecPackedRead if read.result ne read.carrier =>
              read.resultAssignments.foreach { assignment =>
                if ((assignment.target eq read.result) && (assignment.finalTarget eq read.result))
                  packedReadWrappers.put(assignment, java.lang.Boolean.TRUE)
              }
            case _ =>
          }
        }
        // A direct typed UInt resize feeding an explicitly fixed declaration
        // belongs to the existing normalized-consumer path. Protecting its
        // intermediate here would prevent that path from reconstructing the
        // logical resize while preserving the consumer's fixed width. This is
        // only a reservation: AutoResize still proves exact one-use lineage.
        val fixedUIntConsumers = new java.util.IdentityHashMap[UInt, java.lang.Boolean]()
        component.dslBody.walkStatements {
          case outer: DataAssignmentStatement =>
            (outer.target, outer.source) match {
              case (consumer: UInt, carrier: UInt)
                  if (outer.finalTarget eq consumer) && consumer.isFixedWidth &&
                    (consumer.component eq component) && (carrier.component eq component) &&
                    ParameterizedWidth.expressionOf(consumer).forall(_.parameters.isEmpty) =>
                fixedUIntConsumers.put(carrier, java.lang.Boolean.TRUE)
              case _ =>
            }
          case _ =>
        }
        component.dslBody.walkStatements {
          case assignment: DataAssignmentStatement =>
            (assignment.target, assignment.source) match {
              case (target: BitVector, resize: Resize)
                  if (assignment.finalTarget eq target) && target.isComb &&
                    (target.component eq component) &&
                    !packedReadWrappers.containsKey(assignment) =>
                resize.input match {
                  case source: BitVector if source.component eq component =>
                    val typedTargetWidth = ParameterizedWidth.resizeExpressionOf(resize)
                    val fixedConsumerReservation = target match {
                      case uint: UInt => fixedUIntConsumers.containsKey(uint) &&
                        typedTargetWidth.exists(_.parameters.nonEmpty)
                      case _ => false
                    }
                    val targetWidth = typedTargetWidth
                      .getOrElse(ElabInt.literal(resize.size).expression)
                    NativeWidthProvenance.widthOf(source).filter(sourceWidth =>
                      !fixedConsumerReservation &&
                        (sourceWidth.parameters.nonEmpty || targetWidth.parameters.nonEmpty)).foreach { sourceWidth =>
                      NativePublicationWidth.validate(sourceWidth, component, source,
                        "native resize source capture")
                      NativePublicationWidth.validate(targetWidth, component, target,
                        "native resize target capture")
                      // An unsized native zero has no source bits. Leave only
                      // its exact poison-free literal edge to normalization and
                      // the retained-zero publisher, which revalidates the live
                      // positive-width result after native constant folding.
                      val literalZeroReservation = sourceWidth.parameters.isEmpty &&
                        sourceWidth.minimum == 0 && sourceWidth.maximum == 0 &&
                        source.getBitsWidth == 0 && source.isComb &&
                        source.hasOnlyOneStatement && (source.head match {
                          case edge: DataAssignmentStatement
                              if (edge.target eq source) && (edge.finalTarget eq source) =>
                            edge.source match {
                              case literal: BitVectorLiteral => literal.getWidth == 0 &&
                                !literal.hasPoison() && literal.getValue() == 0
                              case _ => false
                            }
                          case _ => false
                        })
                      if ((!literalZeroReservation && sourceWidth.minimum < 1) || targetWidth.minimum < 1 ||
                          sourceWidth.default != source.getBitsWidth || targetWidth.default != target.getBitsWidth)
                        fail(s"native resize target '${target.getName()}' width ${target.getBitsWidth} " +
                          s"(${targetWidth.verilog}; default=${targetWidth.default}, min=${targetWidth.minimum}) " +
                          s"and source '${source.getName()}' width ${source.getBitsWidth} " +
                          s"(${sourceWidth.verilog}; default=${sourceWidth.default}, min=${sourceWidth.minimum}) " +
                          "must retain positive, witness-consistent widths")
                      // Native identity elimination and a fixed narrowing slice
                      // already have the same meaning across the complete owner
                      // domain. Keep those original native graphs and their
                      // emission intact; only varying resize boundaries need
                      // protected declarations and symbolic publication.
                      val identity = (source.parentScope eq target.parentScope) &&
                        NativePublicationWidth.equivalentAtOwners(
                          sourceWidth, source, targetWidth, target, component)
                      val fixedNarrowing = targetWidth.parameters.isEmpty &&
                        targetWidth.maximum <= sourceWidth.minimum
                      if (!literalZeroReservation && !identity && !fixedNarrowing) {
                        target.dontSimplifyIt().addTag(noBackendCombMerge)
                        source.dontSimplifyIt().addTag(noBackendCombMerge)
                        // Publication records retain native identity through name allocation.
                        // Preservation is independent of user/reflected/generated naming.
                        captured += Record(assignment, target, resize, source, sourceWidth, targetWidth,
                          source.getBitsWidth, resize.size,
                          NativePublicationScope.capture(component, assignment.parentScope),
                          NativePublicationScope.capture(component, target.parentScope),
                          NativePublicationScope.capture(component, source.parentScope))
                      }
                    }
                  case _ =>
                }
              case _ =>
            }
          case _ =>
        }
        if (captured.nonEmpty) component.userCache.put(StorageKey, new Storage(captured.toVector))
      }
    })
  }

  /** Begin a one-shot WIRE-TRUNC-01 ownership transfer for the exact captured
    * assignment. No other native-resize record is eligible. The original edge
    * must still be fresh and the complete target domain must be a symbolic
    * unsigned low projection of one fixed source width. The pending null token
    * intentionally makes publication fail until completion binds the actual
    * replacement identity.
    */
  def beginLowBitTruncationConsumption(
      component: Component,
      assignment: DataAssignmentStatement
  ): Boolean = storage(component) match {
    case None => false
    case Some(value) =>
      val record = value.byAssignment.get(assignment)
      if (record == null) false
      else {
        if (consumed(value, record)) fail("native resize assignment was consumed by WIRE-TRUNC-01 twice")
        if (!validFresh(component, record))
          fail("native resize assignment changed before WIRE-TRUNC-01 consumption")
        if (!lowBitProjection(record))
          fail("native resize assignment is not a symbolic low projection of one fixed source width")
        value.wireTruncationReplacement.put(record, null)
        true
      }
  }

  /** Complete the exact WIRE-TRUNC-01 transfer after the proven replacement has
    * become the assignment RHS. The replacement's native fixed-width authority
    * is bound by identity; later mutation, target movement or width drift fails
    * publication validation rather than falling back to a textual heuristic.
    */
  def completeLowBitTruncationConsumption(
      component: Component,
      assignment: DataAssignmentStatement,
      replacement: Expression
  ): Unit = {
    val value = storage(component).getOrElse(fail("WIRE-TRUNC-01 completion lost native resize storage"))
    val record = value.byAssignment.get(assignment)
    if (record == null || !consumed(value, record) || value.wireTruncationReplacement.get(record) != null)
      fail("WIRE-TRUNC-01 completion has no unique pending native resize record")
    if (replacement == null || !(assignment.source eq replacement) ||
        replacement.getTypeObject != record.target.getTypeObject)
      fail("WIRE-TRUNC-01 completion does not bind the exact replacement RHS")
    val replacementWidth = NativeWidthProvenance.widthOf(replacement)
      .getOrElse(fail("WIRE-TRUNC-01 replacement lost native width authority"))
    if (replacementWidth.parameters.nonEmpty ||
        replacementWidth.minimum != record.sourceWidth.minimum ||
        replacementWidth.maximum != record.sourceWidth.maximum ||
        replacementWidth.default != record.sourceWidth.default)
      fail("WIRE-TRUNC-01 replacement width differs from the captured source evaluation width")
    value.wireTruncationReplacement.put(record, replacement)
    if (!validConsumedFresh(component, value, record))
      fail("WIRE-TRUNC-01 completed native resize lineage is not publication-safe")
  }

  /** Bind one exact downstream whole-object receiver to a captured symbolic
    * low-projection owner. The owner may still be the exact fresh native resize
    * retained by another use or may already have completed the first WIRE
    * consumption handoff. The receiver must still read that captured symbolic
    * target and own the same authoritative symbolic width at its declaration.
    * This pending record grants no publication authority until completion binds
    * the exact fixed-width replacement identity.
    */
  def beginLowBitTruncationReceiverForwarding(
      component: Component,
      ownerAssignment: DataAssignmentStatement,
      receiver: DataAssignmentStatement
  ): Boolean = storage(component) match {
    case None => false
    case Some(value) =>
      val owner = value.byAssignment.get(ownerAssignment)
      if (owner == null) false
      else {
        if (!validReceiverOwnerFresh(component, value, owner))
          fail("WIRE-TRUNC-01 receiver forwarding lost its exact low-projection resize owner")
        if (receiver == null) return false
        if (value.wireTruncationReceivers.containsKey(receiver))
          fail("WIRE-TRUNC-01 receiver assignment was forwarded twice")
        receiver.finalTarget match {
          case target: BitVector
              if (receiver.target eq target) && (receiver.source eq owner.target) &&
                (target ne owner.target) && (target.component eq component) &&
                target.getTypeObject == owner.target.getTypeObject &&
                target.getBitsWidth == owner.witnessTargetWidth &&
                receiver.parentScope != null && target.parentScope != null =>
            val targetWidth = ParameterizedWidth.expressionOf(target)
              .filter(_.parameters.nonEmpty).getOrElse(return false)
            if (!NativePublicationWidth.equivalentAtOwners(
                targetWidth, target, owner.targetWidth, owner.target, component)) return false
            val record = ReceiverRecord(
              owner,
              receiver,
              target,
              targetWidth,
              NativePublicationScope.capture(component, receiver.parentScope),
              NativePublicationScope.capture(component, target.parentScope)
            )
            value.wireTruncationReceivers.put(receiver, record)
            value.wireTruncationReceiverReplacement.put(record, null)
            true
          case _ => false
        }
      }
  }

  /** Complete one receiver-forwarding handoff after the canonical receiver
    * proof has installed its exact RHS. Width, type and identity remain bound
    * to the original native-resize owner; publication will revalidate this
    * record before and after generic width analysis.
    */
  def completeLowBitTruncationReceiverForwarding(
      component: Component,
      receiver: DataAssignmentStatement,
      replacement: Expression
  ): Unit = {
    val value = storage(component)
      .getOrElse(fail("WIRE-TRUNC-01 receiver completion lost native resize storage"))
    val record = value.wireTruncationReceivers.get(receiver)
    if (record == null || !value.wireTruncationReceiverReplacement.containsKey(record) ||
        value.wireTruncationReceiverReplacement.get(record) != null)
      fail("WIRE-TRUNC-01 receiver completion has no unique pending record")
    if (replacement == null || !(receiver.source eq replacement) ||
        replacement.getTypeObject != record.target.getTypeObject)
      fail("WIRE-TRUNC-01 receiver completion does not bind the exact replacement RHS")
    val replacementWidth = NativeWidthProvenance.widthOf(replacement)
      .getOrElse(fail("WIRE-TRUNC-01 forwarded receiver lost native width authority"))
    if (replacementWidth.parameters.nonEmpty ||
        replacementWidth.minimum != record.owner.sourceWidth.minimum ||
        replacementWidth.maximum != record.owner.sourceWidth.maximum ||
        replacementWidth.default != record.owner.sourceWidth.default ||
        references(replacement, record.owner.target) || references(replacement, record.owner.source))
      fail("WIRE-TRUNC-01 forwarded receiver does not retain the captured fixed source evaluation")
    value.wireTruncationReceiverReplacement.put(record, replacement)
    if (!validReceiverFresh(component, value, record))
      fail("WIRE-TRUNC-01 completed receiver forwarding is not publication-safe")
  }

  private def valid(component: Component, record: Record): Boolean = {
    val current = publicationValidation.get()
    (current != null && current.containsKey(record)) || validForPublication(component, record)
  }

  private def validForPublication(component: Component, record: Record): Boolean =
    storage(component).exists { value =>
      if (consumed(value, record)) validConsumedFresh(component, value, record)
      else validFresh(component, record)
    }

  private def validConsumedFresh(component: Component, value: Storage, record: Record): Boolean = {
    val replacement = value.wireTruncationReplacement.get(record)
    if (replacement == null) return false
    var count = 0
    var drivers = 0
    var targets = 0
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement =>
        if (assignment eq record.assignment) count += 1
        if (assignment.finalTarget eq record.target) drivers += 1
      case base: BaseType =>
        if (base eq record.target) targets += 1
      case _ =>
    }
    val replacementWidth = NativeWidthProvenance.widthOf(replacement)
    count == 1 && drivers == 1 && targets == 1 &&
      record.assignmentScope.matches(record.assignment.parentScope) &&
      record.targetScope.matches(record.target.parentScope) &&
      (record.assignment.target eq record.target) && (record.assignment.finalTarget eq record.target) &&
      (record.assignment.source eq replacement) && (replacement ne record.resize) &&
      replacement.getTypeObject == record.target.getTypeObject &&
      record.target.getBitsWidth == record.witnessTargetWidth &&
      (record.target.component eq component) && record.target.isComb &&
      record.target.dontSimplify && record.target.hasTag(noBackendCombMerge) &&
      replacementWidth.exists(width => width.parameters.isEmpty &&
        width.minimum == record.sourceWidth.minimum && width.maximum == record.sourceWidth.maximum &&
        width.default == record.sourceWidth.default) &&
      ParameterizedWidth.expressionOf(record.target)
        .exists(NativePublicationWidth.equivalentAtOwner(_, record.targetWidth, component, record.target))
  }

  private def validReceiverFresh(
      component: Component,
      value: Storage,
      record: ReceiverRecord
  ): Boolean = {
    val replacement = value.wireTruncationReceiverReplacement.get(record)
    if (replacement == null || !validReceiverOwnerFresh(component, value, record.owner)) return false
    var assignments = 0
    var targets = 0
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement if assignment eq record.assignment => assignments += 1
      case base: BaseType if base eq record.target => targets += 1
      case _ =>
    }
    val replacementWidth = NativeWidthProvenance.widthOf(replacement)
    assignments == 1 && targets == 1 &&
      record.assignmentScope.matches(record.assignment.parentScope) &&
      record.targetScope.matches(record.target.parentScope) &&
      (record.assignment.target eq record.target) &&
      (record.assignment.finalTarget eq record.target) &&
      (record.assignment.source eq replacement) &&
      (record.target.component eq component) &&
      record.target.getTypeObject == record.owner.target.getTypeObject &&
      record.target.getBitsWidth == record.owner.witnessTargetWidth &&
      replacement.getTypeObject == record.target.getTypeObject &&
      replacementWidth.exists(width => width.parameters.isEmpty &&
        width.minimum == record.owner.sourceWidth.minimum &&
        width.maximum == record.owner.sourceWidth.maximum &&
        width.default == record.owner.sourceWidth.default) &&
      !references(replacement, record.owner.target) &&
      !references(replacement, record.owner.source) &&
      ParameterizedWidth.expressionOf(record.target).exists(width =>
        width.parameters.nonEmpty &&
          NativePublicationWidth.equivalentAtOwners(
            width, record.target, record.owner.targetWidth, record.owner.target, component))
  }

  private def validFresh(component: Component, record: Record): Boolean = {
    var count = 0
    var drivers = 0
    var targets = 0
    var sources = 0
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement =>
        if (assignment eq record.assignment) count += 1
        if (assignment.finalTarget eq record.target) drivers += 1
      case value: BaseType =>
        if (value eq record.target) targets += 1
        if (value eq record.source) sources += 1
      case _ =>
    }
    val exactResize = record.assignment.source eq record.resize
    val normalizedEqualResize = record.witnessSourceWidth == record.witnessTargetWidth &&
      (record.assignment.source eq record.source)
    count == 1 && drivers == 1 && targets == 1 && sources == 1 &&
      record.assignmentScope.matches(record.assignment.parentScope) &&
      record.targetScope.matches(record.target.parentScope) &&
      record.sourceScope.matches(record.source.parentScope) &&
      (record.assignment.target eq record.target) && (exactResize || normalizedEqualResize) &&
      (record.resize.input eq record.source) &&
      record.resize.size == record.witnessTargetWidth &&
      record.target.getBitsWidth == record.witnessTargetWidth &&
      record.source.getBitsWidth == record.witnessSourceWidth &&
      record.resize.getTypeObject == record.source.getTypeObject &&
      record.target.getTypeObject == record.source.getTypeObject &&
      (record.target.component eq component) && (record.source.component eq component) &&
      record.target.isComb && record.target.dontSimplify && record.source.dontSimplify &&
      record.target.hasTag(noBackendCombMerge) && record.source.hasTag(noBackendCombMerge) &&
      NativeWidthProvenance.widthOf(record.source)
        .exists(NativePublicationWidth.equivalentAtOwner(_, record.sourceWidth, component, record.source)) &&
      (if (record.targetWidth.parameters.isEmpty)
         ParameterizedWidth.expressionOf(record.target).isEmpty
       else ParameterizedWidth.expressionOf(record.target)
         .exists(NativePublicationWidth.equivalentAtOwner(_, record.targetWidth, component, record.target)))
  }

  /** A consumed WIRE-TRUNC-01 assignment is not a native resize proof after the
    * handoff. During the one scoped publication call only, however, the generic
    * assignment-width validator may recognize that exact completed handoff as
    * already owned and revalidated by this registry. Pending, stale, unrelated
    * or post-publication assignments remain unauthorized.
    */
  private def validConsumedDuringPublication(
      component: Component,
      value: Storage,
      record: Record
  ): Boolean = {
    val current = publicationValidation.get()
    current != null && current.containsKey(record) &&
      consumed(value, record) && validConsumedFresh(component, value, record)
  }

  private def validReceiverDuringPublication(
      component: Component,
      value: Storage,
      record: ReceiverRecord
  ): Boolean = {
    val current = publicationValidation.get()
    current != null && current.containsKey(record.owner) &&
      validReceiverFresh(component, value, record)
  }

  private[internals] def proves(component: Component, resize: Resize): Boolean =
    storage(component).flatMap(value => Option(value.byResize.get(resize))).exists(validFresh(component, _))

  /** The original target-sized assignment remains a resize boundary when
    * native simplification removes an equal-witness Resize expression. A
    * completed WIRE-TRUNC-01 handoff is recognized only while its enclosing
    * publication validation scope is active; outside that scope this API keeps
    * its historical fresh-resize meaning. Final forwarded receivers have the
    * same scoped-only rule and are bound to their exact captured owner.
    */
  private[internals] def provesAssignment(
      component: Component,
      assignment: DataAssignmentStatement
  ): Boolean = storage(component).exists { value =>
    val native = Option(value.byAssignment.get(assignment)).exists { record =>
      (!consumed(value, record) && validFresh(component, record)) ||
        validConsumedDuringPublication(component, value, record)
    }
    val receiver = Option(value.wireTruncationReceivers.get(assignment))
      .exists(validReceiverDuringPublication(component, value, _))
    native || receiver
  }

  private[internals] def targetWidthOf(component: Component, target: BaseType)
      : Option[ElaborationIntegerExpression] =
    storage(component).flatMap(value => Option(value.byTarget.get(target)))
      .filter(valid(component, _)).map(_.targetWidth)

  /** Expose only the exact fixed source identity of a captured symbolic low
    * projection whose owner is still valid for receiver forwarding. This is a
    * read-only bridge for the WIRE receiver proof, not publication authority.
    */
  private[internals] def lowBitTruncationSourceOf(
      component: Component,
      target: BaseType
  ): Option[BaseType] = storage(component).flatMap { value =>
    Option(value.byTarget.get(target))
      .filter(validReceiverOwnerFresh(component, value, _))
      .map(_.source: BaseType)
  }

  /** The generic width analysis must publish the same geometry that this
    * captured native resize proves. A fixed source witness cannot authorize
    * rewriting a declaration which that analysis infers as symbolic instead.
    */
  private[internals] def validatePublishedWidths(component: Component)(
      mismatch: (BitVector, ElaborationIntegerExpression) => Option[String]
  ): Unit = records(component).foreach { record =>
    if (!valid(component, record))
      fail("retained native resize assignment changed before width validation")
    val value = storage(component).get
    if (!consumed(value, record)) {
      mismatch(record.source, record.sourceWidth).foreach { detail =>
        fail(s"native resize source publication differs from its captured exact width: $detail")
      }
    }
    mismatch(record.target, record.targetWidth).foreach { detail =>
      fail(s"native resize target publication differs from its captured exact width: $detail")
    }
  }

  private[internals] def rewrite(component: Component, verilog: String): String = {
    var lines = verilog.split("\n", -1).toVector
    val claimed = scala.collection.mutable.HashSet.empty[String]
    val value = storage(component)
    records(component).foreach { record =>
      if (!valid(component, record)) fail("retained native resize assignment changed after capture")
      // WIRE-TRUNC-01 has already replaced this exact captured resize with a
      // proof-bound fixed-width expression. Its symbolic target declaration is
      // still validated above; reapplying the old resize textual rewrite would
      // recreate the removed carrier and violate the one-owner handoff.
      if (!value.exists(consumed(_, record))) {
        val targetName = Option(record.target.getName()).filter(_.nonEmpty)
          .getOrElse(fail("retained native resize target has no emitted name"))
        val sourceName = Option(record.source.getName()).filter(_.nonEmpty)
          .getOrElse(fail("retained native resize source has no emitted name"))
        if (!claimed.add(targetName)) fail(s"multiple resize targets share emitted name '$targetName'")
        val targetWidth = record.witnessTargetWidth
        val sourceWidth = record.witnessSourceWidth
        val signed = record.source.isInstanceOf[SInt]
        val expected = if (targetWidth < sourceWidth) s"$sourceName[${targetWidth - 1}:0]"
          else if (targetWidth == sourceWidth) sourceName
          else if (signed) s"{{${targetWidth - sourceWidth}{$sourceName[${sourceWidth - 1}]}}, $sourceName}"
          else s"{${targetWidth - sourceWidth}'d0, $sourceName}"
        val assignment = ("^(\\s*assign\\s+" + Pattern.quote(targetName) +
          "\\s*=\\s*)(.*?)(;\\s*)$").r
        var targets = 0
        var matches = 0
        lines = lines.map {
          case assignment(prefix, rhs, suffix) =>
            targets += 1
            if (rhs.trim == expected) {
              matches += 1
              val to = s"(${record.targetWidth.verilog})"
              val from = s"(${record.sourceWidth.verilog})"
              // Keep every part select positive/in range and every replication
              // non-negative, including domains crossing narrowing and widening.
              // The complete concat has exactly the native target width, so no
              // assignment-context extension or truncation is left implicit.
              val resized = if (record.targetWidth.maximum <= record.sourceWidth.minimum)
                s"$sourceName[$to-1:0]"
              else {
                val selected = if (record.targetWidth.minimum >= record.sourceWidth.maximum) from
                  else s"(($to < $from) ? $to : $from)"
                val extra = s"(($to > $from) ? ($to - $from) : 0)"
                val extension = if (signed) s"$sourceName[$from-1]" else "1'b0"
                s"{{$extra{$extension}}, $sourceName[$selected-1:0]}"
              }
              prefix + resized + suffix
            } else prefix + rhs + suffix
          case line => line
        }
        if (targets != 1 || matches != 1)
          fail(s"resize target '$targetName' maps to $targets native assignments and $matches exact witness edges")
      }
    }
    lines.mkString("\n")
  }
}
