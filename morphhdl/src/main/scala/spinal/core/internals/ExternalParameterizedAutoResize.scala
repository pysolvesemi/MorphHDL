package spinal.core.internals

import java.util.IdentityHashMap

import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** Exact, generation-local provenance for native UInt `.resized` assignments
  * and explicit typed UInt resize carriers that may be normalized away.
  *
  * SpinalHDL removes `tagAutoResize` while normalizing inputs, before the
  * MorphHDL publication pass validates symbolic widths. Capture therefore runs
  * after register nextification but before unnamed intermediates are removed.
  * It stores statement identity in the owning Component's user cache without
  * retaining global state or mutating the native expression graph.
  */
object ExternalParameterizedAutoResize {
  private object StorageKey

  private final case class Candidate(
      component: Component,
      outer: DataAssignmentStatement,
      target: UInt,
      resizeSource: UInt,
      sourceDriver: DataAssignmentStatement,
      typedTarget: Option[ElaborationIntegerExpression]
  )

  private final case class Record(
      component: Component,
      outer: DataAssignmentStatement,
      target: UInt,
      resizeSource: UInt,
      sourceDriver: DataAssignmentStatement,
      typedTarget: Option[ElaborationIntegerExpression]
  )

  private final case class SyntheticBooleanRecord(
      component: Component,
      outer: DataAssignmentStatement,
      target: UInt,
      resizeSource: UInt,
      sourceDriver: DataAssignmentStatement,
      uintCast: CastBitsToUInt,
      bitsSource: Bits,
      bitsDriver: DataAssignmentStatement,
      boolCast: CastBoolToBits
  )

  private final class Storage {
    val byStatement = new IdentityHashMap[DataAssignmentStatement, Record]()
    val byResizeSource = new IdentityHashMap[UInt, Record]()
    val syntheticBoolean = ArrayBuffer.empty[SyntheticBooleanRecord]
  }

  private final class CapturePhase extends PhaseMisc {
    override def impl(pc: PhaseContext): Unit = {
      pc.walkComponents(captureComponent)
    }
  }

