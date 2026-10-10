package spinal.core

import spinal.core.internals._

/** Immutable module-local unsigned constant. `elab` creates dependencies on
  * this exact declaration; `asUInt` is a hardware value, never a public generic.
  */
final class TypedLocalUInt private[core] (private[core] val owner: Component,
    private[core] val value: ElabInt, val bitWidth: Int, val name: String) {
  private[core] def checkOwner(component: Component): Unit =
    if (component ne owner)
      ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-OWNER-MISMATCH",
        s"local '$name' belongs to another Component", None)

  def elab: ElabInt = { checkOwner(Component.current); value }
  def asUInt: UInt = {
    checkOwner(Component.current)
    val result = UInt(BitCount(bitWidth))
    result.assignFrom(new TypedLocalUInt.Reference(this))
    result
  }
  private[core] def reference(width: Int): TypedLocalUInt.Reference = {
    checkOwner(Component.current)
    if (width != bitWidth)
      ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-CONSUMER-WIDTH-MISMATCH",
        s"local '$name' is $bitWidth bits but its switch selector is $width bits", None)
    new TypedLocalUInt.Reference(this)
  }
}

object TypedLocalUInt {
  def apply(name: String, value: BigInt, width: BitCount): TypedLocalUInt =
    apply(name, ElabInt.fromBigInt(value), width)

  def apply(name: String, value: ElabInt, width: BitCount): TypedLocalUInt = {
    val owner = Component.current
    if (owner == null || (DslScopeStack.get ne owner.dslBody) || ElaborationDomainContext.hasActiveRestrictions || value == null ||
        name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || width == null || width.value < 1 || width.value > 4096)
      ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-DECLARATION-INVALID",
        "a typed local needs a module-level owner, portable name, typed value and positive bounded width", None)
    val expression = ElaborationPublicationValue.projected(value, "typed local unsigned value",
      "SPINAL-LOCALPARAM-AUTHORITY-MISSING", requireProjectedExactExtrema = true)
    if (expression.generateIndex.nonEmpty || expression.minimum < 0 || expression.maximum >= (BigInt(1) << width.value))
      ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-VALUE-OUT-OF-RANGE",
        s"local '$name' does not fit its unsigned ${width.value}-bit declaration", expression.sourceLocation)
    NativeLocalParameters.bindTyped(owner, value, name, width.value)
    new TypedLocalUInt(owner, value, width.value, name)
  }

  /** Validate exclusivity before the native switch advertises parallel case
    * semantics. Equal default values or printed names are never a proof.
    */
  private[core] def validateSwitch(statement: SwitchStatement): Unit = {
    val keys = statement.elements.zipWithIndex.flatMap { case (element, arm) => element.keys.map(_ -> arm) }
    if (!keys.exists(_._1.isInstanceOf[Reference])) return
    def value(expression: Expression): Option[ElabInt] = expression match {
      case local: Reference =>
        local.declaration.checkOwner(statement.parentScope.component)
        Some(local.declaration.value)
      case literal: BitVectorLiteral if !literal.hasPoison() =>
        if (literal.getValue().isValidInt) Some(ElabInt.fromBigInt(literal.getValue())) else None
      case _ => ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-SWITCH-KEY-UNSUPPORTED",
        "named unsigned case keys may mix only with exact unsigned literals", None)
    }
    val values = keys.map { case (expression, arm) => (expression, arm, value(expression)) }
    for (i <- values.indices; j <- 0 until i if values(i)._2 != values(j)._2 &&
        (values(i)._1.isInstanceOf[Reference] || values(j)._1.isInstanceOf[Reference])) {
      val disjoint = (values(i)._3, values(j)._3) match {
        case (Some(left), Some(right)) => left.elabEq(right).isAlwaysFalse
        case _ => true // An out-of-Int literal cannot equal an Int-bounded typed local.
      }
      if (!disjoint) ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-SWITCH-OVERLAP",
        "named case arms overlap in the admitted parameter domain", None)
    }
  }

  /** This is deliberately not a Literal: optimizers must not fold a symbolic
    * local's default witness. Fresh copies keep the immutable declaration token.
    */
  private[core] final class Reference(val declaration: TypedLocalUInt) extends Expression with WidthProvider {
    override def opName: String = "typed local unsigned constant"
    override def getTypeObject: Any = TypeUInt
    override def getWidth: Int = declaration.bitWidth
    override def foreachExpression(func: Expression => Unit): Unit = {}
    override def remapExpressions(func: Expression => Expression): Unit = {}
    def fresh: Reference = new Reference(declaration)
    def render(owner: Component): String = {
      declaration.checkOwner(owner)
      NativeLocalParameters.reference(owner, declaration.value.expression).getOrElse(
        ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-PUBLICATION-MISSING",
          s"local '${declaration.name}' was not prepared by native parameter publication", None))
    }
  }
}
