package morphhdl.paramrtl

sealed trait IntExpr extends Product with Serializable

object IntExpr {
  final case class Literal(value: BigInt) extends IntExpr
  final case class ParameterRef(name: String) extends IntExpr
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

sealed trait RtlExpr extends Product with Serializable

object RtlExpr {
  final case class Ref(name: String) extends RtlExpr
}

sealed trait ModuleItem extends Product with Serializable

object ModuleItem {
  final case class ContinuousAssign(target: RtlExpr.Ref, value: RtlExpr) extends ModuleItem
}

final case class ModuleDef(
    name: String,
    parameters: Vector[IntegerParameter],
    ports: Vector[Port],
    items: Vector[ModuleItem]
)

final case class Design(top: String, modules: Vector[ModuleDef])
