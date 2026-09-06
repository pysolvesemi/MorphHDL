package spinal.core

/** Recursive logical geometry beside the finite native carrier. Products
  * deliberately remain factorized: independent dimension roots are never
  * coerced into the single-root ElabInt arithmetic contract.
  */
private[spinal] object ParameterizedVecElementLayout {
  sealed trait Size {
    def render(expression: ElaborationIntegerExpression => String): String
    def evaluate(expression: ElaborationIntegerExpression => BigInt): BigInt
    def expressions: Vector[ElaborationIntegerExpression]
  }
  final case class Constant(value: BigInt) extends Size {
    def render(expression: ElaborationIntegerExpression => String): String = value.toString
    def evaluate(expression: ElaborationIntegerExpression => BigInt): BigInt = value
    def expressions = Vector.empty[ElaborationIntegerExpression]
  }
  final case class Value(value: ElaborationIntegerExpression) extends Size {
    def render(expression: ElaborationIntegerExpression => String): String = expression(value)
    def evaluate(expression: ElaborationIntegerExpression => BigInt): BigInt = expression(value)
    def expressions = Vector(value)
  }
  final case class Sum(values: Vector[Size]) extends Size {
    def render(expression: ElaborationIntegerExpression => String): String =
      if (values.isEmpty) "0" else if (values.size == 1) values.head.render(expression)
      else values.map(value => s"(${value.render(expression)})").mkString(" + ")
    def evaluate(expression: ElaborationIntegerExpression => BigInt): BigInt =
      values.map(_.evaluate(expression)).sum
    def expressions = values.flatMap(_.expressions)
  }
  final case class Product(left: Size, right: Size) extends Size {
    def render(expression: ElaborationIntegerExpression => String): String =
      s"(${left.render(expression)}) * (${right.render(expression)})"
    def evaluate(expression: ElaborationIntegerExpression => BigInt): BigInt =
      left.evaluate(expression) * right.evaluate(expression)
    def expressions = left.expressions ++ right.expressions
  }

  sealed trait Node {
    def size: Size
    def hasVectors: Boolean
    def schema: String
  }
  final case class Scalar(kind: AnyRef, width: ElaborationIntegerExpression) extends Node {
    def size: Size = Value(width)
    def hasVectors = false
    def schema = s"leaf:${kind.getClass.getName}:${width.verilog}"
  }
  final case class Fields(kind: Class[_], values: Vector[(String, Node)]) extends Node {
    def size: Size = Sum(values.map(_._2.size))
    def hasVectors = values.exists(_._2.hasVectors)
    def schema = s"${kind.getName}{${values.map { case (name, node) => name + ":" + node.schema }.mkString("|")}}"
  }
  final case class Dimension(depth: ElaborationIntegerExpression, capacity: Int, element: Node) extends Node {
    def size: Size = Product(Value(depth), element.size)
    def hasVectors = true
    def schema = s"vec:${depth.verilog}:$capacity[${element.schema}]"
  }
  final case class Leaf(ordinal: Int, width: ElaborationIntegerExpression,
      position: Size, dimensions: Vector[(Int, ElaborationIntegerExpression)]) {
    def offset(render: ElaborationIntegerExpression => String): String = position.render(render)
    def activeCondition(render: ElaborationIntegerExpression => String): String = {
      val varying = dimensions.filter { case (index, depth) => BigInt(index) >= depth.minimum }
      if (varying.isEmpty) "1" else varying.map { case (index, depth) => s"($index < (${render(depth)}))" }.mkString(" && ")
    }
  }
  final case class Layout(root: Node) {
    def width(render: ElaborationIntegerExpression => String): String = root.size.render(render)
    def evaluate(expression: ElaborationIntegerExpression => BigInt): BigInt = root.size.evaluate(expression)
    def expressions: Vector[ElaborationIntegerExpression] = root.size.expressions
    def hasNestedVectors: Boolean = root.hasVectors
    def schema: String = root.schema
    def schemaUsing(expression: ElaborationIntegerExpression => String): String = {
      def visit(node: Node): String = node match {
        case Scalar(kind, width) => s"leaf:${kind.getClass.getName}:${expression(width)}"
        case Fields(kind, values) =>
          s"${kind.getName}{${values.map { case (name, child) => name + ":" + visit(child) }.mkString("|")}}"
        case Dimension(depth, capacity, element) => s"vec:${expression(depth)}:$capacity[${visit(element)}]"
      }
      visit(root)
    }
    lazy val leaves: Vector[Leaf] = {
      val out = Vector.newBuilder[Leaf]
      var ordinal = 0
      def visit(node: Node, offset: Size, dimensions: Vector[(Int, ElaborationIntegerExpression)]): Unit = node match {
        case Scalar(_, width) =>
          out += Leaf(ordinal, width, offset, dimensions)
          ordinal += 1
        case Fields(_, values) =>
          values.indices.foreach(index => visit(values(index)._2,
            Sum(Vector(offset) ++ values.take(index).map(_._2.size)), dimensions))
        case Dimension(depth, capacity, element) =>
          (0 until capacity).foreach(index => visit(element,
            Sum(Vector(offset, Product(Constant(index), element.size))), dimensions :+ (index -> depth)))
      }
      visit(root, Constant(0), Vector.empty)
      out.result()
    }
  }

  def equivalent(left: Node, right: Node): Boolean =
    equivalentWith(left, right)(ElabInt.equivalentExactFunction)

  def equivalentWith(left: Node, right: Node)(expression: (ElaborationIntegerExpression, ElaborationIntegerExpression) => Boolean): Boolean = (left, right) match {
    case (Scalar(lk, lw), Scalar(rk, rw)) =>
      (lk eq rk) && expression(lw, rw)
    case (Fields(lk, ls), Fields(rk, rs)) =>
      lk == rk && ls.size == rs.size && ls.zip(rs).forall { case ((ln, l), (rn, r)) => ln == rn && equivalentWith(l, r)(expression) }
    case (Dimension(ld, lc, le), Dimension(rd, rc, re)) =>
      lc == rc && expression(ld, rd) && equivalentWith(le, re)(expression)
    case _ => false
  }

  def capture(value: Data): Layout = {
    val active = new java.util.IdentityHashMap[Data, java.lang.Boolean]()
    var visited = 0
    def invalid(detail: String): Nothing =
      ParameterizedVerilogException.fail("SPINAL-ELAB-VEC-RECURSIVE-LAYOUT-INVALID", detail)
    def visit(value: Data): Node = {
      visited += 1
      if (value == null || active.size >= 128 || visited > 65536 || active.put(value, java.lang.Boolean.TRUE) != null)
        invalid("recursive Data layout is cyclic or exceeds its audited traversal budget")
      try value match {
      case leaf: BaseType => Scalar(leaf.getTypeObject.asInstanceOf[AnyRef],
        ParameterizedWidth.expressionOf(leaf).getOrElse(ElabInt.literal(leaf.getBitsWidth).expression))
      case vector: Vec[_] =>
        if (vector.vec.isEmpty) invalid("empty nested Vec has no positive packed element")
        val shape = ParameterizedVec.shapeOf(vector)
        val depth = shape.map(_.depth).getOrElse(ElabInt.literal(vector.vec.size).expression)
        val element = visit(vector.vec.head.asInstanceOf[Data])
        vector.vec.tail.foreach { item =>
          if (!equivalent(element, visit(item.asInstanceOf[Data])))
            invalid("nested Vec elements do not retain one exact recursive type and dimension schema")
        }
        Dimension(depth, vector.vec.size, element)
      case multi: MultiData => Fields(value.getClass, multi.elements.toVector.map { case (name, child) => name -> visit(child) })
      case _ => invalid("unsupported non-scalar, non-MultiData element")
      } finally active.remove(value)
    }
    Layout(visit(value))
  }

  /** Identity traversal is also used to choose one outer packed owner. */
  def nestedVectors(value: Data): Vector[Vec[_]] = {
    val active = new java.util.IdentityHashMap[Data, java.lang.Boolean]()
    var visited = 0
    def visit(value: Data): Vector[Vec[_]] = {
      visited += 1
      if (value == null || active.size >= 128 || visited > 65536 || active.put(value, java.lang.Boolean.TRUE) != null)
        ParameterizedVerilogException.fail("SPINAL-ELAB-VEC-RECURSIVE-LAYOUT-INVALID",
          "recursive Data ownership is cyclic or exceeds its audited traversal budget")
      try value match {
        case vector: Vec[_] => Vector(vector) ++ vector.vec.toVector.flatMap(item => visit(item.asInstanceOf[Data]))
        case multi: MultiData => multi.elements.toVector.flatMap(item => visit(item._2))
        case _ => Vector.empty
      } finally active.remove(value)
    }
    visit(value)
  }
}
