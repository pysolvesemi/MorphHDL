package spinal.core.internals

import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Native zero construction is local construction evidence only. The closed
  * bridge graph still proves literal reset values, register targets, clocking
  * and replay. No zero algorithm or reduction is implemented here.
  */
private[internals] object TypedBalancedReductionBridgeZero {
  private def fail(detail: String): Nothing = throw new IllegalArgumentException(
    "MORPH-REDUCE-BALANCED-BRIDGE-ZERO-AUTHORITY: " + detail)

  private def same[A <: AnyRef](a: Vector[A], b: Vector[A]): Boolean =
    a.size == b.size && a.zip(b).forall { case (x, y) => x eq y }

  private def declarations(owner: Component): Vector[BaseType] = {
    val values = ArrayBuffer.empty[BaseType]
    owner.dslBody.walkStatements {
      case leaf: BaseType => values += leaf
      case _ =>
    }
    values.toVector
  }

  private def children(data: Data): Vector[Data] = data match {
    case vector: Vec[_] => vector.vec.toVector.map(_.asInstanceOf[Data])
    case multi: MultiData => multi.elements.toVector.map(_._2)
    case _: BaseType => Vector.empty
    case _ => fail("unsupported native container")
  }

  private final class Geometry(val data: Data, owner: Component) {
    val nativeClass = data.getClass
    val nativeChildren = children(data)
    val childGeometry = nativeChildren.map(new Geometry(_, owner))
    val shape = data match {
      case vector: Vec[_] => ParameterizedVec.shapeOf(vector)
      case _ => None
    }
    val leaf = data match {
      case value: BaseType => Some(value)
      case _ => None
    }
    val kind = leaf.map(_.getTypeObject.asInstanceOf[AnyRef])
    val width = leaf.map(_.getBitsWidth)
    val expression = leaf.flatMap(ParameterizedWidth.expressionOf)
    val scope = leaf.map(_.parentScope)
    val direction = leaf.map(value => (value.isInput, value.isOutput, value.isInOut, value.isAnalog))

    shape.foreach { retained =>
      retained.geometryExpressions.foreach(value => ElaborationWidthAuthority.requireAuthoritative(
        value, "native bridge zero geometry", "MORPH-REDUCE-BALANCED-BRIDGE-ZERO-AUTHORITY"))
      val vector = data.asInstanceOf[Vec[_]]
      if (vector.vec.size != retained.carrierCapacity || vector.vec.isEmpty ||
          vector.vec.exists(element => !ParameterizedVecElementLayout.equivalent(
            ParameterizedVecElementLayout.capture(element.asInstanceOf[Data]).root, retained.elementLayout.root)))
        fail("native Vec no longer has its retained recursive layout and capacity")
    }
    expression.foreach(value => ElaborationWidthAuthority.requireAuthoritative(
      value, "native bridge zero leaf width", "MORPH-REDUCE-BALANCED-BRIDGE-ZERO-AUTHORITY"))
    if (data.component ne owner) fail("native Data belongs to a foreign component")

    def unchanged(): Unit = {
      val currentShape = data match {
        case vector: Vec[_] => ParameterizedVec.shapeOf(vector)
        case _ => None
      }
      if (data.getClass != nativeClass || (data.component ne owner) ||
          !same(children(data), nativeChildren) || currentShape.size != shape.size ||
          currentShape.zip(shape).exists { case (a, b) => a ne b })
        fail("native clone changed its exact owner, children or retained shape identity")
      leaf.foreach { value =>
        val current = ParameterizedWidth.expressionOf(value)
        if ((value.getTypeObject.asInstanceOf[AnyRef] ne kind.get) ||
            value.getBitsWidth != width.get || (value.parentScope ne scope.get) ||
            current.size != expression.size ||
            current.zip(expression).exists { case (a, b) => a ne b } ||
            (value.isInput, value.isOutput, value.isInOut, value.isAnalog) != direction.get)
          fail("native clone changed its exact leaf kind, width authority or scope")
      }
      childGeometry.foreach(_.unchanged())
    }
  }

  private final class Clone(source: Vec[_], target: Vec[_], owner: Component) {
    val sourceRef = new WeakReference[Vec[_]](source)
    val targetRef = new WeakReference[Vec[_]](target)
    val sourceGeometry = new Geometry(source, owner)
    val targetGeometry = new Geometry(target, owner)
    def validate(target: Vec[_]): Vec[_] = {
      val source = sourceRef.get()
      if (source == null || (targetRef.get() ne target)) fail("native clone identity expired or was copied")
      sourceGeometry.unchanged()
      targetGeometry.unchanged()
      source
    }
  }

  def withConstruction[A](operand: Data, owner: Component,
                         existing: Vector[BaseType])(body: => A): A = {
    if (operand == null || owner == null || (Component.current ne owner))
      fail("zero scope requires the exact bridge operand and active owner")
    TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(operand))
    val roots = new IdentityHashMap[Vec[_], Geometry]()
    def retainRoots(data: Data): Unit = {
      data match {
        case vector: Vec[_] if ParameterizedVec.shapeOf(vector).nonEmpty =>
          roots.put(vector, new Geometry(vector, owner))
        case _ =>
      }
      children(data).foreach(retainRoots)
    }
    retainRoots(operand)
    if (roots.isEmpty) return body
    val before = new IdentityHashMap[BaseType, java.lang.Boolean]()
    existing.foreach(value => before.put(value, java.lang.Boolean.TRUE))
    val clones = new IdentityHashMap[Vec[_], Clone]()
    val used = new IdentityHashMap[Vec[_], java.lang.Boolean]()

    def authenticate(vector: Vec[_]): Unit = {
      val visited = new IdentityHashMap[Vec[_], java.lang.Boolean]()
      var current = vector
      while (!roots.containsKey(current)) {
        if (visited.put(current, java.lang.Boolean.TRUE) != null || visited.size() > 64)
          fail("native clone ancestry is cyclic or exceeds its bound")
        val record = clones.get(current)
        if (record == null) fail("zero receiver has no exact native clone ancestry from the bridge operand")
        current = record.validate(current)
      }
      roots.get(current).unchanged()
    }

    val backend = new NativeVecZeroConstruction.Backend {
      override def cloned(source: Data, result: Data): Unit = {
        if (Component.current ne owner) fail("native clone escaped the bridge owner")
        TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(source, result))
        val sourceLeaves = source.flatten.toVector
        val targetLeaves = result.flatten.toVector
        if (targetLeaves.isEmpty || targetLeaves.distinct.size != targetLeaves.size ||
            targetLeaves.exists(value => before.containsKey(value) || sourceLeaves.exists(_ eq value) ||
              (value.component ne owner) || !value.isDirectionLess || value.isReg || value.isAnalog || value.head != null))
          fail("native clone result must contain fresh unused unaliased declarations")
        def record(from: Data, to: Data): Unit = {
          if (from.getClass != to.getClass || children(from).size != children(to).size)
            fail("native clone changed recursive container kinds or arity")
          children(from).zip(children(to)).foreach { case (a, b) => record(a, b) }
          (from, to) match {
            case (a: Vec[_], b: Vec[_]) if ParameterizedVec.shapeOf(a).nonEmpty =>
              if (clones.containsKey(b)) {
                if (clones.get(b).validate(b) ne a) fail("one native clone acquired different source ancestry")
              } else clones.put(b, new Clone(a, b, owner))
            case _ =>
          }
        }
        record(source, result)
      }

      override def zero[T <: Data](receiver: Vec[T], native: () => Vec[T]): Vec[T] = {
        if (Component.current ne owner) fail("zero request escaped the exact bridge owner")
        TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(receiver))
        val leaves = (receiver: Data).flatten.toVector
        if (leaves.isEmpty || leaves.distinct.size != leaves.size ||
            leaves.exists(value => before.containsKey(value) || (value.component ne owner)))
          fail("zero construction requires a fresh exact bridge receiver")
        if (used.put(receiver, java.lang.Boolean.TRUE) != null)
          fail("one bridge invocation reused its zero receiver")
        authenticate(receiver)
        val geometry = new Geometry(receiver, owner)
        val statements = leaves.map(value => {
          val result = ArrayBuffer.empty[(AssignmentStatement, Expression, Expression, BaseType)]
          value.foreachStatements(statement => result +=
            ((statement, statement.source, statement.target, statement.finalTarget)))
          result.toVector
        })
        val priorDeclarations = declarations(owner)
        val result = native()
        if (result == null || (result eq receiver)) fail("native zero did not return a fresh result")
        geometry.unchanged()
        leaves.zip(statements).foreach { case (value, expected) =>
          val current = ArrayBuffer.empty[AssignmentStatement]
          value.foreachStatements(current += _)
          if (!same(current.toVector, expected.map(_._1)) || expected.exists {
              case (statement, source, target, finalTarget) =>
                (statement.source ne source) || (statement.target ne target) || (statement.finalTarget ne finalTarget)
            }) fail("native zero changed an existing receiver assignment")
        }
        val resultLeaves = (result: Data).flatten.toVector
        if (resultLeaves.size != leaves.size || resultLeaves.distinct.size != resultLeaves.size ||
            resultLeaves.exists(value => priorDeclarations.exists(_ eq value) ||
              (value.component ne owner) || value.isReg || !value.isDirectionLess || value.isAnalog))
          fail("native zero result reused declarations or changed its leaf inventory")
        val record = clones.get(result)
        if (record == null || (record.validate(result) ne receiver))
          fail("native zero did not retain its exact native clone result")
        authenticate(receiver)
        result
      }
    }
    NativeVecZeroConstruction.withBackend(backend)(body)
  }
}
