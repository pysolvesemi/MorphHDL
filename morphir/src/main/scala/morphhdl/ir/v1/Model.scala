package morphhdl.ir.v1

/** Version of the immutable MorphHDL-owned canonical IR schema. */
final case class IrVersion(major: Int, minor: Int)

object IrVersion {
  val V1: IrVersion = IrVersion(1, 0)
}

sealed trait IrStage extends Product with Serializable {
  def label: String
}

object IrStage {
  /** Parameter intent has been resolved and captured; target emission has not run. */
  case object PostParameterizationPreEmission extends IrStage {
    override val label: String = "post-parameterization-pre-emission"
  }
}

/** Stable public schema markers for consumers that negotiate the v1 handoff. */
object CanonicalIrSchema {
  val schemaVersion: IrVersion = IrVersion.V1
  val stage: IrStage = IrStage.PostParameterizationPreEmission
}

/** Exact source position retained without rendering it into a diagnostic string. */
final case class SourceLocation(path: String, line: Int, column: Int)

/** Complete bounded integer domain admitted for one public parameter. */
final case class IntegerParameterDomain(
    minimum: BigInt,
    maximum: BigInt,
    admittedValues: Vector[BigInt]
)

/** Complete bounded Boolean domain admitted for one public parameter. */
final case class BooleanParameterDomain(admittedValues: Vector[Boolean])

sealed trait Parameter extends Product with Serializable {
  def id: ParameterId
  def name: String
  def sourceLocation: Option[SourceLocation]
}

final case class IntegerParameter(
    id: ParameterId,
    name: String,
    default: BigInt,
    domain: IntegerParameterDomain,
    sourceLocation: Option[SourceLocation] = None
) extends Parameter

final case class BooleanParameter(
    id: ParameterId,
    name: String,
    default: Boolean,
    domain: BooleanParameterDomain,
    sourceLocation: Option[SourceLocation] = None
) extends Parameter

/** Target-neutral elaboration-time integer algebra. */
sealed trait IntExpr extends Product with Serializable

