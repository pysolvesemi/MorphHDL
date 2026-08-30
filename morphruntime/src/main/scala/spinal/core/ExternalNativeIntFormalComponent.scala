package spinal.core

/**
  * Generic low-level boundary for a native child component whose ordinary
  * Scala `Int` constructor argument becomes a definition-side Verilog formal.
  *
  * The caller supplies a checked `ParameterizedMemoryDepth`, but this helper
  * does not assume the component owns a memory or expose the scalar as a packed
  * port width. Compiler-proven shadow, structural, value and memory metadata
  * remain authoritative for the child definition.
  */
private[spinal] object ExternalNativeIntFormalComponent {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def parameter[C <: Component](
      actual: ParameterizedMemoryDepth,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(constructor: Int => C): C = {
    if (actual == null) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-ACTUAL-NULL",
        s"formalComponent.parameter '$name' requires one non-null bounded native Int actual",
        None
      )
    }
    val source = actual.sourceLocation
      .orElse(Option(actual.expression).flatMap(_.sourceLocation))
      .filter(_.nonEmpty)
    val callSite = source.getOrElse(s"<native-formal:$name>")

    if (constructor == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-CONSTRUCTOR-NULL",
        s"formalComponent.parameter slot '$name' requires one non-null native constructor",
        Some(callSite)
      )
    }
    if (
      name == null ||
      !PortableIdentifier.pattern.matcher(name).matches()
    ) {
      fail(
        "MORPH-FRONTEND-FORMAL-PARAMETER-NAME-INVALID",
        s"formal parameter name '$name' is not a portable Verilog identifier",
        Some(callSite)
      )
    }
    if (minimum < 1 || maximum < minimum || maximum > BigInt(Int.MaxValue)) {
      fail(
        "MORPH-FRONTEND-FORMAL-PARAMETER-DOMAIN-INVALID",
        s"formal parameter '$name' requires a positive non-empty Int-sized domain, received [$minimum, $maximum]",
        Some(callSite)
      )
    }

    val expression = actual.expression
    if (expression == null) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-ACTUAL-NULL",
        s"formalComponent.parameter '$name' requires one retained native Int expression",
        Some(callSite)
      )
    }
    if (
      expression.generateIndex.nonEmpty ||
      expression.default != BigInt(actual.value) ||
      expression.minimum < 1 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue) ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-GEOMETRY-DOMAIN-INVALID",
        s"formalComponent.parameter '$name' expression '${expression.verilog}' must have witness ${actual.value} and a finite positive Int-sized loop-invariant domain, received default ${expression.default} in [${expression.minimum}, ${expression.maximum}]",
        Some(callSite)
      )
    }
    if (
      expression.minimum < minimum || expression.maximum > maximum ||
      expression.default < minimum || expression.default > maximum
    ) {
      fail(
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-DOMAIN-UNSUPPORTED",
        s"actual expression '${expression.verilog}' in [${expression.minimum}, ${expression.maximum}] with default ${expression.default} is incompatible with formal '$name' in [$minimum, $maximum]",
        Some(callSite)
      )
    }

    val parent = Option(Component.current).getOrElse {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISSING",
        s"formalComponent.parameter slot '$name' must execute inside one active parent Component",
        Some(callSite)
      )
    }
    val formal = ElaborationIntegerParameter(
      name,
      expression.default,
      minimum,
      maximum
    )
    val definitionExpression = ElaborationIntegerExpression(
      verilog = name,
      default = expression.default,
      minimum = minimum,
      maximum = maximum,
      parameters = Vector(formal),
      sourceLocation = Some(callSite),
      parameterRoots = Vector(formal.declarationRoot)
    )
    val token = ExternalNativeIntFormalizationToken(
      callSite = callSite,
      valueOrigin = expression.sourceLocation.getOrElse(callSite),
      role = s"formalComponent.parameter($name)"
    )
    val capture = ExternalNativeIntShadowRegistry.captureWithDefinition(
      expression = expression,
      definitionExpression = definitionExpression,
      token = token,
      argumentName = name
    ) {
      constructor(actual.value)
    }
    val component = capture.result
    if (component == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        s"formalComponent.parameter slot '$name' constructor returned null",
        Some(callSite)
      )
    }

    val ownerClassName = component.getClass.getName
    val binding = ExternalFormalParameterBinding(
      formal = formal,
      actual = expression,
      declarationKey = s"external-native-int::$ownerClassName::$name",
      ownerClassName = ownerClassName,
      sourceLocation = Some(callSite)
    )
    ExternalNativeIntFormalizationRegistry.attachComponentParameter(
      parent = parent,
      component = component,
      binding = binding,
      token = token
    )
    ExternalNativeIntShadowRegistry.attachComponent(
      component = component,
      binding = binding,
      capture = capture
    )
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