  private def captureComponent(component: Component): Unit = {
    component.userCache.remove(StorageKey)
    val witnessInactiveAssignments =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    ParameterizedStructure
      .capturedWitnessInactiveDataAssignmentsOf(component)
      .foreach(statement => witnessInactiveAssignments.put(statement, java.lang.Boolean.TRUE))
    val candidatesBySource =
      new IdentityHashMap[UInt, ArrayBuffer[Candidate]]()
    val drivingUseCount = new IdentityHashMap[BaseType, java.lang.Integer]()
    val syntheticBySource =
      new IdentityHashMap[UInt, java.lang.Boolean]()
    val syntheticRecords = ArrayBuffer.empty[SyntheticBooleanRecord]

    component.dslBody.walkStatements { statement =>
      statement.walkDrivingExpressions {
        case source: BaseType =>
          val previous = drivingUseCount.get(source)
          drivingUseCount.put(
            source,
            java.lang.Integer.valueOf(
              if (previous == null) 1 else previous.intValue() + 1
            )
          )
        case _ =>
      }
    }

    component.dslBody.walkLeafStatements {
      case outer: DataAssignmentStatement =>
        syntheticBooleanRecord(
          component,
          outer,
          witnessInactiveAssignments,
          drivingUseCount
        ).foreach { record =>
          record.resizeSource.addTag(tagAutoResize)
          syntheticBySource.put(record.resizeSource, java.lang.Boolean.TRUE)
          syntheticRecords += record
        }
        (outer.target, outer.source) match {
          case (target: UInt, resizeSource: UInt)
              if (outer.finalTarget eq target) &&
                (target.component eq component) &&
                (resizeSource.component eq component) &&
                resizeSource.isComb &&
                resizeSource.isDirectionLess &&
                (resizeSource.hasTag(tagAutoResize) ||
                  resizeSource.hasTag(ParameterizedWidth.TypedResizeCaptureTag)) &&
                !syntheticBySource.containsKey(resizeSource) &&
                resizeSource.hasOnlyOneStatement =>
            val sourceDriver = resizeSource.head match {
              case driver: DataAssignmentStatement
                  if (driver.target eq resizeSource) &&
                    (driver.finalTarget eq resizeSource) &&
                    driver.source.isInstanceOf[WidthProvider] &&
                    driver.source.getTypeObject == TypeUInt =>
                Some(driver)
              case _ => None
            }
            sourceDriver.foreach { driver =>
              val typedTarget =
                if (resizeSource.hasTag(ParameterizedWidth.TypedResizeCaptureTag))
                  ParameterizedWidth
                    .expressionOf(resizeSource)
                    .filter(_.parameters.nonEmpty)
                else None
              if (resizeSource.hasTag(tagAutoResize) || typedTarget.nonEmpty) {
                val candidate = Candidate(
                  component,
                  outer,
                  target,
                  resizeSource,
                  driver,
                  typedTarget
                )
                var candidates = candidatesBySource.get(resizeSource)
                if (candidates == null) {
                  candidates = ArrayBuffer.empty[Candidate]
                  candidatesBySource.put(resizeSource, candidates)
                }
                candidates += candidate
              }
            }
          case _ =>
        }
      case _ =>
    }

    val provisional = ArrayBuffer.empty[Record]
    val iterator = candidatesBySource.values().iterator()
    while (iterator.hasNext) {
      val candidates = iterator.next()
      val source = candidates.head.resizeSource
      val useCount = drivingUseCount.get(source)
      if (candidates.size == 1 && useCount != null && useCount.intValue() == 1) {
        val candidate = candidates.head
        val record = Record(
          candidate.component,
          candidate.outer,
          candidate.target,
          candidate.resizeSource,
          candidate.sourceDriver,
          candidate.typedTarget
        )
        provisional += record
      }
    }

    val owners =
      new IdentityHashMap[DataAssignmentStatement, ArrayBuffer[Record]]()
    provisional.foreach { record =>
      Vector(record.outer, record.sourceDriver).foreach { statement =>
        var statementOwners = owners.get(statement)
        if (statementOwners == null) {
          statementOwners = ArrayBuffer.empty[Record]
          owners.put(statement, statementOwners)
        }
        statementOwners += record
      }
    }

    val storage = new Storage
    provisional.foreach { record =>
      if (
        owners.get(record.outer).size == 1 &&
        owners.get(record.sourceDriver).size == 1
      ) {
        storage.byStatement.put(record.outer, record)
        storage.byStatement.put(record.sourceDriver, record)
        storage.byResizeSource.put(record.resizeSource, record)
      }
    }
    storage.syntheticBoolean ++= syntheticRecords
    if (!storage.byStatement.isEmpty || storage.syntheticBoolean.nonEmpty) {
      component.userCache.update(StorageKey, storage)
    }
    component.dslBody.walkDeclarations {
      case value: BitVector =>
        value.removeTag(ParameterizedWidth.TypedResizeCaptureTag)
      case _ =>
    }
  }

