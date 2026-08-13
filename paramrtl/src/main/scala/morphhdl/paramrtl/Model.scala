package morphhdl.paramrtl

sealed trait IntExpr extends Product with Serializable

object IntExpr {
  final case class Literal(value: BigInt) extends IntExpr
  final case class ParameterRef(name: String) extends IntExpr
  final case class LocalParameterRef(name: String) extends IntExpr
  final case class GenerateIndexRef(name: String) extends IntExpr
  final case class Negate(value: IntExpr) extends IntExpr
  final case class Add(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Subtract(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Multiply(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Divide(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Modulo(left: IntExpr, right: IntExpr) extends IntExpr
  final case class Select(condition: BoolExpr, whenTrue: IntExpr, whenFalse: IntExpr) extends IntExpr
}

sealed trait BoolExpr extends Product with Serializable

object BoolExpr {
  final case class Literal(value: Boolean) extends BoolExpr
  final case class ParameterRef(name: String) extends BoolExpr
  final case class LocalParameterRef(name: String) extends BoolExpr
  final case class LessThan(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class LessThanOrEqual(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class GreaterThan(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class GreaterThanOrEqual(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class Equal(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class NotEqual(left: IntExpr, right: IntExpr) extends BoolExpr
  final case class Not(value: BoolExpr) extends BoolExpr
  final case class And(left: BoolExpr, right: BoolExpr) extends BoolExpr
  final case class Or(left: BoolExpr, right: BoolExpr) extends BoolExpr
}

sealed trait IntConstraint extends Product with Serializable

object IntConstraint {
  final case class MinInclusive(value: BigInt) extends IntConstraint
  final case class MaxInclusive(value: BigInt) extends IntConstraint
}

final case class IntegerParameter(
    name: String,
    default: BigInt,
    constraints: Vector[IntConstraint] = Vector.empty
)

final case class BooleanParameter(name: String, default: Boolean)

sealed trait LocalParameterDeclaration extends Product with Serializable {
  def name: String
}

final case class IntegerLocalParameter(name: String, value: IntExpr)
    extends LocalParameterDeclaration

final case class BooleanLocalParameter(name: String, value: BoolExpr)
    extends LocalParameterDeclaration

sealed trait Signedness extends Product with Serializable

object Signedness {
  case object Unsigned extends Signedness
  case object Signed extends Signedness
}

final case class PackedBits(width: IntExpr, signedness: Signedness)

sealed trait PortDirection extends Product with Serializable

object PortDirection {
  case object Input extends PortDirection
  case object Output extends PortDirection
}

final case class Port(name: String, direction: PortDirection, dataType: PackedBits)

final case class ParameterBinding(parameterName: String, value: IntExpr)

final case class BooleanParameterBinding(parameterName: String, value: BoolExpr)

final case class PortConnection(portName: String, actual: RtlExpr)

sealed trait RtlExpr extends Product with Serializable

object RtlExpr {
  final case class Ref(name: String) extends RtlExpr
  final case class IndexedPartSelect(base: Ref, offset: IntExpr, width: IntExpr) extends RtlExpr
}

sealed trait ModuleItem extends Product with Serializable

final case class GenerateBlock(label: String, body: Vector[ModuleItem])

final case class GenerateCaseChoice(value: BigInt, block: GenerateBlock)

final case class ProceduralAssign(target: RtlExpr.Ref, value: RtlExpr.Ref)

object ModuleItem {
  final case class ContinuousAssign(target: RtlExpr.Ref, value: RtlExpr) extends ModuleItem
  final case class ModuleInstance(
      name: String,
      moduleName: String,
      parameterBindings: Vector[ParameterBinding] = Vector.empty,
      portConnections: Vector[PortConnection] = Vector.empty,
      booleanParameterBindings: Vector[BooleanParameterBinding] = Vector.empty
  ) extends ModuleItem
  final case class GenerateFor(
      label: String,
      indexName: String,
      count: IntExpr,
      body: Vector[ModuleItem]
  ) extends ModuleItem
  final case class GenerateIf(
      condition: BoolExpr,
      whenTrue: GenerateBlock,
      whenFalse: GenerateBlock
  ) extends ModuleItem
  final case class GenerateCase(
      selector: IntExpr,
      choices: Vector[GenerateCaseChoice],
      default: GenerateBlock
  ) extends ModuleItem
  final case class CombinationalIf(
      label: String,
      condition: RtlExpr.Ref,
      whenTrue: Vector[ProceduralAssign],
      whenFalse: Vector[ProceduralAssign]
  ) extends ModuleItem
  final case class SynchronousRegister(
      label: String,
      clock: RtlExpr.Ref,
      reset: RtlExpr.Ref,
      assignment: ProceduralAssign
  ) extends ModuleItem
  final case class AsynchronousRegister(
      label: String,
      clock: RtlExpr.Ref,
      reset: RtlExpr.Ref,
      assignment: ProceduralAssign
  ) extends ModuleItem
  final case class SynchronousEnabledRegister(
      label: String,
      clock: RtlExpr.Ref,
      reset: RtlExpr.Ref,
      enable: RtlExpr.Ref,
      assignment: ProceduralAssign
  ) extends ModuleItem
  final case class AsynchronousEnabledRegister(
      label: String,
      clock: RtlExpr.Ref,
      reset: RtlExpr.Ref,
      enable: RtlExpr.Ref,
      assignment: ProceduralAssign
  ) extends ModuleItem
}

final case class ModuleDef(
    name: String,
    parameters: Vector[IntegerParameter],
    ports: Vector[Port],
    items: Vector[ModuleItem],
    localParameters: Vector[IntegerLocalParameter] = Vector.empty,
    booleanParameters: Vector[BooleanParameter] = Vector.empty,
    booleanLocalParameters: Vector[BooleanLocalParameter] = Vector.empty
)

final case class Design(top: String, modules: Vector[ModuleDef])
