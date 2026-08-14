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
  ): IntExpr =
    substituteInteger(
      expression,
      parameters,
      localParameters,
      new java.util.IdentityHashMap[IntExpr, IntExpr](),
      new java.util.IdentityHashMap[BoolExpr, BoolExpr]()
    )

  private def substituteInteger(
      expression: IntExpr,
      parameters: Map[String, IntExpr],
      localParameters: Map[String, IntExpr],
      integerMemo: java.util.IdentityHashMap[IntExpr, IntExpr],
      booleanMemo: java.util.IdentityHashMap[BoolExpr, BoolExpr]
  ): IntExpr = {
    substituteGraph(expression, parameters, localParameters, integerMemo, booleanMemo)
    integerMemo.get(expression)
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
  private def sameStructure(left: IntExpr, right: IntExpr): Boolean =
    sameMixedStructure(left, right)

  private def sameMixedStructure(left: AnyRef, right: AnyRef): Boolean = {
    val stack = scala.collection.mutable.ArrayBuffer((left, right))
    val visited = new java.util.IdentityHashMap[
      AnyRef,
      java.util.IdentityHashMap[AnyRef, java.lang.Boolean]
    ]()
    def alreadyVisited(a: AnyRef, b: AnyRef): Boolean = {
      var rights = visited.get(a)
      if (rights == null) {
        rights = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
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
      if (!(a eq b) && !alreadyVisited(a, b)) {
        (a, b) match {
          case (Literal(x), Literal(y)) if x == y                     =>
          case (ParameterRef(x), ParameterRef(y)) if x == y           =>
          case (LocalParameterRef(x), LocalParameterRef(y)) if x == y =>
          case (GenerateIndexRef(x), GenerateIndexRef(y)) if x == y   =>
          case (AddressWidth(x), AddressWidth(y))                     => stack += ((x, y))
          case (Negate(x), Negate(y))                                 => stack += ((x, y))
          case (Add(al, ar), Add(bl, br))                             => stack += ((al, bl)); stack += ((ar, br))
          case (Subtract(al, ar), Subtract(bl, br))                   => stack += ((al, bl)); stack += ((ar, br))
          case (Multiply(al, ar), Multiply(bl, br))                   => stack += ((al, bl)); stack += ((ar, br))
          case (Divide(al, ar), Divide(bl, br))                       => stack += ((al, bl)); stack += ((ar, br))
          case (Modulo(al, ar), Modulo(bl, br))                       => stack += ((al, bl)); stack += ((ar, br))
          case (Select(ac, at, af), Select(bc, bt, bf)) =>
            stack += ((ac, bc)); stack += ((at, bt)); stack += ((af, bf))
          case (BoolExpr.Literal(x), BoolExpr.Literal(y)) if x == y =>
          case (BoolExpr.ParameterRef(x), BoolExpr.ParameterRef(y)) if x == y =>
          case (BoolExpr.LocalParameterRef(x), BoolExpr.LocalParameterRef(y)) if x == y =>
          case (BoolExpr.LessThan(al, ar), BoolExpr.LessThan(bl, br)) =>
            stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.LessThanOrEqual(al, ar), BoolExpr.LessThanOrEqual(bl, br)) =>
            stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.GreaterThan(al, ar), BoolExpr.GreaterThan(bl, br)) =>
            stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.GreaterThanOrEqual(al, ar), BoolExpr.GreaterThanOrEqual(bl, br)) =>
            stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.Equal(al, ar), BoolExpr.Equal(bl, br)) =>
            stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.NotEqual(al, ar), BoolExpr.NotEqual(bl, br)) =>
            stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.Not(av), BoolExpr.Not(bv)) => stack += ((av, bv))
          case (BoolExpr.And(al, ar), BoolExpr.And(bl, br)) => stack += ((al, bl)); stack += ((ar, br))
          case (BoolExpr.Or(al, ar), BoolExpr.Or(bl, br)) => stack += ((al, bl)); stack += ((ar, br))
          case _                                                      => return false
        }
      }
    }
    true
  }

  private def boundedNodeCount(expression: IntExpr): Option[Int] =
    boundedMixedNodeCount(expression, MaximumNormalizedNodes)

  private def boundedMixedNodeCount(expression: AnyRef, maximum: Int): Option[Int] = {
    val stack = scala.collection.mutable.ArrayBuffer[AnyRef](expression)
    var count = 0
    while (stack.nonEmpty && count <= maximum) {
      val value = stack.remove(stack.length - 1)
      count += 1
      value match {
        case Literal(_) | ParameterRef(_) | LocalParameterRef(_) | GenerateIndexRef(_) =>
        case AddressWidth(operand)                                                       => stack += operand
        case Negate(operand)                                                           => stack += operand
        case Add(left, right)                                                          => stack += left; stack += right
        case Subtract(left, right)                                                     => stack += left; stack += right
        case Multiply(left, right)                                                     => stack += left; stack += right
        case Divide(left, right)                                                       => stack += left; stack += right
        case Modulo(left, right)                                                       => stack += left; stack += right
        case Select(condition, whenTrue, whenFalse) =>
          stack += whenTrue
          stack += whenFalse
          stack += condition
        case BoolExpr.Literal(_) | BoolExpr.ParameterRef(_) | BoolExpr.LocalParameterRef(_) =>
        case BoolExpr.Not(operand)       => stack += operand
        case BoolExpr.And(left, right)   => stack += left; stack += right
        case BoolExpr.Or(left, right)    => stack += left; stack += right
        case BoolExpr.LessThan(left, right)           => stack += left; stack += right
        case BoolExpr.LessThanOrEqual(left, right)    => stack += left; stack += right
        case BoolExpr.GreaterThan(left, right)        => stack += left; stack += right
        case BoolExpr.GreaterThanOrEqual(left, right) => stack += left; stack += right
        case BoolExpr.Equal(left, right)              => stack += left; stack += right
        case BoolExpr.NotEqual(left, right)           => stack += left; stack += right
      }
    }
    if (count <= maximum) Some(count) else None
  }

  private def normalize(expression: IntExpr): IntExpr = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    val integers = new java.util.IdentityHashMap[IntExpr, IntExpr]()
    val booleans = new java.util.IdentityHashMap[BoolExpr, BoolExpr]()
    val work = scala.collection.mutable.ArrayBuffer(Frame(expression, expanded = false))

    def isDone(value: AnyRef): Boolean = value match {
      case integer: IntExpr => integers.containsKey(integer)
      case boolean: BoolExpr => booleans.containsKey(boolean)
      case _ => true
    }
    def push(value: AnyRef): Unit = work += Frame(value, expanded = false)
    def integer(value: IntExpr): IntExpr = integers.get(value)
    def boolean(value: BoolExpr): BoolExpr = booleans.get(value)

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!isDone(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case _: Literal | _: ParameterRef | _: LocalParameterRef | _: GenerateIndexRef =>
            case AddressWidth(operand) => push(operand)
            case Negate(operand)       => push(operand)
            case Add(left, right)      => push(right); push(left)
            case Subtract(left, right) => push(right); push(left)
            case Multiply(left, right) => push(right); push(left)
            case Divide(left, right)   => push(right); push(left)
            case Modulo(left, right)   => push(right); push(left)
            case Select(condition, whenTrue, whenFalse) =>
              push(whenFalse)
              push(whenTrue)
              push(condition)
            case _: BoolExpr.Literal | _: BoolExpr.ParameterRef | _: BoolExpr.LocalParameterRef =>
            case BoolExpr.Not(operand)       => push(operand)
            case BoolExpr.And(left, right)   => push(right); push(left)
            case BoolExpr.Or(left, right)    => push(right); push(left)
            case BoolExpr.LessThan(left, right)           => push(right); push(left)
            case BoolExpr.LessThanOrEqual(left, right)    => push(right); push(left)
            case BoolExpr.GreaterThan(left, right)        => push(right); push(left)
            case BoolExpr.GreaterThanOrEqual(left, right) => push(right); push(left)
            case BoolExpr.Equal(left, right)              => push(right); push(left)
            case BoolExpr.NotEqual(left, right)           => push(right); push(left)
          }
        } else {
          frame.value match {
            case value: Literal          => integers.put(value, value)
            case value: ParameterRef     => integers.put(value, value)
            case value: LocalParameterRef => integers.put(value, value)
            case value: GenerateIndexRef => integers.put(value, value)
            case value @ AddressWidth(operand) =>
              val normalized = integer(operand) match {
                case Literal(number) if number >= 1 =>
                  Literal(if (number <= 2) BigInt(1) else BigInt((number - 1).bitLength))
                case other => AddressWidth(other)
              }
              integers.put(value, normalized)
            case value @ Negate(operand) =>
              val normalized = integer(operand) match {
                case Literal(number) => Literal(-number)
                case Negate(inner)   => inner
                case other           => Negate(other)
              }
              integers.put(value, normalized)
            case value @ Add(left, right) =>
              integers.put(value, normalizeCommutative(integer(left), integer(right), additive = true))
            case value @ Multiply(left, right) =>
              integers.put(value, normalizeCommutative(integer(left), integer(right), additive = false))
            case value @ Subtract(left, right) =>
              val a = integer(left)
              val b = integer(right)
              val normalized = (a, b) match {
                case (Literal(x), Literal(y))          => Literal(x - y)
                case (x, y) if sameStructure(x, y)     => Literal(0)
                case (x, Literal(number)) if number == 0 => x
                case _                                 => Subtract(a, b)
              }
              integers.put(value, normalized)
            case value @ Divide(left, right) =>
              val a = integer(left)
              val b = integer(right)
              val normalized = (a, b) match {
                case (Literal(x), Literal(y)) if y != 0 => Literal(x / y)
                case (x, Literal(number)) if number == 1 => x
                case _                                  => Divide(a, b)
              }
              integers.put(value, normalized)
            case value @ Modulo(left, right) =>
              val a = integer(left)
              val b = integer(right)
              val normalized = (a, b) match {
                case (Literal(x), Literal(y)) if y != 0 => Literal(x % y)
                case (_, Literal(y)) if y.abs == 1      => Literal(0)
                case _                                  => Modulo(a, b)
              }
              integers.put(value, normalized)
            case value @ Select(condition, whenTrue, whenFalse) =>
              val normalizedCondition = boolean(condition)
              val normalizedTrue = integer(whenTrue)
              val normalizedFalse = integer(whenFalse)
              val normalized = normalizedCondition match {
                case BoolExpr.Literal(true)  => normalizedTrue
                case BoolExpr.Literal(false) => normalizedFalse
                case _ if sameStructure(normalizedTrue, normalizedFalse) => normalizedTrue
                case _ => Select(normalizedCondition, normalizedTrue, normalizedFalse)
              }
              integers.put(value, normalized)
            case value: BoolExpr.Literal => booleans.put(value, value)
            case value: BoolExpr.ParameterRef => booleans.put(value, value)
            case value: BoolExpr.LocalParameterRef => booleans.put(value, value)
            case value @ BoolExpr.Not(operand) => booleans.put(value, BoolExpr.Not(boolean(operand)))
            case value @ BoolExpr.And(left, right) =>
              booleans.put(value, BoolExpr.And(boolean(left), boolean(right)))
            case value @ BoolExpr.Or(left, right) =>
              booleans.put(value, BoolExpr.Or(boolean(left), boolean(right)))
            case value @ BoolExpr.LessThan(left, right) =>
              booleans.put(value, BoolExpr.LessThan(integer(left), integer(right)))
            case value @ BoolExpr.LessThanOrEqual(left, right) =>
              booleans.put(value, BoolExpr.LessThanOrEqual(integer(left), integer(right)))
            case value @ BoolExpr.GreaterThan(left, right) =>
              booleans.put(value, BoolExpr.GreaterThan(integer(left), integer(right)))
            case value @ BoolExpr.GreaterThanOrEqual(left, right) =>
              booleans.put(value, BoolExpr.GreaterThanOrEqual(integer(left), integer(right)))
            case value @ BoolExpr.Equal(left, right) =>
              booleans.put(value, BoolExpr.Equal(integer(left), integer(right)))
            case value @ BoolExpr.NotEqual(left, right) =>
              booleans.put(value, BoolExpr.NotEqual(integer(left), integer(right)))
          }
        }
      }
    }
    integers.get(expression)
  }

  private def normalizeCommutative(left: IntExpr, right: IntExpr, additive: Boolean): IntExpr = {
    val operands = scala.collection.mutable.ArrayBuffer.empty[IntExpr]
    val work = scala.collection.mutable.ArrayBuffer(right, left)
    while (work.nonEmpty) {
      work.remove(work.length - 1) match {
        case Add(a, b) if additive       => work += b; work += a
        case Multiply(a, b) if !additive => work += b; work += a
        case other                       => operands += other
      }
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
    case AddressWidth(_)         => "11"
  }

  private def substituteBoolean(
      expression: BoolExpr,
      parameters: Map[String, IntExpr],
      localParameters: Map[String, IntExpr],
      integerMemo: java.util.IdentityHashMap[IntExpr, IntExpr],
      booleanMemo: java.util.IdentityHashMap[BoolExpr, BoolExpr]
  ): BoolExpr = {
    substituteGraph(expression, parameters, localParameters, integerMemo, booleanMemo)
    booleanMemo.get(expression)
  }

  private def substituteGraph(
      root: AnyRef,
      parameters: Map[String, IntExpr],
      localParameters: Map[String, IntExpr],
      integerMemo: java.util.IdentityHashMap[IntExpr, IntExpr],
      booleanMemo: java.util.IdentityHashMap[BoolExpr, BoolExpr]
  ): Unit = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    val work = scala.collection.mutable.ArrayBuffer(Frame(root, expanded = false))

    def isDone(value: AnyRef): Boolean = value match {
      case integer: IntExpr => integerMemo.containsKey(integer)
      case boolean: BoolExpr => booleanMemo.containsKey(boolean)
      case _ => true
    }
    def push(value: AnyRef): Unit = work += Frame(value, expanded = false)
    def integer(value: IntExpr): IntExpr = integerMemo.get(value)
    def boolean(value: BoolExpr): BoolExpr = booleanMemo.get(value)

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!isDone(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case _: Literal | _: ParameterRef | _: LocalParameterRef | _: GenerateIndexRef =>
            case AddressWidth(operand) => push(operand)
            case Negate(operand)       => push(operand)
            case Add(left, right)      => push(right); push(left)
            case Subtract(left, right) => push(right); push(left)
            case Multiply(left, right) => push(right); push(left)
            case Divide(left, right)   => push(right); push(left)
            case Modulo(left, right)   => push(right); push(left)
            case Select(condition, whenTrue, whenFalse) =>
              push(whenFalse)
              push(whenTrue)
              push(condition)
            case _: BoolExpr.Literal | _: BoolExpr.ParameterRef | _: BoolExpr.LocalParameterRef =>
            case BoolExpr.Not(operand)       => push(operand)
            case BoolExpr.And(left, right)   => push(right); push(left)
            case BoolExpr.Or(left, right)    => push(right); push(left)
            case BoolExpr.LessThan(left, right)           => push(right); push(left)
            case BoolExpr.LessThanOrEqual(left, right)    => push(right); push(left)
            case BoolExpr.GreaterThan(left, right)        => push(right); push(left)
            case BoolExpr.GreaterThanOrEqual(left, right) => push(right); push(left)
            case BoolExpr.Equal(left, right)              => push(right); push(left)
            case BoolExpr.NotEqual(left, right)           => push(right); push(left)
          }
        } else {
          frame.value match {
            case value: Literal          => integerMemo.put(value, value)
            case value @ ParameterRef(name) => integerMemo.put(value, parameters.getOrElse(name, value))
            case value @ LocalParameterRef(name) => integerMemo.put(value, localParameters.getOrElse(name, value))
            case value: GenerateIndexRef => integerMemo.put(value, value)
            case value @ AddressWidth(operand) => integerMemo.put(value, AddressWidth(integer(operand)))
            case value @ Negate(operand)       => integerMemo.put(value, Negate(integer(operand)))
            case value @ Add(left, right)      => integerMemo.put(value, Add(integer(left), integer(right)))
            case value @ Subtract(left, right) => integerMemo.put(value, Subtract(integer(left), integer(right)))
            case value @ Multiply(left, right) => integerMemo.put(value, Multiply(integer(left), integer(right)))
            case value @ Divide(left, right)   => integerMemo.put(value, Divide(integer(left), integer(right)))
            case value @ Modulo(left, right)   => integerMemo.put(value, Modulo(integer(left), integer(right)))
            case value @ Select(condition, whenTrue, whenFalse) =>
              integerMemo.put(value, Select(boolean(condition), integer(whenTrue), integer(whenFalse)))
            case value: BoolExpr.Literal => booleanMemo.put(value, value)
            case value: BoolExpr.ParameterRef => booleanMemo.put(value, value)
            case value: BoolExpr.LocalParameterRef => booleanMemo.put(value, value)
            case value @ BoolExpr.Not(operand) => booleanMemo.put(value, BoolExpr.Not(boolean(operand)))
            case value @ BoolExpr.And(left, right) =>
              booleanMemo.put(value, BoolExpr.And(boolean(left), boolean(right)))
            case value @ BoolExpr.Or(left, right) =>
              booleanMemo.put(value, BoolExpr.Or(boolean(left), boolean(right)))
            case value @ BoolExpr.LessThan(left, right) =>
              booleanMemo.put(value, BoolExpr.LessThan(integer(left), integer(right)))
            case value @ BoolExpr.LessThanOrEqual(left, right) =>
              booleanMemo.put(value, BoolExpr.LessThanOrEqual(integer(left), integer(right)))
            case value @ BoolExpr.GreaterThan(left, right) =>
              booleanMemo.put(value, BoolExpr.GreaterThan(integer(left), integer(right)))
            case value @ BoolExpr.GreaterThanOrEqual(left, right) =>
              booleanMemo.put(value, BoolExpr.GreaterThanOrEqual(integer(left), integer(right)))
            case value @ BoolExpr.Equal(left, right) =>
              booleanMemo.put(value, BoolExpr.Equal(integer(left), integer(right)))
            case value @ BoolExpr.NotEqual(left, right) =>
              booleanMemo.put(value, BoolExpr.NotEqual(integer(left), integer(right)))
          }
        }
      }
    }
  }

  private def sameBooleanStructure(left: BoolExpr, right: BoolExpr): Boolean =
    sameMixedStructure(left, right)

  private def boundedBooleanNodeCount(expression: BoolExpr, maximum: Int): Option[Int] =
    boundedMixedNodeCount(expression, maximum)

}
