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
      typedTarget: Option[ElaborationIntegerExpression],
      typedResize: Option[ResizeUInt],
      typedInput: Option[UInt]
  )

  private final case class Record(
      component: Component,
      outer: DataAssignmentStatement,
      target: UInt,
      resizeSource: UInt,
      sourceDriver: DataAssignmentStatement,
      typedTarget: Option[ElaborationIntegerExpression],
      typedResize: Option[ResizeUInt],
      typedInput: Option[UInt]
  )

  /** Exact capture-time lineage for one explicit typed UInt resize whose
    * carrier was normalized into its fixed consumer. The publication rewrite
    * uses both retained width expressions to reconstruct the removed resize;
    * this record alone never authorizes direct-assignment equivalence.
    */
  private[internals] final case class NormalizedTypedUIntResizeBoundary(
      source: UInt,
      sourceWidth: ElaborationIntegerExpression,
      targetWidth: ElaborationIntegerExpression
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
    val typedResizeCarriers =
      new IdentityHashMap[BitVector, java.lang.Boolean]()
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
        outer.target match {
          case value: BitVector
              if (outer.finalTarget eq value) &&
                value.hasTag(ParameterizedWidth.TypedResizeCaptureTag) =>
            typedResizeCarriers.put(value, java.lang.Boolean.TRUE)
          case _ =>
        }
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
              val typedCapture =
                resizeSource.hasTag(ParameterizedWidth.TypedResizeCaptureTag)
              val typedTarget =
                if (typedCapture)
                  ParameterizedWidth
                    .expressionOf(resizeSource)
                    .filter(_.parameters.nonEmpty)
                else None
              if (typedCapture && typedTarget.isEmpty) {
                ParameterizedVerilogException.fail(
                  "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-TARGET-WIDTH-MISSING",
                  "a typed UInt resize capture marker has no exact symbolic target-width provenance"
                )
              }
              val typedLineage = typedTarget.flatMap { expected =>
                driver.source match {
                  case resize: ResizeUInt
                      if resize.getTypeObject == TypeUInt &&
                        resize.size == resizeSource.getBitsWidth =>
                    resize.input match {
                      case input: UInt
                          if ParameterizedWidth
                              .resizeExpressionOf(resize)
                              .exists { retained =>
                                ExternalFormalParameterRegistry
                                  .equivalentExpression(retained, expected)
                              } =>
                        Some(resize -> input)
                      case _ => None
                    }
                  case _ => None
                }
              }
              if (typedTarget.nonEmpty && typedLineage.isEmpty) {
                ParameterizedVerilogException.fail(
                  "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-LINEAGE-UNPROVEN",
                  "a symbolic typed UInt resize reached native normalization without exact Resize/input lineage",
                  typedTarget.flatMap(_.sourceLocation)
                )
              }
              if (
                resizeSource.hasTag(tagAutoResize) ||
                typedCapture
              ) {
                val candidate = Candidate(
                  component,
                  outer,
                  target,
                  resizeSource,
                  driver,
                  typedTarget,
                  typedLineage.map(_._1),
                  typedLineage.map(_._2)
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
          candidate.typedTarget,
          candidate.typedResize,
          candidate.typedInput
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
        record.typedTarget.foreach { expected =>
          ParameterizedStructure.validateProjectedAssignmentDominance(
            component,
            record.outer,
            expected,
            "typed UInt resize capture target width",
            expected.sourceLocation
          )
        }
        storage.byStatement.put(record.outer, record)
        storage.byStatement.put(record.sourceDriver, record)
        storage.byResizeSource.put(record.resizeSource, record)
      }
    }
    storage.syntheticBoolean ++= syntheticRecords
    if (!storage.byStatement.isEmpty || storage.syntheticBoolean.nonEmpty) {
      component.userCache.update(StorageKey, storage)
    }
    val typedCarrierIterator = typedResizeCarriers.keySet().iterator()
    while (typedCarrierIterator.hasNext) {
      val value = typedCarrierIterator.next()
      val useCount = drivingUseCount.get(value)
      val reviewedDirectBoundary = value match {
        case uint: UInt =>
          Option(storage.byResizeSource.get(uint)).exists { record =>
            val targetUseCount = drivingUseCount.get(record.target)
            !record.target.isDirectionLess || record.target.isNamed ||
            record.target.dontSimplify || targetUseCount == null ||
            targetUseCount.intValue() != 1
          }
        case _ => false
      }
      // The capture tag intentionally prevents native simplification while
      // identities are collected. Judge the next phase only after consuming
      // that marker, exactly as publication will see the carrier.
      value.removeTag(ParameterizedWidth.TypedResizeCaptureTag)
      if (
        useCount != null && useCount.intValue() == 1 &&
        value.isComb && value.isDirectionLess && value.isUnnamed &&
        !value.dontSimplify &&
        !reviewedDirectBoundary
      ) {
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-NESTED-TYPED-RESIZE-UNSUPPORTED",
          "a one-use typed packed resize is nested in an expression without a reviewed native reconstruction boundary; assign it to an explicit retained carrier first",
          ParameterizedWidth.expressionOf(value).flatMap(_.sourceLocation)
        )
      }
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

  /** Recover one exact explicit typed UInt resize whose carrier was removed by
    * native input normalization. Capture-time Resize/input identities and the
    * surviving outer edge must all agree. Merely matching witness widths is
    * insufficient: callers must reconstruct the removed resize semantics.
    */
  private[internals] def normalizedTypedUIntResizeBoundary(
      component: Component,
      assignment: DataAssignmentStatement,
      target: UInt
  ): Option[NormalizedTypedUIntResizeBoundary] = {
    if (component == null || assignment == null || target == null) return None
    storageOf(component)
      .flatMap(storage => Option(storage.byStatement.get(assignment)))
      .filter(record => validRecord(component, record))
      .flatMap { record =>
        val untypedConsumer =
          ParameterizedWidth
            .expressionOf(target)
            .forall(_.parameters.isEmpty)
        if (
          !(assignment eq record.outer) ||
          !(target eq record.target) ||
          !untypedConsumer ||
          !(assignment.target eq target) ||
          !(assignment.finalTarget eq target) ||
          !(target.component eq component)
        ) None
        else {
          val lineage = for {
            resize <- record.typedResize
            input <- record.typedInput
            expected <- record.typedTarget
            if (assignment.source eq input)
            if (resize.input eq input)
            if resize.getTypeObject == TypeUInt
            if resize.size == record.resizeSource.getBitsWidth
            if ParameterizedWidth.resizeExpressionOf(resize).exists { retained =>
              ExternalFormalParameterRegistry
                .equivalentExpression(retained, expected)
            }
          } yield (resize, input, expected)
          lineage.map { case (_, input, expected) =>
            if (input.component ne component) {
              ParameterizedVerilogException.fail(
                "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-OWNER-UNSUPPORTED",
                "a normalized typed UInt resize cannot move a child-owned or foreign source into its parent's fixed consumer",
                expected.sourceLocation
              )
            }
            val sourceWidth = ParameterizedWidth.expressionOf(input).orElse {
              if (input.isInput)
                Some(ElabInt.literal(input.getBitsWidth).expression)
              else None
            }.getOrElse {
              ParameterizedVerilogException.fail(
                "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-WIDTH-UNPROVEN",
                "a normalized typed UInt resize has an internal source without retained width provenance",
                expected.sourceLocation
              )
            }
            if (sourceWidth.default != BigInt(input.getBitsWidth)) {
              ParameterizedVerilogException.fail(
                "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-WITNESS-MISMATCH",
                s"normalized typed UInt resize source witness ${input.getBitsWidth} does not match retained width '${sourceWidth.verilog}' default ${sourceWidth.default}",
                sourceWidth.sourceLocation.orElse(expected.sourceLocation)
              )
            }
            ParameterizedStructure.validateProjectedAssignmentDominance(
              component,
              assignment,
              sourceWidth,
              "normalized typed UInt resize source width",
              sourceWidth.sourceLocation.orElse(expected.sourceLocation)
            )
            ParameterizedStructure.validateProjectedAssignmentDominance(
              component,
              assignment,
              expected,
              "normalized typed UInt resize target width",
              expected.sourceLocation.orElse(sourceWidth.sourceLocation)
            )
            NormalizedTypedUIntResizeBoundary(input, sourceWidth, expected)
          }
        }
      }
  }

  /** All exact normalized typed UInt resize boundaries owned by one component.
    * The assignment walk preserves native graph identity and deterministic
    * declaration order; no rendered-name inference participates.
    */
  private[internals] def normalizedTypedUIntResizeBoundariesOf(
      component: Component
  ): Vector[NormalizedTypedUIntResizeBoundary] = {
    if (component == null) return Vector.empty
    val boundaries = ArrayBuffer.empty[NormalizedTypedUIntResizeBoundary]
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement
          if assignment.target eq assignment.finalTarget =>
        assignment.target match {
          case target: UInt =>
            normalizedTypedUIntResizeBoundary(
              component,
              assignment,
              target
            ).foreach(boundaries += _)
          case _ =>
        }
      case _ =>
    }
    boundaries.toVector
  }

  private[internals] def parametersOf(
      component: Component
  ): Vector[ElaborationIntegerParameter] =
    normalizedTypedUIntResizeBoundariesOf(component)
      .flatMap(boundary =>
        boundary.sourceWidth.parameters ++ boundary.targetWidth.parameters
      )
      .distinct
      .sortBy(_.name)

  /** Whether one exact fixed consumer has a mandatory reconstruction record.
    * This does not claim that the normalized direct assignment is equivalent.
    */
  private[internals] def preservesFixedTypedResizeConsumer(
      component: Component,
      assignment: DataAssignmentStatement,
      target: UInt
  ): Boolean =
    normalizedTypedUIntResizeBoundary(component, assignment, target).nonEmpty

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

  private def validRecord(component: Component, record: Record): Boolean = {
    val typedLineageValid = record.typedTarget match {
      case None => record.typedResize.isEmpty && record.typedInput.isEmpty
      case Some(expected) =>
        (record.typedResize, record.typedInput) match {
          case (Some(resize), Some(input)) =>
            resize.getTypeObject == TypeUInt &&
              (resize.input eq input) &&
              input.component != null &&
              resize.size == record.resizeSource.getBitsWidth &&
              ParameterizedWidth.resizeExpressionOf(resize).exists { retained =>
                ExternalFormalParameterRegistry
                  .equivalentExpression(retained, expected)
              }
          case _ => false
        }
    }
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
      typedLineageValid &&
      record.target.getBitsWidth > 0
  }

  private def storageOf(component: Component): Option[Storage] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])

  /** Remove all capture records once the publication rewrite has completed. */
  def clearGraph(top: Component): Unit = {
    if (top != null) {
      top.walkComponents(_.userCache.remove(StorageKey))
    }
  }
}
