package spinal.core

/**
  * Typed scalar-formal boundary for one ordinary native child Component.
  *
  * The constructor receives a fresh definition-side [[ElabInt]] root.  The
  * child instance retains the caller's exact actual expression through the
  * generic formal registry; no native-Int shadow capture participates.
  */
private[spinal] object ElabFormalComponent {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def parameter[C <: Component](
      actual: ElabInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(constructor: ElabInt => C): C = {
    if (actual == null)
      fail(
        "SPINAL-ELAB-FORMAL-ACTUAL-NULL",
        s"typed formal '$name' requires a non-null ElabInt actual",
        None
      )
    val expression = actual.projectedExpression("typed formal actual")
    val source = expression.sourceLocation.orElse(Some(s"<typed-formal:$name>"))
    val exact = expression.exactDomain.getOrElse {
      fail(
        "SPINAL-ELAB-FORMAL-ACTUAL-EXACT-DOMAIN-REQUIRED",
        s"typed formal '$name' actual '${expression.verilog}' must retain exact single-root evidence",
        source
      )
    }
    val roots = expression.completedParameterRoots.foldLeft(
      Vector.empty[ElaborationIntegerParameterRoot]
    ) { (known, root) =>
      if (known.exists(_ eq root)) known else known :+ root
    }
    val schemaMatches = expression.parameters match {
      case Vector(parameter) =>
        parameter == exact.parameter && parameter.name == exact.root.name
      case _ => false
    }
    if (
      roots.size != 1 || !(roots.head eq exact.root) || !schemaMatches
    )
      fail(
        "SPINAL-ELAB-FORMAL-ACTUAL-EXACT-DOMAIN-REQUIRED",
        s"typed formal '$name' actual '${expression.verilog}' must retain one exact declaration root and its matching parameter schema",
        source.orElse(exact.root.sourceLocation)
      )
    if (constructor == null)
      fail(
        "SPINAL-ELAB-FORMAL-CONSTRUCTOR-NULL",
        s"typed formal '$name' requires a non-null constructor",
        source
      )
    if (
      name == null || !PortableIdentifier.pattern.matcher(name).matches()
    )
      fail(
        "SPINAL-ELAB-FORMAL-NAME-INVALID",
        s"typed formal name '$name' is not a portable Verilog identifier",
        source
      )
    if (
      minimum < 1 || maximum < minimum ||
      maximum > BigInt(Int.MaxValue) ||
      expression.generateIndex.nonEmpty ||
      expression.minimum < minimum || expression.maximum > maximum ||
      expression.default < minimum || expression.default > maximum
    )
      fail(
        "SPINAL-ELAB-FORMAL-DOMAIN-INVALID",
        s"typed formal '$name' cannot admit actual '${expression.verilog}' default ${expression.default} in [${expression.minimum}, ${expression.maximum}] inside [$minimum, $maximum]",
        source
      )

    val parent = Option(Component.current).getOrElse {
      fail(
        "SPINAL-ELAB-FORMAL-PARENT-MISSING",
        s"typed formal '$name' must be constructed inside an active parent Component",
        source
      )
    }
    val formal = ElaborationIntegerParameter(
      name,
      expression.default,
      minimum,
      maximum
    )
    val definitionValue = ElabInt.directParameter(formal, source)
    val component = constructor(definitionValue)
    if (component == null)
      fail(
        "SPINAL-ELAB-FORMAL-COMPONENT-NULL",
        s"typed formal '$name' constructor returned null",
        source
      )
    if (component.parent ne parent) {
      val actualParent =
        Option(component.parent).map(_.getClass.getName).getOrElse("<none>")
      fail(
        "SPINAL-ELAB-FORMAL-PARENT-MISMATCH",
        s"typed formal '$name' child belongs to '$actualParent' instead of '${parent.getClass.getName}'",
        source
      )
    }
    val ownerClassName = component.getClass.getName
    val binding = ExternalFormalParameterBinding(
      formal = formal,
      actual = expression,
      declarationKey = s"typed-elab::$ownerClassName::$name",
      ownerClassName = ownerClassName,
      sourceLocation = source
    )
    ExternalFormalParameterRegistry.retainComponent(component, binding)
    component
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
