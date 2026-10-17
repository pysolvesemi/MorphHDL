package spinal.core

/** Lightweight declarations for bounded HDL parameters.
  *
  * Each call declares a formal in the current component. An ElabInt/ElabBool
  * argument is the instance actual, not a request to reuse the parent's formal.
  * Scala values select defaults; they never replace the declared legal domain.
  * Use an explicit ElabInt/ElabBool annotation for natural Scala control flow.
  */
object RtlParam {
  /** Recursive configuration edges may change formals, never constructor specialization. */
  def requireSameSpecialization(previous: Any, next: Any, path: String): Unit = {
    val identical=(previous.asInstanceOf[AnyRef] eq next.asInstanceOf[AnyRef]) ||
      (previous != null && next != null && previous.getClass==next.getClass && (previous match {
        case _: Int | _: Long | _: Short | _: Byte | _: Char | _: Boolean | _: String => previous==next
        case _=>false
      }))
    require(identical,"SPINAL-RECURSIVE-CONFIG-SPECIALIZATION-MISMATCH: recursive edge changes " + path)
  }
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

  def inferredInt(name:String, actual:Int, minimum:BigInt, maximum:BigInt):ElabInt =
    inferredInt(name,ElabInt.literal(actual),minimum,maximum)

  def inferredInt(name:String, actual:ElabInt, minimum:BigInt, maximum:BigInt):ElabInt = {
    require(minimum>=0 && minimum<=maximum && maximum<=Int.MaxValue,
      "MORPHDL-PARAM-DOMAIN-UNPROVEN: authored constraints must establish a finite nonnegative Int domain")
    int(name,actual,minimum,maximum)
  }

  def packed(name: String, literal: BitVector, minimum: BigInt,
      maximum: BigInt, kind: String): ElabInt =
    packed(name, Param(literal), minimum, maximum, kind)

  def packed(name: String, actual: ElabInt, minimum: BigInt,
      maximum: BigInt, kind: String): ElabInt = {
    require(actual != null, "SPINAL-PARAM-LITERAL-REQUIRED: packed actual is null")
    val representation = actual.packedRepresentation.getOrElse(
      throw new IllegalArgumentException("SPINAL-PARAM-REPRESENTATION-MISSING: forward a typed packed actual"))
    require(representation.kind == kind,
      "SPINAL-PARAM-REPRESENTATION-MISMATCH: packed formal and actual types differ")
    val low = minimum.max(representation.minimum)
    val high = maximum.min(representation.maximum)
    require(low <= high && high - low + 1 <= ElaborationExactDomain.MaximumDomainSize,
      "MORPHDL-PARAM-DOMAIN-UNPROVEN: packed representation needs a finite supported semantic domain; add require constraints")
    val formal = ElabFormalParameter.declare(actual, name, low, high, Some(representation))
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

  private[core] def forwardOptional(formal:ElabInt):Option[ElabInt] = {
    val declaration = Option(Component.current).flatMap(_.userCache.get(DeclarationKey))
      .flatMap(_.asInstanceOf[Declarations].find(_.formal eq formal)).getOrElse(
        throw new IllegalArgumentException("SPINAL-OPTIONAL-PARAMETER-OWNER-MISMATCH: forward the exact optional formal in its declaring component"))
    if(declaration.active)Some(formal) else None
  }

  private def optionalActual(actual:Option[ElabInt]):ElabInt = {
    require(actual != null && !actual.contains(null), "optional parameter actual must be None or Some(non-null)")
    actual.orNull
  }
  def optional(name:String, actual:Option[ElabInt], minimum:BigInt, maximum:BigInt, absent:Int):ElabInt =
    optional(name,optionalActual(actual),minimum,maximum,absent)
  def optional(name:String, actual:ElabInt, minimum:BigInt, maximum:BigInt, absent:Int):ElabInt = {
    // ElabFormalParameter validates active Int bounds; absence retains the
    // existing literal fallback contract. Neither path needs enumeration.
    require(minimum<=maximum,
      "MORPHDL-PARAM-DOMAIN-UNPROVEN: optional parameter needs an ordered finite legal domain")
    optionalInt(name,actual,minimum,maximum,absent)
  }
  def recursiveOptionalBinding(formal:ElabInt, actual:Option[ElabInt]):(ElabInt,ElabInt) =
    recursiveOptionalBinding(formal,optionalActual(actual))
  def recursiveOptionalBinding(formal:ElabInt, actual:ElabInt):(ElabInt,ElabInt) = {
    require(Component.current != null && authenticates(Component.current,formal,actual),
      "recursive optional declaration identity is missing")
    formal -> actual
  }
  def recursiveOptionalOverride(formal:ElabInt, actual:Option[ElabInt]):(ElabInt,ElabInt) = {
    val value = optionalActual(actual)
    val declaration = Option(Component.current).flatMap(_.userCache.get(DeclarationKey))
      .flatMap(_.asInstanceOf[Declarations].find(_.formal eq formal)).getOrElse(
        throw new IllegalArgumentException("recursive optional declaration identity is missing"))
    require(actual.isDefined == declaration.active,
      "SPINAL-OPTIONAL-PARAMETER-PRESENCE-MISMATCH: recursive children must preserve optional parameter presence")
    formal -> value
  }
  def recursiveOptionalOverride(formal:ElabInt, actual:ElabInt):(ElabInt,ElabInt) = formal -> actual

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
