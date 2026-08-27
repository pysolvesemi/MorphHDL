package spinal.core.internals

import java.util.IdentityHashMap

import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * Exact, generation-local provenance for native UInt `.resized` assignments.
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
      sourceDriver: DataAssignmentStatement
  )

  private final case class Record(
      component: Component,
      outer: DataAssignmentStatement,
      target: UInt,
      resizeSource: UInt,
      sourceDriver: DataAssignmentStatement
  )

  private final class Storage {
    val byStatement = new IdentityHashMap[DataAssignmentStatement, Record]()
    val byResizeSource = new IdentityHashMap[UInt, Record]()
  }

  private final class CapturePhase extends PhaseMisc {
    override def impl(pc: PhaseContext): Unit = {
      pc.walkComponents(captureComponent)
    }
  }

  private def captureComponent(component: Component): Unit = {
    component.userCache.remove(StorageKey)
    val candidatesBySource =
      new IdentityHashMap[UInt, ArrayBuffer[Candidate]]()
    val drivingUseCount = new IdentityHashMap[UInt, java.lang.Integer]()

    component.dslBody.walkStatements { statement =>
      statement.walkDrivingExpressions {
        case source: UInt =>
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
        (outer.target, outer.source) match {
          case (target: UInt, resizeSource: UInt)
              if (outer.finalTarget eq target) &&
                (target.component eq component) &&
                (resizeSource.component eq component) &&
                resizeSource.isComb &&
                resizeSource.isDirectionLess &&
                resizeSource.hasTag(tagAutoResize) &&
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
              val candidate = Candidate(
                component,
                outer,
                target,
                resizeSource,
                driver
              )
              var candidates = candidatesBySource.get(resizeSource)
              if (candidates == null) {
                candidates = ArrayBuffer.empty[Candidate]
                candidatesBySource.put(resizeSource, candidates)
              }
              candidates += candidate
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
          candidate.sourceDriver
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
    if (!storage.byStatement.isEmpty) {
      component.userCache.update(StorageKey, storage)
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
        .filter(record =>
          ParameterizedWidth.expressionOf(record.target).exists(_.parameters.nonEmpty)
        )
        .map(_.target)
    }
  }

  /**
    * Prove that a surviving statement is one exact edge of a captured native
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
        (assignment.target eq target) &&
        (assignment.finalTarget eq target) &&
        (target.component eq component) &&
        currentSource.isInstanceOf[WidthProvider] &&
        currentSource.getTypeObject == TypeUInt &&
        currentSource.asInstanceOf[WidthProvider].getWidth == target.getBitsWidth &&
        !currentSource.isInstanceOf[Resize]
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
