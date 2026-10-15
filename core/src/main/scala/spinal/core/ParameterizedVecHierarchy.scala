package spinal.core

import scala.collection.mutable.ArrayBuffer
import spinal.core.internals._
import spinal.idslplugin.Location

/** Exact native audit adaptation at a generic child boundary. Logical geometry
  * is established by formal substitution, not equal elaborated widths.
  */
private[spinal] object ParameterizedVecHierarchy {
  private object Key
  final class Evidence private[ParameterizedVecHierarchy](
      val target: Vec[_], val source: Vec[_], val child: Vec[_], val parent: Vec[_],
      val input: Boolean, val operation: ParameterizedVecWholeAssignment,
      val paired: Vector[(BaseType, BaseType)], val padding: Vector[(BaseType, Literal)]) {
    val auditLeaves = leaves(child).filterNot(leaf => paired.exists { case (to, from) =>
      (if(input) to else from) eq leaf
    })
    val parentComponent = parent.component
    val childComponent = child.component
    private[ParameterizedVecHierarchy] val targetShape=ParameterizedVec.shapeOf(target).get
    private[ParameterizedVecHierarchy] val sourceShape=ParameterizedVec.shapeOf(source).get
    private[ParameterizedVecHierarchy] val widths=paired.map { case(to,from) => (to.getBitsWidth,from.getBitsWidth) }
    private[ParameterizedVecHierarchy] val paddingWidths=padding.map(_._1.getBitsWidth)
  }
  private def leaves(vector: Vec[_]): Vector[BaseType] =
    vector.vec.flatMap(_.asInstanceOf[Data].flatten).toVector
  private def entries(component: Component): Vector[Evidence] =
    component.userCache.get(Key).map(_.asInstanceOf[ArrayBuffer[Evidence]].toVector).getOrElse(Vector.empty)

  /** Pair physical carriers by recursive coordinates, never by flattened
    * prefixes: padding in an inner dimension is interleaved between real rows. */
  private def coordinatePairs(child: Data, parent: Data): Option[Vector[(BaseType, BaseType)]] = {
    val active = new java.util.IdentityHashMap[Data, java.lang.Boolean]()
    var visited = 0
    def collect(values: Vector[Option[Vector[(BaseType, BaseType)]]]) =
      if(values.forall(_.nonEmpty)) Some(values.flatMap(_.get)) else None
    def visit(child: Data, parent: Data): Option[Vector[(BaseType, BaseType)]] = {
      visited += 1
      if(child == null || parent == null || active.size >= 128 || visited > 65536 ||
          active.containsKey(child) || active.containsKey(parent)) return None
      active.put(child,java.lang.Boolean.TRUE)
      active.put(parent,java.lang.Boolean.TRUE)
      try { (child, parent) match {
        case (c: BaseType, p: BaseType) if c.getTypeObject == p.getTypeObject => Some(Vector(c -> p))
        case (c: Vec[_], p: Vec[_]) if c.vec.size >= p.vec.size && p.vec.nonEmpty =>
          collect(c.vec.take(p.vec.size).zip(p.vec).map { case (a,b) =>
            visit(a.asInstanceOf[Data],b.asInstanceOf[Data])
          }.toVector)
        case (c: MultiData, p: MultiData) if c.getClass == p.getClass &&
            c.elements.map(_._1) == p.elements.map(_._1) =>
          collect(c.elements.zip(p.elements).map { case ((_,a),(_,b)) => visit(a,b) }.toVector)
        case _ => None
      }} finally { active.remove(child); active.remove(parent) }
    }
    visit(child,parent)
  }

  def assign(target: Vec[_], source: Vec[_], kind: AnyRef)(implicit loc: Location): Boolean = {
    if (leaves(target).size == leaves(source).size || (kind ne DataAssign) ||
        target.component == null || source.component == null) return false
    val input = target.component.parent eq source.component
    val output = source.component.parent eq target.component
    if (!input && !output) return false
    val child = if(input) target else source
    val parent = if(input) source else target
    if ((Component.current ne parent.component) ||
        (DslScopeStack.get ne parent.component.dslBody) ||
        !ParameterizedVec.logicalHierarchyCompatible(child,parent)) return false
    val childLeaves=leaves(child)
    val parentLeaves=leaves(parent)
    if(childLeaves.size <= parentLeaves.size ||
      !childLeaves.forall(l => l.isIo && !l.isInOut &&
        (if(input) l.isInput && !l.isOutput else l.isOutput && !l.isInput)) ||
      !parentLeaves.forall(l => !l.isInOut && (l.component eq parent.component)) ||
      !childLeaves.forall(l => Set[Any](TypeBool,TypeBits,TypeUInt,TypeSInt)(l.getTypeObject))) return false
    val coordinates=coordinatePairs(child,parent).getOrElse(return false)
    if(coordinates.size != parentLeaves.size ||
        coordinates.map(_._1).distinct.size != coordinates.size ||
        coordinates.map(_._2).distinct.size != coordinates.size) return false
    val paired=coordinates.map { case(c,p) => if(input) c -> p else p -> c }
    val extra=childLeaves.filterNot(leaf => coordinates.exists(_._1 eq leaf))
    val padding=if(input) extra.map { leaf =>
      val zero: Literal=leaf.getTypeObject match {
        case TypeBool => new BoolLiteral(false)
        case TypeBits => BitsLiteral(BigInt(0),leaf.getBitsWidth)
        case TypeUInt => UIntLiteral(BigInt(0),leaf.getBitsWidth)
        case TypeSInt => SIntLiteral(BigInt(0),leaf.getBitsWidth)
      }
      leaf -> zero
    } else Vector.empty
    parentLeaves.foreach(_.dontSimplifyIt().setAsVital())
    val(_,assignments)=ParameterizedVec.captureAssignments(target) {
      paired.foreach { case(to,from) => to.compositAssignFrom(from,to,kind) }
      padding.foreach { case(to,zero) => to.assignFrom(zero) }
    }
    val operation=ParameterizedVec.recordWholeAssignment(target,source,assignments,kind).get
    val evidence=new Evidence(target,source,child,parent,input,operation,paired,padding)
    parent.component.userCache.getOrElseUpdate(Key,ArrayBuffer.empty[Evidence])
      .asInstanceOf[ArrayBuffer[Evidence]] += evidence
    true
  }

  def between(left: Vec[_], right: Vec[_]): Option[Evidence] = {
    if(left == null || right == null || left.component == null || right.component == null) return None
    val owner=if(left.component.parent eq right.component) right.component
      else if(right.component.parent eq left.component) left.component else return None
    val found=entries(owner).filter(e => ((e.target eq left) && (e.source eq right)) ||
      ((e.target eq right) && (e.source eq left)))
    if(found.size == 1 && valid(found.head)) Some(found.head) else None
  }

  /** Audit zeros cannot infer width bindings. */
  def isPadding(parent: Component, child: Component, port: BaseType,
      assignment: DataAssignmentStatement): Boolean =
    parent != null && child != null && (child.parent eq parent) && entries(parent).exists { e =>
      e.input && (e.childComponent eq child) && e.operation.assignments.exists(_ eq assignment) &&
        e.padding.exists { case(leaf,zero) => (leaf eq port) && (assignment.finalTarget eq leaf) &&
          (assignment.source eq zero) } && valid(e)
    }

  /** The Vec publisher separately validates emission of these native-only ports. */
  def isAuditPort(parent: Component, child: Component, port: BaseType): Boolean =
    parent != null && child != null && (child.parent eq parent) && entries(parent).exists { e =>
      (e.childComponent eq child) && e.auditLeaves.exists(_ eq port) && valid(e)
    }

  def matches(local: Vec[_], peer: Vec[_], assignments: Vector[DataAssignmentStatement]): Boolean =
    between(local,peer).exists(e => assignments.size == e.operation.assignments.size &&
      assignments.forall(a => e.operation.assignments.exists(_ eq a)))

  private def valid(e: Evidence): Boolean = {
    if((e.child.component ne e.childComponent) || (e.parent.component ne e.parentComponent) ||
      (e.childComponent.parent ne e.parentComponent) ||
      !ParameterizedVec.logicalHierarchyCompatible(e.child,e.parent) ||
      !ParameterizedVec.operationsOf(e.target).exists(_ eq e.operation)) return false
    val assignments=e.operation.assignments
    val targetLeaves=leaves(e.target)
    val coordinates=coordinatePairs(e.child,e.parent).getOrElse(return false)
    val currentPairs=coordinates.map { case(c,p) => if(e.input) c -> p else p -> c }
    val extra=leaves(e.child).filterNot(leaf => coordinates.exists(_._1 eq leaf))
    if(extra.size != e.auditLeaves.size || !extra.zip(e.auditLeaves).forall { case(a,b) => a eq b } ||
        (e.input && (e.padding.size != extra.size ||
          !e.padding.zip(extra).forall { case((leaf,_), expected) => leaf eq expected }))) return false
    if(!ParameterizedVec.shapeOf(e.target).exists(_ eq e.targetShape) ||
      !ParameterizedVec.shapeOf(e.source).exists(_ eq e.sourceShape) ||
      currentPairs.size != e.paired.size || !currentPairs.zip(e.paired).forall {
        case((to,from),(expectedTo,expectedFrom)) => (to eq expectedTo) && (from eq expectedFrom)
      } || !e.paired.zip(e.widths).forall { case((to,from),(tw,sw)) =>
        to.getBitsWidth == tw && from.getBitsWidth == sw && to.getTypeObject == from.getTypeObject
      } || !leaves(e.child).forall(l => (l.component eq e.childComponent) && l.isIo && !l.isInOut &&
        (if(e.input) l.isInput && !l.isOutput else l.isOutput && !l.isInput)) ||
      !leaves(e.parent).forall(l => (l.component eq e.parentComponent) && !l.isInOut &&
        (e.input || !l.isInput))) return false
    val live=ArrayBuffer.empty[DataAssignmentStatement]
    e.parentComponent.dslBody.walkStatements {
      case a: DataAssignmentStatement if targetLeaves.exists(_ eq a.finalTarget) => live += a
      case _ =>
    }
    if(assignments.size != targetLeaves.size || live.size != assignments.size ||
      !assignments.forall(a => live.exists(_ eq a) && (a.target eq a.finalTarget) &&
        (a.parentScope eq e.parentComponent.dslBody))) return false
    if(!e.paired.forall { case(to,from) => assignments.count(a =>
      (a.finalTarget eq to) && (a.source eq from)) == 1 }) return false
    if(!e.padding.zip(e.paddingWidths).forall { case((to,zero),width) =>
      val exactWidth=zero match {
        case bits: BitVectorLiteral => bits.getWidth == width
        case _: BoolLiteral => width == 1
        case _ => false
      }
      exactWidth && to.getBitsWidth == width && !zero.hasPoison() && zero.getValue() == 0 &&
        zero.getTypeObject == to.getTypeObject && assignments.count(a =>
          (a.finalTarget eq to) && (a.source eq zero)) == 1
    }) return false
    if(!e.input) {
      var referenced=false
      e.parentComponent.dslBody.walkStatements { statement =>
        statement.walkDrivingExpressions { expression => if(extra.exists(_ eq expression)) referenced=true }
      }
      if(referenced) return false
    }
    true
  }
}
