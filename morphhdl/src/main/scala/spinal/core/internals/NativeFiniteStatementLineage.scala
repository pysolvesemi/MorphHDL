package spinal.core.internals

import spinal.core._
import scala.collection.mutable.ArrayBuffer

/** Private native handoff for finite packed writes. Names/text do not mint an edge. */
private[internals] object NativeFiniteStatementLineage {
  private object Key
  private object ScalarKey
  private object ReferenceKey
  private object WrapperKey
  def captureReferences(printer: ComponentEmitterVerilog): Unit = {
    printer.component.userCache(WrapperKey) = printer.wrappedExpressionToName.toVector
    if (!printer.component.userCache.contains(ReferenceKey)) {
      val refs = new java.util.IdentityHashMap[BaseType, String]()
      printer.component.children.foreach(_.getOrdredNodeIo.foreach { port =>
        refs.put(port, printer.emitReference(port, false))
      })
      printer.component.userCache(ReferenceKey) = refs
    }
  }
  def wrapper(component: Component, name: String): Option[Expression] =
    component.userCache.get(WrapperKey).toVector.flatMap(_.asInstanceOf[Vector[(Expression, String)]])
      .find(_._2 == name).map(_._1)
  def referenceName(component: Component, source: BaseType): String =
    component.userCache.get(ReferenceKey).flatMap(value => Option(value
      .asInstanceOf[java.util.IdentityHashMap[BaseType, String]].get(source))).getOrElse(source.getName())
  private def scalarRecords(component: Component) = component.userCache.getOrElseUpdate(ScalarKey,
    new java.util.IdentityHashMap[AssignmentStatement, Vector[(BaseType, String)]]())
    .asInstanceOf[java.util.IdentityHashMap[AssignmentStatement, Vector[(BaseType, String)]]]

  def captureScalarReferences(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Unit = {
    captureReferences(printer)
    val captures = ParameterizedStructure.regionsOf(printer.component)
      .flatMap(ParameterizedStructure.allBlocks).flatMap(_.scalarOperators)
      .filter(_.assignment eq assignment)
    if (captures.nonEmpty) {
      val references = captures.head.sources.map {
        case value: BaseType => value -> printer.emitReference(value, false)
        case _ => throw new IllegalArgumentException("scalar source must retain its native declaration identity")
      }
      scalarRecords(printer.component).put(assignment, references)
    }
  }

  def scalarReference(component: Component, assignment: AssignmentStatement, source: BaseType): Option[String] =
    Option(scalarRecords(component).get(assignment)).flatMap(_.find(_._1 eq source).map(_._2))
  private final case class Edge(assignment: AssignmentStatement, marker: String)
  private def records(component: Component): ArrayBuffer[Edge] = component.userCache
    .getOrElseUpdate(Key, ArrayBuffer.empty[Edge]).asInstanceOf[ArrayBuffer[Edge]]

  def suffix(component: Component, assignment: AssignmentStatement): String = {
    if (!TypedFinitePackedAccess.writes(component).exists(_.assignment eq assignment)) return ""
    val edges = records(component)
    require(!edges.exists(_.assignment eq assignment), "finite packed assignment was emitted twice")
    val marker = s" // native_edge_${edges.size}"
    edges += Edge(assignment, marker)
    marker
  }

  def lineAssignments(component: Component, lines: Vector[String]): Map[Int, AssignmentStatement] =
    records(component).map { edge =>
      val matches = lines.indices.filter(i => lines(i).endsWith(edge.marker))
      require(matches.size == 1, "finite packed assignment lost or duplicated its native emission edge")
      require(TypedFinitePackedAccess.writes(component).exists(_.assignment eq edge.assignment),
        "native packed assignment lineage lost its capture")
      matches.head -> edge.assignment
    }.toMap

  def finish(component: Component, source: String): String = {
    val markers = records(component).map(_.marker).toSet
    source.split("\n", -1).map { line =>
      markers.find(line.endsWith).map(marker => line.dropRight(marker.length)).getOrElse(line)
    }.mkString("\n")
  }
}