  private def syntheticBooleanRecord(
      component: Component,
      outer: DataAssignmentStatement,
      inactive: IdentityHashMap[
        DataAssignmentStatement,
        java.lang.Boolean
      ],
      useCount: IdentityHashMap[BaseType, java.lang.Integer]
  ): Option[SyntheticBooleanRecord] = {
    if (!inactive.containsKey(outer)) return None

    def oneUse(value: BaseType): Boolean = {
      val count = useCount.get(value)
      count != null && count.intValue() == 1
    }

    def transient(value: BaseType): Boolean =
      (value.component eq component) &&
        value.isTypeNode && value.isComb && value.isDirectionLess &&
        value.isUnnamed && value.hasOnlyOneStatement && oneUse(value)

    (outer.target, outer.source) match {
      case (target: UInt, source: UInt)
          if (outer.finalTarget eq target) &&
            (target.component eq component) &&
            (source.component eq component) &&
            !source.hasTag(tagAutoResize) &&
            !source.hasTag(ParameterizedWidth.TypedResizeCaptureTag) &&
            transient(source) &&
            source.getBitsWidth == 1 &&
            ParameterizedWidth.expressionOf(target).exists { expression =>
              expression.parameters.nonEmpty &&
              expression.default == BigInt(target.getBitsWidth) &&
              expression.default > 1
            } =>
        source.head match {
          case sourceDriver: DataAssignmentStatement
              if (sourceDriver.target eq source) &&
                (sourceDriver.finalTarget eq source) =>
            sourceDriver.source match {
              case uintCast: CastBitsToUInt =>
                uintCast.input match {
                  case bits: Bits if transient(bits) =>
                    bits.head match {
                      case bitsDriver: DataAssignmentStatement
                          if (bitsDriver.target eq bits) &&
                            (bitsDriver.finalTarget eq bits) =>
                        bitsDriver.source match {
                          case boolCast: CastBoolToBits
                              if boolCast.input != null &&
                                boolCast.input.getTypeObject == TypeBool =>
                            Some(
                              SyntheticBooleanRecord(
                                component,
                                outer,
                                target,
                                source,
                                sourceDriver,
                                uintCast,
                                bits,
                                bitsDriver,
                                boolCast
                              )
                            )
                          case _ => None
                        }
                      case _ => None
                    }
                  case _ => None
                }
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }

  /** Install capture at the last point where native auto-resize tags exist. */
  def install(phases: ArrayBuffer[Phase]): Unit = {
    if (phases == null)
      throw new IllegalArgumentException("native phase plan must not be null")
    val boundary = phases.indexWhere(_.isInstanceOf[PhaseRemoveIntermediateUnnameds])
    if (boundary < 0) {
      throw new IllegalStateException(
        "native phase plan has no pre-normalization unnamed-intermediate boundary"
      )
    }
    phases.insert(boundary, new CapturePhase)
  }

  /** Return the exact whole UInt target that sizes a captured resize source. */
  private[internals] def targetOfResizeSource(
      component: Component,
      source: BaseType
  ): Option[UInt] = {
    if (component == null || source == null || !source.isInstanceOf[UInt]) None
    else {
      storageOf(component)
        .flatMap(storage => Option(storage.byResizeSource.get(source.asInstanceOf[UInt])))
        .filter(record => validRecord(component, record))
        .filter(record => typedTargetMatches(record, record.target))
        .filter(record => ParameterizedWidth.expressionOf(record.target).exists(_.parameters.nonEmpty))
        .map(_.target)
    }
  }

  /** Return the exact source-driver statement for one captured resize clone. */
  private[internals] def sourceDriverOfResizeSource(
      component: Component,
      source: BaseType
  ): Option[DataAssignmentStatement] = {
    if (component == null || source == null || !source.isInstanceOf[UInt]) None
    else {
      storageOf(component)
        .flatMap(storage => Option(storage.byResizeSource.get(source.asInstanceOf[UInt])))
        .filter(record => validRecord(component, record))
        // A typed explicit resize retains its own target width. Reusing its
        // pre-resize driver width would erase the explicit conversion.
        .filter(_.typedTarget.isEmpty)
        .map(_.sourceDriver)
    }
  }

  /** Exact surviving outer Resize, its captured assignment and whole target. */
  private[internals] def materializedResizeBoundary(
      component: Component,
      resize: Resize
  ): Option[(DataAssignmentStatement, UInt)] = {
    if (
      component == null || resize == null ||
      !resize.isInstanceOf[ResizeUInt] || resize.getTypeObject != TypeUInt
    ) return None

    val matches = ArrayBuffer.empty[Record]
    storageOf(component).foreach { storage =>
      val iterator = storage.byResizeSource.values().iterator()
      while (iterator.hasNext) {
        val record = iterator.next()
        if (
          validRecord(component, record) &&
          typedTargetMatches(record, record.target) &&
          (record.outer.source eq resize) &&
          (resize.input eq record.resizeSource) &&
          resize.size == record.target.getBitsWidth
        ) matches += record
      }
    }
    matches.toVector match {
      case Vector(record) => Some(record.outer -> record.target)
      case _              => None
    }
  }

  /** Prove that a surviving statement is one exact edge of a captured native
    * UInt `.resized` boundary.
    */
  private[internals] def proves(
      component: Component,
      assignment: DataAssignmentStatement,
      target: UInt
  ): Boolean = {
    if (component == null || assignment == null || target == null) return false
    storageOf(component)
      .flatMap(storage => Option(storage.byStatement.get(assignment)))
      .filter(record => validRecord(component, record))
      .exists { record =>
        val exactEdge =
          ((assignment eq record.outer) && (target eq record.target)) ||
            ((assignment eq record.sourceDriver) && (target eq record.resizeSource))
        val currentSource = assignment.source
        exactEdge &&
        typedTargetMatches(record, target) &&
        (assignment.target eq target) &&
        (assignment.finalTarget eq target) &&
        (target.component eq component) &&
        currentSource.isInstanceOf[WidthProvider] &&
        currentSource.getTypeObject == TypeUInt &&
        currentSource.asInstanceOf[WidthProvider].getWidth == target.getBitsWidth &&
        !currentSource.isInstanceOf[Resize]
      }
  }

  /** An explicit typed resize may authorize only a consumer with the exact
    * retained target expression. Native `.resized` records carry no typed
    * target and keep their ordinary whole-target behavior.
    */
  private def typedTargetMatches(record: Record, target: UInt): Boolean =
    record.typedTarget.forall { expected =>
      ParameterizedWidth.expressionOf(target).exists { actual =>
        ExternalFormalParameterRegistry.equivalentExpression(expected, actual)
      }
    }

  private def validSyntheticBooleanRecord(
      component: Component,
      record: SyntheticBooleanRecord
  ): Boolean =
    (record.component eq component) &&
      (record.outer ne null) &&
      (record.target ne null) &&
      (record.resizeSource ne null) &&
      (record.sourceDriver ne null) &&
      (record.uintCast ne null) &&
      (record.bitsSource ne null) &&
      (record.bitsDriver ne null) &&
      (record.boolCast ne null) &&
      (record.outer ne record.sourceDriver) &&
      (record.target ne record.resizeSource) &&
      (record.outer.target eq record.target) &&
      (record.outer.finalTarget eq record.target) &&
      (record.target.component eq component) &&
      (record.sourceDriver.target eq record.resizeSource) &&
      (record.sourceDriver.finalTarget eq record.resizeSource) &&
      (record.sourceDriver.source eq record.uintCast) &&
      (record.bitsDriver.target eq record.bitsSource) &&
      (record.bitsDriver.finalTarget eq record.bitsSource) &&
      (record.bitsDriver.source eq record.boolCast) &&
      (record.uintCast.input eq record.boolCast) &&
      record.boolCast.input != null &&
      record.boolCast.input.getTypeObject == TypeBool &&
      record.uintCast.getTypeObject == TypeUInt &&
      record.uintCast.getWidth == 1 &&
      record.target.getBitsWidth > 1 &&
      ParameterizedWidth.expressionOf(record.target).exists { expression =>
        expression.parameters.nonEmpty &&
        expression.default == BigInt(record.target.getBitsWidth)
      }

  /** Recover the symbolic target of the exact native Resize materialized after
    * capture for a witness-inactive Bool-to-UInt assignment. The Resize does
    * not exist during capture, so its identity is bound lazily through the
    * surviving outer assignment and cast edges. Zero or ambiguous matches fail
    * closed.
    */
  private[internals] def syntheticBooleanResizeTarget(
      component: Component,
      resize: Resize
  ): Option[UInt] = {
    if (
      component == null || resize == null ||
      !resize.isInstanceOf[ResizeUInt] ||
      resize.getTypeObject != TypeUInt
    ) return None

    val matches = storageOf(component).toVector
      .flatMap(_.syntheticBoolean.toVector)
      .filter { record =>
        validSyntheticBooleanRecord(component, record) &&
        (record.outer.source eq resize) &&
        (resize.input eq record.uintCast) &&
        resize.input.getWidth == 1 &&
        resize.size == record.target.getBitsWidth &&
        resize.size > 1
      }
    matches match {
      case Vector(record) => Some(record.target)
      case _              => None
    }
  }

  private def validRecord(component: Component, record: Record): Boolean =
    (record.component eq component) &&
      (record.outer ne null) &&
      (record.sourceDriver ne null) &&
      (record.outer ne record.sourceDriver) &&
      (record.target ne null) &&
      (record.resizeSource ne null) &&
      (record.target ne record.resizeSource) &&
      (record.outer.target eq record.target) &&
      (record.outer.finalTarget eq record.target) &&
      (record.sourceDriver.target eq record.resizeSource) &&
      (record.sourceDriver.finalTarget eq record.resizeSource) &&
      (record.target.component eq component) &&
      typedTargetMatches(record, record.resizeSource) &&
      record.target.getBitsWidth > 0

  private def storageOf(component: Component): Option[Storage] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])

  /** Remove all capture records once the publication rewrite has completed. */
  def clearGraph(top: Component): Unit = {
    if (top != null) {
      top.walkComponents(_.userCache.remove(StorageKey))
    }
  }
}