object IntExpr {
  final case class Literal(value: BigInt) extends IntExpr
  final case class ParameterRef(parameter: ParameterId) extends IntExpr
  final case class GenerateIndexRef(index: GenerateIndexId) extends IntExpr
  final case class Negate(value: IntExpr) extends IntExpr
  final case class Add(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Subtract(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Multiply(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Divide(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Modulo(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Min(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Max(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Select(condition: BoolExpr, whenTrue: IntExpr, whenFalse: IntExpr)
      extends IntExpr
  final case class CeilLog2(value: IntExpr) extends IntExpr
  final case class AddressWidth(value: IntExpr) extends IntExpr
  final case class Pow2(exponent: IntExpr) extends IntExpr
}

/** Target-neutral elaboration-time Boolean algebra. */
sealed trait BoolExpr extends Product with Serializable

object BoolExpr {
  final case class Literal(value: Boolean) extends BoolExpr
  final case class ParameterRef(parameter: ParameterId) extends BoolExpr
  final case class LessThan(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class LessThanOrEqual(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class GreaterThan(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class GreaterThanOrEqual(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class Equal(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class NotEqual(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class IsPow2(value: IntExpr) extends BoolExpr
  final case class Not(value: BoolExpr) extends BoolExpr
  final case class And(left: BoolExpr, right: BoolExpr) extends BoolExpr
  final case class Or(left: BoolExpr, right: BoolExpr) extends BoolExpr
}

sealed trait Signedness extends Product with Serializable

object Signedness {
  case object Unsigned extends Signedness
  case object Signed extends Signedness
}

/** Value interpretation is explicit and independent of target signedness rules. */
sealed trait PackedValueSemantics extends Product with Serializable

object PackedValueSemantics {
  case object BitVector extends PackedValueSemantics
  case object UnsignedInteger extends PackedValueSemantics
  case object SignedInteger extends PackedValueSemantics
  case object Boolean extends PackedValueSemantics
}

final case class PackedType(
    width: IntExpr,
    signedness: Signedness,
    valueSemantics: PackedValueSemantics
)

sealed trait ScopeKind extends Product with Serializable {
  def label: String
}

object ScopeKind {
  case object Module extends ScopeKind { override val label: String = "module" }
  case object Generate extends ScopeKind { override val label: String = "generate" }
  case object Process extends ScopeKind { override val label: String = "process" }
  case object Block extends ScopeKind { override val label: String = "block" }
}

final case class Scope(
    id: ScopeId,
    parent: Option[ScopeId],
    kind: ScopeKind,
    label: Option[String] = None,
    sourceLocation: Option[SourceLocation] = None
)

final case class GenerateIndex(
    id: GenerateIndexId,
    owner: ScopeId,
    name: String,
    minimum: BigInt,
    maximum: BigInt,
    sourceLocation: Option[SourceLocation] = None
)

sealed trait PortDirection extends Product with Serializable

object PortDirection {
  case object Input extends PortDirection
  case object Output extends PortDirection
  case object InOut extends PortDirection
}

sealed trait DeclarationKind extends Product with Serializable {
  def label: String
  def requiresPackedType: Boolean
}

object DeclarationKind {
  final case class Port(direction: PortDirection) extends DeclarationKind {
    override val label: String = "port"
    override val requiresPackedType: Boolean = true
  }

  case object InternalCombinational extends DeclarationKind {
    override val label: String = "internal-combinational"
    override val requiresPackedType: Boolean = true
  }

  case object Register extends DeclarationKind {
    override val label: String = "register"
    override val requiresPackedType: Boolean = true
  }

  case object Memory extends DeclarationKind {
    override val label: String = "memory"
    override val requiresPackedType: Boolean = true
  }

  case object Clock extends DeclarationKind {
    override val label: String = "clock"
    override val requiresPackedType: Boolean = true
  }

  case object Reset extends DeclarationKind {
    override val label: String = "reset"
    override val requiresPackedType: Boolean = true
  }

  case object InstanceBoundary extends DeclarationKind {
    override val label: String = "instance-boundary"
    override val requiresPackedType: Boolean = false
  }
}

sealed trait NameOrigin extends Product with Serializable {
  def explicitName: Option[String]
  def isKnown: Boolean
}

object NameOrigin {
  case object Unnamed extends NameOrigin {
    override val explicitName: Option[String] = None
    override val isKnown: Boolean = true
  }

  final case class Explicit(value: String) extends NameOrigin {
    override val explicitName: Option[String] = Some(value)
    override val isKnown: Boolean = true
  }

  final case class Reflected(value: String) extends NameOrigin {
    override val explicitName: Option[String] = Some(value)
    override val isKnown: Boolean = true
  }

  case object Generated extends NameOrigin {
    override val explicitName: Option[String] = None
    override val isKnown: Boolean = true
  }

  /** Explicit fail-closed marker for a capture path that lost naming provenance. */
  case object Unknown extends NameOrigin {
    override val explicitName: Option[String] = None
    override val isKnown: Boolean = false
  }
}

/** Complete declaration observability contract captured before target lowering. */
final case class Observability(
    complete: Boolean,
    externallyVisible: Boolean = false,
    keep: Boolean = false,
    dontTouch: Boolean = false,
    probe: Boolean = false,
    preserve: Boolean = false,
    publicExport: Boolean = false,
    blackBoxBoundary: Boolean = false,
    hierarchyBoundary: Boolean = false
) {
  def preventsElimination: Boolean =
    externallyVisible || keep || dontTouch || probe || preserve || publicExport ||
      blackBoxBoundary || hierarchyBoundary
}

object Observability {
  val Unobserved: Observability = Observability(complete = true)
}

sealed trait AttributeKind extends Product with Serializable {
  def label: String
}

object AttributeKind {
  case object Semantic extends AttributeKind { override val label: String = "semantic" }
  case object Backend extends AttributeKind { override val label: String = "backend" }
  case object CommentStyle extends AttributeKind { override val label: String = "comment" }
}

final case class IrAttribute(
    name: String,
    value: Option[String],
    kind: AttributeKind,
    sourceLocation: Option[SourceLocation] = None
)

final case class IrComment(
    text: String,
    sourceLocation: Option[SourceLocation] = None
)

final case class Declaration(
    id: SymbolId,
    owner: ScopeId,
    kind: DeclarationKind,
    packedType: Option[PackedType],
    nameOrigin: NameOrigin,
    sourceLocation: Option[SourceLocation],
    observability: Observability,
    attributes: Vector[IrAttribute] = Vector.empty,
    comments: Vector[IrComment] = Vector.empty
)

sealed trait RtlUnaryOperator extends Product with Serializable {
  def label: String
}

object RtlUnaryOperator {
  case object Negate extends RtlUnaryOperator { override val label: String = "negate" }
  case object LogicalNot extends RtlUnaryOperator { override val label: String = "logical-not" }
  case object BitwiseNot extends RtlUnaryOperator { override val label: String = "bitwise-not" }
}

sealed trait RtlBinaryOperator extends Product with Serializable {
  def label: String
}

object RtlBinaryOperator {
  case object Add extends RtlBinaryOperator { override val label: String = "add" }
  case object Subtract extends RtlBinaryOperator { override val label: String = "subtract" }
  case object Multiply extends RtlBinaryOperator { override val label: String = "multiply" }
  case object Divide extends RtlBinaryOperator { override val label: String = "divide" }
  case object Modulo extends RtlBinaryOperator { override val label: String = "modulo" }
  case object BitwiseAnd extends RtlBinaryOperator { override val label: String = "bitwise-and" }
  case object BitwiseOr extends RtlBinaryOperator { override val label: String = "bitwise-or" }
  case object BitwiseXor extends RtlBinaryOperator { override val label: String = "bitwise-xor" }
  case object LogicalAnd extends RtlBinaryOperator { override val label: String = "logical-and" }
  case object LogicalOr extends RtlBinaryOperator { override val label: String = "logical-or" }
  case object Equal extends RtlBinaryOperator { override val label: String = "equal" }
  case object NotEqual extends RtlBinaryOperator { override val label: String = "not-equal" }
  case object LessThan extends RtlBinaryOperator { override val label: String = "less-than" }
  case object LessThanOrEqual extends RtlBinaryOperator { override val label: String = "less-than-or-equal" }
  case object GreaterThan extends RtlBinaryOperator { override val label: String = "greater-than" }
  case object GreaterThanOrEqual extends RtlBinaryOperator { override val label: String = "greater-than-or-equal" }
  case object ShiftLeft extends RtlBinaryOperator { override val label: String = "shift-left" }
  case object ShiftRight extends RtlBinaryOperator { override val label: String = "shift-right" }
}

/** Runtime expression algebra. Every reference is an exact SymbolId. */
sealed trait RtlExpr extends Product with Serializable {
  final def referencedSymbols: Vector[SymbolId] = RtlExpr.referencedSymbols(this)
  final def referenceOccurrences: Vector[RtlExpr.Ref] = RtlExpr.referenceOccurrences(this)
  final def directReference: Option[SymbolId] = this match {
    case RtlExpr.Ref(_, target, _, _) => Some(target)
    case _                            => None
  }
}

object RtlExpr {
  final case class Ref(
      id: ReferenceId,
      target: SymbolId,
      owner: ScopeId,
      sourceLocation: Option[SourceLocation] = None
  ) extends RtlExpr
  final case class Literal(value: BigInt, width: Int, signed: Boolean = false) extends RtlExpr
  final case class Unary(operator: RtlUnaryOperator, value: RtlExpr) extends RtlExpr
  final case class Binary(operator: RtlBinaryOperator, left: RtlExpr, right: RtlExpr) extends RtlExpr
  final case class Mux(condition: RtlExpr, whenTrue: RtlExpr, whenFalse: RtlExpr) extends RtlExpr
  final case class Concat(values: Vector[RtlExpr]) extends RtlExpr
  final case class BitSelect(value: RtlExpr, index: RtlExpr) extends RtlExpr
  final case class PartSelect(value: RtlExpr, offset: IntExpr, width: IntExpr) extends RtlExpr
  final case class Resize(value: RtlExpr, width: IntExpr, signedness: Signedness) extends RtlExpr
  final case class Cast(value: RtlExpr, signedness: Signedness) extends RtlExpr

  private[v1] def referencedSymbols(expression: RtlExpr): Vector[SymbolId] =
    expression match {
      case Ref(_, target, _, _)        => Vector(target)
      case Literal(_, _, _)            => Vector.empty
      case Unary(_, value)             => referencedSymbols(value)
      case Binary(_, left, right)      => referencedSymbols(left) ++ referencedSymbols(right)
      case Mux(condition, yes, no)     => referencedSymbols(condition) ++ referencedSymbols(yes) ++ referencedSymbols(no)
      case Concat(values)              => values.flatMap(referencedSymbols)
      case BitSelect(value, index)     => referencedSymbols(value) ++ referencedSymbols(index)
      case PartSelect(value, _, _)     => referencedSymbols(value)
      case Resize(value, _, _)         => referencedSymbols(value)
      case Cast(value, _)              => referencedSymbols(value)
    }

  private[v1] def referenceOccurrences(expression: RtlExpr): Vector[Ref] =
    expression match {
      case value: Ref                      => Vector(value)
      case Literal(_, _, _)                => Vector.empty
      case Unary(_, value)                 => referenceOccurrences(value)
      case Binary(_, left, right)          => referenceOccurrences(left) ++ referenceOccurrences(right)
      case Mux(condition, yes, no)         => referenceOccurrences(condition) ++ referenceOccurrences(yes) ++ referenceOccurrences(no)
      case Concat(values)                  => values.flatMap(referenceOccurrences)
      case BitSelect(value, index)         => referenceOccurrences(value) ++ referenceOccurrences(index)
      case PartSelect(value, _, _)         => referenceOccurrences(value)
      case Resize(value, _, _)             => referenceOccurrences(value)
      case Cast(value, _)                  => referenceOccurrences(value)
    }
}

sealed trait DriverKind extends Product with Serializable {
  def label: String
}

object DriverKind {
  case object Continuous extends DriverKind { override val label: String = "continuous" }
  case object Procedural extends DriverKind { override val label: String = "procedural" }
  case object MemoryPort extends DriverKind { override val label: String = "memory-port" }
  case object InstancePort extends DriverKind { override val label: String = "instance-port" }
  case object Bidirectional extends DriverKind { override val label: String = "bidirectional" }
}

sealed trait DriverCoverage extends Product with Serializable {
  def label: String
}

object DriverCoverage {
  case object FullObject extends DriverCoverage { override val label: String = "full-object" }
  case object Partial extends DriverCoverage { override val label: String = "partial" }
  /** Explicit fail-closed marker when capture cannot prove coverage. */
  case object Unknown extends DriverCoverage { override val label: String = "unknown" }
}

final case class Driver(
    id: DriverId,
    owner: ScopeId,
    target: SymbolId,
    kind: DriverKind,
    coverage: DriverCoverage,
    value: RtlExpr,
    sourceLocation: Option[SourceLocation] = None,
    attributes: Vector[IrAttribute] = Vector.empty,
    comments: Vector[IrComment] = Vector.empty
) {
  def directReference: Option[SymbolId] = Option(value).flatMap(_.directReference)
}

final case class Module(
    id: ModuleId,
    logicalName: String,
    parameters: Vector[Parameter],
    scopes: Vector[Scope],
    generateIndices: Vector[GenerateIndex],
    declarations: Vector[Declaration],
    drivers: Vector[Driver],
    sourceLocation: Option[SourceLocation] = None
)

final case class Design(
    version: IrVersion,
    stage: IrStage,
    top: ModuleId,
    modules: Vector[Module]
) {
  def normalized: Design = CanonicalIrNormalizer.normalize(this)
}
