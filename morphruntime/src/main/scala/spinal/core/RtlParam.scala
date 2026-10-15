package spinal.core

/** Lightweight declarations for bounded HDL parameters.
  *
  * Each call declares a formal in the current component. An ElabInt/ElabBool
  * argument is the instance actual, not a request to reuse the parent's formal.
  * Scala values select defaults; they never replace the declared legal domain.
  * Use an explicit ElabInt/ElabBool annotation for natural Scala control flow.
  */
object RtlParam {
  private object BooleanDeclarationKey
  private case class BooleanDeclaration(formal: ElabBool, actual: ElabBool,
      integerFormal: ElabInt, integerActual: ElabInt)
  private type BooleanDeclarations = scala.collection.mutable.ArrayBuffer[BooleanDeclaration]

  /** Compiler-owned bridge: authenticate the Boolean declaration before exposing
    * its native integer slot. Equivalent expressions cannot borrow this identity.
    */
  def recursiveBooleanBinding(formal: ElabBool, actual: ElabBool): (ElabInt, ElabInt) = {
    val owner = Component.current
    require(owner != null, "recursive Boolean binding requires its declaring component")
    val entry = owner.userCache.get(BooleanDeclarationKey).toSeq
      .flatMap(_.asInstanceOf[BooleanDeclarations]).find(d => (d.formal eq formal) && (d.actual eq actual))
      .getOrElse(throw new IllegalArgumentException("recursive Boolean declaration identity is missing"))
    entry.integerFormal -> entry.integerActual
  }

  def recursiveBooleanOverride(formal: ElabBool, actual: ElabBool): (ElabInt, ElabInt) = {
    val owner = Component.current
    require(owner != null && actual != null, "recursive Boolean override requires a current owner and actual")
    val entry = owner.userCache.get(BooleanDeclarationKey).toSeq
      .flatMap(_.asInstanceOf[BooleanDeclarations]).find(_.formal eq formal)
      .getOrElse(throw new IllegalArgumentException("recursive Boolean declaration identity is missing"))
    entry.integerFormal -> actual.toElabInt
  }

  private object DeclarationKey
  private case class Declaration(formal: ElabInt, actual: ElabInt, active: Boolean)
  private type Declarations = scala.collection.mutable.ArrayBuffer[Declaration]
  private[core] def authenticates(owner: Component, formal: ElabInt, actual: ElabInt): Boolean =
    owner.userCache.get(DeclarationKey).exists(_.asInstanceOf[Declarations].exists {
      case Declaration(issued, supplied, _) => (issued eq formal) && (supplied eq actual)
    })

  def int(name: String, default: Int, minimum: BigInt, maximum: BigInt): ElabInt =
    int(name, ElabInt.literal(default), minimum, maximum)

  def int(name: String, actual: ElabInt, minimum: BigInt, maximum: BigInt): ElabInt = {
    val formal = ElabFormalParameter(actual, name, minimum, maximum)
    remember(formal, actual, active = true)
    formal
  }

  private def remember(formal: ElabInt, actual: ElabInt, active: Boolean): Unit = {
    val entries = Component.current.userCache.getOrElseUpdate(DeclarationKey,
      scala.collection.mutable.ArrayBuffer.empty[Declaration]).asInstanceOf[Declarations]
    entries += Declaration(formal, actual, active)
  }

  private[core] def active(owner: Component, formal: ElabInt, actual: ElabInt): Boolean = {
    require(authenticates(owner, formal, actual), "parameter declaration identity is missing")
    owner.userCache(DeclarationKey).asInstanceOf[Declarations].find(_.formal eq formal).get.active
  }

  /** A null actual omits the HDL formal and returns an authenticated constant.
    * Explicit zero is an active parameter, distinct from omission. Recursion
    * keeps the same optional-parameter presence as its enclosing definition.
    */
  def optionalInt(name: String, actual: ElabInt, minimum: BigInt, maximum: BigInt,
      absent: Int): ElabInt = {
    require(Component.current != null, "optional parameter requires a component")
    require(minimum <= maximum && BigInt(absent) >= minimum && BigInt(absent) <= maximum,
      "absent parameter value must belong to its declared domain")
    if (actual != null) int(name, actual, minimum, maximum)
    else {
      val value = ElabInt.literal(absent)
      remember(value, null, active = false)
      value
    }
  }

  /** Boolean parameters are represented by a bounded 0/1 HDL integer. */
  def bool(name: String, default: Boolean): ElabBool =
    bool(name, ElabBool.literal(default))

  def bool(name: String, actual: ElabBool): ElabBool = {
    require(actual != null, "Boolean parameter actual must not be null")
    val integerActual = actual.toElabInt
    val integerFormal = int(name, integerActual, 0, 1)
    val formal = integerFormal.elabEq(1)
    val entries = Component.current.userCache.getOrElseUpdate(BooleanDeclarationKey,
      scala.collection.mutable.ArrayBuffer.empty[BooleanDeclaration]).asInstanceOf[BooleanDeclarations]
    entries += BooleanDeclaration(formal, actual, integerFormal, integerActual)
    formal
  }
}
