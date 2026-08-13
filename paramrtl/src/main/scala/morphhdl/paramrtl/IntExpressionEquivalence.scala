package morphhdl.paramrtl

import morphhdl.paramrtl.IntExpr._

/** Conservative, fail-closed symbolic equality used for structural type compatibility. */
private[morphhdl] object IntExpressionEquivalence {
  private val MaximumNormalizedNodes = 2048

  /** Replacements are opaque expressions already expanded in their source scope. */
  def substitute(
      expression: IntExpr,
      parameters: Map[String, IntExpr],
      localParameters: Map[String, IntExpr]
  ): IntExpr = expression match {
    case value: Literal          => value
    case ParameterRef(name)      => parameters.getOrElse(name, expression)
    case LocalParameterRef(name) => localParameters.getOrElse(name, expression)
    case value: GenerateIndexRef => value
    case Negate(value)           => Negate(substitute(value, parameters, localParameters))
    case Add(left, right) =>
      Add(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case Subtract(left, right) =>
      Subtract(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case Multiply(left, right) =>
      Multiply(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case Divide(left, right) =>
      Divide(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case Modulo(left, right) =>
      Modulo(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case Select(condition, whenTrue, whenFalse) =>
      Select(
        substituteBoolean(condition, parameters, localParameters),
        substitute(whenTrue, parameters, localParameters),
        substitute(whenFalse, parameters, localParameters)
      )
  }

  def equivalent(left: IntExpr, right: IntExpr): Boolean = {
    if (sameStructure(left, right)) true
    else
      {
        for {
          leftSize <- boundedNodeCount(left)
          rightSize <- boundedNodeCount(right)
          if leftSize + rightSize <= MaximumNormalizedNodes
        } yield sameStructure(normalize(left), normalize(right))
      }.getOrElse(false)
  }

  /** Iterative equality avoids case-class recursion on deep shared expression DAGs. */
  private def sameStructure(left: IntExpr, right: IntExpr): Boolean = {
    val stack = scala.collection.mutable.ArrayBuffer((left, right))
    val visited = new java.util.IdentityHashMap[
      IntExpr,
      java.util.IdentityHashMap[IntExpr, java.lang.Boolean]
    ]()
    def alreadyVisited(a: IntExpr, b: IntExpr): Boolean = {
      var rights = visited.get(a)
      if (rights == null) {
        rights = new java.util.IdentityHashMap[IntExpr, java.lang.Boolean]()
        visited.put(a, rights)
      }
      if (rights.containsKey(b)) true
      else {
        rights.put(b, java.lang.Boolean.TRUE)
        false
      }
    }
    while (stack.nonEmpty) {
      val (a, b) = stack.remove(stack.length - 1)
      if (!(a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]) && !alreadyVisited(a, b)) {
        (a, b) match {
          case (Literal(x), Literal(y)) if x == y                     =>
          case (ParameterRef(x), ParameterRef(y)) if x == y           =>
          case (LocalParameterRef(x), LocalParameterRef(y)) if x == y =>
          case (GenerateIndexRef(x), GenerateIndexRef(y)) if x == y   =>
          case (Negate(x), Negate(y))                                 => stack += ((x, y))
          case (Add(al, ar), Add(bl, br))                             => stack += ((al, bl)); stack += ((ar, br))
          case (Subtract(al, ar), Subtract(bl, br))                   => stack += ((al, bl)); stack += ((ar, br))
          case (Multiply(al, ar), Multiply(bl, br))                   => stack += ((al, bl)); stack += ((ar, br))
          case (Divide(al, ar), Divide(bl, br))                       => stack += ((al, bl)); stack += ((ar, br))
          case (Modulo(al, ar), Modulo(bl, br))                       => stack += ((al, bl)); stack += ((ar, br))
          case (Select(ac, at, af), Select(bc, bt, bf)) if sameBooleanStructure(ac, bc) =>
            stack += ((at, bt)); stack += ((af, bf))
          case _                                                      => return false
        }
      }
    }
    true
  }

  private def boundedNodeCount(expression: IntExpr): Option[Int] = {
    val stack = scala.collection.mutable.ArrayBuffer(expression)
    var count = 0
    while (stack.nonEmpty && count <= MaximumNormalizedNodes) {
      val value = stack.remove(stack.length - 1)
      count += 1
      value match {
        case Literal(_) | ParameterRef(_) | LocalParameterRef(_) | GenerateIndexRef(_) =>
        case Negate(operand)                                                           => stack += operand
        case Add(left, right)                                                          => stack += left; stack += right
        case Subtract(left, right)                                                     => stack += left; stack += right
        case Multiply(left, right)                                                     => stack += left; stack += right
        case Divide(left, right)                                                       => stack += left; stack += right
        case Modulo(left, right)                                                       => stack += left; stack += right
        case Select(condition, whenTrue, whenFalse) =>
          stack += whenTrue
          stack += whenFalse
          count += boundedBooleanNodeCount(condition, MaximumNormalizedNodes - count).getOrElse {
            return None
          }
      }
    }
    if (count <= MaximumNormalizedNodes) Some(count) else None
  }

  private def normalize(expression: IntExpr): IntExpr = expression match {
    case value @ (Literal(_) | ParameterRef(_) | LocalParameterRef(_) | GenerateIndexRef(_)) => value
    case Negate(value) =>
      normalize(value) match {
        case Literal(number) => Literal(-number)
        case Negate(inner)   => inner
        case other           => Negate(other)
      }
    case Add(left, right)      => normalizeCommutative(Add(left, right), additive = true)
    case Multiply(left, right) => normalizeCommutative(Multiply(left, right), additive = false)
    case Subtract(left, right) =>
      val a = normalize(left)
      val b = normalize(right)
      (a, b) match {
        case (Literal(x), Literal(y))          => Literal(x - y)
        case (x, y) if sameStructure(x, y)     => Literal(0)
        case (x, Literal(value)) if value == 0 => x
        case _                                 => Subtract(a, b)
      }
    case Divide(left, right) =>
      val a = normalize(left)
      val b = normalize(right)
      (a, b) match {
        case (Literal(x), Literal(y)) if y != 0 => Literal(x / y)
        case (x, Literal(value)) if value == 1  => x
        case _                                  => Divide(a, b)
      }
    case Modulo(left, right) =>
      val a = normalize(left)
      val b = normalize(right)
      (a, b) match {
        case (Literal(x), Literal(y)) if y != 0 => Literal(x % y)
        case (_, Literal(y)) if y.abs == 1      => Literal(0)
        case _                                  => Modulo(a, b)
      }
    case Select(condition, whenTrue, whenFalse) =>
      val normalizedCondition = normalizeBoolean(condition)
      val normalizedTrue = normalize(whenTrue)
      val normalizedFalse = normalize(whenFalse)
      normalizedCondition match {
        case BoolExpr.Literal(true)  => normalizedTrue
        case BoolExpr.Literal(false) => normalizedFalse
        case _ if sameStructure(normalizedTrue, normalizedFalse) => normalizedTrue
        case _ => Select(normalizedCondition, normalizedTrue, normalizedFalse)
      }
  }

  private def normalizeCommutative(expression: IntExpr, additive: Boolean): IntExpr = {
    val operands = scala.collection.mutable.ArrayBuffer.empty[IntExpr]
    def collect(value: IntExpr): Unit = normalize(value) match {
      case Add(left, right) if additive       => collect(left); collect(right)
      case Multiply(left, right) if !additive => collect(left); collect(right)
      case other                              => operands += other
    }
    expression match {
      case Add(left, right)      => collect(left); collect(right)
      case Multiply(left, right) => collect(left); collect(right)
      case _                     =>
    }
    val literals = operands.collect { case Literal(value) => value }
    val nonLiterals = operands.collect { case value if !value.isInstanceOf[Literal] => value }.toVector
    if (!additive && literals.contains(BigInt(0))) Literal(0)
    else {
      val combined = if (additive) literals.sum else literals.product
      val include = if (additive) combined != 0 || nonLiterals.isEmpty else combined != 1 || nonLiterals.isEmpty
      val values = nonLiterals.sortBy(shallowKey) ++ (if (include) Vector(Literal(combined)) else Vector.empty)
      values
        .reduceLeftOption[IntExpr] { (left, right) =>
          if (additive) Add(left, right) else Multiply(left, right)
        }
        .getOrElse(Literal(if (additive) 0 else 1))
    }
  }

  private def shallowKey(expression: IntExpr): String = expression match {
    case Literal(value)          => s"0:$value"
    case ParameterRef(name)      => s"1:$name"
    case LocalParameterRef(name) => s"2:$name"
    case GenerateIndexRef(name)  => s"3:$name"
    case Negate(_)               => "4"
    case Add(_, _)               => "5"
    case Subtract(_, _)          => "6"
    case Multiply(_, _)          => "7"
    case Divide(_, _)            => "8"
    case Modulo(_, _)            => "9"
    case Select(_, _, _)         => "10"
  }

  private def substituteBoolean(
      expression: BoolExpr,
      parameters: Map[String, IntExpr],
      localParameters: Map[String, IntExpr]
  ): BoolExpr = expression match {
    case value @ (BoolExpr.Literal(_) | BoolExpr.ParameterRef(_)) => value
    case BoolExpr.LessThan(left, right) =>
      BoolExpr.LessThan(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case BoolExpr.LessThanOrEqual(left, right) =>
      BoolExpr.LessThanOrEqual(
        substitute(left, parameters, localParameters),
        substitute(right, parameters, localParameters)
      )
    case BoolExpr.GreaterThan(left, right) =>
      BoolExpr.GreaterThan(
        substitute(left, parameters, localParameters),
        substitute(right, parameters, localParameters)
      )
    case BoolExpr.GreaterThanOrEqual(left, right) =>
      BoolExpr.GreaterThanOrEqual(
        substitute(left, parameters, localParameters),
        substitute(right, parameters, localParameters)
      )
    case BoolExpr.Equal(left, right) =>
      BoolExpr.Equal(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case BoolExpr.NotEqual(left, right) =>
      BoolExpr.NotEqual(substitute(left, parameters, localParameters), substitute(right, parameters, localParameters))
    case BoolExpr.Not(value) => BoolExpr.Not(substituteBoolean(value, parameters, localParameters))
    case BoolExpr.And(left, right) =>
      BoolExpr.And(
        substituteBoolean(left, parameters, localParameters),
        substituteBoolean(right, parameters, localParameters)
      )
    case BoolExpr.Or(left, right) =>
      BoolExpr.Or(
        substituteBoolean(left, parameters, localParameters),
        substituteBoolean(right, parameters, localParameters)
      )
  }

  private def sameBooleanStructure(left: BoolExpr, right: BoolExpr): Boolean = {
    val stack = scala.collection.mutable.ArrayBuffer((left, right))
    while (stack.nonEmpty) {
      val (a, b) = stack.remove(stack.length - 1)
      (a, b) match {
        case (BoolExpr.Literal(x), BoolExpr.Literal(y)) if x == y =>
        case (BoolExpr.ParameterRef(x), BoolExpr.ParameterRef(y)) if x == y =>
        case (BoolExpr.LessThan(al, ar), BoolExpr.LessThan(bl, br)) =>
          if (!sameStructure(al, bl) || !sameStructure(ar, br)) return false
        case (BoolExpr.LessThanOrEqual(al, ar), BoolExpr.LessThanOrEqual(bl, br)) =>
          if (!sameStructure(al, bl) || !sameStructure(ar, br)) return false
        case (BoolExpr.GreaterThan(al, ar), BoolExpr.GreaterThan(bl, br)) =>
          if (!sameStructure(al, bl) || !sameStructure(ar, br)) return false
        case (BoolExpr.GreaterThanOrEqual(al, ar), BoolExpr.GreaterThanOrEqual(bl, br)) =>
          if (!sameStructure(al, bl) || !sameStructure(ar, br)) return false
        case (BoolExpr.Equal(al, ar), BoolExpr.Equal(bl, br)) =>
          if (!sameStructure(al, bl) || !sameStructure(ar, br)) return false
        case (BoolExpr.NotEqual(al, ar), BoolExpr.NotEqual(bl, br)) =>
          if (!sameStructure(al, bl) || !sameStructure(ar, br)) return false
        case (BoolExpr.Not(av), BoolExpr.Not(bv)) => stack += ((av, bv))
        case (BoolExpr.And(al, ar), BoolExpr.And(bl, br)) => stack += ((al, bl)); stack += ((ar, br))
        case (BoolExpr.Or(al, ar), BoolExpr.Or(bl, br)) => stack += ((al, bl)); stack += ((ar, br))
        case _ => return false
      }
    }
    true
  }

  private def boundedBooleanNodeCount(expression: BoolExpr, maximum: Int): Option[Int] = {
    val booleanStack = scala.collection.mutable.ArrayBuffer(expression)
    val integerStack = scala.collection.mutable.ArrayBuffer.empty[IntExpr]
    var count = 0
    while ((booleanStack.nonEmpty || integerStack.nonEmpty) && count <= maximum) {
      if (booleanStack.nonEmpty) {
        count += 1
        booleanStack.remove(booleanStack.length - 1) match {
          case BoolExpr.Literal(_) | BoolExpr.ParameterRef(_) =>
          case BoolExpr.Not(value) => booleanStack += value
          case BoolExpr.And(left, right) => booleanStack += left; booleanStack += right
          case BoolExpr.Or(left, right) => booleanStack += left; booleanStack += right
          case BoolExpr.LessThan(left, right) => integerStack += left; integerStack += right
          case BoolExpr.LessThanOrEqual(left, right) => integerStack += left; integerStack += right
          case BoolExpr.GreaterThan(left, right) => integerStack += left; integerStack += right
          case BoolExpr.GreaterThanOrEqual(left, right) => integerStack += left; integerStack += right
          case BoolExpr.Equal(left, right) => integerStack += left; integerStack += right
          case BoolExpr.NotEqual(left, right) => integerStack += left; integerStack += right
        }
      } else {
        boundedNodeCount(integerStack.remove(integerStack.length - 1)) match {
          case Some(value) => count += value
          case None        => return None
        }
      }
    }
    if (count <= maximum) Some(count) else None
  }

  private def normalizeBoolean(expression: BoolExpr): BoolExpr = expression match {
    case value @ (BoolExpr.Literal(_) | BoolExpr.ParameterRef(_)) => value
    case BoolExpr.LessThan(left, right) => BoolExpr.LessThan(normalize(left), normalize(right))
    case BoolExpr.LessThanOrEqual(left, right) => BoolExpr.LessThanOrEqual(normalize(left), normalize(right))
    case BoolExpr.GreaterThan(left, right) => BoolExpr.GreaterThan(normalize(left), normalize(right))
    case BoolExpr.GreaterThanOrEqual(left, right) => BoolExpr.GreaterThanOrEqual(normalize(left), normalize(right))
    case BoolExpr.Equal(left, right) => BoolExpr.Equal(normalize(left), normalize(right))
    case BoolExpr.NotEqual(left, right) => BoolExpr.NotEqual(normalize(left), normalize(right))
    case BoolExpr.Not(value) => BoolExpr.Not(normalizeBoolean(value))
    case BoolExpr.And(left, right) => BoolExpr.And(normalizeBoolean(left), normalizeBoolean(right))
    case BoolExpr.Or(left, right) => BoolExpr.Or(normalizeBoolean(left), normalizeBoolean(right))
  }
}
