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

  private def fail(detail: String): Nothing = throw new ParameterizedVerilogException(
    "SPINAL-PARAMETERIZED-VERILOG-NATIVE-RESIZE-LINEAGE-MISMATCH", detail)

  private final class Storage(val records: Vector[Record]) {
    val byResize = new java.util.IdentityHashMap[Resize, Record]()
    val byTarget = new java.util.IdentityHashMap[BaseType, Record]()
    val byAssignment = new java.util.IdentityHashMap[DataAssignmentStatement, Record]()
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

  private val publicationValidation = new ThreadLocal[
    java.util.IdentityHashMap[Record, java.lang.Boolean]]()

  /** Limit validation reuse to one pure final publication call. Recheck every
    * captured record before returning its text so mutation cannot cross the
    * publication boundary with a cached proof.
    */
  private[internals] def withPublicationValidation[A](component: Component)(body: => A): A = {
    val capturedStorage = storage(component)
    val captured = capturedStorage.map(_.records).getOrElse(Vector.empty)
    def revalidate(): Unit = {
      if (storage(component).orNull ne capturedStorage.orNull)
        fail("native resize capture ownership changed during publication")
      captured.foreach { record =>
        if (!validFresh(component, record)) fail("retained native resize assignment changed during publication")
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
                    !packedReadWrappers.containsKey(assignment) &&
                    !(morphhdl.MorphSignedCasts.isEnabled(pc.config) && resize.isInstanceOf[ResizeSInt]) =>
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
                      if (sourceWidth.minimum < 1 || targetWidth.minimum < 1 ||
                          sourceWidth.default != source.getBitsWidth || targetWidth.default != target.getBitsWidth)
                        fail("native resize target and source must retain positive, witness-consistent widths")
                      // Native identity elimination and a fixed narrowing slice
                      // already have the same meaning across the complete owner
                      // domain. Keep those original native graphs and their
                      // emission intact; only varying resize boundaries need
                      // protected declarations and symbolic publication.
                      val identity = (source.parentScope eq target.parentScope) &&
                        NativePublicationWidth.equivalentAtOwner(
                          sourceWidth, targetWidth, component, target)
                      val fixedNarrowing = targetWidth.parameters.isEmpty &&
                        targetWidth.maximum <= sourceWidth.minimum
                      if (!identity && !fixedNarrowing) {
                        target.dontSimplifyIt().addTag(noBackendCombMerge)
                        source.dontSimplifyIt().addTag(noBackendCombMerge)
                        if (!target.isNamed) target.setWeakName("morphhdl_resize")
                        if (!source.isNamed) source.setWeakName("morphhdl_resize_source")
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

  private def valid(component: Component, record: Record): Boolean = {
    val current = publicationValidation.get()
    (current != null && current.containsKey(record)) || validFresh(component, record)
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

  private[internals] def proves(component: Component, resize: Resize): Boolean =
    storage(component).flatMap(value => Option(value.byResize.get(resize))).exists(valid(component, _))

  /** The original target-sized assignment remains a resize boundary when
    * native simplification removes an equal-witness Resize expression.
    */
  private[internals] def provesAssignment(
      component: Component,
      assignment: DataAssignmentStatement
  ): Boolean =
    storage(component).flatMap(value => Option(value.byAssignment.get(assignment))).exists(valid(component, _))

  private[internals] def targetWidthOf(component: Component, target: BaseType)
      : Option[ElaborationIntegerExpression] =
    storage(component).flatMap(value => Option(value.byTarget.get(target)))
      .filter(valid(component, _)).map(_.targetWidth)

  /** The generic width analysis must publish the same geometry that this
    * captured native resize proves. A fixed source witness cannot authorize
    * rewriting a declaration which that analysis infers as symbolic instead.
    */
  private[internals] def validatePublishedWidths(component: Component)(
      mismatch: (BitVector, ElaborationIntegerExpression) => Option[String]
  ): Unit = records(component).foreach { record =>
    if (!valid(component, record))
      fail("retained native resize assignment changed before width validation")
    mismatch(record.source, record.sourceWidth).foreach { detail =>
      fail(s"native resize source publication differs from its captured exact width: $detail")
    }
    mismatch(record.target, record.targetWidth).foreach { detail =>
      fail(s"native resize target publication differs from its captured exact width: $detail")
    }
  }

  private[internals] def rewrite(component: Component, verilog: String): String = {
    var lines = verilog.split("\n", -1).toVector
    val claimed = scala.collection.mutable.HashSet.empty[String]
    records(component).foreach { record =>
      if (!valid(component, record)) fail("retained native resize assignment changed after capture")
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
    lines.mkString("\n")
  }
}
