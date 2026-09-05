package morphhdl.analysis

/** Target-neutral descriptions, not permission to change an emitted expression.
  * Identity-bearing use evidence is owned by the native graph analysis. In
  * particular a copied Fact cannot authorize a declaration or a removed cast.
  */
object SignednessFacts {
  sealed trait Kind
  case object SignedScalar extends Kind
  case object UnsignedScalar extends Kind
  case object UnsignedAggregate extends Kind
  case object BooleanValue extends Kind
  case object Unknown extends Kind

  sealed trait Width
  final case class Fixed(bits: BigInt) extends Width
  /** Session-local key for an exact retained elaboration expression, not its
    * witness, printed spelling or parameter name.
    */
  final case class Retained(key: Int) extends Width
  final case class Sum(parts: Vector[Width]) extends Width
  final case class Product(parts: Vector[Width]) extends Width
  final case class Maximum(parts: Vector[Width]) extends Width
  final case class Minimum(parts: Vector[Width]) extends Width
  final case class Difference(left: Width, right: Width) extends Width
  case object UnknownWidth extends Width

  sealed trait Rule
  case object Reference extends Rule
  case object Literal extends Rule
  case object Unary extends Rule
  case object Arithmetic extends Rule
  case object Comparison extends Rule
  case object Logical extends Rule
  case object Shift extends Rule
  case object Mux extends Rule
  case object Cast extends Rule
  case object Resize extends Rule
  case object Concatenation extends Rule
  case object Replication extends Rule
  case object Selection extends Rule
  case object MemoryRead extends Rule
  case object MemoryElement extends Rule
  case object Aggregate extends Rule
  case object Unsupported extends Rule

  sealed trait Requirement
  case object TargetDeclarationMode extends Requirement
  case object TargetLiteralEncoding extends Requirement
  case object OperandSizing extends Requirement
  case object ExplicitConversion extends Requirement
  case object ResizeBoundary extends Requirement
  case object PackedTransport extends Requirement
  case object SelectedBits extends Requirement
  case object MemoryTransport extends Requirement
  case object HierarchyBoundary extends Requirement
  case object InferredWidthAuthority extends Requirement
  case object UnknownSemantics extends Requirement

  final case class Fact(
      id: Int,
      intent: Kind,
      value: Kind,
      nativeBits: Int,
      width: Width,
      rule: Rule,
      operands: Vector[Int],
      requirements: Vector[Requirement]
  )

  /** Conservative value transfer. All width/context obligations remain in the
    * Fact; even SignedScalar is NOT an assertion about today's Verilog printer.
    * Selectors and shift amounts are deliberately not value alternatives.
    */
  def transfer(rule: Rule, intent: Kind, operands: Vector[Kind]): Kind = {
    def sameValues: Kind = {
      if (operands.isEmpty || operands.contains(Unknown)) Unknown
      else if (operands.forall(_ == SignedScalar)) SignedScalar
      else if (operands.forall(_ == BooleanValue)) BooleanValue
      else if (operands.forall(k => k == UnsignedScalar || k == UnsignedAggregate)) UnsignedScalar
      else Unknown
    }
    rule match {
      case Reference | Literal | MemoryElement => intent
      case Unary | Shift => operands.headOption.getOrElse(Unknown)
      case Arithmetic =>
        val joined = sameValues
        if ((intent == SignedScalar || intent == UnsignedScalar) && joined == intent) joined else Unknown
      case Mux =>
        val joined = sameValues
        if (joined == intent) joined else Unknown
      case Comparison | Logical => BooleanValue
      case Cast => if (operands.size == 1 && operands.head != Unknown) intent else Unknown
      case Concatenation | Replication | Aggregate => UnsignedAggregate
      case Selection => UnsignedScalar
      case MemoryRead => UnsignedScalar
      case Resize | Unsupported => Unknown
    }
  }

  def requirements(rule: Rule): Vector[Requirement] = rule match {
    case Reference | MemoryElement => Vector(TargetDeclarationMode)
    case Literal => Vector(TargetLiteralEncoding)
    case Unary | Arithmetic | Comparison | Logical | Shift | Mux => Vector(OperandSizing)
    case Cast => Vector(ExplicitConversion, OperandSizing)
    case Resize => Vector(ResizeBoundary, OperandSizing)
    case Concatenation | Replication | Aggregate => Vector(PackedTransport)
    case Selection => Vector(SelectedBits)
    case MemoryRead => Vector(MemoryTransport)
    case Unsupported => Vector(UnknownSemantics)
  }
}
