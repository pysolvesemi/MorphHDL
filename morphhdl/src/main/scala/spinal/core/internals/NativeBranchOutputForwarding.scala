package spinal.core.internals

import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Retire only a native child connection with one whole, type-identical use.
  * Both alternatives must independently drive the same parent net completely.
  * The emitter reference and retained assignment identities are the authority;
  * printed names merely locate their already-validated publication.
  */
private[internals] object NativeBranchOutputForwarding {
  def publish(component: Component, source: String): String = {
    var lines = source.split("\n", -1).toVector
    val live = ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkStatements {
      case a: DataAssignmentStatement => live += a
      case _ =>
    }
    def plain(value: BaseType): Boolean = !value.dontSimplify &&
      !value.getTags().exists(t => t.isInstanceOf[Attribute] || t.isInstanceOf[CommentTag])
    def visit(regions: Vector[ParameterizedStructure.StructuralRegion]): Unit = regions.foreach { region =>
      region match {
        case branch: ParameterizedStructure.StructuralIf =>
          for ((owner, other) <- Vector(branch.whenTrue -> branch.whenFalse, branch.whenFalse -> branch.whenTrue);
               a <- owner.assignments if a.target eq a.finalTarget) {
            a.source match {
              case port: BitVector if port.isOutput && (port.component.parent eq component) &&
                  owner.children.exists(_ eq port.component) && plain(port) =>
                val target = a.finalTarget
                val drivers = live.filter(_.finalTarget eq target)
                val counterpart = other.assignments.filter(x => (x.finalTarget eq target) && (x.target eq target))
                val actual = NativeWidthFormalSchema.portBinding(port).map(_.binding.actual)
                  .orElse(ParameterizedWidth.expressionOf(port).filter(e => e.parameters.isEmpty ||
                    (port.component.isInstanceOf[BlackBox] && NativeWidthFormalSchema.ownsExpression(component,e))))
                val width = ParameterizedWidth.expressionOf(target)
                  .orElse(Some(ElabInt.literal(target.getBitsWidth).expression).filter(_ => actual.exists(_.parameters.isEmpty)))
                val sameWidth = actual.exists(x => width.exists(y => ElabInt.equivalentExpression(x,y)))
                if ((target.component eq component) && target.isComb && !target.isInput && plain(target) &&
                    target.getClass == port.getClass && sameWidth && counterpart.size == 1 && drivers.size == 2 &&
                    drivers.exists(_ eq a) && drivers.exists(_ eq counterpart.head)) {
                  val alias = NativeFiniteStatementLineage.referenceName(component, port)
                  val name = target.getName()
                  val edges = NativeFiniteStatementLineage.survivingLineAssignments(component, lines)
                  val forward = edges.collect { case (i, edge) if edge eq a => i }.toVector
                  val declaration = ("^\\s*wire\\s+(?:signed\\s+)?\\[[^;]+\\]\\s+" + Pattern.quote(alias) + "\\s*;\\s*$").r
                  val decls = lines.indices.filter(i => declaration.findFirstIn(lines(i)).nonEmpty)
                  val targetNet = ("^\\s*(?:output\\s+)?wire\\b.*\\b" + Pattern.quote(name) + "\\s*[,;]?\\s*$").r
                  val expectedRange = NativeWidthFormalSchema.publicationRange(component,port)
                    .getOrElse(s"[${port.getBitsWidth-1}:0]")
                  val binding = ("^(\\s*\\." + Pattern.quote(port.getName()) + "\\s*\\(\\s*)" +
                    Pattern.quote(alias + expectedRange) + "(\\s*\\).*)$").r
                  val connections = lines.indices.filter(i => binding.findFirstIn(lines(i)).nonEmpty)
                  val count = ParameterizedVerilogVecs.countReferenceIdentifiers(lines, Set(alias)).getOrElse(alias,0)
                  if (forward.size == 1 && decls.size == 1 && connections.size == 1 && count == 3 &&
                      lines.exists(l => targetNet.findFirstIn(l).nonEmpty) &&
                      lines(forward.head).trim.startsWith(s"assign $name = $alias;")) {
                    val m = binding.findFirstMatchIn(lines(connections.head)).get
                    lines = lines.updated(connections.head,m.group(1) + name + m.group(2))
                      .zipWithIndex.filterNot { case (_,i) => i == decls.head || i == forward.head }.map(_._1)
                  }
                }
              case _ =>
            }
          }
        case _ =>
      }
      region.blocks.foreach(b => visit(b.regions))
    }
    visit(ParameterizedStructure.regionsOf(component))
    lines.mkString("\n")
  }
}
