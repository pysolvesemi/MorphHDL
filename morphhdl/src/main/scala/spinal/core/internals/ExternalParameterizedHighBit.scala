package spinal.core.internals

import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Preserve and publish the exact native msb operation. The normal emitter
  * prints its Int witness; publication replaces only that operation's protected
  * direct assignment, after checking the original graph identities again.
  */
object ExternalParameterizedHighBit {
  private object StorageKey
  private final case class Record(
      assignment: DataAssignmentStatement,
      target: Bool,
      access: BitVectorBitAccessFixed,
      source: BitVector,
      width: ElaborationIntegerExpression,
      assignmentScope: NativePublicationScope,
      targetScope: NativePublicationScope,
      sourceScope: NativePublicationScope
  )

  private def fail(detail: String): Nothing = throw new ParameterizedVerilogException(
    "SPINAL-PARAMETERIZED-VERILOG-HIGH-BIT-LINEAGE-MISMATCH", detail)

  private final class Storage(val records: Vector[Record]) {
    val byAccess = new java.util.IdentityHashMap[BitVectorBitAccessFixed, Record]()
    records.foreach { record =>
      if (byAccess.put(record.access, record) != null)
        fail("native high-bit capture has duplicate access identities")
    }
  }

  private def storage(component: Component): Option[Storage] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])

  private def records(component: Component): Vector[Record] =
    storage(component).map(_.records).getOrElse(Vector.empty)

  private val publicationValidation = new ThreadLocal[
    java.util.IdentityHashMap[Record, java.lang.Boolean]]()

  /** Reuse exact-record checks only inside one immutable publication call.
    * Revalidate at both boundaries and restore the previous thread context,
    * including on failure; no result escapes after a graph mutation.
    */
  private[internals] def withPublicationValidation[A](component: Component)(body: => A): A = {
    val capturedStorage = storage(component)
    val captured = capturedStorage.map(_.records).getOrElse(Vector.empty)
    def revalidate(): Unit = {
      if (storage(component).orNull ne capturedStorage.orNull)
        fail("native high-bit capture ownership changed during publication")
      captured.foreach { record =>
        if (!validFresh(component, record)) fail("retained native high-bit assignment changed during publication")
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
    require(boundary >= 0, "native high-bit capture requires the unnamed-intermediate boundary")
    phases.insert(boundary, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = pc.walkComponents { component =>
        val captured = ArrayBuffer.empty[Record]
        component.dslBody.walkStatements {
          case assignment: DataAssignmentStatement =>
            (assignment.target, assignment.source) match {
              case (target: Bool, access: BitVectorBitAccessFixed)
                  if (assignment.finalTarget eq target) && target.isComb &&
                    (target.component eq component) && NativeWidthProvenance.isHighBit(access) =>
                access.source match {
                  case source: BitVector if source.component eq component =>
                    NativeWidthProvenance.widthOf(source).filter(_.parameters.nonEmpty).foreach { width =>
                      NativePublicationWidth.validate(width, component, source,
                        "native high-bit capture")
                      target.dontSimplifyIt().addTag(noBackendCombMerge)
                      source.dontSimplifyIt().addTag(noBackendCombMerge)
                      if (!target.isNamed) target.setWeakName("morphhdl_high_bit")
                      if (!source.isNamed) source.setWeakName("morphhdl_high_bit_source")
                      captured += Record(assignment, target, access, source, width,
                        NativePublicationScope.capture(component, assignment.parentScope),
                        NativePublicationScope.capture(component, target.parentScope),
                        NativePublicationScope.capture(component, source.parentScope))
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
    count == 1 && drivers == 1 && targets == 1 && sources == 1 &&
      record.assignmentScope.matches(record.assignment.parentScope) &&
      record.targetScope.matches(record.target.parentScope) &&
      record.sourceScope.matches(record.source.parentScope) &&
      (record.assignment.target eq record.target) &&
      (record.assignment.source eq record.access) &&
      (record.access.source eq record.source) &&
      (record.target.component eq component) && (record.source.component eq component) &&
      record.target.isComb && record.target.dontSimplify && record.source.dontSimplify &&
      record.target.hasTag(noBackendCombMerge) && record.source.hasTag(noBackendCombMerge) &&
      NativeWidthProvenance.isHighBit(record.access) &&
      NativeWidthProvenance.widthOf(record.source)
        .exists(NativePublicationWidth.equivalentAtOwner(_, record.width, component, record.source))
  }

  private[internals] def proves(component: Component, access: BitVectorBitAccessFixed): Boolean =
    storage(component).flatMap(value => Option(value.byAccess.get(access))).exists(valid(component, _))

  private[internals] def rewrite(component: Component, verilog: String): String = {
    var lines = verilog.split("\n", -1).toVector
    val claimed = scala.collection.mutable.HashSet.empty[String]
    records(component).foreach { record =>
      if (!valid(component, record)) fail("retained native high-bit assignment changed after capture")
      val targetName = Option(record.target.getName()).filter(_.nonEmpty)
        .getOrElse(fail("retained native high-bit target has no emitted name"))
      val sourceName = Option(record.source.getName()).filter(_.nonEmpty)
        .getOrElse(fail("retained native high-bit source has no emitted name"))
      if (!claimed.add(targetName)) fail(s"multiple high-bit targets share emitted name '$targetName'")
      val assignment = ("^(\\s*assign\\s+" + Pattern.quote(targetName) +
        "\\s*=\\s*)(.*?)(;\\s*)$").r
      val expected = s"$sourceName[${record.access.bitId}]"
      var targets = 0
      var matches = 0
      lines = lines.map {
        case assignment(prefix, rhs, suffix) =>
          targets += 1
          if (rhs.trim == expected) {
            matches += 1
            s"$prefix$sourceName[(${record.width.verilog}) - 1]$suffix"
          } else prefix + rhs + suffix
        case line => line
      }
      if (targets != 1 || matches != 1)
        fail(s"high-bit target '$targetName' maps to $targets native assignments and $matches exact witness edges")
    }
    lines.mkString("\n")
  }
}
