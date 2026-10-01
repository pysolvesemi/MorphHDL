package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Publish exact native range and fill geometry retained before Int lowering.
  * Only a protected direct assignment with its original graph identities can
  * replace an emitted witness edge. Names locate edges; they are not proof.
  */
object ExternalParameterizedNativeGeometry {
  private object StorageKey
  private final case class Record(
      assignment: DataAssignmentStatement,
      target: BitVector,
      expression: Expression,
      source: Option[BitVector],
      width: ElaborationIntegerExpression,
      high: Option[ElaborationIntegerExpression],
      low: Option[ElaborationIntegerExpression],
      assignmentScope: NativePublicationScope,
      targetScope: NativePublicationScope,
      sourceScope: Option[NativePublicationScope],
      drivers: Vector[AssignmentStatement])

  private final class Storage(val records: Vector[Record]) {
    val byExpression = new IdentityHashMap[Expression, Record]()
    records.foreach { record =>
      if (byExpression.put(record.expression, record) != null)
        fail("one geometry expression has multiple publication owners")
    }
  }
  private def fail(detail: String): Nothing = throw new ParameterizedVerilogException(
    "SPINAL-PARAMETERIZED-VERILOG-NATIVE-GEOMETRY-LINEAGE-MISMATCH", detail)
  private def storage(component: Component): Option[Storage] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])
  private val publicationValidation = new ThreadLocal[IdentityHashMap[Record, java.lang.Boolean]]()
  private def assignments(component: Component, target: BaseType): Vector[AssignmentStatement] = {
    val result = ArrayBuffer.empty[AssignmentStatement]
    component.dslBody.walkStatements {
      case assignment: AssignmentStatement if assignment.finalTarget eq target => result += assignment
      case _ =>
    }
    result.toVector
  }
  private def same[A <: AnyRef](left: Vector[A], right: Vector[A]): Boolean =
    left.size == right.size && left.zip(right).forall { case (a, b) => a eq b }

  private def sameRetainedWidth(left: ElaborationIntegerExpression,
                                right: ElaborationIntegerExpression,
                                component: Component, owner: BaseType): Boolean =
    ElabInt.equivalentExpression(left, right) &&
      NativePublicationWidth.equivalentAtOwner(left, right, component, owner)

  def install(phases: ArrayBuffer[Phase]): Unit = {
    val boundary = phases.indexWhere(_.isInstanceOf[PhaseRemoveIntermediateUnnameds])
    require(boundary >= 0, "native geometry requires the unnamed-intermediate boundary")
    phases.insert(boundary, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = pc.walkComponents { component =>
        val captured = ArrayBuffer.empty[Record]
        component.dslBody.walkStatements {
          case assignment: DataAssignmentStatement => assignment.target match {
            case target: BitVector if (assignment.finalTarget eq target) && target.isComb &&
                (target.component eq component) =>
              val geometry = assignment.source match {
                case access: BitVectorRangedAccessFixed => access.source match {
                  case source: BitVector if source.component eq component =>
                    for {
                      rule <- NativeWidthProvenance.rangeOf(access)
                      sourceWidth <- NativeWidthProvenance.widthOf(source)
                      if sourceWidth.parameters.nonEmpty
                    } yield (Some(source), rule.resultWidth(sourceWidth),
                      Some(rule.high(sourceWidth)), Some(rule.low(sourceWidth)))
                  case _ => None
                }
                case literal: BitVectorLiteral =>
                  NativeWidthProvenance.fillWidthOf(literal).filter(_.parameters.nonEmpty)
                    .map(width => (None, width, None, None))
                case _ => None
              }
              geometry.foreach { case (source, width, high, low) =>
                NativePublicationWidth.validate(width, component, target, "native geometry result")
                source.foreach { value =>
                  high.foreach(NativePublicationWidth.validate(_, component, value, "native range high"))
                  low.foreach(NativePublicationWidth.validate(_, component, value, "native range low"))
                  value.dontSimplifyIt().addTag(noBackendCombMerge)
                  if (!value.isNamed) value.setWeakName("morphhdl_range_source")
                }
                target.dontSimplifyIt().addTag(noBackendCombMerge)
                if (!target.isNamed) target.setWeakName("morphhdl_geometry")
                captured += Record(assignment, target, assignment.source, source, width, high, low,
                  NativePublicationScope.capture(component, assignment.parentScope),
                  NativePublicationScope.capture(component, target.parentScope),
                  source.map(value => NativePublicationScope.capture(component, value.parentScope)),
                  assignments(component, target))
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
    val currentWidth = record.expression match {
      case access: BitVectorRangedAccessFixed =>
        for {
          rule <- NativeWidthProvenance.rangeOf(access)
          source <- record.source if access.source eq source
          sourceWidth <- NativeWidthProvenance.widthOf(source)
          if record.high.exists(sameRetainedWidth(
            rule.high(sourceWidth), _, component, source))
          if record.low.exists(sameRetainedWidth(
            rule.low(sourceWidth), _, component, source))
        } yield rule.resultWidth(sourceWidth)
      case literal: BitVectorLiteral => NativeWidthProvenance.fillWidthOf(literal)
      case _ => None
    }
    var targetCount = 0
    var sourceCount = 0
    component.dslBody.walkStatements {
      case value: BaseType =>
        if (value eq record.target) targetCount += 1
        if (record.source.exists(_ eq value)) sourceCount += 1
      case _ =>
    }
    targetCount == 1 && (record.source.isEmpty || sourceCount == 1) &&
      same(assignments(component, record.target), record.drivers) &&
      record.drivers.exists(_ eq record.assignment) &&
      record.assignmentScope.matches(record.assignment.parentScope) &&
      record.targetScope.matches(record.target.parentScope) &&
      record.source.zip(record.sourceScope).forall { case (source, scope) =>
        (source.component eq component) && scope.matches(source.parentScope) &&
          source.dontSimplify && source.hasTag(noBackendCombMerge)
      } &&
      (record.assignment.target eq record.target) && (record.assignment.finalTarget eq record.target) &&
      (record.assignment.source eq record.expression) && (record.target.component eq component) &&
      record.target.isComb && record.target.dontSimplify && record.target.hasTag(noBackendCombMerge) &&
      currentWidth.exists(sameRetainedWidth(_, record.width, component, record.target))
  }

  private[internals] def widthOf(component: Component, expression: Expression)
      : Option[ElaborationIntegerExpression] = storage(component)
    .flatMap(value => Option(value.byExpression.get(expression))).map { record =>
      if (!valid(component, record)) fail("retained native geometry changed after capture")
      record.width
    }

  private[internals] def withPublicationValidation[A](component: Component)(body: => A): A = {
    val original = storage(component)
    def check(): Unit = {
      if (storage(component).orNull ne original.orNull) fail("native geometry storage changed during publication")
      original.foreach(_.records.foreach { record =>
        if (!validFresh(component, record)) fail("retained native geometry changed during publication")
      })
    }
    check()
    val previous = publicationValidation.get()
    val current = new IdentityHashMap[Record, java.lang.Boolean]()
    original.foreach(_.records.foreach(record => current.put(record, java.lang.Boolean.TRUE)))
    publicationValidation.set(current)
    try {
      val result = body
      check()
      result
    } finally {
      if (previous == null) publicationValidation.remove()
      else publicationValidation.set(previous)
    }
  }

  private[internals] def rewrite(component: Component, verilog: String): String = {
    var lines = verilog.split("\n", -1).toVector
    storage(component).foreach(_.records.foreach { record =>
      if (!valid(component, record)) fail("retained native geometry assignment changed after capture")
      val name = Option(record.target.getName()).filter(_.nonEmpty)
        .getOrElse(fail("native geometry target has no emitted name"))
      val (expected, replacement) = record.expression match {
        case access: BitVectorRangedAccessFixed =>
          val sourceName = Option(record.source.get.getName()).filter(_.nonEmpty)
            .getOrElse(fail("native geometry source has no emitted name"))
          (s"$sourceName[${access.hi} : ${access.lo}]",
            s"$sourceName[${record.high.get.verilog} : ${record.low.get.verilog}]")
        case literal: BitVectorLiteral =>
          val bits = literal.getWidth
          val witness = if (bits > 4) s"${bits}'h${literal.hexString(bits, false)}"
            else s"${bits}'b${literal.getBitsStringOn(bits, 'x')}"
          (witness, "{" + record.width.verilog + "{1'b1}}")
        case _ => fail("captured native geometry kind changed")
      }
      val assignment = ("^(\\s*(?:assign\\s+)?" + Pattern.quote(name) +
        "\\s*(?:<=|=)\\s*)(.*?)(;\\s*)$").r
      var matches = 0
      lines = lines.map {
        case assignment(prefix, rhs, suffix) if rhs.trim == expected =>
          matches += 1
          prefix + replacement + suffix
        case line => line
      }
      if (matches != 1) fail(s"native geometry '$name' has $matches exact emitted witness edges; expected one")
    })
    lines.mkString("\n")
  }
}
